package com.iot.platform.video.service;

import com.iot.platform.config.IotProperties;
import com.sun.jna.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.player.MediaPlayer;
import uk.co.caprica.vlcj.player.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.direct.BufferFormat;
import uk.co.caprica.vlcj.player.direct.BufferFormatCallback;
import uk.co.caprica.vlcj.player.direct.DirectMediaPlayer;
import uk.co.caprica.vlcj.player.direct.RenderCallback;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VLCJ DirectMediaPlayer 拉 RTSP，渲染回调转 JPEG 供 MJPEG 推送。
 */
final class VlcjRtspJpegSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VlcjRtspJpegSource.class);

    private final DirectMediaPlayer player;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicLong lastFrameAtMs = new AtomicLong(0);
    private final AtomicLong lastEncodeAttemptMs = new AtomicLong(0);
    private final int minEncodeIntervalMs;
    private final float jpegQuality;

    private volatile boolean ended;
    private volatile String lastError;

    private VlcjRtspJpegSource(
            DirectMediaPlayer player,
            int minEncodeIntervalMs,
            float jpegQuality) {
        this.player = player;
        this.minEncodeIntervalMs = minEncodeIntervalMs;
        this.jpegQuality = jpegQuality;
    }

    static VlcjRtspJpegSource start(
            String rtspUrl,
            IotProperties.Video cfg,
            MediaPlayerFactory factory) {
        String url = RtspFrameGrabberFactory.normalizeRtspUrl(rtspUrl);
        int maxW = Math.max(320, cfg.getMaxFrameWidth());
        int caching = Math.max(0, cfg.getVlcNetworkCachingMs());
        String transport = cfg.getRtspTransport() != null ? cfg.getRtspTransport().trim() : "tcp";
        boolean tcp = !"udp".equalsIgnoreCase(transport);
        int minInterval = Math.max(50, cfg.getGrabIntervalMs());
        float q = (float) Math.max(0.1, Math.min(1.0, cfg.getJpegQuality()));

        final VlcjRtspJpegSource[] ref = new VlcjRtspJpegSource[1];

        BufferFormatCallback formatCallback = (sourceWidth, sourceHeight) -> {
            int w = Math.max(1, sourceWidth);
            int h = Math.max(1, sourceHeight);
            if (w > maxW) {
                h = Math.max(1, (int) ((long) h * maxW / w));
                w = maxW;
            }
            // Windows libVLC 直渲多为 RV32（内存序 BGRA）；lines 各分量须 > 0
            return new BufferFormat(
                    "RV32",
                    w,
                    h,
                    new int[]{w * 4},
                    new int[]{h});
        };

        RenderCallback renderCallback = (mediaPlayer, nativeBuffers, bufferFormat) -> {
            VlcjRtspJpegSource src = ref[0];
            if (src != null) {
                src.onFrame(nativeBuffers, bufferFormat);
            }
        };

        DirectMediaPlayer player = factory.newDirectMediaPlayer(formatCallback, renderCallback);

        VlcjRtspJpegSource src = new VlcjRtspJpegSource(player, minInterval, q);
        ref[0] = src;

        player.addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void error(MediaPlayer mediaPlayer) {
                src.lastError = "VLC 播放错误: " + describePlayerState(mediaPlayer);
                src.ended = true;
                log.warn("VLCJ error: {} url={}", src.lastError, maskRtspUrl(url));
            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                src.ended = true;
            }

            @Override
            public void playing(MediaPlayer mediaPlayer) {
                log.info("VLCJ 开始播放: {}", maskRtspUrl(url));
            }
        });

        String[] options = buildPlayOptions(caching, tcp);
        log.info("VLCJ 拉流 {} network-caching={} rtsp-tcp={}",
                maskRtspUrl(url), caching, tcp);
        player.prepareMedia(url, options);
        player.play();
        return src;
    }

    private static String[] buildPlayOptions(int cachingMs, boolean rtspTcp) {
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add(":network-caching=" + cachingMs);
        opts.add(":live-caching=" + cachingMs);
        opts.add(":clock-jitter=0");
        opts.add(":clock-synchro=0");
        if (rtspTcp) {
            opts.add(":rtsp-tcp");
        }
        return opts.toArray(new String[0]);
    }

    private void onFrame(Memory[] nativeBuffers, BufferFormat bufferFormat) {
        if (ended || nativeBuffers == null || nativeBuffers.length == 0 || nativeBuffers[0] == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEncodeAttemptMs.get() < minEncodeIntervalMs) {
            return;
        }
        lastEncodeAttemptMs.set(now);
        try {
            Memory memory = nativeBuffers[0];
            int w = bufferFormat.getWidth();
            int h = bufferFormat.getHeight();
            int byteLen = w * h * 4;
            byte[] rgba = memory.getByteArray(0, byteLen);
            byte[] jpg = rgbaToJpeg(rgba, w, h, jpegQuality);
            if (jpg != null && jpg.length >= 100) {
                latestJpeg.set(jpg);
                lastFrameAtMs.set(now);
            }
        } catch (Exception e) {
            log.trace("VLCJ 帧编码失败: {}", e.toString());
        }
    }

    private static byte[] rgbaToJpeg(byte[] rgba, int width, int height, float quality) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] rgb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int i = 0;
        int n = width * height;
        int p = 0;
        while (i < n && p + 3 < rgba.length) {
            int b = rgba[p++] & 0xff;
            int g = rgba[p++] & 0xff;
            int r = rgba[p++] & 0xff;
            p++;
            rgb[i++] = (r << 16) | (g << 8) | b;
        }
        return encodeJpeg(image, quality);
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            return baos.toByteArray();
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            writer.dispose();
            return baos.toByteArray();
        }
    }

    byte[] pollLatestJpeg() {
        return latestJpeg.get();
    }

    long getLastFrameAtMs() {
        return lastFrameAtMs.get();
    }

    boolean isPlaying() {
        return !ended && player.isPlaying();
    }

    boolean isEnded() {
        return ended;
    }

    String statusMessage() {
        if (lastError != null) {
            return lastError;
        }
        return ended ? "播放结束" : "运行中";
    }

    @Override
    public void close() {
        ended = true;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
    }

    private static String describePlayerState(MediaPlayer mediaPlayer) {
        if (mediaPlayer == null) {
            return "player=null";
        }
        try {
            return "playing=" + mediaPlayer.isPlaying();
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "unknown";
        }
    }

    private static String maskRtspUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("://([^:@/]+):([^@/]+)@", "://***:***@");
    }
}
