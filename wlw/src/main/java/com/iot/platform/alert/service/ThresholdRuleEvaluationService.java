package com.iot.platform.alert.service;

import com.iot.platform.alert.entity.AlertChannelRecord;
import com.iot.platform.alert.entity.ThresholdRule;
import com.iot.platform.alert.repo.ThresholdRuleRepository;
import com.iot.platform.alert.util.PayloadMetricReader;
import com.iot.platform.management.service.DeviceRegistryService;
import com.iot.platform.model.TelemetryMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ThresholdRuleEvaluationService {

    private final ThresholdRuleRepository thresholdRuleRepository;
    private final AlertChannelService alertChannelService;
    private final WebhookNotifier webhookNotifier;
    private final DeviceRegistryService deviceRegistryService;

    public void onTelemetry(TelemetryMessage msg) {
        if (msg == null || msg.deviceId() == null || msg.payload() == null) {
            return;
        }
        String deviceId = msg.deviceId().trim();
        Map<String, Object> payload = msg.payload();
        AlertChannelRecord channel = alertChannelService.getOrEmpty();
        String fallback = channel.getFallbackWebhook();

        for (ThresholdRule r : thresholdRuleRepository.findByEnabledTrue()) {
            if (!matchesDevice(r.getDeviceSn(), deviceId)) {
                continue;
            }
            Double v = PayloadMetricReader.readNumber(payload, r.getMetricKey());
            if (v == null) {
                continue;
            }
            if (!compare(v, r.getThreshold(), r.getOperator())) {
                continue;
            }
            String url = firstNonEmpty(r.getWebhookUrl(), fallback);
            if (url == null) {
                continue;
            }
            boolean alert = "ALERT".equalsIgnoreCase(r.getKind());
            String prefix = alert ? "【告警】" : "【场景联动】";
            String text = prefix + " 设备 " + deviceId + " 指标 " + r.getMetricKey() + "=" + v
                    + " 触发规则「" + r.getName() + "」";
            webhookNotifier.sendDingTalkStyle(url, text);
            if (alert) {
                deviceRegistryService.incrementAlarmCount(deviceId);
            }
        }
    }

    private static boolean matchesDevice(String ruleSn, String deviceId) {
        if (ruleSn == null || ruleSn.trim().isEmpty()) {
            return true;
        }
        return ruleSn.trim().equals(deviceId);
    }

    private static boolean compare(double value, double th, String op) {
        if (op == null) {
            return false;
        }
        switch (op.trim().toUpperCase()) {
            case "GT":
                return value > th;
            case "LT":
                return value < th;
            case "GE":
                return value >= th;
            case "LE":
                return value <= th;
            case "EQ":
                return Math.abs(value - th) < 1e-9;
            default:
                return false;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }
}
