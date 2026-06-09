package com.iot.platform.video.controller;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.gb28181.Gb28181DeviceRegistry;
import com.iot.platform.video.gb28181.Gb28181NetUtil;
import com.iot.platform.video.gb28181.Gb28181PlayManager;
import com.iot.platform.video.gb28181.Gb28181SipServerService;
import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class Gb28181ConsoleController {

    private final Gb28181PlatformConfigService platformConfigService;
    private final Gb28181DeviceRegistry deviceRegistry;
    private final Gb28181PlayManager playManager;
    private final Gb28181SipServerService sipServerService;
    private final IotProperties iotProperties;

    @GetMapping("/video/gb28181")
    @PreAuthorize("hasRole('ADMIN')")
    public String gb28181Admin(Model model) {
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        model.addAttribute("cfg", cfg);
        model.addAttribute("devices", deviceRegistry.listAll());
        model.addAttribute("sipRunning", sipServerService.isRunning());
        model.addAttribute("sipListenHost", sipServerService.getSipListenHost());
        model.addAttribute("sipUdpListening", sipServerService.isUdpListening());
        model.addAttribute("sipTcpListening", sipServerService.isTcpListening());
        model.addAttribute("registerReceivedCount", sipServerService.getRegisterReceivedCount());
        model.addAttribute("sipLastStartupError", sipServerService.getLastStartupError());
        model.addAttribute("lastRegisterAt", sipServerService.getLastRegisterAt());
        model.addAttribute("localIpv4List", Gb28181NetUtil.listLocalIpv4Addresses());
        model.addAttribute("mediaHostOnLocalNic", Gb28181NetUtil.isLocalIpv4(cfg.getMediaHost()));
        model.addAttribute("mediaHostEffective", playManager.mediaHost());
        model.addAttribute("gb28181UseZlm", iotProperties.getVideo().isGb28181UseZlm());
        if (iotProperties.getVideo().isGb28181UseZlm()) {
            model.addAttribute("zlmSdpIpEffective",
                    platformConfigService.effectiveZlmSdpIp(iotProperties.getVideo().getZlm()));
        }
        model.addAttribute("active", "video-gb28181");
        model.addAttribute("breadcrumb", "首页 / 视频中心 / 国标 28181");
        return "video-gb28181";
    }

    @PostMapping("/video/gb28181/save")
    @PreAuthorize("hasRole('ADMIN')")
    public String savePlatformConfig(
            @RequestParam(required = false) String enabled,
            @RequestParam String sipId,
            @RequestParam String sipDomain,
            @RequestParam String host,
            @RequestParam int port,
            @RequestParam(required = false) String mediaHost,
            @RequestParam(required = false) String devicePassword,
            @RequestParam(required = false) String clearDevicePassword,
            @RequestParam int mediaPortMin,
            @RequestParam int mediaPortMax,
            @RequestParam(defaultValue = "3600") int registerExpires,
            @RequestParam(defaultValue = "180") int keepaliveTimeoutSeconds,
            @RequestParam(required = false) String requireSipRegister,
            @RequestParam(defaultValue = "tcp_passive") String mediaTransport,
            @RequestParam(required = false) String remark,
            RedirectAttributes ra) {
        try {
            boolean en = "true".equalsIgnoreCase(enabled) || "on".equalsIgnoreCase(enabled);
            boolean clearPwd = "true".equalsIgnoreCase(clearDevicePassword) || "on".equalsIgnoreCase(clearDevicePassword);
            boolean reqReg = "true".equalsIgnoreCase(requireSipRegister) || "on".equalsIgnoreCase(requireSipRegister);
            platformConfigService.save(
                    en, sipId, sipDomain, host, port, mediaHost, devicePassword,
                    mediaPortMin, mediaPortMax, registerExpires, keepaliveTimeoutSeconds, reqReg,
                    mediaTransport, remark, clearPwd);
            sipServerService.restartFromDatabase();
            ra.addFlashAttribute("msg", "国标平台配置已保存，SIP 服务已按新配置重启");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        } catch (Exception ex) {
            ra.addFlashAttribute("error", "保存失败：" + ex.getMessage());
        }
        return "redirect:/video/gb28181";
    }
}
