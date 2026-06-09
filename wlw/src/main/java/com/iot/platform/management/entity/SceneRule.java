package com.iot.platform.management.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 场景规则：触发条件 + 执行动作（可与 Node-RED / 告警推送对接）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_scene_rule")
public class SceneRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "trigger_summary", length = 512)
    private String triggerSummary;

    @Column(name = "action_summary", length = 512)
    private String actionSummary;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
