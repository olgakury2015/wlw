package com.iot.platform.management.repo;

import com.iot.platform.management.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceSn(String deviceSn);

    boolean existsByDeviceSn(String deviceSn);

    List<Device> findByLatitudeIsNotNullAndLongitudeIsNotNull();

    List<Device> findByGateway_Id(Long gatewayId);

    long countByStatus(String status);

    @Modifying
    @Query("update Device d set d.category = null where d.category.id = :categoryId")
    void clearCategoryByCategoryId(@Param("categoryId") Long categoryId);

    @Modifying
    @Query("update Device d set d.gateway = null where d.gateway.id = :gatewayId")
    void clearGatewayByGatewayId(@Param("gatewayId") Long gatewayId);
}
