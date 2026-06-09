package com.iot.platform.management.service;

import com.iot.platform.gateway.GatewayProfileCatalog;
import com.iot.platform.gateway.GatewaySerialMode;
import com.iot.platform.gateway.GatewayUplinkProtocol;
import com.iot.platform.gateway.GatewayVendor;
import com.iot.platform.management.entity.IotGateway;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.management.repo.GatewayRepository;
import com.iot.platform.protocol.mqtt.GatewayMqttSubscriptionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GatewayRegistryService {

    private final GatewayRepository gatewayRepository;
    private final DeviceRepository deviceRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<IotGateway> search(String name, String sn, String status) {
        String n = trimToNull(name);
        String s = trimToNull(sn);
        String st = trimToNull(status);
        return gatewayRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .filter(g -> n == null || containsIgnoreCase(g.getName(), n))
                .filter(g -> s == null || containsIgnoreCase(g.getGatewaySn(), s))
                .filter(g -> st == null || st.equalsIgnoreCase(g.getStatus()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<IotGateway> findById(Long id) {
        return gatewayRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<IotGateway> listAll() {
        return gatewayRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @Transactional
    public IotGateway create(String name, String gatewaySn, String vendorModel, String uplinkProtocol,
                             String serialMode, String remoteHost, String remotePortStr,
                             String mqttRemoteHost, String mqttRemotePortStr, String mqttSubscribeTopic,
                             String mqttUsername, String mqttPassword,
                             String registerPacket, String heartbeatPacket,
                             String locationAddress, String remark) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("网关名称不能为空");
        }
        if (gatewaySn == null || gatewaySn.trim().isEmpty()) {
            throw new IllegalArgumentException("网关编号不能为空");
        }
        String sn = gatewaySn.trim();
        if (gatewayRepository.existsByGatewaySn(sn)) {
            throw new IllegalArgumentException("网关编号已存在：" + sn);
        }
        IotGateway g = new IotGateway();
        g.setName(name.trim());
        g.setGatewaySn(sn);
        g.setVendorModel(GatewayVendor.parse(vendorModel).name());
        g.setUplinkProtocol(GatewayUplinkProtocol.parse(uplinkProtocol).name());
        g.setSerialMode(GatewaySerialMode.parse(serialMode).name());
        applyConnectionFields(g, remoteHost, remotePortStr, mqttRemoteHost, mqttRemotePortStr,
                mqttSubscribeTopic, mqttUsername, mqttPassword, registerPacket, heartbeatPacket,
                locationAddress, remark);
        GatewayProfileCatalog.applyVendorDefaults(g);
        validateUplink(g);
        g = gatewayRepository.save(g);
        eventPublisher.publishEvent(new GatewayMqttSubscriptionEvent(g.getId(), GatewayMqttSubscriptionEvent.Action.SYNC));
        return g;
    }

    @Transactional
    public void update(Long id, String name, String vendorModel, String uplinkProtocol, String serialMode,
                       String remoteHost, String remotePortStr,
                       String mqttRemoteHost, String mqttRemotePortStr, String mqttSubscribeTopic,
                       String mqttUsername, String mqttPassword,
                       String registerPacket, String heartbeatPacket,
                       String locationAddress, String remark) {
        IotGateway g = gatewayRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("网关不存在"));
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("网关名称不能为空");
        }
        g.setName(name.trim());
        g.setVendorModel(GatewayVendor.parse(vendorModel).name());
        g.setUplinkProtocol(GatewayUplinkProtocol.parse(uplinkProtocol).name());
        g.setSerialMode(GatewaySerialMode.parse(serialMode).name());
        applyConnectionFields(g, remoteHost, remotePortStr, mqttRemoteHost, mqttRemotePortStr,
                mqttSubscribeTopic, mqttUsername, mqttPassword, registerPacket, heartbeatPacket,
                locationAddress, remark);
        GatewayProfileCatalog.applyVendorDefaults(g);
        validateUplink(g);
        gatewayRepository.save(g);
        eventPublisher.publishEvent(new GatewayMqttSubscriptionEvent(id, GatewayMqttSubscriptionEvent.Action.SYNC));
    }

    @Transactional
    public void delete(Long id) {
        eventPublisher.publishEvent(new GatewayMqttSubscriptionEvent(id, GatewayMqttSubscriptionEvent.Action.REMOVE));
        deviceRepository.clearGatewayByGatewayId(id);
        gatewayRepository.deleteById(id);
    }

    private void applyConnectionFields(IotGateway g, String remoteHost, String remotePortStr,
                                       String mqttRemoteHost, String mqttRemotePortStr, String mqttSubscribeTopic,
                                       String mqttUsername, String mqttPassword,
                                       String registerPacket, String heartbeatPacket,
                                       String locationAddress, String remark) {
        g.setRemoteHost(trimToNull(remoteHost));
        g.setRemotePort(parsePortOrNull(remotePortStr));
        g.setMqttRemoteHost(trimToNull(mqttRemoteHost));
        g.setMqttRemotePort(parsePortOrNull(mqttRemotePortStr));
        g.setMqttSubscribeTopic(trimToNull(mqttSubscribeTopic));
        g.setMqttUsername(trimToNull(mqttUsername));
        g.setMqttPassword(trimToNull(mqttPassword));
        g.setRegisterPacket(trimToNull(registerPacket));
        g.setHeartbeatPacket(trimToNull(heartbeatPacket));
        g.setLocationAddress(trimToNull(locationAddress));
        g.setRemark(trimToNull(remark));
    }

    private void validateUplink(IotGateway g) {
        GatewayUplinkProtocol uplink = GatewayUplinkProtocol.parse(g.getUplinkProtocol());
        if (uplink == GatewayUplinkProtocol.TCP_SERVER || uplink == GatewayUplinkProtocol.MODBUS_TCP) {
            if (g.getRemoteHost() == null || g.getRemoteHost().trim().isEmpty()) {
                throw new IllegalArgumentException("该上行方式请填写网关 IP/主机地址");
            }
        }
        if (uplink == GatewayUplinkProtocol.MQTT) {
            if (g.getMqttRemoteHost() == null || g.getMqttRemoteHost().trim().isEmpty()) {
                throw new IllegalArgumentException("MQTT 上行请填写 Broker 地址");
            }
            if (g.getMqttSubscribeTopic() == null || g.getMqttSubscribeTopic().trim().isEmpty()) {
                throw new IllegalArgumentException("MQTT 上行请填写平台订阅主题");
            }
        }
    }

    private static Integer parsePortOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 1 || p > 65535) {
                throw new IllegalArgumentException("端口无效（1-65535）");
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口格式无效");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        return hay != null && hay.toLowerCase().contains(needle.toLowerCase());
    }
}
