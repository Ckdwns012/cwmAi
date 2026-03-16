package com.example.cwmAi.Config;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 실행 위치(현재 작업 디렉터리, user.dir)의 config.txt를 읽어 key=value 맵으로 반환.
 * 없으면 빈 맵. # 으로 시작하는 줄과 빈 줄은 무시.
 */
public final class ConfigLoader {

    public static final String CONFIG_FILENAME = "config.txt";

    /**
     * 현재 작업 디렉터리 기준 상대 경로로 config.txt 로드.
     * - IDE 실행: 프로젝트 루트에서 실행하면 ./config.txt
     * - JAR 실행: java -jar 를 실행한 현재 디렉터리의 ./config.txt
     */
    public static Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        File baseDir = new File(System.getProperty("user.dir"));
        File configFile = new File(baseDir, CONFIG_FILENAME);
        if (!configFile.exists() || !configFile.isFile()) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    out.put(key, value);
                }
            }
        } catch (Exception e) {
            // 로드 실패 시 빈 맵 유지
        }
        return out;
    }
}
