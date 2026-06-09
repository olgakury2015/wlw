package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 国标 TCP 被动：摄像机连入后将媒体字节流 pipe 给 FFmpeg（-f mpeg），不经 UDP/RTP 桥接。
 */
public final class Gb28181FfmpegTcpPipeSource implements Gb28181JpegSource {

    private static final Logger log = LoggerFactory.getLogger(Gb28181FfmpegTcpPipeSource.class);

    private final Process process;
    private final Socket tcpSocket;
    private final InputStream mediaIn;
    private final BufferedInputStream jpegIn;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicLong lastFrameAtMs = new AtomicLong(0);
    private final AtomicReference<String> stderrTail = new AtomicReference<>("");
    private volatile boolean readerRunning;
    private Thread readerThread;
    private Thread stderrThread;
    private Thread pumpThread;

    private Gb28181FfmpegTcpPipeSource(
            Process process, Socket tcpSocket, InputStream mediaIn, BufferedInputStream jpegIn) {
        this.process = process;
        this.tcpSocket = tcpSocket;
        this.mediaIn = mediaIn;
        this.jpegIn = jpegIn;
    }

    public static Gb28181FfmpegTcpPipeSource start(Socket tcp, IotProperties.Video cfg) throws IOException {
        tcp.setTcpNoDelay(true);
        return startWithInput(tcp.getInputStream(), tcp, cfg);
    }

    /** 已 peek 首字节并 unread 的输入流（用于区分 PS 直送与 RTP 桥接）。 */
    public static Gb28181FfmpegTcpPipeSource startWithInput(
            InputStream mediaIn, Socket tcp, IotProperties.Video cfg) throws IOException {
        String exe = Gb28181FfmpegPaths.resolveExecutable(cfg);
        int maxW = Math.max(320, cfg.getMaxFrameWidth());
        int q = mjpegQ(cfg.getJpegQuality());
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-fflags");
        cmd.add("+genpts+discardcorrupt+nobuffer");
        cmd.add("-analyzeduration");
        cmd.add("20000000");
        cmd.add("-probesize");
        cmd.add("100000000");
        cmd.add("-f");
        cmd.add("mpeg");
        cmd.add("-i");
        cmd.add("pipe:0");
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
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        Gb28181FfmpegTcpPipeSource src = new Gb28181FfmpegTcpPipeSource(
                p, tcp, mediaIn, new BufferedInputStream(p.getInputStream(), 256 * 1024));
        src.pumpThread = new Thread(() -> src.pumpTcpToStdin(), "gb28181-tcp-pipe-pump");
        src.pumpThread.setDaemon(true);
        src.pumpThread.start();
        src.startStderrDrain(p.getErrorStream());
        if (!src.waitProcessAlive(1500L)) {
            src.close();
            throw new IOException("FFmpeg TCP pipe 秒退: " + src.stderrHint());
        }
        src.startReader();
        long timeout = Math.max(25_000L, cfg.getOpenTimeoutMs() * 2L);
        if (!src.waitForFirstJpeg(timeout)) {
            src.close();
            throw new IOException("FFmpeg TCP pipe 首帧超时: " + src.stderrHint());
        }
        log.info("GB28181 FFmpeg TCP pipe 首帧已出");
        return src;
    }

    private void pumpTcpToStdin() {
        try (InputStream in = mediaIn;
             OutputStream out = process.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            log.debug("GB28181 TCP pipe 泵结束: {}", e.toString());
        } finally {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
        }
    }

    private void startStderrDrain(InputStream err) {
        stderrThread = new Thread(() -> {
            byte[] buf = new byte[2048];
            StringBuilder sb = new StringBuilder();
            try {
                int n;
                while ((n = err.read(buf)) >= 0) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    if (sb.length() < 4000) {
                        sb.append(chunk);
                    }
                }
            } catch (IOException ignored) {
            }
            stderrTail.set(sb.toString().trim());
        }, "gb28181-tcp-pipe-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
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
                    log.debug("GB28181 TCP pipe jpeg reader: {}", e.toString());
                }
            }
        }, "gb28181-tcp-pipe-jpeg");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private int readJpegFrame(byte[] buf) throws IOException {
        int state = 0;
        int start = 0;
        int pos = 0;
        int b;
        while ((b = jpegIn.read()) >= 0) {
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
        byte[] jpg = latestJpeg.get();
        return jpg != null && jpg.length >= 100;
    }

    private static int mjpegQ(double quality) {
        return (int) Math.round(Math.max(2, Math.min(31, quality * 31)));
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        readerRunning = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
        if (pumpThread != null) {
            pumpThread.interrupt();
        }
        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (tcpSocket != null && !tcpSocket.isClosed()) {
            try {
                tcpSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
