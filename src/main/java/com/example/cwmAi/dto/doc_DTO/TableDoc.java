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
}