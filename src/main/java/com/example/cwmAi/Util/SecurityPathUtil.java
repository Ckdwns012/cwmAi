//0223 추가(김소연)_파일 경로 보안 처리
package com.example.cwmAi.Util;

import java.nio.file.Path;
import java.util.Set;

public final class SecurityPathUtil {
    private SecurityPathUtil() {}

    // OS 경로구분자/상위경로 이동 방지
    private static final Set<String> FORBIDDEN_TOKENS = Set.of("..", "/", "\\", "\0");

    public static void validateSafeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename is empty");
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (filename.contains(token)) {
                throw new IllegalArgumentException("Invalid filename");
            }
        }
    }

    public static void validateSafeCategory(String category) {
        if (category == null) return;
        String c = category.trim();
        if (c.isEmpty()) return;

        // 한글/영문/숫자/공백/언더바/대시만 허용 (길이 1~30)
        if (!c.matches("^[가-힣a-zA-Z0-9 _-]{1,30}$")) {
            throw new IllegalArgumentException("Invalid category");
        }
        // 혹시 모르니 상위경로/구분자도 방지
        for (String token : FORBIDDEN_TOKENS) {
            if (c.contains(token)) {
                throw new IllegalArgumentException("Invalid category");
            }
        }
    }

    public static Path safeResolve(Path basePath, String filename) {
        validateSafeFilename(filename);
        Path normalizedBase = basePath.toAbsolutePath().normalize();
        Path resolved = normalizedBase.resolve(filename).normalize();

        // base 밖으로 나가면 차단 (Path traversal 방어 핵심)
        if (!resolved.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Path traversal detected");
        }
        return resolved;
    }
}