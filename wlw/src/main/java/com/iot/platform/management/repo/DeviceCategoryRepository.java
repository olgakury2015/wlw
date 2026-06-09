package com.iot.platform.management.repo;

import com.iot.platform.management.entity.DeviceCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceCategoryRepository extends JpaRepository<DeviceCategory, Long> {

    List<DeviceCategory> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
