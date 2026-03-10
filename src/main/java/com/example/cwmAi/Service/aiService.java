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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Objects;

@Service("aiService")
public class aiService {

    /* =========================
       설정값
     ========================= */
    private static final String OLLAMA_BASE_URL = "http://localhost:11434/api";
    private static final String MODEL_NAME = "qwen3:4b-instruct-2507-q4_K_M";

    // v2 모드에서 LLM2에 직접 전달할 청크 수 (LLM1 없이 바로 답변 — 컨텍스트 과부하 방지)
    private static final int V2_DIRECT_TOP_K = 10;

    @Value("${rag.pipeline.version:1}")
    private int ragPipelineVersion;
//    private static final String MODEL_NAME = "exaone3.5:2.4b";
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

        // 0225 김소연(수정): 표 임베딩 계산
        System.out.println("=== 표 임베딩 계산 시작 (총 " + allTables.size() + "개) ===");
        allTables.forEach(t -> tableStore.computeAndStoreEmbedding(t));
        System.out.println("=== 표 임베딩 계산 완료 ===");

        // 0226 김소연(수정): 청크 임베딩 계산 (벡터+키워드 필터링용)
        // 이유: 1단계 LLM에 전체 조항 목록 전달 → 벡터 top-50 필터링으로 교체, 속도 개선
        System.out.println("=== 청크 임베딩 계산 시작 (총 " + allChunks.size() + "개) ===");
        allChunks.forEach(c -> vectorStore.computeAndStoreEmbedding(c));
        System.out.println("=== 청크 임베딩 계산 완료 ===");

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

        // 청크 임베딩 계산 (교체 후 반드시 수행 — 없으면 벡터 검색 불가)
        System.out.println("=== 청크 임베딩 계산 시작 (" + newChunks.size() + "개) ===");
        newChunks.forEach(c -> vectorStore.computeAndStoreEmbedding(c));
        System.out.println("=== 청크 임베딩 계산 완료 ===");

        semanticCacheService.invalidateCache(normalizedCategory); // 문서 변경 시 해당 카테고리 캐시 무효화
        System.out.println("=== 카테고리 재로딩 완료: " + (normalizedCategory.isEmpty() ? "(최상위)" : normalizedCategory)
                + " / 추가 청크: " + newChunks.size()
                + " / 전체 청크: " + vectorStore.getSize() + " ===");
    }
    /**
     * PDF 파싱 + 임베딩을 백그라운드 스레드에서 수행 (업로드 요청 즉시 응답용)
     */
    @Async("documentProcessingExecutor")
    public void reloadCategoryAsync(String category) {
        System.out.println("[Async] 백그라운드 재로딩 시작: " + category);
        reloadCategory(category);
        System.out.println("[Async] 백그라운드 재로딩 완료: " + category);
    }

    /**
     * 특정 파일을 참조한 캐시 항목만 무효화 (파일 삭제 시 호출)
     */
    public void invalidateCacheByFile(String fileName, String category) {
        semanticCacheService.invalidateCacheByFile(fileName, category);
    }

    /**
     * chunkId를 포함한 새로운 청크를 생성한다.
     */
    private chunkDTO createChunkWithId(chunkDTO originalChunk, String chunkId) {
        chunkDTO newChunk = new chunkDTO(
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
        newChunk.setChunkType(originalChunk.getChunkType()); // chunkType(LAW/MANUAL) 반드시 복사
        return newChunk;
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
    // v1 askModel (0226 주석처리)
    // 캐시 → 전체 조항 목록 LLM1 → LLM2 방식
    // public Mono<String> askModel(String userPrompt, String category) {
    //     Optional<String> cached = semanticCacheService.findCachedAnswer(userPrompt, category);
    //     if (cached.isPresent()) return Mono.just(cached.get());
    //     return recommendArticleTitles(userPrompt, category, null)
    //             .flatMap(titles -> titles.isEmpty()
    //                 ? Mono.just("해당 분야의 관련 조항을 찾을 수 없습니다.")
    //                 : generateFinalAnswer(userPrompt, titles, category))
    //             .doOnSuccess(a -> { if (a != null && !a.isBlank()) semanticCacheService.cacheAnswer(userPrompt, a, category); });
    // }

    /** 디버그: 카테고리 내 모든 청크 제목 목록 반환 */
    public List<Map<String, String>> getChunkTitlesForDebug(String category) {
        return vectorStore.getChunksByCategory(category).stream()
                .map(c -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("file", c.getFileName());
                    m.put("type", c.getChunkType() != null ? c.getChunkType() : "LAW");
                    m.put("title", c.getArticleTitle() != null ? c.getArticleTitle() : "(제목없음)");
                    m.put("article", c.getArticleNumber() != null ? c.getArticleNumber() : "");
                    return m;
                })
                .collect(Collectors.toList());
    }

    // 0226 김소연(수정): askModel v2 - 벡터+키워드 top-50 필터링 후 LLM 1단계
    // 이유: 전체 조항 목록 → LLM 방식 대비 토큰 절감, 속도 개선
    public Mono<String> askModel(String userPrompt, String category) {
        if (ragPipelineVersion == 2) return askModelV2(userPrompt, category);
        // ① 캐시 확인
        Optional<String> cached = semanticCacheService.findCachedAnswer(userPrompt, category);
        if (cached.isPresent()) {
            System.out.println("[캐시HIT] 즉시 반환: " + userPrompt.substring(0, Math.min(40, userPrompt.length())));
            return Mono.just(cached.get());
        }

        // ② LAW top-10 + MANUAL top-10 분리 벡터 검색 (LLM 없음, ms 단위)
        List<chunkDTO> filteredChunks = filterChunksByVectorAndKeyword(userPrompt, category);
        if (filteredChunks.isEmpty()) {
            return Mono.just("해당 분야의 관련 내용을 찾을 수 없습니다. 다른 키워드로 다시 질문해주세요.");
        }

        // ③ 필터링된 최대 20개 청크 → LLM1(조항 선택) → LLM2(답변 생성)
        // /ask 단일 엔드포인트는 O/X 피드백 없이 자동 pending → 즉시 approve 처리
        return recommendArticleTitlesFromChunks(userPrompt, filteredChunks)
                .flatMap(recommendedTitles -> {
                    if (recommendedTitles == null || recommendedTitles.isEmpty()) {
                        return Mono.just("해당 분야의 관련 조항을 찾을 수 없습니다. 보다 정확한 법률 용어로 다시 질문해주세요.");
                    }
                    return generateFinalAnswer(userPrompt, recommendedTitles, category);
                })
                .doOnSuccess(answer -> {
                    if (answer != null && !answer.isBlank()) {
                        // /ask 엔드포인트는 피드백 UI 없이 직접 호출되므로 pending→approve 즉시 처리
                        String pendingKey = semanticCacheService.cachePending(userPrompt, answer, category);
                        if (pendingKey != null) semanticCacheService.approvePending(pendingKey);
                    }
                });
    }

    /**
     * 단계별 상태를 SSE로 전달하는 메서드
     */
    // v1 askModelWithStages (0226 주석처리) - 전체 조항 목록 → LLM 방식
    // public Flux<aiResponseDTO> askModelWithStages(String userPrompt, String category) {
    //     Mono<List<String>> recommendedTitlesMono = recommendArticleTitles(userPrompt, category, null).cache();
    //     ...
    // }

    // 0226 김소연(수정): askModelWithStages v2 - 벡터+키워드 필터링 후 LLM 전달
    public Flux<aiResponseDTO> askModelWithStages(String userPrompt, String category) {
        if (ragPipelineVersion == 2) return askModelWithStagesV2(userPrompt, category);

        Mono<aiResponseDTO> stage1Start = Mono.just(
                new aiResponseDTO("stage1", "관련 조항을 찾는 중입니다...", null, null));

        // LAW top-10 + MANUAL top-10 분리 벡터 검색 → 최대 20개
        List<chunkDTO> filteredChunks = filterChunksByVectorAndKeyword(userPrompt, category);
        if (filteredChunks.isEmpty()) {
            return Flux.just(
                    new aiResponseDTO("stage1", "관련 조항을 찾는 중입니다...", null, null),
                    new aiResponseDTO("completed", null, null,
                            "해당 분야의 관련 내용을 찾을 수 없습니다. 다른 키워드로 다시 질문해주세요.")
            ).filter(dto -> dto != null && dto.getStage() != null);
        }

        Mono<List<String>> recommendedTitlesMono =
                recommendArticleTitlesFromChunks(userPrompt, filteredChunks).cache();

        Flux<aiResponseDTO> stage2AndFinal = recommendedTitlesMono
                .flatMapMany(recommendedTitles -> {
                    if (recommendedTitles == null || recommendedTitles.isEmpty()) {
                        return Mono.just(new aiResponseDTO("completed", null, null,
                                "해당 분야의 관련 조항을 찾을 수 없습니다. 보다 정확한 법률 용어로 다시 질문해주세요."));
                    }
                    Mono<aiResponseDTO> stage2Start = Mono.just(new aiResponseDTO("stage2",
                            "관련조항을 바탕으로 답변을 생성중입니다! 조금만 기다려주세요!",
                            recommendedTitles, null));
                    Mono<aiResponseDTO> finalAnswer = generateFinalAnswer(userPrompt, recommendedTitles, category)
                            .map(answer -> new aiResponseDTO("completed", null, recommendedTitles, answer));
                    return Flux.concat(stage2Start, finalAnswer);
                });

        return Flux.concat(stage1Start, stage2AndFinal)
                .filter(dto -> dto != null && dto.getStage() != null);
    }

    // ── v2: LLM1 없이 벡터+키워드 top-K → 바로 LLM2 ──────────────────────────
    private Mono<String> askModelV2(String userPrompt, String category) {
        Optional<String> cached = semanticCacheService.findCachedAnswer(userPrompt, category);
        if (cached.isPresent()) {
            System.out.println("[v2][캐시HIT] 즉시 반환: " + userPrompt.substring(0, Math.min(40, userPrompt.length())));
            return Mono.just(cached.get());
        }

        List<chunkDTO> filteredChunks = filterChunksByVectorAndKeyword(userPrompt, category);
        if (filteredChunks.isEmpty()) {
            return Mono.just("해당 분야의 관련 내용을 찾을 수 없습니다. 다른 키워드로 다시 질문해주세요.");
        }

        // MANUAL 우선 정렬 후 top-K 선택
        List<chunkDTO> topChunks = filteredChunks.stream()
                .sorted(Comparator.comparing(c -> "MANUAL".equals(c.getChunkType()) ? 0 : 1))
                .limit(V2_DIRECT_TOP_K)
                .collect(Collectors.toList());

        System.out.printf("[v2] LLM1 생략 — 벡터+키워드 top-%d 청크 직접 LLM2 전달%n", topChunks.size());

        return generateFinalAnswerFromChunks(userPrompt, topChunks, category)
                .doOnSuccess(answer -> {
                    if (answer != null && !answer.isBlank()) {
                        String pendingKey = semanticCacheService.cachePending(userPrompt, answer, category);
                        if (pendingKey != null) semanticCacheService.approvePending(pendingKey);
                    }
                });
    }

    private Flux<aiResponseDTO> askModelWithStagesV2(String userPrompt, String category) {
        Mono<aiResponseDTO> stage1Start = Mono.just(
                new aiResponseDTO("stage1", "관련 조항을 찾는 중입니다...", null, null));

        List<chunkDTO> filteredChunks = filterChunksByVectorAndKeyword(userPrompt, category);
        if (filteredChunks.isEmpty()) {
            return Flux.just(
                    new aiResponseDTO("stage1", "관련 조항을 찾는 중입니다...", null, null),
                    new aiResponseDTO("completed", null, null,
                            "해당 분야의 관련 내용을 찾을 수 없습니다. 다른 키워드로 다시 질문해주세요.")
            ).filter(dto -> dto != null && dto.getStage() != null);
        }

        // MANUAL 우선 정렬 후 top-K 선택
        List<chunkDTO> topChunks = filteredChunks.stream()
                .sorted(Comparator.comparing(c -> "MANUAL".equals(c.getChunkType()) ? 0 : 1))
                .limit(V2_DIRECT_TOP_K)
                .collect(Collectors.toList());

        List<String> chunkTitles = topChunks.stream()
                .map(chunkDTO::getArticleTitle)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .collect(Collectors.toList());

        System.out.printf("[v2] LLM1 생략 — 벡터+키워드 top-%d 청크 직접 LLM2 전달%n", topChunks.size());

        Mono<aiResponseDTO> stage2Start = Mono.just(new aiResponseDTO("stage2",
                "관련조항을 바탕으로 답변을 생성중입니다! 조금만 기다려주세요!",
                chunkTitles, null));

        Mono<aiResponseDTO> finalAnswer = generateFinalAnswerFromChunks(userPrompt, topChunks, category)
                .map(answer -> new aiResponseDTO("completed", null, chunkTitles, answer));

        return Flux.concat(stage1Start, stage2Start, finalAnswer)
                .filter(dto -> dto != null && dto.getStage() != null);
    }

    // v2 전용: 청크 직접 받아 LLM2 호출 (title 조회 단계 없음)
    private Mono<String> generateFinalAnswerFromChunks(String userPrompt, List<chunkDTO> relevantChunks, String category) {
        if (relevantChunks.isEmpty()) {
            return Mono.just("관련 조항을 찾을 수 없습니다.");
        }

        System.out.println("[v2][2단계] 청크 수: " + relevantChunks.size());

        Set<String> sourceFiles = relevantChunks.stream()
                .map(chunkDTO::getFileName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<chunkDTO> lawChunks = new ArrayList<>();
        List<chunkDTO> manualChunks = new ArrayList<>();
        for (chunkDTO c : relevantChunks) {
            if ("MANUAL".equals(c.getChunkType())) manualChunks.add(c);
            else lawChunks.add(c);
        }
        System.out.printf("[v2][2단계] LAW:%d MANUAL:%d%n", lawChunks.size(), manualChunks.size());

        List<messageDTO> messages = new ArrayList<>();
        messages.add(new messageDTO("system",
                """
                너는 공공기관 실무 보조 AI다.
                다음 규칙을 반드시 지켜라:
                1. 모든 답변은 반드시 한글로만 작성한다. 영어, 로마자, 기호는 사용하지 않는다.
                2. 업무매뉴얼 내용이 있으면 실무 절차를 먼저 설명하고, 법령 근거는 보조로 제시한다.
                3. 법령 근거는 답변 맨 마지막에 한 번만 "[근거: 법령명 제○조]" 형식으로 작성한다.
                4. 금액, 수치, 법령 조문 번호는 원문 그대로 유지한다.
                5. 답변은 최대 10개 항목, 각 항목 2~3문장 이내로 간결하게 작성한다.
                6. 불필요한 설명, 반복 문장, 유사 표현을 금지한다.
                7. 제공된 자료에 없는 내용은 절대 추가하지 않는다. 추론·유추·보완 답변을 금지한다.
"""));

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("아래 자료를 참고하여 사용자 질문에 관련된 내용만 답변하세요. 불필요한 설명이나 자료에 없는 내용은 추가하지 마세요:\n\n");

        if (!manualChunks.isEmpty()) {
            contextBuilder.append("=== 업무매뉴얼 ===\n");
            for (chunkDTO chunk : manualChunks) {
                contextBuilder.append("【").append(chunk.getLawName())
                        .append(" - ").append(chunk.getArticleTitle()).append("】\n")
                        .append(chunk.getText()).append("\n\n");
            }
        }
        if (!lawChunks.isEmpty()) {
            contextBuilder.append("=== 관련 법령·조항 ===\n");
            for (chunkDTO chunk : lawChunks) {
                contextBuilder.append("【").append(chunk.getLawName())
                        .append(" ").append(chunk.getArticleNumber())
                        .append(" ").append(chunk.getArticleTitle()).append("】\n")
                        .append(chunk.getText()).append("\n\n");
            }
        }

        try {
            String captionMention = extractCaptionFromChunks(userPrompt, relevantChunks);
            List<com.example.cwmAi.dto.doc_DTO.TableDoc> tablesToAttach = new ArrayList<>();
            if (captionMention != null) {
                tableStore.findByCaption(captionMention, category).ifPresent(tablesToAttach::add);
            }
            if (tablesToAttach.isEmpty()) {
                tablesToAttach.addAll(tableStore.findTop2ByVector(userPrompt, category));
            }
            for (com.example.cwmAi.dto.doc_DTO.TableDoc t : tablesToAttach) {
                contextBuilder.append("\n\n다음 표를 참고하여 답변을 보강하세요:\n")
                        .append(tableStore.renderTableForPrompt(t, 25, 180)).append("\n");
            }
        } catch (Exception ex) {
            System.err.println("[v2][표첨부] 실패: " + ex.getMessage());
        }

        contextBuilder.append("\n질문: ").append(userPrompt);
        messages.add(new messageDTO("user", contextBuilder.toString()));

        return webClient.post().uri("/chat")
                .header("Content-Type", "application/json")
                .bodyValue(new ChatRequest(MODEL_NAME, messages))
                .retrieve().bodyToMono(String.class)
                .map(rawResponse -> {
                    try {
                        responseDTO response = objectMapper.readValue(rawResponse, responseDTO.class);
                        if (response == null || response.getMessage() == null) return "AI 응답을 받지 못했습니다.";
                        String content = response.getContent();
                        if (content != null && !content.trim().isEmpty()) {
                            String cleaned = content.replace("#", "").replace("*", "");
                            if (!sourceFiles.isEmpty()) {
                                // 화면 표시용: ".pdf" 확장자는 제거
                                java.util.List<String> displayNames = sourceFiles.stream()
                                        .map(name -> name != null && name.toLowerCase().endsWith(".pdf")
                                                ? name.substring(0, name.length() - 4)
                                                : name)
                                        .toList();
                                cleaned += "\n\n[참조 파일: " + String.join(", ", displayNames) + "]";
                            }
                            return cleaned;
                        }
                        return "AI 응답을 받지 못했습니다.";
                    } catch (Exception e) {
                        return "AI 응답 파싱 오류: " + e.getMessage();
                    }
                })
                .onErrorResume(e -> Mono.just("AI 호출 중 오류 발생: " + e.getMessage()));
    }

    /**
     * 1단계 질의: 사용자 질문과 해당 분야의 조항 이름 목록을 AI에 전달하여 관련 조항 이름을 추천받는다.
     * @param userPrompt 사용자 질문
     * @param category 카테고리
     * @param fileNames 선택된 파일명 리스트 (null이면 모든 파일)
     * @return 추천받은 조항 이름 리스트 (3~10개)
     */
    /**
     * 1단계: LAW top-10 + MANUAL top-10 벡터 검색 후 LLM1에 최대 20개 제목만 전달
     * 기존(전체 조항 수백 개 → LLM1) 대비 입력 토큰 1/10~1/20 절감 → 응답 속도 향상
     * fileNames 필터 지원
     */
    public Mono<List<String>> recommendArticleTitles(String userPrompt, String category, List<String> fileNames) {
        System.out.printf("[1단계] LAW/MANUAL 분리 벡터 검색 시작 - 카테고리: %s%n", category);

        List<chunkDTO> lawChunks;
        List<chunkDTO> manualChunks;

        if (fileNames != null && !fileNames.isEmpty()) {
            // 파일 선택 시: 해당 파일의 모든 청크를 직접 가져옴 (벡터 top-K 제한 없이)
            Set<String> fileSet = new HashSet<>(fileNames);
            List<chunkDTO> allCategoryChunks = vectorStore.getChunksByCategory(category);
            lawChunks = allCategoryChunks.stream()
                    .filter(c -> fileSet.contains(c.getFileName()) &&
                            "LAW".equals(c.getChunkType() != null ? c.getChunkType() : "LAW"))
                    .collect(Collectors.toList());
            manualChunks = allCategoryChunks.stream()
                    .filter(c -> fileSet.contains(c.getFileName()) &&
                            "MANUAL".equals(c.getChunkType()))
                    .collect(Collectors.toList());
            System.out.printf("[1단계] 파일 직접 검색 - 선택파일:%s → LAW:%d MANUAL:%d%n",
                    fileNames, lawChunks.size(), manualChunks.size());
        } else {
            // 파일 미선택 시: 벡터+키워드 하이브리드 검색
            lawChunks    = filterChunksByType(userPrompt, category, "LAW");
            manualChunks = filterChunksByType(userPrompt, category, "MANUAL");
        }

        // 병합 (MANUAL 우선 — 업무매뉴얼이 있으면 앞쪽에, 중복 제거)
        List<chunkDTO> combined = new ArrayList<>();
        combined.addAll(manualChunks);
        Set<String> addedIds = new LinkedHashSet<>();
        for (chunkDTO c : manualChunks) {
            String key = c.getChunkId() != null ? c.getChunkId() : c.getFileName() + "_" + c.getChunkIndex();
            addedIds.add(key);
        }
        for (chunkDTO c : lawChunks) {
            String key = c.getChunkId() != null ? c.getChunkId() : c.getFileName() + "_" + c.getChunkIndex();
            if (addedIds.add(key)) combined.add(c);
        }

        System.out.printf("[1단계] 벡터 필터 - LAW:%d MANUAL:%d → 합계 %d개 → LLM1 전달%n",
                lawChunks.size(), manualChunks.size(), combined.size());

        if (combined.isEmpty()) {
            System.out.println("[1단계] 관련 청크 없음");
            return Mono.just(new ArrayList<>());
        }

        // 기존 LLM1 호출 (입력이 최대 20개로 축소됨)
        return recommendArticleTitlesFromChunks(userPrompt, combined);
    }
    /**
     * 청크 목록에서 질문과 가장 관련성 높은 별표/표 캡션을 추출한다.
     * - 1순위: 질문 자체에 "별표N"/"표N" 직접 언급
     * - 2순위: 별표를 언급하는 청크 중 질문 키워드와 가장 많이 겹치는 청크의 별표 선택
     *          (첫 번째 매칭 반환 방식 → 오순위 버그 수정)
     */
    private String extractCaptionFromChunks(String question, List<chunkDTO> chunks) {
        java.util.regex.Pattern captionPat =
                java.util.regex.Pattern.compile("[<\\[\\(]?(별표|표)\\s*(\\d+)[>\\]\\)]?");

        // 1) 질문 자체에 별표N 직접 언급 → 최우선
        if (question != null && !question.isBlank()) {
            java.util.regex.Matcher qm = captionPat.matcher(question);
            if (qm.find()) {
                return qm.group(1).replaceAll("\\s+", "") + qm.group(2);
            }
        }

        // 2) 청크별 별표 언급 추출 + 질문-청크 관련성 스코어로 최적 선택
        Set<String> qTokens = extractTokens(question);
        String bestCaption = null;
        int bestScore = -1;

        for (chunkDTO chunk : chunks) {
            if (chunk.getText() == null) continue;
            java.util.regex.Matcher m = captionPat.matcher(chunk.getText());
            if (!m.find()) continue; // 별표 언급 없는 청크 스킵

            String caption = m.group(1).replaceAll("\\s+", "") + m.group(2);

            // 질문 토큰과 청크 제목·번호 겹침으로 관련성 스코어 계산
            int score = countTokenOverlap(qTokens, chunk.getArticleTitle()) * 10
                      + countTokenOverlap(qTokens, chunk.getArticleNumber()) * 5
                      + countTokenOverlap(qTokens,
                            chunk.getText().substring(0, Math.min(300, chunk.getText().length()))) * 2;

            if (score > bestScore) {
                bestScore = score;
                bestCaption = caption;
                System.out.println("[캡션선택] " + caption + " ← " + chunk.getArticleNumber()
                        + " " + chunk.getArticleTitle() + " (score=" + score + ")");
            }
        }
        return bestCaption;
    }

    private Set<String> extractTokens(String s) {
        if (s == null || s.isBlank()) return Collections.emptySet();
        String[] parts = s.split("[\\s,\\-_/()\\[\\]<>{}:;\"']+");
        Set<String> set = new HashSet<>();
        for (String p : parts) {
            if (p.length() >= 2) set.add(p);
        }
        return set;
    }

    private int countTokenOverlap(Set<String> tokens, String text) {
        if (tokens.isEmpty() || text == null || text.isBlank()) return 0;
        Set<String> textTokens = extractTokens(text);
        int count = 0;
        for (String t : tokens) {
            if (textTokens.contains(t)) count++;
        }
        return count;
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

        // 참조 파일명 수집 (캐시 저장 + 답변 끝 표시용)
        Set<String> sourceFiles = relevantChunks.stream()
                .map(chunkDTO::getFileName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 청크를 LAW / MANUAL 로 분리
        List<chunkDTO> lawChunks = new ArrayList<>();
        List<chunkDTO> manualChunks = new ArrayList<>();
        for (chunkDTO c : relevantChunks) {
            if ("MANUAL".equals(c.getChunkType())) manualChunks.add(c);
            else lawChunks.add(c);
        }
        System.out.printf("[2단계] 청크 분류 - LAW:%d MANUAL:%d%n", lawChunks.size(), manualChunks.size());

        List<messageDTO> messages = new ArrayList<>();

        // 시스템 메시지 (업무매뉴얼 우선 + 법령 보조)
        messages.add(new messageDTO(
                "system",
                """
                너는 공공기관 실무 보조 AI다.
                다음 규칙을 반드시 지켜라:
                1. 모든 답변은 반드시 한글로만 작성한다. 영어, 로마자, 기호는 사용하지 않는다.
                2. 업무매뉴얼 내용이 있으면 실무 절차를 먼저 설명하고, 법령 근거는 보조로 제시한다.
                3. 법령 근거는 답변 맨 마지막에 한 번만 "[근거: 법령명 제○조]" 형식으로 작성한다.
                4. 금액, 수치, 법령 조문 번호는 원문 그대로 유지한다.
                5. 답변은 최대 10개 항목, 각 항목 2~3문장 이내로 간결하게 작성한다.
                6. 불필요한 설명, 반복 문장, 유사 표현을 금지한다.
                7. 제공된 자료에 없는 내용은 절대 추가하지 않는다. 추론·유추·보완 답변을 금지한다.
"""
        ));

        // 유저 메시지: 업무매뉴얼 섹션 → 법령 섹션 순서로 컨텍스트 구성
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("아래 자료를 참고하여 사용자 질문에 관련된 내용만 답변하세요. 불필요한 설명이나 자료에 없는 내용은 추가하지 마세요:\n\n");

        if (!manualChunks.isEmpty()) {
            contextBuilder.append("=== 업무매뉴얼 ===\n");
            for (chunkDTO chunk : manualChunks) {
                contextBuilder.append("【").append(chunk.getLawName())
                        .append(" - ").append(chunk.getArticleTitle()).append("】\n")
                        .append(chunk.getText()).append("\n\n");
            }
        }

        if (!lawChunks.isEmpty()) {
            contextBuilder.append("=== 관련 법령·조항 ===\n");
            for (chunkDTO chunk : lawChunks) {
                contextBuilder.append("【").append(chunk.getLawName())
                        .append(" ").append(chunk.getArticleNumber())
                        .append(" ").append(chunk.getArticleTitle()).append("】\n")
                        .append(chunk.getText()).append("\n\n");
            }
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
            // 0225 김소연(수정): 1순위 캡션 직접 지목 → 2순위 벡터 유사도 top-2
            // 이유: <별표3> 같은 직접 언급은 정확하게 매핑, 없으면 셀 내용 의미로 top-2 선택

            // 1순위: 청크 본문에서 "별표N" / "표N" 직접 언급 추출 (질문 관련성 스코어 기반 최적 선택)
            String captionMention = extractCaptionFromChunks(userPrompt, relevantChunks);
            List<com.example.cwmAi.dto.doc_DTO.TableDoc> tablesToAttach = new ArrayList<>();

            if (captionMention != null) {
                tableStore.findByCaption(captionMention, category)
                        .ifPresent(t -> {
                            tablesToAttach.add(t);
                            System.out.println("[표첨부] 1순위 캡션 직접 지목: " + captionMention
                                    + " (page " + t.getPage() + ")");
                        });
            }

            // 2순위: 캡션 못 찾으면 벡터 유사도 top-2
            if (tablesToAttach.isEmpty()) {
                List<com.example.cwmAi.dto.doc_DTO.TableDoc> vectorResult =
                        tableStore.findTop2ByVector(userPrompt, category);
                tablesToAttach.addAll(vectorResult);
                if (!vectorResult.isEmpty()) {
                    System.out.println("[표첨부] 2순위 벡터검색 top-" + vectorResult.size() + "개 첨부");
                } else {
                    System.out.println("[표첨부] 관련 표 없음 (유사도 0.30 미만)");
                }
            }

            // 선택된 표를 컨텍스트에 추가
            for (com.example.cwmAi.dto.doc_DTO.TableDoc t : tablesToAttach) {
                String tableText = tableStore.renderTableForPrompt(t, 25, 180);
                contextBuilder.append("\n\n").append("다음 표를 참고하여 답변을 보강하세요:\n");
                contextBuilder.append(tableText).append("\n");
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
        System.out.println("사용된 청크 수: " + relevantChunks.size()
                + "  (LAW:" + lawChunks.size() + " / MANUAL:" + manualChunks.size() + ")");
        System.out.println("\n--- [2단계] 사용된 청크 정보 ---");
        for (int i = 0; i < relevantChunks.size(); i++) {
            chunkDTO chunk = relevantChunks.get(i);
            String type = "MANUAL".equals(chunk.getChunkType()) ? "MANUAL" : "LAW";
            System.out.printf("  [%2d] %-6s | %-30s | %s %s%n",
                    i + 1, type,
                    chunk.getArticleTitle() != null ? chunk.getArticleTitle() : "(제목없음)",
                    chunk.getLawName() != null ? chunk.getLawName() : "",
                    chunk.getChunkId() != null ? "(ID:" + chunk.getChunkId() + ")" : "");
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

                            if (!sourceFiles.isEmpty()) {
                                // 화면 표시용: ".pdf" 확장자는 제거
                                java.util.List<String> displayNames = sourceFiles.stream()
                                        .map(name -> name != null && name.toLowerCase().endsWith(".pdf")
                                                ? name.substring(0, name.length() - 4)
                                                : name)
                                        .toList();
                                cleanedContent += "\n\n[참조 파일: " + String.join(", ", displayNames) + "]";
                            }

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

            // 4. 줄바꿈 정리: 빈 줄만 제거하고 모든 줄 구조 유지
            // 이유: 법령(제N조) 청킹과 업무매뉴얼(목차 번호) 청킹 모두 줄바꿈이 필요하기 때문
            String[] lines = text.split("\\n");
            StringBuilder cleanedText = new StringBuilder();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (cleanedText.length() > 0) cleanedText.append("\n");
                cleanedText.append(trimmed);
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
                                    // 표 임베딩 계산 (replaceByFile 후 즉시 — 없으면 벡터 기반 표 검색 불가)
                                    if (tables != null) tables.forEach(t -> tableStore.computeAndStoreEmbedding(t));
                                    System.out.println("[표저장] file=" + fileName + ", category=" + fileCategory + ", tables=" + (tables == null ? 0 : tables.size()));
                                } catch (Exception ex) {
                                    System.err.println("[표저장] 실패 file=" + fileName + " / " + ex.getMessage());
                                }
                            }

                            // 0224 김소연(수정): 청크는 기존 텍스트 기반 청킹만 수행(표 병합 없음)
                            // fileName(원본 대소문자) 전달 — fileNames 필터와 대소문자 일치시키기 위함
                            List<chunkDTO> chunks = documentChunker.chunkText(fileName, content, fileCategory);

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

    // 0226 김소연(수정): 벡터+키워드 하이브리드 top-50 필터링
    // 이유: 전체 조항 목록 → LLM 방식 대신 유사도+키워드로 관련 청크 선별
    //       유사도 0.85 이상 우선, 결과 3개 미만이면 top-3 강제 반환(빈 결과 방지)
    /**
     * chunkType("LAW" | "MANUAL") 별 하이브리드 검색 (벡터 + 키워드)
     * - LAW : 유사도 임계값 0.80
     * - MANUAL: 유사도 임계값 0.70 (구어체 표현 커버)
     * LLM 호출 없음 → ms 단위 응답
     */
    // bge-m3 기준 top-K / threshold (AllMiniLmL6V2 대비 점수 범위 다름 → 임계값 낮춤)
    private static final int LAW_TOP_K    = 30;
    private static final int MANUAL_TOP_K = 10;

    private List<chunkDTO> filterChunksByType(String question, String category, String chunkType) {
        int topK = "MANUAL".equals(chunkType) ? MANUAL_TOP_K : LAW_TOP_K;
        float threshold = "MANUAL".equals(chunkType) ? 0.55f : 0.65f;

        // 1) 벡터 검색
        List<chunkDTO> vectorResults = vectorStore.searchByVector(question, topK, category, chunkType, threshold);

        // 2) 키워드 보완 검색 — 매칭 키워드 개수로 점수화 후 정렬 (스토어 순서 의존 제거)
        Set<String> keywords = extractKeywords(question);
        List<chunkDTO> keywordResults = new ArrayList<>();
        if (!keywords.isEmpty()) {
            List<Map.Entry<chunkDTO, Integer>> scored = new ArrayList<>();
            for (chunkDTO c : vectorStore.getChunksByCategory(category)) {
                String ct = c.getChunkType() != null ? c.getChunkType() : "LAW";
                if (!chunkType.equals(ct)) continue;
                String titlePart = c.getArticleTitle() != null ? c.getArticleTitle() : "";
                String textPart  = c.getText() != null ? c.getText() : "";
                int score = 0;
                for (String kw : keywords) {
                    if (titlePart.contains(kw)) score += 2; // 제목 매칭 가중치 2배
                    else if (textPart.contains(kw)) score += 1;
                }
                if (score > 0) scored.add(Map.entry(c, score));
            }
            // 점수 내림차순 정렬 → 관련도 높은 청크가 top-K에 먼저 들어감
            scored.sort((a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<chunkDTO, Integer> e : scored) keywordResults.add(e.getKey());
        }

        // 3) 합집합 (벡터 우선, 키워드 보완 — 중복 제거)
        // 벡터 결과는 searchByVector에서 이미 topK 제한됨
        // 키워드 전용 결과는 topK만큼 추가 허용 → 합계 최대 2*topK
        Map<String, chunkDTO> merged = new LinkedHashMap<>();
        for (chunkDTO c : vectorResults) {
            String key = c.getChunkId() != null ? c.getChunkId() : c.getFileName() + "_" + c.getChunkIndex();
            merged.put(key, c);
        }
        int keywordAdded = 0;
        for (chunkDTO c : keywordResults) {
            String key = c.getChunkId() != null ? c.getChunkId() : c.getFileName() + "_" + c.getChunkIndex();
            if (!merged.containsKey(key)) {
                merged.put(key, c);
                keywordAdded++;
                if (keywordAdded >= topK) break; // 키워드 전용 추가는 topK까지
            }
        }

        List<chunkDTO> result = new ArrayList<>(merged.values());
        System.out.printf("[%s검색] 벡터%d + 키워드추가%d → 합계%d개%n",
                chunkType, vectorResults.size(), keywordAdded, result.size());
        return result;
    }

    // 하위 호환 유지 (askModel, askModelWithStages 내부에서 사용)
    private List<chunkDTO> filterChunksByVectorAndKeyword(String question, String category) {
        List<chunkDTO> lawChunks    = filterChunksByType(question, category, "LAW");
        List<chunkDTO> manualChunks = filterChunksByType(question, category, "MANUAL");
        Map<String, chunkDTO> merged = new LinkedHashMap<>();
        for (chunkDTO c : lawChunks) {
            String key = c.getChunkId() != null ? c.getChunkId() : c.getFileName() + "_" + c.getChunkIndex();
            merged.put(key, c);
        }
        for (chunkDTO c : manualChunks) {
            String key = c.getChunkId() != null ? c.getChunkId() : c.getFileName() + "_" + c.getChunkIndex();
            merged.putIfAbsent(key, c);
        }
        return new ArrayList<>(merged.values());
    }

    // 키워드 추출 (2자 이상, 불용어 제외)
    private Set<String> extractKeywords(String question) {
        Set<String> keywords = new LinkedHashSet<>();
        if (question == null || question.isBlank()) return keywords;
        String[] tokens = question.replaceAll("[^가-힣a-zA-Z0-9\s]", " ").split("\s+");
        Set<String> stopWords = Set.of("은", "는", "이", "가", "을", "를", "의", "에", "에서",
                "로", "으로", "와", "과", "도", "만", "뭐", "뭔", "어떤", "어떻게", "알려줘",
                "무엇", "해줘", "있나요", "있어요", "인가요", "입니까", "알고싶어");
        for (String token : tokens) {
            String t = token.trim();
            if (t.length() >= 2 && !stopWords.contains(t)) keywords.add(t);
        }
        return keywords;
    }

    // 0226 김소연(수정): 필터링된 청크 기준 LLM 1단계 (기존 recommendArticleTitles와 동일 구조)
    // 이유: 전체 목록이 아닌 top-50 필터 청크의 조항 이름만 LLM에 전달 → 토큰 절감
    private Mono<List<String>> recommendArticleTitlesFromChunks(String userPrompt, List<chunkDTO> chunks) {
        // ── [1단계] 입력 청크 전체 출력 ────────────────────────────────────────
        System.out.println("========================================");
        System.out.println("===== [1단계] LLM1 입력 청크 목록 =====");
        System.out.println("========================================");
        System.out.println("총 " + chunks.size() + "개 청크");
        int lawCnt = 0, manualCnt = 0;
        for (int i = 0; i < chunks.size(); i++) {
            chunkDTO c = chunks.get(i);
            String type = "MANUAL".equals(c.getChunkType()) ? "MANUAL" : "LAW";
            if ("MANUAL".equals(type)) manualCnt++; else lawCnt++;
            System.out.printf("  [%2d] %-6s | %-30s | %s%n",
                    i + 1, type,
                    c.getArticleTitle() != null ? c.getArticleTitle() : "(제목없음)",
                    c.getChunkId() != null ? c.getChunkId() : "-");
        }
        System.out.println("  → LAW:" + lawCnt + "개  MANUAL:" + manualCnt + "개");
        System.out.println("========================================");

        // MANUAL 청크를 목록 앞쪽에 배치 → 소형 모델이 앞쪽을 우선 선택하는 경향 활용
        Map<String, String> titleToId = new LinkedHashMap<>();
        for (chunkDTO c : chunks) { // MANUAL 먼저
            if ("MANUAL".equals(c.getChunkType())) {
                String title = c.getArticleTitle();
                if (title != null && !title.isBlank() && !titleToId.containsKey(title))
                    titleToId.put(title, c.getChunkId());
            }
        }
        for (chunkDTO c : chunks) { // LAW 나중
            if (!"MANUAL".equals(c.getChunkType())) {
                String title = c.getArticleTitle();
                if (title != null && !title.isBlank() && !titleToId.containsKey(title))
                    titleToId.put(title, c.getChunkId());
            }
        }
        if (titleToId.isEmpty()) return Mono.just(new ArrayList<>());

        // [매뉴얼]/[법령] 태그 붙여서 LLM이 구분 가능하게 표시
        // ※ 태그는 표시용 — 파싱 시 제거하므로 Stage 2 제목 매칭에 영향 없음
        Map<String, String> titleTag = new LinkedHashMap<>(); // title → tag("매뉴얼"/"법령")
        for (chunkDTO c : chunks) {
            String title = c.getArticleTitle();
            if (title != null && !title.isBlank() && !titleTag.containsKey(title))
                titleTag.put(title, "MANUAL".equals(c.getChunkType()) ? "매뉴얼" : "법령");
        }

        List<String> titles = new ArrayList<>(titleToId.keySet());
        StringBuilder titlesText = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            String title = titles.get(i);
            String tag = titleTag.getOrDefault(title, "법령");
            titlesText.append((i + 1)).append(". [").append(tag).append("] ").append(title);
            if (i < titles.size() - 1) titlesText.append(" ");
        }

        List<messageDTO> messages = new ArrayList<>();
        messages.add(new messageDTO("system",
                """
                너는 법령 조항과 업무매뉴얼 섹션을 분석하는 전문가다.
                중요 규칙:
                1. 반드시 JSON 배열 형식으로만 답변한다. 예: ["조항이름1", "조항이름2"]
                2. 이름은 제공된 목록에서 정확히 선택해야 한다. [매뉴얼]/[법령] 태그는 포함하지 않는다.
                3. 2개 이상 7개 이하로 추천한다.
                4. 한글로만 답변한다.
                5. [매뉴얼] 항목이 질문과 관련 있으면 반드시 우선 선택한다.
                """));
        messages.add(new messageDTO("user", String.format(
                "사용자 질문: %s\n조항 목록(앞쪽 [매뉴얼] 항목 우선):%s\n" +
                "질문과 가장 관련 높은 조항 이름을 2~7개 JSON 배열로만 답변하세요. 태그([매뉴얼],[법령])는 제외하고 이름만 작성하세요.",
                userPrompt, titlesText)));

        System.out.println("[1단계] 필터링된 조항 수: " + titles.size() + "개 → LLM 전달 (MANUAL 앞배치)");

        return webClient.post().uri("/chat")
                .header("Content-Type", "application/json")
                .bodyValue(new ChatRequest(MODEL_NAME, messages))
                .retrieve().bodyToMono(String.class)
                .map(raw -> {
                    try {
                        responseDTO resp = objectMapper.readValue(raw, responseDTO.class);
                        if (resp == null || resp.getMessage() == null) return new ArrayList<String>();
                        String c = resp.getContent();
                        if (c == null || c.isBlank()) return new ArrayList<String>();
                        int s = c.indexOf('['), e = c.lastIndexOf(']');
                        if (s >= 0 && e > s) c = c.substring(s, e + 1);
                        @SuppressWarnings("unchecked")
                        List<String> res = objectMapper.readValue(c, List.class);

                        // LLM이 반환한 제목을 titleToId의 실제 저장 제목으로 교정
                        // 이유: LLM이 제목을 약간 수정하면 Stage 2 exact-match가 실패해 청크 0개 반환
                        List<String> corrected = new ArrayList<>();
                        for (String llmTitle : res) {
                            String t = llmTitle.trim();
                            if (titleToId.containsKey(t)) {
                                corrected.add(t); // 정확히 일치
                            } else {
                                // 퍼지 매칭: contains 방향 양쪽 검사
                                String best = null;
                                for (String stored : titleToId.keySet()) {
                                    if (stored.contains(t) || t.contains(stored)) {
                                        best = stored;
                                        break;
                                    }
                                }
                                if (best != null) {
                                    System.out.println("[1단계] 제목 교정: \"" + t + "\" → \"" + best + "\"");
                                    corrected.add(best);
                                } else {
                                    System.out.println("[1단계] 매칭 실패 (원본 유지): \"" + t + "\"");
                                    corrected.add(t);
                                }
                            }
                        }
                        System.out.println("[1단계] 추천 조항 (교정 후): " + corrected);
                        return corrected;
                    } catch (Exception ex) {
                        System.err.println("[1단계] 파싱 오류: " + ex.getMessage());
                        return new ArrayList<String>();
                    }
                })
                .onErrorResume(e -> {
                    System.err.println("[1단계] 오류: " + e.getMessage());
                    return Mono.just(new ArrayList<>());
                });
    }

}