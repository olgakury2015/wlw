package com.iot.platform.management.controller;

import com.iot.platform.management.entity.Device;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.management.service.DeviceRegistryService;
import com.iot.platform.management.service.DeviceRegistryService.CreationResult;
import com.iot.platform.management.service.DeviceTcpPullService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设备管理 REST，便于网关或 Node-RED 调用。
 */
@RestController
@RequestMapping("/api/v1/management/devices")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class DeviceManagementApiController {

    private final DeviceRepository deviceRepository;
    private final DeviceRegistryService deviceRegistryService;
    private final DeviceTcpPullService deviceTcpPullService;

    @GetMapping
    public List<Device> list() {
        return deviceRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            String name = String.valueOf(body.getOrDefault("name", ""));
            String sn = String.valueOf(body.getOrDefault("deviceSn", ""));
            String protocol = body.get("protocol") != null ? String.valueOf(body.get("protocol")) : "MQTT";
            String product = body.get("productName") != null ? String.valueOf(body.get("productName")) : "";
            String org = body.get("orgName") != null ? String.valueOf(body.get("orgName")) : "";
            String tcpHost = body.get("tcpRemoteHost") != null ? String.valueOf(body.get("tcpRemoteHost")) : null;
            String tcpPort = body.get("tcpRemotePort") != null ? String.valueOf(body.get("tcpRemotePort")) : null;
            String tcpRt = body.get("tcpResponseType") != null ? String.valueOf(body.get("tcpResponseType")) : "JSON";
            String mqttHost = readOptionalString(body.get("mqttRemoteHost"));
            String mqttPort = body.get("mqttRemotePort") != null ? String.valueOf(body.get("mqttRemotePort")) : null;
            String mqttTopic = readOptionalString(body.get("mqttSubscribeTopic"));
            String mqttUser = readOptionalString(body.get("mqttUsername"));
            String mqttPass = readOptionalString(body.get("mqttPassword"));
            String loc = readOptionalString(body.get("locationAddress"));
            if (loc == null) {
                loc = readOptionalString(body.get("address"));
            }
            Long categoryId = parseLongOrNull(body.get("categoryId"));
            Double lat = parseDoubleFromBody(body.get("latitude"));
            Double lon = parseDoubleFromBody(body.get("longitude"));
            String preset = readOptionalString(body.get("locationPreset"));
            Boolean manualGcj = readBooleanFlag(body.get("manualCoordsGcj"));
            String districtPath = readOptionalString(body.get("districtPath"));
            CreationResult cr = deviceRegistryService.create(name, sn, protocol, product, org, tcpHost, tcpPort, tcpRt,
                    mqttHost, mqttPort, mqttTopic, mqttUser, mqttPass,
                    loc, categoryId, lat, lon, preset,
                    manualGcj, districtPath, null);
            Device d = cr.getDevice();
            if (DeviceTcpPullService.isTcpPullDevice(d)) {
                deviceTcpPullService.pullAndIngest(d.getId());
            }
            if (cr.getLocationHint() != null) {
                Map<String, Object> resp = new HashMap<String, Object>();
                resp.put("device", d);
                resp.put("locationHint", cr.getLocationHint());
                return ResponseEntity.status(HttpStatus.CREATED).body(resp);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(d);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", ex.getMessage()));
        }
    }

    /** 立即对 TCP_PULL 设备做一次连接并拉取一行数据。 */
    @PostMapping("/{id}/probe")
    public ResponseEntity<?> probe(@PathVariable Long id) {
        if (!deviceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        deviceTcpPullService.pullAndIngest(id);
        Optional<Device> opt = deviceRepository.findById(id);
        if (!opt.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        Device d = opt.get();
        Map<String, String> body = new HashMap<String, String>();
        body.put("linkCheckStatus", d.getLinkCheckStatus() != null ? d.getLinkCheckStatus() : "");
        body.put("lastLinkMessage", d.getLastLinkMessage() != null ? d.getLastLinkMessage() : "");
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!deviceRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        deviceRegistryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static String readOptionalString(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static Boolean readBooleanFlag(Object v) {
        if (v == null) {
            return Boolean.FALSE;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "on".equalsIgnoreCase(s);
    }

    private static Double parseDoubleFromBody(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            String s = String.valueOf(v).trim().replace(',', '.');
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return null;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(Object v) {
        if (v == null) {
            return null;
        }
        try {
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return null;
            }
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }
}
