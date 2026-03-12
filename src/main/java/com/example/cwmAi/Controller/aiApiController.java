// src/main/java/com/example/cwmAi/Controller/aiApiController.java
package com.example.cwmAi.Controller;

import com.example.cwmAi.Service.SemanticCacheService;
import org.springframework.web.bind.annotation.*;

import com.example.cwmAi.Service.aiService;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/lm/api")
public class aiApiController {

    private final aiService aiService;
    private final SemanticCacheService semanticCacheService;

    public aiApiController(aiService aiService, SemanticCacheService semanticCacheService) {
        this.aiService = aiService;
        this.semanticCacheService = semanticCacheService;
    }

    // AI 응답 요청 (POST) - 카테고리별 질문
    @PostMapping("/ask")
    public Mono<String> ask(
            @RequestParam String question,
            @RequestParam(required = false) String category
    ) {
        return aiService.askModel(question, category);
    }

    // 캐시 확인 전용 엔드포인트
    // cached: true  → answer 필드 반환 → 프론트 즉시 표시 (O/X 없음, 이미 검증된 캐시)
    // cached: false → stage1 → stage2 진행
    @PostMapping("/ask/cache-check")
    public Mono<Map<String, Object>> checkCache(
            @RequestParam String question,
            @RequestParam(required = false) String category
    ) {
        Optional<String> cached = semanticCacheService.findCachedAnswer(question, category);
        if (cached.isPresent()) {
            return Mono.just(Map.of("cached", true, "answer", cached.get()));
        }
        return Mono.just(Map.of("cached", false));
    }

    // 1단계: 관련 조항 추천 (POST)
    @PostMapping("/ask/stage1")
    public Mono<java.util.List<String>> askStage1(
            @RequestParam String question,
            @RequestParam(required = false) String category,
            @RequestBody(required = false) java.util.Map<String, Object> requestBody
    ) {
        java.util.List<String> files = null;
        if (requestBody != null && requestBody.containsKey("files")) {
            @SuppressWarnings("unchecked")
            java.util.List<String> filesList = (java.util.List<String>) requestBody.get("files");
            files = filesList;
        }
        return aiService.recommendArticleTitles(question, category, files);
    }

    // 2단계: 최종 답변 생성 (POST)
    // 변경: 자동 캐시 저장 제거 → pendingKey 반환 → 사용자 O/X 후 /feedback으로 결정
    @PostMapping("/ask/stage2")
    public Mono<Map<String, Object>> askStage2(
            @RequestParam String question,
            @RequestParam String category,
            @RequestBody java.util.List<String> recommendedTitles
    ) {
        return aiService.generateFinalAnswer(question, recommendedTitles, category)
                .map(answer -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("answer", answer);
                    // 임시 저장 후 pendingKey 반환 (프론트에서 O/X 버튼에 사용)
                    if (answer != null && !answer.isBlank()) {
                        String pendingKey = semanticCacheService.cachePending(question, answer, category);
                        result.put("pendingKey", pendingKey);
                    }
                    return result;
                });
    }

    /** 디버그: 카테고리 내 전체 청크 목록 (파일명·타입·조항제목) */
    @GetMapping("/debug/chunks")
    @ResponseBody
    public java.util.List<java.util.Map<String, String>> debugChunks(
            @RequestParam(required = false) String category
    ) {
        return aiService.getChunkTitlesForDebug(category);
    }

    /**
     * O/X 피드백 엔드포인트
     *
     * O(approved=true) : pendingCache → 실제 캐시로 저장
     * X(approved=false): pendingCache에서 제거 (저장 안 함)
     *
     * 프론트에서 답변 표시 후 O/X 버튼 클릭 시 호출
     */
    @PostMapping("/feedback")
    public Mono<Map<String, String>> feedback(
            @RequestParam String pendingKey,
            @RequestParam boolean approved
    ) {
        if (approved) {
            semanticCacheService.approvePending(pendingKey);
            return Mono.just(Map.of("status", "approved"));
        } else {
            semanticCacheService.rejectPending(pendingKey);
            return Mono.just(Map.of("status", "rejected"));
        }
    }

}
