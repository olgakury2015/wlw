package com.iot.platform.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * 向 Thymeleaf 注入地图相关模型属性（高德 / OpenStreetMap）。
 */
@Component
@RequiredArgsConstructor
public class MapViewAttributes {

    private final IotProperties iotProperties;
    private final ObjectMapper objectMapper;

    public MapProvider effectiveProvider() {
        IotProperties.Maps maps = iotProperties.getMaps();
        MapProvider configured = MapProvider.parse(maps.getProvider());
        if (configured == MapProvider.OSM) {
            return MapProvider.OSM;
        }
        if (configured == MapProvider.GAODE) {
            return isGaodeJsReady() ? MapProvider.GAODE : null;
        }
        return isGaodeJsReady() ? MapProvider.GAODE : MapProvider.OSM;
    }

    public void apply(Model model) {
        MapProvider effective = effectiveProvider();
        boolean mapJsOk = effective != null;
        boolean gaode = effective == MapProvider.GAODE;

        model.addAttribute("mapJsConfigured", mapJsOk);
        model.addAttribute("mapProvider", effective != null ? effective.name().toLowerCase() : "");
        model.addAttribute("amapJsConfigured", gaode);
        model.addAttribute("osmMapConfigured", effective == MapProvider.OSM);

        IotProperties.Gaode gaodeCfg = iotProperties.getMaps().getGaode();
        String jsKey = gaodeCfg.getJsApiKey();
        String secCode = gaodeCfg.getSecurityJsCode();
        boolean amapKeys = isGaodeJsReady();
        model.addAttribute("gaodeJsApiKey", amapKeys && jsKey != null ? jsKey.trim() : "");
        model.addAttribute("gaodeSecurityJsCode", amapKeys && secCode != null ? secCode.trim() : "");

        IotProperties.Osm osm = iotProperties.getMaps().getOsm();
        model.addAttribute("osmTileUrl", osm.getTileUrl());
        model.addAttribute("osmAttribution", osm.getAttribution());
        String tdtTk = osm.getTiandituTk();
        boolean useTdt = tdtTk != null && !tdtTk.trim().isEmpty();
        model.addAttribute("osmTiandituTk", useTdt ? tdtTk.trim() : "");
        model.addAttribute("osmUseGcjMap", useTdt);
        try {
            model.addAttribute("osmTileFallbackUrlsJson",
                    objectMapper.writeValueAsString(
                            osm.getTileFallbackUrls() != null ? osm.getTileFallbackUrls() : java.util.Collections.emptyList()));
        } catch (JsonProcessingException e) {
            model.addAttribute("osmTileFallbackUrlsJson", "[]");
        }
    }

    private boolean isGaodeJsReady() {
        IotProperties.Gaode g = iotProperties.getMaps().getGaode();
        String jsKey = g.getJsApiKey();
        String secCode = g.getSecurityJsCode();
        return jsKey != null && !jsKey.trim().isEmpty()
                && secCode != null && !secCode.trim().isEmpty();
    }
}
