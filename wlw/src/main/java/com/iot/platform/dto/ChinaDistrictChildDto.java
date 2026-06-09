package com.iot.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行政区域子节点（高德 Web 服务或内置 pca 数据），用于控制台省市区联动。
 */
@Getter
@AllArgsConstructor
public class ChinaDistrictChildDto {
    private final String adcode;
    private final String name;
    /** 高德 GCJ-02 中心点经度；内置数据可能为 null，此时需地图选点或手输经纬度。 */
    private final Double centerLng;
    /** 高德 GCJ-02 中心点纬度。 */
    private final Double centerLat;
    private final String level;
}
