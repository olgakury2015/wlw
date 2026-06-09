package com.iot.platform.video.controller;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.service.VideoJpegSnapshot;
import com.iot.platform.video.service.VideoStreamRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 浏览器 MJPEG（multipart）。须关闭 Spring 默认 30s async 超时，否则约每 30 秒断流黑屏。
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class VideoMjpegController {

    private final VideoStreamRegistry videoStreamRegistry;
    private final IotProperties iotProperties;

    @GetMapping(value = "/video/mjpeg/{id}", produces = "multipart/x-mixed-replace; boundary=frame")
    public WebAsyncTask<ResponseEntity<StreamingResponseBody>> mjpeg(@PathVariable("id") Long channelId) {
        try {
            videoStreamRegistry.acquire(channelId);
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("禁用") || msg.contains("不存在")) {
                return new WebAsyncTask<>(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
            }
            throw ex;
        }
        int intervalMs = Math.max(0, iotProperties.getVideo().getMjpegMinIntervalMs());
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                videoStreamRegistry.release(channelId);
            }
        };

        StreamingResponseBody body = outputStream -> {
            log.debug("MJPEG 客户端已连接 channelId={}", channelId);
            byte[] dash = "--frame\r\n".getBytes(StandardCharsets.UTF_8);
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    VideoJpegSnapshot snap = videoStreamRegistry.getLatestJpegSnapshot(channelId);
                    byte[] jpg = snap.getJpeg();
                    if (jpg != null && jpg.length > 0) {
                        outputStream.write(dash);
                        String head = "Content-Type: image/jpeg\r\nContent-Length: " + jpg.length + "\r\n\r\n";
                        outputStream.write(head.getBytes(StandardCharsets.UTF_8));
                        outputStream.write(jpg);
                        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }
                    if (intervalMs > 0) {
                        Thread.sleep(intervalMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 客户端断开
            } finally {
                log.debug("MJPEG 客户端断开 channelId={}", channelId);
                releaseOnce.run();
            }
        };

        WebAsyncTask<ResponseEntity<StreamingResponseBody>> task = new WebAsyncTask<>(
                -1L,
                () -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(MediaType.parseMediaType("multipart/x-mixed-replace; boundary=frame"))
                        .body(body));

        task.onTimeout(() -> {
            log.warn("MJPEG async 超时断开 channelId={}（请确认 spring.mvc.async.request-timeout=-1）", channelId);
            releaseOnce.run();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        });

        return task;
    }
}
