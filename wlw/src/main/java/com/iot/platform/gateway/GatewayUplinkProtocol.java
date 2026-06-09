package com.iot.platform.gateway;

/**
 * 网关到平台的上行方式。
 */
public enum GatewayUplinkProtocol {
    /** 网关作 TCP 客户端连平台 {@code iot.tcp.port}（USR-G781 网络透传推荐） */
    TCP_CLIENT("TCP 客户端 → 平台"),
    /** 平台作 TCP 客户端连网关（网关开 TCP Server） */
    TCP_SERVER("TCP 服务端 ← 平台拉取"),
    /** 网关向 MQTT Broker 发布，平台订阅 */
    MQTT("MQTT 发布 → 平台订阅"),
    /** 网关 HTTP POST 到平台 ingest 接口 */
    HTTP("HTTP POST"),
    /** 平台 Modbus TCP 读网关或经网关下挂设备 */
    MODBUS_TCP("Modbus TCP");

    private final String label;

    GatewayUplinkProtocol(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static GatewayUplinkProtocol parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return TCP_CLIENT;
        }
        try {
            return GatewayUplinkProtocol.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TCP_CLIENT;
        }
    }
}
