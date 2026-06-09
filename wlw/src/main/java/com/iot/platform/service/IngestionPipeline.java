package com.iot.platform.service;

import com.iot.platform.alert.service.ThresholdRuleEvaluationService;
import com.iot.platform.management.service.DeviceRegistryService;
import com.iot.platform.management.service.GatewayPresenceService;
import com.iot.platform.model.TelemetryMessage;
import com.iot.platform.telemetry.service.TelemetryHistoryWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionPipeline {

    private final TelemetryHub telemetryHub;
    private final RealtimePushService realtimePushService;
    private final DeviceRegistryService deviceRegistryService;
    private final GatewayPresenceService gatewayPresenceService;
    private final TelemetryHistoryWriter telemetryHistoryWriter;
    private final ThresholdRuleEvaluationService thresholdRuleEvaluationService;
    private final ApplicationEventPublisher eventPublisher;

    public void ingest(String protocol, String deviceId, Map<String, Object> payload) {
        TelemetryMessage msg = TelemetryMessage.of(protocol, deviceId, payload);
        telemetryHub.publish(msg);
        try {
            telemetryHistoryWriter.append(msg);
        } catch (Exception ignored) {
            // 持久化失败不阻断实时通道
        }
        realtimePushService.broadcastTelemetry(msg);
        try {
            deviceRegistryService.touchIfRegistered(msg);
        } catch (Exception e) {
            log.warn("touchIfRegistered 失败 deviceId={} protocol={}: {}", msg.deviceId(), msg.protocol(),
                    e.getMessage());
        }
        try {
            gatewayPresenceService.onTelemetry(msg);
        } catch (Exception e) {
            log.warn("gatewayPresence 失败 deviceId={}: {}", msg.deviceId(), e.getMessage());
        }
        try {
            thresholdRuleEvaluationService.onTelemetry(msg);
        } catch (Exception ignored) {
        }
        eventPublisher.publishEvent(new TelemetryIngestedEvent(msg));
    }
}
