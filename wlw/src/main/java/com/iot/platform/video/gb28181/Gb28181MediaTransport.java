package com.iot.platform.video.gb28181;

/**
 * 国标媒体传输：UDP 推流、TCP 被动（平台监听）、TCP 主动（平台连摄像机，海康 200 OK 常见 setup:active）。
 */
public enum Gb28181MediaTransport {

    UDP,
    TCP_PASSIVE,
    TCP_ACTIVE;

    public static Gb28181MediaTransport parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return TCP_PASSIVE;
        }
        String v = raw.trim().toLowerCase().replace('-', '_');
        if ("udp".equals(v) || "rtp_udp".equals(v)) {
            return UDP;
        }
        if ("tcp_active".equals(v) || "active".equals(v)) {
            return TCP_ACTIVE;
        }
        if ("tcp".equals(v) || "tcp_passive".equals(v) || "passive".equals(v)) {
            return TCP_PASSIVE;
        }
        return TCP_PASSIVE;
    }

    public boolean isTcpPassive() {
        return this == TCP_PASSIVE;
    }

    public boolean isTcpActive() {
        return this == TCP_ACTIVE;
    }

    public boolean isTcp() {
        return this == TCP_PASSIVE || this == TCP_ACTIVE;
    }
}
