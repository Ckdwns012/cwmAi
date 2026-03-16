package com.example.cwmAi.Util;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class jwtAuthenticationFilter extends OncePerRequestFilter {

    private final jwtUtil jwtUtil;

    // JWT 검사를 제외할 URL 패턴
    private static final List<String> EXCLUDE_URLS = Arrays.asList(
            "/loginPage",
            "/login",
            "/signUp",
            "/signIn",
            "/logout",
            "/checkId",
            "/css/",
            "/js/",
            "/images/"
    );

    public jwtAuthenticationFilter(jwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        //  1) 예외 URL 이면 토큰 검사 안 하고 바로 통과
        for(String exclude : EXCLUDE_URLS) {
            if (path.startsWith(exclude)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        //  2) 쿠키에서 JWT 읽기
        String token = null;
        if(request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("accessToken".equals(cookie.getName())) {
                    token = cookie.getValue();
                }
            }
        }

        //  3) 토큰이 없으면 차단
        if (token == null) {
            handleUnauthorized(request, response);
            return;
        }

        //  4) 토큰 검증
        try {
            String userId = jwtUtil.validateAndGetId(token);
            request.setAttribute("userId", userId);
        } catch (Exception e) {
            handleUnauthorized(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    // 페이지 요청(브라우저 새로고침)이면 loginPage로 리다이렉트,
    // API 요청(fetch/XHR)이면 401 반환
    private void handleUnauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String accept = request.getHeader("Accept");
        String requestedWith = request.getHeader("X-Requested-With");
        String path = request.getRequestURI();

        boolean isApiRequest = path.startsWith("/lm/api/") || path.startsWith("/api/")
                || "XMLHttpRequest".equals(requestedWith)
                || (accept != null && accept.contains("application/json") && !accept.contains("text/html"));

        if (isApiRequest) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired JWT");
        } else {
            response.sendRedirect("/loginPage");
        }
    }
}
