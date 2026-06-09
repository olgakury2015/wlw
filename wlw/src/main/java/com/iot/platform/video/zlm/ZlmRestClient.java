package com.iot.platform.video.zlm;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpEntity;

import org.springframework.http.HttpHeaders;

import org.springframework.http.MediaType;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import org.springframework.stereotype.Component;

import org.springframework.util.LinkedMultiValueMap;

import org.springframework.util.MultiValueMap;

import org.springframework.util.StringUtils;

import org.springframework.web.client.RestTemplate;



/**

 * ZLMediaKit HTTP API（对齐 wvp {@code ZLMRESTfulUtils} 点播相关接口）。

 */

@Slf4j

@Component

public class ZlmRestClient {



    private static final String RTP_APP = "rtp";

    private static final String DEFAULT_VHOST = "__defaultVhost__";



    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate;



    public ZlmRestClient() {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(8000);

        factory.setReadTimeout(15000);

        this.restTemplate = new RestTemplate(factory);

    }



    public boolean isConfigured(ZlmProperties cfg) {

        return cfg != null

                && StringUtils.hasText(cfg.getHttpHost())

                && StringUtils.hasText(cfg.getSecret());

    }



    public boolean isEnabled(ZlmProperties cfg) {

        return isConfigured(cfg) && cfg.isEnabled();

    }



    /** ZLM HTTP API 是否可达（未启动时 openRtpServer 会 Connection refused）。 */

    public boolean isApiReachable(ZlmProperties cfg) {

        if (!isConfigured(cfg)) {

            return false;

        }

        MultiValueMap<String, String> form = baseForm(cfg);

        JsonNode root = post(cfg, "getServerConfig", form);

        if (root != null && root.path("code").asInt(-1) == 0) {

            return true;

        }

        int code = root != null ? root.path("code").asInt(-1) : -1;

        String hint = code == -100

                ? "（secret 错误：修改 config.ini 后须重启 MediaServer；勿同时运行多个 MediaServer.exe）"

                : "";

        log.warn("ZLM getServerConfig 失败 base={} code={} msg={}{}",

                apiBaseUrl(cfg), code, msg(root), hint);

        return false;

    }



    public String apiBaseUrl(ZlmProperties cfg) {

        return String.format("http://%s:%d/index/api/", cfg.getHttpHost().trim(), cfg.getHttpPort());

    }



    /**

     * @param tcpMode 0=UDP 1=TCP被动 2=TCP主动

     * @param port    0 表示由 ZLM 在 port_range 内分配

     */

    public int openRtpServer(ZlmProperties cfg, String streamId, int port, int tcpMode) {

        return openRtpServer(cfg, streamId, port, tcpMode, null, null);

    }



    /**

     * @param ssrcY   国标 y= SSRC（10 位数字），与 INVITE SDP 一致

     * @param localIp 收流绑定 IP，建议填 sdp-ip（局域网 IP）

     */

    public int openRtpServer(

            ZlmProperties cfg,

            String streamId,

            int port,

            int tcpMode,

            String ssrcY,

            String localIp) {

        MultiValueMap<String, String> form = baseForm(cfg);

        form.add("app", RTP_APP);

        form.add("stream_id", streamId);

        form.add("port", String.valueOf(port > 0 ? port : 0));

        form.add("tcp_mode", String.valueOf(tcpMode));

        form.add("re_use_port", "1");

        form.add("only_audio", "0");

        if (StringUtils.hasText(ssrcY)) {

            try {

                form.add("ssrc", String.valueOf(Long.parseLong(ssrcY.trim())));

            } catch (NumberFormatException e) {

                log.warn("ZLM openRtpServer 忽略非法 SSRC y={}", ssrcY);

            }

        }

        // 云主机勿绑公网 IP；未指定本机网卡 IP 时显式 0.0.0.0，避免 ZLM ini listen_ip 配错导致 bind 失败
        String bindIp = StringUtils.hasText(localIp) ? localIp.trim() : "0.0.0.0";
        form.add("local_ip", bindIp);

        JsonNode root = post(cfg, "openRtpServer", form);

        if (root == null || root.path("code").asInt(-1) != 0) {

            log.error("ZLM openRtpServer 失败 stream={} port={} bind-ip={} msg={}",
                    streamId, port, bindIp, msg(root));

            return -1;

        }

        int zlmPort = root.path("port").asInt(port > 0 ? port : 0);

        log.info("ZLM openRtpServer 成功 stream={} port={} tcp_mode={} ssrc={}",

                streamId, zlmPort, tcpMode, ssrcY);

        return zlmPort;

    }



    public boolean updateRtpServerSsrc(ZlmProperties cfg, String streamId, String ssrcY) {

        if (!StringUtils.hasText(ssrcY)) {

            return false;

        }

        MultiValueMap<String, String> form = baseForm(cfg);

        form.add("app", RTP_APP);

        form.add("stream_id", streamId);

        try {

            form.add("ssrc", String.valueOf(Long.parseLong(ssrcY.trim())));

        } catch (NumberFormatException e) {

            return false;

        }

        JsonNode root = post(cfg, "updateRtpServerSSRC", form);

        if (root == null || root.path("code").asInt(-1) != 0) {

            log.warn("ZLM updateRtpServerSSRC 失败 stream={} ssrc={} msg={}", streamId, ssrcY, msg(root));

            return false;

        }

        log.info("ZLM updateRtpServerSSRC 成功 stream={} ssrc={}", streamId, ssrcY);

        return true;

    }



    public boolean connectRtpServer(ZlmProperties cfg, String streamId, String dstIp, int dstPort) {

        MultiValueMap<String, String> form = baseForm(cfg);

        form.add("app", RTP_APP);

        form.add("stream_id", streamId);

        form.add("dst_url", dstIp);

        form.add("dst_port", String.valueOf(dstPort));

        JsonNode root = post(cfg, "connectRtpServer", form);

        if (root == null || root.path("code").asInt(-1) != 0) {

            log.error("ZLM connectRtpServer 失败 {}:{} stream={} msg={}", dstIp, dstPort, streamId, msg(root));

            return false;

        }

        log.info("ZLM connectRtpServer 成功 {}:{} stream={}", dstIp, dstPort, streamId);

        return true;

    }



    public void closeRtpServer(ZlmProperties cfg, String streamId) {

        if (!isConfigured(cfg)) {

            return;

        }

        MultiValueMap<String, String> form = baseForm(cfg);

        form.add("app", RTP_APP);

        form.add("stream_id", streamId);

        post(cfg, "closeRtpServer", form);

    }



    /** 国标 RTP 是否已进入 ZLM（RTSP 已注册或 RTP 进程已建立）。 */

    public boolean isRtpStreamOnline(ZlmProperties cfg, String streamId) {

        if (isMediaOnline(cfg, streamId, "rtsp")) {

            return true;

        }

        return isRtpProcessActive(cfg, streamId);

    }



    public boolean isMediaOnline(ZlmProperties cfg, String streamId, String schema) {

        if (!StringUtils.hasText(schema)) {

            return false;

        }

        MultiValueMap<String, String> form = baseForm(cfg);

        form.add("app", RTP_APP);

        form.add("stream", streamId);

        form.add("vhost", DEFAULT_VHOST);

        form.add("schema", schema);

        JsonNode root = post(cfg, "isMediaOnline", form);

        if (root == null || root.path("code").asInt(-1) != 0) {

            return false;

        }

        return root.path("online").asBoolean(false);

    }



    public boolean isRtpProcessActive(ZlmProperties cfg, String streamId) {

        MultiValueMap<String, String> form = baseForm(cfg);

        form.add("app", RTP_APP);

        form.add("stream_id", streamId);

        JsonNode root = post(cfg, "getRtpInfo", form);

        return root != null

                && root.path("code").asInt(-1) == 0

                && root.path("exist").asBoolean(false);

    }



    public String rtspPlayUrl(ZlmProperties cfg, String streamId) {
        String host = StringUtils.hasText(cfg.getSdpIp()) ? cfg.getSdpIp().trim() : cfg.getHttpHost().trim();
        return rtspPlayUrl(cfg, streamId, host);
    }

    /** @param playHost 对外播放 IP，应与网页 media-host / ZLM sdp-ip 一致 */
    public String rtspPlayUrl(ZlmProperties cfg, String streamId, String playHost) {
        String host = StringUtils.hasText(playHost) ? playHost.trim() : cfg.getHttpHost().trim();
        int rtspPort = cfg.getRtspPort() > 0 ? cfg.getRtspPort() : 554;
        return String.format("rtsp://%s:%d/%s/%s", host, rtspPort, RTP_APP, streamId);
    }

    /**
     * wlw 本机从 ZLM 拉流预览（HTTP-FLV），避免 RTSP 在 www/SELinux 下 Permission denied。
     */
    public String httpFlvPlayUrl(ZlmProperties cfg, String streamId, String playHost) {
        String host = StringUtils.hasText(playHost) ? playHost.trim() : cfg.getHttpHost().trim();
        int httpPort = cfg.getHttpPort() > 0 ? cfg.getHttpPort() : 80;
        return String.format("http://%s:%d/%s/%s.live.flv", host, httpPort, RTP_APP, streamId);
    }



    private MultiValueMap<String, String> baseForm(ZlmProperties cfg) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

        form.add("secret", cfg.getSecret().trim());

        return form;

    }



    private JsonNode post(ZlmProperties cfg, String api, MultiValueMap<String, String> form) {

        String url = String.format("http://%s:%d/index/api/%s",

                cfg.getHttpHost().trim(), cfg.getHttpPort(), api);

        try {

            HttpHeaders headers = new HttpHeaders();

            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = restTemplate.postForObject(url, new HttpEntity<>(form, headers), String.class);

            if (body == null || body.isEmpty()) {

                return null;

            }

            return objectMapper.readTree(body);

        } catch (Exception e) {

            log.error("ZLM API {} 请求失败 url={} err={}", api, url, e.getMessage());

            return null;

        }

    }



    private static String msg(JsonNode root) {

        return root != null ? root.path("msg").asText("") : "";

    }

}


