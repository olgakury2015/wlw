package com.iot.platform.service.geocode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.IotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 高德开放平台「地理编码」Web 服务（/v3/geocode/geo）。
 * 返回坐标为 <strong>GCJ-02</strong>（国测局），与 OSM/WGS84 地图叠用时可能有数百米偏差；若首页改用高德 JS 底图则一致。
 *
 * @see <a href="https://lbs.amap.com/api/webservice/guide/api/georegeo">高德地理编码 API</a>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AmapGeocodingService {

    private static final String GEO_URL = "https://restapi.amap.com/v3/geocode/geo";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final IotProperties iotProperties;

    public Optional<AddressGeocodeResult> geocode(String address) {
        String key = webServiceKey();
        if (key == null || key.trim().isEmpty()) {
            return Optional.empty();
        }
        if (address == null || address.trim().isEmpty()) {
            return Optional.empty();
        }

        URI uri = UriComponentsBuilder.fromHttpUrl(GEO_URL)
                .queryParam("key", key.trim())
                .queryParam("address", address.trim())
                .build()
                .encode()
                .toUri();

        WebClient client = webClientBuilder.build();
        try {
            String body = client.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (body == null || body.trim().isEmpty()) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                log.warn("高德地理编码 status!=1 info={}", root.path("info").asText());
                return Optional.empty();
            }
            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.size() == 0) {
                return Optional.empty();
            }
            JsonNode first = geocodes.get(0);
            String loc = first.path("formatted_address").asText("");
            String location = first.path("location").asText("");
            if (location.isEmpty()) {
                return Optional.empty();
            }
            String[] parts = location.split(",");
            if (parts.length != 2) {
                return Optional.empty();
            }
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            String label = loc.isEmpty() ? address.trim() : loc;
            return Optional.of(new AddressGeocodeResult(lat, lng, label + "（高德 GCJ-02）", true));
        } catch (WebClientResponseException e) {
            log.warn("高德地理编码 HTTP {}: {}", e.getRawStatusCode(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("高德地理编码失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String webServiceKey() {
        if (iotProperties.getMaps() == null || iotProperties.getMaps().getGaode() == null) {
            return "";
        }
        return iotProperties.getMaps().getGaode().getWebServiceKey();
    }
}
