package com.iot.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.IotProperties;
import com.iot.platform.integration.NodeRedIntegrationService;
import com.iot.platform.protocol.modbus.ModbusRtuAdapter;
import com.iot.platform.protocol.modbus.ModbusTcpAdapter;
import com.iot.platform.protocol.mqtt.MqttBridgeService;
import com.iot.platform.service.IngestionPipeline;
import com.iot.platform.service.TelemetryHub;
import com.iot.platform.websocket.IotWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外 REST：供设备、网关与 Node-RED（HTTP Request 节点）调用。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PlatformApiController {

    private final TelemetryHub telemetryHub;
    private final IngestionPipeline ingestionPipeline;
    private final IotProperties iotProperties;
    private final ModbusTcpAdapter modbusTcpAdapter;
    private final ModbusRtuAdapter modbusRtuAdapter;
    private final MqttBridgeService mqttBridgeService;
    private final NodeRedIntegrationService nodeRedIntegrationService;
    private final IotWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("status", "UP");
        m.put("tcpEnabled", iotProperties.getTcp().isEnabled());
        m.put("mqttEnabled", iotProperties.getMqtt().isEnabled());
        m.put("modbusRtuEnabled", iotProperties.getModbusRtu().isEnabled());
        m.put("webSocketSessions", webSocketHandler.getSessionCount());
        return m;
    }

    @GetMapping("/telemetry/recent")
    public List<?> recent() {
        return telemetryHub.recentSnapshot();
    }

    /**
     * HTTP 协议数据入口（JSON）。
     */
    @PostMapping("/telemetry")
    public Map<String, Object> ingestTelemetry(@RequestBody Map<String, Object> body) {
        String deviceId = String.valueOf(body.getOrDefault("deviceId", "http-unknown"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body.getOrDefault("payload", body);
        String protocol = String.valueOf(body.getOrDefault("protocol", "HTTP"));
        ingestionPipeline.ingest(protocol, deviceId, payload);
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", Boolean.TRUE);
        r.put("deviceId", deviceId);
        return r;
    }

    @PostMapping("/http/ingest")
    public Map<String, Object> ingestHttpAlias(@RequestBody Map<String, Object> body) {
        body.putIfAbsent("protocol", "HTTP");
        return ingestTelemetry(body);
    }

    @PostMapping("/modbus/tcp/read-holding")
    public Map<String, Object> modbusTcpReadHolding(@RequestBody Map<String, Object> body) throws Exception {
        String host = (String) body.get("host");
        Integer port = portOrNull(body.get("port"));
        int unitId = intVal(body.get("unitId"), iotProperties.getModbusTcp().getDefaultUnitId());
        int ref = intVal(body.get("reference"), 0);
        int count = intVal(body.get("count"), 8);
        return modbusTcpAdapter.readHoldingRegisters(host, port, unitId, ref, count);
    }

    @PostMapping("/modbus/tcp/read-input")
    public Map<String, Object> modbusTcpReadInput(@RequestBody Map<String, Object> body) throws Exception {
        String host = (String) body.get("host");
        Integer port = portOrNull(body.get("port"));
        int unitId = intVal(body.get("unitId"), iotProperties.getModbusTcp().getDefaultUnitId());
        int ref = intVal(body.get("reference"), 0);
        int count = intVal(body.get("count"), 8);
        return modbusTcpAdapter.readInputRegisters(host, port, unitId, ref, count);
    }

    @PostMapping("/modbus/tcp/read-coils")
    public Map<String, Object> modbusTcpReadCoils(@RequestBody Map<String, Object> body) throws Exception {
        String host = (String) body.get("host");
        Integer port = portOrNull(body.get("port"));
        int unitId = intVal(body.get("unitId"), iotProperties.getModbusTcp().getDefaultUnitId());
        int ref = intVal(body.get("reference"), 0);
        int count = intVal(body.get("count"), 8);
        return modbusTcpAdapter.readCoils(host, port, unitId, ref, count);
    }

    @PostMapping("/modbus/rtu/read-holding")
    public Map<String, Object> modbusRtuReadHolding(@RequestBody Map<String, Object> body) throws Exception {
        int ref = intVal(body.get("reference"), 0);
        int count = intVal(body.get("count"), 8);
        return modbusRtuAdapter.readHoldingRegisters(ref, count);
    }

    @PostMapping("/mqtt/publish")
    public Map<String, Object> mqttPublish(@RequestBody Map<String, Object> body) throws Exception {
        String brokerId = body.get("brokerId") != null ? String.valueOf(body.get("brokerId")).trim() : null;
        if (brokerId != null && brokerId.isEmpty()) {
            brokerId = null;
        }
        String sub = String.valueOf(body.getOrDefault("topicSuffix", "out"));
        Object pl = body.containsKey("payload") ? body.get("payload") : Collections.emptyMap();
        String payload = objectMapper.writeValueAsString(pl);
        mqttBridgeService.publish(brokerId, sub, payload);
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", Boolean.TRUE);
        return r;
    }

    /** Spring Boot → Node-RED：使用 WebClient 拉取 Node-RED HTTP 端点数据。 */
    @GetMapping("/nodered/metrics")
    public Map<String, Object> nodeRedMetrics() {
        return nodeRedIntegrationService.fetchMetricsWebClient();
    }

    /** Spring Boot → Node-RED：使用 RestTemplate 向 Node-RED 提交规则/上下文。 */
    @PostMapping("/nodered/rule")
    public ResponseEntity<String> nodeRedRule(@RequestBody Map<String, Object> body) {
        return nodeRedIntegrationService.postRuleRestTemplate(body);
    }

    private static Integer portOrNull(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return null;
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }
}
