package com.iot.platform.video.gb28181;

import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

public final class Gb28181NetUtil {

    private static final Pattern IPV4 = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    private Gb28181NetUtil() {
    }

    public static boolean isIpv4(String value) {
        return StringUtils.hasText(value) && IPV4.matcher(value.trim()).matches();
    }

    public static String requireIpv4(String fieldLabel, String value) {
        if (!isIpv4(value)) {
            String shown = value != null && !value.trim().isEmpty() ? value.trim() : "(空)";
            throw new IllegalArgumentException(
                    fieldLabel + "须填写本机 IPv4 地址（例如 192.168.0.123），勿填用户名、admin 或域名；当前值无效: " + shown);
        }
        return value.trim();
    }

    /** 本机可用 IPv4（不含 127.0.0.1），用于诊断 media-host 是否填对网卡。 */
    public static List<String> listLocalIpv4Addresses() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        Collections.sort(ips);
        return ips;
    }

    public static boolean isLocalIpv4(String ip) {
        if (!isIpv4(ip)) {
            return false;
        }
        return listLocalIpv4Addresses().contains(ip.trim());
    }

    /** 从 RTSP URL 提取摄像机 IPv4（用于国标 Contact 兜底）。 */
    public static String extractIpv4FromRtsp(String rtspUrl) {
        if (!StringUtils.hasText(rtspUrl)) {
            return null;
        }
        String s = rtspUrl.trim();
        try {
            int at = s.indexOf('@');
            if (at >= 0) {
                s = s.substring(at + 1);
            }
            int slash = s.indexOf('/');
            if (slash >= 0) {
                s = s.substring(0, slash);
            }
            int colon = s.lastIndexOf(':');
            String host = colon > 0 ? s.substring(0, colon) : s;
            return isIpv4(host) ? host.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
