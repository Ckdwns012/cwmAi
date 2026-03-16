/* 
package com.example.cwmAi.Util;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class jwtUtil {
    private final Key key = Keys.secretKeyFor(SignatureAlgorithm.HS256);

    // 토큰 생성
    public String createToken(String id) {
        return Jwts.builder()
                .setSubject(id)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60)) // 1시간
                .signWith(key)
                .compact();
    }

    // 토큰 검증
    public String validateAndGetId(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }
}
*/

//0223 수정(김소연)_jwtUtil 파일 생성 , 키 관리 application.properties로 분리
package com.example.cwmAi.Util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
public class jwtUtil {

    private final Key key;

    public jwtUtil(@Value("${security.jwt.secret}") String secret) {
        // 최소 길이 방어 (HS256은 충분한 길이 필요)
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("security.jwt.secret must be at least 32 chars");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 토큰 생성 (3시간)
    public String createToken(String id) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(id)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + 1000L * 60 * 60 * 3))
                .signWith(key)
                .compact();
    }

    public String validateAndGetId(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }
}