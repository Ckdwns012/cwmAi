package com.example.cwmAi.Config;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.stereotype.Component;

import com.example.cwmAi.dto.doc_DTO.TableDoc;

@Component
public class TableStoreInMemory {

    // 0224 김소연(수정): 표를 별도 저장소로 관리 (B안)
    // 이유: 청킹 단계에서 표를 무조건 병합하면 컨텍스트 폭발 + 성능 저하 + 오탐 증가
    //      질문 시점에 1개만 선택해서 붙이기 위해 표만 따로 저장
    private final List<TableDoc> store = new ArrayList<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public void clearAll() {
        rwLock.writeLock().lock();
        try {
            store.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int size() {
        rwLock.readLock().lock();
        try {
            return store.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // 파일 단위 교체(업로드/삭제 후 재로딩)
    public void replaceByFile(String category, String fileName, List<TableDoc> newTables) {
        String c = normalize(category);
        String f = (fileName == null) ? "" : fileName;

        rwLock.writeLock().lock();
        try {
            store.removeIf(t ->
                    normalize(t.getCategory()).equals(c) &&
                            f.equalsIgnoreCase(t.getFileName())
            );
            if (newTables != null && !newTables.isEmpty()) {
                store.addAll(newTables);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // 카테고리 단위 교체(카테고리 재로딩)
    public void replaceByCategory(String category, List<TableDoc> newTables) {
        String c = normalize(category);

        rwLock.writeLock().lock();
        try {
            store.removeIf(t -> normalize(t.getCategory()).equals(c));
            if (newTables != null && !newTables.isEmpty()) {
                store.addAll(newTables);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // 0224 김소연(수정): 질문과 가장 유사한 표 1개만 선택
    // 이유: 표가 많아도 LLM 컨텍스트에는 1개만 붙여 성능/정확도 균형 유지
    // answerContext: LLM 답변에서 "별표N" 언급을 캡처해 표 매핑에 활용 (null 허용)
    public Optional<TableDoc> findBestTableForQuestion(String question, String category, List<String> fileNames) {
        return findBestTableForQuestion(question, null, category, fileNames);
    }

    public Optional<TableDoc> findBestTableForQuestion(String question, String answerContext, String category, List<String> fileNames) {
        if (question == null || question.isBlank()) return Optional.empty();

        // 질문 + 답변 컨텍스트 합쳐서 검색 (답변에 "별표3" 언급 시 캐치)
        String combined = question.trim() + (answerContext != null ? " " + answerContext : "");
        String q = combined;
        String c = normalize(category);

        boolean hasTableKeyword = containsAny(q, "표", "별표", "서식", "양식", "작성 항목", "기준", "현황");

        Set<String> fileSet = null;
        if (fileNames != null && !fileNames.isEmpty()) {
            fileSet = new HashSet<>();
            for (String f : fileNames) fileSet.add(f.toLowerCase());
        }

        // 캡션 직접 언급(표3/별표2 등) 우선 - 질문+답변 통합 텍스트에서 추출
        String captionMention = extractCaptionMention(q); // "표3" or "별표2"
        rwLock.readLock().lock();
        try {
            TableDoc best = null;
            int bestScore = 0;

            for (TableDoc t : store) {
                if (!c.isEmpty() && !normalize(t.getCategory()).equals(c)) continue;
                if (fileSet != null && (t.getFileName() == null || !fileSet.contains(t.getFileName().toLowerCase()))) continue;

                int score = 0;

                // 1) 캡션 직접 지목
                if (captionMention != null && t.getCaption() != null && captionMention.equals(t.getCaption())) {
                    score += 1000;
                }

                // 2) 표 관련 키워드가 있으면 제목/헤더 매칭 가중
                if (hasTableKeyword) score += 10;

                // 3) 제목(title) 매칭
                score += tokenOverlapScore(q, t.getTitle()) * 10;

                // 4) 헤더(1행) 매칭
                String header = firstRowAsText(t.getTableData());
                score += tokenOverlapScore(q, header) * 6;

                // 5) 캡션 자체(표1/별표3)도 약하게 반영
                score += tokenOverlapScore(q, t.getCaption()) * 3;

                if (score > bestScore) {
                    bestScore = score;
                    best = t;
                }
            }

            // 캡션 직접 지목(score 1000+)이면 키워드 없어도 무조건 반환
            // 그 외에는 표 키워드도 없고 점수도 낮으면 붙이지 않음
            if (bestScore >= 1000) return Optional.ofNullable(best);
            if (!hasTableKeyword && bestScore < 30) return Optional.empty();

            return Optional.ofNullable(best);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // LLM에 넣기 위한 렌더링(행/열/문자 제한)
    public String renderTableForPrompt(TableDoc t, int maxRows, int maxCharsPerLine) {
        StringBuilder sb = new StringBuilder();

        sb.append("[표정보] ");
        if (t.getCaption() != null && !t.getCaption().isBlank()) sb.append(t.getCaption()).append(" ");
        if (t.getTitle() != null && !t.getTitle().isBlank()) sb.append(t.getTitle()).append(" ");
        sb.append("(page ").append(t.getPage()).append(")\n");

        List<List<String>> data = t.getTableData();
        if (data == null || data.isEmpty()) return sb.toString();

        int rows = Math.min(maxRows, data.size());
        for (int r = 0; r < rows; r++) {
            List<String> row = data.get(r);
            String line = (row == null) ? "" : String.join(" | ", row);
            if (line.length() > maxCharsPerLine) line = line.substring(0, maxCharsPerLine);
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private String normalize(String s) {
        if (s == null) return "";
        String v = s.trim();
        return v.isEmpty() ? "" : v;
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (k != null && !k.isBlank() && text.contains(k)) return true;
        }
        return false;
    }

    private String extractCaptionMention(String q) {
        // 아주 단순한 캡션 지목: "표3", "별표2"
        // 0224 김소연(수정): 캡션을 직접 지목하면 최우선으로 매핑
        // 이유: 사용자가 "별표3 기준"이라고 말하면 제목 유사도보다 캡션이 정확함
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(표\\s*\\d+|별표\\s*\\d+)").matcher(q);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", "");
        }
        return null;
    }

    private int tokenOverlapScore(String a, String b) {
        if (a == null || b == null) return 0;
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        int hit = 0;
        for (String x : ta) {
            if (tb.contains(x)) hit++;
        }
        return hit;
    }

    private Set<String> tokens(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return Collections.emptySet();
        String[] parts = v.split("[\\s,\\-_/()\\[\\]{}:：;\"'“”‘’]+");
        Set<String> set = new HashSet<>();
        for (String p : parts) {
            if (p.length() >= 2) set.add(p);
        }
        return set;
    }

    private String firstRowAsText(List<List<String>> data) {
        if (data == null || data.isEmpty()) return "";
        List<String> row0 = data.get(0);
        if (row0 == null) return "";
        return String.join(" ", row0);
    }
    // 전체 교체(서버 기동 시 1회 로딩 등)
    public void replaceAll(List<TableDoc> newTables) {
        rwLock.writeLock().lock();
        try {
            store.clear();
            if (newTables != null && !newTables.isEmpty()) {
                store.addAll(newTables);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }



}