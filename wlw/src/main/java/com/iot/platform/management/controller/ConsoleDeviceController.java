package com.iot.platform.management.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.IotProperties;
import com.iot.platform.config.MapViewAttributes;
import com.iot.platform.config.UiProperties;
import com.iot.platform.management.entity.Device;
import com.iot.platform.management.service.DeviceCategoryService;
import com.iot.platform.management.service.DeviceRegistryService;
import com.iot.platform.management.service.DeviceRegistryService.CreationResult;
import com.iot.platform.management.service.DeviceTcpPullService;
import com.iot.platform.management.service.GatewayRegistryService;
import com.iot.platform.management.controller.ConsoleGatewayController;
import com.iot.platform.protocol.mqtt.DeviceMqttSubscriptionManager;
import com.iot.platform.service.TelemetryHub;
import com.iot.platform.service.geocode.BundledChinaDistrictRepository;
import com.iot.platform.service.telemetry.TelemetryDisplayRow;
import com.iot.platform.service.telemetry.TelemetryPresentationService;
import com.iot.platform.telemetry.service.TelemetryTrendService;
import com.iot.platform.util.GnrmcLocationExtractor;
import com.iot.platform.util.GnrmcLocationExtractor.Wgs84Point;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class ConsoleDeviceController {

    private static final DateTimeFormatter EXPORT_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final DeviceRegistryService deviceRegistryService;
    private final DeviceCategoryService deviceCategoryService;
    private final DeviceTcpPullService deviceTcpPullService;
    private final TelemetryHub telemetryHub;
    private final TelemetryPresentationService telemetryPresentationService;
    private final TelemetryTrendService telemetryTrendService;
    private final ObjectMapper objectMapper;
    private final UiProperties uiProperties;
    private final IotProperties iotProperties;
    private final BundledChinaDistrictRepository bundledChinaDistrictRepository;
    private final DeviceMqttSubscriptionManager deviceMqttSubscriptionManager;
    private final MapViewAttributes mapViewAttributes;
    private final GatewayRegistryService gatewayRegistryService;

    @GetMapping("/devices")
    public String list(@RequestParam(required = false) String name,
                       @RequestParam(required = false) String sn,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long categoryId,
                       Model model) {
        model.addAttribute("devices", deviceRegistryService.search(name, sn, status, categoryId));
        model.addAttribute("gateways", gatewayRegistryService.listAll());
        model.addAttribute("platformTcpPort", iotProperties.getTcp().getPort());
        ConsoleGatewayController.addFormOptions(model);
        model.addAttribute("categories", deviceCategoryService.listAll());
        model.addAttribute("qName", name != null ? name : "");
        model.addAttribute("qSn", sn != null ? sn : "");
        model.addAttribute("qStatus", status != null ? status : "");
        model.addAttribute("qCategoryId", categoryId);
        addLocationPickerModelAttrs(model, null, null, false, true);
        model.addAttribute("active", "devices");
        model.addAttribute("breadcrumb", "首页 / 设备管理 / 设备列表");
        return "devices";
    }

    @PostMapping("/devices")
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@RequestParam String name,
                         @RequestParam String deviceSn,
                         @RequestParam(required = false, defaultValue = "MQTT") String protocol,
                         @RequestParam(required = false) String productName,
                         @RequestParam(required = false) String orgName,
                         @RequestParam(required = false) String tcpRemoteHost,
                         @RequestParam(required = false) String tcpRemotePort,
                         @RequestParam(required = false, defaultValue = "JSON") String tcpResponseType,
                         @RequestParam(required = false) String mqttRemoteHost,
                         @RequestParam(required = false) String mqttRemotePort,
                         @RequestParam(required = false) String mqttSubscribeTopic,
                         @RequestParam(required = false) String mqttUsername,
                         @RequestParam(required = false) String mqttPassword,
                         @RequestParam(required = false) String locationAddress,
                         @RequestParam(required = false) String latitude,
                         @RequestParam(required = false) String longitude,
                         @RequestParam(required = false) String locationPreset,
                         @RequestParam(required = false) String districtPath,
                         @RequestParam(required = false, defaultValue = "false") String manualCoordsGcj,
                         @RequestParam(required = false) String categoryId,
                         @RequestParam(required = false) String gatewayId,
                         RedirectAttributes ra) {
        try {
            Long cid = parseLongOrNull(categoryId);
            Long gid = parseLongOrNull(gatewayId);
            CreationResult cr = deviceRegistryService.create(name, deviceSn, protocol, productName, orgName,
                    tcpRemoteHost, tcpRemotePort, tcpResponseType,
                    mqttRemoteHost, mqttRemotePort, mqttSubscribeTopic, mqttUsername, mqttPassword,
                    locationAddress, cid,
                    parseDoubleOrNull(latitude), parseDoubleOrNull(longitude), locationPreset,
                    parseManualCoordsGcj(manualCoordsGcj), districtPath, gid);
            Device d = cr.getDevice();
            if (DeviceTcpPullService.isTcpPullDevice(d)) {
                deviceTcpPullService.pullAndIngest(d.getId());
            }
            String base = DeviceTcpPullService.isTcpPullDevice(d)
                    ? "设备已添加，并已尝试一次 TCP 连接拉取（请在详情页查看链路状态）。"
                    : "设备已添加。";
            if (cr.getLocationHint() != null) {
                base = base + " " + cr.getLocationHint();
            }
            ra.addFlashAttribute("msg", base);
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/devices";
    }

    @GetMapping("/devices/{id}/edit")
    @PreAuthorize("hasRole('ADMIN')")
    public String editForm(@PathVariable Long id, Model model) {
        Optional<Device> d = deviceRegistryService.findById(id);
        if (!d.isPresent()) {
            return "redirect:/devices";
        }
        model.addAttribute("device", d.get());
        model.addAttribute("categories", deviceCategoryService.listAll());
        model.addAttribute("gateways", gatewayRegistryService.listAll());
        Device dev = d.get();
        addLocationPickerModelAttrs(model, dev.getLatitude(), dev.getLongitude(),
                Boolean.TRUE.equals(dev.getLocationCoordGcj02()), false);
        model.addAttribute("active", "devices");
        model.addAttribute("breadcrumb", "首页 / 设备管理 / 修改设备");
        return "device-edit";
    }

    @PostMapping("/devices/{id}/update")
    @PreAuthorize("hasRole('ADMIN')")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false, defaultValue = "MQTT") String protocol,
                         @RequestParam(required = false) String productName,
                         @RequestParam(required = false) String orgName,
                         @RequestParam(required = false) String tcpRemoteHost,
                         @RequestParam(required = false) String tcpRemotePort,
                         @RequestParam(required = false, defaultValue = "JSON") String tcpResponseType,
                         @RequestParam(required = false) String mqttRemoteHost,
                         @RequestParam(required = false) String mqttRemotePort,
                         @RequestParam(required = false) String mqttSubscribeTopic,
                         @RequestParam(required = false) String mqttUsername,
                         @RequestParam(required = false) String mqttPassword,
                         @RequestParam(required = false) String locationAddress,
                         @RequestParam(required = false) String latitude,
                         @RequestParam(required = false) String longitude,
                         @RequestParam(required = false) String locationPreset,
                         @RequestParam(required = false) String districtPath,
                         @RequestParam(required = false, defaultValue = "false") String manualCoordsGcj,
                         @RequestParam(required = false) String categoryId,
                         @RequestParam(required = false) String gatewayId,
                         RedirectAttributes ra) {
        try {
            Optional<String> hint = deviceRegistryService.update(id, name, protocol, productName, orgName,
                    tcpRemoteHost, tcpRemotePort, tcpResponseType,
                    mqttRemoteHost, mqttRemotePort, mqttSubscribeTopic, mqttUsername, mqttPassword,
                    locationAddress, parseLongOrNull(categoryId),
                    parseDoubleOrNull(latitude), parseDoubleOrNull(longitude), locationPreset,
                    parseManualCoordsGcj(manualCoordsGcj), districtPath, parseLongOrNull(gatewayId));
            Optional<Device> refreshed = deviceRegistryService.findById(id);
            if (refreshed.isPresent() && DeviceTcpPullService.isTcpPullDevice(refreshed.get())) {
                deviceTcpPullService.pullAndIngest(id);
            }
            String msg = "设备信息已保存。";
            if (hint.isPresent()) {
                msg = msg + " " + hint.get();
            }
            ra.addFlashAttribute("msg", msg);
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/devices/" + id;
    }

    @PostMapping("/devices/{id}/probe")
    @PreAuthorize("hasRole('ADMIN')")
    public String probe(@PathVariable Long id, RedirectAttributes ra) {
        deviceTcpPullService.pullAndIngest(id);
        deviceRegistryService.findById(id).ifPresent(d -> {
            String st = d.getLinkCheckStatus() != null ? d.getLinkCheckStatus() : "";
            String msg = d.getLastLinkMessage() != null ? d.getLastLinkMessage() : "";
            String stZh = "OK".equals(st) ? "已连接" : ("FAIL".equals(st) ? "未连接" : ("UNKNOWN".equalsIgnoreCase(st) ? "未知" : st));
            ra.addFlashAttribute("msg", "检测完成，链路状态：" + stZh + "。" + msg);
        });
        return "redirect:/devices/" + id;
    }

    @PostMapping("/devices/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            deviceRegistryService.delete(id);
            ra.addFlashAttribute("msg", "已删除设备");
        } catch (Exception ex) {
            ra.addFlashAttribute("error", "删除失败：" + ex.getMessage());
        }
        return "redirect:/devices";
    }

    @GetMapping("/devices/{id}")
    public String detail(@PathVariable Long id, Model model) throws JsonProcessingException {
        Optional<Device> d = deviceRegistryService.findById(id);
        if (!d.isPresent()) {
            return "redirect:/devices";
        }
        model.addAttribute("device", d.get());
        model.addAttribute("active", "devices");
        model.addAttribute("breadcrumb", "首页 / 设备管理 / 设备详情");
        Object rawLast = telemetryHub.lastByDeviceSnapshot().get(d.get().getDeviceSn());
        Map<String, Object> lastSnap = null;
        if (rawLast instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) rawLast;
            lastSnap = m;
        }
        model.addAttribute("lastTelemetry", lastSnap);
        Optional<TelemetryDisplayRow> liveRow =
                telemetryPresentationService.formatLastSnapshot(lastSnap, d.get().getProtocol());
        TelemetryDisplayRow lastRowForView = null;
        if (liveRow.isPresent()) {
            TelemetryDisplayRow live = liveRow.get();
            if (live.getFields() != null && !live.getFields().isEmpty()) {
                lastRowForView = live;
            } else {
                lastRowForView = telemetryPresentationService.mergeLiveHeaderWithStoredFields(
                        live, d.get().getLastTelemetryDisplayJson());
            }
        } else {
            lastRowForView = telemetryPresentationService.deserializeDisplayRow(d.get().getLastTelemetryDisplayJson())
                    .orElse(null);
        }
        model.addAttribute("lastTelemetryRow", lastRowForView);
        model.addAttribute("pageAutoRefreshSeconds", uiProperties.getPageAutoRefreshSeconds());
        model.addAttribute("telemetryChartJson",
                objectMapper.writeValueAsString(telemetryTrendService.buildChartModel(d.get().getDeviceSn(), 48, 200)));
        Instant now = Instant.now();
        Instant from = now.minusSeconds(86400);
        model.addAttribute("exportFromStr", LocalDateTime.ofInstant(from, ZoneId.systemDefault()).format(EXPORT_DT));
        model.addAttribute("exportToStr", LocalDateTime.ofInstant(now, ZoneId.systemDefault()).format(EXPORT_DT));
        model.addAttribute("iotTcp", iotProperties.getTcp());
        model.addAttribute("iotMqtt", iotProperties.getMqtt());
        Device dev = d.get();
        boolean perDevMqtt = "MQTT".equalsIgnoreCase(dev.getProtocol())
                && dev.getMqttRemoteHost() != null && !dev.getMqttRemoteHost().trim().isEmpty()
                && dev.getMqttSubscribeTopic() != null && !dev.getMqttSubscribeTopic().trim().isEmpty();
        model.addAttribute("mqttPerDeviceConfigured", perDevMqtt);
        model.addAttribute("mqttPerDeviceLinked", perDevMqtt && deviceMqttSubscriptionManager.isLinked(dev.getId()));

        applyMapViewToModel(model);
        // 设备位置：与协议无关。优先从「最近一条遥测」payload 任意字段中解析 $GNRMC/$GPRMC；解析不到则用档案中的经纬度（创建设备/编辑时保存的地图选点、手输坐标或地理编码结果）。
        Object livePayload = lastSnap != null ? lastSnap.get("payload") : null;
        Optional<Wgs84Point> gnrmcPt = GnrmcLocationExtractor.tryFindFromTelemetryPayload(livePayload);
        Double mapLat = null;
        Double mapLng = null;
        boolean mapGcj = false;
        String mapSource = null;
        if (gnrmcPt.isPresent()) {
            Wgs84Point p = gnrmcPt.get();
            mapLat = p.getLatitude();
            mapLng = p.getLongitude();
            mapGcj = false;
            mapSource = "gnrmc";
        } else if (dev.getLatitude() != null && dev.getLongitude() != null) {
            mapLat = dev.getLatitude();
            mapLng = dev.getLongitude();
            mapGcj = Boolean.TRUE.equals(dev.getLocationCoordGcj02());
            mapSource = "archive";
        }
        boolean mapHasPoint = mapLat != null && mapLng != null;
        model.addAttribute("deviceDetailMapHasPoint", mapHasPoint);
        model.addAttribute("deviceDetailMapLat", mapLat);
        model.addAttribute("deviceDetailMapLng", mapLng);
        model.addAttribute("deviceDetailMapGcj02", mapGcj);
        model.addAttribute("deviceDetailMapSource", mapSource);
        if (mapHasPoint) {
            Map<String, Object> pin = new LinkedHashMap<String, Object>();
            pin.put("lat", mapLat);
            pin.put("lng", mapLng);
            pin.put("gcj02", mapGcj);
            pin.put("title", dev.getName());
            pin.put("deviceSn", dev.getDeviceSn());
            model.addAttribute("deviceDetailMapPinJson", objectMapper.writeValueAsString(pin));
        } else {
            model.addAttribute("deviceDetailMapPinJson", "{}");
        }
        return "device-detail";
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseManualCoordsGcj(String s) {
        return s != null && ("true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim()) || "on".equalsIgnoreCase(s.trim()));
    }

    /** 地图脚本所需属性（首页、设备详情、设备表单等共用）。 */
    private void applyMapViewToModel(Model model) {
        mapViewAttributes.apply(model);
    }

    private void addLocationPickerModelAttrs(Model model, Double initLat, Double initLng, boolean initGcj,
                                             boolean locationPickerInModal) {
        applyMapViewToModel(model);
        String wsKey = iotProperties.getMaps().getGaode().getWebServiceKey();
        boolean districtOk = bundledChinaDistrictRepository.hasData()
                || (wsKey != null && !wsKey.trim().isEmpty());
        model.addAttribute("districtApiConfigured", districtOk);
        model.addAttribute("locPickInitLat", initLat);
        model.addAttribute("locPickInitLng", initLng);
        model.addAttribute("locPickInitGcj", initGcj);
        model.addAttribute("locationPickerInModal", locationPickerInModal);
    }
}
