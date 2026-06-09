package com.iot.platform.video.gb28181.service;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.entity.CameraChannel;
import com.iot.platform.video.gb28181.Gb28181MediaTransport;
import com.iot.platform.video.gb28181.Gb28181NetUtil;
import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.repo.Gb28181PlatformConfigRepository;
import com.iot.platform.video.repo.CameraChannelRepository;
import com.iot.platform.video.zlm.ZlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class Gb28181PlatformConfigService {

    private final Gb28181PlatformConfigRepository repository;
    private final CameraChannelRepository cameraChannelRepository;
    private final IotProperties iotProperties;

    @Transactional(readOnly = true)
    public Gb28181PlatformConfig getOrCreate() {
        return repository.findById(Gb28181PlatformConfig.SINGLETON_ID).orElseGet(this::createDefault);
    }

    @Transactional
    public Gb28181PlatformConfig save(
            boolean enabled,
            String sipId,
            String sipDomain,
            String host,
            int port,
            String mediaHost,
            String devicePassword,
            int mediaPortMin,
            int mediaPortMax,
            int registerExpires,
            int keepaliveTimeoutSeconds,
            boolean requireSipRegister,
            String mediaTransport,
            String remark,
            boolean clearDevicePassword) {
        validate(sipId, sipDomain, host, port, mediaPortMin, mediaPortMax);
        if (enabled) {
            Gb28181NetUtil.requireIpv4("媒体地址 media-host", mediaHost);
        }
        Gb28181PlatformConfig cfg = getOrCreate();
        cfg.setEnabled(enabled);
        cfg.setSipId(sipId.trim());
        cfg.setSipDomain(sipDomain.trim());
        cfg.setHost(host.trim());
        cfg.setPort(port);
        cfg.setMediaHost(mediaHost != null ? mediaHost.trim() : "");
        if (clearDevicePassword) {
            cfg.setDevicePassword("");
        } else if (devicePassword != null && !devicePassword.trim().isEmpty()) {
            cfg.setDevicePassword(devicePassword);
        }
        cfg.setMediaPortMin(mediaPortMin);
        cfg.setMediaPortMax(mediaPortMax);
        cfg.setRegisterExpires(Math.max(60, registerExpires));
        cfg.setKeepaliveTimeoutSeconds(Math.max(30, keepaliveTimeoutSeconds));
        cfg.setRequireSipRegister(requireSipRegister);
        cfg.setMediaTransport(Gb28181MediaTransport.parse(mediaTransport).name().toLowerCase());
        cfg.setRemark(remark != null ? remark.trim() : "");
        cfg.setUpdatedAt(LocalDateTime.now());
        return repository.save(cfg);
    }

    /**
     * 解析设备注册密码：优先该设备在视频通道中单独配置的密码，否则用平台默认密码。
     */
    @Transactional(readOnly = true)
    public String resolveDevicePassword(String deviceId) {
        if (StringUtils.hasText(deviceId)) {
            String id = deviceId.trim();
            List<CameraChannel> channels = cameraChannelRepository.findByGb28181DeviceId(id);
            for (CameraChannel ch : channels) {
                if (StringUtils.hasText(ch.getGb28181Password())) {
                    return ch.getGb28181Password().trim();
                }
            }
        }
        Gb28181PlatformConfig cfg = getOrCreate();
        return cfg.getDevicePassword() != null ? cfg.getDevicePassword().trim() : "";
    }

    public String effectiveMediaHost(Gb28181PlatformConfig cfg) {
        if (Gb28181NetUtil.isIpv4(cfg.getMediaHost())) {
            return cfg.getMediaHost().trim();
        }
        String h = cfg.getHost();
        if (Gb28181NetUtil.isIpv4(h) && !"0.0.0.0".equals(h.trim())) {
            return h.trim();
        }
        return "127.0.0.1";
    }

    /**
     * INVITE/ACK SDP 媒体 IP（摄像机推流目标）：优先网页 media-host（云主机可填公网 IP）。
     * media-host 未配置时回退 application.yml 的 iot.video.zlm.sdp-ip。
     */
    public String effectiveZlmSdpIp(ZlmProperties zlm) {
        Gb28181PlatformConfig cfg = getOrCreate();
        String fromDb = effectiveMediaHost(cfg);
        if (Gb28181NetUtil.isIpv4(fromDb)) {
            return fromDb;
        }
        if (zlm != null && StringUtils.hasText(zlm.getSdpIp())) {
            return zlm.getSdpIp().trim();
        }
        return "127.0.0.1";
    }

    /**
     * ZLM openRtpServer 绑定 IP（须为本机网卡）。云主机公网 IP 不在网卡时返回 null，由 ZLM 绑定 0.0.0.0。
     */
    public String effectiveZlmBindIp(ZlmProperties zlm) {
        Gb28181PlatformConfig cfg = getOrCreate();
        String fromDb = effectiveMediaHost(cfg);
        if (Gb28181NetUtil.isLocalIpv4(fromDb)) {
            return fromDb;
        }
        if (zlm != null && StringUtils.hasText(zlm.getSdpIp())) {
            String yml = zlm.getSdpIp().trim();
            if (Gb28181NetUtil.isLocalIpv4(yml)) {
                return yml;
            }
        }
        return null;
    }

    /**
     * wlw 本机拉 ZLM RTSP 预览：ZLM http-host 为 127.0.0.1/本机网卡时用本机地址，
     * 避免云主机访问自身公网 media-host 失败（Cannot assign requested address）。
     */
    public String effectiveZlmRtspPlayHost(ZlmProperties zlm) {
        if (zlm != null && StringUtils.hasText(zlm.getHttpHost())) {
            String h = zlm.getHttpHost().trim();
            if ("127.0.0.1".equals(h) || "localhost".equalsIgnoreCase(h) || Gb28181NetUtil.isLocalIpv4(h)) {
                return h;
            }
        }
        return effectiveZlmSdpIp(zlm);
    }

    /**
     * JAIN-SIP 栈标识 IP（须为有效 IPv4，勿用 0.0.0.0 / admin / 域名）。
     */
    public String resolveSipStackIp(Gb28181PlatformConfig cfg) {
        String media = cfg.getMediaHost() != null ? cfg.getMediaHost().trim() : "";
        if (Gb28181NetUtil.isIpv4(media)) {
            return media;
        }
        return Gb28181NetUtil.requireIpv4("媒体地址 media-host", media);
    }

    /** 启动 SIP 前校验；无效时返回 false 并应由调用方记录日志。 */
    public boolean isStackIpValid(Gb28181PlatformConfig cfg) {
        String media = cfg.getMediaHost() != null ? cfg.getMediaHost().trim() : "";
        return Gb28181NetUtil.isIpv4(media);
    }

    /** UDP 监听绑定地址，一般为 0.0.0.0。 */
    public String resolveSipListenHost(Gb28181PlatformConfig cfg) {
        String h = cfg.getHost();
        if (h == null || h.trim().isEmpty()) {
            return "0.0.0.0";
        }
        return h.trim();
    }

    /**
     * SIP UDP 实际 bind 地址：已配置 media-host 时优先绑定该网卡 IP（Windows 上比 0.0.0.0 更易收到 REGISTER）；
     * 「SIP 监听地址」仅在为具体 IPv4 且与 media-host 不同时作为备选。
     */
    public Gb28181MediaTransport resolveMediaTransport(Gb28181PlatformConfig cfg) {
        String fromYml = iotProperties.getVideo().getGb28181MediaTransport();
        if (StringUtils.hasText(fromYml)) {
            return Gb28181MediaTransport.parse(fromYml);
        }
        if (cfg != null && StringUtils.hasText(cfg.getMediaTransport())) {
            return Gb28181MediaTransport.parse(cfg.getMediaTransport());
        }
        return Gb28181MediaTransport.TCP_PASSIVE;
    }

    public String resolveSipUdpBindHost(Gb28181PlatformConfig cfg) {
        if (Gb28181NetUtil.isIpv4(cfg.getMediaHost())) {
            return cfg.getMediaHost().trim();
        }
        String h = resolveSipListenHost(cfg);
        if (Gb28181NetUtil.isIpv4(h)) {
            return h;
        }
        return "0.0.0.0";
    }

    private Gb28181PlatformConfig createDefault() {
        Gb28181PlatformConfig cfg = new Gb28181PlatformConfig();
        cfg.setId(Gb28181PlatformConfig.SINGLETON_ID);
        cfg.setUpdatedAt(LocalDateTime.now());
        return repository.save(cfg);
    }

    private static void validate(String sipId, String sipDomain, String host, int port, int min, int max) {
        if (!StringUtils.hasText(sipId)) {
            throw new IllegalArgumentException("请填写平台 SIP ID");
        }
        if (!StringUtils.hasText(sipDomain)) {
            throw new IllegalArgumentException("请填写 SIP 域");
        }
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("请填写 SIP 监听地址");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SIP 端口无效");
        }
        if (min < 1024 || max > 65535 || min > max) {
            throw new IllegalArgumentException("RTP 端口池范围无效");
        }
    }
}
