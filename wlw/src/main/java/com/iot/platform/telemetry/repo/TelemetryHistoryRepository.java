package com.iot.platform.telemetry.repo;

import com.iot.platform.telemetry.entity.TelemetryHistoryRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TelemetryHistoryRepository extends JpaRepository<TelemetryHistoryRecord, Long> {

    List<TelemetryHistoryRecord> findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtDesc(
            String deviceId, Instant from, Instant to, Pageable pageable);

    List<TelemetryHistoryRecord> findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtAsc(
            String deviceId, Instant from, Instant to, Pageable pageable);

    long countByDeviceIdAndReceivedAtBetween(String deviceId, Instant from, Instant to);
}
