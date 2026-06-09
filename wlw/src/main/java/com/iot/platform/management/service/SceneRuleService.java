package com.iot.platform.management.service;

import com.iot.platform.management.entity.SceneRule;
import com.iot.platform.management.repo.SceneRuleRepository;
import com.iot.platform.ops.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SceneRuleService {

    private final SceneRuleRepository sceneRuleRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<SceneRule> listAll() {
        return sceneRuleRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @Transactional
    public SceneRule create(String name, String triggerSummary, String actionSummary, boolean enabled) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        SceneRule r = new SceneRule();
        r.setName(name.trim());
        r.setTriggerSummary(triggerSummary != null ? triggerSummary.trim() : "");
        r.setActionSummary(actionSummary != null ? actionSummary.trim() : "");
        r.setEnabled(enabled);
        r.setCreatedAt(LocalDateTime.now());
        SceneRule saved = sceneRuleRepository.save(r);
        auditLogService.log("SCENE_RULE_CREATE", "场景规则 id=" + saved.getId() + " name=" + saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        sceneRuleRepository.findById(id).ifPresent(r ->
                auditLogService.log("SCENE_RULE_DELETE", "删除场景规则 id=" + id + " name=" + r.getName()));
        sceneRuleRepository.deleteById(id);
    }
}
