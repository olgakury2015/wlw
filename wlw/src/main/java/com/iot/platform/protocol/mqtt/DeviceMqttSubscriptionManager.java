package com.iot.platform.protocol.mqtt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.management.entity.Device;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.service.IngestionPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按设备档案中的 Broker/主题/账号，为每台 MQTT 设备建立<strong>独立</strong>订阅连接（与 {@link MqttBridgeService} 全局配置并存）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceMqttSubscriptionManager {

    private final DeviceRepository deviceRepository;
    private final IngestionPipeline ingestionPipeline;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, MqttClient> clientsByDeviceId = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        deviceRepository.findAll().forEach(d -> {
            if ("MQTT".equalsIgnoreCase(d.getProtocol())) {
                syncDevice(d.getId());
            }
        });
    }

    public void syncDevice(Long deviceId) {
        disconnect(deviceId);
        deviceRepository.findById(deviceId).ifPresent(this::connectIfConfigured);
    }

    public void removeDevice(Long deviceId) {
        disconnect(deviceId);
    }

    /**
     * 本进程内是否为该设备持有<strong>已连通</strong>的独立 MQTT 客户端（用于详情页诊断）。
     * 与档案里的「设备在线」不同：后者仅在成功处理遥测并匹配设备编号后才会刷新。
     */
    public boolean isLinked(Long deviceId) {
        if (deviceId == null) {
            return false;
        }
        MqttClient c = clientsByDeviceId.get(deviceId);
        return c != null && c.isConnected();
    }

    private void disconnect(Long deviceId) {
        MqttClient c = clientsByDeviceId.remove(deviceId);
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect();
                }
            } catch (Exception e) {
                log.debug("MQTT 设备连接断开 id={}: {}", deviceId, e.getMessage());
            }
        }
    }

    private void connectIfConfigured(Device d) {
        if (!"MQTT".equalsIgnoreCase(d.getProtocol())) {
            return;
        }
        String hostRaw = trim(d.getMqttRemoteHost());
        if (hostRaw == null) {
            return;
        }
        String topic = trim(d.getMqttSubscribeTopic());
        if (topic == null) {
            log.warn("MQTT 设备 id={} 已填 Broker 但未填订阅主题，跳过独立连接", d.getId());
            return;
        }
        int port = d.getMqttRemotePort() != null && d.getMqttRemotePort() > 0 ? d.getMqttRemotePort() : 1883;
        String brokerUri = toPahoServerUri(hostRaw, port);
        final String deviceSn = d.getDeviceSn();
        final Long did = d.getId();
        try {
            // Paho 要求非空 ClientId；每台设备每次连接唯一，避免与自身或其它客户端互踢
            String clientId = "wlw-dev-" + did + "-" + System.currentTimeMillis();
            MqttClient client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setKeepAliveInterval(60);
            opts.setConnectionTimeout(60);
            MqttDirectSocketFactory.applyUnlessSsl(opts, brokerUri);
            String u = trim(d.getMqttUsername());
            if (u != null) {
                opts.setUserName(u);
            }
            String pw = d.getMqttPassword();
            if (pw != null && !pw.isEmpty()) {
                opts.setPassword(pw.toCharArray());
            }
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    if (!reconnect) {
                        return;
                    }
                    try {
                        client.subscribe(topic, 1);
                        log.info("MQTT[设备 id={} sn={}] 重连后已重新订阅 {}", did, deviceSn, topic);
                    } catch (Exception e) {
                        log.error("MQTT[设备 id={}] 重连后订阅失败: {}", did, e.getMessage());
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT[设备 id={}] 连接丢失: {}", did, cause.getMessage());
                }

                @Override
                public void messageArrived(String t, MqttMessage message) {
                    String body = new String(message.getPayload(), StandardCharsets.UTF_8);
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("topic", t);
                    payload.put("body", body);
                    payload.put("mqttPerDevice", Boolean.TRUE);
                    payload.put("mqttDeviceRowId", did);
                    payload.put("mqttDeviceBindingSn", deviceSn);
                    String deviceId = deviceSn;
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
                    try {
                        ingestionPipeline.ingest("MQTT", deviceId, payload);
                    } catch (Exception e) {
                        log.error("MQTT[设备 id={} sn={}] 处理消息失败 topic={}: {}", did, deviceSn, t, e.getMessage(), e);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            client.connect(opts);
            // 与常见示例一致：连接成功后在本线程立即订阅（connectComplete 仍会再订一次，幂等）
            try {
                client.subscribe(topic, 1);
                log.info("MQTT[设备 id={} sn={}] 已订阅 {}", did, deviceSn, topic);
            } catch (Exception e) {
                log.error("MQTT[设备 id={}] 连接后订阅失败: {}", did, e.getMessage());
            }
            clientsByDeviceId.put(did, client);
            log.info("MQTT[设备 id={} sn={}] 已连接 {}", did, deviceSn, brokerUri);
        } catch (MqttException e) {
            log.error("MQTT[设备 id={} sn={}] 连接失败 uri={} reasonCode={} msg={}",
                    did, deviceSn, brokerUri, e.getReasonCode(), e.getMessage(), e);
            logTcpRefusedHints(e, brokerUri);
        } catch (Exception e) {
            log.error("MQTT[设备 id={} sn={}] 连接失败 uri={}: {}", did, deviceSn, brokerUri, e.getMessage(), e);
        }
    }

    /**
     * reasonCode=32103 且根因为 ConnectException 时，多为网络/监听端口/JVM 代理，非业务代码拼错 URI。
     */
    private void logTcpRefusedHints(MqttException e, String brokerUri) {
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            if (!(c instanceof ConnectException)) {
                continue;
            }
            String m = c.getMessage() != null ? c.getMessage() : "";
            if (m.toLowerCase(Locale.ROOT).contains("refused")) {
                log.warn("MQTT 根因「Connection refused」：对端在 {} 上无进程接受 TCP，或防火墙返回 RST；"
                        + "与 Client ID、订阅主题无关。请确认：① Broker 是否监听 0.0.0.0:1883（而非仅 127.0.0.1）；"
                        + "② 云安全组/系统防火墙是否放行本机到该地址的出站 1883；③ 运行 Spring 的机器与 MQTTX 是否同一网络出口。"
                        + "若堆栈含 SocksSocketImpl，请检查 IDEA/JVM 是否设置了 -DsocksProxyHost、-DsocksProxyPort 或 java.net.useSystemProxies=true。",
                        brokerUri);
            } else if (m.toLowerCase(Locale.ROOT).contains("timed out")) {
                log.warn("MQTT 根因「timed out」：到 {} 路由不通或被丢包，请查网络/VPN/安全组。", brokerUri);
            }
            break;
        }
    }

    /**
     * Eclipse Paho 3.x 使用 {@code tcp://} / {@code ssl://}；工具里常见的 {@code mqtt://}、{@code mqtts://} 在此规范化。
     * URI 未带端口时合并档案中的端口（表单默认 1883）。
     */
    static String toPahoServerUri(String hostRaw, int fallbackPort) {
        String h = hostRaw.trim();
        if (h.isEmpty()) {
            throw new IllegalArgumentException("empty host");
        }
        String lower = h.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mqtt://")) {
            h = "tcp://" + h.substring("mqtt://".length());
        } else if (lower.startsWith("mqtts://")) {
            h = "ssl://" + h.substring("mqtts://".length());
        }
        // 规范 tcp:////119.x 或 tcp:///119.x（主机前多写了 /）
        h = collapseExtraSlashesAfterScheme(h);
        if (!h.contains("://")) {
            // 避免「主机框填了 119.45.1.168:1883、端口框又填 1883」拼成 tcp://...:1883:1883
            if (bareHostHasTrailingPort(h)) {
                return "tcp://" + h;
            }
            return "tcp://" + h + ":" + Math.max(1, fallbackPort);
        }
        try {
            URI u = new URI(h);
            String scheme = u.getScheme();
            if (scheme == null) {
                return h;
            }
            if (u.getHost() == null || u.getHost().isEmpty()) {
                return h;
            }
            if (u.getPort() > 0) {
                return u.toString();
            }
            int p = Math.max(1, fallbackPort);
            return new URI(scheme, u.getUserInfo(), u.getHost(), p, u.getPath(), u.getQuery(), u.getFragment()).toString();
        } catch (Exception e) {
            return h;
        }
    }

    /**
     * 无 scheme 的「host:port」整串（如 119.45.1.168:1883），末尾为合法 TCP 端口则视为已带端口。
     */
    private static boolean bareHostHasTrailingPort(String h) {
        int colon = h.lastIndexOf(':');
        if (colon <= 0 || colon >= h.length() - 1) {
            return false;
        }
        String hostPart = h.substring(0, colon);
        String portPart = h.substring(colon + 1);
        if (hostPart.indexOf(':') >= 0) {
            // 简单规避含多冒号的情形（如未加括号的 IPv6），交给后续 URI 或直连失败日志排查
            return false;
        }
        if (!portPart.matches("[0-9]{1,5}")) {
            return false;
        }
        try {
            int p = Integer.parseInt(portPart);
            return p > 0 && p <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String collapseExtraSlashesAfterScheme(String uri) {
        int pos = uri.indexOf("://");
        if (pos < 0) {
            return uri;
        }
        String head = uri.substring(0, pos + 3);
        String tail = uri.substring(pos + 3);
        while (tail.startsWith("/")) {
            tail = tail.substring(1);
        }
        return head + tail;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
}
