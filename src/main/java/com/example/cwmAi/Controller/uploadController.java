package com.example.cwmAi.Controller;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    
    // JAR 파일 실행 위치 기준 상대 경로 (uploads 폴더)
    private static final String UPLOAD_DIR;
    private static final String FILE_DIR;
    
    static {
        // 상대 경로를 절대 경로로 변환 (JAR 실행 위치 기준)
        UPLOAD_DIR = new File("uploads").getAbsolutePath();
        FILE_DIR = UPLOAD_DIR;
        // uploads 폴더가 없으면 생성
        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
    }
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "계약",
            "개인정보보호",
            "정보보안",
            "정보화사업",
            "공제사업"
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
        return "uploadPage"; // uploadPage.html 뷰 반환
    }

    // JSON 응답을 위해 @ResponseBody 추가, 반환 타입 UploadResponse로 변경
    @PostMapping("/upload")
    @ResponseBody
    public UploadResponse uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("category") String category,
            jakarta.servlet.http.HttpServletRequest request
    ) {
        // admin 체크
        String userId = (String) request.getAttribute("userId");
        if (userId == null || !"admin".equals(userId)) {
            return new UploadResponse("error", "권한이 없습니다. 관리자만 파일을 업로드할 수 있습니다.");
        }
        if (file.isEmpty()) {
            return new UploadResponse("error", "업로드할 파일이 없습니다.");
        }
        try {
            // 카테고리별 폴더 없으면 생성
            File uploadDir = (category == null || category.isBlank())
                    ? new File(UPLOAD_DIR)
                    : new File(UPLOAD_DIR, category);
            if (!uploadDir.exists()) uploadDir.mkdirs();

            String filename = StringUtils.cleanPath(file.getOriginalFilename());
            File dest = Paths.get(uploadDir.getPath(), filename).toFile();
            file.transferTo(dest);
            // 업로드 완료 후 메모리 저장소를 최신 상태로 갱신
            aiService.reloadCategory(category);
            return new UploadResponse("success", "파일 업로드 성공: " + filename);
        } catch (IOException e) {
            e.printStackTrace();
            return new UploadResponse("error", "파일 업로드 실패: " + e.getMessage());
        }
    }

    // [수정] Thymeleaf 뷰 반환 엔드포인트
    @GetMapping("/files")
    public String listFiles(Model model) {
        File folder = new File(UPLOAD_DIR);

        // 파일 필터링: 숨김파일 제외, 파일만 표시
        String[] files = folder.list((dir, name) -> {
            // 숨김 파일 제외
            if (name.startsWith(".")) return false;

            // 확장자 필터링(원하면 추가)
            String lower = name.toLowerCase();
            return lower.endsWith(".pdf") ||
                    lower.endsWith(".txt") ||
                    lower.endsWith(".hwp") ||
                    lower.endsWith(".jpg") ||
                    lower.endsWith(".png") ||
                    lower.endsWith(".jpeg");
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
        File folder = (category == null || category.isBlank())
                ? new File(FILE_DIR)
                : new File(FILE_DIR, category);

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


    @GetMapping("/files/delete")
    @ResponseBody
    public String deleteFile(
            @RequestParam String filename,
            @RequestParam("category") String category,
            jakarta.servlet.http.HttpServletRequest request
    ) {
        // admin 체크
        String userId = (String) request.getAttribute("userId");
        if (userId == null || !"admin".equals(userId)) {
            return "권한이 없습니다. 관리자만 파일을 삭제할 수 있습니다.";
        }
        File baseDir = (category == null || category.isBlank())
                ? new File(FILE_DIR)
                : new File(FILE_DIR, category);
        File file = new File(baseDir, filename);
        if (file.exists() && file.isFile()) {
            if (file.delete()) {
                // 파일 삭제 후 메모리 저장소를 최신 상태로 갱신
                aiService.reloadCategory(category);
                return "삭제 성공: " + filename;
            } else {
                return "삭제 실패: " + filename;
            }
        } else {
            return "파일이 존재하지 않습니다: " + filename;
        }
    }

    @GetMapping("/files/download")
    public ResponseEntity<Resource> downloadFile(
            @RequestParam String filename,
            @RequestParam(value = "category", required = false) String category
    ) {
        try {
            Path basePath = (category == null || category.isBlank())
                    ? Paths.get(UPLOAD_DIR)
                    : Paths.get(UPLOAD_DIR, category);
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
