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
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "JWT not found");
            return;
        }

        //  4) 토큰 검증
        try {
            String userId = jwtUtil.validateAndGetId(token);
            request.setAttribute("userId", userId);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired JWT");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
