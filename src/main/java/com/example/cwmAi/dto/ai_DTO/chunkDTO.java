package com.example.cwmAi.dto.ai_DTO;

import lombok.Getter;

@Getter
public class chunkDTO {

    /* ===== 법령 메타데이터 ===== */
    /** 법률명 */
    private final String lawName;
    /** 장 제목 (있을 경우) */
    private final String chapterTitle;
    /** 조항 번호 (예: "제1조", "제2조", "제3조의2") */
    private final String articleNumber;
    /** 조항 이름(조 제목) */
    private final String articleTitle;

    /* ===== 본문 ===== */
    /** 조항 내용 전체 텍스트 */
    private final String text;

    /* ===== 관리용 ===== */
    /** 청크 ID (예: "공제사업#1", "개인정보보호#2") */
    private final String chunkId;
    private final String fileName;
    private final int chunkIndex;
    /** 카테고리 (예: "공제사업", "개인정보보호") */
    private final String category;

    public chunkDTO(
            String lawName,
            String chapterTitle,
            String articleNumber,
            String articleTitle,
            String text,
            String chunkId,
            String fileName,
            int chunkIndex,
            String category
    ) {
        this.lawName = lawName;
        this.chapterTitle = chapterTitle;
        this.articleNumber = articleNumber;
        this.articleTitle = articleTitle;
        this.text = text;
        this.chunkId = chunkId;
        this.fileName = fileName;
        this.chunkIndex = chunkIndex;
        this.category = category;
    }
}

