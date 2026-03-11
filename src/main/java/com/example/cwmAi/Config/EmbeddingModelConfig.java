package com.example.cwmAi.Config;

import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * EmbeddingModel 싱글턴 Bean 설정
 *
 * [교체 이력]
 * v1: AllMiniLmL6V2 (영어 중심, 384차원, 로컬 내장) — CPU 환경에서 가장 빠름
 * v2: nomic-embed-text (다국어 768차원) — 실험 결과 AllMiniLmL6V2 대비 효과 미비
 * v3: bge-m3 (다국어 1024차원, Ollama) — GPU 환경에서 성능 우수, CPU에서 모델 스왑으로 느림
 * → CPU 환경에서는 v1 복귀 (인메모리 인퍼런스, Ollama 스왑 없음)
 */
@Configuration
public class EmbeddingModelConfig {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";

    @Bean
    public EmbeddingModel embeddingModel() {
        // ── v3: bge-m3 (OLLAMA_MAX_LOADED_MODELS=2 설정, 한국어 검색 품질 향상) ──
        // 1024차원, 다국어 지원, AllMiniLmL6V2 대비 한국어 법령 도메인 검색 정확도 향상
        // 전제: 환경변수 OLLAMA_MAX_LOADED_MODELS=2 → qwen3와 동시 로딩으로 스왑 없음
        System.out.println("[EmbeddingModel] bge-m3 사용 (1024차원, Ollama) — OLLAMA_MAX_LOADED_MODELS=2 적용");
        return OllamaEmbeddingModel.builder()
                .baseUrl(OLLAMA_BASE_URL)
                .modelName("bge-m3")
                .timeout(Duration.ofSeconds(60))
                .build();

        // ── v1: AllMiniLmL6V2 (인메모리 fallback) ────────────────────────────
        // OLLAMA_MAX_LOADED_MODELS=2 미설정 환경에서 모델 스왑 문제 발생 시 복귀용
        // System.out.println("[EmbeddingModel] AllMiniLmL6V2 사용 (384차원, 인메모리)");
        // return new AllMiniLmL6V2EmbeddingModel();
    }
}
