package com.iot.platform.config;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 全站顶栏等公共展示：当前日期（中文）。
 */
@ControllerAdvice
public class GlobalModelAttributeAdvice {

    private static final DateTimeFormatter CN_DATE =
            DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA);

    @ModelAttribute
    public void addCommonModelAttributes(Model model) {
        model.addAttribute("todayDisplay", LocalDate.now().format(CN_DATE));
    }
}
