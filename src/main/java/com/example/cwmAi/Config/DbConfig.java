package com.example.cwmAi.Config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * config.txt에 DB_URL이 있을 때만 활성화. DataSource 생성 + 로그인을 DB(member 테이블)로 수행.
 * DB_URL 없으면 이 설정이 로드되지 않아 DataSource 미생성(인메모리 로그인).
 */
@Configuration
@ConditionalOnProperty(name = "DB_URL", matchIfMissing = false)
@Import(DataSourceAutoConfiguration.class)
@MapperScan("com.example.cwmAi.Repository")
public class DbConfig {
}
