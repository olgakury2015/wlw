package com.iot.platform.service.geocode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.IotProperties;
import com.iot.platform.dto.ChinaDistrictChildDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 高德「行政区域查询」Web 服务（/v3/config/district），用于省市区三级数据。
 *
 * @see <a href="https://lbs.amap.com/api/webservice/guide/api/district">行政区域查询</a>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AmapDistrictService {

    private static final String DISTRICT_URL = "https://restapi.amap.com/v3/config/district";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final IotProperties iotProperties;
    private final BundledChinaDistrictRepository bundledChinaDistrictRepository;

    /**
     * @param parentAdcode 父级 adcode；空或 null 表示取全国省级列表（keywords=中国）
     */
    public List<ChinaDistrictChildDto> listChildren(String parentAdcode) {
        String key = webServiceKey();
        String parent = (parentAdcode == null || parentAdcode.trim().isEmpty()) ? "" : parentAdcode.trim();
        if (key == null || key.trim().isEmpty()) {
            return bundledChinaDistrictRepository.listChildren(parent);
        }
        List<ChinaDistrictChildDto> fromChina = fetchDistrictChildren(key, parent.isEmpty() ? "中国" : parent);
        if (fromChina.isEmpty() && parent.isEmpty()) {
            fromChina = fetchDistrictChildren(key, "100000");
        }
        if (!fromChina.isEmpty()) {
            return fromChina;
        }
        return bundledChinaDistrictRepository.listChildren(parent);
    }

    private List<ChinaDistrictChildDto> fetchDistrictChildren(String key, String keywords) {
        URI uri = UriComponentsBuilder.fromHttpUrl(DISTRICT_URL)
                .queryParam("key", key.trim())
                .queryParam("keywords", keywords)
                .queryParam("subdistrict", 1)
                .queryParam("extensions", "base")
                .build()
                .encode()
                .toUri();

        WebClient client = webClientBuilder.build();
        try {
            String body = client.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (body == null || body.trim().isEmpty()) {
                return Collections.emptyList();
            }
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                log.warn("高德行政区域 status!=1 info={}", root.path("info").asText());
                return Collections.emptyList();
            }
            JsonNode arr = root.path("districts");
            if (!arr.isArray() || arr.size() == 0) {
                return Collections.emptyList();
            }
            JsonNode first = arr.get(0);
            JsonNode children = first.path("districts");
            if (!children.isArray()) {
                return Collections.emptyList();
            }
            List<ChinaDistrictChildDto> out = new ArrayList<>();
            for (JsonNode n : children) {
                ChinaDistrictChildDto dto = parseChild(n);
                if (dto != null) {
                    out.add(dto);
                }
            }
            return out;
        } catch (WebClientResponseException e) {
            log.warn("高德行政区域 HTTP {}: {}", e.getRawStatusCode(), e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("高德行政区域请求失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static ChinaDistrictChildDto parseChild(JsonNode n) {
        String adcode = n.path("adcode").asText("");
        String name = n.path("name").asText("");
        String center = n.path("center").asText("");
        String level = n.path("level").asText("");
        if (adcode.isEmpty() || name.isEmpty() || center.isEmpty()) {
            return null;
        }
        String[] parts = center.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            return new ChinaDistrictChildDto(adcode, name, Double.valueOf(lng), Double.valueOf(lat), level);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String webServiceKey() {
        if (iotProperties.getMaps() == null || iotProperties.getMaps().getGaode() == null) {
            return "";
        }
        return iotProperties.getMaps().getGaode().getWebServiceKey();
    }
}
