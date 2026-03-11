package com.example.cwmAi.Service;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.segment.TextSegment;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    /**
     * 캐시 히트 기준 코사인 유사도
     * 0.78 → 0.90 으로 상향: "검증 신청"과 "재검증 신청" 같은 유사 표현 오히트 방지
     */
    private static final float SIMILARITY_THRESHOLD = 0.90f;

    /**
     * Jaccard 토큰 유사도 최소값
     * 코사인 유사도가 SIMILARITY_THRESHOLD 이상이어도 Jaccard < 0.65면 캐시 미스 처리
     * → "보안적합성 검증 신청" vs "보안적합성 재검증 신청" 구분
     */
    private static final float JACCARD_MIN = 0.65f;

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
        final float[]      embedding;   // 384차원 벡터
        final String       answer;      // 캐시된 LLM 답변
        final String       category;    // 카테고리 (필터용)
        final String       questionKey; // 원본 질문 (디버깅용)
        final Instant      createdAt;   // 생성 시각
        final Set<String>  sourceFiles; // 답변 생성에 사용된 파일명 목록
        volatile int       hitCount;    // 캐시 히트 횟수

        CacheEntry(float[] embedding, String answer, String category, String questionKey, Set<String> sourceFiles) {
            this.embedding   = embedding;
            this.answer      = answer;
            this.category    = category;
            this.questionKey = questionKey;
            this.createdAt   = Instant.now();
            this.sourceFiles = sourceFiles != null ? sourceFiles : Collections.emptySet();
            this.hitCount    = 0;
        }

        boolean isExpired() {
            return Instant.now().getEpochSecond() - createdAt.getEpochSecond() > TTL_SECONDS;
        }
    }

    /** 캐시 저장소: UUID → CacheEntry */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 임시 저장소: 사용자 O/X 확인 대기 중인 항목 (pendingKey → CacheEntry) */
    private final ConcurrentHashMap<String, CacheEntry> pendingCache = new ConcurrentHashMap<>();

    // EmbeddingModelConfig에서 주입된 싱글턴 사용
    public SemanticCacheService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        System.out.println("[SemanticCache] 임베딩 모델 주입 완료 (싱글턴)");
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
            // 2차 검증: Jaccard 토큰 유사도
            // 코사인 유사도가 높아도 핵심 단어가 다르면(예: "검증"≠"재검증") 미스 처리
            float jaccard = jaccardSimilarity(question, best.questionKey);
            if (jaccard >= JACCARD_MIN) {
                best.hitCount++;
                System.out.printf("[SemanticCache] HIT  코사인=%.4f  Jaccard=%.4f  히트수=%d  질문='%s'  원본='%s'%n",
                        bestScore, jaccard, best.hitCount,
                        truncate(question, 40), truncate(best.questionKey, 40));
                return Optional.of(best.answer);
            }
            System.out.printf("[SemanticCache] MISS(Jaccard 미달) 코사인=%.4f  Jaccard=%.4f(기준=%.2f)  질문='%s'%n",
                    bestScore, jaccard, JACCARD_MIN, truncate(question, 40));
            return Optional.empty();
        }

        System.out.printf("[SemanticCache] MISS 최고유사도=%.4f  질문='%s'%n",
                bestScore, truncate(question, 40));
        return Optional.empty();
    }

    /**
     * 답변 텍스트의 "[참조 파일: ...]" 섹션에서 파일명을 파싱한다.
     * generateFinalAnswer()가 답변 끝에 붙여놓은 정보를 역으로 복원
     */
    private Set<String> parseSourceFilesFromAnswer(String answer) {
        if (answer == null) return Collections.emptySet();
        int idx = answer.lastIndexOf("[참조 파일:");
        if (idx < 0) return Collections.emptySet();
        int end = answer.indexOf("]", idx);
        if (end < 0) return Collections.emptySet();
        String files = answer.substring(idx + "[참조 파일:".length(), end).trim();
        Set<String> result = new LinkedHashSet<>();
        for (String f : files.split(",")) {
            String t = f.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    // 기존 호출부 하위 호환 (sourceFiles를 답변 텍스트에서 파싱해서 위임)
    public void cacheAnswer(String question, String answer, String category) {
        cacheAnswer(question, answer, category, parseSourceFilesFromAnswer(answer));
    }

    public String cachePending(String question, String answer, String category) {
        return cachePending(question, answer, category, parseSourceFilesFromAnswer(answer));
    }

    /**
     * LLM 답변을 캐시에 저장한다.
     *
     * @param question 사용자 질문
     * @param answer   LLM이 생성한 답변
     * @param category 카테고리
     */
    public void cacheAnswer(String question, String answer, String category, Set<String> sourceFiles) {
        if (question == null || question.isBlank()) return;
        if (answer   == null || answer.isBlank())   return;

        if (cache.size() >= MAX_CACHE_SIZE) {
            evictOldest();
        }

        float[] embedding = embed(question);
        if (embedding == null) return;

        String key = UUID.randomUUID().toString();
        cache.put(key, new CacheEntry(embedding, answer, category, question, sourceFiles));

        System.out.printf("[SemanticCache] STORE 캐시항목수=%d  질문='%s'  참조파일=%s%n",
                cache.size(), truncate(question, 40), sourceFiles);
    }

    /**
     * 답변을 임시 저장하고 pendingKey를 반환한다.
     * 사용자가 O(승인) 버튼을 누르기 전까지 실제 캐시에 저장되지 않는다.
     *
     * @param question 사용자 질문
     * @param answer   LLM 답변
     * @param category 카테고리
     * @return pendingKey (프론트에서 O/X 요청 시 사용)
     */
    public String cachePending(String question, String answer, String category, Set<String> sourceFiles) {
        if (question == null || question.isBlank()) return null;
        if (answer   == null || answer.isBlank())   return null;

        float[] embedding = embed(question);
        if (embedding == null) return null;

        String pendingKey = UUID.randomUUID().toString();
        pendingCache.put(pendingKey, new CacheEntry(embedding, answer, category, question, sourceFiles));

        System.out.printf("[SemanticCache] PENDING key=%s  질문='%s'%n",
                pendingKey.substring(0, 8), truncate(question, 40));
        return pendingKey;
    }

    /**
     * O 버튼: 임시 캐시 → 실제 캐시로 승인
     *
     * @param pendingKey cachePending()이 반환한 키
     */
    public void approvePending(String pendingKey) {
        if (pendingKey == null) return;
        CacheEntry entry = pendingCache.remove(pendingKey);
        if (entry == null) {
            System.out.printf("[SemanticCache] APPROVE 실패 - 키 없음: %s%n", pendingKey.substring(0, 8));
            return;
        }
        if (cache.size() >= MAX_CACHE_SIZE) evictOldest();
        cache.put(UUID.randomUUID().toString(), entry);
        System.out.printf("[SemanticCache] APPROVED → 캐시저장  key=%s  질문='%s'%n",
                pendingKey.substring(0, 8), truncate(entry.questionKey, 40));
    }

    /**
     * X 버튼: 임시 캐시에서 제거 (저장하지 않음)
     *
     * @param pendingKey cachePending()이 반환한 키
     */
    public void rejectPending(String pendingKey) {
        if (pendingKey == null) return;
        CacheEntry removed = pendingCache.remove(pendingKey);
        if (removed != null) {
            System.out.printf("[SemanticCache] REJECTED  key=%s  질문='%s'%n",
                    pendingKey.substring(0, 8), truncate(removed.questionKey, 40));
        }
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
     * 특정 파일을 참조한 캐시 항목만 무효화한다.
     * 파일 삭제 시 카테고리 전체 대신 해당 파일 관련 항목만 제거 → 다른 파일 캐시 보존
     *
     * @param fileName 삭제된 파일명
     * @param category 카테고리
     */
    public void invalidateCacheByFile(String fileName, String category) {
        if (fileName == null || fileName.isBlank()) return;
        String normalized = normalizeCategory(category);
        int before = cache.size();

        cache.entrySet().removeIf(e -> {
            CacheEntry ce = e.getValue();
            return normalizeCategory(ce.category).equals(normalized)
                    && ce.sourceFiles.contains(fileName);
        });

        int removed = before - cache.size();
        System.out.printf("[SemanticCache] INVALIDATE_BY_FILE file='%s' category='%s'  제거=%d  남은=%d%n",
                fileName, category, removed, cache.size());
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

    /**
     * Jaccard 토큰 유사도 계산 (0 ~ 1)
     * 두 질문의 단어 집합 교집합 / 합집합 비율
     * "검증 신청" vs "재검증 신청" → 교집합{신청}/합집합{검증,재검증,신청} = 0.33 → MISS
     * "검증 신청 알려줘" vs "검증 신청 뭐야" → 교집합{검증,신청}/합집합{검증,신청,알려줘,뭐야} = 0.50 → 판단 필요
     */
    private float jaccardSimilarity(String a, String b) {
        Set<String> ta = tokenize(a);
        Set<String> tb = tokenize(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0f;

        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);

        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);

        return (float) intersection.size() / union.size();
    }

    /** 문자열을 2자 이상 한글/영문/숫자 토큰 집합으로 분리 */
    private Set<String> tokenize(String s) {
        if (s == null || s.isBlank()) return Collections.emptySet();
        return Arrays.stream(s.replaceAll("[^가-힣a-zA-Z0-9]", " ").split("\\s+"))
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toSet());
    }
}