package com.example.cwmAi.Config;

import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EmbeddingModel 싱글턴 Bean 설정
 *
 * 기존 문제: VectorStoreInMemory, TableStoreInMemory, SemanticCacheService 각각
 *           new AllMiniLmL6V2EmbeddingModel() → ONNX 세션 3개 = ~69MB 중복 + 직렬 큐 3개
 * 개선: @Bean으로 하나만 생성 → 모든 컴포넌트가 동일 인스턴스 공유
 *       메모리 ~46MB 절감, 임베딩 요청이 단일 큐로 통합
 */
@Configuration
public class EmbeddingModelConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        System.out.println("[EmbeddingModel] AllMiniLmL6V2 싱글턴 초기화 (384차원)");
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
