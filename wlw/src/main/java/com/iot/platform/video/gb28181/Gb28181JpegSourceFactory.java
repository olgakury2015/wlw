package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Gb28181JpegSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(Gb28181JpegSourceFactory.class);

    private Gb28181JpegSourceFactory() {
    }

    public static Gb28181JpegSource start(
            int udpPort,
            IotProperties.Video cfg,
            String mediaHost,
            String remoteSsrcY,
            String rtpSenderIp,
            Gb28181MediaTransport transport)
            throws IOException {
        return start(udpPort, cfg, mediaHost, remoteSsrcY, rtpSenderIp, transport, false);
    }

    /** @param tcpBridged 摄像机 TCP 连入后由桥接转发到本机 UDP，须先起 FFmpeg 再等 TCP */
    public static Gb28181JpegSource start(
            int udpPort,
            IotProperties.Video cfg,
            String mediaHost,
            String remoteSsrcY,
            String rtpSenderIp,
            Gb28181MediaTransport transport,
            boolean tcpBridged)
            throws IOException {
        List<String> order = decoderOrder(cfg.getGb28181Decoder(), tcpBridged);
        IOException last = null;
        boolean portBusy = false;
        for (String mode : order) {
            if (portBusy) {
                break;
            }
            try {
                if ("javacv".equals(mode)) {
                    log.info("GB28181 尝试 JavaCV 解码 port={} from={}", udpPort, rtpSenderIp);
                    return Gb28181JavaCvJpegSource.start(udpPort, cfg, mediaHost, remoteSsrcY, rtpSenderIp, transport);
                }
                String transportMode = "udp";
                if (transport != null) {
                    if (transport.isTcpActive()) {
                        transportMode = "tcp_active";
                    } else if (transport.isTcpPassive()) {
                        transportMode = "tcp_passive";
                    }
                }
                log.info("GB28181 尝试 FFmpeg 子进程 port={} from={} transport={} exe={}",
                        udpPort, rtpSenderIp, transportMode, Gb28181FfmpegPaths.resolveExecutable(cfg));
                return Gb28181FfmpegUdpJpegSource.start(
                        udpPort, cfg, mediaHost, remoteSsrcY, rtpSenderIp, transport, tcpBridged);
            } catch (IOException e) {
                last = e;
                log.warn("GB28181 {} 启动失败: {}", mode, e.getMessage());
                if (isUdpPortBusy(e)) {
                    portBusy = true;
                    sleepQuiet(800L);
                }
            } catch (Exception e) {
                last = new IOException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
                log.warn("GB28181 {} 启动失败: {}", mode, last.getMessage());
                if (isUdpPortBusy(last)) {
                    portBusy = true;
                    sleepQuiet(800L);
                }
            }
        }
        throw last != null ? last : new IOException("GB28181 无可用解码方式");
    }

    private static boolean isUdpPortBusy(Throwable e) {
        if (e == null) {
            return false;
        }
        String m = e.getMessage();
        if (m != null && (m.contains("10048") || m.contains("bind failed") || m.contains("Address already in use"))) {
            return true;
        }
        return isUdpPortBusy(e.getCause());
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> decoderOrder(String configured, boolean tcpBridged) {
        String mode = configured != null ? configured.trim().toLowerCase(Locale.ROOT) : "auto";
        List<String> order = new ArrayList<>(2);
        if (tcpBridged) {
            // TCP→UDP 桥接时避免 JavaCV 与 FFmpeg 争用同一 UDP 端口（Windows 10048）
            order.add("ffmpeg");
            return order;
        }
        if ("javacv".equals(mode)) {
            order.add("javacv");
            order.add("ffmpeg");
        } else if ("ffmpeg".equals(mode)) {
            order.add("ffmpeg");
            order.add("javacv");
        } else {
            order.add("ffmpeg");
            order.add("javacv");
        }
        return order;
    }
}
