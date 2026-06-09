package com.iot.platform.management.controller;

import com.iot.platform.config.IotProperties;
import com.iot.platform.gateway.GatewayProfileCatalog;
import com.iot.platform.gateway.GatewaySerialMode;
import com.iot.platform.gateway.GatewayUplinkProtocol;
import com.iot.platform.gateway.GatewayVendor;
import com.iot.platform.management.entity.IotGateway;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.management.service.GatewayRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ConsoleGatewayController {

    private final GatewayRegistryService gatewayRegistryService;
    private final DeviceRepository deviceRepository;
    private final IotProperties iotProperties;

    @GetMapping("/gateways/{id}")
    public String detail(@PathVariable Long id, Model model) {
        IotGateway g = gatewayRegistryService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("网关不存在"));
        model.addAttribute("gateway", g);
        model.addAttribute("childDevices", deviceRepository.findByGateway_Id(id));
        model.addAttribute("setupSteps", GatewayProfileCatalog.buildSetupSteps(g,
                iotProperties.getTcp().getPort(), "/api/v1/http/ingest"));
        model.addAttribute("platformTcpPort", iotProperties.getTcp().getPort());
        model.addAttribute("active", "devices");
        model.addAttribute("breadcrumb", "首页 / 设备管理 / 网关详情");
        return "gateway-detail";
    }

    @GetMapping("/gateways/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editForm(@PathVariable Long id, Model model) {
        IotGateway g = gatewayRegistryService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("网关不存在"));
        model.addAttribute("gateway", g);
        addFormOptions(model);
        model.addAttribute("platformTcpPort", iotProperties.getTcp().getPort());
        model.addAttribute("active", "devices");
        model.addAttribute("breadcrumb", "首页 / 设备管理 / 修改网关");
        return "gateway-edit";
    }

    @PostMapping("/gateways")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String name,
                         @RequestParam String gatewaySn,
                         @RequestParam(required = false, defaultValue = "USR_G781") String vendorModel,
                         @RequestParam(required = false, defaultValue = "TCP_CLIENT") String uplinkProtocol,
                         @RequestParam(required = false, defaultValue = "NET_TRANSPARENT") String serialMode,
                         @RequestParam(required = false) String remoteHost,
                         @RequestParam(required = false) String remotePort,
                         @RequestParam(required = false) String mqttRemoteHost,
                         @RequestParam(required = false) String mqttRemotePort,
                         @RequestParam(required = false) String mqttSubscribeTopic,
                         @RequestParam(required = false) String mqttUsername,
                         @RequestParam(required = false) String mqttPassword,
                         @RequestParam(required = false) String registerPacket,
                         @RequestParam(required = false) String heartbeatPacket,
                         @RequestParam(required = false) String locationAddress,
                         @RequestParam(required = false) String remark,
                         RedirectAttributes ra) {
        try {
            IotGateway g = gatewayRegistryService.create(name, gatewaySn, vendorModel, uplinkProtocol, serialMode,
                    remoteHost, remotePort, mqttRemoteHost, mqttRemotePort, mqttSubscribeTopic,
                    mqttUsername, mqttPassword, registerPacket, heartbeatPacket, locationAddress, remark);
            ra.addFlashAttribute("msg", "网关已添加，请按详情页说明配置 USR-G781 / DTU。");
            return "redirect:/gateways/" + g.getId();
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
            return "redirect:/devices";
        }
    }

    @PostMapping("/gateways/{id}/update")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false, defaultValue = "GENERIC") String vendorModel,
                         @RequestParam(required = false, defaultValue = "TCP_CLIENT") String uplinkProtocol,
                         @RequestParam(required = false, defaultValue = "NET_TRANSPARENT") String serialMode,
                         @RequestParam(required = false) String remoteHost,
                         @RequestParam(required = false) String remotePort,
                         @RequestParam(required = false) String mqttRemoteHost,
                         @RequestParam(required = false) String mqttRemotePort,
                         @RequestParam(required = false) String mqttSubscribeTopic,
                         @RequestParam(required = false) String mqttUsername,
                         @RequestParam(required = false) String mqttPassword,
                         @RequestParam(required = false) String registerPacket,
                         @RequestParam(required = false) String heartbeatPacket,
                         @RequestParam(required = false) String locationAddress,
                         @RequestParam(required = false) String remark,
                         RedirectAttributes ra) {
        try {
            gatewayRegistryService.update(id, name, vendorModel, uplinkProtocol, serialMode,
                    remoteHost, remotePort, mqttRemoteHost, mqttRemotePort, mqttSubscribeTopic,
                    mqttUsername, mqttPassword, registerPacket, heartbeatPacket, locationAddress, remark);
            ra.addFlashAttribute("msg", "网关已更新。");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/gateways/" + id;
    }

    @PostMapping("/gateways/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            gatewayRegistryService.delete(id);
            ra.addFlashAttribute("msg", "网关已删除。");
        } catch (Exception ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/devices";
    }

    static void addFormOptions(Model model) {
        model.addAttribute("gatewayVendors", Arrays.stream(GatewayVendor.values()).collect(Collectors.toList()));
        model.addAttribute("gatewayUplinks", Arrays.stream(GatewayUplinkProtocol.values()).collect(Collectors.toList()));
        model.addAttribute("gatewaySerialModes", Arrays.stream(GatewaySerialMode.values()).collect(Collectors.toList()));
    }
}
