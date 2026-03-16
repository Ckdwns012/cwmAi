package com.example.cwmAi.Controller;

import com.example.cwmAi.Util.SecurityPathUtil;
import com.example.cwmAi.dto.FileDeleteRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.cwmAi.Service.aiService;

import java.io.File;
import java.io.IOException;
import java.net.URI;
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

    private static final String UPLOAD_DIR;
    private static final String FILE_DIR;
    private static final String FORMAT_DIR;
    static {
        File baseDir;
        try {
            URI location = uploadController.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(location);
            // JAR로 실행 시: path가 jar 파일 → 그 부모 디렉터리를 기준으로 uploads/format 사용
            if (Files.isRegularFile(path)) {
                baseDir = path.getParent().toFile();
            } else {
                // IDE 등에서 실행 시: user.dir(프로젝트 루트) 기준
                baseDir = new File(System.getProperty("user.dir"));
            }
        } catch (Exception e) {
            baseDir = new File(System.getProperty("user.dir"));
        }
        UPLOAD_DIR = new File(baseDir, "uploads").getAbsolutePath();
        FILE_DIR   = UPLOAD_DIR;
        FORMAT_DIR = new File(baseDir, "format").getAbsolutePath();
        if (!new File(UPLOAD_DIR).exists()) new File(UPLOAD_DIR).mkdirs();
        if (!new File(FORMAT_DIR).exists()) new File(FORMAT_DIR).mkdirs();
    }
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "경영전략", "고객복지", "정보화", "퇴직공제", "회계총무"
    );

    /**
     * JAR와 같은 디렉터리의 uploads, format 폴더만 사용합니다.
     * 배포 시 해당 폴더를 JAR 옆에 두고 파일을 넣으면 됩니다. (JAR 내부 복사 없음)
     */
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
        return lower.endsWith(".pdf") ||
                lower.endsWith(".txt") ||
                lower.endsWith(".hwp") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".png") ||
                lower.endsWith(".jpeg");
    }
}
