package com.iot.platform.video.gb28181;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析摄像机 INVITE 200 应答 SDP（海康 y= SSRC 等）。 */
public final class Gb28181AnswerSdp {

    private static final Pattern Y_SSRC = Pattern.compile("(?m)^y=(\\d{1,10})\\s*$");
    private static final Pattern M_VIDEO = Pattern.compile("(?m)^m=video\\s+(\\d+)(?:\\s+(\\S+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern C_CONN = Pattern.compile("(?m)^c=IN IP4\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern SETUP = Pattern.compile("(?m)^a=setup:(\\w+)", Pattern.CASE_INSENSITIVE);

    private final String ssrcY;
    private final int mediaPort;
    private final String mediaIp;
    private final boolean tcpMedia;
    private final boolean tcpSetupActive;

    private Gb28181AnswerSdp(String ssrcY, int mediaPort, String mediaIp, boolean tcpMedia, boolean tcpSetupActive) {
        this.ssrcY = ssrcY;
        this.mediaPort = mediaPort;
        this.mediaIp = mediaIp;
        this.tcpMedia = tcpMedia;
        this.tcpSetupActive = tcpSetupActive;
    }

    public static Gb28181AnswerSdp parse(String sdp) {
        if (!StringUtils.hasText(sdp)) {
            return new Gb28181AnswerSdp(null, -1, null, false, false);
        }
        String ssrc = null;
        Matcher ym = Y_SSRC.matcher(sdp);
        if (ym.find()) {
            ssrc = ym.group(1);
        }
        int mPort = -1;
        boolean tcp = false;
        Matcher mm = M_VIDEO.matcher(sdp);
        if (mm.find()) {
            try {
                mPort = Integer.parseInt(mm.group(1));
            } catch (NumberFormatException ignored) {
                mPort = -1;
            }
            if (mm.groupCount() >= 2 && mm.group(2) != null) {
                tcp = mm.group(2).toUpperCase().contains("TCP");
            }
        }
        String mediaIp = null;
        Matcher cm = C_CONN.matcher(sdp);
        if (cm.find()) {
            mediaIp = cm.group(1);
        }
        boolean setupActive = false;
        Matcher sm = SETUP.matcher(sdp);
        if (sm.find()) {
            setupActive = "active".equalsIgnoreCase(sm.group(1));
        }
        return new Gb28181AnswerSdp(ssrc, mPort, mediaIp, tcp, setupActive);
    }

    /**
     * 按摄像机 200 OK SDP 协商收流方式。
     * 海康常见：平台 INVITE 为 setup:passive，200 应答 setup:active 表示由摄像机主动连平台端口（非平台连 m= 端口）。
     */
    public Gb28181MediaTransport negotiatedTransport() {
        if (tcpMedia) {
            if (tcpSetupActive) {
                return Gb28181MediaTransport.TCP_PASSIVE;
            }
            return Gb28181MediaTransport.TCP_ACTIVE;
        }
        return Gb28181MediaTransport.UDP;
    }

    public String getMediaIp() {
        return mediaIp;
    }

    public boolean isTcpSetupActive() {
        return tcpSetupActive;
    }

    public String getSsrcY() {
        return ssrcY;
    }

    /** 海康有时返回 y=2147483647 表示不固定 SSRC，勿写入收流 SDP。 */
    public String normalizedSsrcY() {
        if (ssrcY == null || ssrcY.trim().isEmpty()) {
            return null;
        }
        String y = ssrcY.trim();
        if ("2147483647".equals(y)) {
            return null;
        }
        return y;
    }

    /** 摄像机 SDP 中 m= 端口（多为发送源端口，非平台收流端口）。 */
    public int getMediaPort() {
        return mediaPort;
    }
}
