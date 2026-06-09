package com.iot.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 首页地图标记（JSON 序列化给前端高德 JS API）。
 */
@Getter
@AllArgsConstructor
public class DeviceMapPinDto {
    private final String name;
    private final String deviceSn;
    private final double lat;
    private final double lng;
    private final String status;
    /** 用户填写的地址（地图气泡展示）。 */
    private final String address;
    /** 数据库坐标是否为 GCJ-02；否则按 WGS84 由前端转为高德坐标。 */
    private final boolean gcj02;
}
