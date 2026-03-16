package com.example.cwmAi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import com.example.cwmAi.Config.ConfigLoader;
import com.example.cwmAi.Service.aiService;

import java.util.Map;

// DataSource는 DB_URL 있을 때만 DataSourceAutoConfigurationImportSelector에서 로드함 (기본은 비활성화)
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AiProjectApplication implements ApplicationListener<ApplicationReadyEvent> {

	public static void main(String[] args) {
		// 현재 작업 디렉터리(user.dir)의 config.txt 로드 (상대 경로)
		System.out.println("[cwmAi] user.dir = " + System.getProperty("user.dir"));
		Map<String, String> config = ConfigLoader.load();
		System.out.println("[cwmAi] loaded config.txt entries = " + config.keySet());
		for (Map.Entry<String, String> e : config.entrySet()) {
			System.setProperty(e.getKey(), e.getValue() != null ? e.getValue() : "");
		}
		String dbUrl = config.get("DB_URL");
		if (dbUrl != null && !dbUrl.isBlank()) {
			// DB 사용 시에만 datasource 시스템 프로퍼티 설정 (DataSourceConfig에서 사용)
			System.setProperty("spring.datasource.url", dbUrl);
			System.setProperty("spring.datasource.username", config.getOrDefault("DB_USERNAME", ""));
			System.setProperty("spring.datasource.password", config.getOrDefault("DB_PASSWORD", ""));
			System.setProperty("spring.datasource.driver-class-name", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
		}

		SpringApplication.run(AiProjectApplication.class, args);
	}

	/**
	 * 서버 기동이 모두 완료된 뒤 한 번만 호출되어
	 * 업로드된 모든 문서를 청킹하고 메모리 저장소에 적재한다.
	 */
	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		aiService aiService = event.getApplicationContext().getBean(aiService.class);
		aiService.loadAllDocuments();
	}
}

