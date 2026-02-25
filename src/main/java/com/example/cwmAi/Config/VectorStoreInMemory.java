package com.example.cwmAi.Config;

import org.springframework.stereotype.Component;

import com.example.cwmAi.dto.ai_DTO.chunkDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class VectorStoreInMemory {
    /* =========================
       법령 Chunk 저장소 (In-Memory)
     ========================= */
    private final List<chunkDTO> store = new ArrayList<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    /* =========================
       기본 관리 메서드
     ========================= */
    public void addChunk(chunkDTO chunk) {
        rwLock.writeLock().lock();
        try {
            store.add(chunk);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int getSize() {
        rwLock.readLock().lock();
        try {
            return store.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void clearChunk() {
        rwLock.writeLock().lock();
        try {
            store.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 모든 청크를 조회한다.
     * @return 저장된 모든 청크 리스트
     */
    public List<chunkDTO> getAllChunks() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(store);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** 전체 스토어를 한 번에 교체(원자적). */
    public void replaceAll(List<chunkDTO> newChunks) {
        rwLock.writeLock().lock();
        try {
            store.clear();
            store.addAll(newChunks);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 특정 카테고리의 청크만 제거. (category가 null/blank면 최상위(빈카테고리)로 처리) */
    public void removeCategory(String category) {
        String normalized = normalizeCategory(category);
        rwLock.writeLock().lock();
        try {
            store.removeIf(c -> normalizeCategory(c.getCategory()).equals(normalized));
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 카테고리별 청크를 조회한다.
     * @param category 카테고리 (null이면 전체)
     * @return 해당 카테고리의 청크 리스트
     */
    public List<chunkDTO> getChunksByCategory(String category) {
        if (category == null || category.isBlank()) return getAllChunks();
        rwLock.readLock().lock();
        try {
            List<chunkDTO> result = new ArrayList<>();
            for (chunkDTO chunk : store) {
                if (category.equals(chunk.getCategory())) result.add(chunk);
            }
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 조항 이름이 없는 청크를 찾는다.
     * @return 조항 이름이 null이거나 비어있는 청크 리스트
     */
    public List<chunkDTO> findChunksWithoutArticleTitle() {
        rwLock.readLock().lock();
        try {
            List<chunkDTO> result = new ArrayList<>();
            for (chunkDTO chunk : store) {
                if (chunk.getArticleTitle() == null || chunk.getArticleTitle().trim().isEmpty()) {
                    result.add(chunk);
                }
            }
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }
    /**
     * 특정 카테고리 청크를 "교체" (기존 카테고리 청크 제거 후, 새 청크 add).
     * ※ writeLock을 한 번만 잡아서 중간 상태 노출 방지
     */
    public void replaceCategory(String category, List<chunkDTO> newChunks) {
        String normalized = normalizeCategory(category);
        rwLock.writeLock().lock();
        try {
            store.removeIf(c -> normalizeCategory(c.getCategory()).equals(normalized));
            store.addAll(newChunks);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private String normalizeCategory(String category) {
        if (category == null) return "";
        String c = category.trim();
        return c.isEmpty() ? "" : c;
    }

    // ===== 아래는 기존 조회 로직들(기존 코드 유지) =====
    /**
     * 카테고리별 청크의 조항 이름 목록을 조회한다.
     * @param category 카테고리
     * @return 조항 이름과 청크ID를 매핑한 맵 (조항 이름 -> 청크ID)
     */
    public Map<String, String> getArticleTitlesByCategory(String category) {
        return getArticleTitlesByCategoryAndFiles(category, null);
    }
    
    /**
     * 카테고리와 파일명 리스트로 필터링하여 조항 이름 목록을 조회한다.
     * @param category 카테고리
     * @param fileNames 파일명 리스트 (null이면 모든 파일)
     * @return 조항 이름과 청크ID를 매핑한 맵 (조항 이름 -> 청크ID)
     */
    public Map<String, String> getArticleTitlesByCategoryAndFiles(String category, List<String> fileNames) {
        rwLock.readLock().lock();
        try {
            Map<String, String> articleTitleToChunkId = new HashMap<>();
            Set<String> fileNameSet = (fileNames != null && !fileNames.isEmpty()) ? new HashSet<>(fileNames) : null;

            for (chunkDTO chunk : store) {
                if (category != null && !category.isBlank() && !category.equals(chunk.getCategory())) continue;
                if (fileNameSet != null && !fileNameSet.contains(chunk.getFileName())) continue;

                String articleTitle = chunk.getArticleTitle();
                if (articleTitle != null && !articleTitle.trim().isEmpty()) {
                    if (!articleTitleToChunkId.containsKey(articleTitle)) {
                        articleTitleToChunkId.put(articleTitle, chunk.getChunkId());
                    }
                }
            }
            return articleTitleToChunkId;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 조항 이름 리스트로 청크를 조회한다.
     * @param articleTitles 조항 이름 리스트
     * @param category 카테고리 (필터링용)
     * @return 해당 조항 이름에 해당하는 청크 리스트
     */
    public List<chunkDTO> getChunksByArticleTitles(List<String> articleTitles, String category) {
        rwLock.readLock().lock();
        try {
            List<chunkDTO> result = new ArrayList<>();
            Set<String> articleTitleSet = new HashSet<>(articleTitles);

            for (chunkDTO chunk : store) {
                if (category != null && !category.isBlank() && !category.equals(chunk.getCategory())) continue;
                String articleTitle = chunk.getArticleTitle();
                if (articleTitle != null && articleTitleSet.contains(articleTitle)) result.add(chunk);
            }
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 청크ID로 청크를 조회한다.
     * @param chunkId 청크ID
     * @return 해당 청크, 없으면 null
     */
    public chunkDTO getChunkById(String chunkId) {
        rwLock.readLock().lock();
        try {
            for (chunkDTO chunk : store) {
                if (chunkId != null && chunkId.equals(chunk.getChunkId())) return chunk;
            }
            return null;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 조항 이름이 없는 청크의 통계 정보를 반환한다.
     * @return 통계 정보 맵
     */
    public Map<String, Object> getChunksWithoutArticleTitleStatistics() {
        rwLock.readLock().lock();
        try {
            Map<String, Object> stats = new HashMap<>();
            List<chunkDTO> chunksWithoutTitle = findChunksWithoutArticleTitle();

            stats.put("totalChunks", store.size());
            stats.put("chunksWithoutTitle", chunksWithoutTitle.size());
            stats.put("chunksWithTitle", store.size() - chunksWithoutTitle.size());

            // 조항 이름이 없는 청크의 상세 정보
            List<Map<String, Object>> details = new ArrayList<>();
            for (chunkDTO chunk : chunksWithoutTitle) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("lawName", chunk.getLawName());
                detail.put("articleNumber", chunk.getArticleNumber());
                detail.put("fileName", chunk.getFileName());
                detail.put("category", chunk.getCategory());
                detail.put("chunkIndex", chunk.getChunkIndex());
                detail.put("textPreview", chunk.getText().length() > 100
                        ? chunk.getText().substring(0, 100) + "..."
                        : chunk.getText());
                details.add(detail);
            }
            stats.put("details", details);
            return stats;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 모든 청크의 필수 필드를 검증하고 문제가 있는 청크를 찾는다.
     * @return 문제가 있는 청크 리스트와 오류 정보
     */
    public Map<String, Object> validateAllChunks() {
        rwLock.readLock().lock();
        try {
            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> invalidChunks = new ArrayList<>();

            for (int i = 0; i < store.size(); i++) {
                chunkDTO chunk = store.get(i);
                List<String> errors = new ArrayList<>();

                // 법령명 검증
                if (chunk.getLawName() == null || chunk.getLawName().trim().isEmpty() || chunk.getLawName().equals("알 수 없음")) {
                    errors.add("법령명이 없거나 유효하지 않음: " + chunk.getLawName());
                }
                // 조항 번호 검증
                if (chunk.getArticleNumber() == null || chunk.getArticleNumber().trim().isEmpty()) {
                    errors.add("조항 번호가 없음");
                } else if (!chunk.getArticleNumber().matches("제\\s*\\d+(?:조(?:의\\s*\\d+)?)?")) {
                    errors.add("조항 번호 형식이 올바르지 않음: " + chunk.getArticleNumber());
                }
                // 조항 이름 검증
                if (chunk.getArticleTitle() == null || chunk.getArticleTitle().trim().isEmpty()) {
                    errors.add("조항 이름이 없음");
                }
                // 조항 내용 검증
                if (chunk.getText() == null || chunk.getText().trim().isEmpty()) {
                    errors.add("조항 내용이 없음");
                } else if (chunk.getText().trim().length() < 10) {
                    errors.add("조항 내용이 너무 짧음 (10자 미만): " + chunk.getText().trim().length() + "자");
                }
                // 파일명 검증
                if (chunk.getFileName() == null || chunk.getFileName().trim().isEmpty()) {
                    errors.add("파일명이 없음");
                }

                if (!errors.isEmpty()) {
                    Map<String, Object> invalidChunk = new HashMap<>();
                    invalidChunk.put("index", i);
                    invalidChunk.put("lawName", chunk.getLawName());
                    invalidChunk.put("articleNumber", chunk.getArticleNumber());
                    invalidChunk.put("articleTitle", chunk.getArticleTitle());
                    invalidChunk.put("fileName", chunk.getFileName());
                    invalidChunk.put("category", chunk.getCategory());
                    invalidChunk.put("chunkIndex", chunk.getChunkIndex());
                    invalidChunk.put("textLength", chunk.getText() != null ? chunk.getText().length() : 0);
                    invalidChunk.put("errors", errors);
                    invalidChunks.add(invalidChunk);
                }
            }

            result.put("totalChunks", store.size());
            result.put("validChunks", store.size() - invalidChunks.size());
            result.put("invalidChunks", invalidChunks.size());
            result.put("invalidChunkDetails", invalidChunks);
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
