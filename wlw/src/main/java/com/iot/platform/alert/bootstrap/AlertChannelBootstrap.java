package com.iot.platform.alert.bootstrap;

import com.iot.platform.alert.entity.AlertChannelRecord;
import com.iot.platform.alert.repo.AlertChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
@RequiredArgsConstructor
public class AlertChannelBootstrap implements CommandLineRunner {

    private final AlertChannelRepository alertChannelRepository;

    @Override
    public void run(String... args) {
        if (alertChannelRepository.count() == 0) {
            AlertChannelRecord r = new AlertChannelRecord();
            r.setId(1L);
            r.setFallbackWebhook(null);
            alertChannelRepository.save(r);
        }
    }
}
