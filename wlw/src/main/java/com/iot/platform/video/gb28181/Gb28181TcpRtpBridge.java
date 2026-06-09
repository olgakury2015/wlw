package com.iot.platform.video.gb28181;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 国标 TCP 收流：解 RTP 后转发到本机 UDP，供 FFmpeg SDP 解码。
 * 支持 RFC4571（{@code $} + channel + len）与海康常用的 2 字节大端长度前缀 RTP。
 */
public final class Gb28181TcpRtpBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Gb28181TcpRtpBridge.class);

    private final Socket socket;
    private final InputStream mediaIn;
    private final DatagramSocket udpForward;
    private final InetSocketAddress forwardTarget;
    private Thread reader;
    private volatile boolean running = true;
    private final AtomicLong rtpPackets = new AtomicLong();
    private final AtomicLong rtpBytes = new AtomicLong();
    private final AtomicBoolean loggedFirstRtp = new AtomicBoolean();

    private Gb28181TcpRtpBridge(
            Socket socket, InputStream mediaIn, DatagramSocket udpForward, InetSocketAddress forwardTarget, Thread reader) {
        this.socket = socket;
        this.mediaIn = mediaIn;
        this.udpForward = udpForward;
        this.forwardTarget = forwardTarget;
        this.reader = reader;
    }

    public static Gb28181TcpRtpBridge fromAcceptedSocket(Socket accepted, int localUdpPort) throws IOException {
        accepted.setTcpNoDelay(true);
        return fromMediaInput(accepted, accepted.getInputStream(), localUdpPort);
    }

    /** 与外部共用的媒体输入流（例如已 peek 首字节的 PushbackInputStream）。 */
    public static Gb28181TcpRtpBridge fromMediaInput(Socket accepted, InputStream mediaIn, int localUdpPort)
            throws IOException {
        DatagramSocket udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        try {
            udp.setSendBufferSize(4 * 1024 * 1024);
        } catch (Exception ignored) {
        }
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", localUdpPort);
        Gb28181TcpRtpBridge bridge = new Gb28181TcpRtpBridge(accepted, mediaIn, udp, target, null);
        Thread reader = new Thread(bridge::pumpAutoDetect, "gb28181-tcp-rtp-accepted");
        reader.setDaemon(true);
        bridge.reader = reader;
        reader.start();
        log.info("GB28181 TCP被动已接受摄像机连接 → 本机 UDP {}（RTP 桥接）", localUdpPort);
        return bridge;
    }

    public static Gb28181TcpRtpBridge start(String cameraIp, int cameraPort, int localUdpPort, int connectTimeoutMs)
            throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(cameraIp, cameraPort), Math.max(2000, connectTimeoutMs));
        DatagramSocket udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        InetSocketAddress target = new InetSocketAddress("127.0.0.1", localUdpPort);
        Gb28181TcpRtpBridge bridge = new Gb28181TcpRtpBridge(socket, socket.getInputStream(), udp, target, null);
        Thread reader = new Thread(
                bridge::pumpAutoDetect,
                "gb28181-tcp-rtp-" + cameraIp + "-" + cameraPort);
        reader.setDaemon(true);
        bridge.reader = reader;
        reader.start();
        log.info("GB28181 TCP主动已连接 {}:{} → 本机 UDP {}（RTP 桥接）", cameraIp, cameraPort, localUdpPort);
        return bridge;
    }

    public long rtpPackets() {
        return rtpPackets.get();
    }

    public long rtpBytes() {
        return rtpBytes.get();
    }

    private void pumpAutoDetect() {
        InputStream raw = null;
        BufferedInputStream in = null;
        try {
            raw = mediaIn != null ? mediaIn : socket.getInputStream();
            in = raw instanceof BufferedInputStream
                    ? (BufferedInputStream) raw
                    : new BufferedInputStream(raw, 128 * 1024);
            in.mark(1);
            int first = in.read();
            if (first < 0) {
                return;
            }
            if (first == '$') {
                log.info("GB28181 TCP 桥接模式=RFC4571 interleaved");
                pumpInterleaved(in, false);
            } else {
                log.info("GB28181 TCP 桥接模式=2字节长度前缀 RTP（首字节=0x{}）",
                        Integer.toHexString(first & 0xFF));
                pumpLengthPrefixed(in, first);
            }
        } catch (IOException e) {
            if (socket != null && !socket.isClosed()) {
                log.debug("GB28181 TCP RTP 桥接结束: {}", e.toString());
            }
        } finally {
            if (in != null && raw != mediaIn && in != raw) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void pumpInterleaved(BufferedInputStream in, boolean expectDollar) throws IOException {
        byte[] lengthBuf = new byte[2];
        byte[] packetBuf = new byte[65535];
        while (running && !socket.isClosed()) {
            if (expectDollar) {
                int dollar = in.read();
                if (dollar < 0) {
                    break;
                }
                if (dollar != '$') {
                    continue;
                }
            }
            expectDollar = true;
            int channel = in.read();
            if (channel < 0) {
                break;
            }
            if (readFully(in, lengthBuf, 0, 2) < 2) {
                break;
            }
            int len = ((lengthBuf[0] & 0xFF) << 8) | (lengthBuf[1] & 0xFF);
            if (len < 12 || len > packetBuf.length) {
                continue;
            }
            if (readFully(in, packetBuf, 0, len) < len) {
                break;
            }
            forwardRtp(packetBuf, len);
        }
    }

    private void pumpLengthPrefixed(BufferedInputStream in, int pendingHigh) throws IOException {
        byte[] packetBuf = new byte[65535];
        int high = pendingHigh;
        boolean haveHigh = true;
        while (running && !socket.isClosed()) {
            int low;
            if (haveHigh) {
                low = in.read();
                haveHigh = false;
            } else {
                high = in.read();
                if (high < 0) {
                    break;
                }
                low = in.read();
            }
            if (low < 0) {
                break;
            }
            int len = ((high & 0xFF) << 8) | (low & 0xFF);
            if (len < 12 || len > packetBuf.length) {
                haveHigh = true;
                high = low;
                continue;
            }
            if (readFully(in, packetBuf, 0, len) < len) {
                break;
            }
            if ((packetBuf[0] & 0xC0) != 0x80) {
                continue;
            }
            forwardRtp(packetBuf, len);
        }
    }

    private void forwardRtp(byte[] packetBuf, int len) throws IOException {
        DatagramPacket dp = new DatagramPacket(packetBuf, 0, len);
        dp.setSocketAddress(forwardTarget);
        udpForward.send(dp);
        long n = rtpPackets.incrementAndGet();
        rtpBytes.addAndGet(len);
        if (n == 1 && loggedFirstRtp.compareAndSet(false, true)) {
            int pt = packetBuf.length > 1 ? (packetBuf[1] & 0x7F) : -1;
            log.info("GB28181 TCP 桥接首包 RTP len={} PT={} hex={}",
                    len, pt, hex4(packetBuf));
        }
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) {
                return total;
            }
            total += n;
        }
        return total;
    }

    private static String hex4(byte[] p) {
        if (p == null || p.length == 0) {
            return "";
        }
        int n = Math.min(4, p.length);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X", p[i] & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        if (udpForward != null && !udpForward.isClosed()) {
            udpForward.close();
        }
        if (reader != null) {
            reader.interrupt();
        }
    }
}
