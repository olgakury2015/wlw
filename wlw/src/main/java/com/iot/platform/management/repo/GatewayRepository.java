package com.iot.platform.management.repo;

import com.iot.platform.management.entity.IotGateway;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GatewayRepository extends JpaRepository<IotGateway, Long> {

    Optional<IotGateway> findByGatewaySn(String gatewaySn);

    boolean existsByGatewaySn(String gatewaySn);

    List<IotGateway> findByRegisterPacket(String registerPacket);
}
