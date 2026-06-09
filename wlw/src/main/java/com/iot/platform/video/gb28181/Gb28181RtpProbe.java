package com.iot.platform.video.gb28181;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/** 解码失败后的短时诊断：最多收 2 个 UDP 包，避免再次排空 RTP 缓冲。 */
final class Gb28181RtpProbe {

    private static final Logger log = LoggerFactory.getLogger(Gb28181RtpProbe.class);
    private static final int SMOKE_MAX_PACKETS = 2;

    private Gb28181RtpProbe() {
    }

    /**
     * 解码失败时确认端口是否仍有 RTP（最多 {@value #SMOKE_MAX_PACKETS} 包，避免与 FFmpeg 争用）。
     */
    static ProbeResult smoke(int port, String expectedSender, int timeoutMs) {
        DatagramSocket socket = null;
        int total = 0;
        int matched = 0;
        long bytes = 0;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(Math.max(50, timeoutMs));
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            byte[] buf = new byte[65535];
            long deadline = System.currentTimeMillis() + Math.max(80, timeoutMs);
            while (total < SMOKE_MAX_PACKETS && System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
                    total++;
                    bytes += pkt.getLength();
                    String from = pkt.getAddress() != null ? pkt.getAddress().getHostAddress() : "";
                    if (expectedSender == null || expectedSender.equals(from)) {
                        matched++;
                    }
                } catch (SocketTimeoutException ignored) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("GB28181 RTP 诊断失败 port={}: {}", port, e.toString());
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        return new ProbeResult(total, matched, bytes);
    }

    static final class ProbeResult {
        final int totalPackets;
        final int matchedPackets;
        final long totalBytes;

        ProbeResult(int totalPackets, int matchedPackets, long totalBytes) {
            this.totalPackets = totalPackets;
            this.matchedPackets = matchedPackets;
            this.totalBytes = totalBytes;
        }

        boolean anyReceived() {
            return totalPackets > 0;
        }
    }
}
