package com.iot.platform.video.gb28181;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class Gb28181SdpUtil {

    enum RecvPayload {
        PS("PS/90000"),
        MP2T("MP2T/90000");

        final String rtpmap;

        RecvPayload(String rtpmap) {
            this.rtpmap = rtpmap;
        }
    }

    private Gb28181SdpUtil() {
    }

    /** 本机收流 SDP（RTP 解 PS/TS）。勿写 y= 过滤 SSRC。 */
    static Path writeRecvSdp(
            int mediaPort,
            String mediaHost,
            RecvPayload payload,
            boolean bindAllIfaces,
            String rtpSenderIp,
            Gb28181MediaTransport transport)
            throws IOException {
        Path sdp = Files.createTempFile("wlw-gb28181-", ".sdp");
        String bindIp = bindAllIfaces ? "0.0.0.0" : effectiveBindIp(mediaHost);
        StringBuilder body = new StringBuilder();
        body.append("v=0\r\n");
        body.append("o=- 0 0 IN IP4 ").append(bindIp).append("\r\n");
        body.append("s=GB28181\r\n");
        body.append("c=IN IP4 ").append(bindIp).append("\r\n");
        body.append("t=0 0\r\n");
        appendVideoMedia(body, mediaPort, payload, transport);
        Files.write(sdp, body.toString().getBytes(StandardCharsets.UTF_8));
        return sdp;
    }

    private static String effectiveBindIp(String mediaHost) {
        if (mediaHost != null && Gb28181NetUtil.isIpv4(mediaHost.trim())) {
            return mediaHost.trim();
        }
        return "0.0.0.0";
    }

    static String pathForFfmpeg(Path sdp) {
        return sdp.toAbsolutePath().toString().replace('\\', '/');
    }

    static String buildInviteSdp(
            String platformId,
            String mediaIp,
            int mediaPort,
            String channelId,
            int streamIndex,
            Gb28181MediaTransport transport,
            String ssrcY) {
        String ch = channelId != null ? channelId : platformId;
        int idx = streamIndex >= 0 ? streamIndex : 0;
        StringBuilder body = new StringBuilder();
        body.append("v=0\r\n");
        body.append("o=").append(platformId).append(" 0 0 IN IP4 ").append(mediaIp).append("\r\n");
        body.append("s=Play\r\n");
        body.append("u=").append(ch).append(":").append(idx).append("\r\n");
        body.append("c=IN IP4 ").append(mediaIp).append("\r\n");
        body.append("t=0 0\r\n");
        appendVideoMedia(body, mediaPort, RecvPayload.PS, transport);
        appendSsrcY(body, ssrcY);
        body.append("a=filesize:0\r\n");
        return body.toString();
    }

    /** ACK 收流 SDP（与 INVITE 媒体参数一致，c= 用平台 media-host）。 */
    static String buildAckRecvSdp(String mediaIp, int mediaPort, Gb28181MediaTransport transport, String ssrcY) {
        StringBuilder body = new StringBuilder();
        body.append("v=0\r\n");
        body.append("o=- 0 0 IN IP4 ").append(mediaIp).append("\r\n");
        body.append("s=Play\r\n");
        body.append("c=IN IP4 ").append(mediaIp).append("\r\n");
        body.append("t=0 0\r\n");
        appendVideoMedia(body, mediaPort, RecvPayload.PS, transport);
        appendSsrcY(body, ssrcY);
        return body.toString();
    }

    private static void appendSsrcY(StringBuilder body, String ssrcY) {
        if (ssrcY != null && !ssrcY.trim().isEmpty()) {
            body.append("y=").append(ssrcY.trim()).append("\r\n");
        }
    }

    /** 摄像机 200 OK 为 setup:active 时，ACK/收流 SDP：平台主动连摄像机媒体端口。 */
    static String buildTcpActiveRecvSdp(String cameraIp, int cameraPort) {
        String ip = Gb28181NetUtil.isIpv4(cameraIp) ? cameraIp.trim() : "0.0.0.0";
        int port = cameraPort > 0 ? cameraPort : 15060;
        StringBuilder body = new StringBuilder();
        body.append("v=0\r\n");
        body.append("o=- 0 0 IN IP4 ").append(ip).append("\r\n");
        body.append("s=Play\r\n");
        body.append("c=IN IP4 ").append(ip).append("\r\n");
        body.append("t=0 0\r\n");
        appendVideoMedia(body, port, RecvPayload.PS, Gb28181MediaTransport.TCP_ACTIVE);
        return body.toString();
    }

    static Path writeTcpActiveRecvSdp(String cameraIp, int cameraPort) throws IOException {
        Path sdp = Files.createTempFile("wlw-gb28181-active-", ".sdp");
        Files.write(sdp, buildTcpActiveRecvSdp(cameraIp, cameraPort).getBytes(StandardCharsets.UTF_8));
        return sdp;
    }

    private static void appendVideoMedia(
            StringBuilder body, int port, RecvPayload payload, Gb28181MediaTransport transport) {
        Gb28181MediaTransport mode = transport != null ? transport : Gb28181MediaTransport.TCP_PASSIVE;
        String proto = mode.isTcp() ? "TCP/RTP/AVP" : "RTP/AVP";
        body.append("m=video ").append(port).append(" ").append(proto).append(" 96\r\n");
        body.append("a=rtpmap:96 ").append(payload.rtpmap).append("\r\n");
        if (payload == RecvPayload.PS) {
            body.append("a=fmtp:96 streamtype=5\r\n");
        }
        if (mode.isTcpPassive()) {
            body.append("a=setup:passive\r\n");
            body.append("a=connection:new\r\n");
        } else if (mode.isTcpActive()) {
            body.append("a=setup:active\r\n");
            body.append("a=connection:new\r\n");
        }
        body.append("a=recvonly\r\n");
    }
}
