package com.iot.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "nodered")
public class NodeRedProperties {
    private String baseUrl = "http://127.0.0.1:1880";
    private String metricsPath = "/wlw/metrics";
    private String rulePath = "/wlw/rule";
}
