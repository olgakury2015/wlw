package com.iot.platform.telemetry.service;

import com.iot.platform.telemetry.entity.TelemetryHistoryRecord;
import com.iot.platform.telemetry.repo.TelemetryHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TelemetryExportService {

    private static final int EXPORT_CAP = 10_000;

    private final TelemetryHistoryRepository telemetryHistoryRepository;

    public void writeExcel(String deviceSn, Instant from, Instant to, OutputStream out) throws IOException {
        List<TelemetryHistoryRecord> rows = telemetryHistoryRepository
                .findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtAsc(
                        deviceSn.trim(), from, to, PageRequest.of(0, EXPORT_CAP));
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("telemetry");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("receivedAt");
            header.createCell(1).setCellValue("deviceId");
            header.createCell(2).setCellValue("protocol");
            header.createCell(3).setCellValue("payloadJson");
            int r = 1;
            for (TelemetryHistoryRecord row : rows) {
                Row excelRow = sheet.createRow(r++);
                excelRow.createCell(0).setCellValue(row.getReceivedAt() != null ? row.getReceivedAt().toString() : "");
                excelRow.createCell(1).setCellValue(row.getDeviceId() != null ? row.getDeviceId() : "");
                excelRow.createCell(2).setCellValue(row.getProtocol() != null ? row.getProtocol() : "");
                excelRow.createCell(3).setCellValue(row.getPayloadJson() != null ? row.getPayloadJson() : "");
            }
            for (int i = 0; i < 4; i++) {
                sheet.autoSizeColumn(i);
            }
            wb.write(out);
        }
    }

    public void writeCsv(String deviceSn, Instant from, Instant to, OutputStream out) throws IOException {
        List<TelemetryHistoryRecord> rows = telemetryHistoryRepository
                .findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtDesc(
                        deviceSn.trim(), from, to, PageRequest.of(0, EXPORT_CAP));
        OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('\ufeff');
        w.write("receivedAt,deviceId,protocol,payloadJson\n");
        for (TelemetryHistoryRecord r : rows) {
            w.write(csv(r.getReceivedAt().toString()));
            w.write(',');
            w.write(csv(r.getDeviceId()));
            w.write(',');
            w.write(csv(r.getProtocol()));
            w.write(',');
            w.write('"');
            w.write(escape(r.getPayloadJson() != null ? r.getPayloadJson() : ""));
            w.write('"');
            w.write('\n');
        }
        w.flush();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\"", "\"\"");
        if (t.indexOf(',') >= 0 || t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) {
            return "\"" + t + "\"";
        }
        return t;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
