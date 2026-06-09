package com.iot.platform.management.repo;

import com.iot.platform.management.entity.ThingModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ThingModelRepository extends JpaRepository<ThingModel, Long> {

    Optional<ThingModel> findByCode(String code);

    boolean existsByCode(String code);
}
