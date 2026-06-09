package com.iot.platform.management.service;

import com.iot.platform.management.entity.DeviceCategory;
import com.iot.platform.management.repo.DeviceCategoryRepository;
import com.iot.platform.management.repo.DeviceRepository;
import com.iot.platform.ops.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceCategoryService {

    private final DeviceCategoryRepository categoryRepository;
    private final DeviceRepository deviceRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<DeviceCategory> listAll() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<DeviceCategory> findById(Long id) {
        return categoryRepository.findById(id);
    }

    @Transactional
    public void create(String name, String code, Integer sortOrder) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        String n = name.trim();
        if (categoryRepository.existsByNameIgnoreCase(n)) {
            throw new IllegalArgumentException("分类名称已存在：" + n);
        }
        DeviceCategory c = new DeviceCategory();
        c.setName(n);
        c.setCode(code != null ? code.trim() : "");
        c.setSortOrder(sortOrder != null ? sortOrder : 0);
        categoryRepository.save(c);
        auditLogService.log("DEVICE_CATEGORY_CREATE", "新建设备分类 " + n);
    }

    @Transactional
    public void update(Long id, String name, String code, Integer sortOrder) {
        DeviceCategory c = categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        String n = name.trim();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(n, id)) {
            throw new IllegalArgumentException("分类名称与其他分类冲突");
        }
        c.setName(n);
        c.setCode(code != null ? code.trim() : "");
        c.setSortOrder(sortOrder != null ? sortOrder : 0);
        categoryRepository.save(c);
        auditLogService.log("DEVICE_CATEGORY_UPDATE", "更新设备分类 id=" + id + " name=" + n);
    }

    @Transactional
    public void delete(Long id) {
        deviceRepository.clearCategoryByCategoryId(id);
        categoryRepository.findById(id).ifPresent(c ->
                auditLogService.log("DEVICE_CATEGORY_DELETE", "删除设备分类 id=" + id + " name=" + c.getName()));
        categoryRepository.deleteById(id);
    }
}
