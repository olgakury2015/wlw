package com.iot.platform.video.gb28181;

import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 国标点播 SSRC（y= 字段），格式对齐 wvp {@code SSRCFactory#getPlaySsrc}：10 位，首位 0 表示实况。
 */
@Component
public class Gb28181SsrcAllocator {

    private final Gb28181PlatformConfigService platformConfigService;
    private final AtomicInteger seq = new AtomicInteger(1);

    public Gb28181SsrcAllocator(Gb28181PlatformConfigService platformConfigService) {
        this.platformConfigService = platformConfigService;
    }

    public String allocatePlaySsrc() {
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        String domain = cfg.getSipDomain() != null ? cfg.getSipDomain().trim() : "3402000000";
        String domainPart = domain.length() >= 8 ? domain.substring(3, 8) : domain;
        int n = seq.getAndIncrement() % 10_000;
        if (n == 0) {
            n = ThreadLocalRandom.current().nextInt(1, 10_000);
        }
        return "0" + domainPart + String.format("%04d", n);
    }
}
