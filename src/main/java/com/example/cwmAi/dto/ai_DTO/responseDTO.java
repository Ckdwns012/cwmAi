package com.example.cwmAi.dto.ai_DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Ollama API 응답 DTO
// Ollama 응답 구조:
// {
//   "model": "qwen3:4b-q4_K_M",
//   "created_at": "2025-12-20T01:14:48Z",
//   "message": {
//     "role": "assistant",
//     "content": "최종 답변 텍스트",
//     "thinking": "내부 추론 과정"
//   },
//   "done": true,
//   "total_duration": 57596872000,
//   ...
// }
// 우리는 message.content만 필요하므로 다른 필드는 무시
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)  // 알 수 없는 필드는 무시 (model, created_at, done, total_duration 등)
public class responseDTO {
    private messageDTO message;  // Ollama: message 객체 (content 포함)
    
    /**
     * Ollama 응답에서 content만 추출
     * @return message.content 필드의 값, 없으면 null
     */
    public String getContent() {
        if (message != null && message.getContent() != null) {
            return message.getContent();
        }
        return null;
    }
}
