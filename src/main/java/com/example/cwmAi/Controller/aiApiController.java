// src/main/java/com/example/cwmAi/Controller/aiApiController.java
package com.example.cwmAi.Controller;

import org.springframework.web.bind.annotation.*;

import com.example.cwmAi.Service.aiService;

import reactor.core.publisher.Mono;

@RestController // 데이터 반환 전용 컨트롤러
@RequestMapping("/lm/api") // API 요청 경로는 /lm/api/...
public class aiApiController {

    private final aiService aiService;

    public aiApiController(aiService aiService) {
        this.aiService = aiService;
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
        return aiService.generateFinalAnswer(question, recommendedTitles, category);
    }

}
