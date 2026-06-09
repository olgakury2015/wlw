package com.iot.platform.management.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 工业物联网网关（与 {@link Device} 分离建档）。
 * 下挂传感器/PLC 仍用「添加设备」并可选绑定本网关。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_gateway")
public class IotGateway {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 网关唯一编号，TCP 注册包 / JSON {@code gatewayId} 建议与此一致。 */
    @Column(name = "gateway_sn", nullable = false, unique = true, length = 64)
    private String gatewaySn;

    @Column(nullable = false, length = 32)
    private String status = "OFFLINE";

    /** {@link com.iot.platform.gateway.GatewayVendor} 名称 */
    @Column(name = "vendor_model", length = 32)
    private String vendorModel = "GENERIC";

    /** {@link com.iot.platform.gateway.GatewayUplinkProtocol} 名称 */
    @Column(name = "uplink_protocol", length = 32)
    private String uplinkProtocol = "TCP_CLIENT";

    /** {@link com.iot.platform.gateway.GatewaySerialMode} 名称（DTU 串口模式说明） */
    @Column(name = "serial_mode", length = 32)
    private String serialMode = "NET_TRANSPARENT";

    /** 平台作客户端时：网关 IP/域名 */
    @Column(name = "remote_host", length = 128)
    private String remoteHost;

    @Column(name = "remote_port")
    private Integer remotePort;

    @Column(name = "mqtt_remote_host", length = 512)
    private String mqttRemoteHost;

    @Column(name = "mqtt_remote_port")
    private Integer mqttRemotePort;

    @Column(name = "mqtt_subscribe_topic", length = 512)
    private String mqttSubscribeTopic;

    @Column(name = "mqtt_username", length = 128)
    private String mqttUsername;

    @Column(name = "mqtt_password", length = 256)
    private String mqttPassword;

    /** TCP 注册包（USR-G781「特色功能」），默认与 gatewaySn 相同 */
    @Column(name = "register_packet", length = 256)
    private String registerPacket;

    @Column(name = "heartbeat_packet", length = 256)
    private String heartbeatPacket;

    @Column(length = 512)
    private String remark;

    @Column(name = "location_address", length = 512)
    private String locationAddress;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @PrePersist
    public void prePersist() {
        if (activatedAt == null) {
            activatedAt = LocalDateTime.now();
        }
    }
}
