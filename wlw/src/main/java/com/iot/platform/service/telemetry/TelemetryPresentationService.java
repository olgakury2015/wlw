package com.iot.platform.service.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.model.TelemetryMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class TelemetryPresentationService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String formatInstant(Instant instant) {
        if (instant == null) {
            return "-";
        }
        return TIME_FMT.format(instant);
    }

    public List<TelemetryDisplayRow> formatRecent(List<TelemetryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<TelemetryDisplayRow> rows = new ArrayList<TelemetryDisplayRow>();
        for (TelemetryMessage m : messages) {
            rows.add(fromMessage(m));
        }
        return rows;
    }

    public TelemetryDisplayRow fromMessage(TelemetryMessage m) {
        TelemetryDisplayRow row = new TelemetryDisplayRow();
        row.setTimeDisplay(formatInstant(m.receivedAt()));
        row.setProtocol(m.protocol() != null ? m.protocol() : "-");
        row.setDeviceId(m.deviceId() != null ? m.deviceId() : "-");
        List<PayloadField> fields = enrichUnits(isMqttProtocol(m.protocol())
                ? collectMqttNameValueRows(m.payload())
                : flattenPayload(m.payload()));
        for (PayloadField f : fields) {
            row.addField(f);
        }
        return row;
    }

    /**
     * 将「设备最后一次遥测」缓存（protocol / payload / receivedAt）格式化为一行展示。
     */
    public Optional<TelemetryDisplayRow> formatLastSnapshot(Map<String, Object> snapshot) {
        return formatLastSnapshot(snapshot, null);
    }

    /**
     * @param deviceProtocolHint 设备档案中的协议（如设备详情页传入）。为 MQTT 时<strong>强制</strong>按测点 name/value 解析表格，
     *                           避免缓存里 protocol 缺失或为字面量 "null" 时误走扁平化整包 JSON。
     */
    @SuppressWarnings("unchecked")
    public Optional<TelemetryDisplayRow> formatLastSnapshot(Map<String, Object> snapshot, String deviceProtocolHint) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Optional.empty();
        }
        TelemetryDisplayRow row = new TelemetryDisplayRow();
        String snapProto = normalizeProtocolToken(snapshot.get("protocol"));
        row.setProtocol(snapProto.isEmpty() ? "-" : snapProto);
        Object dev = snapshot.get("deviceId");
        row.setDeviceId(dev != null ? String.valueOf(dev) : "-");

        String received = String.valueOf(snapshot.getOrDefault("receivedAt", ""));
        Instant instant = null;
        try {
            if (received != null && !received.isEmpty() && !"null".equals(received)) {
                instant = Instant.parse(received);
            }
        } catch (Exception ignored) {
        }
        row.setTimeDisplay(instant != null ? formatInstant(instant) : received);

        Object payloadObj = snapshot.get("payload");
        Map<String, Object> payload = null;
        if (payloadObj instanceof Map) {
            payload = (Map<String, Object>) payloadObj;
        }
        boolean mqttTable = isMqttProtocol(snapProto) || isMqttProtocol(deviceProtocolHint);
        List<PayloadField> fields = enrichUnits(
                mqttTable ? collectMqttNameValueRows(payload) : flattenPayload(payload));
        for (PayloadField f : fields) {
            row.addField(f);
        }
        return Optional.of(row);
    }

    /** 快照里 protocol 可能为 null、或经序列化变成字面量 "null"。 */
    private static String normalizeProtocolToken(Object protocolObj) {
        if (protocolObj == null) {
            return "";
        }
        String s = String.valueOf(protocolObj).trim();
        if (s.isEmpty() || "-".equals(s) || "null".equalsIgnoreCase(s)) {
            return "";
        }
        return s;
    }

    private static boolean isMqttProtocol(String protocol) {
        return protocol != null && "MQTT".equalsIgnoreCase(protocol.trim());
    }

    private static final Set<String> INNER_METADATA_KEYS = new HashSet<String>(Arrays.asList(
            "device_id", "deviceId", "device_sn", "deviceSn", "timestamp", "ts", "time"));

    /**
     * 将 json 节点统一为 Map：兼容 {@code Map}、Jackson {@link JsonNode}、以及部分路径下仍为 JSON 字符串的情况。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        if (o instanceof JsonNode) {
            JsonNode n = (JsonNode) o;
            if (n.isObject()) {
                try {
                    return objectMapper.convertValue(n, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception e) {
                    log.debug("JsonNode 转 Map 失败: {}", e.getMessage());
                    return null;
                }
            }
            return null;
        }
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.length() >= 2 && s.charAt(0) == '{') {
                try {
                    return objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * TCP 行上报解析后的设备 JSON：优先 {@code json}，否则尝试把 {@code raw} 整行当 JSON 解析。
     */
    private Map<String, Object> resolveInnerDeviceObjectMap(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Map<String, Object> j = asObjectMap(payload.get("json"));
        if (j != null) {
            return j;
        }
        return asObjectMap(payload.get("raw"));
    }

    private static boolean looksLikeTcpEnvelope(Map<String, Object> payload) {
        return payload.containsKey("raw") && (payload.containsKey("remote") || payload.containsKey("json"));
    }

    /**
     * {@code data: [{"name":"CH4","value":0}, ...]} 形式（仅从设备 JSON 内层读取）。
     */
    private List<PayloadField> tryExtractDataAsNameValueListFromPayload(Map<String, Object> payload) {
        Map<String, Object> inner = resolveInnerDeviceObjectMap(payload);
        if (inner == null) {
            return null;
        }
        Object data = inner.get("data");
        if (!(data instanceof List)) {
            return null;
        }
        List<?> list = (List<?>) data;
        List<PayloadField> out = new ArrayList<PayloadField>();
        for (Object item : list) {
            Map<String, Object> pt = asObjectMap(item);
            if (pt == null || !pt.containsKey("name") || !pt.containsKey("value")) {
                return null;
            }
            out.add(new PayloadField(stringify(pt.get("name")), stringify(pt.get("value")), null));
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * TCP 包在专用规则未命中时：绝不展开 raw/remote，只基于设备 JSON 内层生成表格（必要时展开 {@code data} 子对象）。
     */
    private List<PayloadField> tryRowsFromTcpDeviceJsonOnly(Map<String, Object> payload) {
        if (!looksLikeTcpEnvelope(payload)) {
            return null;
        }
        Map<String, Object> inner = resolveInnerDeviceObjectMap(payload);
        if (inner == null) {
            return null;
        }
        List<PayloadField> scalarData = flattenScalarMapToRows(inner.get("data"));
        if (scalarData != null) {
            return scalarData;
        }
        List<PayloadField> out = new ArrayList<PayloadField>();
        for (Map.Entry<String, Object> e : inner.entrySet()) {
            String k = e.getKey();
            if (INNER_METADATA_KEYS.contains(k)) {
                continue;
            }
            flattenOne(k, e.getValue(), out);
        }
        return out.isEmpty() ? null : out;
    }

    @SuppressWarnings("unchecked")
    private List<PayloadField> flattenScalarMapToRows(Object dataObj) {
        if (dataObj == null) {
            return null;
        }
        if (!(dataObj instanceof Map)) {
            return null;
        }
        Map<String, Object> dm = (Map<String, Object>) dataObj;
        if (dm.isEmpty()) {
            return null;
        }
        for (Object v : dm.values()) {
            if (v instanceof Map || v instanceof List) {
                return null;
            }
        }
        List<PayloadField> out = new ArrayList<PayloadField>();
        for (Map.Entry<String, Object> e : dm.entrySet()) {
            out.add(new PayloadField(e.getKey(), stringify(e.getValue()), null));
        }
        return out;
    }

    /**
     * MQTT 详情/最近列表：仅展示 JSON 内形如 {@code {"name":"…","value":"…"}} 的测点，忽略 topic、body 原文、dir、id、err 等。
     * 同一对 name/value 若在不同分支重复出现（如 json 与 body 双份），只保留首次。
     */
    private List<PayloadField> collectMqttNameValueRows(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Collections.emptyList();
        }
        List<PayloadField> out = new ArrayList<PayloadField>();
        Set<String> seen = new HashSet<String>();
        collectMqttNameValueRecursive(payload, out, seen);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void collectMqttNameValueRecursive(Object node, List<PayloadField> out, Set<String> seen) {
        if (node == null) {
            return;
        }
        if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            if (isMqttNameValuePoint(m)) {
                addMqttPointIfUnique(out, seen, m.get("name"), m.get("value"));
                return;
            }
            for (Object v : m.values()) {
                collectMqttNameValueRecursive(v, out, seen);
            }
            return;
        }
        if (node instanceof JsonNode) {
            JsonNode j = (JsonNode) node;
            if (j.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> asMap = objectMapper.convertValue(j, Map.class);
                collectMqttNameValueRecursive(asMap, out, seen);
            } else if (j.isArray()) {
                for (JsonNode item : j) {
                    collectMqttNameValueRecursive(item, out, seen);
                }
            }
            return;
        }
        if (node instanceof List) {
            for (Object item : (List<?>) node) {
                collectMqttNameValueRecursive(item, out, seen);
            }
            return;
        }
        if (node instanceof String) {
            String s = ((String) node).trim();
            if (s.length() >= 2 && (s.charAt(0) == '{' || s.charAt(0) == '[')) {
                try {
                    Object parsed = objectMapper.readValue(s, Object.class);
                    collectMqttNameValueRecursive(parsed, out, seen);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean isMqttNameValuePoint(Map<String, Object> m) {
        if (m == null || !m.containsKey("name") || !m.containsKey("value")) {
            return false;
        }
        return m.get("name") != null;
    }

    private static void addMqttPointIfUnique(List<PayloadField> out, Set<String> seen, Object nameObj, Object valObj) {
        String n = stringify(nameObj);
        String v = stringify(valObj);
        String sig = n + "\u0000" + v;
        if (!seen.add(sig)) {
            return;
        }
        out.add(new PayloadField(n, v, null));
    }

    /**
     * 四合一等上报：{@code json.sensors} 下按气体名为 key，值为含 {@code v}（及可选 {@code u}）的对象时，只展示这些行。
     */
    @SuppressWarnings("unchecked")
    private List<PayloadField> tryExtractSensorsVMapRows(Map<String, Object> payload) {
        Map<String, Object> json = resolveInnerDeviceObjectMap(payload);
        if (json == null) {
            return null;
        }
        Object sObj = json.get("sensors");
        if (!(sObj instanceof Map)) {
            return null;
        }
        Map<String, Object> sensors = (Map<String, Object>) sObj;
        if (sensors.isEmpty()) {
            return null;
        }
        List<PayloadField> out = new ArrayList<PayloadField>();
        for (Map.Entry<String, Object> e : sensors.entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                return null;
            }
            Map<String, Object> pt = (Map<String, Object>) e.getValue();
            if (!pt.containsKey("v")) {
                return null;
            }
            Object u = pt.get("u");
            String unit = u != null && !(u instanceof Map) && !(u instanceof List)
                    ? stringify(u)
                    : null;
            out.add(new PayloadField(e.getKey(), stringify(pt.get("v")), unit));
        }
        return out;
    }

    /**
     * TCP/HTTP 等上报里常见：负载含解析后的 {@code json}，且 {@code json.data} 为「指标名 → 数值」的平铺对象
     * （如四合一气体）。此时表格<strong>只</strong>展示这些行，不再列出 raw、remote、device_id 等整包扁平字段。
     */
    @SuppressWarnings("unchecked")
    private List<PayloadField> tryExtractSimpleDataMapRows(Map<String, Object> payload) {
        Map<String, Object> dataMap = resolveSimpleDataObject(payload);
        return flattenScalarMapToRows(dataMap);
    }

    /**
     * 优先设备 JSON 内 {@code data}，否则 HTTP 等顶层 {@code data}。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveSimpleDataObject(Map<String, Object> payload) {
        Map<String, Object> inner = resolveInnerDeviceObjectMap(payload);
        if (inner != null) {
            Object d = inner.get("data");
            if (d instanceof Map) {
                return (Map<String, Object>) d;
            }
        }
        Object d = payload.get("data");
        if (d instanceof Map) {
            return (Map<String, Object>) d;
        }
        return null;
    }

    /**
     * 与早期实现一致：对 payload 顶层键整包扁平（含 raw / json / remote），供解析异常时回退，避免阻断入库链路。
     */
    private List<PayloadField> flattenPayloadEnvelopeOnly(Map<String, Object> payload) {
        List<PayloadField> out = new ArrayList<PayloadField>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            flattenOne(e.getKey(), e.getValue(), out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<PayloadField> flattenPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<PayloadField> nameValueList = tryExtractDataAsNameValueListFromPayload(payload);
            if (nameValueList != null) {
                return nameValueList;
            }
            List<PayloadField> dataOnly = tryExtractSimpleDataMapRows(payload);
            if (dataOnly != null) {
                return dataOnly;
            }
            List<PayloadField> sensorsOnly = tryExtractSensorsVMapRows(payload);
            if (sensorsOnly != null) {
                return sensorsOnly;
            }
            List<PayloadField> tcpInner = tryRowsFromTcpDeviceJsonOnly(payload);
            if (tcpInner != null) {
                return tcpInner;
            }
            return flattenPayloadEnvelopeOnly(payload);
        } catch (Exception e) {
            log.warn("flattenPayload 解析异常，回退为整包扁平: {}", e.getMessage());
            return flattenPayloadEnvelopeOnly(payload);
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenOne(String key, Object val, List<PayloadField> out) {
        if (val == null) {
            out.add(new PayloadField(key, "—", null));
            return;
        }
        if (val instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) val;
            if (m.containsKey("v")) {
                String v = stringify(m.get("v"));
                String u = m.containsKey("u") ? stringify(m.get("u")) : null;
                out.add(new PayloadField(key, v, u));
                return;
            }
            if (m.isEmpty()) {
                out.add(new PayloadField(key, "{}", null));
                return;
            }
            for (Map.Entry<String, Object> sub : m.entrySet()) {
                flattenOne(key + " · " + sub.getKey(), sub.getValue(), out);
            }
            return;
        }
        if (val instanceof List) {
            try {
                out.add(new PayloadField(key, objectMapper.writeValueAsString(val), null));
            } catch (Exception e) {
                out.add(new PayloadField(key, val.toString(), null));
            }
            return;
        }
        out.add(new PayloadField(key, String.valueOf(val), null));
    }

    private static String stringify(Object o) {
        if (o == null) {
            return "—";
        }
        if (o instanceof Number) {
            Number n = (Number) o;
            if (o instanceof Double || o instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    return String.valueOf(o);
                }
                long r = Math.round(d);
                if (Math.abs(d - r) < 1e-9) {
                    return String.valueOf(r);
                }
                return String.format(java.util.Locale.ROOT, "%.3f", d);
            }
            return n.toString();
        }
        return String.valueOf(o);
    }

    public String serializeDisplayRow(TelemetryDisplayRow row) throws JsonProcessingException {
        return objectMapper.writeValueAsString(row);
    }

    public Optional<TelemetryDisplayRow> deserializeDisplayRow(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            TelemetryDisplayRow row = objectMapper.readValue(json, TelemetryDisplayRow.class);
            if (row.getFields() == null) {
                row.setFields(new ArrayList<PayloadField>());
            }
            return Optional.of(row);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 本条内存快照无表格行时，沿用库中上次非空行；时间 / 协议 / 设备编号取当前快照。
     */
    public TelemetryDisplayRow mergeLiveHeaderWithStoredFields(TelemetryDisplayRow live, String storedJson) {
        Optional<TelemetryDisplayRow> st = deserializeDisplayRow(storedJson);
        if (!st.isPresent() || st.get().getFields() == null || st.get().getFields().isEmpty()) {
            return live;
        }
        TelemetryDisplayRow out = new TelemetryDisplayRow();
        out.setTimeDisplay(live.getTimeDisplay());
        out.setProtocol(live.getProtocol());
        out.setDeviceId(live.getDeviceId());
        for (PayloadField f : enrichUnits(st.get().getFields())) {
            out.addField(f);
        }
        return out;
    }

    /** JSON 无单位时按测点名称映射单位。 */
    private List<PayloadField> enrichUnits(List<PayloadField> fields) {
        if (fields == null || fields.isEmpty()) {
            return fields == null ? Collections.emptyList() : fields;
        }
        for (PayloadField f : fields) {
            if (f == null) {
                continue;
            }
            String mapped = TelemetryUnitMapper.resolve(f.getName(), f.getUnit());
            if (mapped != null) {
                f.setUnit(mapped);
            }
        }
        return fields;
    }
}
