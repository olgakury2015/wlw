package com.iot.platform.management.controller;

import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.management.repo.SceneRuleRepository;
import com.iot.platform.management.repo.ThingModelRepository;
import com.iot.platform.service.TelemetryHub;
import com.iot.platform.service.telemetry.TelemetryPresentationService;
import com.iot.platform.websocket.IotWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class ConsoleVizController {

    private final DeviceRepository deviceRepository;
    private final ThingModelRepository thingModelRepository;
    private final SceneRuleRepository sceneRuleRepository;
    private final TelemetryHub telemetryHub;
    private final TelemetryPresentationService telemetryPresentationService;
    private final IotWebSocketHandler webSocketHandler;

    @GetMapping("/viz")
    public String viz(Model model) {
        long totalDevices = deviceRepository.count();
        long onlineDevices = deviceRepository.countByStatus("ONLINE");
        double onlineRate = totalDevices == 0 ? 0 : Math.round(1000.0 * onlineDevices / totalDevices) / 10.0;

        model.addAttribute("active", "viz");
        model.addAttribute("breadcrumb", "首页 / 监控与告警 / 可视化仪表板");
        model.addAttribute("totalDevices", totalDevices);
        model.addAttribute("onlineDevices", onlineDevices);
        model.addAttribute("onlineRate", onlineRate);
        model.addAttribute("thingModelCount", thingModelRepository.count());
        model.addAttribute("sceneRuleCount", sceneRuleRepository.count());
        model.addAttribute("wsSessions", webSocketHandler.getSessionCount());
        model.addAttribute("recentDisplay", telemetryPresentationService.formatRecent(telemetryHub.recentSnapshot()));
        return "viz";
    }
}
