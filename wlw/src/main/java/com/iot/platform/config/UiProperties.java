package com.iot.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ui")
public class UiProperties {

    /**
     * 首页、设备详情等遥测相关页面自动整页刷新间隔（秒）。
     */
    private int pageAutoRefreshSeconds = 10;
}
