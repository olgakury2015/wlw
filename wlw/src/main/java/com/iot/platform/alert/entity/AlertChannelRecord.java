package com.iot.platform.alert.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 单例行 id=1：规则未配置 webhook 时的全局兜底（如钉钉机器人）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_alert_channel")
public class AlertChannelRecord {

    @Id
    private Long id = 1L;

    @Column(name = "fallback_webhook", length = 1024)
    private String fallbackWebhook;
}
