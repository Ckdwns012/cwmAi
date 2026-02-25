// src/main/java/com/example/cwmAi/Controller/aiApiController.java
package com.example.cwmAi.Controller;

import com.example.cwmAi.Service.SemanticCacheService;
import org.springframework.web.bind.annotation.*;

import com.example.cwmAi.Service.aiService;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@RestController // 데이터 반환 전용 컨트롤러
@RequestMapping("/lm/api") // API 요청 경로는 /lm/api/...
public class aiApiController {

    private final aiService aiService;
    private final SemanticCacheService semanticCacheService; // 0224 김소연(수정) - 시맨틱캐싱

    public aiApiController(aiService aiService, SemanticCacheService semanticCacheService ) {
        this.aiService = aiService;
        this.semanticCacheService = semanticCacheService;

    }

    // AI 응답 요청 (POST) - 카테고리별 질문
    @PostMapping("/ask")
    public Mono<String> ask(
            @RequestParam String question,
            @RequestParam(required = false) String category
    ) {
        // aiService의 비동기 작업(Mono<String>)을 그대로 반환합니다.
        // Spring WebFlux가 Mono의 완료 시점에 맞춰 비동기적으로 HTTP 응답을 처리합니다.
        return aiService.askModel(question, category);
    }

    // 0224 김소연(수정) 캐시 확인 전용 엔드포인트
    // cached: true  → answer 필드 반환 → 프론트 즉시 표시
    // cached: false → 기존 stage1 → stage2 진행
    @PostMapping("/ask/cache-check")
    public Mono<Map<String, Object>> checkCache(
            @RequestParam String question,
            @RequestParam(required = false) String category
    ) {
        Optional<String> cached = semanticCacheService.findCachedAnswer(question, category);
        if (cached.isPresent()) {
            System.out.println("[캐시HIT] 즉시 반환: " + question.substring(0, Math.min(40, question.length())));
            return Mono.just(Map.of("cached", true, "answer", cached.get()));
        }
        System.out.println("[캐시MISS] LLM 호출: " + question.substring(0, Math.min(40, question.length())));
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
    @PostMapping("/ask/stage2")
    public Mono<String> askStage2(
            @RequestParam String question,
            @RequestParam String category,
            @RequestBody java.util.List<String> recommendedTitles
    ) {
        return aiService.generateFinalAnswer(question, recommendedTitles, category)
                .doOnSuccess(answer -> {
                    if (answer != null && !answer.isBlank()) {
                        semanticCacheService.cacheAnswer(question, answer, category);
                        System.out.println("[캐시STORE] 저장: " + question.substring(0, Math.min(40, question.length())));
                    }
                });
    }

}
