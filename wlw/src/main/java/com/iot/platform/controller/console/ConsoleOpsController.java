package com.iot.platform.controller.console;

import com.iot.platform.ops.service.ApiKeyService;
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
public class ConsoleOpsController {

    private final AuditLogService auditLogService;
    private final ApiKeyService apiKeyService;

    @GetMapping("/ops/audit-logs")
    public String auditLogs(Model model) {
        model.addAttribute("active", "ops-audit");
        model.addAttribute("breadcrumb", "首页 / 运维与安全 / 操作日志");
        model.addAttribute("logs", auditLogService.recent(200));
        return "ops-audit-logs";
    }

    @GetMapping("/ops/api-keys")
    public String apiKeys(Model model) {
        model.addAttribute("active", "ops-api-keys");
        model.addAttribute("breadcrumb", "首页 / 运维与安全 / API 密钥");
        model.addAttribute("keys", apiKeyService.listAll());
        return "ops-api-keys";
    }

    @PostMapping("/ops/api-keys")
    @PreAuthorize("hasRole('ADMIN')")
    public String createKey(@RequestParam String label, RedirectAttributes ra) {
        String secret = apiKeyService.createKey(label);
        ra.addFlashAttribute("msg", "密钥已创建，请立即复制保存（仅显示一次）：");
        ra.addFlashAttribute("newApiSecret", secret);
        return "redirect:/ops/api-keys";
    }

    @PostMapping("/ops/api-keys/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public String disableKey(@RequestParam Long id, RedirectAttributes ra) {
        apiKeyService.disable(id);
        ra.addFlashAttribute("msg", "已禁用该密钥");
        return "redirect:/ops/api-keys";
    }

    @GetMapping("/ops/api-doc")
    public String apiDoc(Model model) {
        model.addAttribute("active", "ops-api-doc");
        model.addAttribute("breadcrumb", "首页 / 运维与安全 / API 开放说明");
        return "ops-api-doc";
    }
}
