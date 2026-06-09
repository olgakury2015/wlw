package com.iot.platform.service;

import com.iot.platform.model.TelemetryMessage;

/**
 * 任意协议遥测入库后发布，供 MQTT 等异步扇出，避免在 MQTT 回调线程内直接 publish。
 */
public final class TelemetryIngestedEvent {

    private final TelemetryMessage message;

    public TelemetryIngestedEvent(TelemetryMessage message) {
        this.message = message;
    }

    public TelemetryMessage getMessage() {
        return message;
    }
}
