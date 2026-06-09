package com.iot.platform.video.repo;

import com.iot.platform.video.entity.CameraChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CameraChannelRepository extends JpaRepository<CameraChannel, Long> {

    List<CameraChannel> findByEnabledTrueOrderBySortOrderAscIdAsc();

    List<CameraChannel> findByGb28181DeviceId(String gb28181DeviceId);
}
