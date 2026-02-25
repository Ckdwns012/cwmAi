package com.example.cwmAi.dto.doc_DTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StructuredDocument {
    private String fileName;
    private String lawName;
    private String category;

    private List<DocElement> elements = new ArrayList<>();

    // "표1" -> "TABLE_001", "별표1" -> "TABLE_002"
    private Map<String, String> referenceMap = new HashMap<>();

    public StructuredDocument() {}
}