package com.iot.platform.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "logout", required = false) String logout,
                        @RequestParam(value = "error", required = false) String error,
                        Model model) {
        model.addAttribute("active", "login");
        if (logout != null) {
            model.addAttribute("msg", "您已退出登录。");
        }
        if (error != null) {
            model.addAttribute("error", "用户名或密码错误。");
        }
        return "login";
    }
}
