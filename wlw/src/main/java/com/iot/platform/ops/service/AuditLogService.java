package com.iot.platform.ops.service;

import com.iot.platform.ops.entity.AuditLogEntry;
import com.iot.platform.ops.repo.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void log(String action, String detail) {
        String user = currentUsername();
        AuditLogEntry e = new AuditLogEntry();
        e.setUsername(user);
        e.setAction(action);
        e.setDetail(detail != null && detail.length() > 2000 ? detail.substring(0, 2000) : detail);
        e.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(e);
    }

    public List<AuditLogEntry> recent(int max) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(max, 500))));
    }

    private static String currentUsername() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getPrincipal())) {
            return "anonymous";
        }
        return a.getName();
    }
}
