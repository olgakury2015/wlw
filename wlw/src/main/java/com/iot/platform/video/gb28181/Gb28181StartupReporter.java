package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import com.iot.platform.video.zlm.ZlmProperties;
import com.iot.platform.video.zlm.ZlmRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@org.springframework.context.annotation.DependsOn("gb28181DatabaseMigrator")
@RequiredArgsConstructor
public class Gb28181StartupReporter implements ApplicationRunner {

    private final Gb28181SipServerService sipServerService;
    private final Gb28181PlatformConfigService platformConfigService;
    private final IotProperties iotProperties;
    private final ZlmRestClient zlmRestClient;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String exe = Gb28181FfmpegPaths.resolveExecutable(iotProperties.getVideo());
            log.info("GB28181 已预热 FFmpeg: {}", exe);
        } catch (Exception e) {
            log.warn("GB28181 FFmpeg 预热失败: {}", e.toString());
        }
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        log.info("======== GB28181 启动检查 enabled={} sipRunning={} mediaHost={} sipId={} ========",
                cfg.isEnabled(),
                sipServerService.isRunning(),
                platformConfigService.effectiveMediaHost(cfg),
                cfg.getSipId());
        if (cfg.isEnabled() && !sipServerService.isRunning()) {
            String err = sipServerService.getLastStartupError();
            log.error("国标已启用但 SIP 未运行。原因: {}。请向上滚动查看「GB/T 28181 SIP 启动失败」；"
                    + "常见为 UDP 5060 被占用，执行 netstat -ano | findstr :5060 后结束旧进程。", err);
        }
        if (cfg.isEnabled() && sipServerService.isRunning()) {
            java.util.List<String> localIps = Gb28181NetUtil.listLocalIpv4Addresses();
            log.info("GB28181 本机 IPv4={} media-host={} 是否在网卡上={}",
                    localIps,
                    cfg.getMediaHost(),
                    Gb28181NetUtil.isLocalIpv4(cfg.getMediaHost()));
            if (!Gb28181NetUtil.isLocalIpv4(cfg.getMediaHost())) {
                log.error("GB28181 media-host「{}」不在本机网卡上！摄像机无法注册。请改为本机 IP 之一：{}",
                        cfg.getMediaHost(), localIps);
            }
            log.info("GB28181 UDP 绑定 {}:{}（摄像机 SIP 服务器地址须为 media-host {}）",
                    sipServerService.getSipListenHost(), cfg.getPort(), platformConfigService.effectiveMediaHost(cfg));
            log.warn("GB28181 已启用：海康若同时开国标+RTSP，RTSP 可能 500/黑屏。仅测 RTSP 时请关闭本页「启用国标 SIP」并在摄像机侧关闭国标");
        }
        if (iotProperties.getVideo().isGb28181UseZlm()) {
            ZlmProperties zlm = iotProperties.getVideo().getZlm();
            if (!zlmRestClient.isConfigured(zlm)) {
                log.error("GB28181 已启用 ZLM 方案，但 iot.video.zlm 未配置 secret/http-host");
            } else if (!zlmRestClient.isApiReachable(zlm)) {
                log.error("GB28181 ZLM HTTP API 校验失败 {}（[http] port={}）。"
                                + "常见原因：① 修改 config.ini 的 secret 后未重启 MediaServer（进程内密钥与文件不一致）；"
                                + "② 同时运行了多个 MediaServer.exe；③ secret 仍为官方默认 1925cc 时 ZLM 会随机生成新密钥。"
                                + "处理：结束全部 MediaServer → 确认 config.ini [api] secret 与 yml 一致 → 只启动一个实例。"
                                + "测试：POST {}/getServerConfig 表单 secret=你的密钥，应返回 code=0",
                        zlmRestClient.apiBaseUrl(zlm), zlm.getHttpPort(), zlmRestClient.apiBaseUrl(zlm));
            } else {
                String bindIp = platformConfigService.effectiveZlmBindIp(zlm);
                log.info("GB28181+ZLM 已连通 api={} sdp-ip={} bind-ip={} rtsp-port={}",
                        zlmRestClient.apiBaseUrl(zlm),
                        platformConfigService.effectiveZlmSdpIp(zlm),
                        bindIp != null ? bindIp : "0.0.0.0",
                        zlm.getRtspPort());
            }
        }
    }
}
