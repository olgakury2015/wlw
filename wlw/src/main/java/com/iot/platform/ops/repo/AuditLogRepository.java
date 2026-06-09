package com.iot.platform.ops.repo;

import com.iot.platform.ops.entity.AuditLogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    List<AuditLogEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
