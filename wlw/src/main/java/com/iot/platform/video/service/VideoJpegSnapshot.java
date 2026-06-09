package com.iot.platform.video.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 供 MJPEG 输出：画面字节 + 是否陈旧（陈旧时应显示黑场并触发后台重连，避免一直停在同一帧）。
 */
@Getter
@RequiredArgsConstructor
public final class VideoJpegSnapshot {

    private final byte[] jpeg;
    /** 为 true 表示尚无画面（仅启动/重连瞬间），有 lastKnown 时浏览器仍显示最后一帧 */
    private final boolean stale;

    static VideoJpegSnapshot fresh(byte[] jpeg) {
        return new VideoJpegSnapshot(jpeg, false);
    }

    static VideoJpegSnapshot waiting(byte[] lastKnown) {
        return new VideoJpegSnapshot(lastKnown, lastKnown == null);
    }
}
