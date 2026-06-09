package com.iot.platform.service.geocode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AddressGeocodeResult {
    private final double latitude;
    private final double longitude;
    /** Nominatim 返回的展示名，可用于核对。 */
    private final String resolvedLabel;
    /** 高德地理编码为 GCJ-02；Nominatim 等为 WGS84。 */
    private final boolean gcj02;
}
