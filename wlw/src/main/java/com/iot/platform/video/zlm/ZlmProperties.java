package com.iot.platform.video.zlm;

import lombok.Data;

/** ZLMediaKit 连接参数（需单独部署 ZLM，与 wvp 相同）。 */
@Data
public class ZlmProperties {

    /** 启用后国标收流走 ZLM（推荐，与 wvp-GB28181-pro 一致） */
    private boolean enabled = false;
    private String httpHost = "127.0.0.1";
    private int httpPort = 80;
    private String secret = "";
    /**
     * 可选：MediaServer 同目录 config.ini，启动时同步 [api] secret 到本配置（须与运行中 ZLM 一致）。
     */
    private String configIni = "";
    /** SDP / 收流展示 IP，一般为平台 media-host */
    private String sdpIp = "";
    private int rtspPort = 554;
}
