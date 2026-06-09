package com.iot.platform.video.zlm;

import com.iot.platform.config.IotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时从 ZLM config.ini 同步 [api] secret，避免 yml 与 MediaServer 目录配置不一致。
 */
@Slf4j
@Component
public class ZlmIniSecretSync {

    public ZlmIniSecretSync(IotProperties iotProperties) {
        ZlmProperties zlm = iotProperties.getVideo().getZlm();
        if (!StringUtils.hasText(zlm.getConfigIni())) {
            return;
        }
        Path path = Paths.get(zlm.getConfigIni().trim());
        if (!Files.isRegularFile(path)) {
            log.warn("ZLM config.ini 不存在: {}", path.toAbsolutePath());
            return;
        }
        String fromIni = readApiSecret(path);
        if (!StringUtils.hasText(fromIni)) {
            log.warn("ZLM config.ini 未找到 [api] secret: {}", path.toAbsolutePath());
            return;
        }
        String trimmed = fromIni.trim();
        if (!trimmed.equals(zlm.getSecret())) {
            log.info("ZLM secret 已从 config.ini 同步（{}）", path.toAbsolutePath());
        }
        zlm.setSecret(trimmed);
    }

    static String readApiSecret(Path iniPath) {
        try {
            boolean inApi = false;
            for (String line : Files.readAllLines(iniPath, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith(";") || t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("[") && t.endsWith("]")) {
                    inApi = "[api]".equalsIgnoreCase(t);
                    continue;
                }
                if (inApi && t.regionMatches(true, 0, "secret=", 0, 7)) {
                    return t.substring(7).trim();
                }
            }
        } catch (Exception e) {
            log.warn("读取 ZLM config.ini 失败 {}: {}", iniPath, e.getMessage());
        }
        return "";
    }
}
