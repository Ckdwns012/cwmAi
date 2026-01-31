package com.example.cwmAi.Config;

import org.springframework.stereotype.Component;

import com.example.cwmAi.dto.ai_DTO.chunkDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentChunker {

    /**
     * 하나의 법령 문서를 조(조항) 단위로 분할하여 청크를 생성한다.
     * 각 청크는 다음 정보를 포함한다.
     * - 법률명
     * - 장 제목
     * - 조항 이름(조 제목)
     * - 조항 내용(본문)
     * - 카테고리
     * 
     * 개선사항:
     * - 법률 참조 패턴(예: "제XX조를 참고하라")을 실제 조항 시작과 구분
     * - 조항 경계를 더 정확하게 판단
     * - 항(項) 패턴도 고려
     */
    public List<chunkDTO> chunkText(String fileName, String text, String category) {
        System.out.println("=== 청킹 시작: " + fileName + " ===");
        System.out.println("카테고리: " + (category != null ? category : "없음"));
        System.out.println("원본 텍스트 길이: " + text.length() + "자");

        List<chunkDTO> chunks = new ArrayList<>();

        /* =========================
           0. 법령명 추출 (법/시행령/규칙/규정/지침)
           ========================= */
        String lawName = extractLawName(fileName);
        System.out.println("추출된 법령명: " + lawName);

        /* =========================
           1. 장 제목 추출 (번호 제거)
           ========================= */
        Pattern chapterPattern =
                Pattern.compile("(?m)^\\s*제\\s*\\d+장\\s*([^\\n]+)");
        Matcher chapterMatcher = chapterPattern.matcher(text);

        List<Integer> chapterPositions = new ArrayList<>();
        List<String> chapterTitles = new ArrayList<>();

        while (chapterMatcher.find()) {
            chapterPositions.add(chapterMatcher.start());
            chapterTitles.add(chapterMatcher.group(1).trim());
        }
        System.out.println("발견된 장(章) 수: " + chapterPositions.size());

        /* =========================
           2. 조 제목 추출 (개선)
           - 기본: "제3조(개인정보의 수집·이용)" 형태
           - "제5조의2(정관의 기재사항)" 처럼 '조의n' 구조도 지원
           - 괄호 안 제목이 없을 때도 null이 되지 않도록 전체 매칭 문자열을 사용
           - 줄바꿈을 고려하여 조 경계를 정확히 찾음
           - 법률 참조 패턴을 제외하여 실제 조항만 추출
           ========================= */
        
        // 실제 조항 시작 패턴: 줄 시작에 "제 숫자조" 또는 "제 숫자조의숫자" 형태
        // 단, 법률 참조가 아닌 실제 조항 시작만 매칭
        Pattern articlePattern =
                Pattern.compile("(?m)^\\s*제\\s*(\\d+)(?:조(?:의\\s*\\d+)?)?\\s*(?:\\(([^)]+)\\))?");
        Matcher articleMatcher = articlePattern.matcher(text);

        List<Integer> articlePositions = new ArrayList<>();
        List<String> articleTitles = new ArrayList<>();
        List<String> articleNumbers = new ArrayList<>(); // 조항 번호 저장

        while (articleMatcher.find()) {
            int position = articleMatcher.start();
            String matchedText = articleMatcher.group(0).trim();
            String articleNum = articleMatcher.group(1); // 조항 번호
            
            // 해당 위치 주변 텍스트 확인 (앞 50자, 뒤 50자)
            int contextStart = Math.max(0, position - 50);
            int contextEnd = Math.min(text.length(), position + matchedText.length() + 50);
            String context = text.substring(contextStart, contextEnd);
            
            // 법률 참조 패턴인지 확인
            boolean isReference = isLawReference(context, position - contextStart, matchedText);
            
            if (!isReference) {
                // 실제 조항 시작으로 판단
                articlePositions.add(position);
                
                // 조항 번호 추출 (예: "제1조", "제2조", "제3조의2")
                String fullMatch = articleMatcher.group(0).trim();
                String articleNumberStr = extractArticleNumber(fullMatch, articleNum);
                articleNumbers.add(articleNumberStr);
                
                String bracketTitle = articleMatcher.group(2); // 괄호 안 제목
                String finalTitle;
                if (bracketTitle != null && !bracketTitle.isBlank()) {
                    finalTitle = bracketTitle.trim();
                } else {
                    // 괄호 안 제목이 없는 경우, 조항 번호를 제목으로 사용
                    finalTitle = articleNumberStr;
                }
                articleTitles.add(finalTitle);
            }
        }
        
        System.out.println("발견된 조항 수: " + articlePositions.size());
        if (articlePositions.isEmpty()) {
            System.err.println("[경고] 조항을 찾을 수 없습니다. 파일을 확인해주세요.");
            System.out.println("=== 청킹 종료: 조항 없음 ===");
            return chunks; // 조항이 없으면 빈 리스트 반환
        }

        /* =========================
           3. 조 단위 Chunk 생성 (개선)
           - 조항 내용의 끝을 더 정확하게 판단
           - 다음 조항 시작 전까지가 한 조항
           ========================= */
        for (int i = 0; i < articlePositions.size(); i++) {
            int start = articlePositions.get(i);
            int end = (i + 1 < articlePositions.size())
                    ? articlePositions.get(i + 1)
                    : text.length();

            String articleText = text.substring(start, end).trim();
            
            // 조항 내용 정리: 불필요한 공백 제거, 하지만 구조는 유지
            articleText = cleanArticleText(articleText);
            
            // 조항이 너무 짧으면(50자 미만) 건너뛰기 (잘못된 매칭일 가능성)
            if (articleText.length() < 50) {
                continue;
            }
            
            String chapterTitle = findChapterTitle(start, chapterPositions, chapterTitles);
            if (chapterTitle == null) {
                chapterTitle = "";
            }

            String articleNumber = articleNumbers.get(i); // 조항 번호
            String articleTitle = articleTitles.get(i);
            
            // 필수 필드 검증
            List<String> validationErrors = validateChunkFields(
                lawName, chapterTitle, articleNumber, articleTitle, 
                articleText, fileName, category
            );
            
            if (!validationErrors.isEmpty()) {
                System.err.println("=== 청크 생성 실패: 필수 필드 누락 ===");
                System.err.println("파일명: " + fileName);
                System.err.println("청크 인덱스: " + i);
                System.err.println("오류 목록:");
                for (String error : validationErrors) {
                    System.err.println("  - " + error);
                }
                System.err.println("조항 내용 (처음 200자): " + 
                    (articleText.length() > 200 ? articleText.substring(0, 200) + "..." : articleText));
                System.err.println("=====================================");
                continue; // 필수 필드가 없으면 청크를 생성하지 않음
            }
            
            // 조항 이름이 비어있으면 조항 번호를 기본값으로 사용
            if (articleTitle == null || articleTitle.trim().isEmpty()) {
                articleTitle = articleNumber;
                System.out.println("[경고] 조항 이름이 없어 조항 번호를 사용: " + fileName + " - " + articleNumber);
            }

            chunkDTO dto = new chunkDTO(
                    lawName,                 // 법령명
                    chapterTitle,            // 장 제목
                    articleNumber,           // 조항 번호
                    articleTitle,            // 조항 이름(조 제목)
                    articleText,             // 조항 내용(본문)
                    null,                    // chunkId (aiService에서 부여)
                    fileName,                // 파일명
                    i,                       // chunk index
                    category                 // 카테고리
            );
            chunks.add(dto);
            
            // 생성된 청크 정보 로그 (디버깅용)
            System.out.println("[청크 생성 성공 #" + (i + 1) + "] " + articleNumber + 
                " - " + articleTitle + " (길이: " + articleText.length() + "자)");
        }
        
        System.out.println("=== 청킹 완료: 총 " + chunks.size() + "개 청크 생성 ===");
        System.out.println();
        return chunks;
    }
    
    /**
     * 법률 참조 패턴인지 확인
     * @param context 주변 텍스트 (앞 50자 + 매칭 텍스트 + 뒤 50자)
     * @param matchPosition 매칭된 텍스트의 시작 위치 (context 내에서)
     * @param matchedText 매칭된 텍스트
     * @return 참조 패턴이면 true, 실제 조항 시작이면 false
     */
    private boolean isLawReference(String context, int matchPosition, String matchedText) {
        // 매칭 텍스트 앞부분 확인
        String before = context.substring(0, matchPosition);
        String after = context.substring(matchPosition + matchedText.length());
        
        // 실제 조항 시작의 특징:
        // 1. 줄 시작에 위치 (앞에 줄바꿈이 있거나 텍스트 시작)
        // 2. 앞에 다른 조항 참조가 없음
        
        // 참조 패턴의 특징:
        // 1. 문장 중간에 위치 (앞에 다른 텍스트가 있음)
        // 2. 뒤에 조사나 참조 키워드가 옴 (을, 를, 에, 의, 제X항 등)
        
        // 앞부분이 비어있거나 줄바꿈으로 끝나면 실제 조항일 가능성 높음
        String beforeTrimmed = before.trim();
        if (beforeTrimmed.isEmpty() || beforeTrimmed.endsWith("\n") || 
            before.matches(".*\\n\\s*$")) {
            // 뒷부분 확인: 조사나 참조 키워드가 바로 오면 참조
            String afterTrimmed = after.trim();
            if (afterTrimmed.matches("^(?:을|를|에|의|에\\s*따르면|를\\s*참고|에\\s*따라|에\\s*의하여|에\\s*의한|제\\s*\\d+항).*")) {
                return true;
            }
            // 실제 조항으로 판단
            return false;
        }
        
        // 앞부분에 다른 조항 참조가 있으면 참조로 판단
        // "제X조를", "제X조제X항", "제X조에 따르면" 등의 패턴
        Pattern referenceBeforePattern = Pattern.compile(
            "제\\s*\\d+(?:조(?:의\\s*\\d+)?)?(?:제\\s*\\d+항)?(?:을|를|에|의|에\\s*따르면|를\\s*참고|에\\s*따라|에\\s*의하여|에\\s*의한|및|와|과)"
        );
        if (referenceBeforePattern.matcher(beforeTrimmed).find()) {
            return true;
        }
        
        // 뒷부분에 참조 키워드가 있으면 참조로 판단
        String afterTrimmed = after.trim();
        if (afterTrimmed.matches("^(?:을|를|에|의|에\\s*따르면|를\\s*참고|에\\s*따라|에\\s*의하여|에\\s*의한|제\\s*\\d+항).*")) {
            return true;
        }
        
        // 문장 중간에 있고 앞뒤에 일반 텍스트가 있으면 참조일 가능성 높음
        if (!beforeTrimmed.isEmpty() && !afterTrimmed.isEmpty() && 
            !beforeTrimmed.endsWith("\n") && !afterTrimmed.startsWith("\n")) {
            // 앞에 조항 번호가 있고 뒤에 조사가 오면 참조
            if (beforeTrimmed.matches(".*제\\s*\\d+(?:조(?:의\\s*\\d+)?)?$") && 
                afterTrimmed.matches("^(?:을|를|에|의|제\\s*\\d+항).*")) {
                return true;
            }
        }
        
        // 기본적으로 실제 조항으로 판단
        return false;
    }
    
    /**
     * 조항 번호 추출
     * @param fullMatch 매칭된 전체 문자열 (예: "제1조", "제3조의2", "제5조(제목)")
     * @param articleNum 조항 번호 (숫자만)
     * @return 조항 번호 문자열 (예: "제1조", "제3조의2")
     */
    private String extractArticleNumber(String fullMatch, String articleNum) {
        // "제X조의Y" 패턴 확인
        if (fullMatch.contains("조의")) {
            Pattern subArticlePattern = Pattern.compile("조의\\s*(\\d+)");
            Matcher subMatcher = subArticlePattern.matcher(fullMatch);
            if (subMatcher.find()) {
                return "제" + articleNum + "조의" + subMatcher.group(1);
            }
        }
        // 기본: "제X조"
        return "제" + articleNum + "조";
    }
    
    /**
     * 청크 필수 필드 검증
     * @param lawName 법령명
     * @param chapterTitle 장 제목
     * @param articleNumber 조항 번호
     * @param articleTitle 조항 이름
     * @param text 조항 내용
     * @param fileName 파일명
     * @param category 카테고리
     * @return 검증 오류 목록 (오류가 없으면 빈 리스트)
     */
    private List<String> validateChunkFields(
            String lawName, String chapterTitle, String articleNumber, 
            String articleTitle, String text, String fileName, String category) {
        List<String> errors = new ArrayList<>();
        
        // 법령명 검증
        if (lawName == null || lawName.trim().isEmpty() || lawName.equals("알 수 없음")) {
            errors.add("법령명이 없거나 유효하지 않음: " + lawName);
        }
        
        // 조항 번호 검증
        if (articleNumber == null || articleNumber.trim().isEmpty()) {
            errors.add("조항 번호가 없음");
        } else if (!articleNumber.matches("제\\s*\\d+(?:조(?:의\\s*\\d+)?)?")) {
            errors.add("조항 번호 형식이 올바르지 않음: " + articleNumber);
        }
        
        // 조항 이름 검증 (비어있어도 조항 번호로 대체 가능하므로 경고만)
        if (articleTitle == null || articleTitle.trim().isEmpty()) {
            // 조항 이름이 없으면 조항 번호를 사용하므로 오류가 아님
            // 하지만 로그는 남김
        }
        
        // 조항 내용 검증
        if (text == null || text.trim().isEmpty()) {
            errors.add("조항 내용이 없음");
        } else if (text.trim().length() < 10) {
            errors.add("조항 내용이 너무 짧음 (10자 미만): " + text.trim().length() + "자");
        }
        
        // 파일명 검증
        if (fileName == null || fileName.trim().isEmpty()) {
            errors.add("파일명이 없음");
        }
        
        // 카테고리 검증 (null이거나 빈 문자열이어도 허용하되, 로그는 남김)
        if (category == null || category.trim().isEmpty()) {
            // 카테고리는 선택사항이므로 오류가 아님
        }
        
        return errors;
    }
    
    /**
     * 조항 텍스트 정리
     * - 불필요한 공백 제거
     * - 연속된 줄바꿈 정리
     * - 하지만 조항 구조는 유지
     */
    private String cleanArticleText(String text) {
        // 연속된 공백을 하나로
        text = text.replaceAll("[ \\t]{2,}", " ");
        // 연속된 줄바꿈을 두 개로 제한
        text = text.replaceAll("\\n{3,}", "\n\n");
        // 줄 끝의 공백 제거
        text = text.replaceAll("(?m)[ \\t]+$", "");
        return text.trim();
    }

    /* ======================================================
       법령명 추출
       ====================================================== */
    private String extractLawName(String fileName) {
        String name = fileName.replaceAll("\\.[^.]+$", "");
        Pattern pattern = Pattern.compile(
                "([가-힣\\s]+(시행규칙|시행령|법률|법|규정|지침))"
        );
        Matcher matcher = pattern.matcher(name);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "알 수 없음";
    }

    /* =========================
       조 위치 기준 장 제목 찾기
       ========================= */
    private String findChapterTitle(
            int articlePos,
            List<Integer> chapterPositions,
            List<String> chapterTitles
    ) {
        String result = null;
        for (int i = 0; i < chapterPositions.size(); i++) {
            if (chapterPositions.get(i) <= articlePos) {
                result = chapterTitles.get(i);
            } else {
                break;
            }
        }
        return result;
    }
}

