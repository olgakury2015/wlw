package com.iot.platform.management.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "device_sn", nullable = false, unique = true, length = 64)
    private String deviceSn;

    @Column(nullable = false, length = 32)
    private String status = "OFFLINE";

    /**
     * 档案用协议。MQTT：设备向 Broker <strong>发布</strong>遥测；平台连接同一 Broker 作<strong>订阅者</strong>收上行（见 iot.mqtt.subscribe-topics）。
     */
    @Column(length = 32)
    private String protocol = "MQTT";

    /**
     * MQTT 独立订阅：Broker 主机或完整 URI（如 192.168.0.50 或 tcp://192.168.0.50:1883）。留空则仅依赖 application.yml 全局 {@code iot.mqtt}。
     */
    @Column(name = "mqtt_remote_host", length = 512)
    private String mqttRemoteHost;

    /** 端口；主机为纯 IP/主机名时使用；完整 URI 时可留空。 */
    @Column(name = "mqtt_remote_port")
    private Integer mqttRemotePort;

    /** 本连接要订阅的主题，支持 +、# 通配。 */
    @Column(name = "mqtt_subscribe_topic", length = 512)
    private String mqttSubscribeTopic;

    @Column(name = "mqtt_username", length = 128)
    private String mqttUsername;

    @Column(name = "mqtt_password", length = 256)
    private String mqttPassword;

    @Column(name = "product_name", length = 128)
    private String productName;

    @Column(name = "org_name", length = 128)
    private String orgName;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "alarm_count")
    private int alarmCount;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /**
     * 当设备为 TCP 服务端时：平台作为客户端连接的目标地址（如 192.168.0.200）。
     */
    @Column(name = "tcp_remote_host", length = 128)
    private String tcpRemoteHost;

    @Column(name = "tcp_remote_port")
    private Integer tcpRemotePort;

    /**
     * JSON：按行解析为 JSON 对象；TEXT：整行放入 payload.raw。
     */
    @Column(name = "tcp_response_type", length = 16)
    private String tcpResponseType = "JSON";

    /**
     * 最近一次「能否连上并读到数据」的检测结果：UNKNOWN / OK / FAIL。
     */
    @Column(name = "link_check_status", length = 16)
    private String linkCheckStatus;

    @Column(name = "last_link_check_at")
    private LocalDateTime lastLinkCheckAt;

    @Column(name = "last_link_message", length = 512)
    private String lastLinkMessage;

    /**
     * 用户填写的安装地址文本；提交时由平台地理编码为 latitude/longitude。
     */
    @Column(name = "location_address", length = 512)
    private String locationAddress;

    /**
     * 纬度；与 {@link #locationCoordGcj02} 配合：false/null 表示 WGS84（Nominatim/手动等），true 表示 GCJ-02（高德地理编码）。
     */
    @Column(name = "latitude")
    private Double latitude;

    /** 经度。 */
    @Column(name = "longitude")
    private Double longitude;

    /** 坐标是否为 GCJ-02；null 表示历史数据，按 WGS84 在高德底图上转换展示。 */
    @Column(name = "location_coord_gcj02")
    private Boolean locationCoordGcj02;

    /**
     * 最近一次「右侧遥测表格」可展示内容的 JSON（{@code TelemetryDisplayRow}）。
     * 仅当本条遥测解析出非空字段时更新；纯定位等无表格行上报不会覆盖，避免地图/定位刷新把测点表冲掉。
     */
    @Column(name = "last_telemetry_display_json", columnDefinition = "TEXT")
    private String lastTelemetryDisplayJson;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id")
    private DeviceCategory category;

    /** 下挂工业网关；留空表示直连平台。 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "gateway_id")
    private IotGateway gateway;

    @PrePersist
    public void prePersist() {
        if (activatedAt == null) {
            activatedAt = LocalDateTime.now();
        }
    }
}
