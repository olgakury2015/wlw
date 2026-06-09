import usocket as socket
import utime
import network
from machine import UART, Pin
import ujson

# ========== WiFi配置 ==========
WIFI_SSID = "1705"
WIFI_PASSWORD = "66666666"

# ========== 服务器配置 ==========
SERVER_IP = "192.168.0.114"  # Java后端服务器IP
SERVER_PORT = 9099           # Java后端TCP端口

# ========== 设备标识配置（重要！每个设备唯一）==========
DEVICE_ID = "gas_sensor_01"      # 设备唯一ID，与Java后端设备表对应

# ========== LED ==========
led = Pin(2, Pin.OUT)

# ========== 气体传感器配置 ==========
UART_NUM = 2
TX_PIN = 22
RX_PIN = 23
BAUDRATE = 9600

# 传感器地址映射
SENSOR_CONFIG = {
    0x01: {"name": "CH4", "unit": "LEL%", "decimal": 0},
    0x02: {"name": "O2", "unit": "VOL%", "decimal": 1},
    0x03: {"name": "CO", "unit": "ppm", "decimal": 0},
    0x04: {"name": "H2S", "unit": "ppm", "decimal": 0},
}

# 状态映射
STATUS_MAP = {0x01: "Normal", 0x05: "Low alarm", 0x06: "High alarm"}

# ========== CRC16校验 ==========
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

# ========== 读取单个气体传感器 ==========
def read_gas_data(slave_addr):
    """读取指定地址的气体传感器数据"""
    # 构建Modbus帧
    frame = bytes([slave_addr, 0x03, 0x00, 0x00, 0x00, 0x0A])
    frame += crc16(frame)
    
    # 清空缓冲区
    while uart.any():
        uart.read()
    
    # 发送请求
    uart.write(frame)
    utime.sleep_ms(150)
    
    # 读取响应（25字节）
    response = uart.read(25)
    
    if response and len(response) == 25:
        if response[0] == slave_addr and response[1] == 0x03:
            # 解析浓度值
            raw_concentration = (response[5] << 8) | response[6]
            # 解析状态
            status = (response[13] << 8) | response[14]
            # 解析温度
            temp_raw = (response[17] << 8) | response[18]
            # 解析湿度
            humidity_raw = (response[21] << 8) | response[22]
            
            temperature = temp_raw / 10.0
            humidity = humidity_raw / 10.0
            
            config = SENSOR_CONFIG.get(slave_addr, {})
            decimal = config.get("decimal", 0)
            
            # 根据小数位数处理浓度值
            if decimal == 1:
                concentration = raw_concentration / 10.0
            else:
                concentration = raw_concentration
            
            return {
                'value': round(concentration, 1),
                'status': status,
                'temperature': round(temperature, 1),
                'humidity': round(humidity, 1),
                'name': config.get("name", "Unknown"),
                'unit': config.get("unit", "")
            }
    return None

# ========== 读取所有气体传感器 ==========
def read_all_gases():
    """读取所有4个气体传感器数据"""
    result = {}
    success_count = 0
    
    for addr in [0x01, 0x02, 0x03, 0x04]:
        data = read_gas_data(addr)
        if data:
            result[data['name']] = data
            success_count += 1
        utime.sleep_ms(50)
    
    # 至少读取到3个传感器数据才认为成功
    return result if success_count >= 3 else None

# ========== WiFi连接 ==========
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
def build_json_data(gases, ip):
    """构建要上传的JSON数据"""
    sensors_data = {}
    
    for name, data in gases.items():
        sensors_data[name] = {
            "v": data['value'],
            "u": data['unit'],
            "s": STATUS_MAP.get(data['status'], "Normal"),
            "t": data['temperature'],
            "h": data['humidity']
        }
    
    return ujson.dumps({
        "device_id": DEVICE_ID,
        "timestamp": utime.time(),
        "sensors": sensors_data
    }) + "\n"

# ========== 打印传感器数据 ==========
def print_sensor_data(gases):
    """打印传感器数据到控制台"""
    for name, data in gases.items():
        print("  %s: %.1f%s [%s] Temp:%.1fC Humidity:%.1f%%" % 
              (name, data['value'], data['unit'], 
               STATUS_MAP.get(data['status'], "Normal"),
               data['temperature'], data['humidity']))

# ========== 主程序 ==========
def main():
    print("=" * 50)
    print("Gas Sensor TCP Client v2.0")
    print("=" * 50)
    print("Device ID: %s" % DEVICE_ID)
    print("Server: %s:%d" % (SERVER_IP, SERVER_PORT))
    print("=" * 50)
    
    global uart
    uart = UART(UART_NUM, baudrate=BAUDRATE, bits=8, parity=None, stop=1, 
                tx=TX_PIN, rx=RX_PIN)
    print("UART initialized: TX=GPIO%d, RX=GPIO%d" % (TX_PIN, RX_PIN))
    
    ip = connect_wifi()
    if not ip:
        print("WiFi failed, stopping...")
        while True:
            utime.sleep(1)
    
    print("\nStarting data collection loop...\n")
    
    # 主循环
    while True:
        try:
            # 读取传感器
            print("Reading sensors...")
            gases = read_all_gases()
            
            if gases:
                print("Data: %d sensors" % len(gases))
                print_sensor_data(gases)
                
                # 构建并发送数据
                json_data = build_json_data(gases, ip)
                if upload_to_server(json_data):
                    # 上传成功闪烁LED
                    led.value(0)
                    utime.sleep_ms(50)
                    led.value(1)
            else:
                print("Sensor read failed, retrying...")
            
            print("-" * 40)
            utime.sleep(5)
            
        except Exception as e:
            print("Error: %s" % str(e))
            led.value(0)
            utime.sleep(2)
            led.value(1)

# 程序入口
if __name__ == "__main__":
    main()
