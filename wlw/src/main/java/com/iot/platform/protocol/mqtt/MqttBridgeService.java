package com.iot.platform.protocol.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.IotProperties;
import com.iot.platform.config.IotProperties.Mqtt.BrokerProfile;
import com.iot.platform.model.TelemetryMessage;
import com.iot.platform.service.IngestionPipeline;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class MqttBridgeService {

    private final IotProperties iotProperties;
    private final IngestionPipeline ingestionPipeline;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<ManagedClient> managedClients = Collections.synchronizedList(new ArrayList<ManagedClient>());

    @PostConstruct
    public void start() {
        if (!iotProperties.getMqtt().isEnabled()) {
            log.info("MQTT 未启用 (iot.mqtt.enabled=false)，可在 application.yml 打开并配置 broker / brokers");
            return;
        }
        List<BrokerProfile> profiles = iotProperties.getMqtt().resolveBrokerProfiles();
        if (profiles.isEmpty()) {
            log.warn("MQTT 已启用但未配置任何 Broker 连接（brokers 为空且未回退到 broker-url）");
            return;
        }
        for (BrokerProfile p : profiles) {
            if (p.getBrokerUrl() == null || p.getBrokerUrl().trim().isEmpty()) {
                log.warn("跳过 MQTT 连接 id={}：broker-url 为空", p.getId());
                continue;
            }
            if (p.getSubscribeTopics() == null || p.getSubscribeTopics().isEmpty()) {
                log.warn("跳过 MQTT 连接 id={}：subscribe-topics 为空", p.getId());
                continue;
            }
            try {
                managedClients.add(connectOne(p));
            } catch (Exception e) {
                log.error("MQTT 连接失败 [{}] {}: {}", p.getId(), p.getBrokerUrl(), e.getMessage());
            }
        }
    }

    private ManagedClient connectOne(BrokerProfile p) throws MqttException {
        final String profileId = p.getId() != null && !p.getId().trim().isEmpty() ? p.getId().trim() : "default";
        String clientIdBase = hasText(p.getClientId()) ? p.getClientId().trim() : "wlw-iot-platform";
        String clientId = clientIdBase + "-" + profileId + "-" + System.currentTimeMillis();
        MqttClient c = new MqttClient(p.getBrokerUrl().trim(), clientId, new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        MqttDirectSocketFactory.applyUnlessSsl(opts, p.getBrokerUrl().trim());
        if (hasText(p.getUsername())) {
            opts.setUserName(p.getUsername().trim());
        }
        if (hasText(p.getPassword())) {
            opts.setPassword(p.getPassword().toCharArray());
        }
        c.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                try {
                    for (String topic : p.getSubscribeTopics()) {
                        if (topic == null || topic.trim().isEmpty()) {
                            continue;
                        }
                        c.subscribe(topic.trim(), 1);
                        log.info("MQTT[{}] 已订阅: {}", profileId, topic.trim());
                    }
                } catch (Exception e) {
                    log.error("MQTT[{}] 订阅失败: {}", profileId, e.getMessage());
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT[{}] 连接丢失: {}", profileId, cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String body = new String(message.getPayload(), StandardCharsets.UTF_8);
                Map<String, Object> payload = new HashMap<>();
                payload.put("topic", topic);
                payload.put("body", body);
                payload.put("mqttBrokerId", profileId);
                String deviceId = topic;
                try {
                    JsonNode node = objectMapper.readTree(body);
                    String fromJson = resolveDeviceIdFromJson(node);
                    if (fromJson != null) {
                        deviceId = fromJson;
                    }
                    if (node.isObject()) {
                        payload.put("json", objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
                        }));
                    }
                } catch (Exception ignored) {
                }
                ingestionPipeline.ingest("MQTT", deviceId, payload);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        c.connect(opts);
        log.info("MQTT[{}] 已连接: {}", profileId, p.getBrokerUrl().trim());
        return new ManagedClient(profileId, c);
    }

    /**
     * 向 {@code publishTopicPrefix + subTopic} 发布；使用指定 Broker 连接 id，空则使用第一个已连接客户端。
     */
    public void publish(String brokerId, String subTopic, String payload) throws MqttException {
        if (!iotProperties.getMqtt().isEnabled()) {
            throw new IllegalStateException("MQTT 未启用");
        }
        ManagedClient mc = pickClientForPublish(brokerId);
        if (mc == null || mc.client == null || !mc.client.isConnected()) {
            throw new IllegalStateException("MQTT 未连接");
        }
        String topic = iotProperties.getMqtt().getPublishTopicPrefix() + subTopic;
        MqttMessage mq = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        mq.setQos(1);
        mc.client.publish(topic, mq);
    }

    public void publish(String subTopic, String payload) throws MqttException {
        publish(null, subTopic, payload);
    }

    public void fanoutTelemetryIfEnabled(TelemetryMessage msg) {
        if (!iotProperties.getMqtt().isEnabled() || !iotProperties.getMqtt().isFanoutEnabled()) {
            return;
        }
        if (msg == null || msg.deviceId() == null || msg.deviceId().trim().isEmpty()) {
            return;
        }
        String fanBid = iotProperties.getMqtt().getFanoutBrokerId();
        ManagedClient mc = pickClientForPublish(hasText(fanBid) ? fanBid.trim() : null);
        if (mc == null || mc.client == null || !mc.client.isConnected()) {
            return;
        }
        try {
            String subTopic = iotProperties.getMqtt().getFanoutSubTopicPrefix() + topicSafeDeviceId(msg.deviceId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("protocol", msg.protocol());
            body.put("deviceId", msg.deviceId());
            body.put("payload", msg.payload());
            body.put("receivedAt", msg.receivedAt().toString());
            String topic = iotProperties.getMqtt().getPublishTopicPrefix() + subTopic;
            MqttMessage mq = new MqttMessage(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
            mq.setQos(1);
            mc.client.publish(topic, mq);
        } catch (Exception e) {
            log.warn("MQTT 扇出失败: {}", e.getMessage());
        }
    }

    private ManagedClient pickClientForPublish(String brokerId) {
        synchronized (managedClients) {
            if (managedClients.isEmpty()) {
                return null;
            }
            if (hasText(brokerId)) {
                for (ManagedClient mc : managedClients) {
                    if (brokerId.equals(mc.id) && mc.client != null && mc.client.isConnected()) {
                        return mc;
                    }
                }
            }
            for (ManagedClient mc : managedClients) {
                if (mc.client != null && mc.client.isConnected()) {
                    return mc;
                }
            }
        }
        return null;
    }

    private static String topicSafeDeviceId(String deviceId) {
        return deviceId.trim().replace('/', '_').replace('+', '_').replace('#', '_');
    }

    private static String resolveDeviceIdFromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String v;
        if (node.hasNonNull("deviceId")) {
            v = node.get("deviceId").asText().trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        if (node.hasNonNull("device_id")) {
            v = node.get("device_id").asText().trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        if (node.hasNonNull("deviceSn")) {
            v = node.get("deviceSn").asText().trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        if (node.hasNonNull("device_sn")) {
            v = node.get("device_sn").asText().trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    @PreDestroy
    public void stop() {
        synchronized (managedClients) {
            for (ManagedClient mc : managedClients) {
                if (mc.client != null && mc.client.isConnected()) {
                    try {
                        mc.client.disconnect();
                    } catch (MqttException ignored) {
                    }
                }
            }
            managedClients.clear();
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static final class ManagedClient {
        final String id;
        final MqttClient client;

        ManagedClient(String id, MqttClient client) {
            this.id = id;
            this.client = client;
        }
    }
}
