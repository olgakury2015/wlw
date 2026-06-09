package com.iot.platform.video.service;

import com.iot.platform.config.IotProperties;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FFmpeg 子进程拉 RTSP → MJPEG；后台线程持续读 stdout，避免管道塞满导致断流。
 */
final class FfmpegRtspJpegSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRtspJpegSource.class);

    /** bytedeco 自带 ffmpeg CLI 通常不支持 -stimeout，避免每次双启动探测。 */
    private static volatile boolean stimeoutCliSupported = false;

    private final Process process;
    private final BufferedInputStream input;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicLong lastFrameAtMs = new AtomicLong(0);
    private final AtomicReference<String> stderrTail = new AtomicReference<>("");

    private volatile boolean readerRunning;
    private Thread readerThread;
    private Thread stderrThread;

    private FfmpegRtspJpegSource(Process process, BufferedInputStream input) {
        this.process = process;
        this.input = input;
    }

    static FfmpegRtspJpegSource start(String inputUrl, IotProperties.Video cfg) throws IOException {
        return start(inputUrl, cfg, null);
    }

    static FfmpegRtspJpegSource start(String inputUrl, IotProperties.Video cfg, String transportOverride)
            throws IOException {
        String url = inputUrl != null ? inputUrl.trim() : "";
        boolean rtspInput = url.regionMatches(true, 0, "rtsp://", 0, 7)
                || url.regionMatches(true, 0, "rtsps://", 0, 8);
        if (rtspInput) {
            url = RtspFrameGrabberFactory.normalizeRtspUrl(url);
        }
        String transport = transportOverride != null && !transportOverride.trim().isEmpty()
                ? transportOverride.trim()
                : (cfg.getRtspTransport() != null ? cfg.getRtspTransport().trim() : "tcp");
        if (transport.isEmpty()) {
            transport = "tcp";
        }
        int openMs = Math.max(3000, cfg.getOpenTimeoutMs());
        long timeoutUs = openMs * 1000L;
        long stimeoutUs = Math.min(timeoutUs, 10_000_000L);
        int maxW = Math.max(320, cfg.getMaxFrameWidth());
        int q = mjpegQ(cfg.getJpegQuality());
        String exe = resolveFfmpegExecutable(cfg);

        if (rtspInput && stimeoutCliSupported) {
            FfmpegRtspJpegSource src = launch(exe, transport, url, stimeoutUs, timeoutUs, maxW, q, true, true);
            if (src.probeStartupFailedWithStimeout()) {
                log.warn("当前 ffmpeg 不支持 -stimeout，后续将仅使用 -timeout");
                stimeoutCliSupported = false;
                src.close();
            } else {
                return src;
            }
        }
        return launch(exe, transport, url, stimeoutUs, timeoutUs, maxW, q, false, rtspInput);
    }

    static boolean isConnectionReset(String stderr) {
        return stderr != null && (stderr.contains("10054") || stderr.contains("Connection reset"));
    }

    private static FfmpegRtspJpegSource launch(
            String exe,
            String transport,
            String url,
            long stimeoutUs,
            long timeoutUs,
            int maxW,
            int q,
            boolean useStimeout,
            boolean rtspInput) throws IOException {
        List<String> cmd = buildCommand(exe, transport, url, stimeoutUs, timeoutUs, maxW, q, useStimeout, rtspInput);
        if (rtspInput) {
            if (useStimeout) {
                log.info("启动 ffmpeg(RTSP): {} -rtsp_transport {} -timeout {} -i {}",
                        exe, transport, timeoutUs, maskRtspUrl(url));
            } else {
                log.info("启动 ffmpeg(RTSP): {} -rtsp_transport {} -timeout {} -i {}",
                        exe, transport, timeoutUs, maskRtspUrl(url));
            }
        } else {
            log.info("启动 ffmpeg(HTTP): {} -timeout {} -i {}", exe, timeoutUs, maskRtspUrl(url));
        }

        Process process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        FfmpegRtspJpegSource src = new FfmpegRtspJpegSource(
                process, new BufferedInputStream(process.getInputStream(), 256 * 1024));
        src.stderrThread = new Thread(
                () -> drainStderr(process.getErrorStream(), src.stderrTail), "ffmpeg-err");
        src.stderrThread.setDaemon(true);
        src.stderrThread.start();
        src.readerRunning = true;
        src.readerThread = new Thread(src::readLoop, "ffmpeg-mjpeg-read");
        src.readerThread.setDaemon(true);
        src.readerThread.start();
        return src;
    }

    private static List<String> buildCommand(
            String exe,
            String transport,
            String url,
            long stimeoutUs,
            long timeoutUs,
            int maxW,
            int q,
            boolean useStimeout,
            boolean rtspInput) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        if (rtspInput) {
            cmd.add("-rtsp_transport");
            cmd.add(transport);
            if ("tcp".equalsIgnoreCase(transport)) {
                cmd.add("-rtsp_flags");
                cmd.add("prefer_tcp");
            }
        }
        if (useStimeout) {
            cmd.add("-stimeout");
            cmd.add(String.valueOf(stimeoutUs));
        }
        cmd.add("-timeout");
        cmd.add(String.valueOf(timeoutUs));
        cmd.add("-i");
        cmd.add(url);
        cmd.add("-an");
        cmd.add("-vf");
        cmd.add("scale=" + maxW + ":-2,format=yuv420p");
        cmd.add("-r");
        cmd.add("8");
        cmd.add("-c:v");
        cmd.add("mjpeg");
        cmd.add("-q:v");
        cmd.add(String.valueOf(q));
        cmd.add("-f");
        cmd.add("mjpeg");
        cmd.add("-");
        return cmd;
    }

    /** 启动后极短时间内因 -stimeout 不支持而退出时返回 true。 */
    private boolean probeStartupFailedWithStimeout() {
        sleepQuiet(400);
        if (process.isAlive()) {
            return false;
        }
        try {
            stderrThread.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String err = stderrTail.get();
        return err != null && err.toLowerCase().contains("stimeout");
    }

    /** 取后台线程已解析的最新一帧（非阻塞）。 */
    byte[] pollLatestJpeg() {
        return latestJpeg.get();
    }

    long getLastFrameAtMs() {
        return lastFrameAtMs.get();
    }

    /** 阻塞等待首帧到达（用于无缝切换）。 */
    boolean awaitFirstFrame(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
        long seen = lastFrameAtMs.get();
        while (System.currentTimeMillis() < deadline) {
            if (lastFrameAtMs.get() > seen && latestJpeg.get() != null) {
                return true;
            }
            if (!process.isAlive() && safeAvailable() <= 0) {
                return false;
            }
            sleepQuiet(30);
        }
        return lastFrameAtMs.get() > 0 && latestJpeg.get() != null;
    }

    private int safeAvailable() {
        try {
            return input.available();
        } catch (IOException e) {
            return 0;
        }
    }

    boolean isAlive() {
        return process.isAlive();
    }

    int exitValue() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    String stderrMessage() {
        try {
            if (stderrThread != null) {
                stderrThread.join(400);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return shortenStderr(stderrTail.get());
    }

    private static String shortenStderr(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "(无)";
        }
        String s = raw;
        int demux = s.indexOf("Error during demuxing");
        if (demux >= 0) {
            s = s.substring(demux);
        } else {
            int pkt = s.indexOf("Error retrieving a packet");
            if (pkt >= 0) {
                s = s.substring(pkt);
            }
        }
        if (s.length() > 600) {
            s = s.substring(0, 600) + "…";
        }
        return s;
    }

    private void readLoop() {
        try {
            while (readerRunning) {
                if (!process.isAlive() && safeAvailable() <= 0) {
                    break;
                }
                byte[] jpg = readOneJpegBlocking();
                if (jpg != null && jpg.length >= 100) {
                    latestJpeg.set(jpg);
                    lastFrameAtMs.set(System.currentTimeMillis());
                } else if (!process.isAlive() && safeAvailable() <= 0) {
                    break;
                } else {
                    sleepQuiet(5);
                }
            }
        } catch (Exception e) {
            log.debug("ffmpeg 读流结束: {}", e.toString());
        }
    }

    private byte[] readOneJpegBlocking() throws IOException {
        int prev = -1;
        boolean inFrame = false;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(128 * 1024);
        while (readerRunning) {
            if (!process.isAlive() && safeAvailable() <= 0) {
                return null;
            }
            int b = input.read();
            if (b < 0) {
                return inFrame ? null : null;
            }
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    inFrame = true;
                    out.write(0xFF);
                    out.write(0xD8);
                }
                prev = b;
                continue;
            }
            out.write(b);
            if (prev == 0xFF && b == 0xD9) {
                return out.toByteArray();
            }
            prev = b;
        }
        return null;
    }

    private static String resolveFfmpegExecutable(IotProperties.Video cfg) {
        if (cfg.getFfmpegPath() != null && !cfg.getFfmpegPath().trim().isEmpty()) {
            File custom = new File(cfg.getFfmpegPath().trim());
            if (custom.isFile()) {
                return custom.getAbsolutePath();
            }
            log.warn("配置的 ffmpeg-path 不存在: {}", cfg.getFfmpegPath());
        }
        String platform = Loader.getPlatform();
        String[] resourcePaths = {
                "org/bytedeco/ffmpeg/" + platform + "/ffmpeg.exe",
                "org/bytedeco/ffmpeg/" + platform + "/ffmpeg",
                "org/bytedeco/ffmpeg/" + platform + "/bin/ffmpeg.exe",
        };
        for (String res : resourcePaths) {
            try {
                File cached = Loader.cacheResource(res);
                if (cached != null && cached.isFile() && cached.length() > 0) {
                    cached.setExecutable(true);
                    return cached.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            String loaded = Loader.load(ffmpeg.class);
            File f = new File(loaded);
            if (f.isFile() && loaded.toLowerCase().endsWith(".exe")) {
                return f.getAbsolutePath();
            }
            File dir = f.isDirectory() ? f : f.getParentFile();
            if (dir != null) {
                File winExe = new File(dir, "ffmpeg.exe");
                if (winExe.isFile()) {
                    return winExe.getAbsolutePath();
                }
            }
        } catch (Throwable e) {
            log.warn("加载 bytedeco ffmpeg 失败: {}", e.toString());
        }
        return "ffmpeg";
    }

    private static String maskRtspUrl(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int at = url.indexOf('@', scheme + 3);
        if (at < 0) {
            return url;
        }
        return url.substring(0, scheme + 3) + "***:***@" + url.substring(at + 1);
    }

    private static int mjpegQ(double quality) {
        double q = Math.max(0.05, Math.min(1.0, quality));
        return (int) Math.round(31.0 - q * 28.0);
    }

    private static void drainStderr(InputStream err, AtomicReference<String> tail) {
        byte[] buf = new byte[1024];
        StringBuilder sb = new StringBuilder();
        try {
            int n;
            while ((n = err.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.length() > 8192) {
                    sb.delete(0, sb.length() - 4096);
                }
            }
        } catch (IOException ignored) {
        } finally {
            tail.set(sb.toString().trim());
            try {
                err.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() {
        readerRunning = false;
        try {
            OutputStream os = process.getOutputStream();
            os.close();
        } catch (Exception ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
        try {
            if (readerThread != null) {
                readerThread.join(1500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (stderrThread != null) {
                stderrThread.join(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
