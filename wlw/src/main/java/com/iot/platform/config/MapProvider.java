package com.iot.platform.config;

/**
 * 控制台地图底图提供方。
 */
public enum MapProvider {
    /** OpenStreetMap + Leaflet，无需 Key，坐标系 WGS84，适合商用自建。 */
    OSM,
    /** 高德 JS API，需 Web 端 Key 与安全密钥。 */
    GAODE,
    /** 已配置高德 Key 时用高德，否则回退 OSM。 */
    AUTO;

    public static MapProvider parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return AUTO;
        }
        switch (raw.trim().toLowerCase()) {
            case "osm":
            case "openstreetmap":
            case "leaflet":
                return OSM;
            case "gaode":
            case "amap":
            case "gaode-map":
                return GAODE;
            case "auto":
            default:
                return AUTO;
        }
    }
}
