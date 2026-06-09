package com.iot.platform.video.gb28181;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

/**
 * INVITE 等待期间预占 RTP UDP 端口；200 后须先释放、由 FFmpeg 绑定，再发 ACK 收流（见 Gb28181PlayService）。
 */
final class Gb28181RtpPortGuard implements AutoCloseable {

    private final DatagramSocket socket;
    private final int port;

    private Gb28181RtpPortGuard(DatagramSocket socket, int port) {
        this.socket = socket;
        this.port = port;
    }

    static Gb28181RtpPortGuard bind(int port) throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("0.0.0.0", port));
            return new Gb28181RtpPortGuard(s, port);
        } catch (SocketException e) {
            s.close();
            throw new IOException("无法预占 RTP 端口 " + port + ": " + e.getMessage(), e);
        }
    }

    int getPort() {
        return port;
    }

    @Override
    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
