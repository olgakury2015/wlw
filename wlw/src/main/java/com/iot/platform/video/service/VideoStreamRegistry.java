package com.iot.platform.video.service;

import com.iot.platform.config.IotProperties;
import com.iot.platform.video.entity.CameraChannel;
import com.iot.platform.video.gb28181.Gb28181DeviceRegistry;
import com.iot.platform.video.gb28181.Gb28181DeviceSession;
import com.iot.platform.video.gb28181.Gb28181JpegSource;
import com.iot.platform.video.gb28181.Gb28181MediaSession;
import com.iot.platform.video.gb28181.Gb28181NetUtil;
import com.iot.platform.video.gb28181.Gb28181PlayManager;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import com.iot.platform.video.repo.CameraChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每路摄像头一个拉流线程；JavaCV grabImage + OpenCV imencode → MJPEG。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStreamRegistry {

    private static final int GRAB_NULL_RECONNECT_THRESHOLD = 100;

    private final CameraChannelRepository cameraChannelRepository;
    private final OnvifRtspResolverService onvifRtspResolverService;
    private final IotProperties iotProperties;
    private final VlcjFactoryHolder vlcjFactoryHolder;
    private final Gb28181PlayManager gb28181PlayManager;
    private final Gb28181DeviceRegistry gb28181DeviceRegistry;
    private final Gb28181PlatformConfigService gb28181PlatformConfigService;

    private final ConcurrentHashMap<Long, Worker> workers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> idleStopTasks = new ConcurrentHashMap<>();
    /** 国标拉流占用摄像机 IP 时，暂停同 IP 的 RTSP watchdog 自动重启（避免 id=4 与 id=7 抢海康） */
    private final ConcurrentHashMap<String, AtomicInteger> gb28181ExclusiveIps = new ConcurrentHashMap<>();

    private ScheduledExecutorService idleScheduler;

    @PostConstruct
    void startScheduler() {
        idleScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "video-scheduler");
            t.setDaemon(true);
            return t;
        });
        idleScheduler.scheduleAtFixedRate(this::ensureWorkersForSubscribers, 3, 3, TimeUnit.SECONDS);
    }

    private void ensureWorkersForSubscribers() {
        for (Map.Entry<Long, AtomicInteger> e : subscribers.entrySet()) {
            if (e.getValue() == null || e.getValue().get() <= 0) {
                continue;
            }
            Long channelId = e.getKey();
            CameraChannel ch = cameraChannelRepository.findById(channelId).orElse(null);
            if (ch == null || !ch.isEnabled()) {
                subscribers.remove(channelId);
                stopWorkerNow(channelId);
                continue;
            }
            Worker w = workers.get(channelId);
            if (w == null || !w.isThreadAlive()) {
                if (isRtspWatchdogBlockedByGb28181(channelId)) {
                    continue;
                }
                log.info("视频通道 id={} 拉流线程不在运行，自动重启", channelId);
                startWorker(channelId);
            }
        }
    }

    private boolean isGb28181ExclusiveIp(String cameraIp) {
        if (!Gb28181NetUtil.isIpv4(cameraIp)) {
            return false;
        }
        AtomicInteger hold = gb28181ExclusiveIps.get(cameraIp.trim());
        return hold != null && hold.get() > 0;
    }

    private boolean isRtspWatchdogBlockedByGb28181(Long channelId) {
        CameraChannel ch = cameraChannelRepository.findById(channelId).orElse(null);
        if (ch == null || ch.isGb28181Source()) {
            return false;
        }
        String ip = Gb28181NetUtil.extractIpv4FromRtsp(channelRtspUrlOnly(ch));
        if (!Gb28181NetUtil.isIpv4(ip)) {
            return false;
        }
        AtomicInteger hold = gb28181ExclusiveIps.get(ip);
        return hold != null && hold.get() > 0;
    }

    private void holdGb28181ExclusiveIp(String cameraIp) {
        if (!Gb28181NetUtil.isIpv4(cameraIp)) {
            return;
        }
        gb28181ExclusiveIps.computeIfAbsent(cameraIp.trim(), k -> new AtomicInteger(0)).incrementAndGet();
    }

    private void releaseGb28181ExclusiveIp(String cameraIp) {
        if (!Gb28181NetUtil.isIpv4(cameraIp)) {
            return;
        }
        AtomicInteger hold = gb28181ExclusiveIps.get(cameraIp.trim());
        if (hold == null) {
            return;
        }
        if (hold.decrementAndGet() <= 0) {
            gb28181ExclusiveIps.remove(cameraIp.trim(), hold);
        }
    }

    @PreDestroy
    public void destroy() {
        for (Long id : workers.keySet()) {
            stopWorkerNow(id);
        }
        idleStopTasks.values().forEach(f -> f.cancel(false));
        if (idleScheduler != null) {
            idleScheduler.shutdownNow();
        }
    }

    @Transactional(readOnly = true)
    public void acquire(Long channelId) {
        CameraChannel ch = cameraChannelRepository.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("通道不存在"));
        if (!ch.isEnabled()) {
            throw new IllegalArgumentException("通道已禁用");
        }
        subscribers.computeIfAbsent(channelId, k -> new AtomicInteger(0)).incrementAndGet();
        ScheduledFuture<?> prev = idleStopTasks.remove(channelId);
        if (prev != null) {
            prev.cancel(false);
        }
        if (ch.isGb28181Source()) {
            String ip = resolveGb28181CameraIpForChannel(
                    ch.getGb28181DeviceId() != null ? ch.getGb28181DeviceId().trim() : "", ch);
            if (Gb28181NetUtil.isIpv4(ip)) {
                if (!isGb28181ExclusiveIp(ip)) {
                    holdGb28181ExclusiveIp(ip);
                }
                stopRtspWorkersOnCameraIp(ip, channelId);
            }
        }
        Worker running = workers.get(channelId);
        if (running != null && running.isThreadAlive()) {
            return;
        }
        startWorker(channelId);
    }

    private void startWorker(Long channelId) {
        CameraChannel ch = cameraChannelRepository.findById(channelId).orElse(null);
        if (ch == null || !ch.isEnabled()) {
            log.warn("视频通道 id={} 不存在或已禁用", channelId);
            return;
        }
        if (ch.isGb28181Source()) {
            startGb28181Worker(channelId, ch);
            return;
        }
        String rtspIp = Gb28181NetUtil.extractIpv4FromRtsp(channelRtspUrlOnly(ch));
        if (isGb28181ExclusiveIp(rtspIp)) {
            log.warn("视频通道 id={} 跳过 RTSP：摄像机 {} 正由国标占用，请停用 id=4 或关闭其预览", channelId, rtspIp);
            return;
        }
        String rtsp = resolveRtsp(ch);
        if (!StringUtils.hasText(rtsp)) {
            log.warn("视频通道 id={} 无 RTSP 地址，无法拉流", channelId);
            return;
        }
        String rtspKey = RtspFrameGrabberFactory.normalizeRtspUrl(rtsp);
        for (Map.Entry<Long, Worker> e : workers.entrySet()) {
            Worker shared = e.getValue();
            if (shared == null || !rtspKey.equals(shared.rtspKey) || !shared.isThreadAlive()) {
                continue;
            }
            if (shared.channelIds.contains(channelId)) {
                workers.put(channelId, shared);
                return;
            }
            shared.attachChannel(channelId);
            workers.put(channelId, shared);
            log.info("视频通道 id={} 与 id={} 共享同一路 RTSP（单 VLC 连接）", channelId, e.getKey());
            return;
        }
        workers.compute(channelId, (cid, existing) -> {
            if (existing != null && existing.isThreadAlive()) {
                if (rtspKey.equals(existing.rtspKey)) {
                    existing.attachChannel(channelId);
                    return existing;
                }
            }
            if (existing != null) {
                existing.stopSafely();
            }
            return new Worker(channelId, rtsp, rtspKey, false, null, null, 0);
        });
    }

    private void stopRtspWorkersOnCameraIp(String cameraIp, Long gbChannelId) {
        if (!Gb28181NetUtil.isIpv4(cameraIp)) {
            return;
        }
        for (Map.Entry<Long, Worker> e : workers.entrySet()) {
            Worker w = e.getValue();
            if (w == null || w.gb28181 || !w.isThreadAlive()) {
                continue;
            }
            String url = w.rtspUrl;
            if (url != null && url.contains(cameraIp)) {
                log.warn("国标通道 id={} 开播，立即停掉同机 RTSP 通道 id={}（{}）", gbChannelId, e.getKey(), cameraIp);
                w.stopSafely();
                subscribers.remove(e.getKey());
            }
        }
    }

    private String resolveGb28181CameraIpForChannel(String deviceId, CameraChannel ch) {
        if (StringUtils.hasText(deviceId)) {
            Gb28181DeviceSession dev = gb28181DeviceRegistry.get(deviceId.trim());
            if (dev != null && Gb28181NetUtil.isIpv4(dev.getContactHost())) {
                return dev.getContactHost();
            }
        }
        return Gb28181NetUtil.extractIpv4FromRtsp(channelRtspUrlOnly(ch));
    }

    private void startGb28181Worker(Long channelId, CameraChannel ch) {
        String devId = ch.getGb28181DeviceId().trim();
        if (!StringUtils.hasText(ch.getGb28181ChannelId())) {
            throw new IllegalStateException("通道 id=" + channelId + " 未配置国标通道编码，请在编辑页填写海康「视频通道编码 ID」");
        }
        String chId = ch.getGb28181ChannelId().trim();
        if (devId.equals(chId)) {
            log.error("通道 id={} 国标通道编码与设备编码相同({})，请在编辑页改为海康「视频通道编码 ID」如 34020000001320000003",
                    channelId, devId);
        }
        int streamIdx = ch.getGb28181StreamIndex() != null ? ch.getGb28181StreamIndex() : 0;
        String streamKey = "gb28181:" + devId + ":" + chId + ":" + streamIdx;
        for (Map.Entry<Long, Worker> e : workers.entrySet()) {
            Worker shared = e.getValue();
            if (shared == null || !streamKey.equals(shared.rtspKey) || !shared.isThreadAlive()) {
                continue;
            }
            if (shared.channelIds.contains(channelId)) {
                workers.put(channelId, shared);
                return;
            }
            shared.attachChannel(channelId);
            workers.put(channelId, shared);
            log.info("视频通道 id={} 与 id={} 共享同一路国标流", channelId, e.getKey());
            return;
        }
        workers.compute(channelId, (cid, existing) -> {
            if (existing != null && existing.isThreadAlive() && streamKey.equals(existing.rtspKey)) {
                existing.attachChannel(channelId);
                return existing;
            }
            if (existing != null) {
                existing.stopSafely();
            }
            return new Worker(channelId, streamKey, streamKey, true, devId, chId, streamIdx);
        });
    }

    @Transactional(readOnly = true)
    private String resolveRtspUrlForChannel(Long channelId) {
        CameraChannel ch = cameraChannelRepository.findById(channelId).orElse(null);
        if (ch == null || !ch.isEnabled()) {
            return null;
        }
        return resolveRtsp(ch);
    }

    private String resolveRtsp(CameraChannel ch) {
        if (StringUtils.hasText(ch.getRtspUrl())) {
            return ch.getRtspUrl().trim();
        }
        return onvifRtspResolverService.resolveRtspUrl(
                ch.getOnvifDeviceServiceUrl(),
                ch.getOnvifUsername(),
                ch.getOnvifPassword());
    }

    /** 仅使用通道表里的 RTSP，不触发 ONVIF（国标兜底/Contact 推断用）。 */
    private static String channelRtspUrlOnly(CameraChannel ch) {
        if (ch == null || !StringUtils.hasText(ch.getRtspUrl())) {
            return null;
        }
        return ch.getRtspUrl().trim();
    }

    public void release(Long channelId) {
        AtomicInteger c = subscribers.get(channelId);
        if (c == null) {
            return;
        }
        int v = c.decrementAndGet();
        if (v <= 0) {
            int delay = Math.max(5, iotProperties.getVideo().getSubscriberIdleShutdownSeconds());
            ScheduledFuture<?> f = idleScheduler.schedule(() -> {
                AtomicInteger cur = subscribers.get(channelId);
                if (cur != null && cur.get() <= 0) {
                    stopWorkerNow(channelId);
                }
                idleStopTasks.remove(channelId);
            }, delay, TimeUnit.SECONDS);
            idleStopTasks.put(channelId, f);
        }
    }

    public byte[] getLatestJpeg(Long channelId) {
        VideoJpegSnapshot snap = getLatestJpegSnapshot(channelId);
        return snap != null ? snap.getJpeg() : null;
    }

    public VideoJpegSnapshot getLatestJpegSnapshot(Long channelId) {
        ensureWorkersForSubscribers();
        Worker w = workers.get(channelId);
        if (w == null) {
            return VideoJpegSnapshot.waiting(null);
        }
        byte[] jpg = w.latest.get();
        if (jpg != null && jpg.length > 0) {
            return VideoJpegSnapshot.fresh(jpg);
        }
        return VideoJpegSnapshot.waiting(null);
    }

    private void stopWorkerNow(Long channelId) {
        Worker w = workers.remove(channelId);
        if (w == null) {
            return;
        }
        if (w.detachChannel(channelId) <= 0) {
            w.stopSafely();
        }
    }

    public void evictChannel(Long channelId) {
        ScheduledFuture<?> f = idleStopTasks.remove(channelId);
        if (f != null) {
            f.cancel(false);
        }
        subscribers.remove(channelId);
        stopWorkerNow(channelId);
    }

    public void restartIfRunning(Long channelId) {
        Worker w = workers.get(channelId);
        if (w != null) {
            w.requestReconnect();
        }
    }

    private final class Worker implements Runnable {

        private final Long primaryChannelId;
        private final String rtspUrl;
        private final String rtspKey;
        private final boolean gb28181;
        private final String gbDeviceId;
        private final String gbChannelId;
        private final int gbStreamIndex;
        private final java.util.Set<Long> channelIds = ConcurrentHashMap.newKeySet();
        private final AtomicInteger refCount = new AtomicInteger(1);
        private final AtomicReference<byte[]> latest = new AtomicReference<>();
        private volatile Thread thread;
        private volatile boolean stop;
        private volatile boolean reconnectRequested;
        private volatile FFmpegFrameGrabber grabber;
        private long lastGb28181StartLogMs;
        private volatile String heldGb28181CameraIp;

        private Worker(Long channelId, String rtspUrl, String rtspKey, boolean gb28181,
                       String gbDeviceId, String gbChannelId, int gbStreamIndex) {
            this.primaryChannelId = channelId;
            this.rtspUrl = rtspUrl;
            this.rtspKey = rtspKey;
            this.gb28181 = gb28181;
            this.gbDeviceId = gbDeviceId;
            this.gbChannelId = gbChannelId;
            this.gbStreamIndex = gbStreamIndex;
            this.channelIds.add(channelId);
            this.thread = new Thread(this, gb28181 ? "camera-gb28181-" + channelId : "camera-rtsp-" + channelId);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void attachChannel(Long channelId) {
            channelIds.add(channelId);
            refCount.incrementAndGet();
        }

        int detachChannel(Long channelId) {
            channelIds.remove(channelId);
            return refCount.decrementAndGet();
        }

        boolean isThreadAlive() {
            Thread t = thread;
            return t != null && t.isAlive();
        }

        void requestReconnect() {
            reconnectRequested = true;
        }

        void stopSafely() {
            stop = true;
            reconnectRequested = true;
            closeGrabber();
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }

        private void closeGrabber() {
            FFmpegFrameGrabber g = grabber;
            grabber = null;
            if (g != null) {
                try {
                    g.stop();
                    g.release();
                } catch (Exception e) {
                    log.debug("grabber release id={}: {}", primaryChannelId, e.toString());
                }
            }
        }

        @Override
        public void run() {
            IotProperties.Video cfg = iotProperties.getVideo();
            String decoder = videoDecoderMode(cfg);
            log.info("摄像头拉流线程启动 id={} gb28181={} decoder={} key={}",
                    primaryChannelId, gb28181, decoder, rtspKey);
            if (gb28181) {
                CameraChannel chInit = cameraChannelRepository.findById(primaryChannelId).orElse(null);
                if (chInit != null) {
                    heldGb28181CameraIp = resolveGb28181CameraIpForChannel(gbDeviceId, chInit);
                    if (!isGb28181ExclusiveIp(heldGb28181CameraIp)) {
                        holdGb28181ExclusiveIp(heldGb28181CameraIp);
                    }
                    stopConflictingRtspWorkers(heldGb28181CameraIp);
                }
            }
            int failStreak = 0;
            try {
                while (!stop) {
                    try {
                        CameraChannel ch = cameraChannelRepository.findById(primaryChannelId).orElse(null);
                        if (ch == null || !ch.isEnabled()) {
                            break;
                        }
                        if (gb28181) {
                            pumpGb28181(cfg);
                            if (stop) {
                                break;
                            }
                            reconnectRequested = false;
                            failStreak++;
                            long gbBackoff = failStreak >= 5 ? 5000L : Math.max(2000L, cfg.getFfmpegMinReconnectMs());
                            sleepQuiet(gbBackoff);
                            continue;
                        }
                        switch (decoder) {
                            case "vlcj":
                                pumpVlcjOrFallback(rtspUrl, cfg);
                                break;
                            case "ffmpeg":
                                pumpFfmpeg(rtspUrl, cfg);
                                break;
                            default:
                                pumpJavaCv(rtspUrl, cfg);
                                break;
                        }
                        failStreak = 0;
                    } catch (IllegalArgumentException ex) {
                        log.warn("摄像头 id={} 配置错误: {}", primaryChannelId, ex.getMessage());
                        sleepQuiet(3000);
                    } catch (Exception ex) {
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                        log.warn("摄像头 id={} 拉流异常: {}", primaryChannelId, msg);
                        long backoff = 2000L;
                        if (gb28181 && msg != null && msg.contains("INVITE 400")) {
                            backoff = 60_000L;
                        } else if (gb28181 && msg != null && (msg.contains("未在线") || msg.contains("未注册")
                                || msg.contains("未 REGISTER") || msg.contains("Contact") || msg.contains("403")
                                || msg.contains("400") || msg.contains("BYE") || msg.contains("REGISTER")
                                || msg.contains("10048") || msg.contains("预绑定") || msg.contains("冷却"))) {
                            backoff = 30_000L;
                        }
                        sleepQuiet(backoff);
                    }
                    closeGrabber();
                    Thread.interrupted();
                    if (stop) {
                        break;
                    }
                    reconnectRequested = false;
                    failStreak++;
                    long backoff = failStreak >= 5 ? 3000L : 400L;
                    sleepQuiet(backoff);
                }
            } finally {
                closeGrabber();
                if (heldGb28181CameraIp != null) {
                    releaseGb28181ExclusiveIp(heldGb28181CameraIp);
                    heldGb28181CameraIp = null;
                }
                for (Long cid : channelIds) {
                    workers.remove(cid, this);
                }
                if (stop) {
                    log.info("摄像头拉流线程结束 primaryId={} 共享通道={}", primaryChannelId, channelIds);
                } else {
                    log.warn("摄像头 id={} 拉流线程意外结束，若有观众将由 watchdog 重启", primaryChannelId);
                }
            }
        }

        /** VLCJ 不可用时自动回退 ffmpeg，避免黑屏。 */
        private void pumpVlcjOrFallback(String rtsp, IotProperties.Video cfg) throws Exception {
            if (!vlcjFactoryHolder.isReady()) {
                log.warn("摄像头 id={} 未检测到 VLC/libvlc.dll，回退 ffmpeg 拉流", primaryChannelId);
                pumpFfmpeg(rtsp, cfg);
                return;
            }
            try {
                pumpVlcj(rtsp, cfg);
            } catch (IllegalStateException ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                if (msg.contains("VLC") || msg.contains("libvlc")) {
                    log.warn("摄像头 id={} VLCJ 加载失败，回退 ffmpeg: {}", primaryChannelId, msg);
                    pumpFfmpeg(rtsp, cfg);
                } else {
                    throw ex;
                }
            }
        }

        /** VLCJ + 本机 libVLC 拉流；进程内重连，保留最后一帧避免闪黑。 */
        private boolean rtspPausedByGb28181(String rtsp) {
            String ip = Gb28181NetUtil.extractIpv4FromRtsp(rtsp);
            return isGb28181ExclusiveIp(ip);
        }

        private void pumpVlcj(String rtsp, IotProperties.Video cfg) throws Exception {
            if (rtspPausedByGb28181(rtsp)) {
                log.debug("摄像头 id={} 暂停 RTSP：{} 正由国标占用", primaryChannelId, rtsp);
                sleepQuiet(2000);
                return;
            }
            log.info("摄像头 id={} 连接 RTSP (VLCJ) …", primaryChannelId);
            reconnectRequested = false;
            int grabIntervalMs = Math.max(33, cfg.getGrabIntervalMs());
            long stallMs = Math.max(10_000, cfg.getFfmpegStallMs());
            long openTimeoutMs = Math.max(8000, cfg.getOpenTimeoutMs());

            VlcjRtspJpegSource src = null;
            long sessionStartMs = System.currentTimeMillis();
            long lastAliveAtMs = 0;
            boolean loggedFirst = false;
            int vlcFailWithoutFrame = 0;

            try {
                while (!stop && !reconnectRequested) {
                    if (rtspPausedByGb28181(rtsp)) {
                        if (src != null) {
                            src.close();
                            src = null;
                        }
                        sleepQuiet(2000);
                        return;
                    }
                    if (src == null) {
                        src = VlcjRtspJpegSource.start(rtsp, cfg, vlcjFactoryHolder.requireFactory());
                        sessionStartMs = System.currentTimeMillis();
                        lastAliveAtMs = 0;
                        loggedFirst = false;
                    }

                    long now = System.currentTimeMillis();
                    byte[] jpg = src.pollLatestJpeg();
                    if (jpg != null && jpg.length >= 100) {
                        latest.set(jpg);
                        if (src.isPlaying() || src.getLastFrameAtMs() > 0) {
                            lastAliveAtMs = now;
                            if (!loggedFirst) {
                                loggedFirst = true;
                                log.info("摄像头 id={} 首帧已就绪 (VLCJ)", primaryChannelId);
                            }
                        }
                    }

                    boolean needRestart = false;
                    if (lastAliveAtMs == 0 && now - sessionStartMs > openTimeoutMs) {
                        log.warn("摄像头 id={} VLCJ 首帧超时: {}", primaryChannelId, src.statusMessage());
                        needRestart = true;
                    } else if (lastAliveAtMs > 0 && now - lastAliveAtMs > stallMs) {
                        log.warn("摄像头 id={} VLCJ 超过 {}ms 无画面活动，进程内重连", primaryChannelId, stallMs);
                        needRestart = true;
                    } else if (src.isEnded()) {
                        log.warn("摄像头 id={} VLCJ 播放结束，进程内重连: {}", primaryChannelId, src.statusMessage());
                        needRestart = true;
                    }

                    if (needRestart) {
                        src.close();
                        src = null;
                        if (lastAliveAtMs == 0) {
                            vlcFailWithoutFrame++;
                            if (vlcFailWithoutFrame >= 3) {
                                log.warn("摄像头 id={} VLCJ 连续 {} 次无法出画，回退 ffmpeg 拉流",
                                        primaryChannelId, vlcFailWithoutFrame);
                                pumpFfmpeg(rtsp, cfg);
                                return;
                            }
                        } else {
                            vlcFailWithoutFrame = 0;
                        }
                        sleepQuiet(1500);
                        continue;
                    }

                    sleepQuiet(grabIntervalMs);
                }
            } finally {
                if (src != null) {
                    src.close();
                }
            }
        }

        /** JavaCV grabImage + OpenCV imencode。 */
        private void pumpJavaCv(String rtsp, IotProperties.Video cfg) throws Exception {
            long noFrameMs = Math.max(60_000, cfg.getVideoNoFrameReconnectMs());
            log.info("摄像头 id={} 连接 RTSP (JavaCV) …", primaryChannelId);
            FFmpegFrameGrabber g = RtspFrameGrabberFactory.create(rtsp, cfg);
            grabber = g;
            g.start();
            reconnectRequested = false;

            long framesInSession = 0;
            int grabIntervalMs = Math.max(33, cfg.getGrabIntervalMs());
            int maxW = Math.max(320, cfg.getMaxFrameWidth());
            long lastEncodedAtMs = 0;
            long sessionStartMs = System.currentTimeMillis();
            int emptyGrabStreak = 0;
            int encodeFailStreak = 0;

            while (!stop && !reconnectRequested) {
                if (lastEncodedAtMs > 0
                        && System.currentTimeMillis() - lastEncodedAtMs > noFrameMs) {
                    log.warn("摄像头 id={} 超过 {}s 无画面，重连 (grab空={} 编码失败={})",
                            primaryChannelId, noFrameMs / 1000, emptyGrabStreak, encodeFailStreak);
                    break;
                }
                if (framesInSession > 0 && emptyGrabStreak >= GRAB_NULL_RECONNECT_THRESHOLD) {
                    log.warn("摄像头 id={} grab 连续空 {} 次，提前重连（HEVC 解码中断）",
                            primaryChannelId, emptyGrabStreak);
                    break;
                }
                if (framesInSession == 0
                        && System.currentTimeMillis() - sessionStartMs > noFrameMs) {
                    log.warn("摄像头 id={} 首帧超时，重连", primaryChannelId);
                    break;
                }
                Frame frame;
                try {
                    frame = g.grabFrame(false, true, true, false);
                } catch (Exception grabEx) {
                    log.warn("摄像头 id={} grab 失败: {}", primaryChannelId, grabEx.getMessage());
                    break;
                }
                if (frame == null || frame.image == null) {
                    emptyGrabStreak++;
                    sleepQuiet(20);
                    continue;
                }
                emptyGrabStreak = 0;
                Frame work = frame.clone();
                byte[] jpg = OpenCvRtspJpegEncoder.encode(work, maxW, cfg.getJpegQuality());
                try {
                    work.close();
                } catch (Exception ignored) {
                }
                if (jpg == null || jpg.length < 100) {
                    encodeFailStreak++;
                    if (encodeFailStreak % 30 == 1) {
                        log.warn("摄像头 id={} OpenCV 编码失败(连续 {})", primaryChannelId, encodeFailStreak);
                    }
                    sleepQuiet(10);
                    continue;
                }
                encodeFailStreak = 0;
                latest.set(jpg);
                lastEncodedAtMs = System.currentTimeMillis();
                framesInSession++;
                if (framesInSession == 1) {
                    log.info("摄像头 id={} 首帧已就绪", primaryChannelId);
                }
                sleepQuiet(grabIntervalMs);
            }
        }

        private void pumpFfmpeg(String rtsp, IotProperties.Video cfg) throws IOException {
            if (rtspPausedByGb28181(rtsp)) {
                log.debug("摄像头 id={} 暂停 RTSP(ffmpeg)：国标占用同机 IP", primaryChannelId);
                sleepQuiet(2000);
                return;
            }
            long noFrameMs = Math.max(60_000, cfg.getVideoNoFrameReconnectMs());
            log.info("摄像头 id={} 拉流 (ffmpeg，失败后退避重连，不抢连) …", primaryChannelId);
            reconnectRequested = false;
            int grabIntervalMs = Math.max(33, cfg.getGrabIntervalMs());
            int handoffIntervalMs = cfg.getFfmpegHandoffIntervalMs();
            long stallMs = Math.max(5000, cfg.getFfmpegStallMs());
            long minReconnectMs = Math.max(3000, cfg.getFfmpegMinReconnectMs());
            long handoffWaitMs = Math.max(3000, cfg.getFfmpegHandoffFirstFrameMs());
            long openTimeoutMs = Math.max(8000, cfg.getOpenTimeoutMs());
            boolean httpFallback = cfg.isFfmpegHikvisionHttpFallback();

            String rtspUrl = rtsp;
            String inputUrl = rtspUrl;
            String transport = cfg.getRtspTransport() != null ? cfg.getRtspTransport().trim() : "udp";
            if (transport.isEmpty()) {
                transport = "udp";
            }
            if (isLocalZlmRtsp(inputUrl)) {
                transport = "udp";
                log.info("摄像头 id={} 本机 ZLM RTSP 使用 udp 拉流（避免 tcp 被动监听 Permission denied）", primaryChannelId);
            }
            boolean usingHttpPreview = false;
            int resetStreak = 0;
            long lastReconnectAtMs = 0;

            FfmpegRtspJpegSource active = FfmpegRtspJpegSource.start(inputUrl, cfg, transport);
            long sessionStartMs = System.currentTimeMillis();
            long lastEncodedAtMs = 0;
            long lastSeenFrameAt = 0;
            boolean loggedFirst = false;
            int reconnectStreak = 0;

            try {
                while (!stop && !reconnectRequested) {
                    if (rtspPausedByGb28181(rtsp)) {
                        active.close();
                        sleepQuiet(2000);
                        return;
                    }
                    long now = System.currentTimeMillis();
                    long frameAt = active.getLastFrameAtMs();
                    if (frameAt > lastSeenFrameAt) {
                        byte[] jpg = active.pollLatestJpeg();
                        if (jpg != null && jpg.length >= 100) {
                            lastSeenFrameAt = frameAt;
                            latest.set(jpg);
                            lastEncodedAtMs = now;
                            resetStreak = 0;
                            reconnectStreak = 0;
                            if (!loggedFirst) {
                                loggedFirst = true;
                                log.info("摄像头 id={} 首帧已就绪 (ffmpeg)", primaryChannelId);
                            }
                        }
                    }

                    if (lastEncodedAtMs == 0 && now - sessionStartMs > openTimeoutMs) {
                        log.warn("摄像头 id={} ffmpeg 首帧超时，{}ms 后再试", primaryChannelId, minReconnectMs);
                        reconnectStreak++;
                        waitMinReconnectGap(lastReconnectAtMs, minReconnectMs, now);
                        lastReconnectAtMs = System.currentTimeMillis();
                        active = reopenFfmpeg(active, inputUrl, cfg, transport, 0);
                        sessionStartMs = lastReconnectAtMs;
                        lastSeenFrameAt = 0;
                        continue;
                    }

                    boolean needReconnect = false;
                    String reconnectReason = null;
                    if (!active.isAlive()) {
                        needReconnect = true;
                        reconnectReason = "进程退出 code=" + active.exitValue() + " " + active.stderrMessage();
                    } else if (lastEncodedAtMs > 0 && now - active.getLastFrameAtMs() > stallMs) {
                        needReconnect = true;
                        reconnectReason = "超过 " + stallMs + "ms 无新帧";
                    } else if (handoffIntervalMs > 0
                            && lastEncodedAtMs > 0
                            && now - sessionStartMs > handoffIntervalMs) {
                        needReconnect = true;
                        reconnectReason = "定时切换(" + handoffIntervalMs + "ms)";
                    }

                    if (needReconnect) {
                        String stderr = active.stderrMessage();
                        long sessionMs = now - sessionStartMs;
                        if (FfmpegRtspJpegSource.isConnectionReset(stderr)) {
                            resetStreak++;
                        }
                        long cooldown = reconnectCooldownMs(
                                reconnectStreak, stderr, sessionMs, minReconnectMs);
                        waitMinReconnectGap(lastReconnectAtMs, cooldown, now);
                        lastReconnectAtMs = System.currentTimeMillis();
                        reconnectStreak++;

                        if (!usingHttpPreview && httpFallback && resetStreak >= 3) {
                            if (resetStreak == 3 && "tcp".equalsIgnoreCase(transport)) {
                                transport = "udp";
                                log.warn("摄像头 id={} RTSP 频繁 -10054，改用 UDP 拉流", primaryChannelId);
                            } else if (resetStreak >= 6) {
                                java.util.Optional<String> http = HikvisionStreamUrls.httpPreviewFromRtsp(rtspUrl);
                                if (http.isPresent()) {
                                    inputUrl = http.get();
                                    usingHttpPreview = true;
                                    log.warn("摄像头 id={} RTSP 仍不稳定，改用海康 HTTP 预览流", primaryChannelId);
                                }
                            }
                        }

                        if (reconnectStreak <= 3 || reconnectStreak % 5 == 0) {
                            log.warn("摄像头 id={} {}ms 后重连 ffmpeg({}): {}",
                                    primaryChannelId, cooldown, usingHttpPreview ? "HTTP" : transport, reconnectReason);
                        }
                        long waitMs = lastEncodedAtMs > 0 ? handoffWaitMs : openTimeoutMs;
                        active = reopenFfmpeg(active, inputUrl, cfg, transport, 0);
                        sessionStartMs = System.currentTimeMillis();
                        lastSeenFrameAt = 0;
                        if (active.awaitFirstFrame(waitMs)) {
                            byte[] jpg = active.pollLatestJpeg();
                            if (jpg != null && jpg.length >= 100) {
                                latest.set(jpg);
                                lastEncodedAtMs = System.currentTimeMillis();
                                lastSeenFrameAt = active.getLastFrameAtMs();
                            }
                        } else if (reconnectStreak <= 3 || reconnectStreak % 5 == 0) {
                            log.warn("摄像头 id={} ffmpeg 重连后首帧超时", primaryChannelId);
                        }
                    }

                    if (lastEncodedAtMs > 0 && now - lastEncodedAtMs > noFrameMs) {
                        log.warn("摄像头 id={} 超过 {}s 仍无画面，整段重连", primaryChannelId,
                                noFrameMs / 1000);
                        break;
                    }

                    sleepQuiet(grabIntervalMs);
                }
            } finally {
                active.close();
            }
        }

        /** GB/T 28181：平台 INVITE，摄像机向本机 UDP 推 PS，FFmpeg 转 JPEG。 */
        private void pumpGb28181(IotProperties.Video cfg) throws Exception {
            if (!gb28181PlatformConfigService.getOrCreate().isEnabled()) {
                throw new IllegalStateException("国标接入未启用，请在控制台「国标 28181」页启用并保存");
            }
            CameraChannel ch = cameraChannelRepository.findById(primaryChannelId).orElse(null);
            if (ch == null || !ch.isEnabled()) {
                return;
            }
            String devId = ch.getGb28181DeviceId() != null ? ch.getGb28181DeviceId().trim() : "";
            if (!StringUtils.hasText(ch.getGb28181ChannelId())) {
                throw new IllegalArgumentException("未配置国标通道编码，请填写海康「视频通道编码 ID」（如 34020000001320000003）");
            }
            String chId = ch.getGb28181ChannelId().trim();
            if (devId.equals(chId)) {
                throw new IllegalArgumentException(
                        "国标通道编码不能与设备编码相同，请改为海康视频通道编码（如 34020000001320000003）");
            }
            int streamIdx = ch.getGb28181StreamIndex() != null ? ch.getGb28181StreamIndex() : 0;
            long logNow = System.currentTimeMillis();
            if (logNow - lastGb28181StartLogMs >= 5000L) {
                lastGb28181StartLogMs = logNow;
                log.info("摄像头 id={} 启动国标实况 device={} channel={} stream={}",
                        primaryChannelId, devId, chId, streamIdx);
            }
            seedGb28181ContactFromRtsp(devId, ch);
            Gb28181MediaSession session = null;
            Gb28181JpegSource src = null;
            try {
                session = gb28181PlayManager.acquire(devId, chId, streamIdx);
                if (StringUtils.hasText(session.getZlmRtspUrl())) {
                    pumpGb28181ZlmRtsp(session.getZlmRtspUrl(), cfg, devId, chId, streamIdx);
                    return;
                }
                src = session.getJpegSource();
                if (src == null) {
                    throw new IllegalStateException("国标解码器未启动");
                }
                int grabIntervalMs = Math.max(33, cfg.getGrabIntervalMs());
                long stallMs = Math.max(15_000, cfg.getFfmpegStallMs());
                long openTimeoutMs = Math.max(25_000, cfg.getOpenTimeoutMs() * 2L);
                long sessionStart = System.currentTimeMillis();
                long lastAlive = 0;
                boolean loggedFirst = false;
                while (!stop && !reconnectRequested) {
                    if (!gb28181PlayManager.isSessionActive(devId, chId, streamIdx)) {
                        throw new IllegalStateException("国标会话已结束（摄像机 BYE 或未注册），将重试 INVITE");
                    }
                    long now = System.currentTimeMillis();
                    byte[] jpg = src.pollLatestJpeg();
                    if (jpg != null && jpg.length >= 100) {
                        latest.set(jpg);
                        lastAlive = now;
                        if (!loggedFirst) {
                            loggedFirst = true;
                            log.info("摄像头 id={} 国标首帧已就绪", primaryChannelId);
                        }
                    }
                    if (lastAlive == 0 && now - sessionStart > openTimeoutMs) {
                        String hint = src.stderrHint();
                        throw new IllegalStateException("国标首帧超时（设备需在线、media-host 为本机局域网 IP、防火墙放行 RTP 端口池）"
                                + (hint != null && !hint.isEmpty() ? "；ffmpeg: " + hint : ""));
                    }
                    if (lastAlive > 0 && now - lastAlive > stallMs) {
                        throw new IllegalStateException("国标流中断超过 " + (stallMs / 1000) + "s");
                    }
                    sleepQuiet(grabIntervalMs);
                }
            } catch (Exception ex) {
                if (tryGb28181RtspFallback(ch, cfg)) {
                    return;
                }
                throw ex;
            } finally {
                if (src != null) {
                    src.close();
                }
                if (session != null) {
                    gb28181PlayManager.release(session);
                }
            }
        }

        /** 国标经 ZLM 收 RTP 后，用 HTTP-FLV / RTSP 拉流转 MJPEG。 */
        private void pumpGb28181ZlmRtsp(String previewUrl, IotProperties.Video cfg,
                                        String devId, String chId, int streamIdx) throws Exception {
            log.info("摄像头 id={} 国标 ZLM 预览={}", primaryChannelId, previewUrl);
            while (!stop && !reconnectRequested) {
                if (!gb28181PlayManager.isSessionActive(devId, chId, streamIdx)) {
                    throw new IllegalStateException("国标 ZLM 会话已结束，将重试 INVITE");
                }
                pumpFfmpeg(previewUrl, cfg);
                if (stop) {
                    break;
                }
                sleepQuiet(Math.max(2000L, cfg.getFfmpegMinReconnectMs()));
            }
        }

        /** 通道已填 RTSP 时，在 Contact 为空或已离线时用摄像机 IP 兜底。 */
        private void seedGb28181ContactFromRtsp(String deviceId, CameraChannel ch) {
            if (!StringUtils.hasText(deviceId)) {
                return;
            }
            Gb28181DeviceSession dev = gb28181DeviceRegistry.get(deviceId);
            if (dev != null && dev.isOnline() && Gb28181NetUtil.isIpv4(dev.getContactHost())) {
                return;
            }
            String ip = Gb28181NetUtil.extractIpv4FromRtsp(channelRtspUrlOnly(ch));
            if (ip == null) {
                if (dev != null && !dev.isOnline()) {
                    log.warn("摄像头 id={} 国标离线且未配置 RTSP，无法推断 Contact device={}",
                            primaryChannelId, deviceId);
                }
                return;
            }
            gb28181DeviceRegistry.setContact(deviceId, ip, 5060,
                    "sip:" + deviceId + "@" + ip + ":5060");
            log.info("摄像头 id={} 国标 Contact 由 RTSP 推断为 {}:5060（海康页不在线时仍可尝试 INVITE）",
                    primaryChannelId, ip);
        }

        /** 国标失败时用同通道 RTSP（勿与 id=4 同时预览同一摄像机，海康常限单路连接）。 */
        private boolean tryGb28181RtspFallback(CameraChannel ch, IotProperties.Video cfg) throws Exception {
            if (!cfg.isGb28181RtspFallback() || ch == null) {
                return false;
            }
            String rtsp = channelRtspUrlOnly(ch);
            if (!StringUtils.hasText(rtsp)) {
                return false;
            }
            log.warn("摄像头 id={} 国标失败，回退 RTSP（若 id=4 等同机 RTSP 已开，请先关掉其它预览避免海康限连）", primaryChannelId);
            if (heldGb28181CameraIp != null) {
                releaseGb28181ExclusiveIp(heldGb28181CameraIp);
                heldGb28181CameraIp = null;
            }
            pumpVlcjOrFallback(rtsp, cfg);
            return true;
        }

        private String resolveGb28181CameraIp(String deviceId, CameraChannel ch) {
            if (StringUtils.hasText(deviceId)) {
                Gb28181DeviceSession dev = gb28181DeviceRegistry.get(deviceId.trim());
                if (dev != null && Gb28181NetUtil.isIpv4(dev.getContactHost())) {
                    return dev.getContactHost();
                }
            }
            return Gb28181NetUtil.extractIpv4FromRtsp(channelRtspUrlOnly(ch));
        }

        /** 海康等同机仅一路码流：国标开播前暂停指向同一 IP 的 RTSP 线程。 */
        private void stopConflictingRtspWorkers(String cameraIp) {
            if (!Gb28181NetUtil.isIpv4(cameraIp)) {
                return;
            }
            for (Map.Entry<Long, Worker> e : workers.entrySet()) {
                Worker w = e.getValue();
                if (w == null || w.gb28181 || !w.isThreadAlive()) {
                    continue;
                }
                String url = w.rtspUrl;
                if (url != null && url.contains(cameraIp)) {
                    log.warn("摄像头 id={} 国标占用 {}，暂停同机 RTSP 通道 id={}（watchdog 在国标期间不再自动重启该路）",
                            primaryChannelId, cameraIp, e.getKey());
                    w.stopSafely();
                    subscribers.computeIfPresent(e.getKey(), (k, sub) -> {
                        if (sub.get() > 0) {
                            log.info("视频通道 id={} 仍有页面订阅，国标期间暂不自动拉 RTSP", e.getKey());
                        }
                        return sub;
                    });
                }
            }
        }

    }

    private static void waitMinReconnectGap(long lastReconnectAtMs, long minGapMs, long now) {
        if (lastReconnectAtMs <= 0) {
            return;
        }
        long elapsed = now - lastReconnectAtMs;
        if (elapsed < minGapMs) {
            sleepQuiet(minGapMs - elapsed);
        }
    }

    /** 先关闭旧进程再启动，全程仅一条拉流连接。 */
    private static FfmpegRtspJpegSource reopenFfmpeg(
            FfmpegRtspJpegSource old,
            String inputUrl,
            IotProperties.Video cfg,
            String transport,
            long extraCooldownMs)
            throws IOException {
        if (old != null) {
            old.close();
        }
        if (extraCooldownMs > 0) {
            sleepQuiet(extraCooldownMs);
        }
        return FfmpegRtspJpegSource.start(inputUrl, cfg, transport);
    }

    private static long reconnectCooldownMs(
            int streak, String stderr, long sessionDurationMs, long minReconnectMs) {
        long gap = minReconnectMs;
        if (FfmpegRtspJpegSource.isConnectionReset(stderr)) {
            gap = Math.max(gap, 10_000L + (long) Math.min(streak, 5) * 2000L);
        } else if (sessionDurationMs > 0 && sessionDurationMs < 5000) {
            gap = Math.max(gap, 6000L);
        }
        return gap;
    }

    /** 本机 ZLM RTSP（127.0.0.1）应用 UDP，TCP 需本地 listen 易触发 Permission denied。 */
    private static boolean isLocalZlmRtsp(String rtspUrl) {
        if (!StringUtils.hasText(rtspUrl)) {
            return false;
        }
        String u = rtspUrl.trim().toLowerCase(Locale.ROOT);
        return u.contains("://127.0.0.1") || u.contains("://localhost");
    }

    private static String videoDecoderMode(IotProperties.Video cfg) {
        String d = cfg.getDecoder();
        if (d == null || d.trim().isEmpty()) {
            return "ffmpeg";
        }
        return d.trim().toLowerCase(Locale.ROOT);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
