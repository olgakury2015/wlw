package com.iot.platform.protocol.mqtt;

import com.iot.platform.service.TelemetryIngestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 遥测入库后由平台向 Broker 发布，便于外部客户端<strong>只订阅</strong>即可消费（与设备侧 publish 主题分离）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MqttTelemetryFanoutListener {

    private final MqttBridgeService mqttBridgeService;

    @Async
    @EventListener
    public void onTelemetryIngested(TelemetryIngestedEvent event) {
        try {
            mqttBridgeService.fanoutTelemetryIfEnabled(event.getMessage());
        } catch (Exception e) {
            log.debug("MQTT fanout 监听异常: {}", e.getMessage());
        }
    }
}
