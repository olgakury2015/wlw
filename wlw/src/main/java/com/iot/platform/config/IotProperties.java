package com.iot.platform.config;

import com.iot.platform.video.zlm.ZlmProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "iot")
public class IotProperties {

    private final Tcp tcp = new Tcp();
    private final Mqtt mqtt = new Mqtt();
    private final ModbusTcp modbusTcp = new ModbusTcp();
    private final ModbusRtu modbusRtu = new ModbusRtu();
    private final Maps maps = new Maps();
    private final Video video = new Video();

    @Data
    public static class Tcp {
        private boolean enabled = true;
        private String bindHost = "0.0.0.0";
        private int port = 9099;
        /**
         * 每处理完一行上报后向客户端回写一行 UTF-8（默认 {@code {"ok":true}}），便于 Python 等脚本在 send 后 recv 不阻塞。
         */
        private boolean sendLineAck = true;
    }

    @Data
    public static class Mqtt {
        private boolean enabled = false;
        /** 单 Broker 回退配置（当 {@link #brokers} 为空时使用） */
        private String brokerUrl = "tcp://127.0.0.1:1883";
        private String clientId = "wlw-iot-platform";
        private String username = "";
        private String password = "";
        private List<String> subscribeTopics = new ArrayList<>();
        /**
         * 多 Broker：每项独立 broker-url、订阅主题等；非空时<strong>优先</strong>于此列表，不再使用上方单条 broker-url。
         */
        private List<BrokerProfile> brokers = new ArrayList<>();
        private String publishTopicPrefix = "wlw/platform/";
        /**
         * 入库后是否向 Broker 发布扇出消息，便于本机/第三方<strong>订阅</strong> {@code publishTopicPrefix + fanoutSubTopicPrefix + deviceId} 消费。
         */
        private boolean fanoutEnabled = true;
        /**
         * 与 {@link #publishTopicPrefix} 拼接：完整主题为 {@code publishTopicPrefix + fanoutSubTopicPrefix + deviceId}，例如 {@code wlw/platform/telemetry/DEV001}。
         */
        private String fanoutSubTopicPrefix = "telemetry/";
        /**
         * 扇出发布使用的连接 id（与 {@link BrokerProfile#getId()} 对应）；留空则用第一个已成功连接的 Broker。
         */
        private String fanoutBrokerId = "";

        /**
         * 实际生效的 Broker 列表：配置了 {@link #brokers} 则用之，否则由单条 broker-url 合成一条。
         */
        public List<BrokerProfile> resolveBrokerProfiles() {
            if (brokers != null && !brokers.isEmpty()) {
                return new ArrayList<>(brokers);
            }
            BrokerProfile p = new BrokerProfile();
            p.setId("default");
            p.setBrokerUrl(brokerUrl);
            p.setClientId(clientId);
            p.setUsername(username);
            p.setPassword(password);
            if (subscribeTopics != null && !subscribeTopics.isEmpty()) {
                p.setSubscribeTopics(new ArrayList<>(subscribeTopics));
            } else {
                p.setSubscribeTopics(new ArrayList<String>());
            }
            return Collections.singletonList(p);
        }

        @Data
        public static class BrokerProfile {
            private String id = "default";
            private String brokerUrl = "tcp://127.0.0.1:1883";
            private String clientId = "wlw-iot-platform";
            private String username = "";
            private String password = "";
            private List<String> subscribeTopics = new ArrayList<>();
        }
    }

    @Data
    public static class ModbusTcp {
        private String defaultHost = "127.0.0.1";
        private int defaultPort = 502;
        private int defaultUnitId = 1;
    }

    @Data
    public static class ModbusRtu {
        private boolean enabled = false;
        private String portName = "COM3";
        private int baudRate = 9600;
        private int dataBits = 8;
        private int stopBits = 1;
        private String parity = "NONE";
        private int unitId = 1;
    }

    /**
     * 地图相关。控制台底图：{@link #provider} 为 {@code osm} 时使用 OpenStreetMap（免费、WGS84）；
     * 服务端地址解析仍可用 Nominatim，可选 {@link Gaode#getWebServiceKey()}。
     */
    @Data
    public static class Maps {
        /**
         * {@code osm}：OpenStreetMap + Leaflet（无需 Key，商用友好，坐标 WGS84）；
         * {@code gaode}：仅高德（须配置 js-api-key 与安全密钥）；
         * {@code auto}：有高德 Key 用高德，否则 OSM。
         */
        private String provider = "auto";
        private final Osm osm = new Osm();
        private final Gaode gaode = new Gaode();
    }

    @Data
    public static class Osm {
        /**
         * Leaflet 瓦片 URL。默认 Wikimedia osm-intl（中国区域地名多为中文）；
         * 灰色块多为瓦片请求失败，可改备用 URL 或填写 {@link #tiandituTk}。
         */
        private String tileUrl = "https://maps.wikimedia.org/osm-intl/{z}/{x}/{y}.png";
        /** 地图右下角署名（HTML）。 */
        private String attribution =
                "&copy; <a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\" rel=\"noopener\">OpenStreetMap</a>"
                        + " &copy; <a href=\"https://foundation.wikimedia.org/wiki/Maps_Terms_of_Use\" target=\"_blank\" rel=\"noopener\">Wikimedia</a>";
        /**
         * 天地图浏览器端 Key（<a href="https://console.tianditu.gov.cn">申请</a>）。
         * 填写后优先用天地图中文标注；底图为 GCJ-02，标点会自动做 WGS↔GCJ 转换。商用须遵守天地图协议。
         */
        private String tiandituTk = "";
        /** 主瓦片源连续失败时依次尝试的备用 URL（可为空）。 */
        private java.util.List<String> tileFallbackUrls = java.util.Arrays.asList(
                "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png",
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
    }

    @Data
    public static class Gaode {
        /**
         * 浏览器端 JS API 2.0 使用的 Key（与 Web 服务 Key 通常不同，勿混用）。
         */
        private String jsApiKey = "";
        /**
         * JS API 2.0 配套的安全密钥 securityJsCode。
         */
        private String securityJsCode = "";
        /**
         * 「Web服务」控制台申请的 Key，并勾选「地理编码」能力；供服务端调用 restapi.amap.com。
         */
        private String webServiceKey = "";
    }

    /**
     * 视频中心：RTSP 解码、MJPEG 推送、订阅者空闲回收等。
     */
    @Data
    public static class Video {
        /** JPEG 压缩质量 0.1–1.0 */
        private double jpegQuality = 0.72;
        /** 超过此宽度则等比缩小，减轻带宽与 CPU */
        private int maxFrameWidth = 1280;
        /** 无人观看后延迟关闭拉流线程的秒数 */
        private int subscriberIdleShutdownSeconds = 45;
        /** RTSP 传输：tcp 更稳；udp 延迟略低 */
        private String rtspTransport = "tcp";
        /** FFmpeg 打开 RTSP 超时（毫秒） */
        private int openTimeoutMs = 8000;
        /** 单次读流超时（毫秒），≤0 时按 5 秒处理，避免 grab 永久阻塞导致画面卡死 */
        private int readTimeoutMs = 5000;
        /** 向浏览器推送 MJPEG 时两帧之间的最小间隔（毫秒） */
        private int mjpegMinIntervalMs = 50;
        /** 拉流线程两次 grab 之间的间隔（毫秒），约 20fps */
        private int grabIntervalMs = 50;
        /**
         * 拉流引擎：ffmpeg=子进程；javacv=JavaCV+OpenCV；vlcj=本机 VLC（需安装 VideoLAN VLC）。
         */
        private String decoder = "ffmpeg";
        /** VLC 安装目录，留空则自动发现（Windows 常见：C:/Program Files/VideoLAN/VLC） */
        private String vlcPath = "";
        /** VLC network-caching（毫秒），影响 RTSP 缓冲 */
        private int vlcNetworkCachingMs = 300;
        /** 可选：本机 ffmpeg.exe 绝对路径；留空则自动从 bytedeco / PATH 查找 */
        private String ffmpegPath = "";
        /** ffmpeg 定时无缝切换间隔（毫秒），在摄像机断 TCP 前换新进程 */
        /** 0 表示关闭定时切换，仅进程退出/卡顿时重连。 */
        private int ffmpegHandoffIntervalMs = 0;
        /** 超过此时间无新帧才重连（毫秒）；VLCJ/ffmpeg 共用 */
        private int ffmpegStallMs = 60_000;
        /** 长时间无任何画面时结束本轮拉流（毫秒） */
        private int videoNoFrameReconnectMs = 120_000;
        /** 两次启动 ffmpeg 之间的最短间隔（毫秒），减轻海康 -10054 限连 */
        private int ffmpegMinReconnectMs = 8_000;
        /** 无缝切换时等待新进程首帧的最长时间（毫秒） */
        private int ffmpegHandoffFirstFrameMs = 10_000;
        /** RTSP 频繁 -10054 时是否尝试海康 ISAPI httpPreview */
        private boolean ffmpegHikvisionHttpFallback = true;
        /**
         * 国标 RTP 解码：auto=先 FFmpeg 子进程再 JavaCV；ffmpeg；javacv。
         * 日志出现 no decoder found for: none 时建议 auto 或 javacv，并配置 ffmpeg-path。
         */
        private String gb28181Decoder = "auto";
        /**
         * 国标媒体传输：tcp_passive（默认，海康选 TCP）| udp。留空则使用数据库「国标 28181」页配置。
         */
        private String gb28181MediaTransport = "";
        /** 国标无画面时，若通道配置了 RTSP 则回退 VLCJ/FFmpeg 拉流（同机海康常用） */
        private boolean gb28181RtspFallback = true;
        /**
         * 国标收流使用 ZLMediaKit（与 wvp 相同，需本机已启动 ZLM 并配置 secret）。
         * 启用后由 ZLM 收 RTP，前端仍通过 MJPEG 播放 ZLM 的 RTSP 流。
         */
        private boolean gb28181UseZlm = false;
        /** ZLM 未启动或 openRtpServer 失败时，自动回退本机 FFmpeg 国标收流 */
        private boolean gb28181ZlmFallback = true;
        private final ZlmProperties zlm = new ZlmProperties();
    }

}
