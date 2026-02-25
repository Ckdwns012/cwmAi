package com.example.cwmAi.Service;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시맨틱 캐싱 - LLM 비용을 줄이는데 도움을 주는 캐싱 기술을 참조하였습니다 !
 *
 * 동작 원리:
 *   질문 → 임베딩 벡터(384차원) 생성 → 캐시 내 벡터들과 코사인 유사도 비교
 *   유사도 > threshold → 캐시 히트 → LLM 호출 없이 즉시 반환
 *   유사도 < threshold → 캐시 미스 → LLM 호출 후 결과 캐시에 저장
 *
 * DB 구현 후에는 ConcurrentHashMap → MySQL semantic_cache 테이블로 교체 예정
 *
 * 작성자: 김소연
 * 작성일: 2025-02-24
 */
@Service
public class SemanticCacheService {

    // ── 설정값 ──────────────────────────────────────────────────
    /** 캐시 히트 기준 코사인 유사도 (0.90 이상이면 동일 질문으로 간주) */
    private static final float SIMILARITY_THRESHOLD = 0.78f;

    /** 캐시 TTL: 30일 (초 단위) */
    private static final long TTL_SECONDS = 30L * 24 * 60 * 60;

    /** 캐시 최대 항목 수 (메모리 보호용) */
    private static final int MAX_CACHE_SIZE = 500;
    // ────────────────────────────────────────────────────────────

    private final EmbeddingModel embeddingModel;

    /**
     * 캐시 항목: 임베딩 벡터 + 답변 + 메타정보
     */
    private static class CacheEntry {
        final float[]  embedding;   // 384차원 벡터
        final String   answer;      // 캐시된 LLM 답변
        final String   category;    // 카테고리 (필터용)
        final String   questionKey; // 원본 질문 (디버깅용)
        final Instant  createdAt;   // 생성 시각
        volatile int   hitCount;    // 캐시 히트 횟수

        CacheEntry(float[] embedding, String answer, String category, String questionKey) {
            this.embedding   = embedding;
            this.answer      = answer;
            this.category    = category;
            this.questionKey = questionKey;
            this.createdAt   = Instant.now();
            this.hitCount    = 0;
        }

        boolean isExpired() {
            return Instant.now().getEpochSecond() - createdAt.getEpochSecond() > TTL_SECONDS;
        }
    }

    /** 캐시 저장소: UUID → CacheEntry */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SemanticCacheService() {
        // AllMiniLmL6V2: 384차원, 약 23MB, CPU 전용 ONNX 모델
        // langchain4j-embeddings-all-minilm-l6-v2 의존성 필요
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        System.out.println("[SemanticCache] 임베딩 모델 초기화 완료 (AllMiniLmL6V2, 384차원)");
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * 캐시에서 유사한 질문의 답변을 검색한다.
     *
     * @param question 사용자 질문
     * @param category 카테고리 (null이면 전체 검색)
     * @return 캐시 히트 시 Optional(답변), 미스 시 Optional.empty()
     */
    public Optional<String> findCachedAnswer(String question, String category) {
        if (question == null || question.isBlank()) return Optional.empty();

        float[] queryEmbedding = embed(question);
        if (queryEmbedding == null) return Optional.empty();

        String normalizedCategory = normalizeCategory(category);

        CacheEntry best      = null;
        float      bestScore = 0f;
        String     bestKey   = null;

        // 만료 항목 수집
        List<String> expiredKeys = new ArrayList<>();

        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            CacheEntry ce = entry.getValue();

            // TTL 만료 체크
            if (ce.isExpired()) {
                expiredKeys.add(entry.getKey());
                continue;
            }

            // 카테고리 필터 (category가 지정된 경우에만)
            if (!normalizedCategory.isEmpty() &&
                    !normalizedCategory.equals(normalizeCategory(ce.category))) {
                continue;
            }

            float score = cosineSimilarity(queryEmbedding, ce.embedding);
            if (score > bestScore) {
                bestScore = score;
                best      = ce;
                bestKey   = entry.getKey();
            }
        }

        // 만료 항목 정리
        expiredKeys.forEach(cache::remove);

        if (best != null && bestScore >= SIMILARITY_THRESHOLD) {
            best.hitCount++;
            System.out.printf("[SemanticCache] HIT  유사도=%.4f  히트수=%d  질문='%s'  원본질문='%s'%n",
                    bestScore, best.hitCount,
                    truncate(question, 40), truncate(best.questionKey, 40));
            return Optional.of(best.answer);
        }

        System.out.printf("[SemanticCache] MISS 최고유사도=%.4f  질문='%s'%n",
                bestScore, truncate(question, 40));
        return Optional.empty();
    }

    /**
     * LLM 답변을 캐시에 저장한다.
     *
     * @param question 사용자 질문
     * @param answer   LLM이 생성한 답변
     * @param category 카테고리
     */
    public void cacheAnswer(String question, String answer, String category) {
        if (question == null || question.isBlank()) return;
        if (answer   == null || answer.isBlank())   return;

        // 캐시 크기 초과 시 오래된 항목 제거
        if (cache.size() >= MAX_CACHE_SIZE) {
            evictOldest();
        }

        float[] embedding = embed(question);
        if (embedding == null) return;

        String key = UUID.randomUUID().toString();
        cache.put(key, new CacheEntry(embedding, answer, category, question));

        System.out.printf("[SemanticCache] STORE 캐시항목수=%d  질문='%s'%n",
                cache.size(), truncate(question, 40));
    }

    /**
     * 특정 카테고리의 캐시를 무효화한다.
     * (문서 업로드/삭제/재로딩 시 호출)
     *
     * @param category 무효화할 카테고리
     */
    public void invalidateCache(String category) {
        if (category == null || category.isBlank()) return;

        String normalized = normalizeCategory(category);
        int before = cache.size();

        cache.entrySet().removeIf(e ->
                normalizeCategory(e.getValue().category).equals(normalized));

        int removed = before - cache.size();
        System.out.printf("[SemanticCache] INVALIDATE category='%s'  제거항목=%d  남은항목=%d%n",
                category, removed, cache.size());
    }

    /**
     * 전체 캐시를 비운다.
     */
    public void clearAll() {
        int size = cache.size();
        cache.clear();
        System.out.println("[SemanticCache] CLEAR ALL  제거항목=" + size);
    }

    /**
     * 현재 캐시 통계를 반환한다.
     */
    public Map<String, Object> getStats() {
        int totalHits = cache.values().stream().mapToInt(e -> e.hitCount).sum();
        long expired  = cache.values().stream().filter(CacheEntry::isExpired).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalEntries",   cache.size());
        stats.put("totalHits",      totalHits);
        stats.put("expiredEntries", expired);
        stats.put("threshold",      SIMILARITY_THRESHOLD);
        stats.put("ttlDays",        TTL_SECONDS / 86400);
        return stats;
    }

    // ── Private helpers ──────────────────────────────────────────

    /** 텍스트를 384차원 float[] 벡터로 변환 */
    private float[] embed(String text) {
        try {
            List<Float> vector = embeddingModel
                    .embed(TextSegment.from(text))
                    .content()
                    .vectorAsList();

            float[] arr = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                arr[i] = vector.get(i);
            }
            return arr;
        } catch (Exception e) {
            System.err.println("[SemanticCache] 임베딩 실패: " + e.getMessage());
            return null;
        }
    }

    /** 코사인 유사도 계산 (0 ~ 1) */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;

        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }

        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        if (denom < 1e-10) return 0f;
        return (float) (dot / denom);
    }

    /** 가장 오래된 캐시 항목 제거 (LRU 근사) */
    private void evictOldest() {
        cache.entrySet().stream()
                .filter(e -> e.getValue().isExpired())
                .map(Map.Entry::getKey)
                .forEach(cache::remove);

        // 만료 항목 제거 후에도 아직 꽉 찬 경우 → 가장 오래된 10% 제거
        if (cache.size() >= MAX_CACHE_SIZE) {
            int toRemove = MAX_CACHE_SIZE / 10;
            cache.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getValue().createdAt))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .forEach(cache::remove);
        }
    }

    private String normalizeCategory(String s) {
        return s == null ? "" : s.trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}