package com.iot.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.model.TelemetryMessage;
import com.iot.platform.websocket.IotWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RealtimePushService {

    private final IotWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void broadcastTelemetry(TelemetryMessage message) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "telemetry");
            body.put("protocol", message.protocol());
            body.put("deviceId", message.deviceId());
            body.put("payload", message.payload());
            body.put("receivedAt", message.receivedAt().toString());
            webSocketHandler.broadcast(objectMapper.writeValueAsString(body));
        } catch (Exception ignored) {
            // 推送失败不影响主流程
        }
    }
}
