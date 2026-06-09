package com.iot.platform.management.controller;

import com.iot.platform.management.service.SceneRuleService;
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
public class ConsoleRuleController {

    private final SceneRuleService sceneRuleService;

    @GetMapping("/rules")
    public String list(Model model) {
        model.addAttribute("rules", sceneRuleService.listAll());
        model.addAttribute("active", "rules");
        model.addAttribute("breadcrumb", "首页 / 监控与告警 / 联动说明");
        return "rules";
    }

    @PostMapping("/rules")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String triggerSummary,
                         @RequestParam(required = false) String actionSummary,
                         @RequestParam(required = false) Boolean enabled,
                         RedirectAttributes ra) {
        try {
            boolean on = enabled != null && enabled;
            sceneRuleService.create(name, triggerSummary, actionSummary, on);
            ra.addFlashAttribute("msg", "规则已保存（可与 Node-RED 编排联动）");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/rules";
    }

    @PostMapping("/rules/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            sceneRuleService.delete(id);
            ra.addFlashAttribute("msg", "已删除规则");
        } catch (Exception ex) {
            ra.addFlashAttribute("error", "删除失败：" + ex.getMessage());
        }
        return "redirect:/rules";
    }
}
