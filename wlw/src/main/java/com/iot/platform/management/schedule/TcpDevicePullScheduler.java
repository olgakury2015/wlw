package com.iot.platform.management.schedule;

import com.iot.platform.management.service.DeviceTcpPullService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时对「TCP_PULL」设备发起连接并拉取一行数据（与 MicroPython 作 TCP Server 的场景一致）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TcpDevicePullScheduler {

    private final DeviceTcpPullService deviceTcpPullService;

    @Value("${iot.device-tcp.poll-enabled:true}")
    private boolean pollEnabled;

    @Scheduled(fixedDelayString = "${iot.device-tcp.poll-interval-ms:30000}")
    public void tick() {
        if (!pollEnabled) {
            return;
        }
        List<Long> ids = deviceTcpPullService.listTcpPullDeviceIds();
        for (Long id : ids) {
            try {
                deviceTcpPullService.pullAndIngest(id);
            } catch (Exception e) {
                log.warn("定时拉取 deviceId={} 异常: {}", id, e.getMessage());
            }
        }
    }
}
