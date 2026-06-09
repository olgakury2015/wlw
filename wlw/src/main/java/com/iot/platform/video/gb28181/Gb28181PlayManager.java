package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import com.iot.platform.video.zlm.ZlmProperties;
import com.iot.platform.video.zlm.ZlmRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class Gb28181PlayManager {

    private final Gb28181UdpPortPool portPool;
    private final Gb28181PlayService playService;
    private final Gb28181PlatformConfigService platformConfigService;
    private final IotProperties iotProperties;
    private final ZlmRestClient zlmRestClient;
    private final ConcurrentHashMap<String, Gb28181MediaSession> sessions = new ConcurrentHashMap<>();
    /** 摄像机连续 INVITE 400 后冷却，避免连打导致海康本地预览也失败。 */
    private final ConcurrentHashMap<String, Long> inviteCooldownUntilMs = new ConcurrentHashMap<>();

    public Gb28181MediaSession acquire(String deviceId, String channelId, int streamIndex) throws Exception {
        String dev = deviceId.trim();
        if (channelId == null || channelId.trim().isEmpty()) {
            throw new IllegalArgumentException("国标通道编码未配置，请填写与海康「视频通道编码 ID」一致的 20 位编码");
        }
        String ch = channelId.trim();
        if (dev.equals(ch)) {
            throw new IllegalArgumentException(
                    "国标通道编码不能与设备编码相同，请改为海康「视频通道编码 ID」（如 34020000001320000003）");
        }
        String key = dev + ":" + ch + ":" + streamIndex;
        Gb28181MediaSession existing = sessions.get(key);
        if (existing != null && isPlayable(existing)) {
            return existing;
        }
        synchronized (this) {
            existing = sessions.get(key);
            if (existing != null && isPlayable(existing)) {
                return existing;
            }
            if (existing != null) {
                release(existing);
            }
            checkInviteCooldown(dev);
            releaseAllForDevice(dev);
            int port = resolveInitialRtpPort(key);
            Gb28181MediaSession session = new Gb28181MediaSession(key, dev, ch, streamIndex, port);
            Gb28181JpegSource src = null;
            try {
                playService.inviteLive(session);
                inviteCooldownUntilMs.remove(dev);
                session.markInviteOk();
                src = session.getJpegSource();
                if (!session.isUseZlm() && src == null) {
                    throw new IllegalStateException("国标解码器未启动");
                }
                if (session.isUseZlm() && !StringUtils.hasText(session.getZlmRtspUrl())) {
                    throw new IllegalStateException("ZLM 国标流未就绪");
                }
                sessions.put(key, session);
                return session;
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                if (msg.contains("INVITE 400") || msg.contains("400")) {
                    inviteCooldownUntilMs.put(dev, System.currentTimeMillis() + 60_000L);
                }
                if (src != null) {
                    try {
                        src.close();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    playService.bye(session);
                } catch (Exception ignored) {
                }
                releaseRtpPortQuietly(port);
                throw ex;
            }
        }
    }

    public void releaseByKey(String streamKey) {
        Gb28181MediaSession s = sessions.remove(streamKey);
        if (s != null) {
            release(s);
        }
    }

    /** 摄像机主动 BYE 时释放会话，避免端口占用与无效重连。 */
    public boolean isSessionActive(String deviceId, String channelId, int streamIndex) {
        String key = deviceId.trim() + ":" + channelId.trim() + ":" + streamIndex;
        Gb28181MediaSession s = sessions.get(key);
        return s != null && isPlayable(s);
    }

    private boolean isPlayable(Gb28181MediaSession s) {
        return s.isInviteOk()
                && (s.getJpegSource() != null || StringUtils.hasText(s.getZlmRtspUrl()));
    }

    public void onRemoteBye(String deviceId, String callId) {
        if (callId == null || callId.isEmpty()) {
            return;
        }
        for (Gb28181MediaSession s : sessions.values()) {
            if (callId.equals(s.getCallId())) {
                log.info("GB28181 摄像机 BYE 释放会话 device={} key={}", deviceId, s.getStreamKey());
                release(s);
                return;
            }
        }
    }

    public void release(Gb28181MediaSession session) {
        if (session == null) {
            return;
        }
        sessions.remove(session.getStreamKey());
        session.invalidate();
        try {
            playService.bye(session);
        } catch (Exception e) {
            log.debug("GB28181 BYE {}: {}", session.getStreamKey(), e.toString());
        }
        session.closeJpegSourceQuietly();
        if (session.isUseZlm() && StringUtils.hasText(session.getZlmStreamId())) {
            ZlmProperties zlm = iotProperties.getVideo().getZlm();
            if (zlmRestClient.isEnabled(zlm)) {
                zlmRestClient.closeRtpServer(zlm, session.getZlmStreamId());
            }
        }
        releaseRtpPortQuietly(resolveReleaseRtpPort(session));
    }

    /** ZLM 方案由 MediaServer 监听 RTP，Java 不预占 30000，避免与 ZLM 冲突。 */
    private int resolveInitialRtpPort(String ownerKey) {
        if (!iotProperties.getVideo().isGb28181UseZlm()) {
            return portPool.allocate(ownerKey);
        }
        ZlmProperties zlm = iotProperties.getVideo().getZlm();
        if (zlmRestClient.isEnabled(zlm)) {
            return 0;
        }
        return portPool.allocate(ownerKey);
    }

    private int resolveReleaseRtpPort(Gb28181MediaSession session) {
        if (session.getLocalRtpPort() > 0) {
            return session.getLocalRtpPort();
        }
        if (!session.isUseZlm() && session.getInviteMediaPort() > 0) {
            return session.getInviteMediaPort();
        }
        return 0;
    }

    public String mediaHost() {
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        return platformConfigService.effectiveMediaHost(cfg);
    }

    void checkInviteCooldown(String deviceId) {
        Long until = inviteCooldownUntilMs.get(deviceId.trim());
        if (until != null && System.currentTimeMillis() < until) {
            long sec = Math.max(1, (until - System.currentTimeMillis()) / 1000);
            throw new IllegalStateException(
                    "摄像机刚连续拒绝国标 INVITE(400)，请等待约 " + sec + " 秒或重启摄像机后再试。"
                            + "若海康网页预览也失败，请先断电重启摄像机并暂时停止本平台拉流");
        }
    }

    /** 释放该设备下所有国标会话（新 INVITE 前释放占用）。 */
    void releaseAllForDevice(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return;
        }
        String prefix = deviceId.trim() + ":";
        List<Gb28181MediaSession> toClose = new ArrayList<>();
        for (Gb28181MediaSession s : sessions.values()) {
            if (s.getDeviceId().equals(deviceId.trim()) || s.getStreamKey().startsWith(prefix)) {
                toClose.add(s);
            }
        }
        for (Gb28181MediaSession s : toClose) {
            log.info("GB28181 释放旧会话 key={} 再点播", s.getStreamKey());
            release(s);
        }
        if (!toClose.isEmpty()) {
            try {
                Thread.sleep(1200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Windows 上 UDP 端口释放滞后，避免下一路 bind 报 10048。 */
    private void releaseRtpPortQuietly(int port) {
        if (port <= 0) {
            return;
        }
        portPool.release(port);
        try {
            Thread.sleep(800L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
