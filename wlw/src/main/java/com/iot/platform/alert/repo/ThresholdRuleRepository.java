package com.iot.platform.alert.repo;

import com.iot.platform.alert.entity.ThresholdRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ThresholdRuleRepository extends JpaRepository<ThresholdRule, Long> {

    List<ThresholdRule> findByEnabledTrue();

    List<ThresholdRule> findByKindIgnoreCaseOrderByIdDesc(String kind);
}
