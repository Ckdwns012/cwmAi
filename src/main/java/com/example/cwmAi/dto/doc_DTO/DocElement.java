package com.example.cwmAi.dto.doc_DTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DocElement {
    private String elementId;                 // 예: "TABLE_001", "ARTICLE_005"
    private DocElementType type;              // TABLE, ARTICLE 등
    private String content;                   // 텍스트(조항/캡션/각주 등)
    private List<List<String>> tableData;     // TABLE인 경우 셀 데이터
    private Map<String, Object> metadata = new HashMap<>(); // page, bbox 등
    private List<String> references = new ArrayList<>();    // "표1","별표1" 등 원문 참조 토큰

    public DocElement() {}
}