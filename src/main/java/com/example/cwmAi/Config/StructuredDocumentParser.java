package com.example.cwmAi.Config;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.example.cwmAi.dto.doc_DTO.DocElement;
import com.example.cwmAi.dto.doc_DTO.DocElementType;
import com.example.cwmAi.dto.doc_DTO.StructuredDocument;
import com.example.cwmAi.dto.doc_DTO.TableDoc;

import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.Table;
import technology.tabula.RectangularTextContainer;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import technology.tabula.extractors.BasicExtractionAlgorithm;

@Component
public class StructuredDocumentParser {

    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "(?:<\\s*표\\s*(\\d+)\\s*>|\\b표\\s*(\\d+)\\b|\\[\\s*별표\\s*(\\d+)\\s*\\]|별표\\s*(\\d+))"
    );

    private static final Pattern TABLE_CAPTION_PATTERN = Pattern.compile(
            "(?:<\\s*표\\s*(\\d+)\\s*>|표\\s*(\\d+)\\s*(?:[:：\\-]|\\)|\\(|\\s)|\\[\\s*별표\\s*(\\d+)\\s*\\]|별표\\s*(\\d+)\\s*(?:[:：\\-]|\\)|\\(|\\s))"
    );

    private static final Pattern TOC_DOT_LEADER_PATTERN = Pattern.compile("[·\\.]{5,}");
    private static final Pattern TOC_KEYWORD_PATTERN = Pattern.compile("(?i)\\b목\\s*차\\b|\\bcontents\\b");

    public StructuredDocument parsePdf(File pdfFile, String fileName, String category, String lawName) throws Exception {
        StructuredDocument doc = new StructuredDocument();
        doc.setFileName(fileName);
        doc.setCategory(category);
        doc.setLawName(lawName);

        String fullText = extractText(pdfFile);

        DocElement textElement = new DocElement();
        textElement.setElementId("TEXT_001");
        textElement.setType(DocElementType.PARAGRAPH);
        textElement.setContent(fullText);
        doc.getElements().add(textElement);

        // 0224 김소연(수정): B안에서는 테이블은 doc에 대량 적재하지 않음
        // 이유: 표는 TableStore에 별도 저장하고, 질문 시점에 1개만 선택해서 붙이기 위함
        // extractTables(pdfFile, doc);

        return doc;
    }

    // 0224 김소연(수정): B안 - PDF에서 표만 추출하여 TableDoc 리스트로 반환
    // 이유: 청킹 단계에서 표를 병합하지 않고, 질문 시점에서 표 1개만 선택해 LLM 컨텍스트에 추가하기 위함
    public List<TableDoc> extractTablesAsTableDocs(File pdfFile, String fileName, String category) throws Exception {
        List<TableDoc> result = new ArrayList<>();

        System.out.println("[표추출] 시작(B안) file=" + pdfFile.getName());

        try (PDDocument pdDocument = PDDocument.load(pdfFile)) {
            ObjectExtractor extractor = new ObjectExtractor(pdDocument);

            int tableSeq = 0;
            int totalTablesFound = 0;

            PDFTextStripper pageStripper = new PDFTextStripper();
            pageStripper.setSortByPosition(true);

            for (int pageNum = 1; pageNum <= pdDocument.getNumberOfPages(); pageNum++) {
                Page page = extractor.extract(pageNum);

                List<Table> tables;
                boolean usedFallback = false;

                SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
                tables = sea.extract(page);

                if (tables == null || tables.isEmpty()) {
                    BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm();
                    tables = bea.extract(page);
                    usedFallback = true;
                }
                if (tables == null || tables.isEmpty()) continue;

                pageStripper.setStartPage(pageNum);
                pageStripper.setEndPage(pageNum);
                String pageText = pageStripper.getText(pdDocument);

                boolean looksLikeTocPage = isLikelyTocPage(pageText);

                List<Table> filtered = new ArrayList<>();
                for (Table t : tables) {
                    if (isLikelyRealTable(t, looksLikeTocPage)) {
                        filtered.add(t);
                    }
                }
                if (filtered.isEmpty()) continue;

                System.out.println("[표추출] file=" + pdfFile.getName()
                        + ", page=" + pageNum
                        + ", tables=" + filtered.size()
                        + ", algo=" + (usedFallback ? "Basic" : "Spreadsheet")
                        + (looksLikeTocPage ? ", tocPage=true" : ""));

                // 캡션(표1/별표3) + 제목(가능하면) 추출
                List<CaptionAndTitle> captions = extractCaptionsWithTitleFromPageText(pageText);

                for (int i = 0; i < filtered.size(); i++) {
                    Table t = filtered.get(i);

                    tableSeq++;
                    String tableId = String.format("TABLE_%03d", tableSeq);

                    TableDoc td = new TableDoc();
                    td.setTableId(tableId);
                    td.setFileName(fileName);
                    td.setCategory(category);
                    td.setPage(pageNum);

                    List<List<String>> tableData = toTableData(t);
                    td.setTableData(tableData);

                    // 0224 김소연(수정): 캡션/제목은 "페이지 내 순서 매핑"
                    // 이유: 표 내부에 캡션이 없을 때도 페이지 텍스트의 캡션 등장 순서로 매핑하면 성공률이 높음
                    if (captions != null && i < captions.size()) {
                        CaptionAndTitle ct = captions.get(i);
                        if (ct != null) {
                            td.setCaption(ct.caption);
                            td.setTitle(ct.title);
                        }
                    }

                    // 셀 내부에 캡션이 있는 경우 보강
                    String guessedRef = guessTableReferenceFromTable(tableData);
                    if (td.getCaption() == null && guessedRef != null) {
                        td.setCaption(guessedRef);
                    }

                    totalTablesFound++;

                    // 디버그: 헤더 1줄만
                    if (tableData != null && !tableData.isEmpty()) {
                        String header = String.join(" | ", tableData.get(0));
                        if (header.length() > 120) header = header.substring(0, 120);
                        System.out.println("[표추출] id=" + tableId
                                + ", caption=" + (td.getCaption() == null ? "" : td.getCaption())
                                + ", title=" + (td.getTitle() == null ? "" : td.getTitle())
                                + ", header=" + header);
                    }

                    result.add(td);
                }
            }

            System.out.println("[표추출] 완료(B안) file=" + pdfFile.getName()
                    + ", totalTables=" + totalTablesFound);
        }

        return result;
    }

    private String extractText(File file) throws Exception {
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(document.getNumberOfPages());
            String text = stripper.getText(document);

            text = text.replaceAll("<[^>]+>", "");
            text = text.replaceAll("(?m)^.*(국가법령정보센터|법제처).*$", "");
            text = text.replaceAll("(?m)^\\s*\\d+\\s*$", "");
            text = text.replaceAll("[ \\t]{2,}", " ");
            text = text.replaceAll("\\n{3,}", "\n\n");
            return text.trim();
        }
    }

    private List<List<String>> toTableData(Table t) {
        List<List<String>> tableData = new ArrayList<>();
        List<List<RectangularTextContainer>> rows = t.getRows();
        if (rows == null) return tableData;

        for (List<RectangularTextContainer> row : rows) {
            List<String> rowData = new ArrayList<>();
            if (row != null) {
                for (RectangularTextContainer cell : row) {
                    rowData.add(cell != null && cell.getText() != null ? cell.getText().trim() : "");
                }
            }
            tableData.add(rowData);
        }
        return tableData;
    }

    private boolean isLikelyTocPage(String pageText) {
        if (pageText == null) return false;
        String t = pageText.trim();
        if (t.isEmpty()) return false;

        int dotLeaderHits = countMatches(TOC_DOT_LEADER_PATTERN, t);
        boolean hasTocKeyword = TOC_KEYWORD_PATTERN.matcher(t).find();
        return hasTocKeyword || dotLeaderHits >= 2;
    }

    private int countMatches(Pattern p, String text) {
        int c = 0;
        Matcher m = p.matcher(text);
        while (m.find()) c++;
        return c;
    }

    /**
     * 추출된 Table이 실제 데이터 표인지 판별한다.
     *
     * 개선 포인트 (2025-02-24 김소연):
     * 기존 로직은 행수·열수·채움율만 체크하여 공문서 구분선(────)이 Tabula에서
     * 테이블 경계로 잡힐 때 일반 텍스트 단락을 "1열짜리 표"로 오인식하는 문제가 있었음.
     *
     * 추가된 필터 5가지:
     *   1) 단일 컬럼 지배 검사  - 행의 80% 이상이 1열이면 텍스트 단락으로 간주
     *   2) 컬럼 수 분산 검사    - 열 수 편차가 너무 크면(구조 없음) 거부
     *   3) 구분선 행(divider)   - 빈 행이나 대시/점으로만 채워진 행 비율이 높으면 거부
     *   4) 긴 텍스트 셀 비율    - 셀 내용이 산문(prose)이면 거부
     *   5) 목차 페이지 강화     - 목차 페이지에서는 2열 이상 + 짧은 셀 필수
     */
    private boolean isLikelyRealTable(Table t, boolean looksLikeTocPage) {
        if (t == null) return false;
        List<List<RectangularTextContainer>> rows = t.getRows();
        if (rows == null || rows.isEmpty()) return false;

        int rowCount    = rows.size();
        int colCountMax = 0;
        int nonEmptyCells   = 0;
        int totalCells      = 0;
        int longTextCells   = 0;   // 셀 내용 50자 이상
        int dividerRows     = 0;   // 구분선 행 (빈 행 또는 대시/점만)
        int singleColRows   = 0;   // 열 수가 1인 행

        // 컬럼 수 분포 계산용
        List<Integer> colCounts = new ArrayList<>();

        for (List<RectangularTextContainer> row : rows) {
            if (row == null) {
                dividerRows++;
                continue;
            }

            int colsInRow = row.size();
            colCountMax = Math.max(colCountMax, colsInRow);
            colCounts.add(colsInRow);

            if (colsInRow <= 1) singleColRows++;

            boolean rowIsAllEmptyOrDash = true;
            for (RectangularTextContainer cell : row) {
                totalCells++;
                String v = (cell == null || cell.getText() == null) ? "" : cell.getText().trim();
                if (!v.isEmpty()) nonEmptyCells++;
                if (v.length() >= 50) longTextCells++;   // 50자 이상 = 산문 의심

                // 구분선 셀 판별: 빈 칸 or 대시/점/밑줄/공백만
                if (!v.isEmpty() && !v.matches("[\\-—_·.\\s]+")) {
                    rowIsAllEmptyOrDash = false;
                }
            }
            if (rowIsAllEmptyOrDash) dividerRows++;
        }

        // ─── 기본 조건 ────────────────────────────────────────────
        if (rowCount < 2 || colCountMax < 2) return false;

        double fillRatio = (totalCells == 0) ? 0.0
                : (double) nonEmptyCells / totalCells;
        if (fillRatio < 0.25) return false;

        // ─── [필터 1] 단일 컬럼 지배 검사 ───────────────────────────
        // 행의 80% 이상이 1열 → 구분선으로 나뉜 텍스트 단락일 가능성 높음
        if (!colCounts.isEmpty()) {
            double singleColRatio = (double) singleColRows / rowCount;
            if (singleColRatio >= 0.80) {
                System.out.printf("[표필터] 단일컬럼 지배 (%.0f%%) → 거부%n", singleColRatio * 100);
                return false;
            }
        }

        // ─── [필터 2] 컬럼 수 분산 검사 ─────────────────────────────
        // 실제 표는 열 수가 일정함. 편차가 지나치게 크면 구조 없는 텍스트
        if (colCounts.size() >= 3) {
            double mean = colCounts.stream().mapToInt(Integer::intValue).average().orElse(0);
            double variance = colCounts.stream()
                    .mapToDouble(c -> (c - mean) * (c - mean))
                    .average().orElse(0);
            double stdDev = Math.sqrt(variance);

            // 평균 대비 표준편차가 60% 초과 = 열 구조가 일관되지 않음
            if (mean > 0 && (stdDev / mean) > 0.60) {
                System.out.printf("[표필터] 컬럼수 불일치(평균=%.1f, stdDev=%.1f) → 거부%n", mean, stdDev);
                return false;
            }
        }

        // ─── [필터 3] 구분선 행 비율 검사 ───────────────────────────
        // 구분선 행이 30% 이상이면 표가 아니라 섹션 구분자일 가능성
        double dividerRatio = (double) dividerRows / rowCount;
        if (dividerRatio >= 0.30) {
            System.out.printf("[표필터] 구분선 행 비율 %.0f%% → 거부%n", dividerRatio * 100);
            return false;
        }

        // ─── [필터 4] 긴 텍스트 셀 비율 검사 ───────────────────────
        // 셀의 40% 이상이 50자 초과 → 산문 텍스트 단락 오인식 가능성
        if (nonEmptyCells > 0) {
            double longTextRatio = (double) longTextCells / nonEmptyCells;
            if (longTextRatio >= 0.40) {
                System.out.printf("[표필터] 긴 텍스트 셀 비율 %.0f%% → 거부%n", longTextRatio * 100);
                return false;
            }
        }

        // ─── [필터 5] 목차 페이지 강화 ──────────────────────────────
        if (looksLikeTocPage) {
            // 목차 페이지: 2열 이상 + 짧은 셀 많아야 함
            if (colCountMax < 2 || longTextCells > 1) return false;
        }

        // ─── 소규모 표 최소 조건 ─────────────────────────────────────
        if (rowCount <= 2 && colCountMax <= 2) {
            if (nonEmptyCells <= 2) return false;
        }

        return true;
    }

    private String guessTableReferenceFromTable(List<List<String>> tableData) {
        if (tableData == null || tableData.isEmpty()) return null;

        int maxRows = Math.min(2, tableData.size());
        for (int r = 0; r < maxRows; r++) {
            for (String cell : tableData.get(r)) {
                if (cell == null) continue;
                Matcher m = TABLE_REF_PATTERN.matcher(cell);
                if (m.find()) {
                    String tableNo = firstNonNull(m.group(1), m.group(2));
                    String annexNo = firstNonNull(m.group(3), m.group(4));
                    if (tableNo != null) return "표" + tableNo;
                    if (annexNo != null) return "별표" + annexNo;
                }
            }
        }
        return null;
    }

    private String firstNonNull(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    // 0224 김소연(수정): 캡션 + 제목(가능하면)까지 함께 추출
    // 이유: "<표1>" 표기 없는 문서도 "표 제목"으로 검색해 표 1개 선택 가능하게 하기 위함
    private List<CaptionAndTitle> extractCaptionsWithTitleFromPageText(String pageText) {
        List<CaptionAndTitle> list = new ArrayList<>();
        if (pageText == null || pageText.isBlank()) return list;

        // 줄 단위로 보고, "표1/별표3" 라인이 있으면 제목은 같은 줄 나머지 or 다음 줄 1줄을 후보로 사용
        String[] lines = pageText.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty()) continue;

            Matcher m = TABLE_CAPTION_PATTERN.matcher(line);
            if (m.find()) {
                String tableNo = firstNonNull(m.group(1), m.group(2));
                String annexNo = firstNonNull(m.group(3), m.group(4));

                String caption = null;
                if (tableNo != null) caption = "표" + tableNo;
                if (annexNo != null) caption = "별표" + annexNo;
                if (caption == null) continue;

                String title = extractTitleFromCaptionLine(line, caption);

                // 같은 줄에서 제목 못 잡으면 다음 줄 1줄을 제목 후보로
                if ((title == null || title.isBlank()) && i + 1 < lines.length) {
                    String next = lines[i + 1] == null ? "" : lines[i + 1].trim();
                    if (!next.isEmpty() && next.length() <= 80) {
                        title = next;
                    }
                }

                list.add(new CaptionAndTitle(caption, title));
            }
        }

        // 중복 제거(순서 유지)
        LinkedHashMap<String, CaptionAndTitle> uniq = new LinkedHashMap<>();
        for (CaptionAndTitle ct : list) {
            if (!uniq.containsKey(ct.caption)) uniq.put(ct.caption, ct);
        }
        return new ArrayList<>(uniq.values());
    }

    private String extractTitleFromCaptionLine(String line, String caption) {
        if (line == null) return null;
        String normalized = line.replaceAll("\\s+", " ").trim();

        // "표 1 : 제목" / "<표1> 제목" 같은 케이스에서 caption 뒤쪽을 제목으로
        int idx = normalized.indexOf(caption);
        if (idx < 0) {
            // 공백 없는 "표1"도 대비
            String cap2 = caption.replaceAll("\\s+", "");
            idx = normalized.replaceAll("\\s+", "").indexOf(cap2);
            if (idx < 0) return null;
        }

        String after = normalized.substring(Math.min(normalized.length(), idx + caption.length())).trim();
        after = after.replaceAll("^[\\]<>\\[\\(\\)\\-:：]+", "").trim();

        if (after.isEmpty()) return null;
        if (after.length() > 80) after = after.substring(0, 80);
        return after;
    }

    private static class CaptionAndTitle {
        String caption;
        String title;
        CaptionAndTitle(String caption, String title) {
            this.caption = caption;
            this.title = title;
        }
    }
}