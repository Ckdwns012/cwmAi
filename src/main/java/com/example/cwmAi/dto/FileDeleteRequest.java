//0223 추가(김소연)_파일 삭제 요청 DTO_삭제 API를 GET -> DELETE로 변경 (보안 표준)
package com.example.cwmAi.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FileDeleteRequest {
    private String filename;
    private String category;
}