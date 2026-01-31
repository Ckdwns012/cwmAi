package com.example.cwmAi.dto.ai_DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Message DTO
// Ollama 응답 구조: { "role": "assistant", "content": "...", "thinking": "..." }
// 우리는 content만 사용하므로 thinking 필드는 무시
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)  // thinking 등 알 수 없는 필드는 무시
public class messageDTO {
    private String role;
    private String content;  // 이 필드만 사용, thinking은 무시됨
}

