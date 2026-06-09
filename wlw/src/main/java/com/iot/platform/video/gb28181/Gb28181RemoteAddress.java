package com.iot.platform.video.gb28181;

import gov.nist.javax.sip.message.SIPRequest;
import lombok.Value;
import org.springframework.util.StringUtils;

import javax.sip.header.ViaHeader;

/**
 * 从 SIP 请求解析设备真实地址（参考 wvp {@code SipUtils.getRemoteAddressFromRequest}）。
 */
public final class Gb28181RemoteAddress {

    private Gb28181RemoteAddress() {
    }

    @Value
    public static class Info {
        String ip;
        int port;
    }

    /** 优先使用 UDP/TCP 报文源地址（NAT/海康 Contact 为域时更可靠）。 */
    public static Info fromRequest(SIPRequest request) {
        String ip = null;
        int port = 5060;
        if (request.getPeerPacketSourceAddress() != null) {
            ip = request.getPeerPacketSourceAddress().getHostAddress();
            if (request.getPeerPacketSourcePort() > 0) {
                port = request.getPeerPacketSourcePort();
            }
        }
        ViaHeader via = request.getTopmostViaHeader();
        if (via != null) {
            if (!StringUtils.hasText(ip) && StringUtils.hasText(via.getReceived())) {
                ip = via.getReceived();
            }
            if (via.getRPort() > 0) {
                port = via.getRPort();
            }
        }
        if (!StringUtils.hasText(ip) && request.getRemoteAddress() != null) {
            ip = request.getRemoteAddress().getHostAddress();
            if (request.getRemotePort() > 0) {
                port = request.getRemotePort();
            }
        }
        return new Info(ip != null ? ip : "", port);
    }

    public static String transportFromRequest(SIPRequest request) {
        ViaHeader via = request.getTopmostViaHeader();
        if (via == null || via.getTransport() == null) {
            return "UDP";
        }
        return "TCP".equalsIgnoreCase(via.getTransport()) ? "TCP" : "UDP";
    }
}
