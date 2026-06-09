package com.iot.platform.video.service;

import com.iot.platform.config.IotProperties;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * JavaCV 拉取 RTSP：TCP 传输为主，参数从简，避免过多 option 导致 grab 持续 null。
 */
final class RtspFrameGrabberFactory {

    static {
        try {
            FFmpegLogCallback.set();
            org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR);
        } catch (Throwable ignored) {
        }
    }

    private RtspFrameGrabberFactory() {
    }

    static FFmpegFrameGrabber create(String rtspUrl, IotProperties.Video cfg) throws Exception {
        String url = normalizeRtspUrl(rtspUrl);
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url);
        grabber.setFormat("rtsp");

        String transport = cfg.getRtspTransport() != null ? cfg.getRtspTransport().trim() : "tcp";
        if (transport.isEmpty()) {
            transport = "tcp";
        }
        // 与海康示例一致：强制 TCP
        grabber.setOption("rtsp_transport", transport);
        grabber.setAudioChannels(0);

        int openMs = Math.max(3000, cfg.getOpenTimeoutMs());
        int readMs = cfg.getReadTimeoutMs() > 0 ? cfg.getReadTimeoutMs() : 15000;
        grabber.setOption("stimeout", String.valueOf(openMs * 1000L));
        grabber.setOption("rw_timeout", String.valueOf(readMs * 1000L));
        grabber.setOption("buffer_size", "2097152");
        grabber.setOption("max_delay", "5000000");
        grabber.setOption("reorder_queue_size", "1024");
        grabber.setOption("probesize", "5000000");
        grabber.setOption("analyzeduration", "5000000");

        return grabber;
    }

    static final class RtspEndpoint {
        final String scheme;
        final String hostPart;
        final String username;
        final String password;

        String getHostPart() {
            return hostPart;
        }

        String getUsername() {
            return username;
        }

        String getPassword() {
            return password;
        }

        RtspEndpoint(String scheme, String hostPart, String username, String password) {
            this.scheme = scheme;
            this.hostPart = hostPart;
            this.username = username;
            this.password = password;
        }

        String toUrlWithAuth() {
            try {
                String user = encodeComponent(username);
                String pass = encodeComponent(password != null ? password : "");
                return scheme + user + ":" + pass + "@" + hostPart;
            } catch (Exception e) {
                return scheme + username + ":" + (password != null ? password : "") + "@" + hostPart;
            }
        }
    }

    private static String encodeComponent(String part) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    static String normalizeRtspUrl(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        RtspEndpoint ep = parseRtspUrl(raw.trim());
        if (!StringUtils.hasText(ep.username)) {
            return raw.trim();
        }
        return ep.toUrlWithAuth();
    }

    static RtspEndpoint parseRtspUrl(String raw) {
        String s = raw != null ? raw.trim() : "";
        if (!s.regionMatches(true, 0, "rtsp://", 0, 7) && !s.regionMatches(true, 0, "rtsps://", 0, 8)) {
            return new RtspEndpoint("", s, null, null);
        }
        int schemeLen = s.regionMatches(true, 0, "rtsps://", 0, 8) ? 8 : 7;
        int at = s.indexOf('@', schemeLen);
        if (at < 0) {
            return new RtspEndpoint(s.substring(0, schemeLen), s.substring(schemeLen), null, null);
        }
        String auth = s.substring(schemeLen, at);
        String hostPart = s.substring(at + 1);
        String scheme = s.substring(0, schemeLen);
        int colon = auth.indexOf(':');
        String user = colon >= 0 ? auth.substring(0, colon) : auth;
        String pass = colon >= 0 ? auth.substring(colon + 1) : "";
        return new RtspEndpoint(scheme, hostPart, decodeComponent(user), decodeComponent(pass));
    }

    private static String decodeComponent(String part) {
        if (part == null || part.isEmpty()) {
            return part;
        }
        try {
            return URLDecoder.decode(part, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return part;
        }
    }
}
