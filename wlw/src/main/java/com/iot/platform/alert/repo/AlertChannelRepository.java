package com.iot.platform.alert.repo;

import com.iot.platform.alert.entity.AlertChannelRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertChannelRepository extends JpaRepository<AlertChannelRecord, Long> {
}
