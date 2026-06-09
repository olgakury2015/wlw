package com.iot.platform.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.NodeRedProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node-RED 双向集成：
 * <ul>
 *     <li>WebClient：调用 Node-RED 暴露的 HTTP 端点（适合非阻塞与链式编排）</li>
 *     <li>RestTemplate：同步调用 Node-RED 规则/流程入口</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NodeRedIntegrationService {

    private final NodeRedProperties nodeRedProperties;
    private final RestTemplate restTemplate;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public Map<String, Object> fetchMetricsWebClient() {
        URI uri = UriComponentsBuilder.fromUriString(nodeRedProperties.getBaseUrl())
                .path(nodeRedProperties.getMetricsPath())
                .build()
                .toUri();
        String body = webClientBuilder.build()
                .get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                .blockOptional()
                .orElse("{}");
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("raw", body);
            m.put("parseError", e.getMessage());
            return m;
        }
    }

    public ResponseEntity<String> postRuleRestTemplate(Map<String, Object> body) {
        URI uri = UriComponentsBuilder.fromUriString(nodeRedProperties.getBaseUrl())
                .path(nodeRedProperties.getRulePath())
                .build()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForEntity(uri, entity, String.class);
    }
}
