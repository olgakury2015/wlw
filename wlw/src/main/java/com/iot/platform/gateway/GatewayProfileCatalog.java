package com.iot.platform.gateway;

import com.iot.platform.management.entity.IotGateway;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 网关型号默认参数与对接说明（可扩展新型号）。
 */
public final class GatewayProfileCatalog {

    private GatewayProfileCatalog() {
    }

    public static void applyVendorDefaults(IotGateway g) {
        GatewayVendor vendor = GatewayVendor.parse(g.getVendorModel());
        if (vendor == GatewayVendor.USR_G781 && isBlank(g.getUplinkProtocol())) {
            g.setUplinkProtocol(GatewayUplinkProtocol.TCP_CLIENT.name());
        }
        if (vendor == GatewayVendor.USR_G781 && isBlank(g.getSerialMode())) {
            g.setSerialMode(GatewaySerialMode.NET_TRANSPARENT.name());
        }
        if (isBlank(g.getRegisterPacket())) {
            g.setRegisterPacket(g.getGatewaySn());
        }
    }

    public static List<String> buildSetupSteps(IotGateway g, int platformTcpPort, String platformHttpIngestPath) {
        GatewayVendor vendor = GatewayVendor.parse(g.getVendorModel());
        GatewayUplinkProtocol uplink = GatewayUplinkProtocol.parse(g.getUplinkProtocol());
        List<String> steps = new ArrayList<>();
        steps.add("网关编号（注册包 / gatewayId）：" + g.getGatewaySn());

        if (vendor == GatewayVendor.USR_G781) {
            steps.add("USR-G781 Web 配置路径：「DTU 功能」→「工作模式」→ 选择 "
                    + GatewaySerialMode.parse(g.getSerialMode()).getLabel());
        }

        switch (uplink) {
            case TCP_CLIENT:
                steps.add("Socket A 工作方式：TCP Client；目标地址：平台公网/内网 IP；端口："
                        + platformTcpPort + "（与 application.yml iot.tcp.port 一致）");
                steps.add("注册包：建议填 ASCII「" + nullToEmpty(g.getRegisterPacket()) + "」；心跳包可选");
                steps.add("串口数据：每行一条 UTF-8 JSON，含 deviceId（下挂设备编号）与测点字段；"
                        + "或整包透传后在平台解析");
                steps.add("示例：{\"gatewayId\":\"" + g.getGatewaySn()
                        + "\",\"deviceId\":\"sensor-01\",\"temp\":25.6}");
                break;
            case TCP_SERVER:
                steps.add("网关开 TCP Server；平台「TCP 拉取」或后续扩展轮询连接 "
                        + nullToEmpty(g.getRemoteHost()) + ":" + safePort(g.getRemotePort()));
                break;
            case MQTT:
                steps.add("网关/MQTT 客户端向 Broker 发布；平台订阅主题："
                        + nullToEmpty(g.getMqttSubscribeTopic()));
                steps.add("JSON 建议带 gatewayId / deviceId；平台按 deviceId 写入对应设备遥测");
                break;
            case HTTP:
                steps.add("HTTPD 模式或自定义脚本 POST 至：" + platformHttpIngestPath);
                steps.add("Body 示例：{\"deviceId\":\"sensor-01\",\"gatewayId\":\""
                        + g.getGatewaySn() + "\",\"data\":{...}}");
                break;
            case MODBUS_TCP:
                steps.add("USR-G781 可设「Modbus RTU ↔ Modbus TCP 互转」；平台用 Modbus TCP 读网关 LAN 口 IP");
                steps.add("远程地址：" + nullToEmpty(g.getRemoteHost()) + " 端口 "
                        + safePort(g.getRemotePort() != null ? g.getRemotePort() : 502));
                break;
            default:
                break;
        }

        if (vendor == GatewayVendor.USR_G781) {
            steps.add("参考：说明书「10. DTU 功能」— 网络透传 / HTTPD / Modbus 互转；"
                    + "支持注册包、心跳包、4 路 Socket 同时在线");
        }
        return steps;
    }

    public static List<GatewayVendor> listVendors() {
        return Arrays.asList(GatewayVendor.values());
    }

    public static List<GatewayUplinkProtocol> uplinksForVendor(GatewayVendor vendor) {
        if (vendor == GatewayVendor.USR_G781) {
            return Arrays.asList(
                    GatewayUplinkProtocol.TCP_CLIENT,
                    GatewayUplinkProtocol.MQTT,
                    GatewayUplinkProtocol.HTTP,
                    GatewayUplinkProtocol.MODBUS_TCP,
                    GatewayUplinkProtocol.TCP_SERVER);
        }
        return Arrays.asList(GatewayUplinkProtocol.values());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String safePort(Integer port) {
        return port != null && port > 0 ? String.valueOf(port) : "—";
    }
}
