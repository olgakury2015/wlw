package com.iot.platform.alert.service;

import com.iot.platform.alert.entity.ThresholdRule;
import com.iot.platform.alert.repo.ThresholdRuleRepository;
import com.iot.platform.ops.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ThresholdRuleService {

    private final ThresholdRuleRepository thresholdRuleRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<ThresholdRule> listByKind(String kind) {
        String k = kind != null ? kind.trim().toUpperCase() : "ALERT";
        return thresholdRuleRepository.findByKindIgnoreCaseOrderByIdDesc(k);
    }

    @Transactional
    public ThresholdRule create(String name, String kind, String deviceSn, String metricKey,
                                String operator, double threshold, boolean enabled, String webhookUrl) {
        validate(name, metricKey, operator, kind);
        ThresholdRule r = new ThresholdRule();
        r.setName(name.trim());
        r.setKind(kind != null && kind.toUpperCase().contains("LINK") ? "LINKAGE" : "ALERT");
        r.setDeviceSn(trimToNull(deviceSn));
        r.setMetricKey(metricKey.trim());
        r.setOperator(normalizeOp(operator));
        r.setThreshold(threshold);
        r.setEnabled(enabled);
        r.setWebhookUrl(trimToNull(webhookUrl));
        ThresholdRule saved = thresholdRuleRepository.save(r);
        auditLogService.log("THRESHOLD_RULE_CREATE", "kind=" + saved.getKind() + " id=" + saved.getId() + " name=" + saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        thresholdRuleRepository.findById(id).ifPresent(r ->
                auditLogService.log("THRESHOLD_RULE_DELETE", "id=" + id + " name=" + r.getName()));
        thresholdRuleRepository.deleteById(id);
    }

    private static void validate(String name, String metricKey, String operator, String kind) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        if (metricKey == null || metricKey.trim().isEmpty()) {
            throw new IllegalArgumentException("指标字段名不能为空，如 temp、hum");
        }
        normalizeOp(operator);
        if (kind == null) {
            throw new IllegalArgumentException("类型无效");
        }
    }

    private static String normalizeOp(String operator) {
        String o = operator != null ? operator.trim().toUpperCase() : "GT";
        if (!o.matches("GT|LT|GE|LE|EQ")) {
            throw new IllegalArgumentException("比较符仅支持 GT / LT / GE / LE / EQ");
        }
        return o;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
