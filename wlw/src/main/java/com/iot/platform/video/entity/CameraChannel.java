package com.iot.platform.video.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 视频通道：浏览器通过 MJPEG 观看。
 * 接入方式：RTSP、ONVIF→RTSP、GB/T 28181（平台 SIP 注册 + INVITE 收流）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_camera_channel")
public class CameraChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** RTSP | ONVIF | GB28181 */
    @Column(name = "source_type", length = 16)
    private String sourceType = "RTSP";

    /** 国标设备编码（20 位，与摄像机 SIP 用户名一致，如 34020000001320000001） */
    @Column(name = "gb28181_device_id", length = 32)
    private String gb28181DeviceId;

    /** 国标视频通道编码，单 IPC 通常与设备 ID 相同 */
    @Column(name = "gb28181_channel_id", length = 32)
    private String gb28181ChannelId;

    /** 国标设备注册密码（可选；留空则用平台默认密码） */
    @Column(name = "gb28181_password", length = 256)
    private String gb28181Password;

    /** 28181 码流索引：0 主码流，1 子码流 */
    @Column(name = "gb28181_stream_index")
    private Integer gb28181StreamIndex = 0;

    /**
     * 直接 RTSP 地址；若走 ONVIF 解析，可与 {@link #onvifDeviceServiceUrl} 二选一或并存（优先 RTSP）。
     */
    @Lob
    @Column(name = "rtsp_url")
    private String rtspUrl;

    /** 如 http://192.168.1.64/onvif/device_service */
    @Column(name = "onvif_device_service_url", length = 512)
    private String onvifDeviceServiceUrl;

    @Column(name = "onvif_username", length = 64)
    private String onvifUsername;

    @Column(name = "onvif_password", length = 256)
    private String onvifPassword;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(length = 512)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public boolean isGb28181Source() {
        return sourceType != null && "GB28181".equalsIgnoreCase(sourceType.trim());
    }
}
