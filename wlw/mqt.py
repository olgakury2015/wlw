# -*- coding: utf-8 -*-
"""
MQTT 订阅端（本机测试）：主动连接 Broker，订阅主题并打印消息。

模型说明：
  · 平台（Java）连接同一 Broker，作为「订阅者」收设备上行：wlw/devices/+/telemetry
  · 任意协议遥测入库后，平台再向「扇出主题」发布一条 JSON，便于你只订阅即可拿到数据：
      <publishTopicPrefix><fanoutSubTopicPrefix><设备编号>
    默认即：wlw/platform/telemetry/<设备编号>

本机测试步骤（Windows 示例）：
  1) 安装 Mosquitto 并确保 1883 监听（或改端口并同步修改 application.yml 的 broker-url）
  2) application.yml：iot.mqtt.enabled=true，iot.mqtt.broker-url=tcp://127.0.0.1:1883，fanout-enabled=true
  3) 启动 IoT 平台（Spring Boot）
  4) pip install paho-mqtt
  5) python mqt.py
  6) 另开终端触发入库，例如：
       mosquitto_pub -h 127.0.0.1 -p 1883 -t wlw/devices/demo001/telemetry -m "{\"deviceId\":\"demo001\",\"temp\":25}"
     或 ces.py 走 TCP 上报
  7) 本脚本应打印扇出消息（topic 形如 wlw/platform/telemetry/demo001）

  python mqt.py --spy   订阅 wlw/devices/+/telemetry，只看设备原始上行。
"""

import json
import os
import sys
import time

MQTT_BROKER = os.environ.get("MQTT_BROKER", "127.0.0.1")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_USER = os.environ.get("MQTT_USER", "") or ""
MQTT_PASSWORD = os.environ.get("MQTT_PASSWORD", "") or ""

TOPIC_FANOUT = "wlw/platform/telemetry/+"
TOPIC_UPLINK = "wlw/devices/+/telemetry"


def _pretty(msg):
    try:
        o = json.loads(msg)
        return json.dumps(o, ensure_ascii=False, indent=2)
    except Exception:
        return msg


def run_paho(spy_uplink):
    import paho.mqtt.client as mqtt

    sub_topic = TOPIC_UPLINK if spy_uplink else TOPIC_FANOUT

    def on_connect(client, _userdata, _flags, rc):
        if rc != 0:
            print("[MQTT] 连接失败 rc=", rc)
            return
        print("[MQTT] 已连接，订阅:", sub_topic)
        client.subscribe(sub_topic, qos=1)

    def on_message(_client, _userdata, msg):
        ts = time.strftime("%H:%M:%S")
        print("\n----- %s -----" % ts)
        print("topic:", msg.topic)
        try:
            body = msg.payload.decode("utf-8")
        except Exception:
            body = str(msg.payload)
        print(_pretty(body))

    cid = "wlw-mqt-sub-%d" % (int(time.time()) % 1_000_000_000)
    c = mqtt.Client(client_id=cid, protocol=mqtt.MQTTv311)
    if MQTT_USER:
        c.username_pw_set(MQTT_USER, MQTT_PASSWORD or None)
    c.on_connect = on_connect
    c.on_message = on_message
    print("[MQTT] 连接 %s:%s ..." % (MQTT_BROKER, MQTT_PORT))
    c.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
    c.loop_forever()


def run_umqtt(spy_uplink):
    from umqtt.simple import MQTTClient

    sub_topic = TOPIC_UPLINK if spy_uplink else TOPIC_FANOUT
    topic_b = sub_topic.encode("utf-8") if isinstance(sub_topic, str) else sub_topic

    def cb(topic, msg):
        ts = time.strftime("%H:%M:%S")
        print("\n----- %s -----" % ts)
        t = topic.decode("utf-8") if isinstance(topic, bytes) else topic
        print("topic:", t)
        try:
            body = msg.decode("utf-8") if isinstance(msg, bytes) else str(msg)
        except Exception:
            body = str(msg)
        print(_pretty(body))

    cid = b"wlw-sub-%d" % (int(time.time()) % 1_000_000_000)
    user = MQTT_USER if MQTT_USER else None
    pwd = MQTT_PASSWORD if MQTT_PASSWORD else None
    c = MQTTClient(cid, MQTT_BROKER, port=MQTT_PORT, user=user, password=pwd, keepalive=60)
    c.set_callback(cb)
    c.connect()
    c.subscribe(topic_b, qos=1)
    print("[MQTT] 已连接(umqtt)，订阅:", sub_topic)
    while True:
        c.wait_msg()


def main():
    spy = os.environ.get("MQTT_SPY", "0") == "1"
    if len(sys.argv) > 1 and sys.argv[1] in ("--spy", "-s"):
        spy = True
    print("模式:", "设备上行 " + TOPIC_UPLINK if spy else "平台扇出 " + TOPIC_FANOUT)
    try:
        import paho.mqtt.client  # noqa: F401

        run_paho(spy)
    except ImportError:
        run_umqtt(spy)


if __name__ == "__main__":
    main()
