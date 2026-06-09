package com.iot.platform.service;

import com.iot.platform.model.TelemetryMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TelemetryHub {

    private static final int MAX_BUFFER = 500;

    private final List<TelemetryMessage> recent = Collections.synchronizedList(new ArrayList<TelemetryMessage>());
    private final Map<String, Object> lastByDevice = new ConcurrentHashMap<String, Object>();

    public void publish(TelemetryMessage message) {
        synchronized (recent) {
            recent.add(0, message);
            while (recent.size() > MAX_BUFFER) {
                recent.remove(recent.size() - 1);
            }
        }
        if (message.deviceId() != null) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("deviceId", message.deviceId());
            summary.put("protocol", message.protocol());
            summary.put("payload", message.payload());
            summary.put("receivedAt", message.receivedAt().toString());
            lastByDevice.put(message.deviceId(), summary);
        }
    }

    public List<TelemetryMessage> recentSnapshot() {
        synchronized (recent) {
            return Collections.unmodifiableList(new ArrayList<TelemetryMessage>(recent));
        }
    }

    public Map<String, Object> lastByDeviceSnapshot() {
        return Collections.unmodifiableMap(new HashMap<String, Object>(lastByDevice));
    }
}
