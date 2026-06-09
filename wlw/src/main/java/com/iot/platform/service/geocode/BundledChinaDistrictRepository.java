package com.iot.platform.service.geocode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.dto.ChinaDistrictChildDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置「省 / 市 / 区」三级数据（国家统计局区划编码形态，与高德 adcode 常见取值一致），
 * 数据来自 <a href="https://github.com/modood/Administrative-divisions-of-China">modood/Administrative-divisions-of-China</a> 的 pca-code.json。
 * 未配置高德 Web 服务 Key 时作为行政区域下拉的数据源；中心经纬度可能为空，需用户地图选点或手输。
 */
@Component
@Slf4j
public class BundledChinaDistrictRepository {

    private static final String RESOURCE = "data/china-pca-code.json";

    private final ObjectMapper objectMapper;

    private volatile Map<String, List<ChinaDistrictChildDto>> childrenByParentAdcode = Collections.emptyMap();

    public BundledChinaDistrictRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param parentAdcode 父级 6 位区划码；空表示省级列表
     */
    public List<ChinaDistrictChildDto> listChildren(String parentAdcode) {
        ensureLoaded();
        String p = parentAdcode == null || parentAdcode.trim().isEmpty() ? "" : parentAdcode.trim();
        List<ChinaDistrictChildDto> list = childrenByParentAdcode.get(p);
        return list == null ? Collections.emptyList() : list;
    }

    /** 内置数据是否已成功加载（用于控制台是否启用省市区下拉）。 */
    public boolean hasData() {
        ensureLoaded();
        List<ChinaDistrictChildDto> top = childrenByParentAdcode.get("");
        return top != null && !top.isEmpty();
    }

    private void ensureLoaded() {
        if (!childrenByParentAdcode.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (!childrenByParentAdcode.isEmpty()) {
                return;
            }
            childrenByParentAdcode = load();
        }
    }

    private Map<String, List<ChinaDistrictChildDto>> load() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        if (!res.exists()) {
            log.warn("未找到内置行政区划文件 {}，省市区下拉将不可用（除非配置高德 web-service-key）", RESOURCE);
            return Collections.emptyMap();
        }
        try (InputStream in = res.getInputStream()) {
            PcaNode[] roots = objectMapper.readValue(in, PcaNode[].class);
            Map<String, List<ChinaDistrictChildDto>> map = new HashMap<>();
            List<ChinaDistrictChildDto> provinces = new ArrayList<>();
            for (PcaNode n : roots) {
                if (n == null || n.code == null || n.name == null) {
                    continue;
                }
                String provAd = toFullAdcode(n.code);
                provinces.add(new ChinaDistrictChildDto(provAd, n.name, null, null, "province"));
                indexChildren(map, provAd, n.children, 1);
            }
            provinces.sort((a, b) -> a.getName().compareTo(b.getName()));
            map.put("", provinces);
            return Collections.unmodifiableMap(map);
        } catch (Exception e) {
            log.warn("加载内置行政区划失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void indexChildren(Map<String, List<ChinaDistrictChildDto>> map, String parentAd,
                               List<PcaNode> children, int depth) {
        if (children == null || children.isEmpty()) {
            return;
        }
        List<ChinaDistrictChildDto> row = new ArrayList<>();
        for (PcaNode c : children) {
            if (c == null || c.code == null || c.name == null) {
                continue;
            }
            String ad = toFullAdcode(c.code);
            String level = depth <= 1 ? "city" : "district";
            row.add(new ChinaDistrictChildDto(ad, c.name, null, null, level));
        }
        row.sort((a, b) -> a.getName().compareTo(b.getName()));
        map.put(parentAd, row);
        for (PcaNode c : children) {
            if (c == null || c.code == null) {
                continue;
            }
            String ad = toFullAdcode(c.code);
            indexChildren(map, ad, c.children, depth + 1);
        }
    }

    /**
     * 将 modood 中的 code 规范为 6 位统计区划码（与高德 adcode 常见形态一致）。
     */
    static String toFullAdcode(String shortCode) {
        String s = shortCode.trim();
        if (s.length() >= 6) {
            return s;
        }
        if (s.length() == 4) {
            return s + "00";
        }
        if (s.length() == 2) {
            return s + "0000";
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 6) {
            sb.append('0');
        }
        return sb.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PcaNode {
        public String code;
        public String name;
        public List<PcaNode> children;
    }
}
