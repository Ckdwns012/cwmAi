package com.example.cwmAi.dto.ai_DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class aiResponseDTO {
    private String stage;  // "stage1", "stage2", "completed"
    private String statusMessage;  // 상태 메시지
    private List<String> recommendedArticles;  // 1단계에서 추천받은 조항 이름 목록
    private String finalAnswer;  // 최종 답변 (stage가 "completed"일 때만)
}

