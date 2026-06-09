package com.iot.platform.video.service;

import com.iot.platform.video.entity.CameraChannel;
import com.iot.platform.video.gb28181.Gb28181DeviceRegistry;
import com.iot.platform.video.gb28181.Gb28181DeviceSession;
import com.iot.platform.video.gb28181.Gb28181NetUtil;
import com.iot.platform.video.repo.CameraChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CameraChannelService {

    private final CameraChannelRepository cameraChannelRepository;
    private final VideoStreamRegistry videoStreamRegistry;
    private final Gb28181DeviceRegistry gb28181DeviceRegistry;

    @Transactional(readOnly = true)
    public List<CameraChannel> listEnabledForMonitor() {
        List<CameraChannel> all = cameraChannelRepository.findByEnabledTrueOrderBySortOrderAscIdAsc();
        Set<String> gbCameraIps = collectGb28181CameraIps(all);
        if (gbCameraIps.isEmpty()) {
            return all;
        }
        List<CameraChannel> visible = new ArrayList<>(all.size());
        for (CameraChannel ch : all) {
            if (ch.isGb28181Source()) {
                visible.add(ch);
                continue;
            }
            String rtspIp = Gb28181NetUtil.extractIpv4FromRtsp(
                    ch.getRtspUrl() != null ? ch.getRtspUrl().trim() : null);
            if (rtspIp != null && gbCameraIps.contains(rtspIp)) {
                continue;
            }
            visible.add(ch);
        }
        return visible;
    }

    private Set<String> collectGb28181CameraIps(List<CameraChannel> channels) {
        Set<String> ips = new HashSet<>();
        for (CameraChannel ch : channels) {
            if (!ch.isGb28181Source()) {
                continue;
            }
            String devId = ch.getGb28181DeviceId() != null ? ch.getGb28181DeviceId().trim() : "";
            if (StringUtils.hasText(devId)) {
                Gb28181DeviceSession dev = gb28181DeviceRegistry.get(devId);
                if (dev != null && Gb28181NetUtil.isIpv4(dev.getContactHost())) {
                    ips.add(dev.getContactHost().trim());
                    continue;
                }
            }
            String ip = Gb28181NetUtil.extractIpv4FromRtsp(
                    ch.getRtspUrl() != null ? ch.getRtspUrl().trim() : null);
            if (ip != null) {
                ips.add(ip);
            }
        }
        return ips;
    }

    @Transactional(readOnly = true)
    public List<CameraChannel> listAll() {
        return cameraChannelRepository.findAll();
    }

    @Transactional(readOnly = true)
    public CameraChannel require(Long id) {
        return cameraChannelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("通道不存在：" + id));
    }

    @Transactional
    public CameraChannel create(String name,
                                String sourceType,
                                String rtspUrl,
                                String onvifDeviceServiceUrl,
                                String onvifUsername,
                                String onvifPassword,
                                String gb28181DeviceId,
                                String gb28181ChannelId,
                                String gb28181Password,
                                Integer gb28181StreamIndex,
                                boolean enabled,
                                Integer sortOrder,
                                String remark) {
        validateSource(sourceType, rtspUrl, onvifDeviceServiceUrl, onvifUsername, gb28181DeviceId);
        CameraChannel c = new CameraChannel();
        apply(c, name, sourceType, rtspUrl, onvifDeviceServiceUrl, onvifUsername, onvifPassword,
                gb28181DeviceId, gb28181ChannelId, gb28181Password, gb28181StreamIndex,
                enabled, sortOrder, remark, false);
        c.setCreatedAt(LocalDateTime.now());
        return cameraChannelRepository.save(c);
    }

    @Transactional
    public void update(Long id,
                       String name,
                       String sourceType,
                       String rtspUrl,
                       String onvifDeviceServiceUrl,
                       String onvifUsername,
                       String onvifPassword,
                       String gb28181DeviceId,
                       String gb28181ChannelId,
                       String gb28181Password,
                       Integer gb28181StreamIndex,
                       boolean enabled,
                       Integer sortOrder,
                       String remark,
                       String clearGb28181Password) {
        CameraChannel c = require(id);
        validateSource(sourceType, rtspUrl, onvifDeviceServiceUrl, onvifUsername, gb28181DeviceId);
        boolean clearGbPwd = "true".equalsIgnoreCase(clearGb28181Password) || "on".equalsIgnoreCase(clearGb28181Password);
        apply(c, name, sourceType, rtspUrl, onvifDeviceServiceUrl, onvifUsername, onvifPassword,
                gb28181DeviceId, gb28181ChannelId, gb28181Password, gb28181StreamIndex,
                enabled, sortOrder, remark, clearGbPwd);
        cameraChannelRepository.save(c);
        videoStreamRegistry.restartIfRunning(id);
    }

    @Transactional
    public void delete(Long id) {
        videoStreamRegistry.evictChannel(id);
        cameraChannelRepository.deleteById(id);
    }

    /** 列表页快速启停：停用时立即断开拉流线程。 */
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        CameraChannel c = require(id);
        if (c.isEnabled() == enabled) {
            return;
        }
        c.setEnabled(enabled);
        cameraChannelRepository.save(c);
        if (!enabled) {
            videoStreamRegistry.evictChannel(id);
        }
    }

    private void apply(CameraChannel c,
                       String name,
                       String sourceType,
                       String rtspUrl,
                       String onvifDeviceServiceUrl,
                       String onvifUsername,
                       String onvifPassword,
                       String gb28181DeviceId,
                       String gb28181ChannelId,
                       String gb28181Password,
                       Integer gb28181StreamIndex,
                       boolean enabled,
                       Integer sortOrder,
                       String remark,
                       boolean clearGb28181Password) {
        String prevOnvif = c.getOnvifPassword();
        String prevGb = c.getGb28181Password();

        c.setName(name != null ? name.trim() : "");
        String st = sourceType != null ? sourceType.trim().toUpperCase() : "RTSP";
        if (!"RTSP".equals(st) && !"ONVIF".equals(st) && !"GB28181".equals(st)) {
            st = "RTSP";
        }
        c.setSourceType(st);
        c.setRtspUrl(trimToNull(rtspUrl));
        c.setOnvifDeviceServiceUrl(trimToNull(onvifDeviceServiceUrl));
        c.setOnvifUsername(trimToNull(onvifUsername));
        if (onvifPassword != null && !onvifPassword.trim().isEmpty()) {
            c.setOnvifPassword(onvifPassword);
        } else if (prevOnvif != null) {
            c.setOnvifPassword(prevOnvif);
        } else {
            c.setOnvifPassword("");
        }
        c.setGb28181DeviceId(trimToNull(gb28181DeviceId));
        String gbDev = trimToNull(gb28181DeviceId);
        String gbCh = trimToNull(gb28181ChannelId);
        if ("GB28181".equals(st) && gbDev != null && gbCh == null) {
            throw new IllegalArgumentException(
                    "国标请填写通道编码（与海康「视频通道编码 ID」一致，如 34020000001320000003，勿与设备编码相同）");
        }
        c.setGb28181ChannelId(gbCh);
        if (clearGb28181Password) {
            c.setGb28181Password(null);
        } else if (gb28181Password != null && !gb28181Password.trim().isEmpty()) {
            c.setGb28181Password(gb28181Password.trim());
        } else if (prevGb != null) {
            c.setGb28181Password(prevGb);
        }
        c.setGb28181StreamIndex(gb28181StreamIndex != null ? Math.max(0, Math.min(1, gb28181StreamIndex)) : 0);
        c.setEnabled(enabled);
        c.setSortOrder(sortOrder != null ? sortOrder : 0);
        c.setRemark(remark != null ? remark.trim() : "");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static void validateSource(String sourceType,
                                       String rtspUrl,
                                       String onvifDeviceServiceUrl,
                                       String onvifUsername,
                                       String gb28181DeviceId) {
        String st = sourceType != null ? sourceType.trim().toUpperCase() : "RTSP";
        if ("GB28181".equals(st)) {
            if (!StringUtils.hasText(gb28181DeviceId)) {
                throw new IllegalArgumentException("国标接入请填写设备编码（20 位 SIP 用户名）");
            }
            String id = gb28181DeviceId.trim();
            if (id.length() < 10 || id.length() > 32) {
                throw new IllegalArgumentException("国标设备编码长度异常，请填写摄像机 SIP 用户名");
            }
            return;
        }
        boolean hasRtsp = StringUtils.hasText(rtspUrl != null ? rtspUrl.trim() : null);
        boolean hasOnvif = StringUtils.hasText(onvifDeviceServiceUrl != null ? onvifDeviceServiceUrl.trim() : null);
        if (!hasRtsp && !hasOnvif) {
            throw new IllegalArgumentException("请至少填写 RTSP 地址或 ONVIF 设备服务地址之一");
        }
        if (hasOnvif && !StringUtils.hasText(onvifUsername != null ? onvifUsername.trim() : null)) {
            throw new IllegalArgumentException("使用 ONVIF 时请填写设备用户名");
        }
        if (hasRtsp) {
            String r = rtspUrl.trim().toLowerCase();
            if (!r.startsWith("rtsp://") && !r.startsWith("rtsps://")) {
                throw new IllegalArgumentException("RTSP 地址应以 rtsp:// 或 rtsps:// 开头");
            }
        }
    }
}
