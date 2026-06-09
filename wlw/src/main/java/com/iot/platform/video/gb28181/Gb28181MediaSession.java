package com.iot.platform.video.gb28181;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public class Gb28181MediaSession {

    private final String streamKey;
    private final String deviceId;
    private final String channelId;
    private final int streamIndex;
    private final int localRtpPort;
    /** ZLM openRtpServer 返回端口等，覆盖 INVITE/ACK SDP 中的 m= 端口 */
    @Setter
    private volatile int inviteMediaPort = -1;
    private final AtomicBoolean inviteOk = new AtomicBoolean(false);
    @Setter
    private volatile String callId;
    @Setter
    private volatile Gb28181JpegSource jpegSource;
    @Setter
    private volatile javax.sip.Dialog sipDialog;
    /** 海康 200 OK SDP 中的 y= SSRC（10 位）。 */
    @Setter
    private volatile String remoteSsrcY;
    private final CountDownLatch decoderReady = new CountDownLatch(1);
    private final Object decoderLock = new Object();
    @Setter
    private volatile Exception decoderStartError;
    @Setter
    private volatile boolean decoderStartScheduled;
    /** 400 重试时临时覆盖通道/码流序号。 */
    @Setter
    private volatile String inviteChannelOverride;
    @Setter
    private volatile int inviteStreamIndexOverride = -1;
    /** 已配置密码或已 REGISTER 时用 sip:xxx@domain + Route（海康常见）。 */
    @Setter
    private volatile boolean inviteUseDomainRoute;
    /** INVITE 200 应答协商后的收流方式（覆盖平台默认）。 */
    @Setter
    private volatile Gb28181MediaTransport negotiatedTransport;
    @Setter
    private volatile String remoteMediaHost;
    @Setter
    private volatile int remoteMediaPort = -1;
    @Setter
    private volatile Gb28181TcpRtpBridge tcpRtpBridge;
    @Setter
    private volatile boolean useZlm;
    @Setter
    private volatile String zlmStreamId;
    @Setter
    private volatile String zlmRtspUrl;
    /** INVITE / ZLM openRtpServer 使用的 SSRC（y=）。 */
    @Setter
    private volatile String inviteSsrc;

    public int getEffectiveRtpPort() {
        return inviteMediaPort > 0 ? inviteMediaPort : localRtpPort;
    }

    public Gb28181MediaTransport effectiveMediaTransport(Gb28181MediaTransport platformDefault) {
        return negotiatedTransport != null ? negotiatedTransport : platformDefault;
    }

    public String effectiveInviteChannel() {
        return inviteChannelOverride != null && !inviteChannelOverride.trim().isEmpty()
                ? inviteChannelOverride.trim()
                : channelId;
    }

    public int effectiveInviteStreamIndex() {
        return inviteStreamIndexOverride >= 0 ? inviteStreamIndexOverride : streamIndex;
    }

    public boolean isDecoderStartScheduled() {
        return decoderStartScheduled;
    }

    public boolean awaitDecoderReady(long timeoutSec) throws InterruptedException {
        return decoderReady.await(timeoutSec, TimeUnit.SECONDS);
    }

    public void markDecoderReady() {
        decoderReady.countDown();
    }

    public Gb28181MediaSession(String streamKey, String deviceId, String channelId, int streamIndex, int localRtpPort) {
        this.streamKey = streamKey;
        this.deviceId = deviceId;
        this.channelId = channelId;
        this.streamIndex = streamIndex;
        this.localRtpPort = localRtpPort;
    }

    public void markInviteOk() {
        inviteOk.set(true);
    }

    public boolean isInviteOk() {
        return inviteOk.get();
    }

    public void invalidate() {
        inviteOk.set(false);
        callId = null;
        closeJpegSourceQuietly();
        closeTcpBridgeQuietly();
        sipDialog = null;
    }

    public void closeTcpBridgeQuietly() {
        Gb28181TcpRtpBridge bridge = tcpRtpBridge;
        tcpRtpBridge = null;
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Exception ignored) {
            }
        }
    }

    public Object getDecoderLock() {
        return decoderLock;
    }

    public void closeJpegSourceQuietly() {
        synchronized (decoderLock) {
            Gb28181JpegSource src = jpegSource;
            jpegSource = null;
            if (src != null) {
                try {
                    src.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
