package com.example.cwmAi.Service;

import com.example.cwmAi.Config.*;
import com.example.cwmAi.dto.doc_DTO.TableDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.cwmAi.dto.ai_DTO.aiResponseDTO;
import com.example.cwmAi.dto.ai_DTO.chunkDTO;
import com.example.cwmAi.dto.ai_DTO.messageDTO;
import com.example.cwmAi.dto.ai_DTO.responseDTO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service("aiService")
public class aiService {

    /* =========================
       설정값
     ========================= */
    private static final String OLLAMA_BASE_URL = "http://localhost:11434/api";
    private static final String MODEL_NAME = "qwen3:4b-instruct-2507-q4_K_M";
    // JAR 파일 실행 위치 기준 상대 경로 (uploads 폴더)
    private static final String UPLOAD_DIR;

    static {
        // 상대 경로를 절대 경로로 변환 (JAR 실행 위치 기준)
        UPLOAD_DIR = new File("uploads").getAbsolutePath();
        // uploads 폴더가 없으면 생성
        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
    }

    private final WebClient webClient;
    private final DocumentChunker documentChunker;
    private final VectorStoreInMemory vectorStore;
    // 0223 김소연(수정): Phase1 구조화 파싱을 위한 Parser 주입
    // 이유: Tabula로 표 구조를 추출하여 조항 참조(<표1>)에 매핑하기 위함
    private final StructuredDocumentParser structuredDocumentParser;
    // 0224 김소연(수정): B안 표 저장소 주입
    // 이유: 표를 청크에 미리 병합하지 않고, 질문 시점에 1개만 선택해 붙이기 위함
    private final TableStoreInMemory tableStore;

    private final SemanticCacheService semanticCacheService;
    // 카테고리별 청크 ID 카운터 (예: "공제사업" -> 1, "개인정보보호" -> 1) ->  기존 (쓰레드 안전하지 않음)
//    private final Map<String, Integer> categoryChunkCounter = new HashMap<>();

    // 0224 김소연(수정) (ConcurrentHashMap으로 교체)
    private final Map<String, Integer> categoryChunkCounter = new ConcurrentHashMap<>();

    //0224 김소연(수정) - ObjectMapper 싱글톤 (기존 코드에서 매번 new로 생성하던 것을 필드로 이동)
    private final ObjectMapper objectMapper = new ObjectMapper();

    /* =========================
       생성자
     ========================= */
    public aiService(
            WebClient.Builder webClientBuilder,
            DocumentChunker documentChunker,
            VectorStoreInMemory vectorStore,
            StructuredDocumentParser structuredDocumentParser,
            TableStoreInMemory tableStore,
            SemanticCacheService semanticCacheService
    ) {
        this.documentChunker = documentChunker;
        this.vectorStore = vectorStore;
        this.structuredDocumentParser = structuredDocumentParser;
        this.tableStore = tableStore;
        this.semanticCacheService  = semanticCacheService;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)  // 연결 타임아웃 30초
                .responseTimeout(Duration.ofMinutes(10))  // 응답 타임아웃 10분
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(600))  // 읽기 타임아웃 10분 (600초)
                                .addHandlerLast(new WriteTimeoutHandler(60))  // 쓰기 타임아웃 1분
                );

        this.webClient = webClientBuilder
                .baseUrl(OLLAMA_BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /* =========================
       초기화/재로딩 유틸
     ========================= */
    /**
     * 전체 업로드 디렉터리(카테고리 포함)를 모두 읽어
     * 청킹하고 메모리 저장소에 적재한다.
     * - 서버 기동 시 1회 호출용
     */
    // public void loadAllDocuments() {
    //     System.out.println("=== 전체 문서 초기 로딩 시작 ===");
    //     vectorStore.clearChunk();
    //     categoryChunkCounter.clear(); // 카운터 초기화
    //     readAndChunkUploadedFiles(null); // category=null → 최상위 uploads 전체
    //     System.out.println("=== 전체 문서 초기 로딩 완료. 청크 수: " + vectorStore.getSize() + " ===");

    //     // 조항 이름이 없는 청크 점검
    //     checkChunksWithoutArticleTitle();

    //     // 전체 청크 검증
    //     validateAndReportChunks();
    // }

    public void loadAllDocuments() {
        System.out.println("=== 전체 문서 초기 로딩 시작 ===");

        // 0223 김소연(수정): 전체 초기 로딩 시 clearChunk() 후 addChunk() 반복 방식은 로딩 중 중간 상태(빈 store/부분 store)가 노출될 수 있음
        // 이유: 문서 수가 증가(예: 300개)하면 로딩 시간이 길어져 질의 요청이 대기하거나 중간 상태를 볼 위험이 커짐
        // vectorStore.clearChunk();
        // categoryChunkCounter.clear(); // 카운터 초기화
        // readAndChunkUploadedFiles(null); // category=null → 최상위 uploads 전체
        // System.out.println("=== 전체 문서 초기 로딩 완료. 청크 수: " + vectorStore.getSize() + " ===");

        // 0223 김소연(수정): 전체 문서를 먼저 List로 구성한 뒤, replaceAll()로 한 번에 반영(원자적 교체)
        // 이유: 로딩이 끝나기 전까지는 이전 store로 질의 처리 가능, 로딩 완료 후 한 번에 교체되어 중간 상태 노출 방지
        categoryChunkCounter.clear(); // 카운터 초기화
        List<chunkDTO> allChunks = readAndChunkUploadedFilesToList(null);
        vectorStore.replaceAll(allChunks);
        System.out.println("=== 전체 문서 초기 로딩 완료. 청크 수: " + vectorStore.getSize() + " ===");

        List<TableDoc> allTables = readAndExtractTablesToList(null);
        tableStore.replaceAll(allTables);
        // 조항 이름이 없는 청크 점검
        checkChunksWithoutArticleTitle();

        // 전체 청크 검증
        validateAndReportChunks();
    }

    /**
     * 조항 이름이 없는 청크를 점검하고 로그를 출력한다.
     */
    public void checkChunksWithoutArticleTitle() {
        List<chunkDTO> chunksWithoutTitle = vectorStore.findChunksWithoutArticleTitle();

        if (chunksWithoutTitle.isEmpty()) {
            System.out.println("=== 조항 이름 점검 결과: 모든 청크에 조항 이름이 있습니다. ===");
        } else {
            System.out.println("=== 조항 이름 점검 결과: 조항 이름이 없는 청크 " + chunksWithoutTitle.size() + "개 발견 ===");
            for (int i = 0; i < chunksWithoutTitle.size(); i++) {
                chunkDTO chunk = chunksWithoutTitle.get(i);
                System.out.println("--- 조항 이름 없는 청크 " + (i + 1) + " ---");
                System.out.println("법령명: " + chunk.getLawName());
                System.out.println("조항 번호: " + chunk.getArticleNumber());
                System.out.println("조항 이름: " + (chunk.getArticleTitle() == null ? "null" : "\"" + chunk.getArticleTitle() + "\""));
                System.out.println("파일명: " + chunk.getFileName());
                System.out.println("카테고리: " + chunk.getCategory());
                System.out.println("청크 인덱스: " + chunk.getChunkIndex());
                System.out.println("조항 내용 (처음 200자): " +
                        (chunk.getText().length() > 200 ? chunk.getText().substring(0, 200) + "..." : chunk.getText()));
                System.out.println();
            }
        }
        System.out.println("=== 조항 이름 점검 완료 ===");
    }

    /**
     * 조항 이름이 없는 청크의 통계 정보를 반환한다.
     */
    public Map<String, Object> getChunksWithoutArticleTitleStatistics() {
        return vectorStore.getChunksWithoutArticleTitleStatistics();
    }

    /**
     * 모든 청크의 필수 필드를 검증한다.
     */
    public Map<String, Object> validateAllChunks() {
        return vectorStore.validateAllChunks();
    }

    /**
     * 모든 청크를 검증하고 결과를 콘솔에 출력한다.
     */
    public void validateAndReportChunks() {
        System.out.println("=== 전체 청크 검증 시작 ===");
        Map<String, Object> validationResult = validateAllChunks();

        int totalChunks = (Integer) validationResult.get("totalChunks");
        int validChunks = (Integer) validationResult.get("validChunks");
        int invalidChunks = (Integer) validationResult.get("invalidChunks");

        System.out.println("전체 청크 수: " + totalChunks);
        System.out.println("유효한 청크: " + validChunks);
        System.out.println("문제가 있는 청크: " + invalidChunks);

        if (invalidChunks > 0) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> invalidChunkDetails =
                    (List<Map<String, Object>>) validationResult.get("invalidChunkDetails");

            System.out.println("\n=== 문제가 있는 청크 상세 정보 ===");
            for (int i = 0; i < invalidChunkDetails.size(); i++) {
                Map<String, Object> chunk = invalidChunkDetails.get(i);
                System.out.println("\n--- 문제 청크 " + (i + 1) + " ---");
                System.out.println("저장소 인덱스: " + chunk.get("index"));
                System.out.println("법령명: " + chunk.get("lawName"));
                System.out.println("조항 번호: " + chunk.get("articleNumber"));
                System.out.println("조항 이름: " + chunk.get("articleTitle"));
                System.out.println("파일명: " + chunk.get("fileName"));
                System.out.println("카테고리: " + chunk.get("category"));
                System.out.println("청크 인덱스: " + chunk.get("chunkIndex"));
                System.out.println("조항 내용 길이: " + chunk.get("textLength") + "자");

                @SuppressWarnings("unchecked")
                List<String> errors = (List<String>) chunk.get("errors");
                System.out.println("오류 목록:");
                for (String error : errors) {
                    System.out.println("  - " + error);
                }
            }
        } else {
            System.out.println("\n✅ 모든 청크가 유효합니다!");
        }

        System.out.println("\n=== 전체 청크 검증 완료 ===");
    }

    /**
     * 특정 카테고리의 문서만 다시 읽어 청킹 후 메모리 저장소에 반영한다.
     * - 이미 메모리에 로딩된 기존 청크 중 해당 카테고리와 관련된 것만 비우는
     *   정교한 구현도 가능하지만, 현재는 간단하게 전체 초기화를 선택.
     */
    // public void reloadCategory(String category) {
    //     System.out.println("=== 카테고리 재로딩 시작: " + category + " ===");
    //     vectorStore.clearChunk();
    //     categoryChunkCounter.clear(); // 카운터 초기화
    //     readAndChunkUploadedFiles(null); // 단순화를 위해 전체 다시 로딩
    //     System.out.println("=== 카테고리 재로딩 완료. 청크 수: " + vectorStore.getSize() + " ===");
    // }

    public void reloadCategory(String category) {
        // 0223 김소연(수정): 업로드/삭제마다 전체 clear + 전체 재로딩 방식 제거
        // 이유: 문서가 300개 수준으로 늘면 업로드/삭제 1회마다 전체 PDF 재파싱/청킹으로 성능 저하 및 서비스 응답 지연 발생
        // System.out.println("=== 카테고리 재로딩 시작: " + category + " ===");
        // vectorStore.clearChunk();
        // categoryChunkCounter.clear(); // 카운터 초기화
        // readAndChunkUploadedFiles(null); // 단순화를 위해 전체 다시 로딩
        // System.out.println("=== 카테고리 재로딩 완료. 청크 수: " + vectorStore.getSize() + " ===");

        // 0223 김소연(수정): 해당 카테고리 폴더만 다시 읽어 청킹하고, replaceCategory()로 원자적 교체
        // 이유: 질의(읽기) 비중이 높아(95:5) 전체 재로딩보다 증분 재로딩이 운영 효율이 높고, write lock 점유 시간을 최소화할 수 있음
        String normalizedCategory = normalizeCategory(category);
        System.out.println("=== 카테고리 재로딩 시작: " + (normalizedCategory.isEmpty() ? "(최상위)" : normalizedCategory) + " ===");

        // 0223 김소연(수정): 재로딩 카테고리의 chunkId 재부여를 위해 해당 카테고리 카운터만 초기화
        // 이유: 전체 카운터를 초기화하면 다른 카테고리 chunkId 연속성이 매번 바뀌어 로그/추적이 어려워짐
        String idCategory = normalizedCategory.isEmpty() ? "기타" : normalizedCategory;
        categoryChunkCounter.put(idCategory, 0);

        // 0223 김소연(수정): 해당 카테고리의 청크만 새로 생성
        List<chunkDTO> newChunks = readAndChunkUploadedFilesToList(normalizedCategory);

        // 0223 김소연(수정): 기존 store에서 해당 카테고리만 제거 후 새 청크로 교체(원자적 반영)
        // 이유: clear 전체 삭제 없이 특정 카테고리만 교체하여 서비스 연속성 유지
        vectorStore.replaceCategory(normalizedCategory, newChunks);
        semanticCacheService.invalidateCache(normalizedCategory); // 문서 변경 시 해당 카테고리 캐시 무효화
        System.out.println("=== 카테고리 재로딩 완료: " + (normalizedCategory.isEmpty() ? "(최상위)" : normalizedCategory)
                + " / 추가 청크: " + newChunks.size()
                + " / 전체 청크: " + vectorStore.getSize() + " ===");
    }
    /**
     * chunkId를 포함한 새로운 청크를 생성한다.
     */
    private chunkDTO createChunkWithId(chunkDTO originalChunk, String chunkId) {
        return new chunkDTO(
                originalChunk.getLawName(),
                originalChunk.getChapterTitle(),
                originalChunk.getArticleNumber(),
                originalChunk.getArticleTitle(),
                originalChunk.getText(),
                chunkId,
                originalChunk.getFileName(),
                originalChunk.getChunkIndex(),
                originalChunk.getCategory()
        );
    }

    // ──0224 김소연(수정) askModel() 메서드 교체 ──────────────────────────
    /* =========================
       사용자 질문 → AI 응답 (2단계 질의 시스템)
       1단계: 사용자 질문 + 해당 분야의 조항 이름 목록 → 관련 조항 이름 3~10개 추천
       2단계: 사용자 질문 + 추천받은 조항 이름의 실제 청크 내용 → 최종 답변
     ========================= */
//    public Mono<String> askModel(String userPrompt, String category) {
//        // 1단계: 조항 이름 추천 (파일 선택 없이 전체 파일 사용)
//        return recommendArticleTitles(userPrompt, category, null)
//                .flatMap(recommendedTitles -> {
//                    if (recommendedTitles == null || recommendedTitles.isEmpty()) {
//                        return Mono.just("해당 분야의 관련 조항을 찾을 수 없습니다. 보다 정확한 법률 용어로 다시 질문해주세요.");
//                    }
//
//                    // 2단계: 추천받은 조항 이름으로 실제 청크 조회 후 최종 답변
//                    return generateFinalAnswer(userPrompt, recommendedTitles, category);
//                });
//    }
    //   캐시 확인 → 캐시 히트 시 즉시 반환 → 미스 시 LLM 호출 후 캐시 저장
    public Mono<String> askModel(String userPrompt, String category) {

        // ① 캐시에서 먼저 찾기 (동기 → Mono로 감싸기)
        Optional<String> cached = semanticCacheService.findCachedAnswer(userPrompt, category);
        if (cached.isPresent()) {
            System.out.println("[캐시HIT] 즉시 반환: " + userPrompt.substring(0, Math.min(40, userPrompt.length())));
            return Mono.just(cached.get());
        }

        // ② 캐시 미스 → 기존 2단계 LLM 호출
        return recommendArticleTitles(userPrompt, category, null)
                .flatMap(recommendedTitles -> {
                    if (recommendedTitles == null || recommendedTitles.isEmpty()) {
                        return Mono.just("해당 분야의 관련 조항을 찾을 수 없습니다. 보다 정확한 법률 용어로 다시 질문해주세요.");
                    }
                    return generateFinalAnswer(userPrompt, recommendedTitles, category);
                })
                .doOnSuccess(answer -> {
                    // ③ LLM 답변을 캐시에 저장 (비동기로 side-effect 처리)
                    if (answer != null && !answer.isBlank()) {
                        semanticCacheService.cacheAnswer(userPrompt, answer, category);
                    }
                });
    }

    /**
     * 단계별 상태를 SSE로 전달하는 메서드
     */
    public Flux<aiResponseDTO> askModelWithStages(String userPrompt, String category) {
        // 1단계 시작
        Mono<aiResponseDTO> stage1Start = Mono.just(new aiResponseDTO("stage1", "관련 조항을 찾는 중입니다...", null, null));

        // 1단계: 조항 이름 추천 (한 번만 실행되도록 cache, 파일 선택 없이 전체 파일 사용)
        Mono<List<String>> recommendedTitlesMono = recommendArticleTitles(userPrompt, category, null).cache();

        // 1단계 완료 후 2단계 시작 및 최종 답변 생성
        Flux<aiResponseDTO> stage2AndFinal = recommendedTitlesMono
                .flatMapMany(recommendedTitles -> {
                    if (recommendedTitles == null || recommendedTitles.isEmpty()) {
                        return Mono.just(new aiResponseDTO("completed", null, null,
                                "해당 분야의 관련 조항을 찾을 수 없습니다. 보다 정확한 법률 용어로 다시 질문해주세요."));
                    }

                    // 2단계 시작
                    Mono<aiResponseDTO> stage2Start = Mono.just(new aiResponseDTO("stage2",
                            "관련조항을 바탕으로 답변을 생성중입니다! 조금만 기다려주세요!",
                            recommendedTitles, null));

                    // 최종 답변 생성
                    Mono<aiResponseDTO> finalAnswer = generateFinalAnswer(userPrompt, recommendedTitles, category)
                            .map(answer -> new aiResponseDTO("completed", null, recommendedTitles, answer));

                    // 2단계 시작과 최종 답변을 순차적으로 연결
                    return Flux.concat(stage2Start, finalAnswer);
                });

        return Flux.concat(stage1Start, stage2AndFinal)
                .filter(dto -> dto != null && dto.getStage() != null);
    }

    /**
     * 1단계 질의: 사용자 질문과 해당 분야의 조항 이름 목록을 AI에 전달하여 관련 조항 이름을 추천받는다.
     * @param userPrompt 사용자 질문
     * @param category 카테고리
     * @param fileNames 선택된 파일명 리스트 (null이면 모든 파일)
     * @return 추천받은 조항 이름 리스트 (3~10개)
     */
    public Mono<List<String>> recommendArticleTitles(String userPrompt, String category, List<String> fileNames) {
        // 선택된 파일의 청크에서만 조항 이름 추출
        Map<String, String> articleTitleToChunkId = vectorStore.getArticleTitlesByCategoryAndFiles(category, fileNames);

        if (articleTitleToChunkId.isEmpty()) {
            System.out.println("[1단계] 해당 카테고리의 조항 이름이 없습니다: " + category);
            if (fileNames != null && !fileNames.isEmpty()) {
                System.out.println("[1단계] 선택된 파일: " + fileNames);
            }
            return Mono.just(new ArrayList<>());
        }

        // 조항 이름 목록을 문자열로 변환
        List<String> articleTitles = new ArrayList<>(articleTitleToChunkId.keySet());
        StringBuilder articleTitlesText = new StringBuilder();
        for (int i = 0; i < articleTitles.size(); i++) {
            articleTitlesText.append((i + 1)).append(". ").append(articleTitles.get(i));
            if (i < articleTitles.size() - 1) {
                articleTitlesText.append("\n");
            }
        }

        List<messageDTO> messages = new ArrayList<>();

        // 시스템 메시지
        messages.add(new messageDTO(
                "system",
                """
                너는 법령 조항을 분석하는 전문가다.
                사용자의 질문과 관련된 조항 이름을 관련도가 높은 순으로 2개에서 7개 사이로 추천해야 한다.
                
                중요 규칙:
                1. 반드시 JSON 배열 형식으로만 답변한다. 예: ["조항이름1", "조항이름2", "조항이름3"]
                2. 다른 설명이나 텍스트는 절대 포함하지 않는다.
                3. 조항 이름은 제공된 목록에서 정확히 선택해야 한다.
                4. 2개 이상 7개 이하로 추천한다.
                5. 한글로만 답변한다.
                6. 금액, 수치, 법령 조문 번호는 원문 그대로 유지한다.
                """
        ));

        // 유저 메시지: 질문 + 조항 이름 목록
        String userMessage = String.format(
                "사용자 질문: %s\n\n" +
                        "다음은 해당 분야의 모든 조항 이름 목록입니다:\n%s\n\n" +
                        "질문에 나온 단어가 조항이름에 있으면 꼭 추천하세요.\n" +
                        "위 질문과 가장 관련이 높은 조항 이름을 2개에서 7개 사이로 선택하여 JSON 배열 형식으로만 답변하세요.\n" +
                        "예시: [\"조항이름1\", \"조항이름2\", \"조항이름3\"]",
                userPrompt,
                articleTitlesText.toString()
        );

        messages.add(new messageDTO("user", userMessage));

        ChatRequest requestBody = new ChatRequest(MODEL_NAME, messages);

        // 1단계 요청 상세 로그
        System.out.println("========================================");
        System.out.println("===== [1단계] 조항 이름 추천 요청 =====");
        System.out.println("========================================");
        System.out.println("카테고리: " + (category != null ? category : "전체"));
        if (fileNames != null && !fileNames.isEmpty()) {
            System.out.println("선택된 파일: " + fileNames);
        } else {
            System.out.println("선택된 파일: 전체 파일");
        }
        System.out.println("전체 조항 이름 수: " + articleTitles.size());
        System.out.println("\n--- [1단계] AI 요청 내용 ---");
        System.out.println("URL: " + OLLAMA_BASE_URL + "/chat");
        System.out.println("Model: " + MODEL_NAME);
        System.out.println("\n[System 메시지]");
        System.out.println(messages.get(0).getContent());
        System.out.println("\n[User 메시지]");
        System.out.println(messages.get(1).getContent());
        System.out.println("========================================\n");

        return webClient.post()
                .uri("/chat")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(rawResponse -> {
                    // 1단계 응답 상세 로그
                    System.out.println("========================================");
                    System.out.println("===== [1단계] Ollama API 원본 응답 =====");
                    System.out.println("========================================");
                    System.out.println(rawResponse);
                    System.out.println("========================================\n");

                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        responseDTO response = mapper.readValue(rawResponse, responseDTO.class);

                        if (response == null || response.getMessage() == null) {
                            System.err.println("[1단계] 응답 파싱 실패");
                            return new ArrayList<String>();
                        }

                        String content = response.getContent();
                        if (content == null || content.trim().isEmpty()) {
                            System.err.println("[1단계] content가 비어있음");
                            return new ArrayList<String>();
                        }

                        System.out.println("--- [1단계] 추출된 content ---");
                        System.out.println(content);
                        System.out.println("==============================\n");

                        // JSON 배열 파싱
                        // 응답에서 JSON 배열 부분만 추출 (앞뒤 불필요한 텍스트 제거)
                        String jsonArray = content.trim();
                        // 대괄호로 시작하고 끝나는 부분만 추출
                        int startIdx = jsonArray.indexOf('[');
                        int endIdx = jsonArray.lastIndexOf(']');
                        if (startIdx >= 0 && endIdx > startIdx) {
                            jsonArray = jsonArray.substring(startIdx, endIdx + 1);
                        }

                        @SuppressWarnings("unchecked")
                        List<String> recommendedTitles = mapper.readValue(jsonArray, List.class);

                        System.out.println("--- [1단계] 파싱 결과 ---");
                        System.out.println("추천받은 조항 이름 수: " + recommendedTitles.size());
                        System.out.println("추천받은 조항 이름: " + recommendedTitles);
                        System.out.println("==============================\n");

                        return recommendedTitles;
                    } catch (Exception e) {
                        System.err.println("[1단계] JSON 파싱 오류: " + e.getMessage());
                        System.err.println("[1단계] 원본 응답: " + rawResponse);
                        e.printStackTrace();
                        return new ArrayList<String>();
                    }
                })
                .onErrorResume(e -> {
                    System.err.println("[1단계] API 호출 오류: " + e.getMessage());
                    e.printStackTrace();
                    return Mono.just(new ArrayList<String>());
                });
    }

    /**
     * 2단계 질의: 추천받은 조항 이름에 해당하는 실제 청크 내용을 AI에 전달하여 최종 답변을 생성한다.
     * @param userPrompt 사용자 질문
     * @param recommendedTitles 추천받은 조항 이름 리스트
     * @param category 카테고리
     * @return 최종 답변
     */
    public Mono<String> generateFinalAnswer(String userPrompt, List<String> recommendedTitles, String category) {
        // 추천받은 조항 이름으로 실제 청크 조회
        List<chunkDTO> relevantChunks = vectorStore.getChunksByArticleTitles(recommendedTitles, category);

        if (relevantChunks.isEmpty()) {
            System.err.println("[2단계] 추천받은 조항 이름에 해당하는 청크를 찾을 수 없습니다.");
            return Mono.just("관련 조항을 찾을 수 없습니다.");
        }

        System.out.println("[2단계] 추천받은 조항 이름에 해당하는 청크 수: " + relevantChunks.size());

        List<messageDTO> messages = new ArrayList<>();

        // 시스템 메시지
        messages.add(new messageDTO(
                "system",
                """
                너는 공공기관 법령 질의에 답변하는 실무 보조 AI다.
                다음 규칙을 반드시 지켜라:
                1. 모든 답변은 반드시 한글로만 작성한다. 영어, 로마자, 기호는 사용하지 않는다.
                2. 금액, 수치, 법령 조문 번호는 원문 그대로 유지한다.
                3. 답변은 반드시 최대 10개 항목까지만 작성한다.
                4. 불필요한 설명, 반복 문장, 유사 표현을 금지한다.
                5. 각 항목은 2~3문장 이내로 간결하게 작성한다.
                6. 법령 근거는 항목별이 아니라 답변 맨 마지막에 한 번만 제시한다.
                7. 근거 형식은 반드시 "[근거: 법령명 제○조]" 같이 작성한다.
                """
        ));

        // 유저 메시지: 청크 내용 + 사용자 질문
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("다음 법령 조문을 참고하여 질문에 한글로만 답변해주세요. 절대 영어를 사용하지 마세요:\n\n");
        for (chunkDTO chunk : relevantChunks) {
            contextBuilder
                    .append("【")
                    .append(chunk.getLawName())
                    .append(" ")
                    .append(chunk.getArticleNumber())
                    .append(" ")
                    .append(chunk.getArticleTitle())
                    .append("】\n")
                    .append(chunk.getText())
                    .append("\n\n");
        }
        // 0224 김소연(수정): B안 - 질문 시점에 표 1개만 선택해 컨텍스트에 추가
        // 이유: 표를 청크에 미리 병합하지 않고, 질문과 관련성이 높은 표만 최소 비용으로 붙이기 위함
//        try {
//            java.util.Optional<com.example.cwmAi.dto.doc_DTO.TableDoc> bestTable =
//                    tableStore.findBestTableForQuestion(userPrompt, category, null);
//
//            if (bestTable.isPresent()) {
//                String tableText = tableStore.renderTableForPrompt(bestTable.get(), 25, 180);
//                contextBuilder.append("\n\n").append("다음 표를 참고하여 답변을 보강하세요:\n");
//                contextBuilder.append(tableText).append("\n");
//                System.out.println("[표첨부] 질문에 표 1개 첨부: "
//                        + (bestTable.get().getCaption() == null ? "" : bestTable.get().getCaption())
//                        + " "
//                        + (bestTable.get().getTitle() == null ? "" : bestTable.get().getTitle())
//                        + " (page " + bestTable.get().getPage() + ")");
//            }
        try {
            // 조항 본문에서 표/별표 참조 언급 수집
            StringBuilder chunkContext = new StringBuilder();
            for (chunkDTO chunk : relevantChunks) {
                if (chunk.getText() != null) chunkContext.append(chunk.getText()).append(" ");
            }
            String answerCtx = chunkContext.length() > 0 ? chunkContext.toString() : null;

            java.util.Optional<com.example.cwmAi.dto.doc_DTO.TableDoc> bestTable =
                    tableStore.findBestTableForQuestion(userPrompt, answerCtx, category, null);
            if (bestTable.isPresent()) {
                String tableText = tableStore.renderTableForPrompt(bestTable.get(), 25, 180);
                contextBuilder.append("\n\n").append("다음 표를 참고하여 답변을 보강하세요:\n");
                contextBuilder.append(tableText).append("\n");
                System.out.println("[표첨부] 표 1개 첨부: "
                        + (bestTable.get().getCaption() == null ? "" : bestTable.get().getCaption())
                        + " "
                        + (bestTable.get().getTitle() == null ? "" : bestTable.get().getTitle())
                        + " (page " + bestTable.get().getPage() + ")");
            } else {
                System.out.println("[표첨부] 관련 표 없음");
            }
        } catch (Exception ex) {
            System.err.println("[표첨부] 실패: " + ex.getMessage());
        }
        contextBuilder.append("\n질문: ").append(userPrompt);
        messages.add(new messageDTO("user", contextBuilder.toString()));

        ChatRequest requestBody = new ChatRequest(MODEL_NAME, messages);

        // 2단계 요청 상세 로그
        System.out.println("========================================");
        System.out.println("===== [2단계] 최종 답변 생성 요청 =====");
        System.out.println("========================================");
        System.out.println("사용된 청크 수: " + relevantChunks.size());
        System.out.println("\n--- [2단계] 사용된 청크 정보 ---");
        for (int i = 0; i < relevantChunks.size(); i++) {
            chunkDTO chunk = relevantChunks.get(i);
            System.out.println((i + 1) + ". " + chunk.getLawName() + " " +
                    chunk.getArticleNumber() + " " + chunk.getArticleTitle() +
                    " (청크ID: " + chunk.getChunkId() + ")");
        }
        System.out.println("\n--- [2단계] AI 요청 내용 ---");
        System.out.println("URL: " + OLLAMA_BASE_URL + "/chat");
        System.out.println("Model: " + MODEL_NAME);
        System.out.println("\n[System 메시지]");
        System.out.println(messages.get(0).getContent());
        System.out.println("\n[User 메시지]");
        System.out.println(messages.get(1).getContent());
        System.out.println("========================================\n");

        return webClient.post()
                .uri("/chat")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(rawResponse -> {
                    // 2단계 응답 상세 로그
                    System.out.println("========================================");
                    System.out.println("===== [2단계] Ollama API 원본 응답 =====");
                    System.out.println("========================================");
                    System.out.println(rawResponse);
                    System.out.println("========================================\n");

                    // JSON 파싱: Ollama 응답에서 message.content만 추출
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        responseDTO response = mapper.readValue(rawResponse, responseDTO.class);

                        if (response == null) {
                            System.err.println("[2단계] 응답 객체가 null입니다.");
                            return "AI 응답을 받지 못했습니다.";
                        }

                        if (response.getMessage() == null) {
                            System.err.println("[2단계] message 필드가 null입니다.");
                            return "AI 응답을 받지 못했습니다. (message 필드 없음)";
                        }

                        // message.content만 추출 (thinking 필드는 무시됨)
                        String content = response.getContent();

                        if (content != null && !content.trim().isEmpty()) {
                            // 가독성을 위해 #과 * 특수문자 제거
                            String cleanedContent = content.replace("#", "").replace("*", "");

                            System.out.println("--- [2단계] 최종 답변 (원본) ---");
                            System.out.println("길이: " + content.length() + "자");
                            System.out.println("내용:");
                            System.out.println(content);
                            System.out.println("\n--- [2단계] 최종 답변 (정제 후) ---");
                            System.out.println("길이: " + cleanedContent.length() + "자");
                            System.out.println("내용:");
                            System.out.println(cleanedContent);
                            System.out.println("==============================\n");
                            return cleanedContent;
                        }

                        System.err.println("[2단계] content가 비어있거나 null입니다.");
                        return "AI 응답을 받지 못했습니다. (응답 내용이 비어있음)";
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        System.err.println("===== [2단계] JSON 파싱 오류 =====");
                        System.err.println("오류: " + e.getMessage());
                        if (rawResponse != null && rawResponse.length() > 0) {
                            System.err.println("원본 응답 (처음 500자): " +
                                    rawResponse.substring(0, Math.min(500, rawResponse.length())));
                        }
                        e.printStackTrace();
                        return "AI 응답 파싱 오류: " + e.getMessage();
                    } catch (Exception e) {
                        System.err.println("===== [2단계] 예상치 못한 오류 =====");
                        System.err.println("오류: " + e.getMessage());
                        e.printStackTrace();
                        return "AI 응답 처리 중 오류 발생: " + e.getMessage();
                    }
                })
                .onErrorResume(e -> {
                    System.err.println("===== [2단계] Ollama API 호출 오류 =====");
                    e.printStackTrace();
                    System.err.println("오류 메시지: " + e.getMessage());
                    System.err.println("=======================");
                    return Mono.just("AI 호출 중 오류 발생: " + e.getMessage() + "\n\n확인 사항:\n1. Ollama 서버가 실행 중인지 확인 (ollama serve)\n2. 모델이 설치되어 있는지 확인 (ollama list)\n3. 포트 11434가 사용 가능한지 확인");
                });
    }

    /* =========================
       파일 로딩 & 청킹 (카테고리별 디렉토리 기준)
     ========================= */
    private void readAndChunkUploadedFiles(String category) {
        Path uploadPath;

        if (category == null || category.isBlank()) {
            uploadPath = Paths.get(UPLOAD_DIR);
        } else {
            uploadPath = Paths.get(UPLOAD_DIR, category);
        }

        if (!Files.exists(uploadPath)) {
            System.err.println("업로드 디렉토리 없음: " + uploadPath);
            return;
        }
        // 하위 폴더까지 모두 탐색해 업로드 파일을 읽는다.
        try (Stream<Path> paths = Files.walk(uploadPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .forEach(filePath -> {
                        String fileName = filePath.getFileName().toString().toLowerCase();
                        String content = "";
                        try {
                            if (fileName.endsWith(".txt")) {
                                content = Files.readString(filePath);
                            } else if (fileName.endsWith(".pdf")) {
                                content = extractTextFromPdf(filePath.toFile());
                            }

                            if (!content.isBlank()) {
                                // 파일 경로에서 카테고리 추출
                                String fileCategory = extractCategoryFromPath(filePath, Paths.get(UPLOAD_DIR));
                                List<chunkDTO> chunks =
                                        documentChunker.chunkText(fileName, content, fileCategory);
                                for (chunkDTO chunk : chunks) {
                                    // 카테고리별 청크 ID 부여
                                    String categoryForId = (chunk.getCategory() != null && !chunk.getCategory().isBlank())
                                            ? chunk.getCategory() : "기타";
                                    int chunkNumber = categoryChunkCounter.getOrDefault(categoryForId, 0) + 1;
                                    categoryChunkCounter.put(categoryForId, chunkNumber);
                                    String chunkId = categoryForId + "#" + chunkNumber;

                                    // chunkId를 포함한 새로운 청크 생성
                                    chunkDTO chunkWithId = createChunkWithId(chunk, chunkId);
                                    vectorStore.addChunk(chunkWithId);
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("파일 처리 실패: " + fileName);
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            System.err.println("업로드 디렉토리 접근 실패");
            e.printStackTrace();
        }
    }

    /* =========================
       PDF 처리
     ========================= */
    private String extractTextFromPdf(File file) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // ★ 필수
            stripper.setStartPage(1);
            stripper.setEndPage(document.getNumberOfPages());
            String text = stripper.getText(document);

            // 1.<10.0개정> 같은 편집 이력 제거
            text = text.replaceAll("<[^>]+>", "");
            // 2.페이지 머리글/바닥글 제거 (국가법령정보센터 계열)
            text = text.replaceAll(
                    "(?m)^.*(국가법령정보센터|법제처).*$", ""
            );
            // 3.쪽수만 있는 줄 제거
            text = text.replaceAll("(?m)^\\s*\\d+\\s*$", "");

            // 4. 줄바꿈 정리: 조/장 경계를 보존하면서 문장 중간 줄바꿈은 공백으로 변환
            // 조/장 패턴 앞의 줄바꿈은 유지하고, 나머지는 공백으로 변환
            String[] lines = text.split("\\n");
            StringBuilder cleanedText = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 조/장 패턴으로 시작하는 줄인지 확인
                boolean isArticleOrChapterStart = line.matches("^\\s*제\\s*\\d+[장조].*") ||
                        line.matches("^\\s*제\\s*\\d+조의\\d+.*");

                if (isArticleOrChapterStart && cleanedText.length() > 0) {
                    // 조/장 시작 전에는 줄바꿈 유지
                    cleanedText.append("\n").append(line);
                } else {
                    // 일반 줄은 공백으로 연결 (조항 내용이 여러 줄에 걸쳐 있을 때 유지)
                    if (cleanedText.length() > 0 && !cleanedText.toString().endsWith("\n")) {
                        cleanedText.append(" ");
                    }
                    cleanedText.append(line);
                }
            }
            text = cleanedText.toString();

            // 5. 연속 공백 정리
            text = text.replaceAll("[ \\t]{2,}", " ");
            // 6. 연속 개행 정리
            text = text.replaceAll("\\n{3,}", "\n\n");
            return text.trim();
        }
    }

    /**
     * 파일 경로에서 카테고리를 추출한다.
     * 예: /uploads/공제사업/file.pdf → "공제사업"
     * 예: /uploads/file.pdf → "" (최상위)
     */
    private String extractCategoryFromPath(Path filePath, Path basePath) {
        try {
            Path relativePath = basePath.relativize(filePath);
            if (relativePath.getNameCount() > 1) {
                // 하위 폴더에 있는 경우, 첫 번째 폴더명이 카테고리
                return relativePath.getName(0).toString();
            }
            // 최상위에 있는 경우 빈 문자열
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    // 0223 김소연(수정): 카테고리 입력값 정규화
    // 이유: null/blank를 동일하게 처리하여 replaceCategory/removeCategory 등에서 카테고리 매칭 안정화
    private String normalizeCategory(String category) {
        if (category == null) return "";
        String c = category.trim();
        return c.isEmpty() ? "" : c;
    }

    // 0224 김소연(수정): B안 - 청킹 시점 표 병합 제거, 표는 TableStore에만 저장
// 이유: 표를 청크에 미리 붙이면 컨텍스트/성능이 폭발하므로 질문 시점에 1개만 선택해 붙이기 위함
    private List<chunkDTO> readAndChunkUploadedFilesToList(String category) {
        List<chunkDTO> result = new ArrayList<>();

        Path uploadPath;
        if (category == null || category.isBlank()) {
            uploadPath = Paths.get(UPLOAD_DIR);
        } else {
            uploadPath = Paths.get(UPLOAD_DIR, category);
        }

        if (!Files.exists(uploadPath)) {
            System.err.println("업로드 디렉토리 없음: " + uploadPath);
            return result;
        }

        try (Stream<Path> paths = Files.walk(uploadPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .forEach(filePath -> {
                        try {
                            String fileName = filePath.getFileName().toString();
                            String fileNameLower = fileName.toLowerCase();
                            String content = "";

                            if (fileNameLower.endsWith(".txt")) {
                                content = Files.readString(filePath);
                            } else if (fileNameLower.endsWith(".pdf")) {
                                content = extractTextFromPdf(filePath.toFile());
                            }

                            if (content.isBlank()) return;

                            String fileCategory = extractCategoryFromPath(filePath, Paths.get(UPLOAD_DIR));

                            // 0224 김소연(수정): PDF는 표를 추출해 TableStore에 저장(B안)
                            // 이유: 표를 청크에 합치지 않고, 질문 시점에 필요한 표 1개만 붙이기 위함
                            if (fileNameLower.endsWith(".pdf")) {
                                try {
                                    List<com.example.cwmAi.dto.doc_DTO.TableDoc> tables =
                                            structuredDocumentParser.extractTablesAsTableDocs(filePath.toFile(), fileName, fileCategory);
                                    tableStore.replaceByFile(fileCategory, fileName, tables);
                                    System.out.println("[표저장] file=" + fileName + ", category=" + fileCategory + ", tables=" + (tables == null ? 0 : tables.size()));
                                } catch (Exception ex) {
                                    System.err.println("[표저장] 실패 file=" + fileName + " / " + ex.getMessage());
                                }
                            }

                            // 0224 김소연(수정): 청크는 기존 텍스트 기반 청킹만 수행(표 병합 없음)
                            List<chunkDTO> chunks = documentChunker.chunkText(fileNameLower, content, fileCategory);

                            for (chunkDTO chunk : chunks) {
                                String categoryForId = (chunk.getCategory() != null && !chunk.getCategory().isBlank())
                                        ? chunk.getCategory()
                                        : "기타";

                                int chunkNumber = categoryChunkCounter.getOrDefault(categoryForId, 0) + 1;
                                categoryChunkCounter.put(categoryForId, chunkNumber);
                                String chunkId = categoryForId + "#" + chunkNumber;

                                chunkDTO chunkWithId = createChunkWithId(chunk, chunkId);
                                result.add(chunkWithId);
                            }

                        } catch (Exception e) {
                            System.err.println("파일 처리 실패: " + filePath.getFileName());
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            System.err.println("업로드 디렉토리 접근 실패");
            e.printStackTrace();
        }

        return result;
    }

    // 0223 김소연(수정): TABLE 셀 데이터를 텍스트로 렌더링
    // 이유: LLM 컨텍스트에 표 구조를 단순 텍스트로 포함시켜 답변 근거 보강
    private String renderTableAsText(List<List<String>> tableData) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : tableData) {
            sb.append(String.join(" | ", row)).append("\n");
        }
        return sb.toString().trim();
    }

    // 0224 김소연(수정): B안 - 전체 업로드 폴더를 훑어서 PDF 표만 수집
    private List<TableDoc> readAndExtractTablesToList(String category) {
        List<TableDoc> result = new ArrayList<>();

        Path uploadPath;
        if (category == null || category.isBlank()) {
            uploadPath = Paths.get(UPLOAD_DIR);
        } else {
            uploadPath = Paths.get(UPLOAD_DIR, category);
        }

        if (!Files.exists(uploadPath)) {
            System.err.println("업로드 디렉토리 없음: " + uploadPath);
            return result;
        }

        try (Stream<Path> paths = Files.walk(uploadPath)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .forEach(filePath -> {
                        try {
                            String fileName = filePath.getFileName().toString();
                            String lower = fileName.toLowerCase();

                            if (!lower.endsWith(".pdf")) return;

                            String fileCategory = extractCategoryFromPath(filePath, Paths.get(UPLOAD_DIR));

                            List<TableDoc> tables =
                                    structuredDocumentParser.extractTablesAsTableDocs(filePath.toFile(), fileName, fileCategory);

                            if (tables != null && !tables.isEmpty()) {
                                result.addAll(tables);
                            }

                        } catch (Exception e) {
                            System.err.println("[표수집] 실패: " + filePath.getFileName() + " / " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("업로드 디렉토리 접근 실패");
            e.printStackTrace();
        }

        return result;
    }
}
