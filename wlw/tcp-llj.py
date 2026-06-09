"""
拉力机传感器 - TCP客户端推送版本（动态IP，中文JSON键名）
支持RS485转TTL模块，带方向控制
"""

import usocket as socket
import utime
import network
from machine import UART, Pin
import ujson

try:
    import struct
except ImportError:
    struct = None

# ========== WiFi配置 ==========
WIFI_SSID = "1705"
WIFI_PASSWORD = "66666666"

# ========== 服务器配置（Java后端）==========
SERVER_IP = "192.168.0.114"  # Java后端服务器IP
SERVER_PORT = 9099           # Java后端TCP端口

# ========== 设备标识配置（重要！每个设备唯一）==========
DEVICE_ID = "tensile_machine_012"      # 设备唯一ID，与Java后端设备表对应

# ========== LED ==========
led = Pin(2, Pin.OUT)

# ========== 硬件配置 ==========
# RS485串口配置
UART_NUM = 2
TX_PIN = 13    # DI引脚
RX_PIN = 12    # RO引脚
BAUDRATE = 9600

# RS485方向控制引脚（如果需要）
# 有些模块需要手动控制接收/发送方向
RE_PIN = 4     # RES引脚 - 低电平接收，高电平发送
USE_DIRECTION_PIN = True  # 是否使用方向控制引脚

# ========== 拉力机传感器配置（南京天光数字模块 ModBus RTU 规约 V1.01.808）==========
# 读重量：功能码 03H，起始地址 0x0000，读 2 个寄存器（4 字节）
# 从站应答：地址 + 03 + 字节数(04) + 浮点大端(IEEE754) + CRC_L + CRC_H
# 示例 RX: 01 03 04 45 9C 40 00 xx xx  →  0x459C4000 按大端 float = 5000.0
SLAVE_ADDRESS = 0x01
WEIGHT_START_REG = 0x0000
WEIGHT_REGISTER_COUNT = 2  # 2×16bit = 4 字节浮点

# 规约未在寄存器帧内约定物理单位；数显拉力/称重模块在国内常见标定为「千克」示值（kg 或工程上俗称的 kgf）。
# 若与第三方校准证书不一致，可改为 "N"、"kN"、"kgf" 等。
WEIGHT_UNIT_LABEL = "kg"

# ========== 初始化RS485 ==========
def init_rs485():
    """初始化RS485串口"""
    uart = UART(UART_NUM, baudrate=BAUDRATE, bits=8, parity=None, stop=1, 
                tx=TX_PIN, rx=RX_PIN)
    
    # 如果需要方向控制引脚
    if USE_DIRECTION_PIN:
        re_pin = Pin(RE_PIN, Pin.OUT)
        re_pin.value(0)  # 初始为接收模式
        return uart, re_pin
    
    return uart, None

# ========== 辅助函数 ==========
def bytes_to_hex(data):
    """将字节数据转换为十六进制字符串"""
    return ' '.join(f'{b:02X}' for b in data)

def calculate_crc16(data):
    """计算Modbus CRC16校验"""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x0001:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return bytes([crc & 0xFF, (crc >> 8) & 0xFF])

def build_modbus_frame(slave_addr, func_code, start_addr, register_count):
    """构建Modbus RTU帧"""
    frame = bytes([slave_addr, func_code, 
                   (start_addr >> 8) & 0xFF, start_addr & 0xFF,
                   (register_count >> 8) & 0xFF, register_count & 0xFF])
    frame += calculate_crc16(frame)
    return frame

# 读重量查询帧（与规约示例 01 03 00 00 00 02 C4 0B 一致）
QUERY_FRAME = build_modbus_frame(SLAVE_ADDRESS, 0x03, WEIGHT_START_REG, WEIGHT_REGISTER_COUNT)

def send_query(uart, re_pin=None):
    """发送查询指令"""
    # 如果是手动控制方向，切换到发送模式
    if re_pin:
        re_pin.value(1)  # 发送模式
        utime.sleep_ms(1)
    
    # 清空缓冲区
    while uart.any():
        uart.read()
    
    # 发送数据
    uart.write(QUERY_FRAME)
    utime.sleep_ms(50)
    
    # 切换回接收模式
    if re_pin:
        utime.sleep_ms(1)
        re_pin.value(0)  # 接收模式

def read_response(uart, timeout_ms=500):
    """读取设备回复"""
    buffer = bytearray()
    start = utime.ticks_ms()
    
    while utime.ticks_diff(utime.ticks_ms(), start) < timeout_ms:
        if uart.any():
            while uart.any():
                data = uart.read()
                if data:
                    buffer.extend(data)
            break
        utime.sleep_ms(10)
    
    return buffer

def crc_valid(data):
    """校验 Modbus RTU 帧末尾 CRC（低字节在前）。"""
    if data is None or len(data) < 3:
        return False
    c = calculate_crc16(data[:-2])
    return data[-2] == c[0] and data[-1] == c[1]

def _ieee754_be_float(b4):
    """大端 4 字节 IEEE754 转 float（与规约「浮点 HH/HL/LH/LL」一致）。"""
    if struct is not None:
        return struct.unpack(">f", bytes(b4))[0]
    return 0.0

def extract_weight_response_packet(data, slave_addr=None):
    """
    在原始缓冲区中截取一帧「读重量」正常应答。
    帧格式: SA + 03 + 04 + D0..D3(float BE) + CRC_L + CRC_H 共 9 字节。
    若前面有杂散字节，从滑动窗口中找第一帧 CRC 正确的包。
    """
    if slave_addr is None:
        slave_addr = SLAVE_ADDRESS
    if not data or len(data) < 9:
        return None
    d = data if isinstance(data, (bytes, bytearray)) else bytearray(data)
    last = len(d) - 9
    for i in range(last + 1):
        if d[i] != slave_addr or d[i + 1] != 0x03:
            continue
        if d[i + 2] != 4:
            continue
        pkt = d[i : i + 9]
        if crc_valid(pkt):
            return pkt
    return None

def parse_modbus_exception(data, slave_addr=None):
    """异常应答: SA + (83H) + 异常码 + CRC。规约功能码最高位置 1。"""
    if slave_addr is None:
        slave_addr = SLAVE_ADDRESS
    if len(data) < 5:
        return None
    d = data if isinstance(data, (bytes, bytearray)) else bytearray(data)
    for i in range(len(d) - 4):
        if d[i] != slave_addr:
            continue
        if d[i + 1] != (0x03 | 0x80):
            continue
        pkt = d[i : i + 5]
        if crc_valid(pkt):
            code = d[i + 2]
            reasons = {
                0x01: "非法功能码",
                0x02: "非法数据地址",
                0x03: "非法数据内容",
            }
            return {
                "force": None,
                "unit": WEIGHT_UNIT_LABEL,
                "status": "Modbus异常",
                "modbus_exception": code,
                "modbus_message": reasons.get(code, "未知异常码"),
            }
    return None

# ========== 读取拉力数据 ==========
def read_force_data(uart, re_pin):
    """读取拉力数据"""
    # 发送查询并读取响应
    send_query(uart, re_pin)
    response = read_response(uart)
    
    if len(response) == 0:
        return None
    
    # 检查Modbus异常
    ex = parse_modbus_exception(response)
    if ex is not None:
        return None
    
    # 解析正常数据
    pkt = extract_weight_response_packet(response)
    if pkt is None:
        return None
    
    b4 = pkt[3:7]
    raw_u32 = (b4[0] << 24) | (b4[1] << 16) | (b4[2] << 8) | b4[3]
    force = _ieee754_be_float(b4)
    
    # 排除NaN
    if force != force:
        return None
    
    return round(force, 4)

# ========== 连接WiFi ==========
def connect_wifi():
    """连接WiFi网络"""
    wlan = network.WLAN(network.STA_IF)
    wlan.active(True)
    
    if wlan.isconnected():
        ip = wlan.ifconfig()[0]
        print("WiFi already connected, IP: %s" % ip)
        led.value(1)
        return ip
    
    print("Connecting to WiFi: %s" % WIFI_SSID)
    wlan.connect(WIFI_SSID, WIFI_PASSWORD)
    
    for i in range(15):
        if wlan.isconnected():
            ip = wlan.ifconfig()[0]
            print("\nWiFi connected! IP: %s" % ip)
            led.value(1)
            return ip
        utime.sleep(1)
        print(".", end="")
    
    print("\nWiFi connection failed!")
    led.value(0)
    return None

# ========== 上传数据到服务器 ==========
def upload_to_server(data):
    """通过TCP上传数据到Java后端"""
    try:
        addr = socket.getaddrinfo(SERVER_IP, SERVER_PORT)[0][-1]
        client = socket.socket()
        client.settimeout(5)
        
        client.connect(addr)
        client.send(data.encode())
        
        client.close()
        return True
        
    except Exception as e:
        print("Upload failed: %s" % str(e))
        return False

# ========== 构建JSON数据 ==========
def build_json_data(force_value):
    """构建要上传的JSON数据"""
    if force_value is None:
        return None
    
    # 按照气体传感器的格式组织数据
    sensors_data = {
        "拉力": {
            "v": force_value,
            "u": WEIGHT_UNIT_LABEL,
            "s": "Normal"
        }
    }
    
    return ujson.dumps({
        "device_id": DEVICE_ID,
        "timestamp": utime.time(),
        "sensors": sensors_data
    }) + "\n"

# ========== 打印传感器数据 ==========
def print_sensor_data(force_value):
    """打印传感器数据到控制台"""
    if force_value is not None:
        print("  拉力: %.4f %s" % (force_value, WEIGHT_UNIT_LABEL))

# ========== 主程序 ==========
def main():
    print("=" * 50)
    print("拉力机传感器 TCP Client v2.0")
    print("=" * 50)
    print("Device ID: %s" % DEVICE_ID)
    print("Server: %s:%d" % (SERVER_IP, SERVER_PORT))
    print("=" * 50)
    
    # 初始化RS485
    uart, re_pin = init_rs485()
    print("RS485 initialized: UART%d, TX=GPIO%d, RX=GPIO%d" % (UART_NUM, TX_PIN, RX_PIN))
    print("Baudrate: %d" % BAUDRATE)
    print("Query command: %s" % bytes_to_hex(QUERY_FRAME))
    
    if USE_DIRECTION_PIN and re_pin:
        print("Direction control: GPIO%d enabled" % RE_PIN)
    
    # 连接WiFi
    ip = connect_wifi()
    if not ip:
        print("WiFi failed, stopping...")
        while True:
            utime.sleep(1)
    
    print("\nStarting data collection loop...\n")
    
    # 主循环 - 主动推送数据
    while True:
        try:
            # 读取传感器
            print("Reading sensor...")
            force_value = read_force_data(uart, re_pin)
            
            if force_value is not None:
                print("Data collected:")
                print_sensor_data(force_value)
                
                # 构建并发送数据
                json_data = build_json_data(force_value)
                if json_data and upload_to_server(json_data):
                    # 上传成功闪烁LED
                    led.value(0)
                    utime.sleep_ms(50)
                    led.value(1)
                    print("  ✓ Upload successful")
            else:
                print("  ✗ Sensor read failed, retrying...")
            
            print("-" * 40)
            utime.sleep(5)  # 每5秒推送一次数据
            
        except Exception as e:
            print("Error: %s" % str(e))
            led.value(0)
            utime.sleep(2)
            led.value(1)

# 程序入口
if __name__ == "__main__":
    main()
