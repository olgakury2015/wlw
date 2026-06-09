package com.iot.platform.gateway;

/**
 * 工业网关型号（可扩展）。
 */
public enum GatewayVendor {
    USR_G781("有人 USR-G781", "4G 路由器 + DTU，支持串口透传 / Modbus 互转 / HTTPD / TCP·UDP 多路 Socket"),
    GENERIC("通用网关", "按上行协议自行配置，适配市面常见 DTU/路由器");

    private final String label;
    private final String description;

    GatewayVendor(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public static GatewayVendor parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return GENERIC;
        }
        try {
            return GatewayVendor.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERIC;
        }
    }
}
