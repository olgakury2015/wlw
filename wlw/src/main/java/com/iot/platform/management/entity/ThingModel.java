package com.iot.platform.management.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * 物模型：描述设备属性、服务、事件等（propertiesJson 存 JSON 定义）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "iot_thing_model")
public class ThingModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Lob
    @Column(name = "properties_json")
    private String propertiesJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
