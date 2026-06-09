package com.iot.platform.service.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条遥测记录在页面上的展示形态。
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TelemetryDisplayRow {

    private String timeDisplay;
    private String protocol;
    private String deviceId;
    private List<PayloadField> fields = new ArrayList<PayloadField>();

    public void addField(PayloadField f) {
        fields.add(f);
    }
}
