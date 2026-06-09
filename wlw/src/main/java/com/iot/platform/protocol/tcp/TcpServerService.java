package com.iot.platform.protocol.tcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.IotProperties;
import com.iot.platform.service.IngestionPipeline;
import com.iot.platform.management.service.GatewayPresenceService;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 简易 TCP 文本接入：每行一条 UTF-8 文本（与 Node-RED / Python 客户端一行一 JSON 的常见写法一致）。
 * 若为 JSON，设备标识优先顺序：{@code deviceId}、{@code device_id}、{@code deviceSn}、{@code device_sn}（须与平台「设备编号」一致以便标在线）；
 * 否则使用对端地址作为设备标识。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TcpServerService {

    private final IotProperties iotProperties;
    private final IngestionPipeline ingestionPipeline;
    private final GatewayPresenceService gatewayPresenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutorService acceptExecutor;
    private ExecutorService clientExecutor;
    private volatile ServerSocket serverSocket;

    @PostConstruct
    public void start() {
        if (!iotProperties.getTcp().isEnabled()) {
            log.info("TCP 接入未启用 (iot.tcp.enabled=false)");
            return;
        }
        acceptExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "iot-tcp-accept");
            t.setDaemon(true);
            return t;
        });
        clientExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "iot-tcp-client");
            t.setDaemon(true);
            return t;
        });
        acceptExecutor.submit(this::runAcceptLoop);
    }

    private void runAcceptLoop() {
        int port = iotProperties.getTcp().getPort();
        String host = iotProperties.getTcp().getBindHost();
        try {
            InetAddress addr = InetAddress.getByName(host);
            serverSocket = new ServerSocket(port, 50, addr);
            log.info("TCP 接入已监听 {}:{}", host, port);
            while (!serverSocket.isClosed()) {
                Socket client = serverSocket.accept();
                clientExecutor.submit(() -> handleClient(client));
            }
        } catch (Exception e) {
            if (serverSocket != null && !serverSocket.isClosed()) {
                log.error("TCP 服务异常: {}", e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        String remote = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        boolean ack = iotProperties.getTcp().isSendLineAck();
        BufferedWriter writer = null;
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            if (ack) {
                writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                Map<String, Object> payload = new HashMap<>();
                payload.put("raw", line);
                payload.put("remote", remote);
                String deviceId = remote;
                try {
                    JsonNode node = objectMapper.readTree(line);
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
                gatewayPresenceService.onTcpLine(line, deviceId);
                ingestionPipeline.ingest("TCP", deviceId, payload);
                if (writer != null) {
                    try {
                        writer.write("{\"ok\":true}\n");
                        writer.flush();
                    } catch (Exception writeEx) {
                        log.debug("TCP 应答写入失败 {}: {}", remote, writeEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("TCP 客户端 {} 结束: {}", remote, e.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 与控制台「设备编号」对齐：支持 camelCase / snake_case，便于与示例脚本字段 {@code device_id} 一致。
     */
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
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
        }
        if (acceptExecutor != null) {
            acceptExecutor.shutdownNow();
        }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }
    }
}
