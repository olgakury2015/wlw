package com.iot.platform.service.geocode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.GeocodingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 使用 Nominatim 将文本地址解析为 WGS84 坐标。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NominatimGeocodingService {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final GeocodingProperties geocodingProperties;

    public Optional<AddressGeocodeResult> geocode(String address) {
        if (address == null || address.trim().isEmpty()) {
            return Optional.empty();
        }
        if (!geocodingProperties.isEnabled()) {
            return Optional.empty();
        }
        String ua = geocodingProperties.getUserAgent();
        if (ua == null || ua.trim().isEmpty()) {
            throw new IllegalStateException("iot.geocoding.user-agent 未配置");
        }

        URI uri = UriComponentsBuilder
                .fromHttpUrl(geocodingProperties.getNominatimBaseUrl() + "/search")
                .queryParam("format", "json")
                .queryParam("q", address.trim())
                .queryParam("limit", 1)
                .build()
                .encode()
                .toUri();

        WebClient client = webClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, ua)
                .build();

        try {
            String body = client.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(geocodingProperties.getReadTimeoutMs() + geocodingProperties.getConnectTimeoutMs()));

            if (body == null || body.trim().isEmpty()) {
                return Optional.empty();
            }
            JsonNode arr = objectMapper.readTree(body);
            if (!arr.isArray() || arr.size() == 0) {
                return Optional.empty();
            }
            JsonNode first = arr.get(0);
            if (!first.has("lat") || !first.has("lon")) {
                return Optional.empty();
            }
            double lat = Double.parseDouble(first.get("lat").asText());
            double lon = Double.parseDouble(first.get("lon").asText());
            String label = first.has("display_name") ? first.get("display_name").asText("") : "";
            return Optional.of(new AddressGeocodeResult(lat, lon, label, false));
        } catch (WebClientResponseException e) {
            log.warn("Nominatim HTTP {}: {}", e.getRawStatusCode(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Nominatim 解析失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * WGS84 坐标 → 可读地址（Nominatim {@code /reverse} 的 {@code display_name}）。
     * 需 {@link GeocodingProperties#isEnabled()} 且配置 {@code user-agent}。
     */
    public Optional<String> reverseDisplayName(double lat, double lon) {
        if (!geocodingProperties.isEnabled()) {
            return Optional.empty();
        }
        String ua = geocodingProperties.getUserAgent();
        if (ua == null || ua.trim().isEmpty()) {
            return Optional.empty();
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return Optional.empty();
        }
        URI uri = UriComponentsBuilder
                .fromHttpUrl(geocodingProperties.getNominatimBaseUrl() + "/reverse")
                .queryParam("format", "json")
                .queryParam("lat", lat)
                .queryParam("lon", lon)
                .queryParam("zoom", 18)
                .build()
                .encode()
                .toUri();

        WebClient client = webClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, ua)
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
                .build();

        try {
            String body = client.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(geocodingProperties.getReadTimeoutMs() + geocodingProperties.getConnectTimeoutMs()));

            if (body == null || body.trim().isEmpty()) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(body);
            if (!root.has("display_name")) {
                return Optional.empty();
            }
            String name = root.get("display_name").asText("").trim();
            if (name.isEmpty()) {
                return Optional.empty();
            }
            if (name.length() > 512) {
                name = name.substring(0, 512);
            }
            return Optional.of(name);
        } catch (WebClientResponseException e) {
            log.warn("Nominatim reverse HTTP {}: {}", e.getRawStatusCode(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Nominatim reverse 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
