package com.iot.platform.management.controller;

import com.iot.platform.management.service.DeviceCategoryService;
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
public class ConsoleDeviceCategoryController {

    private final DeviceCategoryService deviceCategoryService;

    @GetMapping("/device-categories")
    public String list(Model model) {
        model.addAttribute("active", "device-categories");
        model.addAttribute("breadcrumb", "首页 / 设备管理 / 设备分类");
        model.addAttribute("categories", deviceCategoryService.listAll());
        return "device-categories";
    }

    @PostMapping("/device-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String code,
                         @RequestParam(required = false) Integer sortOrder,
                         RedirectAttributes ra) {
        try {
            deviceCategoryService.create(name, code, sortOrder);
            ra.addFlashAttribute("msg", "分类已创建");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/device-categories";
    }

    @PostMapping("/device-categories/{id}/update")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String code,
                         @RequestParam(required = false) Integer sortOrder,
                         RedirectAttributes ra) {
        try {
            deviceCategoryService.update(id, name, code, sortOrder);
            ra.addFlashAttribute("msg", "分类已更新");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/device-categories";
    }

    @PostMapping("/device-categories/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            deviceCategoryService.delete(id);
            ra.addFlashAttribute("msg", "已删除分类（相关设备的分类字段已清空）");
        } catch (Exception ex) {
            ra.addFlashAttribute("error", "删除失败：" + ex.getMessage());
        }
        return "redirect:/device-categories";
    }
}
