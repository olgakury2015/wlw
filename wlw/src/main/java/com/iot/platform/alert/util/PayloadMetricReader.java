package com.iot.platform.alert.util;

import java.util.Map;

/**
 * 从遥测 payload 中读取数值：支持扁平 key 或物模型风格 { key: { v: number } }。
 */
public final class PayloadMetricReader {

    private PayloadMetricReader() {
    }

    @SuppressWarnings("unchecked")
    public static Double readNumber(Map<String, Object> payload, String metricKey) {
        if (payload == null || metricKey == null || metricKey.trim().isEmpty()) {
            return null;
        }
        Object o = payload.get(metricKey.trim());
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof Map) {
            Object v = ((Map<String, Object>) o).get("v");
            if (v instanceof Number) {
                return ((Number) v).doubleValue();
            }
        }
        return null;
    }
}
