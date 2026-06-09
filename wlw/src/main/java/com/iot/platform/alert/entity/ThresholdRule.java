package com.iot.platform.alert.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 阈值规则：ALERT 告警、LINKAGE 场景联动；触发时 POST webhook（如钉钉机器人）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_threshold_rule")
public class ThresholdRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** ALERT 或 LINKAGE */
    @Column(nullable = false, length = 16)
    private String kind = "ALERT";

    @Column(name = "device_sn", length = 64)
    private String deviceSn;

    @Column(name = "metric_key", nullable = false, length = 64)
    private String metricKey;

    /** GT LT GE LE EQ */
    @Column(nullable = false, length = 4)
    private String operator = "GT";

    @Column(nullable = false)
    private double threshold;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "webhook_url", length = 1024)
    private String webhookUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
