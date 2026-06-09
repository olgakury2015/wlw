package com.iot.platform.video.gb28181;

/**
 * 国标 RTP(PS) → MJPEG 帧源（FFmpeg 子进程或 JavaCV 等实现）。
 */
public interface Gb28181JpegSource extends AutoCloseable {

    byte[] pollLatestJpeg();

    long lastFrameAtMs();

    String stderrHint();
}
