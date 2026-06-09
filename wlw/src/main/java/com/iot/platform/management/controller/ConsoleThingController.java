package com.iot.platform.management.controller;

import com.iot.platform.management.service.ThingModelService;
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
public class ConsoleThingController {

    private final ThingModelService thingModelService;

    @GetMapping("/things")
    public String list(Model model) {
        model.addAttribute("models", thingModelService.listAll());
        model.addAttribute("active", "things");
        model.addAttribute("breadcrumb", "首页 / 物模型");
        model.addAttribute("defaultJson", thingModelService.defaultPropertiesTemplate());
        return "things";
    }

    @PostMapping("/things")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String code,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String propertiesJson,
                         RedirectAttributes ra) {
        try {
            thingModelService.create(code, name, description, propertiesJson);
            ra.addFlashAttribute("msg", "物模型已保存");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/things";
    }

    @PostMapping("/things/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            thingModelService.delete(id);
            ra.addFlashAttribute("msg", "已删除物模型");
        } catch (Exception ex) {
            ra.addFlashAttribute("error", "删除失败：" + ex.getMessage());
        }
        return "redirect:/things";
    }
}
