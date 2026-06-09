package com.iot.platform.alert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 向钉钉机器人等地址 POST JSON（钉钉 text 消息格式）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookNotifier {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public void sendDingTalkStyle(String webhookUrl, String textContent) {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("msgtype", "text");
            ObjectNode text = objectMapper.createObjectNode();
            text.put("content", textContent);
            root.set("text", text);
            String body = objectMapper.writeValueAsString(root);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(webhookUrl.trim(), new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.warn("Webhook 调用失败: {}", e.getMessage());
        }
    }
}
