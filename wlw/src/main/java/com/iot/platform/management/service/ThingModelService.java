package com.iot.platform.management.service;

import com.iot.platform.management.entity.ThingModel;
import com.iot.platform.management.repo.ThingModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ThingModelService {

    private final ThingModelRepository thingModelRepository;

    @Transactional(readOnly = true)
    public List<ThingModel> listAll() {
        return thingModelRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @Transactional
    public ThingModel create(String code, String name, String description, String propertiesJson) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("物模型标识 code 不能为空");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("物模型名称不能为空");
        }
        String c = code.trim();
        if (thingModelRepository.existsByCode(c)) {
            throw new IllegalArgumentException("物模型标识已存在：" + c);
        }
        ThingModel m = new ThingModel();
        m.setCode(c);
        m.setName(name.trim());
        m.setDescription(description != null ? description.trim() : "");
        if (propertiesJson != null && !propertiesJson.trim().isEmpty()) {
            m.setPropertiesJson(propertiesJson.trim());
        } else {
            m.setPropertiesJson(defaultPropertiesTemplate());
        }
        m.setCreatedAt(LocalDateTime.now());
        return thingModelRepository.save(m);
    }

    @Transactional
    public void delete(Long id) {
        thingModelRepository.deleteById(id);
    }

    public String defaultPropertiesTemplate() {
        return "{\n"
                + "  \"attributes\": [\n"
                + "    {\"identifier\": \"temperature\", \"name\": \"温度\", \"type\": \"float\", \"unit\": \"℃\"},\n"
                + "    {\"identifier\": \"switch\", \"name\": \"开关\", \"type\": \"bool\"}\n"
                + "  ],\n"
                + "  \"services\": [{\"identifier\": \"reboot\", \"name\": \"远程重启\"}],\n"
                + "  \"events\": [{\"identifier\": \"alarm\", \"name\": \"告警\"}]\n"
                + "}";
    }
}
