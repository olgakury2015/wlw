package com.iot.platform.service.geocode;

import com.iot.platform.config.IotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 优先使用高德 Web 服务地理编码（已配置 {@code iot.maps.gaode.web-service-key} 时），
 * 无结果或未配置时回退 Nominatim。
 */
@Service
@Primary
@RequiredArgsConstructor
public class CompositeGeocodingService implements GeocodingClient {

    private final AmapGeocodingService amapGeocodingService;
    private final NominatimGeocodingService nominatimGeocodingService;
    private final IotProperties iotProperties;

    @Override
    public Optional<AddressGeocodeResult> geocode(String address) {
        if (hasAmapWebKey()) {
            Optional<AddressGeocodeResult> fromAmap = amapGeocodingService.geocode(address);
            if (fromAmap.isPresent()) {
                return fromAmap;
            }
        }
        return nominatimGeocodingService.geocode(address);
    }

    private boolean hasAmapWebKey() {
        if (iotProperties.getMaps() == null || iotProperties.getMaps().getGaode() == null) {
            return false;
        }
        String k = iotProperties.getMaps().getGaode().getWebServiceKey();
        return k != null && !k.trim().isEmpty();
    }
}
