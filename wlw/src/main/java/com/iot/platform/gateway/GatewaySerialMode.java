package com.iot.platform.gateway;

/** USR-G781 等 DTU 串口侧工作模式（档案说明用）。 */
public enum GatewaySerialMode {
    NET_TRANSPARENT("网络透传"),
    MODBUS_BRIDGE("Modbus RTU ↔ Modbus TCP 互转"),
    HTTPD("HTTPD 模式");

    private final String label;

    GatewaySerialMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static GatewaySerialMode parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return NET_TRANSPARENT;
        }
        try {
            return GatewaySerialMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NET_TRANSPARENT;
        }
    }
}
