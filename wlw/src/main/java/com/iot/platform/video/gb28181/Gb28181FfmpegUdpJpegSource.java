package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从本机 UDP 端口接收国标 RTP(PS/TS) 流，经 FFmpeg 子进程解码为 MJPEG 帧。
 */
public final class Gb28181FfmpegUdpJpegSource implements Gb28181JpegSource {

    private static final Logger log = LoggerFactory.getLogger(Gb28181FfmpegUdpJpegSource.class);

    private final Process process;
    private final BufferedInputStream input;
    private final Path sdpFile;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicLong lastFrameAtMs = new AtomicLong(0);
    private final AtomicReference<String> stderrTail = new AtomicReference<>("");
    private volatile boolean readerRunning;
    private Thread readerThread;
    private Thread stderrThread;

    private Gb28181FfmpegUdpJpegSource(Process process, BufferedInputStream input, Path sdpFile) {
        this.process = process;
        this.input = input;
        this.sdpFile = sdpFile;
    }

    public static Gb28181FfmpegUdpJpegSource start(
            int udpPort,
            IotProperties.Video cfg,
            String mediaHost,
            String remoteSsrcY,
            String rtpSenderIp,
            Gb28181MediaTransport transport)
            throws IOException {
        return start(udpPort, cfg, mediaHost, remoteSsrcY, rtpSenderIp, transport, false);
    }

    public static Gb28181FfmpegUdpJpegSource start(
            int udpPort,
            IotProperties.Video cfg,
            String mediaHost,
            String remoteSsrcY,
            String rtpSenderIp,
            Gb28181MediaTransport transport,
            boolean tcpBridged)
            throws IOException {
        int maxW = Math.max(320, cfg.getMaxFrameWidth());
        int q = mjpegQ(cfg.getJpegQuality());
        String exe = Gb28181FfmpegPaths.resolveExecutable(cfg);
        if (remoteSsrcY != null && !remoteSsrcY.trim().isEmpty()) {
            log.debug("GB28181 收流不按 SSRC y={} 过滤", remoteSsrcY.trim());
        }

        IOException lastError = null;
        boolean portBusy = false;
        // 国标为 RTP 封装的 PS；优先 0.0.0.0 绑定，避免多 profile 轮换再次丢首包
        Profile[] profiles = {
                new Profile("sdp-ps-any", Gb28181SdpUtil.RecvPayload.PS, true),
                new Profile("sdp-ps", Gb28181SdpUtil.RecvPayload.PS, false),
        };
        for (Profile profile : profiles) {
            if (portBusy) {
                break;
            }
            Path sdp = null;
            try {
                SdpHolder holder = new SdpHolder();
                String inputArg = resolveInput(profile, udpPort, mediaHost, rtpSenderIp, transport, holder);
                sdp = holder.sdp;
                List<String> cmd = buildCommand(exe, inputArg, maxW, q, profile.udpFormat, tcpBridged);
                log.info("GB28181 FFmpeg profile={} port={} input={}", profile.name, udpPort, inputArg);
                if (log.isDebugEnabled() && sdp != null) {
                    log.debug("GB28181 recv SDP:\n{}", new String(Files.readAllBytes(sdp), StandardCharsets.UTF_8));
                }
                Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
                String err = drainStderrBrief(p.getErrorStream(), 300);
                if (!p.isAlive()) {
                    log.warn("GB28181 FFmpeg profile={} 秒退 exit={} stderr={}", profile.name, p.exitValue(), err);
                    lastError = new IOException("ffmpeg exit " + p.exitValue() + ": " + err);
                    if (isPortBusy(err)) {
                        portBusy = true;
                    }
                    deleteQuiet(sdp);
                    continue;
                }
                if (isUnsupportedOptionError(err)) {
                    p.destroyForcibly();
                    lastError = new IOException(err);
                    deleteQuiet(sdp);
                    continue;
                }
                Gb28181FfmpegUdpJpegSource src = new Gb28181FfmpegUdpJpegSource(
                        p, new BufferedInputStream(p.getInputStream(), 256 * 1024), sdp);
                sdp = null;
                src.startReader();
                src.startStderrDrain(p.getErrorStream());
                if (!src.waitProcessAlive(800L)) {
                    log.warn("GB28181 FFmpeg profile={} 进程退出: {}", profile.name, src.stderrHint());
                    src.close();
                    lastError = new IOException(src.stderrHint());
                    continue;
                }
                long firstFrameMs = tcpBridged
                        ? Math.max(25_000L, cfg.getOpenTimeoutMs() * 2L)
                        : Math.max(8000L, cfg.getOpenTimeoutMs());
                if (!src.waitForFirstJpeg(firstFrameMs)) {
                    log.warn("GB28181 FFmpeg profile={} 超时无 MJPEG 帧: {}", profile.name, src.stderrHint());
                    src.close();
                    lastError = new IOException("profile " + profile.name + " 无首帧: " + src.stderrHint());
                    if (!tcpBridged) {
                        portBusy = true;
                    } else {
                        sleepQuiet(600L);
                    }
                    continue;
                }
                log.info("GB28181 FFmpeg profile={} 首帧已出", profile.name);
                return src;
            } catch (IOException e) {
                lastError = e;
                if (isPortBusy(e.getMessage())) {
                    portBusy = true;
                }
                deleteQuiet(sdp);
            }
        }
        throw lastError != null ? lastError : new IOException("GB28181 FFmpeg 无法启动");
    }

    private static String resolveInput(
            Profile profile,
            int udpPort,
            String mediaHost,
            String rtpSenderIp,
            Gb28181MediaTransport transport,
            SdpHolder holder)
            throws IOException {
        Path sdp = Gb28181SdpUtil.writeRecvSdp(
                udpPort, mediaHost, profile.payload, profile.bindAllIfaces, rtpSenderIp, transport);
        holder.sdp = sdp;
        return Gb28181SdpUtil.pathForFfmpeg(sdp);
    }

    private static List<String> buildCommand(
            String exe, String inputArg, int maxW, int q, String udpFormat, boolean tcpBridged) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-analyzeduration");
        cmd.add(tcpBridged ? "20000000" : "15000000");
        cmd.add("-probesize");
        cmd.add(tcpBridged ? "100000000" : "50000000");
        cmd.add("-max_delay");
        cmd.add(tcpBridged ? "5000000" : "2000000");
        cmd.add("-reorder_queue_size");
        cmd.add(tcpBridged ? "65536" : "8192");
        cmd.add("-flags");
        cmd.add("low_delay");
        cmd.add("-err_detect");
        cmd.add("ignore_err");
        cmd.add("-fflags");
        cmd.add("+genpts+discardcorrupt+nobuffer");
        cmd.add("-rtbufsize");
        cmd.add(tcpBridged ? "16777216" : "4194304");
        cmd.add("-thread_queue_size");
        cmd.add("1024");
        cmd.add("-protocol_whitelist");
        cmd.add("file,udp,rtp,tcp");
        cmd.add("-i");
        cmd.add(inputArg);
        cmd.add("-an");
        cmd.add("-vf");
        cmd.add("scale=" + maxW + ":-2");
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

    private boolean waitProcessAlive(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            sleepQuiet(80L);
        }
        return process.isAlive();
    }

    private boolean waitForFirstJpeg(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            byte[] jpg = latestJpeg.get();
            if (jpg != null && jpg.length >= 100) {
                return true;
            }
            sleepQuiet(50L);
        }
        return latestJpeg.get() != null && latestJpeg.get().length >= 100;
    }

    private static boolean isUnsupportedOptionError(String err) {
        if (err == null || err.isEmpty()) {
            return false;
        }
        String lower = err.toLowerCase();
        return lower.contains("option not found")
                || lower.contains("unrecognized option")
                || lower.contains("unknown option")
                || lower.contains("invalid argument");
    }

    private static String drainStderrBrief(InputStream err, int maxMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[2048];
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            int avail = err.available();
            if (avail <= 0) {
                sleepQuiet(40L);
                continue;
            }
            int n = err.read(buf, 0, Math.min(buf.length, avail));
            if (n > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (sb.length() > 4000) {
                    break;
                }
            }
        }
        return sb.toString().trim();
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
        readerRunning = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        deleteQuiet(sdpFile);
    }

    private void startReader() {
        readerRunning = true;
        readerThread = new Thread(() -> {
            byte[] buf = new byte[512 * 1024];
            try {
                int len;
                while (readerRunning && (len = readJpegFrame(buf)) > 0) {
                    byte[] frame = new byte[len];
                    System.arraycopy(buf, 0, frame, 0, len);
                    latestJpeg.set(frame);
                    lastFrameAtMs.set(System.currentTimeMillis());
                }
            } catch (IOException e) {
                if (readerRunning) {
                    log.debug("GB28181 jpeg reader: {}", e.toString());
                }
            }
        }, "gb28181-jpeg-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private int readJpegFrame(byte[] buf) throws IOException {
        int state = 0;
        int start = 0;
        int pos = 0;
        int b;
        while ((b = input.read()) >= 0) {
            if (pos >= buf.length) {
                return 0;
            }
            buf[pos++] = (byte) b;
            if (state == 0 && b == 0xFF) {
                state = 1;
            } else if (state == 1 && b == 0xD8) {
                start = pos - 2;
                state = 2;
            } else if (state == 2 && b == 0xFF) {
                state = 3;
            } else if (state == 3 && b == 0xD9) {
                return pos - start;
            } else {
                state = (b == 0xFF) ? 1 : 0;
            }
        }
        return 0;
    }

    private void startStderrDrain(InputStream err) {
        stderrThread = new Thread(() -> {
            byte[] line = new byte[4096];
            StringBuilder acc = new StringBuilder();
            try {
                int n;
                while ((n = err.read(line)) >= 0) {
                    if (n > 0) {
                        acc.append(new String(line, 0, n, StandardCharsets.UTF_8));
                        if (acc.length() > 8000) {
                            acc.delete(0, acc.length() - 4000);
                        }
                        String chunk = acc.toString().trim();
                        if (!chunk.isEmpty()) {
                            stderrTail.set(chunk.length() > 500 ? chunk.substring(chunk.length() - 500) : chunk);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "gb28181-ffmpeg-err");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private static boolean isPortBusy(String err) {
        return err != null && (err.contains("10048") || err.contains("bind failed"));
    }

    private static int mjpegQ(double quality) {
        double q = quality <= 0 ? 0.72 : Math.min(1.0, quality);
        return Math.max(2, Math.min(31, (int) Math.round(31 - q * 29)));
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuiet(Path sdp) {
        if (sdp == null) {
            return;
        }
        try {
            Files.deleteIfExists(sdp);
        } catch (IOException ignored) {
        }
    }

    private static final class SdpHolder {
        Path sdp;
    }

    private static final class Profile {
        final String name;
        final Gb28181SdpUtil.RecvPayload payload;
        final boolean bindAllIfaces;
        final String udpFormat;

        Profile(String name, Gb28181SdpUtil.RecvPayload payload, boolean bindAllIfaces) {
            this.name = name;
            this.payload = payload;
            this.bindAllIfaces = bindAllIfaces;
            this.udpFormat = null;
        }
    }
}
