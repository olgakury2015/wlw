package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import com.iot.platform.video.zlm.ZlmProperties;
import com.iot.platform.video.zlm.ZlmRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import gov.nist.javax.sip.ResponseEventExt;
import gov.nist.javax.sip.message.SIPResponse;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.SubjectHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class Gb28181PlayService {

    private final Gb28181SipServerService sipServerService;
    private final Gb28181DeviceRegistry deviceRegistry;
    private final Gb28181PlatformConfigService platformConfigService;
    private final Gb28181UdpPortPool portPool;
    private final Gb28181SsrcAllocator ssrcAllocator;
    private final IotProperties iotProperties;
    private final ZlmRestClient zlmRestClient;

    public void inviteLive(Gb28181MediaSession session) throws Exception {
        if (!iotProperties.getVideo().isGb28181UseZlm()) {
            inviteLiveDirect(session);
            return;
        }
        ZlmProperties zlm = iotProperties.getVideo().getZlm();
        if (!zlmRestClient.isConfigured(zlm)) {
            throw new IllegalStateException(
                    "已启用 gb28181-use-zlm，请在 application.yml 配置 iot.video.zlm（http-host/http-port/secret/sdp-ip）");
        }
        if (!zlmRestClient.isEnabled(zlm)) {
            throw new IllegalStateException(
                    "已启用 gb28181-use-zlm，请设置 iot.video.zlm.enabled=true");
        }
        if (!zlmRestClient.isApiReachable(zlm)) {
            fallbackFromZlm(session, "ZLM HTTP API 不可达 " + zlmRestClient.apiBaseUrl(zlm));
            return;
        }
        inviteLiveWithZlm(session, zlm);
    }

    private void fallbackFromZlm(Gb28181MediaSession session, String reason) throws Exception {
        if (!iotProperties.getVideo().isGb28181ZlmFallback()) {
            throw new IllegalStateException(reason + "（已关闭 gb28181-zlm-fallback，请启动 ZLM 或设 gb28181-use-zlm=false）");
        }
        log.warn("GB28181 {}，回退本机收流（tcp_passive + FFmpeg）", reason);
        resetZlmSessionFlags(session);
        ensureFallbackRtpPort(session);
        inviteLiveDirect(session);
    }

    private static void resetZlmSessionFlags(Gb28181MediaSession session) {
        session.setUseZlm(false);
        session.setZlmStreamId(null);
        session.setZlmRtspUrl(null);
        session.setInviteMediaPort(-1);
    }

    /** ZLM 失败回退时为本机收流分配端口（ZLM 方案初始 localRtpPort=0）。 */
    private void ensureFallbackRtpPort(Gb28181MediaSession session) {
        if (session.getLocalRtpPort() > 0) {
            return;
        }
        if (session.getInviteMediaPort() > 0) {
            return;
        }
        int port = portPool.allocate(session.getStreamKey());
        session.setInviteMediaPort(port);
        log.info("GB28181 回退收流已分配端口={}（避免与 ZLM rtp_proxy 争用 30000）", port);
    }

    private void inviteLiveWithZlm(Gb28181MediaSession session, ZlmProperties zlm) throws Exception {
        String streamId = session.getDeviceId() + "_" + session.getChannelId();
        session.setUseZlm(true);
        session.setZlmStreamId(streamId);
        String sdpIp = resolveZlmSdpIp(zlm);
        String bindIp = platformConfigService.effectiveZlmBindIp(zlm);
        String ssrc = ssrcAllocator.allocatePlaySsrc();
        session.setInviteSsrc(ssrc);
        // 先等设备/REGISTER，再 openRtpServer 并立即 INVITE，避免 ZLM rtp_proxy 默认 15s 超时关端口
        prepareDeviceForInvite(session);
        int preferPort = session.getLocalRtpPort();
        String effectiveBind = bindIp != null ? bindIp : "0.0.0.0";
        log.info("GB28181+ZLM 准备 openRtpServer stream={} sdp-ip={} bind-ip={}",
                streamId, sdpIp, effectiveBind);
        int rtpPort = zlmRestClient.openRtpServer(zlm, streamId, preferPort, 1, ssrc, bindIp);
        if (rtpPort <= 0) {
            rtpPort = zlmRestClient.openRtpServer(zlm, streamId, 0, 1, ssrc, bindIp);
        }
        if (rtpPort <= 0) {
            fallbackFromZlm(session, "ZLM openRtpServer 失败");
            return;
        }
        session.setInviteMediaPort(rtpPort);
        log.info("GB28181+ZLM 已开 RTP 端口={} stream={} sdp-ip={} bind-ip={} ssrc={}，立即发 INVITE（tcp_mode=1）",
                rtpPort, streamId, sdpIp, bindIp != null ? bindIp : "0.0.0.0", ssrc);
        runSipInvitePlay(session, rtpPort, sdpIp, true);
        waitZlmStreamAndBind(session, zlm, streamId);
        log.info("GB28181+ZLM 实况已建立 stream={} rtsp={}", streamId, session.getZlmRtspUrl());
    }

    private String resolveZlmSdpIp(ZlmProperties zlm) {
        return platformConfigService.effectiveZlmSdpIp(zlm);
    }

    /** 海康国标页选 TCP 时，出站 INVITE 优先走 TCP（与媒体 TCP 被动一致）。 */
    private String resolveOutboundSipTransport(Gb28181DeviceSession dev, Gb28181PlatformConfig cfg) {
        if (dev.getSipTransport() != null && !dev.getSipTransport().trim().isEmpty()) {
            return dev.getSipTransport().trim();
        }
        Gb28181MediaTransport mt = platformConfigService.resolveMediaTransport(cfg);
        if (mt.isTcp() && sipServerService.isTcpListening()) {
            return "TCP";
        }
        return "UDP";
    }

    private void inviteLiveDirect(Gb28181MediaSession session) throws Exception {
        resetZlmSessionFlags(session);
        int bindPort = resolveBindRtpPort(session);
        log.info("GB28181 本机收流 device={} channel={} port={}（无 ZLM，tcp_passive 等连）",
                session.getDeviceId(), session.getChannelId(), bindPort);
        prepareDeviceForInvite(session);
        runSipInvitePlay(session, bindPort, null, true);
        if (session.getDecoderStartError() != null) {
            throw new IllegalStateException("国标解码器启动失败: " + session.getDecoderStartError().getMessage(),
                    session.getDecoderStartError());
        }
        if (session.getJpegSource() == null) {
            throw new IllegalStateException("国标解码器未就绪");
        }
        log.info("GB28181 实况已建立 device={} channel={} rtpPort={} mediaHost={}",
                session.getDeviceId(), session.getChannelId(), bindPort,
                platformConfigService.effectiveMediaHost(platformConfigService.getOrCreate()));
    }

    private static int resolveBindRtpPort(Gb28181MediaSession session) {
        if (session.getLocalRtpPort() > 0) {
            return session.getLocalRtpPort();
        }
        return session.getEffectiveRtpPort();
    }

    /** 等待设备在线 / REGISTER（ZLM 须在 openRtpServer 之前完成，避免 ZLM 先超时）。 */
    private Gb28181DeviceSession prepareDeviceForInvite(Gb28181MediaSession session) throws Exception {
        Gb28181PlatformConfig platformCfg = platformConfigService.getOrCreate();
        Gb28181DeviceSession dev = waitForReachableDevice(session.getDeviceId(), 30_000L);
        if (dev.getContactHost() == null || !Gb28181NetUtil.isIpv4(dev.getContactHost())) {
            throw new IllegalStateException(
                    "设备 Contact 不是摄像机 IP（当前="
                            + (dev.getContactHost() != null ? dev.getContactHost() : "空")
                            + "）。请确认海康国标已启用且平台能收到 MESSAGE 心跳");
        }
        String devicePassword = platformConfigService.resolveDevicePassword(session.getDeviceId());
        session.setInviteUseDomainRoute(dev.isSipRegistered());
        if (!dev.isSipRegistered()) {
            if (platformCfg.isRequireSipRegister()) {
                log.info("GB28181 等待摄像机 REGISTER device={}（已勾选「强制要求 REGISTER」）",
                        session.getDeviceId());
                requireSipRegistration(dev, 60_000L);
            } else if (StringUtils.hasText(devicePassword)) {
                log.info("GB28181 已配置国标密码，短时等待 REGISTER device={}（15s）",
                        session.getDeviceId());
                if (!awaitSipRegistration(dev, 15_000L)) {
                    log.warn("GB28181 未收到 REGISTER，按 MESSAGE 心跳 Contact 尝试点播 device={} {}:{}",
                            session.getDeviceId(), dev.getContactHost(), dev.getContactPort());
                }
            }
        }
        if (dev.getRegisteredAt() != null) {
            long sinceRegMs = Instant.now().toEpochMilli() - dev.getRegisteredAt().toEpochMilli();
            if (sinceRegMs < 800L) {
                Thread.sleep(800L - sinceRegMs);
            }
        }
        warnIfChannelNotInCatalog(session, dev);
        if (dev.getCatalogUpdatedAt() == null
                || dev.getCatalogUpdatedAt().isBefore(Instant.now().minusSeconds(300))) {
            try {
                sipServerService.sendCatalogQuery(session.getDeviceId());
                Thread.sleep(400L);
            } catch (Exception e) {
                log.debug("GB28181 Catalog 查询: {}", e.toString());
            }
            warnIfChannelNotInCatalog(session, dev);
        }
        return dev;
    }

    /** SIP INVITE/ACK + 收流（ZLM 或本机 FFmpeg）。 */
    private void runSipInvitePlay(Gb28181MediaSession session, int inviteRtpPort) throws Exception {
        runSipInvitePlay(session, inviteRtpPort, null, false);
    }

    private void runSipInvitePlay(Gb28181MediaSession session, int inviteRtpPort, String mediaIpOverride)
            throws Exception {
        runSipInvitePlay(session, inviteRtpPort, mediaIpOverride, false);
    }

    private void runSipInvitePlay(
            Gb28181MediaSession session,
            int inviteRtpPort,
            String mediaIpOverride,
            boolean skipDevicePrepare) throws Exception {
        if (!StringUtils.hasText(session.getInviteSsrc())) {
            session.setInviteSsrc(ssrcAllocator.allocatePlaySsrc());
        }
        Gb28181PlatformConfig platformCfg = platformConfigService.getOrCreate();
        Gb28181DeviceSession dev = skipDevicePrepare
                ? deviceRegistry.get(session.getDeviceId())
                : prepareDeviceForInvite(session);
        if (dev == null) {
            throw new IllegalStateException("设备未在线: " + session.getDeviceId());
        }
        if (dev.getContactHost() == null || !Gb28181NetUtil.isIpv4(dev.getContactHost())) {
            throw new IllegalStateException("设备 Contact 不是摄像机 IP");
        }
        Gb28181MediaTransport mediaTransport = platformConfigService.resolveMediaTransport(platformCfg);
        log.info("GB28181 媒体传输 mode={} port={} device={} zlm={}",
                mediaTransport.isTcpPassive() ? "TCP被动" : (mediaTransport.isTcpActive() ? "TCP主动" : "UDP"),
                inviteRtpPort, session.getDeviceId(), session.isUseZlm());
        warnIfChannelNotInCatalog(session, dev);
        String callId = "gb-play-" + session.getStreamKey() + "-" + System.currentTimeMillis();
        session.setCallId(callId);
        PendingInvite pending = new PendingInvite(session, mediaTransport);
        sipServerService.registerPendingInvite(callId, pending);
        Gb28181MediaPortGuard portGuard = null;
        if (!session.isUseZlm()) {
            portGuard = Gb28181MediaPortGuard.bind(inviteRtpPort, mediaTransport);
        }
        try {
            pending.setUriTarget(InviteUriTarget.DEVICE);
            sendInvite(session, dev, callId, 1L, null, InviteUriTarget.DEVICE,
                    SubjectStyle.CHANNEL_PLATFORM, mediaTransport, mediaIpOverride);
            boolean ok = pending.await(15, TimeUnit.SECONDS);
            if (!ok) {
                throw new IllegalStateException(pending.inviteFailureMessage());
            }
            pending.sendDeferredAck(this);
            if (!session.isUseZlm()) {
                startDecoderBlocking(session, portGuard);
            }
        } finally {
            if (portGuard != null) {
                portGuard.close();
            }
            sipServerService.unregisterPendingInvite(callId);
        }
    }

    private void waitZlmStreamAndBind(Gb28181MediaSession session, ZlmProperties zlm, String streamId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (zlmRestClient.isRtpStreamOnline(zlm, streamId)) {
                String playHost = platformConfigService.effectiveZlmRtspPlayHost(zlm);
                session.setZlmRtspUrl(zlmRestClient.httpFlvPlayUrl(zlm, streamId, playHost));
                session.markDecoderReady();
                log.info("GB28181+ZLM 流已上线 stream={} 预览地址={}", streamId, session.getZlmRtspUrl());
                return;
            }
            Thread.sleep(300L);
        }
        zlmRestClient.closeRtpServer(zlm, streamId);
        throw new IllegalStateException(
                "ZLM 等待国标流超时 stream=" + streamId
                        + "，请确认：① ZLM 已启动；② 摄像机国标「传输协议」为 TCP 且 SIP 服务器地址="
                        + resolveZlmSdpIp(zlm)
                        + "；③ Windows 防火墙放行 TCP " + session.getEffectiveRtpPort()
                        + " 及 ZLM rtp_proxy 端口段 30000-30500");
    }

    private void handleZlmAfterInvite200(
            Gb28181MediaSession session,
            Gb28181AnswerSdp answer,
            Gb28181MediaTransport negotiated) {
        ZlmProperties zlm = iotProperties.getVideo().getZlm();
        String streamId = session.getZlmStreamId();
        if (!StringUtils.hasText(streamId)) {
            return;
        }
        String answerSsrc = answer.normalizedSsrcY();
        if (StringUtils.hasText(answerSsrc)
                && StringUtils.hasText(session.getInviteSsrc())
                && !answerSsrc.equals(session.getInviteSsrc())) {
            log.info("GB28181+ZLM 摄像机 SSRC 与 INVITE 不一致 {} -> {}，更新 ZLM",
                    session.getInviteSsrc(), answerSsrc);
            zlmRestClient.updateRtpServerSsrc(zlm, streamId, answerSsrc);
            session.setInviteSsrc(answerSsrc);
        }
        if (answer.isTcpSetupActive()) {
            log.info("GB28181+ZLM 等待设备 TCP 连入 stream={} port={}（200 setup:active）",
                    streamId, session.getEffectiveRtpPort());
        } else if (negotiated.isTcpActive()) {
            String camIp = session.getRemoteMediaHost();
            int camPort = session.getRemoteMediaPort();
            if (Gb28181NetUtil.isIpv4(camIp) && camPort > 0) {
                log.info("GB28181+ZLM 200 为 TCP 被动应答，ZLM 主动连 {}:{}", camIp, camPort);
                if (!zlmRestClient.connectRtpServer(zlm, streamId, camIp, camPort)) {
                    log.warn("GB28181+ZLM connectRtpServer 失败，请检查摄像机媒体端口与防火墙");
                }
            }
        }
    }

    void handleInviteResponse(ResponseEvent event, PendingInvite pending) {
        Response response = event.getResponse();
        if (response == null) {
            return;
        }
        int status = response.getStatusCode();
        Gb28181MediaSession session = pending.getSession();
        String reason = response.getReasonPhrase();
        pending.setLastResponse(status, reason);
        log.info("GB28181 INVITE 响应 status={} reason={} device={} channel={} uriTarget={}",
                status, reason, session.getDeviceId(), session.getChannelId(), pending.getUriTarget());
        if (status == Response.BAD_REQUEST || status == Response.FORBIDDEN) {
            logInviteRejectDetail(response);
        }

        if (status == Response.OK) {
            completeInviteOk(event, pending);
            return;
        }
        if (status == Response.UNAUTHORIZED) {
            if (pending.getAuthAttempts() >= 2) {
                pending.fail();
                log.warn("GB28181 INVITE 401 重试次数用尽 device={}", session.getDeviceId());
                return;
            }
            try {
                resendInviteWithDigest(event, pending);
            } catch (Exception e) {
                pending.fail();
                log.warn("GB28181 INVITE 401 鉴权重发失败: {}", e.getMessage());
            }
            return;
        }
        if (status == Response.FORBIDDEN && pending.tryAlternateUriOn403()) {
            try {
                Gb28181DeviceSession dev = deviceRegistry.get(session.getDeviceId());
                InviteUriTarget alt = pending.getUriTarget();
                log.info("GB28181 INVITE 403 后改用 {} 重试", alt);
                sendInvite(session, dev, session.getCallId(), pending.nextCSeq(), null, alt,
                        pending.getSubjectStyle(), pending.getMediaTransport(), null);
            } catch (Exception e) {
                pending.fail();
                log.warn("GB28181 INVITE 403 重试失败: {}", e.getMessage());
            }
            return;
        }
        if (status >= 300) {
            pending.fail();
            if (status == Response.FORBIDDEN) {
                Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
                log.warn("GB28181 INVITE 403 拒绝 device={} channel={} 平台SIP ID={}（须与海康「SIP服务器ID」34020000002000000002 等一致）",
                        session.getDeviceId(), session.getChannelId(), cfg.getSipId());
            } else if (status == Response.BAD_REQUEST) {
                Gb28181DeviceSession dev = deviceRegistry.get(session.getDeviceId());
                boolean reg = dev != null && dev.isSipRegistered();
                String pwd = platformConfigService.resolveDevicePassword(session.getDeviceId());
                String catalogHint = "";
                if (dev != null && dev.getCatalogDeviceIds() != null && !dev.getCatalogDeviceIds().isEmpty()) {
                    catalogHint = "；Catalog 通道=" + dev.getCatalogDeviceIds();
                }
                log.warn("GB28181 INVITE 400 device={} channel={} sipRegistered={} pwdConfigured={}{}。"
                                + " 请核对海康「视频通道编码 ID」、平台 SIP ID={}、重启摄像机；勿连续重试 INVITE",
                        session.getDeviceId(), session.getChannelId(), reg, StringUtils.hasText(pwd), catalogHint,
                        platformConfigService.getOrCreate().getSipId());
            } else {
                log.warn("GB28181 INVITE 失败 status={} device={} channel={}", status,
                        session.getDeviceId(), session.getChannelId());
            }
        }
    }

    private void completeInviteOk(ResponseEvent event, PendingInvite pending) {
        try {
            Response response = event.getResponse();
            ClientTransaction ct = event.getClientTransaction();
            if (ct == null) {
                ct = pending.getInviteTransaction();
            }
            Dialog dialog = event.getDialog();
            if (dialog == null && ct != null) {
                dialog = ct.getDialog();
            }
            CSeqHeader cSeq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
            long cSeqNum = cSeq != null ? cSeq.getSeqNumber() : 1L;

            Gb28181AnswerSdp answer = logInviteAnswerSdp(response);
            Gb28181MediaSession mediaSession = pending.getSession();
            String ssrc = answer.normalizedSsrcY();
            mediaSession.setRemoteSsrcY(ssrc);
            Gb28181MediaTransport negotiated = answer.negotiatedTransport();
            mediaSession.setNegotiatedTransport(negotiated);
            String camIp = answer.getMediaIp();
            if (!Gb28181NetUtil.isIpv4(camIp)) {
                Gb28181DeviceSession dev = deviceRegistry.get(mediaSession.getDeviceId());
                if (dev != null && Gb28181NetUtil.isIpv4(dev.getContactHost())) {
                    camIp = dev.getContactHost();
                }
            }
            if (Gb28181NetUtil.isIpv4(camIp)) {
                mediaSession.setRemoteMediaHost(camIp);
            }
            if (answer.getMediaPort() > 0) {
                mediaSession.setRemoteMediaPort(answer.getMediaPort());
            }
            if (answer.isTcpSetupActive()) {
                log.info("GB28181 摄像机 200 setup:active → {}:{}（平台被动监听，等待设备连入，勿连 m= 端口）",
                        mediaSession.getRemoteMediaHost(), mediaSession.getRemoteMediaPort());
            } else if (negotiated.isTcpActive()) {
                log.info("GB28181 摄像机应答 TCP主动，平台将连接 {}:{}",
                        mediaSession.getRemoteMediaHost(), mediaSession.getRemoteMediaPort());
            }
            if (mediaSession.isUseZlm()) {
                handleZlmAfterInvite200(mediaSession, answer, negotiated);
            }
            if (ssrc != null) {
                log.info("GB28181 摄像机 SSRC y={}（收流不过滤 y，避免 PT/cseq 错乱）", ssrc);
            } else if (answer.getSsrcY() != null) {
                log.info("GB28181 摄像机 SSRC y={} 为通配值，收流不过滤 y", answer.getSsrcY());
            }

            if (dialog != null) {
                pending.stageAck(dialog, cSeqNum);
                pending.getSession().setSipDialog(dialog);
                pending.completeSuccess(dialog);
                return;
            }

            // AUTOMATIC_DIALOG_SUPPORT=off 时无 Dialog，按 wvp 手动构造 ACK
            if (!(response instanceof SIPResponse)) {
                pending.fail();
                log.warn("GB28181 INVITE 200 非 SIPResponse，无法 ACK");
                return;
            }
            Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
            String mediaIp = mediaSession.isUseZlm()
                    ? resolveZlmSdpIp(iotProperties.getVideo().getZlm())
                    : platformConfigService.effectiveMediaHost(cfg);
            Gb28181MediaTransport transport = mediaSession.effectiveMediaTransport(pending.getMediaTransport());
            // 海康 200 setup:active → 平台被动监听，ACK 必须带 setup:passive SDP（与 INVITE 端口一致）
            Gb28181MediaTransport ackTransport = answer.isTcpSetupActive()
                    ? Gb28181MediaTransport.TCP_PASSIVE
                    : transport;
            String ackSdp;
            if (ackTransport.isTcpActive() && !mediaSession.isUseZlm()) {
                ackSdp = null;
            } else {
                ackSdp = Gb28181SdpUtil.buildAckRecvSdp(
                        mediaIp, mediaSession.getEffectiveRtpPort(), ackTransport,
                        mediaSession.getInviteSsrc());
            }
            if (mediaSession.isUseZlm() && ackSdp != null) {
                log.info("GB28181+ZLM ACK SDP 收流 {}:{}（由 ZLM 监听，非 Java 进程）",
                        mediaIp, mediaSession.getEffectiveRtpPort());
            }
            String remoteIp = "";
            int remotePort = 5060;
            if (event instanceof ResponseEventExt) {
                ResponseEventExt ext = (ResponseEventExt) event;
                if (ext.getRemoteIpAddress() != null) {
                    remoteIp = ext.getRemoteIpAddress();
                }
                if (ext.getRemotePort() > 0) {
                    remotePort = ext.getRemotePort();
                }
            }
            Gb28181DeviceSession dev = deviceRegistry.get(pending.getSession().getDeviceId());
            if (dev != null && Gb28181NetUtil.isIpv4(dev.getContactHost())) {
                remoteIp = dev.getContactHost();
                if (dev.getContactPort() > 0) {
                    remotePort = dev.getContactPort();
                }
            }
            String stackIp = platformConfigService.resolveSipStackIp(cfg);
            Request ack = Gb28181SipAckBuilder.buildAck(
                    (SIPResponse) response,
                    stackIp,
                    cfg.getPort(),
                    cfg.getSipId(),
                    remoteIp,
                    remotePort,
                    ackSdp);
            SipProvider ackProvider = event.getSource() instanceof SipProvider
                    ? (SipProvider) event.getSource()
                    : null;
            pending.stageManualAck(ack, ct, ackProvider, cSeqNum);
            pending.completeSuccess(null);
            log.info("GB28181 INVITE 200 已准备手动 ACK → {}:{}（无 Dialog，对齐 wvp）", remoteIp, remotePort);
        } catch (Exception e) {
            pending.fail();
            log.warn("GB28181 INVITE 200 处理失败: {}", e.getMessage(), e);
        }
    }

    private void sendManualAck(Request ack, SipProvider provider, Gb28181MediaSession session) throws Exception {
        if (provider == null) {
            Gb28181DeviceSession dev = deviceRegistry.get(session.getDeviceId());
            String transport = dev != null && dev.getSipTransport() != null ? dev.getSipTransport() : "UDP";
            provider = sipServerService.requireProvider(transport);
        }
        provider.sendRequest(ack);
        boolean hasSdp = ack.getContent() != null;
        log.info("GB28181 已发送手动 ACK{} device={} localRtpPort={}",
                hasSdp ? "（含 SDP）" : "（无 SDP，TCP主动由桥接连摄像机）",
                session.getDeviceId(), session.getEffectiveRtpPort());
    }

    /** GB28181 要求 ACK 携带平台收流 SDP（与 INVITE 一致），缺省会话易被海康立即 BYE。 */
    private void sendAckWithRecvSdp(Dialog dialog, long cSeqNum, Gb28181MediaSession session) throws Exception {
        Request ack = dialog.createAck(cSeqNum);
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        String mediaIp = platformConfigService.effectiveMediaHost(cfg);
        Gb28181MediaTransport transport = session.effectiveMediaTransport(
                platformConfigService.resolveMediaTransport(cfg));
        if (session.getNegotiatedTransport() != null && session.getNegotiatedTransport().isTcpPassive()) {
            transport = Gb28181MediaTransport.TCP_PASSIVE;
        }
        String sdp = Gb28181SdpUtil.buildAckRecvSdp(
                mediaIp, session.getEffectiveRtpPort(), transport, session.getInviteSsrc());
        ContentTypeHeader ct = sipServerService.getHeaderFactory().createContentTypeHeader("application", "sdp");
        ack.setContent(sdp, ct);
        dialog.sendAck(ack);
        log.info("GB28181 已发送 ACK（含收流 SDP）rtpPort={} mediaHost={}", session.getEffectiveRtpPort(), mediaIp);
    }

    private Gb28181DeviceSession waitForReachableDevice(String deviceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Gb28181DeviceSession dev = deviceRegistry.get(deviceId);
            if (dev != null && dev.isOnline() && dev.hasReachableContact()) {
                return dev;
            }
            Thread.sleep(250L);
        }
        Gb28181DeviceSession dev = deviceRegistry.get(deviceId);
        if (dev != null && dev.hasReachableContact()) {
            long ago = dev.secondsSinceKeepalive();
            log.warn("GB28181 心跳已超时 device={} contact={}:{} 距上次信令约 {}s，仍尝试点播"
                            + "（海康页若显示不在线，请保存国标配置并核对密码/SIP服务器ID）",
                    deviceId, dev.getContactHost(), dev.getContactPort(),
                    ago >= 0 ? ago : "未知");
            dev.setOnline(true);
            return dev;
        }
        if (dev == null) {
            throw new IllegalStateException(
                    "国标设备无会话记录：" + deviceId
                            + "。请确认海康「SIP服务器地址」=" + platformConfigService.effectiveMediaHost(
                            platformConfigService.getOrCreate())
                            + "、已启用国标，且日志有 GB28181 SIP 入站 method=MESSAGE/REGISTER");
        }
        throw new IllegalStateException(
                "国标设备无有效 Contact（当前="
                        + (dev.getContactHost() != null ? dev.getContactHost() : "空")
                        + "）。请在通道配置填写 RTSP 地址以便推断摄像机 IP，或等待 MESSAGE 心跳");
    }

    private void warnIfChannelNotInCatalog(Gb28181MediaSession session, Gb28181DeviceSession dev) {
        if (dev == null || dev.getCatalogDeviceIds() == null || dev.getCatalogDeviceIds().isEmpty()) {
            return;
        }
        String ch = session.getChannelId();
        if (ch == null || dev.getCatalogDeviceIds().contains(ch)) {
            return;
        }
        log.warn("GB28181 通道 {} 不在摄像机 Catalog 中，海康可用通道={}，请在「视频通道配置」改为 Catalog 中的编码",
                ch, dev.getCatalogDeviceIds());
    }

    private boolean awaitSipRegistration(Gb28181DeviceSession dev, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!dev.isSipRegistered() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250L);
        }
        return dev.isSipRegistered();
    }

    private void requireSipRegistration(Gb28181DeviceSession dev, long timeoutMs) throws InterruptedException {
        if (!awaitSipRegistration(dev, timeoutMs)) {
            throw new IllegalStateException(
                    "等待摄像机 SIP REGISTER 超时（请核对平台「国标28181」设备密码与海康密码一致后保存，"
                            + "并在海康国标页重新启用）");
        }
    }

    /** INVITE 200 且已发 ACK 后绑定 RTP/PS 解码（摄像机 ACK 后才开始推流）。 */
    private void startDecoderBlocking(Gb28181MediaSession session, Gb28181MediaPortGuard portGuard) {
        synchronized (session.getDecoderLock()) {
            if (session.getJpegSource() != null) {
                session.markDecoderReady();
                return;
            }
            session.closeJpegSourceQuietly();
        }
        session.setDecoderStartError(null);
        String rtpSenderIp = null;
        try {
            String mediaHost = platformConfigService.effectiveMediaHost(platformConfigService.getOrCreate());
            Gb28181DeviceSession dev = deviceRegistry.get(session.getDeviceId());
            if (dev != null && Gb28181NetUtil.isIpv4(dev.getContactHost())) {
                rtpSenderIp = dev.getContactHost();
            }
            long t0 = System.currentTimeMillis();
            Gb28181PlatformConfig platformCfg = platformConfigService.getOrCreate();
            Gb28181MediaTransport transport = session.effectiveMediaTransport(
                    platformConfigService.resolveMediaTransport(platformCfg));
            String recvHost = rtpSenderIp;
            int recvPort = session.getEffectiveRtpPort();
            boolean tcpBridged = false;
            if (transport.isTcpPassive() && portGuard != null && portGuard.isTcpPassive()) {
                session.closeTcpBridgeQuietly();
                tcpBridged = true;
                rtpSenderIp = null;
                recvHost = "127.0.0.1";
                transport = Gb28181MediaTransport.UDP;
                final Gb28181MediaPortGuard guard = portGuard;
                final int bridgePort = recvPort;
                log.info("GB28181 等待摄像机 TCP 连入 port={}（200 setup:active）…", bridgePort);
                java.net.Socket client = guard.acceptTcpClient(20_000);
                if (client == null) {
                    throw new IOException("等待摄像机 TCP 连入超时 port=" + bridgePort);
                }
                guard.closeTcpListenOnly();
                java.io.PushbackInputStream peekIn = new java.io.PushbackInputStream(
                        new java.io.BufferedInputStream(client.getInputStream(), 128 * 1024), 4);
                int firstByte = peekIn.read();
                if (firstByte < 0) {
                    throw new IOException("摄像机 TCP 媒体流立即关闭");
                }
                peekIn.unread(firstByte);
                if (firstByte == 0x00 || firstByte == 0x47) {
                    log.info("GB28181 首字节=0x{}，判定为 PS/MPEG-TS 直送 → FFmpeg pipe",
                            Integer.toHexString(firstByte & 0xFF));
                    Gb28181JpegSource pipeSrc = Gb28181FfmpegTcpPipeSource.startWithInput(
                            peekIn, client, iotProperties.getVideo());
                    synchronized (session.getDecoderLock()) {
                        session.setJpegSource(pipeSrc);
                    }
                    log.info("GB28181 解码器已绑定 mode=TCP被动→pipe 耗时 {}ms",
                            System.currentTimeMillis() - t0);
                    return;
                }
                log.info("GB28181 首字节=0x{}，判定为 RTP 封装 → UDP 桥接 + FFmpeg",
                        Integer.toHexString(firstByte & 0xFF));
                session.setTcpRtpBridge(Gb28181TcpRtpBridge.fromMediaInput(client, peekIn, bridgePort));
                Gb28181JpegSource srcBridged = Gb28181JpegSourceFactory.start(
                        recvPort,
                        iotProperties.getVideo(),
                        mediaHost,
                        session.getRemoteSsrcY(),
                        recvHost,
                        transport,
                        true);
                Gb28181TcpRtpBridge bridge = session.getTcpRtpBridge();
                if (bridge != null) {
                    log.info("GB28181 TCP 桥接已转发 RTP 包={} 字节={}",
                            bridge.rtpPackets(), bridge.rtpBytes());
                }
                synchronized (session.getDecoderLock()) {
                    session.setJpegSource(srcBridged);
                }
                log.info("GB28181 解码器已绑定 mode=TCP被动→UDP桥接 target={}:{} 耗时 {}ms",
                        recvHost, recvPort, System.currentTimeMillis() - t0);
                return;
            } else if (transport.isTcpActive()) {
                String camIp = session.getRemoteMediaHost();
                int camPort = session.getRemoteMediaPort();
                if (!Gb28181NetUtil.isIpv4(camIp) && Gb28181NetUtil.isIpv4(rtpSenderIp)) {
                    camIp = rtpSenderIp;
                }
                if (camPort <= 0) {
                    throw new IOException("摄像机 200 SDP 无有效 TCP 媒体端口");
                }
                session.closeTcpBridgeQuietly();
                try {
                    Gb28181TcpRtpBridge bridge = Gb28181TcpRtpBridge.start(
                            camIp, camPort, recvPort, 5000);
                    session.setTcpRtpBridge(bridge);
                    Thread.sleep(300L);
                } catch (IOException e) {
                    throw new IOException("TCP主动连接摄像机 " + camIp + ":" + camPort + " 失败: " + e.getMessage(), e);
                }
                recvHost = "127.0.0.1";
                transport = Gb28181MediaTransport.UDP;
            }
            Gb28181JpegSource src = Gb28181JpegSourceFactory.start(
                    recvPort,
                    iotProperties.getVideo(),
                    mediaHost,
                    session.getRemoteSsrcY(),
                    recvHost,
                    transport,
                    tcpBridged);
            synchronized (session.getDecoderLock()) {
                session.setJpegSource(src);
            }
            String modeLabel = tcpBridged ? "TCP被动→UDP桥接"
                    : (transport.isTcpActive() ? "TCP主动" : (transport.isTcpPassive() ? "TCP被动" : "UDP"));
            log.info("GB28181 解码器已绑定 mode={} target={}:{} 耗时 {}ms ssrc={}",
                    modeLabel, recvHost, recvPort, System.currentTimeMillis() - t0, session.getRemoteSsrcY());
        } catch (Exception e) {
            session.setDecoderStartError(e);
            log.warn("GB28181 解码器启动失败 port={}: {}", session.getLocalRtpPort(), e.getMessage());
            Gb28181PlatformConfig platformCfg = platformConfigService.getOrCreate();
            Gb28181RtpProbe.ProbeResult probe = Gb28181RtpProbe.smoke(
                    session.getLocalRtpPort(), rtpSenderIp, 120);
            if (!probe.anyReceived()) {
                Gb28181MediaTransport failTransport = session.effectiveMediaTransport(
                        platformConfigService.resolveMediaTransport(platformCfg));
                if (failTransport.isTcpActive()) {
                    Gb28181TcpRtpBridge bridge = session.getTcpRtpBridge();
                    long pkts = bridge != null ? bridge.rtpPackets() : 0;
                    log.warn("GB28181 TCP主动收流失败 target={}:{} 桥接RTP包={}（请确认能访问摄像机媒体端口；海康勿开第二路流/RTSP）",
                            session.getRemoteMediaHost(), session.getRemoteMediaPort(), pkts);
                } else {
                    log.warn("GB28181 解码失败后 port={} 仍无 UDP（请检查防火墙入站 UDP {}-{}，media-host={}，摄像机={}）",
                            session.getLocalRtpPort(),
                            platformCfg.getMediaPortMin(),
                            platformCfg.getMediaPortMax(),
                            platformConfigService.effectiveMediaHost(platformCfg),
                            rtpSenderIp != null ? rtpSenderIp : "?");
                }
            } else {
                log.info("GB28181 解码失败但 RTP 可达 port={} 包={} 字节={} 来自={}（多为 PS 头被提前消费或编码非 H.264）",
                        session.getLocalRtpPort(), probe.totalPackets, probe.totalBytes, rtpSenderIp);
            }
        } finally {
            session.markDecoderReady();
        }
    }

    private Gb28181AnswerSdp logInviteAnswerSdp(Response response) {
        if (response == null) {
            return Gb28181AnswerSdp.parse(null);
        }
        Object content = response.getContent();
        if (content == null) {
            log.info("GB28181 INVITE 200 无 SDP 正文（部分机型仍可向 INVITE 中端口推流）");
            return Gb28181AnswerSdp.parse(null);
        }
        String sdp = content instanceof byte[]
                ? new String((byte[]) content, java.nio.charset.StandardCharsets.UTF_8)
                : content.toString();
        String oneLine = sdp.replace('\r', ' ').replace('\n', ' ').trim();
        if (oneLine.length() > 600) {
            oneLine = oneLine.substring(0, 600) + "…";
        }
        log.info("GB28181 INVITE 200 SDP: {}", oneLine);
        return Gb28181AnswerSdp.parse(sdp);
    }

    private void resendInviteWithDigest(ResponseEvent event, PendingInvite pending) throws Exception {
        WWWAuthenticateHeader www = (WWWAuthenticateHeader) event.getResponse().getHeader(WWWAuthenticateHeader.NAME);
        if (www == null) {
            pending.fail();
            return;
        }
        String realm = www.getRealm();
        String nonce = www.getNonce();
        String qop = www.getQop();
        Gb28181MediaSession session = pending.getSession();
        Gb28181DeviceSession dev = deviceRegistry.get(session.getDeviceId());
        String password = platformConfigService.resolveDevicePassword(session.getDeviceId());
        if (!StringUtils.hasText(password)) {
            pending.fail();
            log.warn("GB28181 INVITE 401 但未配置设备密码 device={}", session.getDeviceId());
            return;
        }
        pending.incrementAuthAttempts();
        long cSeq = pending.nextCSeq();
        String callId = session.getCallId();
        sendInvite(session, dev, callId, cSeq, new DigestParams(realm, nonce, qop, password),
                pending.getUriTarget(), pending.getSubjectStyle(), pending.getMediaTransport(), null);
    }

    private enum InviteUriTarget {
        DEVICE,
        CHANNEL
    }

    /**
     * GB/T 28181 Subject：{发送方}:{流序号},{接收方}:{流序号}。
     * 实况点播时发送方=通道编码，接收方=平台 SIP ID（海康常用；第二段写设备 ID 会 400）。
     */
    private enum SubjectStyle {
        CHANNEL_PLATFORM,
        CHANNEL_DEVICE_STREAM,
        CHANNEL_ONLY,
        DEVICE_CHANNEL_STREAM
    }

    private static String buildSubject(
            Gb28181MediaSession session,
            SubjectStyle style,
            String channelId,
            int streamIndex,
            String platformId) {
        int stream = streamIndex >= 0 ? streamIndex : 0;
        String ch = channelId != null ? channelId : session.getChannelId();
        switch (style) {
            case CHANNEL_ONLY:
                return ch + ":" + stream;
            case DEVICE_CHANNEL_STREAM:
                return session.getDeviceId() + ":0," + ch + ":" + stream;
            case CHANNEL_DEVICE_STREAM:
                return ch + ":" + stream + "," + session.getDeviceId() + ":" + stream;
            case CHANNEL_PLATFORM:
            default:
                return ch + ":" + stream + "," + platformId + ":" + stream;
        }
    }

    private void sendInvite(
            Gb28181MediaSession session,
            Gb28181DeviceSession dev,
            String callId,
            long cSeqNum,
            DigestParams digest,
            InviteUriTarget uriTarget,
            SubjectStyle subjectStyle,
            Gb28181MediaTransport mediaTransport,
            String mediaIpOverride) throws Exception {
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        String sipTransport = resolveOutboundSipTransport(dev, cfg);
        SipProvider provider = sipServerService.requireProvider(sipTransport);
        log.info("GB28181 INVITE 使用 SIP 传输={} -> {}:{}", sipTransport,
                dev.getContactHost(), dev.getContactPort());
        AddressFactory addressFactory = sipServerService.getAddressFactory();
        HeaderFactory headerFactory = sipServerService.getHeaderFactory();
        MessageFactory messageFactory = sipServerService.getMessageFactory();
        String platformId = cfg.getSipId();
        String domain = cfg.getSipDomain();
        String mediaIp = StringUtils.hasText(mediaIpOverride)
                ? mediaIpOverride.trim()
                : platformConfigService.effectiveMediaHost(cfg);
        int rtpPort = session.getEffectiveRtpPort();

        String channelForInvite = session.effectiveInviteChannel();
        int streamForInvite = session.effectiveInviteStreamIndex();
        // 海康多数机型 Request-URI 用设备编码；通道编码放在 Subject / SDP u=
        String inviteUser = uriTarget == InviteUriTarget.CHANNEL ? channelForInvite : session.getDeviceId();
        boolean domainRoute = dev.isSipRegistered();
        SipURI requestUri;
        if (domainRoute) {
            requestUri = addressFactory.createSipURI(inviteUser, domain);
        } else {
            requestUri = addressFactory.createSipURI(inviteUser, dev.getContactHost());
            if (dev.getContactPort() > 0) {
                requestUri.setPort(dev.getContactPort());
            }
        }
        String uriStr = requestUri.toString();
        // 海康：Request-URI 指向摄像机 IP；To 统一用设备编码@SIP域（通道编码仅放 Subject/SDP u=）
        String toUser = session.getDeviceId();
        SipURI toUri = addressFactory.createSipURI(toUser, domain);
        Address toAddress = addressFactory.createAddress(toUri);
        ToHeader toHeader = headerFactory.createToHeader(toAddress, null);

        SipURI fromUri = addressFactory.createSipURI(platformId, domain);
        Address fromAddress = addressFactory.createAddress(fromUri);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, "tag" + System.currentTimeMillis());

        SipURI contactUri = addressFactory.createSipURI(platformId, mediaIp);
        contactUri.setPort(cfg.getPort());
        Address contactAddress = addressFactory.createAddress(contactUri);
        ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);

        CallIdHeader callIdHeader = headerFactory.createCallIdHeader(callId);
        CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(cSeqNum, Request.INVITE);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
        ArrayList<ViaHeader> viaHeaders = new ArrayList<>();
        String stackIp = platformConfigService.resolveSipStackIp(cfg);
        String viaTransport = "TCP".equalsIgnoreCase(sipTransport) ? "tcp" : "udp";
        ViaHeader via = headerFactory.createViaHeader(stackIp, cfg.getPort(), viaTransport, null);
        viaHeaders.add(via);

        String sdp = Gb28181SdpUtil.buildInviteSdp(
                platformId, mediaIp, rtpPort, channelForInvite, streamForInvite, mediaTransport,
                session.getInviteSsrc());
        if (StringUtils.hasText(session.getInviteSsrc())) {
            log.info("GB28181 INVITE SDP y={} rtpPort={}", session.getInviteSsrc(), rtpPort);
        }
        Request invite = messageFactory.createRequest(
                requestUri, Request.INVITE, callIdHeader, cSeqHeader, fromHeader, toHeader, viaHeaders, maxForwards);
        if (domainRoute) {
            SipURI routeUri = addressFactory.createSipURI(session.getDeviceId(), dev.getContactHost());
            if (dev.getContactPort() > 0) {
                routeUri.setPort(dev.getContactPort());
            }
            invite.addHeader(headerFactory.createRouteHeader(addressFactory.createAddress(routeUri)));
        }
        invite.addHeader(contactHeader);
        String subjectVal = buildSubject(session, subjectStyle, channelForInvite, streamForInvite, platformId);
        SubjectHeader subject = headerFactory.createSubjectHeader(subjectVal);
        invite.addHeader(subject);
        ContentTypeHeader ct = headerFactory.createContentTypeHeader("application", "sdp");
        invite.setContent(sdp, ct);
        log.info("GB28181 INVITE 信令 Request-URI={} To={} Subject={} rtpPort={}",
                uriStr, toUri, subjectVal, rtpPort);

        if (digest != null) {
            AuthorizationHeader auth = headerFactory.createAuthorizationHeader("Digest");
            auth.setUsername(session.getDeviceId());
            auth.setRealm(digest.realm);
            auth.setNonce(digest.nonce);
            auth.setURI(requestUri);
            auth.setAlgorithm("MD5");
            String qopVal = digest.qop != null && !digest.qop.trim().isEmpty() ? digest.qop.trim() : null;
            if (qopVal != null) {
                auth.setQop(qopVal);
                auth.setNonceCount(1);
                auth.setCNonce(Gb28181DigestHelper.newNonce().substring(0, 16));
            }
            String response = Gb28181DigestHelper.clientInviteResponse(
                    uriStr, session.getDeviceId(), digest.realm, digest.nonce, digest.password, qopVal);
            auth.setResponse(response);
            invite.addHeader(auth);
            log.info("GB28181 INVITE 带 Digest 重发 device={} channel={} cSeq={}",
                    session.getDeviceId(), session.getChannelId(), cSeqNum);
        } else {
            log.info("GB28181 INVITE 发送 uriTarget={} platformId={} device={} channel={} -> {} rtpPort={}",
                    uriTarget, platformId, session.getDeviceId(), session.getChannelId(), uriStr, rtpPort);
        }

        ClientTransaction tx = provider.getNewClientTransaction(invite);
        Gb28181PlayService.PendingInvite pendingInvite = sipServerService.getPendingInvite(callId);
        if (pendingInvite != null) {
            pendingInvite.setInviteTransaction(tx, provider);
        }
        tx.sendRequest();
    }

    public void bye(Gb28181MediaSession session) {
        Dialog dialog = session.getSipDialog();
        if (dialog == null) {
            return;
        }
        try {
            String transport = "UDP";
            Gb28181DeviceSession dev = deviceRegistry.get(session.getDeviceId());
            if (dev != null && dev.getSipTransport() != null) {
                transport = dev.getSipTransport();
            }
            Request bye = dialog.createRequest(Request.BYE);
            ClientTransaction tx = sipServerService.requireProvider(transport).getNewClientTransaction(bye);
            tx.sendRequest();
        } catch (Exception e) {
            log.debug("BYE failed: {}", e.toString());
        }
    }

    static final class PendingInvite {
        private final Gb28181MediaSession session;
        private final Gb28181MediaTransport mediaTransport;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<Dialog> dialog = new AtomicReference<>();
        private final AtomicInteger authAttempts = new AtomicInteger();
        private final AtomicInteger cSeq = new AtomicInteger(1);
        private volatile boolean success;
        private volatile InviteUriTarget uriTarget = InviteUriTarget.DEVICE;
        private volatile SubjectStyle subjectStyle = SubjectStyle.CHANNEL_PLATFORM;
        private volatile boolean uri403Retried;
        private volatile int lastStatus;
        private volatile String lastReason = "";

        PendingInvite(Gb28181MediaSession session, Gb28181MediaTransport mediaTransport) {
            this.session = session;
            this.mediaTransport = mediaTransport != null ? mediaTransport : Gb28181MediaTransport.TCP_PASSIVE;
        }

        Gb28181MediaSession getSession() {
            return session;
        }

        Gb28181MediaTransport getMediaTransport() {
            return mediaTransport;
        }

        InviteUriTarget getUriTarget() {
            return uriTarget;
        }

        void setUriTarget(InviteUriTarget uriTarget) {
            this.uriTarget = uriTarget;
        }

        SubjectStyle getSubjectStyle() {
            return subjectStyle;
        }

        String inviteChannelForSdp() {
            return session.effectiveInviteChannel();
        }

        int inviteStreamIndex() {
            return session.effectiveInviteStreamIndex();
        }

        void setLastResponse(int status, String reason) {
            this.lastStatus = status;
            this.lastReason = reason != null ? reason : "";
        }

        boolean tryAlternateUriOn403() {
            if (uri403Retried) {
                return false;
            }
            uri403Retried = true;
            uriTarget = uriTarget == InviteUriTarget.DEVICE ? InviteUriTarget.CHANNEL : InviteUriTarget.DEVICE;
            return true;
        }

        String inviteFailureMessage() {
            if (lastStatus == Response.FORBIDDEN) {
                return "摄像机拒绝国标 INVITE(403)，device=" + session.getDeviceId() + " channel=" + session.getChannelId()
                        + "。请核对：① 国标页「平台 SIP 服务器 ID」须与海康完全一致(如 34020000002000000002)；"
                        + "② 设备/通道编码与海康一致；③ 海康仅允许已注册平台拉流";
            }
            if (lastStatus == Response.BAD_REQUEST) {
                return "摄像机返回 INVITE 400，device=" + session.getDeviceId() + " channel=" + session.getChannelId()
                        + "。请确认：① 海康网页预览是否正常（若也失败请重启摄像机）；② 通道编码与日志 Catalog 一致；"
                        + "③ 平台 SIP ID 与海康「SIP服务器ID」一致；④ 停用同机 RTSP 与其它国标平台";
            }
            return "国标 INVITE 超时或失败，device=" + session.getDeviceId() + " channel=" + session.getChannelId()
                    + (lastStatus > 0 ? "，最后响应=" + lastStatus + " " + lastReason : "")
                    + "（请确认设备在线、通道编码与海康一致）";
        }

        int getAuthAttempts() {
            return authAttempts.get();
        }

        void incrementAuthAttempts() {
            authAttempts.incrementAndGet();
        }

        long nextCSeq() {
            return cSeq.incrementAndGet();
        }

        void completeSuccess(Dialog d) {
            dialog.set(d);
            success = true;
            latch.countDown();
        }

        private volatile Dialog ackDialog;
        private volatile long ackCSeq = -1L;
        private volatile Request manualAckRequest;
        private volatile ClientTransaction inviteTransaction;
        private volatile SipProvider inviteSipProvider;

        void setInviteTransaction(ClientTransaction tx, SipProvider provider) {
            this.inviteTransaction = tx;
            this.inviteSipProvider = provider;
        }

        ClientTransaction getInviteTransaction() {
            return inviteTransaction;
        }

        void stageAck(Dialog d, long cSeqNum) {
            this.ackDialog = d;
            this.ackCSeq = cSeqNum;
        }

        void stageManualAck(Request ack, ClientTransaction tx, SipProvider provider, long cSeqNum) {
            this.manualAckRequest = ack;
            this.inviteTransaction = tx != null ? tx : inviteTransaction;
            this.inviteSipProvider = provider != null ? provider : inviteSipProvider;
            this.ackCSeq = cSeqNum;
        }

        void sendDeferredAck(Gb28181PlayService play) throws Exception {
            if (manualAckRequest != null) {
                play.sendManualAck(manualAckRequest, inviteSipProvider, session);
                return;
            }
            if (ackDialog == null || ackCSeq < 0) {
                log.warn("GB28181 INVITE 200 无待发送 ACK，跳过");
                return;
            }
            play.sendAckWithRecvSdp(ackDialog, ackCSeq, session);
        }

        void fail() {
            latch.countDown();
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            latch.await(timeout, unit);
            return success;
        }
    }

    private static final class DigestParams {
        final String realm;
        final String nonce;
        final String qop;
        final String password;

        DigestParams(String realm, String nonce, String qop, String password) {
            this.realm = realm;
            this.nonce = nonce;
            this.qop = qop;
            this.password = password;
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void logInviteRejectDetail(Response response) {
        if (response == null) {
            return;
        }
        Object content = response.getContent();
        if (content != null) {
            String body = content instanceof byte[]
                    ? new String((byte[]) content, java.nio.charset.StandardCharsets.UTF_8)
                    : content.toString();
            String one = body.replace('\r', ' ').replace('\n', ' ').trim();
            if (!one.isEmpty()) {
                log.warn("GB28181 INVITE 拒绝正文: {}", one.length() > 400 ? one.substring(0, 400) + "…" : one);
            }
        }
        javax.sip.header.WarningHeader warn =
                (javax.sip.header.WarningHeader) response.getHeader(javax.sip.header.WarningHeader.NAME);
        if (warn != null) {
            log.warn("GB28181 INVITE Warning: {} {}", warn.getCode(), warn.getText());
        }
    }
}
