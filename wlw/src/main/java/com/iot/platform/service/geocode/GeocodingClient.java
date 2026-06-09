package com.iot.platform.service.geocode;

import java.util.Optional;

/**
 * 文本地址 → 坐标（WGS84 或 GCJ-02，由具体实现决定）。
 */
public interface GeocodingClient {

    Optional<AddressGeocodeResult> geocode(String address);
}
