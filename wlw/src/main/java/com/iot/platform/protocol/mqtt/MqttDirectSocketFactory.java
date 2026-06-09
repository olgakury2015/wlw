package com.iot.platform.protocol.mqtt;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

/**
 * 使用 {@link Proxy#NO_PROXY} 建立 TCP，避免 IDEA/JVM 的 {@code -DsocksProxyHost} 等导致
 * Paho 连 {@code 127.0.0.1} 或公网 Broker 仍走 SOCKS 而被拒（MQTTX 等客户端常不受影响）。
 */
public final class MqttDirectSocketFactory extends SocketFactory {

    public static final SocketFactory INSTANCE = new MqttDirectSocketFactory();

    private MqttDirectSocketFactory() {
    }

    private static Socket newDirectSocket() {
        return new Socket(Proxy.NO_PROXY);
    }

    /**
     * 对 {@code tcp://}、{@code mqtt://}（尚未规范成 ssl）等使用直连；{@code ssl://}、{@code wss://}
     * 不设置，以免覆盖 TLS 所需的 {@link javax.net.ssl.SSLSocketFactory}。
     */
    public static void applyUnlessSsl(MqttConnectOptions opts, String serverUri) {
        if (opts == null || serverUri == null) {
            return;
        }
        String u = serverUri.trim().toLowerCase();
        if (u.startsWith("ssl://") || u.startsWith("wss://")) {
            return;
        }
        opts.setSocketFactory(INSTANCE);
    }

    @Override
    public Socket createSocket() throws IOException {
        return newDirectSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = newDirectSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = newDirectSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket s = newDirectSocket();
        if (localHost != null) {
            s.bind(new InetSocketAddress(localHost, localPort));
        } else if (localPort > 0) {
            s.bind(new InetSocketAddress(localPort));
        }
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        Socket s = newDirectSocket();
        if (localAddress != null) {
            s.bind(new InetSocketAddress(localAddress, localPort));
        } else if (localPort > 0) {
            s.bind(new InetSocketAddress(localPort));
        }
        s.connect(new InetSocketAddress(address, port));
        return s;
    }
}
