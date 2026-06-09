package com.iot.platform.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class TelemetryMessage {

    private final String protocol;
    private final String deviceId;
    private final Map<String, Object> payload;
    private final Instant receivedAt;

    private TelemetryMessage(String protocol, String deviceId, Map<String, Object> payload, Instant receivedAt) {
        this.protocol = protocol;
        this.deviceId = deviceId;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public static TelemetryMessage of(String protocol, String deviceId, Map<String, Object> payload) {
        return new TelemetryMessage(protocol, deviceId, payload, Instant.now());
    }

    public String protocol() {
        return protocol;
    }

    public String deviceId() {
        return deviceId;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public Instant receivedAt() {
        return receivedAt;
    }
}
