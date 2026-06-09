"""
拉力机传感器 - ESP32 RS485 读取代码
支持RS485转TTL模块，带方向控制
"""

from machine import UART, Pin
import time
import ujson

try:
    import struct
except ImportError:
    struct = None

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

# QUERY_FRAME 见下方 build_modbus_frame 定义之后

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
        time.sleep_ms(1)
    
    # 清空缓冲区
    while uart.any():
        uart.read()
    
    # 发送数据
    uart.write(QUERY_FRAME)
    time.sleep_ms(50)
    
    # 切换回接收模式
    if re_pin:
        time.sleep_ms(1)
        re_pin.value(0)  # 接收模式

def read_response(uart, timeout_ms=500):
    """读取设备回复"""
    buffer = bytearray()
    start = time.ticks_ms()
    
    while time.ticks_diff(time.ticks_ms(), start) < timeout_ms:
        if uart.any():
            while uart.any():
                data = uart.read()
                if data:
                    buffer.extend(data)
            break
        time.sleep_ms(10)
    
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


# ========== 解析拉力数据 ==========
def parse_force_data(data):
    """
    按南京天光规约解析「读重量」应答（单帧 9 字节，IEEE754 大端浮点）。
    与 parse_weight_response / parse_extended_data 行为一致。
    """
    return parse_weight_response(data)


def parse_weight_response(data):
    """解析读重量 03 应答；成功返回含 force(浮点)、unit、status、raw_u32 等字段。"""
    ex = parse_modbus_exception(data)
    if ex is not None:
        return ex

    pkt = extract_weight_response_packet(data)
    if pkt is None:
        return None

    b4 = pkt[3:7]
    raw_u32 = (b4[0] << 24) | (b4[1] << 16) | (b4[2] << 8) | b4[3]
    force = _ieee754_be_float(b4)

    # 排除 NaN
    if force != force:
        return None

    return {
        "force": round(force, 4),
        "unit": WEIGHT_UNIT_LABEL,
        "status": "Normal",
        "raw_u32": raw_u32,
        "raw_float_be_hex": "%02X%02X%02X%02X" % (b4[0], b4[1], b4[2], b4[3]),
        "crc_ok": True,
    }


def parse_extended_data(data):
    """
    南京天光数字模块当前「读重量」仅返回 9 字节浮点一帧；
    若日后扩展多寄存器，可在此按寄存器表解析；现与 parse_weight_response 相同。
    """
    return parse_weight_response(data)

# ========== 主程序 ==========
def main():
    print("=" * 50)
    print("拉力机传感器 RS485 读取程序")
    print("=" * 50)
    
    # 初始化
    uart, re_pin = init_rs485()
    print(f"RS485初始化: UART{UART_NUM}, TX=GPIO{TX_PIN}, RX=GPIO{RX_PIN}")
    print(f"波特率: {BAUDRATE}")
    print(f"查询命令: {bytes_to_hex(QUERY_FRAME)}")
    
    if USE_DIRECTION_PIN and re_pin:
        print(f"方向控制: GPIO{RE_PIN} 已启用")
    
    print("\n开始读取数据...\n")
    
    # 主循环
    while True:
        try:
            # 发送查询并读取响应
            send_query(uart, re_pin)
            response = read_response(uart)
            
            if len(response) > 0:
                # 打印原始数据
                print(f"原始数据: {bytes_to_hex(response)}")
                print(f"数据长度: {len(response)} 字节")
                
                # 解析数据
                force_data = parse_extended_data(response)
                
                if force_data:
                    print("解析结果:")
                    if force_data.get("modbus_exception") is not None:
                        print(
                            "  Modbus异常:",
                            force_data.get("modbus_message"),
                            "异常码=0x%02X" % force_data.get("modbus_exception"),
                        )
                    elif "current_force" in force_data:
                        print(f"  当前拉力: {force_data['current_force']} {force_data['unit']}")
                        print(f"  峰值拉力: {force_data['peak_force']} {force_data['unit']}")
                        print(f"  位移: {force_data['displacement']} mm")
                        print(f"  速度: {force_data['speed']} mm/s")
                    else:
                        fv = force_data.get("force")
                        if fv is not None:
                            print(f"  拉力: {fv} {force_data['unit']}")
                        if force_data.get("raw_u32") is not None:
                            print("  原始浮点(u32): 0x%08X" % force_data["raw_u32"])
                    print(f"  状态: {force_data['status']}")
                    
                    # 可选：转换为JSON格式用于网络传输
                    json_data = ujson.dumps(force_data)
                    print(f"  JSON: {json_data}")
                else:
                    print("数据解析失败 - 检查协议格式")
            else:
                print("未收到数据 - 检查接线和传感器地址")
            
            print("-" * 40)
            time.sleep(1)  # 每秒读取一次
            
        except KeyboardInterrupt:
            print("\n程序停止")
            break
        except Exception as e:
            print(f"错误: {e}")
            time.sleep(2)

# ========== 高级功能：连续读取 ==========
def continuous_read(duration_seconds=60, interval=0.5):
    """
    连续读取指定时间的数据
    
    Args:
        duration_seconds: 读取持续时间（秒）
        interval: 读取间隔（秒）
    """
    uart, re_pin = init_rs485()
    data_log = []
    
    start_time = time.time()
    print(f"开始连续读取 {duration_seconds} 秒...")
    
    while time.time() - start_time < duration_seconds:
        send_query(uart, re_pin)
        response = read_response(uart)
        
        if len(response) > 0:
            force_data = parse_extended_data(response)
            if force_data:
                force_data["timestamp"] = time.time()
                data_log.append(force_data)
                
                if "current_force" in force_data:
                    print(f"时间: {time.time():.1f}s, 拉力: {force_data['current_force']} {force_data['unit']}")
                elif force_data.get("modbus_exception") is not None:
                    print(
                        "时间: %.1fs, Modbus异常: %s"
                        % (time.time(), force_data.get("modbus_message"))
                    )
                elif force_data.get("force") is not None:
                    print(f"时间: {time.time():.1f}s, 拉力: {force_data['force']} {force_data['unit']}")
        
        time.sleep(interval)
    
    print(f"\n总共读取 {len(data_log)} 条数据")
    return data_log

# ========== 测试不同查询命令 ==========
def test_queries():
    """测试不同的Modbus查询命令"""
    uart, re_pin = init_rs485()
    
    test_commands = [
        ([0x01, 0x03, 0x00, 0x00, 0x00, 0x02, 0xC4, 0x0B], "读取2个寄存器"),
        ([0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0x84, 0x0E], "读取10个寄存器"),
        ([0x01, 0x04, 0x00, 0x00, 0x00, 0x02, 0x71, 0xCB], "读取输入寄存器"),
    ]
    
    for cmd_bytes, description in test_commands:
        cmd = bytes(cmd_bytes)
        print(f"\n测试: {description}")
        print(f"命令: {bytes_to_hex(cmd)}")
        
        # 清空缓冲区
        while uart.any():
            uart.read()
        
        # 发送命令
        if re_pin:
            re_pin.value(1)
            time.sleep_ms(1)
        
        uart.write(cmd)
        time.sleep_ms(100)
        
        if re_pin:
            time.sleep_ms(1)
            re_pin.value(0)
        
        # 读取响应
        response = read_response(uart, timeout_ms=300)
        
        if len(response) > 0:
            print(f"响应: {bytes_to_hex(response)}")
        else:
            print("无响应")
        
        time.sleep(1)

# 运行
if __name__ == "__main__":
    main()
    
    # 可选：连续读取10秒
    # continuous_read(10, 0.5)
    
    # 可选：测试不同的查询命令
    # test_queries()
