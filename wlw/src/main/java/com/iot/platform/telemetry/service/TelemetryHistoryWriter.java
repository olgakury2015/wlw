package com.iot.platform.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.model.TelemetryMessage;
import com.iot.platform.telemetry.entity.TelemetryHistoryRecord;
import com.iot.platform.telemetry.repo.TelemetryHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TelemetryHistoryWriter {

    private final TelemetryHistoryRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void append(TelemetryMessage msg) {
        TelemetryHistoryRecord r = new TelemetryHistoryRecord();
        r.setDeviceId(msg.deviceId() != null ? msg.deviceId() : "");
        r.setProtocol(msg.protocol() != null ? msg.protocol() : "");
        r.setReceivedAt(msg.receivedAt());
        try {
            r.setPayloadJson(objectMapper.writeValueAsString(msg.payload()));
        } catch (JsonProcessingException e) {
            r.setPayloadJson("{}");
        }
        repository.save(r);
    }
}
