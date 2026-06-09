package com.iot.platform.video.gb28181;

import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import org.springframework.stereotype.Component;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Gb28181UdpPortPool {

    private final Gb28181PlatformConfigService platformConfigService;
    private final ConcurrentHashMap<Integer, String> inUse = new ConcurrentHashMap<>();
    private volatile BitSet ports;
    private volatile int cachedMin = -1;
    private volatile int cachedMax = -1;

    public Gb28181UdpPortPool(Gb28181PlatformConfigService platformConfigService) {
        this.platformConfigService = platformConfigService;
    }

    public synchronized int allocate(String ownerKey) {
        ensureInit();
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        int min = cfg.getMediaPortMin();
        int max = cfg.getMediaPortMax();
        for (int p = min; p <= max; p++) {
            int idx = p - min;
            if (!ports.get(idx)) {
                ports.set(idx);
                inUse.put(p, ownerKey);
                return p;
            }
        }
        throw new IllegalStateException("无可用 RTP 端口（" + min + "-" + max + "）");
    }

    public synchronized void release(int port) {
        if (port <= 0) {
            return;
        }
        inUse.remove(port);
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        int min = cfg.getMediaPortMin();
        int idx = port - min;
        if (ports != null && idx >= 0 && idx < ports.size()) {
            ports.clear(idx);
        }
    }

    private void ensureInit() {
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        int min = cfg.getMediaPortMin();
        int max = cfg.getMediaPortMax();
        if (ports != null && cachedMin == min && cachedMax == max) {
            return;
        }
        cachedMin = min;
        cachedMax = max;
        int size = max - min + 1;
        ports = new BitSet(Math.max(1, size));
    }
}
