package com.iot.platform.controller.console;

import com.iot.platform.alert.service.AlertChannelService;
import com.iot.platform.ops.service.AuditLogService;
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
public class ConsoleAlertNotifyController {

    private final AlertChannelService alertChannelService;
    private final AuditLogService auditLogService;

    @GetMapping("/alerts/notify")
    public String notifyPage(Model model) {
        model.addAttribute("active", "alert-notify");
        model.addAttribute("breadcrumb", "首页 / 监控与告警 / 告警通知");
        model.addAttribute("channel", alertChannelService.getOrEmpty());
        return "alert-notify";
    }

    @PostMapping("/alerts/notify")
    @PreAuthorize("hasRole('ADMIN')")
    public String saveNotify(@RequestParam(required = false) String fallbackWebhook, RedirectAttributes ra) {
        alertChannelService.saveFallbackWebhook(fallbackWebhook);
        auditLogService.log("ALERT_NOTIFY_CONFIG", "更新全局告警 Webhook");
        ra.addFlashAttribute("msg", "已保存全局通知 Webhook（钉钉机器人等）。单条规则上也可单独填写地址，优先于此处。");
        return "redirect:/alerts/notify";
    }
}
