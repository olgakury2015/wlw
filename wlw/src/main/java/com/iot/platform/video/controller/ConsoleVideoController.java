package com.iot.platform.video.controller;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.entity.CameraChannel;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import com.iot.platform.video.service.CameraChannelService;
import com.iot.platform.video.service.OnvifRtspResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class ConsoleVideoController {

    private final CameraChannelService cameraChannelService;
    private final OnvifRtspResolverService onvifRtspResolverService;
    private final Gb28181PlatformConfigService gb28181PlatformConfigService;
    private final IotProperties iotProperties;

    @GetMapping("/video")
    public String videoCenter(Model model) {
        model.addAttribute("channels", cameraChannelService.listEnabledForMonitor());
        model.addAttribute("videoDecoder", iotProperties.getVideo().getDecoder());
        model.addAttribute("active", "video-center");
        model.addAttribute("breadcrumb", "首页 / 视频中心");
        return "video-center";
    }

    @GetMapping("/video/channels")
    @PreAuthorize("hasRole('ADMIN')")
    public String channelAdmin(Model model) {
        model.addAttribute("channels", cameraChannelService.listAll());
        model.addAttribute("active", "video-channels");
        model.addAttribute("breadcrumb", "首页 / 视频中心 / 通道配置");
        return "video-channels";
    }

    @PostMapping("/video/channels")
    @PreAuthorize("hasRole('ADMIN')")
    public String channelCreate(@RequestParam String name,
                                @RequestParam(required = false, defaultValue = "RTSP") String sourceType,
                                @RequestParam(required = false) String rtspUrl,
                                @RequestParam(required = false) String onvifDeviceServiceUrl,
                                @RequestParam(required = false) String onvifUsername,
                                @RequestParam(required = false) String onvifPassword,
                                @RequestParam(required = false) String gb28181DeviceId,
                                @RequestParam(required = false) String gb28181ChannelId,
                                @RequestParam(required = false) String gb28181Password,
                                @RequestParam(required = false) Integer gb28181StreamIndex,
                                @RequestParam(required = false) String enabled,
                                @RequestParam(required = false) Integer sortOrder,
                                @RequestParam(required = false) String remark,
                                RedirectAttributes ra) {
        try {
            boolean en = "true".equalsIgnoreCase(enabled) || "on".equalsIgnoreCase(enabled);
            cameraChannelService.create(name, sourceType, rtspUrl, onvifDeviceServiceUrl, onvifUsername, onvifPassword,
                    gb28181DeviceId, gb28181ChannelId, gb28181Password, gb28181StreamIndex, en, sortOrder, remark);
            ra.addFlashAttribute("msg", "通道已保存");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/video/channels";
    }

    @PostMapping("/video/channels/{id}/update")
    @PreAuthorize("hasRole('ADMIN')")
    public String channelUpdate(@PathVariable Long id,
                                @RequestParam String name,
                                @RequestParam(required = false, defaultValue = "RTSP") String sourceType,
                                @RequestParam(required = false) String rtspUrl,
                                @RequestParam(required = false) String onvifDeviceServiceUrl,
                                @RequestParam(required = false) String onvifUsername,
                                @RequestParam(required = false) String onvifPassword,
                                @RequestParam(required = false) String gb28181DeviceId,
                                @RequestParam(required = false) String gb28181ChannelId,
                                @RequestParam(required = false) String gb28181Password,
                                @RequestParam(required = false) Integer gb28181StreamIndex,
                                @RequestParam(required = false) String enabled,
                                @RequestParam(required = false) Integer sortOrder,
                                @RequestParam(required = false) String remark,
                                @RequestParam(required = false) String clearGb28181Password,
                                RedirectAttributes ra) {
        try {
            boolean en = "true".equalsIgnoreCase(enabled) || "on".equalsIgnoreCase(enabled);
            cameraChannelService.update(id, name, sourceType, rtspUrl, onvifDeviceServiceUrl, onvifUsername, onvifPassword,
                    gb28181DeviceId, gb28181ChannelId, gb28181Password, gb28181StreamIndex, en, sortOrder, remark,
                    clearGb28181Password);
            ra.addFlashAttribute("msg", "通道已更新");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/video/channels";
    }

    @PostMapping("/video/channels/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public String channelSetEnabled(@PathVariable Long id,
                                    @RequestParam boolean enabled,
                                    RedirectAttributes ra) {
        try {
            cameraChannelService.setEnabled(id, enabled);
            ra.addFlashAttribute("msg", enabled ? "通道已启用" : "通道已停用，拉流已断开");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/video/channels";
    }

    @PostMapping("/video/channels/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String channelDelete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            cameraChannelService.delete(id);
            ra.addFlashAttribute("msg", "已删除通道");
        } catch (Exception ex) {
            ra.addFlashAttribute("error", "删除失败：" + ex.getMessage());
        }
        return "redirect:/video/channels";
    }

    /**
     * 仅解析 ONVIF 得到 RTSP，不落库；前端可将结果填入 RTSP 字段。
     */
    @PostMapping("/video/channels/onvif-resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public String onvifResolve(@RequestParam String onvifDeviceServiceUrl,
                               @RequestParam String onvifUsername,
                               @RequestParam(required = false) String onvifPassword,
                               RedirectAttributes ra) {
        try {
            String rtsp = onvifRtspResolverService.resolveRtspUrl(onvifDeviceServiceUrl, onvifUsername, onvifPassword);
            ra.addFlashAttribute("msg", "ONVIF 解析成功，已将 RTSP 填入下方新建表单（请核对后保存）。");
            ra.addFlashAttribute("prefillRtsp", rtsp);
            ra.addFlashAttribute("prefillOnvifUrl", onvifDeviceServiceUrl.trim());
            ra.addFlashAttribute("prefillOnvifUser", onvifUsername.trim());
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/video/channels";
    }

    @GetMapping("/video/channels/edit/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String channelEditForm(@PathVariable Long id, Model model) {
        CameraChannel ch = cameraChannelService.require(id);
        model.addAttribute("ch", ch);
        String platformPwd = gb28181PlatformConfigService.getOrCreate().getDevicePassword();
        model.addAttribute("platformGbPassword", platformPwd != null ? platformPwd : "");
        if ("GB28181".equalsIgnoreCase(ch.getSourceType()) && StringUtils.hasText(ch.getGb28181DeviceId())) {
            model.addAttribute("gb28181EffectivePassword",
                    gb28181PlatformConfigService.resolveDevicePassword(ch.getGb28181DeviceId()));
        }
        model.addAttribute("active", "video-channel-edit");
        model.addAttribute("breadcrumb", "首页 / 视频中心 / 编辑通道");
        return "video-channel-edit";
    }
}
