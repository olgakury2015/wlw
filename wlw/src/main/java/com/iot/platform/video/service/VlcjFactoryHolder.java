package com.iot.platform.video.service;

import com.iot.platform.config.IotProperties;
import com.sun.jna.NativeLibrary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import uk.co.caprica.vlcj.player.MediaPlayerFactory;
import uk.co.caprica.vlcj.runtime.RuntimeUtil;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;

/**
 * 全局共享 libVLC 工厂；VLCJ 需本机 64 位 VLC，且 libvlc.dll / libvlccore.dll 可被 JNA 加载。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlcjFactoryHolder {

    private static final String[] WINDOWS_VLC_DIRS = {
            "C:\\Program Files\\VideoLAN\\VLC",
            "C:\\Program Files (x86)\\VideoLAN\\VLC"
    };

    private final IotProperties iotProperties;

    private volatile MediaPlayerFactory factory;
    private volatile String initError;
    private volatile File resolvedVlcDir;

    @PostConstruct
    void logVlcProbe() {
        File dir = resolveVlcInstallDir();
        if (dir != null) {
            log.info("检测到 VLC 安装目录: {}", dir.getAbsolutePath());
        } else if (StringUtils.hasText(iotProperties.getVideo().getVlcPath())) {
            log.warn("未在 vlc-path 中找到 libvlc.dll，请确认路径为 VLC 根目录（含 libvlc.dll）");
        } else {
            log.info("未检测到 VLC；使用 decoder=vlcj 时将回退 ffmpeg，或安装 VLC 并配置 iot.video.vlc-path");
        }
    }

    public synchronized MediaPlayerFactory requireFactory() {
        if (factory != null) {
            return factory;
        }
        if (initError != null) {
            throw new IllegalStateException(initError);
        }
        File vlcDir = resolveVlcInstallDir();
        if (vlcDir == null) {
            initError = buildMissingVlcMessage();
            log.error(initError);
            throw new IllegalStateException(initError);
        }
        try {
            applyNativeLibraryPath(vlcDir);
            String[] args = new String[]{
                    "--intf=dummy",
                    "--no-audio",
                    "--quiet",
                    "--no-video-title-show"
            };
            factory = new MediaPlayerFactory(args);
            resolvedVlcDir = vlcDir;
            log.info("VLCJ 已加载 libVLC，目录: {}", vlcDir.getAbsolutePath());
            return factory;
        } catch (Throwable t) {
            initError = "libVLC 加载失败（目录 " + vlcDir.getAbsolutePath() + "）。"
                    + "请确认已安装 64 位 VLC，与 JDK 位数一致。"
                    + " 原因: " + rootMessage(t);
            log.error(initError, t);
            throw new IllegalStateException(initError, t);
        }
    }

    public boolean isReady() {
        if (factory != null) {
            return true;
        }
        return resolveVlcInstallDir() != null;
    }

    public String getInitError() {
        return initError;
    }

    public File getResolvedVlcDir() {
        return resolvedVlcDir;
    }

    private File resolveVlcInstallDir() {
        String configured = iotProperties.getVideo().getVlcPath();
        if (StringUtils.hasText(configured)) {
            File dir = new File(configured.trim());
            if (isValidVlcDir(dir)) {
                return dir;
            }
            log.warn("iot.video.vlc-path 无效（需含 libvlc.dll），将尝试默认安装路径: {}", dir.getAbsolutePath());
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            for (String path : WINDOWS_VLC_DIRS) {
                File dir = new File(path);
                if (isValidVlcDir(dir)) {
                    return dir;
                }
            }
        }
        return null;
    }

    private static boolean isValidVlcDir(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        return new File(dir, "libvlc.dll").isFile()
                && new File(dir, "libvlccore.dll").isFile();
    }

    private static void applyNativeLibraryPath(File vlcDir) {
        String libDir = vlcDir.getAbsolutePath();
        String libVlcName = RuntimeUtil.getLibVlcLibraryName();

        String jnaPath = System.getProperty("jna.library.path", "");
        if (!jnaPath.contains(libDir)) {
            String merged = jnaPath.isEmpty() ? libDir : jnaPath + File.pathSeparator + libDir;
            System.setProperty("jna.library.path", merged);
        }

        NativeLibrary.addSearchPath(libVlcName, libDir);
        NativeLibrary.addSearchPath("libvlc", libDir);
        NativeLibrary.addSearchPath("libvlccore", libDir);

        File plugins = new File(vlcDir, "plugins");
        if (plugins.isDirectory()) {
            System.setProperty("VLC_PLUGIN_PATH", plugins.getAbsolutePath());
        }
    }

    private String buildMissingVlcMessage() {
        return "未找到 VLC 原生库 libvlc.dll。"
                + " 请安装 64 位 VideoLAN VLC，并在 application.yml 设置"
                + " iot.video.vlc-path（例如 C:/Program Files/VideoLAN/VLC），"
                + " 或将 decoder 改为 ffmpeg。";
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    @PreDestroy
    void release() {
        if (factory != null) {
            try {
                factory.release();
            } catch (Exception e) {
                log.debug("释放 MediaPlayerFactory: {}", e.toString());
            }
            factory = null;
        }
    }
}
