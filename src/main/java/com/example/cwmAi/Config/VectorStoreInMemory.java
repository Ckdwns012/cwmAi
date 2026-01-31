package com.example.cwmAi.Config;

import org.springframework.stereotype.Component;

import com.example.cwmAi.dto.ai_DTO.chunkDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class VectorStoreInMemory {
    /* =========================
       법령 Chunk 저장소 (In-Memory)
     ========================= */
    private final List<chunkDTO> store = new ArrayList<>();

    /* =========================
       기본 관리 메서드
     ========================= */
    public void addChunk(chunkDTO chunk) {
        store.add(chunk);
    }

    public int getSize() {
        return store.size();
    }

    public void clearChunk() {
        store.clear();
    }

    /**
     * 모든 청크를 조회한다.
     * @return 저장된 모든 청크 리스트
     */
    public List<chunkDTO> getAllChunks() {
        return new ArrayList<>(store);
    }

    /**
     * 카테고리별 청크를 조회한다.
     * @param category 카테고리 (null이면 전체)
     * @return 해당 카테고리의 청크 리스트
     */
    public List<chunkDTO> getChunksByCategory(String category) {
        if (category == null || category.isBlank()) {
            return getAllChunks();
        }
        List<chunkDTO> result = new ArrayList<>();
        for (chunkDTO chunk : store) {
            if (category.equals(chunk.getCategory())) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 조항 이름이 없는 청크를 찾는다.
     * @return 조항 이름이 null이거나 비어있는 청크 리스트
     */
    public List<chunkDTO> findChunksWithoutArticleTitle() {
        List<chunkDTO> result = new ArrayList<>();
        for (chunkDTO chunk : store) {
            if (chunk.getArticleTitle() == null || chunk.getArticleTitle().trim().isEmpty()) {
                result.add(chunk);
            }
        }
        return result;
    }

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
        Map<String, String> articleTitleToChunkId = new HashMap<>();
        Set<String> fileNameSet = null;
        if (fileNames != null && !fileNames.isEmpty()) {
            fileNameSet = new HashSet<>(fileNames);
        }
        
        for (chunkDTO chunk : store) {
            // 카테고리 필터링
            if (category != null && !category.isBlank() && !category.equals(chunk.getCategory())) {
                continue;
            }
            
            // 파일명 필터링
            if (fileNameSet != null && !fileNameSet.contains(chunk.getFileName())) {
                continue;
            }
            
            String articleTitle = chunk.getArticleTitle();
            if (articleTitle != null && !articleTitle.trim().isEmpty()) {
                // 같은 조항 이름이 여러 개 있을 수 있으므로, 첫 번째 매칭만 사용
                if (!articleTitleToChunkId.containsKey(articleTitle)) {
                    articleTitleToChunkId.put(articleTitle, chunk.getChunkId());
                }
            }
        }
        return articleTitleToChunkId;
    }


    /**
     * 조항 이름 리스트로 청크를 조회한다.
     * @param articleTitles 조항 이름 리스트
     * @param category 카테고리 (필터링용)
     * @return 해당 조항 이름에 해당하는 청크 리스트
     */
    public List<chunkDTO> getChunksByArticleTitles(List<String> articleTitles, String category) {
        List<chunkDTO> result = new ArrayList<>();
        Set<String> articleTitleSet = new HashSet<>(articleTitles);
        
        for (chunkDTO chunk : store) {
            // 카테고리 필터링
            if (category != null && !category.isBlank()) {
                if (!category.equals(chunk.getCategory())) {
                    continue;
                }
            }
            
            // 조항 이름 매칭
            String articleTitle = chunk.getArticleTitle();
            if (articleTitle != null && articleTitleSet.contains(articleTitle)) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 청크ID로 청크를 조회한다.
     * @param chunkId 청크ID
     * @return 해당 청크, 없으면 null
     */
    public chunkDTO getChunkById(String chunkId) {
        for (chunkDTO chunk : store) {
            if (chunkId != null && chunkId.equals(chunk.getChunkId())) {
                return chunk;
            }
        }
        return null;
    }

    /**
     * 조항 이름이 없는 청크의 통계 정보를 반환한다.
     * @return 통계 정보 맵
     */
    public Map<String, Object> getChunksWithoutArticleTitleStatistics() {
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
    }

    /**
     * 모든 청크의 필수 필드를 검증하고 문제가 있는 청크를 찾는다.
     * @return 문제가 있는 청크 리스트와 오류 정보
     */
    public Map<String, Object> validateAllChunks() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> invalidChunks = new ArrayList<>();
        
        for (int i = 0; i < store.size(); i++) {
            chunkDTO chunk = store.get(i);
            List<String> errors = new ArrayList<>();
            
            // 법령명 검증
            if (chunk.getLawName() == null || chunk.getLawName().trim().isEmpty() || 
                chunk.getLawName().equals("알 수 없음")) {
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
    }

}

