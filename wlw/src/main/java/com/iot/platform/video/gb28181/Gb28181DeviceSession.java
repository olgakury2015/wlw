package com.iot.platform.video.gb28181;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 已注册国标设备会话。
 */
@Getter
@Setter
public class Gb28181DeviceSession {

    private final String deviceId;
    private String contactHost;
    private int contactPort = 5060;
    private String contactUri;
    private volatile boolean online;
    /** 仅来自 SIP REGISTER，MESSAGE 心跳不会设置。 */
    private volatile boolean sipRegistered;
    private volatile Instant registeredAt;
    private volatile Instant lastKeepaliveAt;
    private volatile int expiresSeconds = 3600;
    /** 设备 REGISTER/MESSAGE 使用的 SIP 传输：TCP 或 UDP（默认 UDP）。 */
    private volatile String sipTransport = "UDP";
    /** 最近一次 Catalog 上报中的 DeviceID 列表（含通道）。 */
    private volatile List<String> catalogDeviceIds = Collections.emptyList();
    private volatile Instant catalogUpdatedAt;

    public Gb28181DeviceSession(String deviceId) {
        this.deviceId = deviceId;
    }

    public String sipUri() {
        return "sip:" + deviceId + "@" + contactHost;
    }

    public boolean isKeepaliveExpired(int timeoutSeconds) {
        if (lastKeepaliveAt == null) {
            return registeredAt == null
                    || registeredAt.isBefore(Instant.now().minusSeconds(timeoutSeconds));
        }
        return lastKeepaliveAt.isBefore(Instant.now().minusSeconds(timeoutSeconds));
    }

    public boolean hasReachableContact() {
        return Gb28181NetUtil.isIpv4(contactHost);
    }

    /** 距上次 MESSAGE/REGISTER 的秒数；无记录返回 -1。 */
    public long secondsSinceKeepalive() {
        Instant ref = lastKeepaliveAt != null ? lastKeepaliveAt : registeredAt;
        if (ref == null) {
            return -1L;
        }
        return Math.max(0L, java.time.Duration.between(ref, Instant.now()).getSeconds());
    }
}
