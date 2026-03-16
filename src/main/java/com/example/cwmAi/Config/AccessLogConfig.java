package com.example.cwmAi.Config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * JAR 기준 log/ 폴더에 컨트롤러 요청만 월별 access 로그를 기록합니다.
 * 정적 리소스(/images/, /js/, /css/, *.png 등)는 제외합니다.
 */
@Configuration
public class AccessLogConfig {

    private static final List<String> SKIP_PREFIXES = Arrays.asList(
            "/images/", "/js/", "/css/", "/static/", "/favicon.ico"
    );
    private static final List<String> SKIP_EXTENSIONS = Arrays.asList(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg", ".webp",
            ".css", ".js", ".woff", ".woff2", ".ttf", ".eot"
    );
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern(
            "dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    public static File getLogDirectory() {
        File baseDir;
        try {
            URI location = AccessLogConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(location);
            if (Files.isRegularFile(path)) {
                baseDir = path.getParent().toFile();
            } else {
                baseDir = new File(System.getProperty("user.dir"));
            }
        } catch (Exception e) {
            baseDir = new File(System.getProperty("user.dir"));
        }
        File logDir = new File(baseDir, "log");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        return logDir;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> accessLogFilter() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                if (shouldSkip(request)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                long startNanos = System.nanoTime();
                filterChain.doFilter(request, response);
                long elapsedMicros = (System.nanoTime() - startNanos) / 1_000;
                String remoteAddr = getRemoteAddr(request);
                String queryString = request.getQueryString();
                if (queryString != null) {
                    try {
                        queryString = URLDecoder.decode(queryString, StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                        // 디코딩 실패 시 원본 유지
                    }
                }
                String requestLine = request.getMethod() + " " + request.getRequestURI()
                        + (queryString != null && !queryString.isEmpty() ? "?" + queryString : "")
                        + " " + request.getProtocol();
                int status = response.getStatus();
                String dateStr = java.time.Instant.now().atZone(ZoneId.systemDefault()).format(DATE_FORMAT);
                String logLine = String.format("%s - - [%s] \"%s\" %d - %d%n",
                        remoteAddr, dateStr, requestLine, status, elapsedMicros);
                appendToLog(logLine);
            }

            private boolean shouldSkip(HttpServletRequest req) {
                String path = req.getRequestURI();
                if (path == null) return true;
                // 캐시 체크(문서 초기화 폴링)는 로그에서 제외
                if (path.contains("cache-check")) return true;
                for (String prefix : SKIP_PREFIXES) {
                    if (path.startsWith(prefix)) return true;
                }
                path = path.toLowerCase(Locale.ROOT);
                for (String ext : SKIP_EXTENSIONS) {
                    if (path.endsWith(ext)) return true;
                }
                return false;
            }

            private String getRemoteAddr(HttpServletRequest req) {
                String xff = req.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
                return req.getRemoteAddr() != null ? req.getRemoteAddr() : "-";
            }

            private void appendToLog(String line) {
                try {
                    File logDir = getLogDirectory();
                    String month = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    Path file = logDir.toPath().resolve("access_log." + month + ".log");
                    Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (Exception ignored) { }
            }
        });
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return bean;
    }
}
