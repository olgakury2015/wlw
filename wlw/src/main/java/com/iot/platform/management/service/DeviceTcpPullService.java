package com.iot.platform.management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.management.entity.Device;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.service.IngestionPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 设备作为 TCP 服务端（如 MicroPython <code>server.bind + accept</code>）时，
 * 平台作为客户端连接并读取一行数据，再写入遥测。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceTcpPullService {

    private final DeviceRepository deviceRepository;
    private final IngestionPipeline ingestionPipeline;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${iot.device-tcp.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${iot.device-tcp.read-timeout-ms:8000}")
    private int readTimeoutMs;

    public static boolean isTcpPullDevice(Device d) {
        if (d == null || d.getTcpRemoteHost() == null || d.getTcpRemotePort() == null) {
            return false;
        }
        if (d.getTcpRemoteHost().trim().isEmpty() || d.getTcpRemotePort() <= 0) {
            return false;
        }
        return "TCP_PULL".equalsIgnoreCase(d.getProtocol());
    }

    @Transactional(readOnly = true)
    public List<Long> listTcpPullDeviceIds() {
        return deviceRepository.findAll().stream()
                .filter(DeviceTcpPullService::isTcpPullDevice)
                .map(Device::getId)
                .collect(Collectors.toList());
    }

    /**
     * 连接设备 TCP 服务，读取一行，解析后写入遥测，并更新链路检测结果。
     * 网络 I/O 在事务外执行，避免长时间占用 Hikari 连接。
     */
    public void pullAndIngest(Long deviceId) {
        Optional<TcpPullTarget> target = loadTarget(deviceId);
        if (!target.isPresent()) {
            return;
        }
        TcpPullTarget t = target.get();
        try {
            String line = readOneLine(t.host, t.port);
            Map<String, Object> payload = parsePayload(line, t.responseType);
            ingestionPipeline.ingest("TCP_PULL", t.deviceSn, payload);
            persistLinkResult(deviceId, "OK", "已读取一行并入库");
        } catch (Exception e) {
            log.warn("TCP 拉取失败 deviceId={} {}:{} -> {}", deviceId, t.host, t.port, e.getMessage());
            persistLinkResult(deviceId, "FAIL", truncate(e.getMessage(), 480));
        }
    }

    @Transactional(readOnly = true)
    Optional<TcpPullTarget> loadTarget(Long deviceId) {
        return deviceRepository.findById(deviceId)
                .filter(DeviceTcpPullService::isTcpPullDevice)
                .map(d -> new TcpPullTarget(
                        d.getId(),
                        d.getDeviceSn(),
                        d.getTcpRemoteHost().trim(),
                        d.getTcpRemotePort(),
                        d.getTcpResponseType()));
    }

    private void persistLinkResult(Long deviceId, String linkStatus, String message) {
        transactionTemplate.executeWithoutResult(status ->
                deviceRepository.findById(deviceId).ifPresent(d -> {
                    d.setLinkCheckStatus(linkStatus);
                    d.setLastLinkCheckAt(LocalDateTime.now());
                    d.setLastLinkMessage(message);
                    if ("FAIL".equals(linkStatus) && isTcpPullDevice(d)) {
                        d.setStatus("OFFLINE");
                    }
                    deviceRepository.save(d);
                }));
    }

    private String readOneLine(String host, int port) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalStateException("连接已建立但未读到任何数据（对端未发送或已关闭）");
            }
            return line.trim();
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String line, String responseType) throws Exception {
        if (line == null || line.isEmpty()) {
            throw new IllegalStateException("空行");
        }
        String rt = responseType != null ? responseType.trim().toUpperCase() : "JSON";
        if ("TEXT".equals(rt)) {
            Map<String, Object> m = new HashMap<>();
            m.put("raw", line);
            return m;
        }
        try {
            return objectMapper.readValue(line, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("按 JSON 解析失败: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static final class TcpPullTarget {
        final Long id;
        final String deviceSn;
        final String host;
        final int port;
        final String responseType;

        TcpPullTarget(Long id, String deviceSn, String host, int port, String responseType) {
            this.id = id;
            this.deviceSn = deviceSn;
            this.host = host;
            this.port = port;
            this.responseType = responseType;
        }
    }
}
