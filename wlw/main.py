"""四合一气体传感器：MicroPython + Modbus-RTU（与 qiti.py 逻辑一致，适配本板串口引脚）。
板上 2 号四针口丝印多为 5V、GND、TXD1、RXD1；例程将 TX/RX 接到 GPIO43/44。
接线：传感器 T(发)→板 RX；传感器 R(收)→板 TX（交叉）。"""

import utime
from machine import Pin, UART

# ========== 串口 ==========
# AUTO_UART_SCAN=True：启动时自动尝试多组「UART 外设 + TX/RX 引脚」，判断是否为引脚/外设不对。
# 若已确认一组可用，可改 False 并填写 UART_ID / UART_TX / UART_RX 以加快启动。
AUTO_UART_SCAN = True
UART_ID = 1
UART_TX = 43
UART_RX = 44
BAUDRATE = 9600
# 依次尝试：(说明, UART外设号, TX脚, RX脚)
UART_TRY_LIST = (
    ("UART1 TX43/RX44(本板官方例程)", 1, 43, 44),
    ("UART2 TX43/RX44(与 qiti.py 相同)", 2, 43, 44),
    ("UART1 TX17/RX18(其它走线/旧猜脚)", 1, 17, 18),
    ("UART2 TX17/RX18", 2, 17, 18),
    ("UART1 TX44/RX43(线反或丝印与走线对调试)", 1, 44, 43),
)
# 发完帧后再等一会；读 25 字节用轮询
POST_WRITE_MS = 200
READ_TOTAL_MS = 1200
RX_SNIFF_MS = 400

# 四合一：各气体 Modbus 从机地址（与 qiti.py 相同）
SENSOR_CONFIG = {
    0x01: {"name": "CH4", "unit": "LEL%", "decimal": 0},
    0x02: {"name": "O2", "unit": "VOL%", "decimal": 1},
    0x03: {"name": "CO", "unit": "ppm", "decimal": 0},
    0x04: {"name": "H2S", "unit": "ppm", "decimal": 0},
}


def crc16(data):
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x0001:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return bytes([crc & 0xFF, (crc >> 8) & 0xFF])


def _uart_open(uid, tx, rx):
    return UART(
        uid,
        baudrate=BAUDRATE,
        bits=8,
        parity=None,
        stop=1,
        tx=Pin(tx),
        rx=Pin(rx),
        timeout=READ_TOTAL_MS,
    )


def _uart_deinit(u):
    try:
        u.deinit()
    except (AttributeError, TypeError, ValueError):
        pass


def sniff_rx(uart, ms):
    """仅看 RX 上是否有字节（接线/波特率粗测）；有则打印 hex。"""
    t0 = utime.ticks_ms()
    got = b""
    while utime.ticks_diff(utime.ticks_ms(), t0) < ms:
        n = uart.any()
        if n:
            got += uart.read(n)
        utime.sleep_ms(10)
    if got:
        try:
            print("  [监听RX] 收到 %d 字节: %s" % (len(got), got.hex()))
        except AttributeError:
            print("  [监听RX] 收到 %d 字节" % len(got))


def read_exact(uart, n, total_ms):
    """在 total_ms 内尽量读满 n 字节（避免单次 uart.read(n) 不阻塞导致永远「无响应」）。"""
    buf = b""
    t0 = utime.ticks_ms()
    while len(buf) < n:
        m = uart.any()
        if m:
            chunk = uart.read(m if (len(buf) + m) <= n else (n - len(buf)))
            if chunk:
                buf += chunk
        if utime.ticks_diff(utime.ticks_ms(), t0) > total_ms:
            break
        utime.sleep_ms(2)
    return buf


def read_gas_data(uart, slave_addr, quiet=False):
    """读保持寄存器 0x0000 起共 10 个寄存器（与 qiti.py 一致）。"""
    frame = bytes([slave_addr, 0x03, 0x00, 0x00, 0x00, 0x0A])
    frame += crc16(frame)

    while uart.any():
        uart.read()
    uart.write(frame)
    utime.sleep_ms(POST_WRITE_MS)

    # 多读一些：常见情况是 RX 线上耦合到自己发出的 8 字节请求(回显)，真应答跟在后面
    raw = read_exact(uart, 40, READ_TOTAL_MS)
    response = raw
    if len(raw) >= 8 and raw[:8] == frame:
        if len(raw) == 8:
            if not quiet:
                print(
                    "地址%x: 仅收到本机发出的请求帧(回显 0103…)，不是传感器应答。"
                    " 说明 RX 与 TX 被短接、或线接错。请确认：传感器 T→板 RXD1、R→板 TXD1(交叉)；"
                    " 杜邦线不要把板的 TX 与 RX 连在一起。" % slave_addr
                )
            return None
        response = raw[8:]
        if len(response) < 25:
            utime.sleep_ms(120)
            response = response + read_exact(uart, 25 - len(response), 600)
    if len(response) > 25:
        response = response[:25]

    if response and len(response) == 25:
        if response[0] == slave_addr and response[1] == 0x03:
            received_crc = response[-2:]
            calc_crc = crc16(response[:-2])
            if received_crc == calc_crc:
                raw_concentration = (response[5] << 8) | response[6]
                low_alarm = (response[7] << 8) | response[8]
                high_alarm = (response[9] << 8) | response[10]
                range_val = (response[11] << 8) | response[12]
                status = (response[13] << 8) | response[14]
                temp_raw = (response[17] << 8) | response[18]
                humidity_raw = (response[21] << 8) | response[22]

                temperature = temp_raw / 10.0
                humidity = humidity_raw / 10.0

                config = SENSOR_CONFIG.get(slave_addr, {})
                decimal = config.get("decimal", 0)

                if decimal == 1:
                    concentration = raw_concentration / 10.0
                    low_alarm = low_alarm / 10.0
                    high_alarm = high_alarm / 10.0
                    range_val = range_val / 10.0
                elif decimal == 2:
                    concentration = raw_concentration / 100.0
                    low_alarm = low_alarm / 100.0
                    high_alarm = high_alarm / 100.0
                    range_val = range_val / 100.0
                else:
                    concentration = raw_concentration

                return {
                    "concentration": concentration,
                    "low_alarm": low_alarm,
                    "high_alarm": high_alarm,
                    "range": range_val,
                    "status": status,
                    "temperature": temperature,
                    "humidity": humidity,
                    "name": config.get("name", "Unknown"),
                    "unit": config.get("unit", ""),
                    "decimal": decimal,
                }
            if not quiet:
                print("地址%x: CRC校验失败" % slave_addr)
        else:
            if not quiet:
                print("地址%x: 响应格式错误" % slave_addr)
    elif response:
        try:
            hx = response.hex()
        except AttributeError:
            hx = str(len(response)) + "B"
        if not quiet:
            print("地址%x: 收到%d字节(期望25) %s" % (slave_addr, len(response), hx))
            if len(response) == 8:
                print(
                    "  若 hex 与刚发出的读命令一致，即为「回显」：查 TX/RX 是否短接或接反。"
                )
    else:
        if not quiet:
            print("地址%x: 无响应" % slave_addr)

    return None


def read_gas_data_with_retry(uart, slave_addr, retries=2, silent=False):
    for i in range(retries):
        last = i == retries - 1
        q = silent or (not last)
        data = read_gas_data(uart, slave_addr, quiet=q)
        if data:
            return data
        utime.sleep_ms(50)
    return None


def parse_status(status):
    status_map = {
        0x00: "预热",
        0x01: "正常",
        0x02: "数据错误",
        0x03: "传感器故障",
        0x04: "预警",
        0x05: "低报",
        0x06: "高报",
        0x07: "访问故障",
        0x08: "超量程",
        0x09: "需要标定",
        0x0A: "超时",
        0x0B: "STEL报警",
        0x0C: "TWA报警",
        0x0F: "通信故障",
    }
    return status_map.get(status, "未知(0x%02X)" % status)


def format_concentration(concentration, decimal, unit):
    if decimal == 1:
        return "%.1f %s" % (concentration, unit)
    if decimal == 2:
        return "%.2f %s" % (concentration, unit)
    return "%.0f %s" % (concentration, unit)


def pick_uart():
    """返回 (uart, label, uid, tx, rx)。自动扫描失败时退回手动 UART_ID/UART_TX/UART_RX。"""
    if not AUTO_UART_SCAN:
        u = _uart_open(UART_ID, UART_TX, UART_RX)
        return u, "手动", UART_ID, UART_TX, UART_RX

    print("正在自动尝试串口组合(测 CH4 地址1)…")
    for label, uid, tx, rx in UART_TRY_LIST:
        print("  试:", label)
        u = _uart_open(uid, tx, rx)
        utime.sleep_ms(40)
        while u.any():
            u.read()
        sniff_rx(u, RX_SNIFF_MS)
        if read_gas_data_with_retry(u, 0x01, retries=1, silent=True):
            print(">>> 已选用:", label)
            return u, label, uid, tx, rx
        _uart_deinit(u)
        utime.sleep_ms(30)

    print("自动扫描未收到 CH4 应答，改用手动 UART_ID/TX/RX 打开串口。")
    u = _uart_open(UART_ID, UART_TX, UART_RX)
    return u, "手动(扫描失败)", UART_ID, UART_TX, UART_RX


def main():
    uart, label, uid, tx, rx = pick_uart()
    print("=" * 55)
    print("四合一气体传感器读取程序 (main.py / 参考 qiti.py)")
    print("=" * 55)
    print(
        "串口: %s | UART%d TX=GPIO%d RX=GPIO%d 波特率=%d"
        % (label, uid, tx, rx, BAUDRATE)
    )
    if AUTO_UART_SCAN and "手动" in label and "失败" in label:
        print(
            "排查: 1) 传感器 T→板 RXD1、R→板 TXD1(交叉) 2) 共地 3) 波特率9600 4) 模块是否 TTL(Modbus)"
            " 非 RS485 5) robot_boot 是否占用同引脚"
        )
    print("\n气体类型     浓度值              状态")
    print("-" * 55)

    while True:
        try:
            for addr in (0x01, 0x02, 0x03, 0x04):
                data = read_gas_data_with_retry(uart, addr)
                if data:
                    conc_str = format_concentration(
                        data["concentration"], data["decimal"], data["unit"]
                    )
                    status_str = parse_status(data["status"])
                    print("%-8s %-20s [%s]" % (data["name"], conc_str, status_str))
                else:
                    cfg = SENSOR_CONFIG.get(addr, {})
                    name = cfg.get("name", "ID%x" % addr)
                    print("%-8s 读取失败" % name)
                utime.sleep_ms(50)
            print("-" * 55)
            utime.sleep(2)
        except KeyboardInterrupt:
            print("\n程序停止")
            break
        except Exception as e:
            print("错误: %s" % e)
            utime.sleep(2)


if __name__ == "__main__":
    main()
