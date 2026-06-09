package com.iot.platform.video.gb28181;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Gb28181DeviceRegistry {

    private final ConcurrentHashMap<String, Gb28181DeviceSession> devices = new ConcurrentHashMap<>();

    public Gb28181DeviceSession get(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        return devices.get(deviceId.trim());
    }

    public Gb28181DeviceSession registerOrUpdate(String deviceId) {
        String id = deviceId.trim();
        return devices.compute(id, (k, old) -> {
            Gb28181DeviceSession s = old != null ? old : new Gb28181DeviceSession(id);
            s.setOnline(true);
            s.setSipRegistered(true);
            s.setRegisteredAt(Instant.now());
            s.setLastKeepaliveAt(Instant.now());
            return s;
        });
    }

    public void touchKeepalive(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return;
        }
        String id = deviceId.trim();
        Gb28181DeviceSession s = devices.compute(id, (k, old) -> {
            Gb28181DeviceSession sess = old != null ? old : new Gb28181DeviceSession(id);
            sess.setOnline(true);
            sess.setLastKeepaliveAt(Instant.now());
            return sess;
        });
        s.setLastKeepaliveAt(Instant.now());
    }

    public void setContact(String deviceId, String host, int port, String contactUri) {
        if (!Gb28181NetUtil.isIpv4(host)) {
            Gb28181DeviceSession existing = get(deviceId);
            if (existing != null && Gb28181NetUtil.isIpv4(existing.getContactHost())) {
                return;
            }
            ensureSession(deviceId);
            return;
        }
        Gb28181DeviceSession s = ensureSession(deviceId);
        s.setContactHost(host.trim());
        s.setContactPort(port > 0 ? port : 5060);
        s.setContactUri(contactUri);
    }

    public void unregister(String deviceId) {
        Gb28181DeviceSession s = devices.get(deviceId);
        if (s != null) {
            s.setOnline(false);
            s.setSipRegistered(false);
        }
    }

    private Gb28181DeviceSession ensureSession(String deviceId) {
        String id = deviceId.trim();
        return devices.computeIfAbsent(id, Gb28181DeviceSession::new);
    }

    public List<Gb28181DeviceSession> listAll() {
        Collection<Gb28181DeviceSession> all = devices.values();
        List<Gb28181DeviceSession> list = new ArrayList<>(all);
        list.sort((a, b) -> a.getDeviceId().compareTo(b.getDeviceId()));
        return list;
    }

    public void refreshOnlineFlags(int keepaliveTimeoutSeconds) {
        // 海康心跳周期常为 60s，判离线略放宽，避免偶发丢包即「不在线」
        int effectiveTimeout = Math.max(keepaliveTimeoutSeconds, 240);
        for (Gb28181DeviceSession s : devices.values()) {
            s.setOnline(!s.isKeepaliveExpired(effectiveTimeout));
        }
    }
}
