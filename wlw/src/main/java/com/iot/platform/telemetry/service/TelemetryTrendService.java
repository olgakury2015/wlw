package com.iot.platform.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.telemetry.entity.TelemetryHistoryRecord;
import com.iot.platform.telemetry.repo.TelemetryHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从时序库解析数值字段，供设备详情折线图使用。
 */
@Service
@RequiredArgsConstructor
public class TelemetryTrendService {

    private static final DateTimeFormatter LBL = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final TelemetryHistoryRepository telemetryHistoryRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Map<String, Object> buildChartModel(String deviceSn, int hoursBack, int maxPoints) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", new ArrayList<String>());
        out.put("series", new LinkedHashMap<String, List<Double>>());
        if (deviceSn == null || deviceSn.trim().isEmpty()) {
            return out;
        }
        Instant to = Instant.now();
        Instant from = to.minusSeconds(Math.max(1, hoursBack) * 3600L);
        List<TelemetryHistoryRecord> rows = telemetryHistoryRepository
                .findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                        deviceSn.trim(), from, to, PageRequest.of(0, Math.max(10, Math.min(maxPoints, 500))));

        if (rows.isEmpty()) {
            return out;
        }

        Set<String> keys = new LinkedHashSet<>();
        List<Map<String, Double>> rowValues = new ArrayList<>();
        for (TelemetryHistoryRecord r : rows) {
            Map<String, Double> nums = extractNumericMap(r.getPayloadJson());
            rowValues.add(nums);
            keys.addAll(nums.keySet());
        }
        if (keys.isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (TelemetryHistoryRecord r : rows) {
                labels.add(LBL.format(r.getReceivedAt()));
            }
            out.put("labels", labels);
            return out;
        }

        Map<String, List<Double>> series = new LinkedHashMap<>();
        for (String k : keys) {
            series.put(k, new ArrayList<Double>());
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            TelemetryHistoryRecord r = rows.get(i);
            labels.add(LBL.format(r.getReceivedAt()));
            Map<String, Double> nums = rowValues.get(i);
            for (String k : keys) {
                Double v = nums.get(k);
                series.get(k).add(v);
            }
        }
        out.put("labels", labels);
        out.put("series", series);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> extractNumericMap(String json) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return m;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            flattenNumbers("", root, m);
        } catch (Exception ignored) {
            // ignore bad json
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void flattenNumbers(String prefix, Map<String, Object> node, Map<String, Double> out) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            Double d = toDouble(v);
            if (d != null) {
                out.putIfAbsent(key, d);
                continue;
            }
            if (v instanceof Map) {
                Object innerV = ((Map<String, Object>) v).get("v");
                Double inner = toDouble(innerV);
                if (inner != null) {
                    out.putIfAbsent(key, inner);
                }
            }
        }
    }

    private static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
