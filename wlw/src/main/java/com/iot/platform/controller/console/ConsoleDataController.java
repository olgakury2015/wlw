package com.iot.platform.controller.console;

import com.iot.platform.service.TelemetryHub;
import com.iot.platform.service.telemetry.TelemetryPresentationService;
import com.iot.platform.telemetry.entity.TelemetryHistoryRecord;
import com.iot.platform.telemetry.repo.TelemetryHistoryRepository;
import com.iot.platform.telemetry.service.TelemetryExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class ConsoleDataController {

    private final TelemetryHub telemetryHub;
    private final TelemetryPresentationService telemetryPresentationService;
    private final TelemetryHistoryRepository telemetryHistoryRepository;
    private final TelemetryExportService telemetryExportService;

    @GetMapping("/data/receive")
    public String receive(Model model) {
        model.addAttribute("active", "data-receive");
        model.addAttribute("breadcrumb", "首页 / 数据中心 / 数据接收与解析");
        model.addAttribute("recentDisplay", telemetryPresentationService.formatRecent(telemetryHub.recentSnapshot()));
        return "data-receive";
    }

    private static final DateTimeFormatter DT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @GetMapping("/data/history")
    public String history(@RequestParam(required = false) String deviceSn,
                          @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          Model model) {
        model.addAttribute("active", "data-history");
        model.addAttribute("breadcrumb", "首页 / 数据中心 / 时序查询与导出");
        model.addAttribute("qDevice", deviceSn != null ? deviceSn : "");
        Instant now = Instant.now();
        Instant f = parseInstant(from, now.minusSeconds(86400));
        Instant t = parseInstant(to, now);
        model.addAttribute("qFromStr", formatLocal(f));
        model.addAttribute("qToStr", formatLocal(t));
        List<TelemetryHistoryRecord> rows = java.util.Collections.emptyList();
        if (deviceSn != null && !deviceSn.trim().isEmpty()) {
            rows = telemetryHistoryRepository.findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtDesc(
                    deviceSn.trim(), f, t, PageRequest.of(0, 500));
        }
        model.addAttribute("historyRows", rows);
        return "data-history";
    }

    @GetMapping("/data/export.csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam String deviceSn,
                                              @RequestParam String from,
                                              @RequestParam String to)
            throws Exception {
        Instant f = parseInstant(from, Instant.now().minusSeconds(86400));
        Instant t = parseInstant(to, Instant.now());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        telemetryExportService.writeCsv(deviceSn, f, t, buf);
        byte[] bytes = buf.toByteArray();
        String filename = "telemetry-" + deviceSn + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    @GetMapping("/data/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(@RequestParam String deviceSn,
                                               @RequestParam String from,
                                               @RequestParam String to) throws Exception {
        Instant f = parseInstant(from, Instant.now().minusSeconds(86400));
        Instant t = parseInstant(to, Instant.now());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        telemetryExportService.writeExcel(deviceSn, f, t, buf);
        byte[] bytes = buf.toByteArray();
        String filename = "telemetry-" + deviceSn + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    private static Instant parseInstant(String s, Instant fallback) {
        if (s == null || s.trim().isEmpty()) {
            return fallback;
        }
        try {
            if (s.length() == 16) {
                return java.time.LocalDateTime.parse(s, DT_LOCAL).atZone(ZoneId.systemDefault()).toInstant();
            }
            return Instant.parse(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatLocal(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault()).format(DT_LOCAL);
    }
}
