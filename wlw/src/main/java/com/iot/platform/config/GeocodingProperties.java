package com.iot.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 地址 → 坐标（基于 OpenStreetMap Nominatim，需遵守其使用政策并设置可识别的 User-Agent）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "iot.geocoding")
public class GeocodingProperties {

    /**
     * 关闭后：若用户填写了地址，将提示无法解析（不再静默忽略）。
     */
    private boolean enabled = true;

    /**
     * Nominatim 根地址，勿带末尾斜杠。
     */
    private String nominatimBaseUrl = "https://nominatim.openstreetmap.org";

    /**
     * 必填：联系信息，便于 Nominatim 识别调用方（可改为你的邮箱或项目主页）。
     */
    private String userAgent = "WLW-IoT-Platform/1.0 (internal)";

    private int connectTimeoutMs = 8000;

    private int readTimeoutMs = 12000;
}
