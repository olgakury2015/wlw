package com.iot.platform.protocol.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.gateway.GatewayUplinkProtocol;
import com.iot.platform.management.entity.IotGateway;
import com.iot.platform.management.repo.GatewayRepository;
import com.iot.platform.service.IngestionPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 网关档案 MQTT 上行：平台按网关配置独立订阅 Broker。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayMqttSubscriptionManager {

    private final GatewayRepository gatewayRepository;
    private final IngestionPipeline ingestionPipeline;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, MqttClient> clientsByGatewayId = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        gatewayRepository.findAll().forEach(g -> {
            if (GatewayUplinkProtocol.MQTT.name().equalsIgnoreCase(g.getUplinkProtocol())) {
                syncGateway(g.getId());
            }
        });
    }

    public void syncGateway(Long gatewayId) {
        disconnect(gatewayId);
        gatewayRepository.findById(gatewayId).ifPresent(this::connectIfConfigured);
    }

    public void removeGateway(Long gatewayId) {
        disconnect(gatewayId);
    }

    @EventListener
    public void onGatewayMqttSubscriptionEvent(GatewayMqttSubscriptionEvent event) {
        if (event == null || event.getGatewayId() == null) {
            return;
        }
        if (event.getAction() == GatewayMqttSubscriptionEvent.Action.REMOVE) {
            removeGateway(event.getGatewayId());
        } else {
            syncGateway(event.getGatewayId());
        }
    }

    private void disconnect(Long gatewayId) {
        MqttClient c = clientsByGatewayId.remove(gatewayId);
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect();
                }
            } catch (Exception e) {
                log.debug("MQTT 网关断开 id={}: {}", gatewayId, e.getMessage());
            }
        }
    }

    private void connectIfConfigured(IotGateway g) {
        if (!GatewayUplinkProtocol.MQTT.name().equalsIgnoreCase(g.getUplinkProtocol())) {
            return;
        }
        String hostRaw = trim(g.getMqttRemoteHost());
        String topic = trim(g.getMqttSubscribeTopic());
        if (hostRaw == null || topic == null) {
            return;
        }
        int port = g.getMqttRemotePort() != null && g.getMqttRemotePort() > 0 ? g.getMqttRemotePort() : 1883;
        String brokerUri = DeviceMqttSubscriptionManager.toPahoServerUri(hostRaw, port);
        final String gatewaySn = g.getGatewaySn();
        final Long gid = g.getId();
        try {
            String clientId = "wlw-gw-" + gid + "-" + System.currentTimeMillis();
            MqttClient client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setKeepAliveInterval(60);
            opts.setConnectionTimeout(60);
            MqttDirectSocketFactory.applyUnlessSsl(opts, brokerUri);
            String u = trim(g.getMqttUsername());
            if (u != null) {
                opts.setUserName(u);
            }
            String pw = g.getMqttPassword();
            if (pw != null && !pw.isEmpty()) {
                opts.setPassword(pw.toCharArray());
            }
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    if (reconnect) {
                        try {
                            client.subscribe(topic, 1);
                        } catch (Exception e) {
                            log.warn("MQTT[网关 {}] 重连订阅失败: {}", gatewaySn, e.getMessage());
                        }
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT[网关 {}] 连接丢失: {}", gatewaySn, cause != null ? cause.getMessage() : "");
                }

                @Override
                public void messageArrived(String t, MqttMessage message) {
                    String body = new String(message.getPayload(), StandardCharsets.UTF_8);
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("topic", t);
                    payload.put("body", body);
                    payload.put("mqttGateway", Boolean.TRUE);
                    payload.put("gatewaySn", gatewaySn);
                    String deviceId = gatewaySn;
                    try {
                        JsonNode node = objectMapper.readTree(body);
                        String fromJson = resolveDeviceId(node);
                        if (fromJson != null) {
                            deviceId = fromJson;
                        }
                        if (node.isObject()) {
                            payload.put("json", objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
                            }));
                        }
                    } catch (Exception ignored) {
                    }
                    payload.put("gatewayId", gatewaySn);
                    ingestionPipeline.ingest("MQTT", deviceId, payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            client.connect(opts);
            client.subscribe(topic, 1);
            clientsByGatewayId.put(gid, client);
            log.info("MQTT[网关 sn={}] 已连接并订阅 {}", gatewaySn, topic);
        } catch (Exception e) {
            log.error("MQTT[网关 sn={}] 连接失败: {}", gatewaySn, e.getMessage());
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String resolveDeviceId(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : new String[]{"deviceId", "device_id", "deviceSn", "device_sn"}) {
            if (node.hasNonNull(key)) {
                String v = node.get(key).asText().trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }
}
