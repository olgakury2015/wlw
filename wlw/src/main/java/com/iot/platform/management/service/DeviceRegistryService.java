package com.iot.platform.management.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.iot.platform.config.GeocodingProperties;
import com.iot.platform.management.entity.Device;
import com.iot.platform.management.entity.DeviceCategory;
import com.iot.platform.management.entity.IotGateway;
import com.iot.platform.management.repo.DeviceCategoryRepository;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.management.repo.GatewayRepository;
import com.iot.platform.ops.service.AuditLogService;
import com.iot.platform.model.TelemetryMessage;
import com.iot.platform.protocol.mqtt.DeviceMqttSubscriptionManager;
import com.iot.platform.service.geocode.AddressGeocodeResult;
import com.iot.platform.service.geocode.GeocodingClient;
import com.iot.platform.service.geocode.NominatimGeocodingService;
import com.iot.platform.service.telemetry.TelemetryDisplayRow;
import com.iot.platform.service.telemetry.TelemetryPresentationService;
import com.iot.platform.util.GnrmcLocationExtractor;
import com.iot.platform.util.GnrmcLocationExtractor.Wgs84Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistryService {

    private final DeviceRepository deviceRepository;
    private final GatewayRepository gatewayRepository;
    private final DeviceCategoryRepository deviceCategoryRepository;
    private final GeocodingClient geocodingClient;
    private final GeocodingProperties geocodingProperties;
    private final NominatimGeocodingService nominatimGeocodingService;
    private final TelemetryPresentationService telemetryPresentationService;
    private final AuditLogService auditLogService;
    /** 延迟解析，避免与 {@link com.iot.platform.service.IngestionPipeline} 形成构造期循环依赖 */
    private final ObjectProvider<DeviceMqttSubscriptionManager> deviceMqttSubscriptionManagerProvider;

    @Transactional(readOnly = true)
    public List<Device> search(String name, String sn, String status, Long categoryId) {
        String n = trimToNull(name);
        String s = trimToNull(sn);
        String st = trimToNull(status);
        return deviceRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .filter(d -> n == null || containsIgnoreCase(d.getName(), n))
                .filter(d -> s == null || containsIgnoreCase(d.getDeviceSn(), s))
                .filter(d -> st == null || st.equalsIgnoreCase(d.getStatus()))
                .filter(d -> categoryId == null
                        || (d.getCategory() != null && categoryId.equals(d.getCategory().getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<Device> findById(Long id) {
        return deviceRepository.findById(id);
    }

    /**
     * 创建设备；{@link CreationResult#getLocationHint()} 在「仅地址且解析失败」等场景给出提示文案。
     */
    @Transactional
    public CreationResult create(String name, String deviceSn, String protocol, String productName, String orgName,
                                 String tcpRemoteHost, String tcpRemotePortStr, String tcpResponseType,
                                 String mqttRemoteHost, String mqttRemotePortStr, String mqttSubscribeTopic,
                                 String mqttUsername, String mqttPassword,
                                 String locationAddress, Long categoryId,
                                 Double manualLatitude, Double manualLongitude, String locationPresetCode,
                                 Boolean manualCoordsGcj, String districtPath, Long gatewayId) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }
        if (deviceSn == null || deviceSn.trim().isEmpty()) {
            throw new IllegalArgumentException("设备编号不能为空");
        }
        String sn = deviceSn.trim();
        if (deviceRepository.existsByDeviceSn(sn)) {
            throw new IllegalArgumentException("设备编号已存在：" + sn);
        }
        String proto = protocol != null && !protocol.trim().isEmpty() ? protocol.trim() : "MQTT";

        Integer tcpPort = null;
        String tcpHost = trimToNull(tcpRemoteHost);
        if ("TCP_PULL".equalsIgnoreCase(proto)) {
            if (tcpHost == null) {
                throw new IllegalArgumentException("TCP（设备为服务端）模式下请填写设备 TCP 服务地址");
            }
            tcpPort = parsePort(tcpRemotePortStr);
            if (tcpPort == null || tcpPort <= 0 || tcpPort > 65535) {
                throw new IllegalArgumentException("请填写有效的设备 TCP 端口（1-65535）");
            }
        }

        Device d = new Device();
        d.setName(name.trim());
        d.setDeviceSn(sn);
        d.setProtocol(proto);
        d.setProductName(trimToEmpty(productName));
        d.setOrgName(trimToEmpty(orgName));
        d.setStatus("OFFLINE");
        d.setActivatedAt(LocalDateTime.now());
        d.setAlarmCount(0);

        if ("TCP_PULL".equalsIgnoreCase(proto)) {
            d.setTcpRemoteHost(tcpHost);
            d.setTcpRemotePort(tcpPort);
            String rt = trimToNull(tcpResponseType);
            d.setTcpResponseType(rt != null ? rt.toUpperCase() : "JSON");
            d.setLinkCheckStatus("UNKNOWN");
            d.setLastLinkMessage("尚未检测");
            clearMqttConnectionFields(d);
        } else {
            d.setTcpRemoteHost(null);
            d.setTcpRemotePort(null);
            d.setTcpResponseType("JSON");
            d.setLinkCheckStatus(null);
            d.setLastLinkMessage(null);
            if ("MQTT".equalsIgnoreCase(proto)) {
                applyMqttConnectionFields(d, mqttRemoteHost, mqttRemotePortStr, mqttSubscribeTopic, mqttUsername, mqttPassword, false);
            } else {
                clearMqttConnectionFields(d);
            }
        }

        Optional<String> hint = applyLocation(d, locationAddress, manualLatitude, manualLongitude, locationPresetCode,
                manualCoordsGcj(manualCoordsGcj), districtPath);
        applyCategory(d, categoryId);
        applyGateway(d, gatewayId);

        Device saved = deviceRepository.save(d);
        deviceMqttSubscriptionManagerProvider.getObject().syncDevice(saved.getId());
        auditLogService.log("DEVICE_CREATE", "新增设备 id=" + saved.getId() + " sn=" + saved.getDeviceSn());
        return new CreationResult(saved, hint.orElse(null));
    }

    @Transactional
    public Optional<String> update(Long id, String name, String protocol, String productName, String orgName,
                                   String tcpRemoteHost, String tcpRemotePortStr, String tcpResponseType,
                                   String mqttRemoteHost, String mqttRemotePortStr, String mqttSubscribeTopic,
                                   String mqttUsername, String mqttPassword,
                                   String locationAddress, Long categoryId,
                                   Double manualLatitude, Double manualLongitude, String locationPresetCode,
                                   Boolean manualCoordsGcj, String districtPath, Long gatewayId) {
        Device d = deviceRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("设备不存在"));
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("设备名称不能为空");
        }
        String proto = protocol != null && !protocol.trim().isEmpty() ? protocol.trim() : "MQTT";
        Integer tcpPort = null;
        String tcpHost = trimToNull(tcpRemoteHost);
        if ("TCP_PULL".equalsIgnoreCase(proto)) {
            if (tcpHost == null) {
                throw new IllegalArgumentException("TCP（设备为服务端）模式下请填写设备 TCP 服务地址");
            }
            tcpPort = parsePort(tcpRemotePortStr);
            if (tcpPort == null || tcpPort <= 0 || tcpPort > 65535) {
                throw new IllegalArgumentException("请填写有效的设备 TCP 端口（1-65535）");
            }
        }

        d.setName(name.trim());
        d.setProtocol(proto);
        d.setProductName(trimToEmpty(productName));
        d.setOrgName(trimToEmpty(orgName));

        if ("TCP_PULL".equalsIgnoreCase(proto)) {
            d.setTcpRemoteHost(tcpHost);
            d.setTcpRemotePort(tcpPort);
            String rt = trimToNull(tcpResponseType);
            d.setTcpResponseType(rt != null ? rt.toUpperCase() : "JSON");
            if (d.getLinkCheckStatus() == null) {
                d.setLinkCheckStatus("UNKNOWN");
            }
            if (d.getLastLinkMessage() == null) {
                d.setLastLinkMessage("尚未检测");
            }
            clearMqttConnectionFields(d);
        } else {
            d.setTcpRemoteHost(null);
            d.setTcpRemotePort(null);
            d.setTcpResponseType("JSON");
            d.setLinkCheckStatus(null);
            d.setLastLinkMessage(null);
            if ("MQTT".equalsIgnoreCase(proto)) {
                applyMqttConnectionFields(d, mqttRemoteHost, mqttRemotePortStr, mqttSubscribeTopic, mqttUsername, mqttPassword, true);
            } else {
                clearMqttConnectionFields(d);
            }
        }

        Optional<String> hint = applyLocation(d, locationAddress, manualLatitude, manualLongitude, locationPresetCode,
                manualCoordsGcj(manualCoordsGcj), districtPath);
        applyCategory(d, categoryId);
        applyGateway(d, gatewayId);

        deviceRepository.save(d);
        deviceMqttSubscriptionManagerProvider.getObject().syncDevice(id);
        auditLogService.log("DEVICE_UPDATE", "更新设备 id=" + id + " sn=" + d.getDeviceSn());
        return hint;
    }

    private void applyCategory(Device d, Long categoryId) {
        if (categoryId == null) {
            d.setCategory(null);
            return;
        }
        DeviceCategory cat = deviceCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("无效的设备分类"));
        d.setCategory(cat);
    }

    private void applyGateway(Device d, Long gatewayId) {
        if (gatewayId == null) {
            d.setGateway(null);
            return;
        }
        IotGateway gw = gatewayRepository.findById(gatewayId)
                .orElseThrow(() -> new IllegalArgumentException("无效的所属网关"));
        d.setGateway(gw);
    }

    /**
     * 安装位置：优先有效经纬度（地图选点 / 省市区中心 / 手输），其次内置城市预设，再尝试按文字地理编码。
     * 无坐标且仅有备注/行政区文字时解析失败仍会保存文字。
     */
    private Optional<String> applyLocation(Device d, String addressRaw,
                                           Double manualLat, Double manualLon, String presetCodeRaw,
                                           boolean manualCoordsGcj, String districtPathRaw) {
        String addr = trimToNull(addressRaw);
        String districtPath = trimToNull(districtPathRaw);
        String presetCode = trimToNull(presetCodeRaw);
        String displayText = addr != null ? addr : districtPath;

        if (manualLat != null && manualLon != null && validLatLon(manualLat, manualLon)) {
            d.setLocationAddress(displayText);
            d.setLatitude(manualLat);
            d.setLongitude(manualLon);
            d.setLocationCoordGcj02(manualCoordsGcj);
            return Optional.empty();
        }

        if (presetCode != null) {
            DeviceLocationPresets.Preset preset = DeviceLocationPresets.byCode(presetCode);
            if (preset != null) {
                d.setLocationAddress(displayText != null ? displayText : preset.getLabel());
                d.setLatitude(preset.getLat());
                d.setLongitude(preset.getLon());
                d.setLocationCoordGcj02(false);
                return Optional.empty();
            }
        }

        if (addr == null && districtPath == null) {
            d.setLocationAddress(null);
            d.setLatitude(null);
            d.setLongitude(null);
            d.setLocationCoordGcj02(null);
            return Optional.empty();
        }

        d.setLocationAddress(displayText);
        String geocodeQuery = addr != null ? addr : districtPath;
        if (!geocodingProperties.isEnabled()) {
            d.setLatitude(null);
            d.setLongitude(null);
            d.setLocationCoordGcj02(null);
            return Optional.of("已保存位置说明；当前未启用联网地理编码，未写入坐标。请使用省市区与地图选点，或填写经纬度。");
        }
        Optional<AddressGeocodeResult> resolved = geocodingClient.geocode(geocodeQuery);
        if (resolved.isPresent()) {
            AddressGeocodeResult g = resolved.get();
            d.setLatitude(g.getLatitude());
            d.setLongitude(g.getLongitude());
            d.setLocationCoordGcj02(g.isGcj02());
            return Optional.empty();
        }
        d.setLatitude(null);
        d.setLongitude(null);
        d.setLocationCoordGcj02(null);
        return Optional.of("已保存位置说明，但未能解析到坐标。可直接在地图上点击选点，或完善省市区后保存。");
    }

    private static boolean manualCoordsGcj(Boolean v) {
        return v != null && v;
    }

    private static boolean validLatLon(double lat, double lon) {
        return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0;
    }

    public static final class CreationResult {
        private final Device device;
        private final String locationHint;

        public CreationResult(Device device, String locationHint) {
            this.device = device;
            this.locationHint = locationHint;
        }

        public Device getDevice() {
            return device;
        }

        public String getLocationHint() {
            return locationHint;
        }
    }

    private static Integer parsePort(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim(), 10);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    public void delete(Long id) {
        deviceRepository.findById(id).ifPresent(d ->
                auditLogService.log("DEVICE_DELETE", "删除设备 id=" + id + " sn=" + d.getDeviceSn()));
        deviceMqttSubscriptionManagerProvider.getObject().removeDevice(id);
        deviceRepository.deleteById(id);
    }

    @Transactional
    public void incrementAlarmCount(String deviceSn) {
        if (deviceSn == null || deviceSn.trim().isEmpty()) {
            return;
        }
        deviceRepository.findByDeviceSn(deviceSn.trim()).ifPresent(d -> {
            d.setAlarmCount(d.getAlarmCount() + 1);
            deviceRepository.save(d);
        });
    }

    /**
     * 遥测入库后：刷新在线与最后上报；若有 {@code $GNRMC}/{@code $GPRMC} 则更新档案经纬度（WGS84），并在开启 {@code iot.geocoding} 时用 Nominatim 逆地理更新「安装地址」；
     * 若本条遥测解析出<strong>非空</strong>右侧表格行则写入 {@link Device#getLastTelemetryDisplayJson()}，否则保留上次 JSON（纯定位等上报不冲掉测点表）。
     */
    @Transactional
    public void touchIfRegistered(TelemetryMessage msg) {
        String deviceId = msg.deviceId();
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return;
        }
        Map<String, Object> payload = msg.payload();
        Optional<Wgs84Point> gnrmc = payload != null
                ? GnrmcLocationExtractor.tryFindFromTelemetryPayload(payload)
                : Optional.empty();
        TelemetryDisplayRow displayRow = telemetryPresentationService.fromMessage(msg);
        deviceRepository.findByDeviceSn(deviceId.trim()).ifPresent(d -> {
            d.setLastSeenAt(LocalDateTime.now());
            d.setStatus("ONLINE");
            if (gnrmc.isPresent()) {
                Wgs84Point w = gnrmc.get();
                d.setLatitude(w.getLatitude());
                d.setLongitude(w.getLongitude());
                d.setLocationCoordGcj02(Boolean.FALSE);
                if (geocodingProperties.isEnabled()) {
                    nominatimGeocodingService.reverseDisplayName(w.getLatitude(), w.getLongitude())
                            .ifPresent(d::setLocationAddress);
                }
            }
            if (displayRow.getFields() != null && !displayRow.getFields().isEmpty()) {
                try {
                    d.setLastTelemetryDisplayJson(telemetryPresentationService.serializeDisplayRow(displayRow));
                } catch (JsonProcessingException e) {
                    log.warn("保存最近遥测展示 JSON 失败 sn={}: {}", deviceId, e.getMessage());
                }
            }
            deviceRepository.save(d);
        });
    }

    private void clearMqttConnectionFields(Device d) {
        d.setMqttRemoteHost(null);
        d.setMqttRemotePort(null);
        d.setMqttSubscribeTopic(null);
        d.setMqttUsername(null);
        d.setMqttPassword(null);
    }

    /**
     * 填写了 Broker 主机则必须填写订阅主题；端口默认 1883。主机可填 IP 或完整 URI（含 tcp://、端口）。
     */
    private void applyMqttConnectionFields(Device d, String mqttRemoteHost, String mqttRemotePortStr,
                                         String mqttSubscribeTopic, String mqttUsername, String mqttPassword,
                                         boolean keepPasswordIfBlank) {
        String host = trimToNull(mqttRemoteHost);
        if (host == null) {
            clearMqttConnectionFields(d);
            return;
        }
        String topic = trimToNull(mqttSubscribeTopic);
        if (topic == null) {
            throw new IllegalArgumentException("MQTT：已填写 Broker 地址时，订阅主题不能为空");
        }
        Integer p = parsePort(mqttRemotePortStr);
        if (p == null) {
            p = 1883;
        }
        if (p <= 0 || p > 65535) {
            throw new IllegalArgumentException("MQTT 端口无效（1-65535）");
        }
        d.setMqttRemoteHost(host);
        // 完整 URI 时仍保存表单端口：Paho 需 tcp/ssl，且 URI 常不带端口，连接时用此端口合并
        d.setMqttRemotePort(host.contains("://") ? parsePort(mqttRemotePortStr) : p);
        d.setMqttSubscribeTopic(topic);
        d.setMqttUsername(trimToNull(mqttUsername));
        if (keepPasswordIfBlank && (mqttPassword == null || mqttPassword.trim().isEmpty())) {
            // 修改设备时留空表示不修改密码
        } else {
            d.setMqttPassword(trimToNull(mqttPassword));
        }
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimToEmpty(String v) {
        return v == null ? "" : v.trim();
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }
}
