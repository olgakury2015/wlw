package com.iot.platform.management.controller;

import com.iot.platform.service.geocode.AddressGeocodeResult;
import com.iot.platform.service.geocode.CompositeGeocodingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 文本地址 → 坐标（Nominatim 或可选高德 Web 服务），供 OSM 地图下省市区联动补点。
 */
@RestController
@RequestMapping("/api/v1/management/geocode")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AddressGeocodeApiController {

    private final CompositeGeocodingService compositeGeocodingService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> geocode(@RequestParam String address) {
        if (address == null || address.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Optional<AddressGeocodeResult> opt = compositeGeocodingService.geocode(address.trim());
        if (!opt.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        AddressGeocodeResult r = opt.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lat", r.getLatitude());
        body.put("lng", r.getLongitude());
        body.put("gcj02", r.isGcj02());
        body.put("label", r.getResolvedLabel());
        return ResponseEntity.ok(body);
    }
}
