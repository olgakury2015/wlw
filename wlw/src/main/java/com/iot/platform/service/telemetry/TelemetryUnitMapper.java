package com.iot.platform.service.telemetry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 测点名称 → 单位：MQTT/TCP JSON 常不带单位字段，详情页展示前按名称补全。
 */
public final class TelemetryUnitMapper {

    private static final Map<String, String> BY_NAME = new LinkedHashMap<>();

    static {
        BY_NAME.put("PM0.5", "pc/L");
        BY_NAME.put("PM1.0", "pc/L");
        BY_NAME.put("PM5.0", "pc/L");
        BY_NAME.put("Temperature", "℃");
        BY_NAME.put("Humidity", "%RH");
        BY_NAME.put("Diff_Pressure", "Pa");
        BY_NAME.put("data_ch4", "%LEL");
        BY_NAME.put("data_02", "%VOL");
        BY_NAME.put("data_o2", "%VOL");
        BY_NAME.put("data_co", "ppm");
        BY_NAME.put("data_h2s", "ppm");
    }

    private TelemetryUnitMapper() {
    }

    /**
     * @param name         测点名称（如 Temperature、data_co）
     * @param existingUnit JSON 中已有单位（如 sensors.x.u），非空则优先使用
     */
    public static String resolve(String name, String existingUnit) {
        if (existingUnit != null && !existingUnit.trim().isEmpty() && !"—".equals(existingUnit.trim())) {
            return existingUnit.trim();
        }
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String key = name.trim();
        String exact = BY_NAME.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : BY_NAME.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : BY_NAME.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return e.getValue();
            }
        }
        return null;
    }
}
