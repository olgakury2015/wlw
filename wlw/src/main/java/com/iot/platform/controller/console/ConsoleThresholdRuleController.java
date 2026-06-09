package com.iot.platform.controller.console;

import com.iot.platform.alert.service.ThresholdRuleService;
import lombok.RequiredArgsConstructor;
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
public class ConsoleThresholdRuleController {

    private final ThresholdRuleService thresholdRuleService;

    @GetMapping("/alerts/rules")
    public String alertRules(Model model) {
        model.addAttribute("active", "alert-rules");
        model.addAttribute("breadcrumb", "首页 / 监控与告警 / 告警规则");
        model.addAttribute("kind", "ALERT");
        model.addAttribute("rules", thresholdRuleService.listByKind("ALERT"));
        return "threshold-rules";
    }

    @GetMapping("/scenes/linkage")
    public String linkageRules(Model model) {
        model.addAttribute("active", "scene-linkage");
        model.addAttribute("breadcrumb", "首页 / 监控与告警 / 场景联动");
        model.addAttribute("kind", "LINKAGE");
        model.addAttribute("rules", thresholdRuleService.listByKind("LINKAGE"));
        return "threshold-rules";
    }

    @PostMapping("/alerts/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public String createAlert(@RequestParam String name,
                              @RequestParam(required = false) String deviceSn,
                              @RequestParam String metricKey,
                              @RequestParam(defaultValue = "GT") String operator,
                              @RequestParam double threshold,
                              @RequestParam(required = false) Boolean enabled,
                              @RequestParam(required = false) String webhookUrl,
                              RedirectAttributes ra) {
        return createInternal("ALERT", name, deviceSn, metricKey, operator, threshold, enabled, webhookUrl, ra, "redirect:/alerts/rules");
    }

    @PostMapping("/scenes/linkage")
    @PreAuthorize("hasRole('ADMIN')")
    public String createLinkage(@RequestParam String name,
                                @RequestParam(required = false) String deviceSn,
                                @RequestParam String metricKey,
                                @RequestParam(defaultValue = "LT") String operator,
                                @RequestParam double threshold,
                                @RequestParam(required = false) Boolean enabled,
                                @RequestParam(required = false) String webhookUrl,
                                RedirectAttributes ra) {
        return createInternal("LINKAGE", name, deviceSn, metricKey, operator, threshold, enabled, webhookUrl, ra, "redirect:/scenes/linkage");
    }

    private String createInternal(String kind, String name, String deviceSn, String metricKey, String operator,
                                  double threshold, Boolean enabled, String webhookUrl, RedirectAttributes ra, String redirect) {
        try {
            boolean on = enabled == null || enabled;
            thresholdRuleService.create(name, kind, deviceSn, metricKey, operator, threshold, on, webhookUrl);
            ra.addFlashAttribute("msg", "规则已保存");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return redirect;
    }

    @PostMapping("/alerts/rules/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteAlert(@PathVariable Long id, RedirectAttributes ra) {
        thresholdRuleService.delete(id);
        ra.addFlashAttribute("msg", "已删除");
        return "redirect:/alerts/rules";
    }

    @PostMapping("/scenes/linkage/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteLinkage(@PathVariable Long id, RedirectAttributes ra) {
        thresholdRuleService.delete(id);
        ra.addFlashAttribute("msg", "已删除");
        return "redirect:/scenes/linkage";
    }
}
