package com.iot.platform.video.service;

import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海康 RTSP 地址 → HTTP MJPEG 预览（ISAPI httpPreview），在 RTSP 频繁 -10054 时作备选拉流。
 */
final class HikvisionStreamUrls {

    private static final Pattern CHANNEL = Pattern.compile("Channels/(\\d+)", Pattern.CASE_INSENSITIVE);

    private HikvisionStreamUrls() {
    }

    static Optional<String> httpPreviewFromRtsp(String rtspRaw) {
        if (!StringUtils.hasText(rtspRaw)) {
            return Optional.empty();
        }
        String raw = rtspRaw.trim();
        if (!raw.regionMatches(true, 0, "rtsp://", 0, 7)
                && !raw.regionMatches(true, 0, "rtsps://", 0, 8)) {
            return Optional.empty();
        }
        RtspFrameGrabberFactory.RtspEndpoint ep = RtspFrameGrabberFactory.parseRtspUrl(raw);
        Matcher m = CHANNEL.matcher(ep.getHostPart());
        if (!m.find()) {
            return Optional.empty();
        }
        String channelId = m.group(1);
        String hostPortPath = ep.getHostPart();
        int slash = hostPortPath.indexOf('/');
        String hostPort = slash >= 0 ? hostPortPath.substring(0, slash) : hostPortPath;
        if (hostPort.endsWith(":554")) {
            hostPort = hostPort.substring(0, hostPort.length() - 4);
        }
        String path = "/ISAPI/Streaming/channels/" + channelId + "/httpPreview";
        if (StringUtils.hasText(ep.getUsername())) {
            return Optional.of("http://" + ep.getUsername() + ":" + nullToEmpty(ep.getPassword()) + "@" + hostPort + path);
        }
        return Optional.of("http://" + hostPort + path);
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
