package com.iot.platform.video.gb28181;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;

/**
 * INVITE 等待期间预占媒体端口；200 后释放，由 FFmpeg 绑定收流（UDP 或 TCP 被动）。
 */
final class Gb28181MediaPortGuard implements AutoCloseable {

    private final DatagramSocket udpSocket;
    private ServerSocket tcpSocket;
    private final int port;

    private Gb28181MediaPortGuard(DatagramSocket udpSocket, ServerSocket tcpSocket, int port) {
        this.udpSocket = udpSocket;
        this.tcpSocket = tcpSocket;
        this.port = port;
    }

    static Gb28181MediaPortGuard bind(int port, Gb28181MediaTransport transport) throws IOException {
        if (transport != null && transport.isTcpPassive()) {
            ServerSocket ss = new ServerSocket();
            try {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", port));
                return new Gb28181MediaPortGuard(null, ss, port);
            } catch (IOException e) {
                closeQuietly(ss);
                throw new IOException("无法预占 TCP 媒体端口 " + port + ": " + e.getMessage(), e);
            }
        }
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("0.0.0.0", port));
            return new Gb28181MediaPortGuard(s, null, port);
        } catch (SocketException e) {
            s.close();
            throw new IOException("无法预占 UDP 媒体端口 " + port + ": " + e.getMessage(), e);
        }
    }

    int getPort() {
        return port;
    }

    boolean isTcpPassive() {
        return tcpSocket != null;
    }

    /** 等待摄像机 TCP 连入（海康 setup:active 应答后）。 */
    java.net.Socket acceptTcpClient(int timeoutMs) throws IOException {
        if (tcpSocket == null) {
            return null;
        }
        int prev = tcpSocket.getSoTimeout();
        try {
            tcpSocket.setSoTimeout(Math.max(1000, timeoutMs));
            return tcpSocket.accept();
        } finally {
            tcpSocket.setSoTimeout(prev);
        }
    }

    /** 已 accept 后释放 TCP 监听，便于同端口启动 FFmpeg UDP。 */
    void closeTcpListenOnly() {
        if (tcpSocket != null && !tcpSocket.isClosed()) {
            try {
                tcpSocket.close();
            } catch (IOException ignored) {
            }
            tcpSocket = null;
        }
    }

    @Override
    public void close() {
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
        }
        if (tcpSocket != null && !tcpSocket.isClosed()) {
            try {
                tcpSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket ss) {
        if (ss != null && !ss.isClosed()) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }
}
