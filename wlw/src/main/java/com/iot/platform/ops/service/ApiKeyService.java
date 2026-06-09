package com.iot.platform.ops.service;

import com.iot.platform.ops.entity.ApiKeyRecord;
import com.iot.platform.ops.repo.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public List<ApiKeyRecord> listAll() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * @return 明文密钥（仅创建时返回一次）
     */
    @Transactional
    public String createKey(String label) {
        String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        raw = raw.substring(0, 48);
        ApiKeyRecord r = new ApiKeyRecord();
        r.setLabel(label != null ? label.trim() : "未命名");
        r.setSecretSha256(sha256Hex(raw));
        r.setEnabled(true);
        r.setCreatedAt(LocalDateTime.now());
        apiKeyRepository.save(r);
        return raw;
    }

    @Transactional
    public void disable(Long id) {
        apiKeyRepository.findById(id).ifPresent(k -> {
            k.setEnabled(false);
            apiKeyRepository.save(k);
        });
    }

    @Transactional
    public boolean validateAndTouch(String rawKey) {
        if (rawKey == null || rawKey.trim().isEmpty()) {
            return false;
        }
        String h = sha256Hex(rawKey.trim());
        Optional<ApiKeyRecord> opt = apiKeyRepository.findBySecretSha256AndEnabledTrue(h);
        if (!opt.isPresent()) {
            return false;
        }
        ApiKeyRecord k = opt.get();
        k.setLastUsedAt(LocalDateTime.now());
        apiKeyRepository.save(k);
        return true;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
