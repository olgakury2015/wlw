package com.iot.platform.alert.service;

import com.iot.platform.alert.entity.AlertChannelRecord;
import com.iot.platform.alert.repo.AlertChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertChannelService {

    private final AlertChannelRepository alertChannelRepository;

    @Transactional(readOnly = true)
    public AlertChannelRecord getOrEmpty() {
        return alertChannelRepository.findById(1L).orElseGet(() -> {
            AlertChannelRecord r = new AlertChannelRecord();
            r.setId(1L);
            return r;
        });
    }

    @Transactional
    public void saveFallbackWebhook(String url) {
        AlertChannelRecord r = alertChannelRepository.findById(1L).orElseGet(() -> {
            AlertChannelRecord x = new AlertChannelRecord();
            x.setId(1L);
            return x;
        });
        r.setFallbackWebhook(trimToNull(url));
        alertChannelRepository.save(r);
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
