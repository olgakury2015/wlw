package com.iot.platform.management.service;

import com.iot.platform.management.repo.GatewayRepository;
import com.iot.platform.model.TelemetryMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 根据 TCP 注册包或遥测 payload 中的 gatewayId 刷新网关在线状态。
 */
@Service
@RequiredArgsConstructor
public class GatewayPresenceService {

    private final GatewayRepository gatewayRepository;

    public void onTcpLine(String line, String resolvedDeviceId) {
        if (line != null) {
            touchRegisterPacket(line.trim());
        }
        if (resolvedDeviceId != null) {
            touchIfGateway(resolvedDeviceId.trim());
        }
    }

    public void onTelemetry(TelemetryMessage msg) {
        if (msg == null || msg.payload() == null) {
            return;
        }
        Map<String, Object> p = msg.payload();
        String gw = firstString(p, "gatewayId", "gateway_id", "gatewaySn", "gateway_sn");
        if (gw != null) {
            touchOnline(gw);
        }
        Object json = p.get("json");
        if (json instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) json;
            String fromJson = firstString(m, "gatewayId", "gateway_id", "gatewaySn", "gateway_sn");
            if (fromJson != null) {
                touchOnline(fromJson);
            }
        }
        touchIfGateway(msg.deviceId());
    }

    @Transactional
    public void touchOnline(String gatewaySn) {
        if (gatewaySn == null || gatewaySn.trim().isEmpty()) {
            return;
        }
        gatewayRepository.findByGatewaySn(gatewaySn.trim()).ifPresent(g -> {
            g.setStatus("ONLINE");
            g.setLastSeenAt(LocalDateTime.now());
            gatewayRepository.save(g);
        });
    }

    @Transactional
    public void touchRegisterPacket(String packet) {
        if (packet == null || packet.trim().isEmpty()) {
            return;
        }
        String trimmed = packet.trim();
        gatewayRepository.findByGatewaySn(trimmed).ifPresent(g -> touchOnline(g.getGatewaySn()));
        gatewayRepository.findByRegisterPacket(trimmed).forEach(g -> touchOnline(g.getGatewaySn()));
    }

    private void touchIfGateway(String sn) {
        if (sn == null || sn.isEmpty()) {
            return;
        }
        gatewayRepository.findByGatewaySn(sn).ifPresent(g -> touchOnline(g.getGatewaySn()));
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }
}
