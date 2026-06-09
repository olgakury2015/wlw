# -*- coding: utf-8 -*-
"""
ESP / MicroPython：模拟四合一气体数据，TCP 一行一 JSON 推到 IoT 平台（iot.tcp.port，默认 9099，须与 application.yml 一致）。
使用前改 SERVER_IP / SERVER_PORT / DEVICE_ID；设备编号须与控制台注册一致。

重要：若诊断里 ESP 的 ip 仍是 0.0.0.0，说明板子还没连上 WiFi，此时 TCP 必报 118，与 SERVER_IP、device_id 无关。
要么 USE_WIFI=True，要么修好 robot_boot；若已填真实 WIFI_SSID（非 your-ssid），本脚本会在无 IP 时自动尝试连接。
"""

import time

try:
    import ujson as json
except ImportError:
    import json

try:
    import random
except ImportError:
    random = None

# ============ 按你的环境修改 ============
# 必须是「ESP 能路由到的」地址：填运行 Java 平台的那台电脑的 WiFi IPv4（cmd 里 ipconfig）。
# 不要用 127.0.0.1（在 ESP 上表示板子自己）。若出现 [Errno 118] EHOSTUNREACH，多半是 IP/网段错误或访客网络隔离。
SERVER_IP = "192.168.0.10"  # 改成跑平台的电脑 ipconfig 里的 IPv4；例如 192.168.0.124 表示该局域网地址
SERVER_PORT = 9099
DEVICE_ID = "GAS_SENSOR_001"  # 与平台「设备编号」一致
SEND_INTERVAL_S = 10

# MicroPython 联网：True=启动时必定用下面账号连接；False=若 STA 无 IP 且已填真实 SSID 也会自动连一次
USE_WIFI = False
WIFI_SSID = "your-ssid"
WIFI_PASSWORD = "your-password"
# ========================================


def _wifi_credentials_configured():
    return bool(WIFI_SSID and WIFI_SSID != "your-ssid" and WIFI_PASSWORD)


def _errno_of(exc):
    if isinstance(exc, OSError) and len(exc.args) > 0:
        a0 = exc.args[0]
        if isinstance(a0, int):
            return a0
    return None


def _ip_prefix(ipv4):
    parts = str(ipv4).split(".")
    if len(parts) >= 3:
        return "%s.%s.%s" % (parts[0], parts[1], parts[2])
    return ""


def print_net_diag():
    """启动时打印本机 IP，便于和 SERVER_IP 对照（需已连 WiFi）。"""
    try:
        import network

        w = network.WLAN(network.STA_IF)
        if not w.active():
            print("[诊断] WiFi STA 未开启。若由 robot_boot 联网，请确认其已执行且成功。")
            return
        ip, mask, gw, dns = w.ifconfig()
        print("[诊断] ESP ifconfig: ip=%s mask=%s gw=%s dns=%s" % (ip, mask, gw, dns))
        if ip == "0.0.0.0":
            print("[诊断] 未获取到 IP：STA 未连上路由器（与 SERVER_IP、设备编号无关）。请先让 ESP 拿到局域网 IP。")
            return
        sp = _ip_prefix(ip)
        tp = _ip_prefix(SERVER_IP)
        if sp and tp and sp != tp:
            print("[诊断] 与 SERVER_IP 前三段不同: ESP %s vs 目标 %s（可能不在同一局域网）" % (sp, tp))
        else:
            print("[诊断] 与 SERVER_IP 同网段前三段，若仍 118 请查电脑防火墙或电脑 IP 是否已变。")
    except Exception as ex:
        print("[诊断] 无法读取网络:", ex)


def explain_connect_error(exc):
    n = _errno_of(exc)
    # 118/113 等在不同 lwIP 上常表示「到不了主机」
    if n in (118, 113):
        print(
            "[说明] TCP 连不上(常见 errno 118)。若上面 ESP 的 ip 是 0.0.0.0，请先连 WiFi，不要改 SERVER_IP。\n"
            "  · ESP 已拿到 IP 后：把 SERVER_IP 设成电脑 ipconfig 的 IPv4（你的是 192.168.0.124 即可）。\n"
            "  · 确认 Java 已启动、iot.tcp.port 与 SERVER_PORT 一致、防火墙放行 TCP %s。\n"
            "  · 访客 WiFi / AP 隔离会导致设备互访失败。" % SERVER_PORT
        )
    elif n == 104:
        print("[说明] ECONNRESET: 对端端口无进程监听或中途断开。")


def _rand_float(a, b):
    if random is None:
        return a
    if hasattr(random, "uniform"):
        return round(random.uniform(a, b), 1)
    # 无 uniform 时用整数近似
    span = int((b - a) * 10) + 1
    return round(a + (random.getrandbits(8) % span) / 10.0, 1)


def _rand_int(a, b):
    if random is None:
        return a
    return random.randint(a, b)


def _status(val, low, high):
    if val >= high:
        return "高报"
    if val >= low:
        return "低报"
    return "正常"


def build_payload():
    ch4 = _rand_float(0.0, 8.0)
    o2 = _rand_float(19.5, 21.5)
    co = float(_rand_int(0, 30))
    h2s = float(_rand_int(0, 8))
    if random and random.random() < 0.12:
        ch4 = _rand_float(6.0, 12.0)
    if random and random.random() < 0.06:
        co = float(_rand_int(28, 55))
    ts = int(time.time())
    t = _rand_float(20.0, 35.0)
    h = _rand_float(40.0, 80.0)
    return {
        "device_id": DEVICE_ID,
        "timestamp": ts,
        "data": {
            "CH4": {
                "value": ch4,
                "unit": "LEL%",
                "status": _status(ch4, 5.0, 10.0),
                "temperature": t,
                "humidity": h,
            },
            "O2": {
                "value": o2,
                "unit": "VOL%",
                "status": _status(o2, 19.5, 23.0),
                "temperature": t,
                "humidity": h,
            },
            "CO": {
                "value": co,
                "unit": "ppm",
                "status": _status(co, 24.0, 50.0),
                "temperature": t,
                "humidity": h,
            },
            "H2S": {
                "value": h2s,
                "unit": "ppm",
                "status": _status(h2s, 10.0, 20.0),
                "temperature": t,
                "humidity": h,
            },
        },
    }


def _wlan_status_hint(st):
    # ESP32 MicroPython 常见值：1 连接中，3 已连上；202/201 等见固件版本
    m = {
        0: "IDLE",
        1: "CONNECTING",
        2: "WRONG_PASSWORD(部分固件)",
        3: "OK_GOT_IP",
        202: "NO_AP_FOUND",
        201: "WRONG_PASSWORD",
        204: "HANDSHAKE_TIMEOUT",
    }
    return m.get(st, "见文档 network.WLAN.status")


def connect_wifi_mp():
    try:
        import network
    except ImportError:
        print("当前环境无 network 模块，跳过 WiFi")
        return
    wlan = network.WLAN(network.STA_IF)
    wlan.active(True)
    if wlan.isconnected():
        print("WiFi 已连接 IP:", wlan.ifconfig()[0])
        return
    print("[WiFi] 连接 SSID:", WIFI_SSID)
    wlan.connect(WIFI_SSID, WIFI_PASSWORD)
    for _ in range(80):
        st = wlan.status()
        if st < 0 or st >= 3:
            break
        time.sleep(0.25)
    st = wlan.status()
    if st != 3:
        raise RuntimeError(
            "WiFi 失败 status=%s (%s)。检查 SSID/密码、2.4G、路由器是否拒绝陌生 MAC。"
            % (st, _wlan_status_hint(st))
        )
    print("WiFi OK IP:", wlan.ifconfig()[0])


def ensure_sta_has_ip():
    """无 IP 时：USE_WIFI 或已配置真实 SSID 则发起连接；否则只打印提示。"""
    try:
        import network
    except ImportError:
        return
    w = network.WLAN(network.STA_IF)
    if not w.active():
        w.active(True)
        time.sleep(0.1)
    ip = w.ifconfig()[0]
    if ip != "0.0.0.0":
        return
    if USE_WIFI:
        connect_wifi_mp()
        return
    if _wifi_credentials_configured():
        print("[提示] STA 无 IP，robot_boot 可能未连上；已用本文件 WIFI_SSID 自动连接…")
        connect_wifi_mp()
        return
    print(
        "[提示] STA 无 IP(0.0.0.0)。请任选其一：\n"
        "  1) 本文件设 USE_WIFI = True 并填写 WIFI_SSID / WIFI_PASSWORD\n"
        "  2) 或修好 robot_boot.py 让板子上电即连同一 WiFi\n"
        "  在此之前改 device_id、SERVER_IP 都无法解决 118。"
    )


def send_once():
    payload = build_payload()
    line = json.dumps(payload) + "\n"
    data = line.encode("utf-8") if isinstance(line, str) else line

    import socket

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.settimeout(8)
        print("[连接] -> %s:%s" % (SERVER_IP, SERVER_PORT))
        sock.connect((SERVER_IP, SERVER_PORT))
        sock.send(data)
        try:
            ack = sock.recv(512)
            if ack:
                print("应答:", ack.decode("utf-8").strip())
        except OSError:
            print("无应答或超时（平台仍可能已入库）")
    finally:
        sock.close()

    print("已发送:", line.strip()[:200] + ("..." if len(line) > 200 else ""))


def main():
    ensure_sta_has_ip()
    print_net_diag()
    try:
        import network

        if network.WLAN(network.STA_IF).ifconfig()[0] == "0.0.0.0":
            print("[退出] 无局域网 IP，已停止发 TCP。请改好 WiFi 后软重启再运行。")
            return
    except Exception:
        pass
    n = 0
    while True:
        try:
            n += 1
            print("--- #%s ---" % n)
            send_once()
        except Exception as e:
            print("错误:", e)
            explain_connect_error(e)
        time.sleep(SEND_INTERVAL_S)


if __name__ == "__main__":
    main()
