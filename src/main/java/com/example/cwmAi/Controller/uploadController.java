package com.example.cwmAi.Controller;

import com.example.cwmAi.Util.SecurityPathUtil;
import com.example.cwmAi.dto.FileDeleteRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.cwmAi.Service.aiService;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

// 응답을 위한 간단한 DTO 클래스 (UploadResponse.java 파일로 별도 생성 권장)
class UploadResponse {
    public String status;
    public String message;
    public UploadResponse(String status, String message) {
        this.status = status;
        this.message = message;
    }
}

@Controller
public class uploadController {
    @Autowired
    private aiService aiService;
    
    @Autowired
    private ResourceLoader resourceLoader;
    
    // JAR 파일 실행 위치 기준 상대 경로
    private static final String UPLOAD_DIR;   // 업로드된 파일 루트 (uploads)
    private static final String FILE_DIR;     // 카테고리용 파일 루트 (현재는 uploads와 동일)
    private static final String FORMAT_DIR;   // 업로드와 별도 보관하는 양식 폴더 (format)
    
    static {
        // 상대 경로를 절대 경로로 변환 (JAR 실행 위치 기준)
        UPLOAD_DIR = new File("uploads").getAbsolutePath();
        FILE_DIR   = UPLOAD_DIR;
        FORMAT_DIR = new File("format").getAbsolutePath();
        
        // uploads 폴더가 없으면 생성
        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
        // format 폴더는 선택적으로 사용하므로, 존재하면 그대로 사용하고
        // 없으면 자동 생성까진 강제하지 않는다(필요 시 수동 생성).
    }
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "경영전략",
            "고객복지",
            "정보화",
            "퇴직공제",
            "회계총무"
    );

    /**
     * JAR 내부의 uploads 리소스를 외부 uploads 폴더로 복사
     * 애플리케이션 시작 시 한 번만 실행
     */
    @PostConstruct
    public void copyUploadsFromJar() {
        try {
            // ResourceLoader가 ResourcePatternResolver를 구현하는지 확인
            ResourcePatternResolver resolver = resourceLoader instanceof ResourcePatternResolver 
                ? (ResourcePatternResolver) resourceLoader
                : new org.springframework.core.io.support.PathMatchingResourcePatternResolver(resourceLoader);
            
            // JAR 내부의 uploads 폴더의 모든 파일 찾기
            Resource[] resources = resolver.getResources("classpath:uploads/**");
            
            if (resources.length == 0) {
                // JAR 내부에 uploads 폴더가 없으면 (개발 환경 등) 그냥 리턴
                return;
            }
            
            // JAR 환경인지 확인 (개발 환경에서는 파일 시스템 경로를 사용하므로 복사하지 않음)
            boolean isJarEnvironment = false;
            for (Resource resource : resources) {
                try {
                    String uri = resource.getURI().toString();
                    if (uri.startsWith("jar:")) {
                        isJarEnvironment = true;
                        break;
                    }
                } catch (Exception e) {
                    // URI를 가져올 수 없으면 건너뛰기
                    continue;
                }
            }
            
            // JAR 환경이 아니면 (로컬 개발 환경) 복사하지 않음
            if (!isJarEnvironment) {
                return;
            }
            
            int copiedCount = 0;
            for (Resource resource : resources) {
                if (!resource.isReadable() || resource.getFilename() == null) {
                    continue;
                }
                
                String resourcePath = resource.getURI().toString();
                // classpath:uploads/... 또는 jar:file:.../uploads/... 형태
                String relativePath = resourcePath.contains("uploads/") 
                    ? resourcePath.substring(resourcePath.indexOf("uploads/") + 8) 
                    : resource.getFilename();
                
                // 디렉토리는 건너뛰기 (파일만 복사)
                if (relativePath.endsWith("/")) {
                    continue;
                }
                
                // 외부 파일 경로 생성
                File destFile = new File(UPLOAD_DIR, relativePath);
                
                // 기존 파일이 있으면 건너뛰기 (덮어쓰지 않음)
                if (destFile.exists()) {
                    continue;
                }
                
                // 디렉토리 생성
                destFile.getParentFile().mkdirs();
                
                // 파일 복사
                try (InputStream inputStream = resource.getInputStream()) {
                    Files.copy(inputStream, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                }
            }
            
            if (copiedCount > 0) {
                System.out.println("JAR 내부에서 " + copiedCount + "개의 파일을 외부 uploads 폴더로 복사했습니다.");
            }
        } catch (Exception e) {
            // JAR 내부에 uploads가 없거나 복사 실패 시 오류 무시 (정상적인 경우일 수 있음)
            System.out.println("JAR 내부의 uploads 폴더를 외부로 복사하는 중 오류 발생 (무시됨): " + e.getMessage());
        }
    }
    @RequestMapping("uploadPage")
    public String uploadPage(){
        return "uploadPage";
    }

    @PostMapping("/upload")
    @ResponseBody
    public UploadResponse uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("category") String category,
            HttpServletRequest request
    ) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null || !"admin".equals(userId)) {
            return new UploadResponse("error", "권한이 없습니다. 관리자만 파일을 업로드할 수 있습니다.");
        }
        if (file.isEmpty()) {
            return new UploadResponse("error", "업로드할 파일이 없습니다.");
        }

        try {
            // category 검증
            SecurityPathUtil.validateSafeCategory(category);

            String filename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
            SecurityPathUtil.validateSafeFilename(filename);

            // 확장자 화이트리스트 체크 (업로드에도 적용!)
            if (!hasAllowedExtension(filename)) {
                return new UploadResponse("error", "허용되지 않은 파일 확장자입니다.");
            }

            File uploadDir = (category == null || category.isBlank())
                    ? new File(UPLOAD_DIR)
                    : new File(UPLOAD_DIR, category);

            if (!uploadDir.exists()) uploadDir.mkdirs();

            Path basePath = uploadDir.toPath().toAbsolutePath().normalize();
            Path destPath = SecurityPathUtil.safeResolve(basePath, filename);

            // 덮어쓰기 정책: 기본은 "거부"
            if (Files.exists(destPath)) {
                return new UploadResponse("error", "같은 파일명이 이미 존재합니다. 파일명을 변경해 업로드해주세요.");
            }

            Files.copy(file.getInputStream(), destPath, StandardCopyOption.REPLACE_EXISTING);

            // 비동기 재로딩: 파일 저장 후 즉시 응답, 백그라운드에서 파싱+임베딩 처리
            aiService.reloadCategoryAsync(category);

            return new UploadResponse("success",
                    "파일 저장 완료: " + filename + "\n백그라운드에서 분석 중입니다. 잠시 후 검색에 반영됩니다.");
        } catch (IllegalArgumentException e) {
            return new UploadResponse("error", "요청 값이 올바르지 않습니다: " + e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            return new UploadResponse("error", "파일 업로드 실패: " + e.getMessage());
        }
    }

    // [수정] Thymeleaf 뷰 반환 엔드포인트
    @GetMapping("/files")
    public String listFiles(Model model) {
        File folder = new File(UPLOAD_DIR);
        String[] files = folder.list((dir, name) -> {
            if (name.startsWith(".")) return false;
            return hasAllowedExtension(name);
        });
        model.addAttribute("files", files);
        return "fileListPage";
    }

    // 사용 가능한 분야 목록 반환 (디렉터리 + 기본값)
    @GetMapping("/api/categories")
    @ResponseBody
    public List<String> listCategories() {
        File baseDir = new File(FILE_DIR);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        File[] dirs = baseDir.listFiles(file ->
                file.isDirectory() && !file.isHidden()
        );

        Set<String> categories = new HashSet<>(DEFAULT_CATEGORIES);
        if (dirs != null) {
            for (File dir : dirs) {
                categories.add(dir.getName());
            }
        }

        return categories.stream()
                .sorted()
                .collect(Collectors.toList());
    }

    // 분야 추가 (디렉토리 생성)
    @PostMapping("/api/categories/add")
    @ResponseBody
    public String addCategory(
            @RequestParam String category,
            jakarta.servlet.http.HttpServletRequest request
    ) {
        // admin 체크
        String userId = (String) request.getAttribute("userId");
        if (userId == null || !"admin".equals(userId)) {
            return "권한이 없습니다. 관리자만 분야를 추가할 수 있습니다.";
        }

        if (category == null || category.isBlank()) {
            return "분야명을 입력해주세요.";
        }

        try {
            // 입력값 정리 (공백 제거, 특수문자 제거 등)
            String trimmedCategory = category.trim();
            
            // 기본 분야 목록에 이미 있으면 에러
            if (DEFAULT_CATEGORIES.contains(trimmedCategory)) {
                return "이미 존재하는 분야입니다.";
            }

            // 디렉토리 생성
            File categoryDir = new File(FILE_DIR, trimmedCategory);
            if (categoryDir.exists()) {
                return "이미 존재하는 분야입니다.";
            }

            if (categoryDir.mkdirs()) {
                return "success";
            } else {
                return "분야 추가 실패: 디렉토리를 생성할 수 없습니다.";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "분야 추가 실패: " + e.getMessage();
        }
    }

    // [추가] JavaScript fetch API를 위한 JSON 반환 엔드포인트 (카테고리별)
    @GetMapping("/api/files")
    @ResponseBody // JSON 반환
    public List<String> apiListFiles(@RequestParam(value = "category", required = false) String category) {
        // 카테고리별 디렉터리가 없으면 빈 리스트 반환 (오류 대신 안전하게 처리)
        File folder;
        if (category == null || category.isBlank()) {
            folder = new File(FILE_DIR);
        } else if ("format".equals(category)) {
            // 양식 전용 카테고리는 uploads가 아닌 별도 format 폴더에서 조회
            folder = new File(FORMAT_DIR);
        } else {
            folder = new File(FILE_DIR, category);
        }

        if (!folder.exists()) {
            return List.of();
        }

        File[] files = folder.listFiles(file ->
                file.isFile() &&
                        !file.isHidden() &&
                        hasAllowedExtension(file.getName())
        );

        if (files == null) {
            return List.of();
        }

        return Arrays.stream(files)
                .map(File::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 삭제: GET -> DELETE로 변경
     */
    @DeleteMapping("/api/files")
    @ResponseBody
    public String deleteFile(
            @RequestBody FileDeleteRequest req,
            HttpServletRequest request
    ) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null || !"admin".equals(userId)) {
            return "권한이 없습니다. 관리자만 파일을 삭제할 수 있습니다.";
        }

        if (req == null || req.getFilename() == null) {
            return "삭제 실패: filename이 없습니다.";
        }

        String filename = req.getFilename();
        String category = req.getCategory();

        try {
            SecurityPathUtil.validateSafeCategory(category);
            SecurityPathUtil.validateSafeFilename(filename);

            Path basePath = (category == null || category.isBlank())
                    ? Paths.get(FILE_DIR)
                    : Paths.get(FILE_DIR, category);

            Path targetPath = SecurityPathUtil.safeResolve(basePath, filename);

            if (!Files.exists(targetPath) || !Files.isRegularFile(targetPath)) {
                return "파일이 존재하지 않습니다: " + filename;
            }

            Files.delete(targetPath);

            // 해당 파일을 참조한 캐시 항목 먼저 제거 (카테고리 전체 삭제보다 정밀)
            aiService.invalidateCacheByFile(filename, category);
            // 비동기 재로딩
            aiService.reloadCategoryAsync(category);
            return "삭제 성공: " + filename;

        } catch (IllegalArgumentException e) {
            return "삭제 실패: 요청 값이 올바르지 않습니다.";
        } catch (IOException e) {
            e.printStackTrace();
            return "삭제 실패: " + e.getMessage();
        }
    }

    @GetMapping("/files/download")
    public ResponseEntity<Resource> downloadFile(
            @RequestParam String filename,
            @RequestParam(value = "category", required = false) String category
    ) {
        try {
            Path basePath;
            if (category == null || category.isBlank()) {
                basePath = Paths.get(UPLOAD_DIR);
            } else if ("format".equals(category)) {
                // 양식 파일은 프로젝트 루트의 format 폴더에서 다운로드
                basePath = Paths.get(FORMAT_DIR);
            } else {
                basePath = Paths.get(UPLOAD_DIR, category);
            }
            Path filePath = basePath.resolve(filename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists()) {
                String contentType = "application/octet-stream";
                
                // ContentDisposition 빌더를 사용하여 한글 파일명 지원
                ContentDisposition contentDisposition = ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build();
                
                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                        .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().build();
        }
    }

    // 업로드 및 조회 시 허용할 파일 확장자 체크
    private boolean hasAllowedExtension(String name) {
        String lower = name.toLowerCase();
        // 현재 PDF만 파싱 지원. 추후 다른 확장자 파싱 기능 추가 시 아래 주석 해제
        return lower.endsWith(".pdf");
        // return lower.endsWith(".pdf") ||
        //         lower.endsWith(".txt") ||
        //         lower.endsWith(".hwp") ||
        //         lower.endsWith(".jpg") ||
        //         lower.endsWith(".png") ||
        //         lower.endsWith(".jpeg");
    }
}
