package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.service.OpenCvRtspJpegEncoder;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JavaCV 直接读 SDP/RTP(PS)，避免 bytedeco 自带 ffmpeg CLI 参数不全的问题。
 */
public final class Gb28181JavaCvJpegSource implements Gb28181JpegSource {

    private static final Logger log = LoggerFactory.getLogger(Gb28181JavaCvJpegSource.class);

    private final FFmpegFrameGrabber grabber;
    private final Path sdpFile;
    private final int maxW;
    private final double jpegQuality;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicLong lastFrameAtMs = new AtomicLong(0);
    private final AtomicReference<String> stderrTail = new AtomicReference<>("");
    private volatile boolean running;
    private Thread readerThread;

    private Gb28181JavaCvJpegSource(FFmpegFrameGrabber grabber, Path sdpFile, int maxW, double jpegQuality) {
        this.grabber = grabber;
        this.sdpFile = sdpFile;
        this.maxW = maxW;
        this.jpegQuality = jpegQuality;
    }

    public static Gb28181JavaCvJpegSource start(
            int udpPort,
            IotProperties.Video cfg,
            String mediaHost,
            String remoteSsrcY,
            String rtpSenderIp,
            Gb28181MediaTransport transport)
            throws Exception {
        Path sdp = Gb28181SdpUtil.writeRecvSdp(
                udpPort, mediaHost, Gb28181SdpUtil.RecvPayload.PS, true, rtpSenderIp, transport);
        String sdpPath = Gb28181SdpUtil.pathForFfmpeg(sdp);
        int openMs = Math.max(10_000, cfg.getOpenTimeoutMs());
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(sdpPath);
        try {
            g.setFormat("sdp");
            g.setOption("protocol_whitelist", "file,udp,rtp,tcp");
            g.setOption("reuse", "1");
            g.setOption("analyzeduration", "15000000");
            g.setOption("probesize", "50000000");
            g.setOption("max_delay", "5000000");
            g.setOption("reorder_queue_size", "2048");
            g.setOption("err_detect", "ignore_err");
            g.setOption("fflags", "+discardcorrupt+nobuffer");
            g.setOption("stimeout", String.valueOf(openMs * 1000L));
            g.setOption("rw_timeout", String.valueOf(openMs * 1000L));
            g.start();
            int maxW = Math.max(320, cfg.getMaxFrameWidth());
            Gb28181JavaCvJpegSource src = new Gb28181JavaCvJpegSource(g, sdp, maxW, cfg.getJpegQuality());
            src.startReader();
            log.info("GB28181 JavaCV 已启动 sdp={} port={}", sdpPath, udpPort);
            return src;
        } catch (Exception e) {
            releaseGrabberQuietly(g);
            try {
                Files.deleteIfExists(sdp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    @Override
    public byte[] pollLatestJpeg() {
        return latestJpeg.get();
    }

    @Override
    public long lastFrameAtMs() {
        return lastFrameAtMs.get();
    }

    @Override
    public String stderrHint() {
        return stderrTail.get();
    }

    @Override
    public void close() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        try {
            grabber.stop();
        } catch (Exception ignored) {
        }
        try {
            grabber.release();
        } catch (Exception ignored) {
        }
        if (sdpFile != null) {
            try {
                Files.deleteIfExists(sdpFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void startReader() {
        running = true;
        readerThread = new Thread(() -> {
            int emptyStreak = 0;
            while (running) {
                try {
                    Frame frame = grabber.grabFrame(false, true, true, false);
                    if (frame == null || frame.image == null) {
                        emptyStreak++;
                        if (emptyStreak % 200 == 1 && emptyStreak > 1) {
                            stderrTail.set("JavaCV 未收到视频帧（请确认 RTP 已到达本机 UDP 端口、通道编码为 ...00003）");
                        }
                        Thread.sleep(20);
                        continue;
                    }
                    emptyStreak = 0;
                    Frame work = frame.clone();
                    byte[] jpg = OpenCvRtspJpegEncoder.encode(work, maxW, jpegQuality);
                    try {
                        work.close();
                    } catch (Exception ignored) {
                    }
                    if (jpg != null && jpg.length >= 100) {
                        latestJpeg.set(jpg);
                        lastFrameAtMs.set(System.currentTimeMillis());
                    }
                    Thread.sleep(Math.max(20, 1000 / 12));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    stderrTail.set(e.getMessage() != null ? e.getMessage() : e.toString());
                    log.debug("GB28181 JavaCV read: {}", stderrTail.get());
                    sleepQuiet(100);
                }
            }
        }, "gb28181-javacv-read");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static void releaseGrabberQuietly(FFmpegFrameGrabber g) {
        if (g == null) {
            return;
        }
        try {
            g.stop();
        } catch (Exception ignored) {
        }
        try {
            g.release();
        } catch (Exception ignored) {
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
