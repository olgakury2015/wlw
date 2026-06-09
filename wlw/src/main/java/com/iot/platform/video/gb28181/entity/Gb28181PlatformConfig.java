package com.iot.platform.video.gb28181.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 国标平台侧配置（单例，id 固定为 1）。多路摄像机共用同一 SIP 平台参数。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_gb28181_platform_config")
public class Gb28181PlatformConfig {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "sip_id", nullable = false, length = 32)
    private String sipId = "34020000002000000001";

    @Column(name = "sip_domain", nullable = false, length = 16)
    private String sipDomain = "3402000000";

    @Column(nullable = false, length = 64)
    private String host = "0.0.0.0";

    @Column(nullable = false)
    private int port = 5060;

    /** SDP 中媒体地址，摄像机推 RTP 的目标 IP */
    @Column(name = "media_host", length = 64)
    private String mediaHost = "";

    /** 默认设备注册密码（单台摄像机可在通道里单独覆盖） */
    @Column(name = "device_password", length = 256)
    private String devicePassword = "";

    @Column(name = "media_port_min", nullable = false)
    private int mediaPortMin = 30000;

    @Column(name = "media_port_max", nullable = false)
    private int mediaPortMax = 30500;

    @Column(name = "register_expires", nullable = false)
    private int registerExpires = 3600;

    @Column(name = "keepalive_timeout_seconds", nullable = false)
    private int keepaliveTimeoutSeconds = 180;

    /**
     * true：必须完成 SIP REGISTER 才允许 INVITE（标准模式）。
     * false：海康等仅发 MESSAGE 心跳、不发 REGISTER 时，凭心跳 + UDP 源地址也可点播。
     */
    @Column(name = "require_sip_register", nullable = false)
    private boolean requireSipRegister = false;

    /** udp | tcp_passive（海康可在国标页选「TCP」传输，与平台一致） */
    @Column(name = "media_transport", nullable = false, length = 16)
    private String mediaTransport = "tcp_passive";

    @Column(length = 512)
    private String remark = "";

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
