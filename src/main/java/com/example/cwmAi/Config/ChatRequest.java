package com.example.cwmAi.Config;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

import com.example.cwmAi.dto.ai_DTO.messageDTO;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String model;
    private List<messageDTO> messages;

    // Ollama API 필드명
    // 최대 생성 길이 제한 (Ollama는 num_predict 사용)
    @com.fasterxml.jackson.annotation.JsonProperty("num_predict")
    private Integer num_predict = 1024;

    // 출력 다양성 조절 (담백한 응답을 위해 0.2로 설정)
    private Double temperature = 0.2;

    // 고려할 단어 확률 범위 조절
    @com.fasterxml.jackson.annotation.JsonProperty("min_p")
    private Double min_p = 0.1;

    // 스트리밍 사용 여부
    private Boolean stream = false;

    // 사용자 편의를 위한 오버로드된 생성자 (기본값 사용)
    public ChatRequest(String model, List<messageDTO> messages) {
        this.model = model;
        this.messages = messages;
    }
}
