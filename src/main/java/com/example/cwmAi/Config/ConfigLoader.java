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
 * JAR와 같은 디렉터리의 config.txt를 읽어 key=value 맵으로 반환.
 * 없으면 빈 맵. # 으로 시작하는 줄과 빈 줄은 무시.
 */
public final class ConfigLoader {

    public static final String CONFIG_FILENAME = "config.txt";

    /**
     * JAR 기준 같은 디렉터리에서 config.txt 로드.
     * IDE 등에서 실행 시에는 user.dir 기준.
     */
    public static Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        File baseDir;
        try {
            URI location = ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(location);
            if (Files.isRegularFile(path)) {
                baseDir = path.getParent().toFile();
            } else {
                baseDir = new File(System.getProperty("user.dir"));
            }
        } catch (Exception e) {
            baseDir = new File(System.getProperty("user.dir"));
        }
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
