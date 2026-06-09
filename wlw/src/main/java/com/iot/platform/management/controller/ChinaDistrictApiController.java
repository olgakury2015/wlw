package com.iot.platform.management.controller;

import com.iot.platform.dto.ChinaDistrictChildDto;
import com.iot.platform.service.geocode.AmapDistrictService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 中国省市区子级列表（高德 Web 服务），供控制台设备表单调级使用。
 */
@RestController
@RequestMapping("/api/v1/management/china-districts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ChinaDistrictApiController {

    private final AmapDistrictService amapDistrictService;

    /**
     * @param parent 父级 adcode；省略则返回省级行政区
     */
    @GetMapping
    public List<ChinaDistrictChildDto> children(@RequestParam(required = false) String parent) {
        return amapDistrictService.listChildren(parent);
    }
}
