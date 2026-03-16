package com.example.cwmAi.dto.doc_DTO;

import java.util.ArrayList;
import java.util.List;

public class TableDoc {
    private String tableId;          // TABLE_001
    private String fileName;         // 국가정보보안기본지침.pdf
    private String category;         // 정보보안
    private int page;                // 13
    private String caption;          // 표1 / 별표3 (있으면)
    private String title;            // 캡션 옆 제목(가능하면)
    private List<List<String>> tableData = new ArrayList<>();

    // 0225 김소연(수정): 표 전체 셀 내용 기반 임베딩 벡터 (벡터 유사도 검색용)
    private float[] embedding;

    // 0225 김소연(수정): Tabula 필터 거부 시 페이지 텍스트 전체를 저장하는 fallback 모드
    // 이유: 업무매뉴얼처럼 셀에 긴 텍스트가 많아 Tabula가 거부하는 표도 벡터 검색으로 활용하기 위함
    private boolean isPageFallback = false;
    private String pageFullText;   // fallback 시 페이지 전체 텍스트

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<List<String>> getTableData() { return tableData; }
    public void setTableData(List<List<String>> tableData) { this.tableData = tableData; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }

    public boolean isPageFallback() { return isPageFallback; }
    public void setPageFallback(boolean pageFallback) { isPageFallback = pageFallback; }

    public String getPageFullText() { return pageFullText; }
    public void setPageFullText(String pageFullText) { this.pageFullText = pageFullText; }
}