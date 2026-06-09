package com.iot.platform.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.IotProperties;
import com.iot.platform.config.MapViewAttributes;
import com.iot.platform.config.NodeRedProperties;
import com.iot.platform.config.UiProperties;
import com.iot.platform.dto.DeviceMapPinDto;
import com.iot.platform.management.entity.Device;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.management.repo.SceneRuleRepository;
import com.iot.platform.management.repo.ThingModelRepository;
import com.iot.platform.service.TelemetryHub;
import com.iot.platform.service.telemetry.TelemetryPresentationService;
import com.iot.platform.websocket.IotWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final ObjectMapper objectMapper;
    private final TelemetryHub telemetryHub;
    private final IotProperties iotProperties;
    private final NodeRedProperties nodeRedProperties;
    private final IotWebSocketHandler webSocketHandler;
    private final DeviceRepository deviceRepository;
    private final ThingModelRepository thingModelRepository;
    private final SceneRuleRepository sceneRuleRepository;
    private final TelemetryPresentationService telemetryPresentationService;
    private final UiProperties uiProperties;
    private final MapViewAttributes mapViewAttributes;

    @Value("${iot.device-tcp.poll-interval-ms:30000}")
    private long tcpPullPollIntervalMs;

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("recent", telemetryHub.recentSnapshot());
        model.addAttribute("recentDisplay", telemetryPresentationService.formatRecent(telemetryHub.recentSnapshot()));
        model.addAttribute("tcpPort", iotProperties.getTcp().getPort());
        model.addAttribute("mqttEnabled", iotProperties.getMqtt().isEnabled());
        model.addAttribute("wsSessions", webSocketHandler.getSessionCount());
        model.addAttribute("tcpPullPollSeconds", Math.max(1L, tcpPullPollIntervalMs / 1000L));
        long totalDev = deviceRepository.count();
        long onlineDev = deviceRepository.countByStatus("ONLINE");
        long offlineDev = Math.max(0, totalDev - onlineDev);
        model.addAttribute("deviceCount", totalDev);
        model.addAttribute("thingModelCount", thingModelRepository.count());
        model.addAttribute("ruleCount", sceneRuleRepository.count());
        model.addAttribute("active", "home");
        model.addAttribute("breadcrumb", "首页");
        model.addAttribute("pageAutoRefreshSeconds", uiProperties.getPageAutoRefreshSeconds());
        try {
            Map<String, Object> doughnut = new LinkedHashMap<>();
            doughnut.put("labels", Arrays.asList("在线", "离线"));
            doughnut.put("data", Arrays.asList(onlineDev, offlineDev));
            model.addAttribute("homeDoughnutJson", objectMapper.writeValueAsString(doughnut));
            Map<String, Object> bar = new LinkedHashMap<>();
            bar.put("labels", Arrays.asList("设备", "物模型", "联动说明"));
            bar.put("data", Arrays.asList(totalDev, thingModelRepository.count(), sceneRuleRepository.count()));
            model.addAttribute("homeBarJson", objectMapper.writeValueAsString(bar));
        } catch (JsonProcessingException e) {
            model.addAttribute("homeDoughnutJson", "{\"labels\":[],\"data\":[]}");
            model.addAttribute("homeBarJson", "{\"labels\":[],\"data\":[]}");
        }
        List<DeviceMapPinDto> mapPins = deviceRepository.findByLatitudeIsNotNullAndLongitudeIsNotNull().stream()
                .map(PageController::toMapPin)
                .collect(Collectors.toList());
        try {
            model.addAttribute("mapPinsJson", objectMapper.writeValueAsString(mapPins));
        } catch (JsonProcessingException e) {
            model.addAttribute("mapPinsJson", "[]");
        }
        mapViewAttributes.apply(model);
        return "index";
    }

    private static DeviceMapPinDto toMapPin(Device d) {
        String addr = d.getLocationAddress() != null ? d.getLocationAddress() : "";
        return new DeviceMapPinDto(
                d.getName() != null ? d.getName() : "",
                d.getDeviceSn() != null ? d.getDeviceSn() : "",
                d.getLatitude(),
                d.getLongitude(),
                d.getStatus() != null ? d.getStatus() : "OFFLINE",
                addr,
                Boolean.TRUE.equals(d.getLocationCoordGcj02()));
    }

    @GetMapping("/protocols")
    public String protocols(Model model) {
        model.addAttribute("tcp", iotProperties.getTcp());
        model.addAttribute("mqtt", iotProperties.getMqtt());
        model.addAttribute("modbusTcp", iotProperties.getModbusTcp());
        model.addAttribute("modbusRtu", iotProperties.getModbusRtu());
        model.addAttribute("active", "protocols");
        model.addAttribute("breadcrumb", "首页 / 协议接入");
        return "protocols";
    }

    @GetMapping("/nodered")
    public String nodered(Model model) {
        model.addAttribute("nodeRed", nodeRedProperties);
        model.addAttribute("active", "nodered");
        model.addAttribute("breadcrumb", "首页 / Node-RED");
        return "nodered";
    }
}
