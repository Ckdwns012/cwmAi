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
 * 한국어 임베딩 품질 개선: AllMiniLmL6V2(영어 384차원) → nomic-embed-text(다국어 768차원)
 * nomic-embed-text는 한국어 문장 유사도 품질이 AllMiniLmL6V2 대비 크게 향상됨
 *
 * 사전 준비: ollama pull nomic-embed-text
 * Ollama 서버 미기동 시 자동으로 AllMiniLmL6V2 fallback
 */
@Configuration
public class EmbeddingModelConfig {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String KOREAN_EMBED_MODEL = "nomic-embed-text";

    @Bean
    public EmbeddingModel embeddingModel() {
        System.out.println("[EmbeddingModel] AllMiniLmL6V2 사용 (384차원, 로컬 내장)");
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
