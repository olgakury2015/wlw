package com.iot.platform.ops.repo;

import com.iot.platform.ops.entity.ApiKeyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyRecord, Long> {

    List<ApiKeyRecord> findAllByOrderByCreatedAtDesc();

    Optional<ApiKeyRecord> findBySecretSha256AndEnabledTrue(String secretSha256);
}
