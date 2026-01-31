package com.example.cwmAi;

//import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import com.example.cwmAi.Service.aiService;

//@MapperScan("com.example.cwmAi.Repository") // loginMapper 패키지
@SpringBootApplication
public class AiProjectApplication implements ApplicationListener<ApplicationReadyEvent> {

	public static void main(String[] args) {
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

