package com.iot.platform.controller.console;

import com.iot.platform.identity.entity.ConsoleAccount;
import com.iot.platform.identity.repo.ConsoleAccountRepository;
import com.iot.platform.ops.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class ConsoleAdminAccountController {

    private final ConsoleAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @GetMapping("/admin/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public String list(Model model) {
        model.addAttribute("active", "admin-accounts");
        model.addAttribute("breadcrumb", "首页 / 用户与权限 / 账号管理");
        List<ConsoleAccount> all = accountRepository.findAll();
        model.addAttribute("accounts", all);
        return "admin-accounts";
    }

    @PostMapping("/admin/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String username,
                         @RequestParam String password,
                         @RequestParam(defaultValue = "USER") String role,
                         RedirectAttributes ra) {
        try {
            if (username == null || username.trim().isEmpty()) {
                throw new IllegalArgumentException("用户名不能为空");
            }
            if (password == null || password.length() < 6) {
                throw new IllegalArgumentException("密码至少 6 位");
            }
            if (accountRepository.findByUsername(username.trim()).isPresent()) {
                throw new IllegalArgumentException("用户名已存在");
            }
            String r = role != null && role.toUpperCase().contains("ADMIN") ? "ADMIN" : "USER";
            ConsoleAccount a = new ConsoleAccount();
            a.setUsername(username.trim());
            a.setPasswordHash(passwordEncoder.encode(password));
            a.setRole(r);
            accountRepository.save(a);
            auditLogService.log("ACCOUNT_CREATE", "新建控制台账号 " + a.getUsername() + " 角色 " + a.getRole());
            ra.addFlashAttribute("msg", "账号已创建");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/accounts";
    }
}
