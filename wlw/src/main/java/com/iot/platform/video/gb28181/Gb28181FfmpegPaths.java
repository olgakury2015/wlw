package com.iot.platform.video.gb28181;

import com.iot.platform.config.IotProperties;
import org.bytedeco.ffmpeg.ffmpeg;
import org.bytedeco.javacpp.Loader;

import java.io.File;

final class Gb28181FfmpegPaths {

    private Gb28181FfmpegPaths() {
    }

    static String resolveExecutable(IotProperties.Video cfg) {
        if (cfg.getFfmpegPath() != null && !cfg.getFfmpegPath().trim().isEmpty()) {
            File f = new File(cfg.getFfmpegPath().trim());
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        String fromVlc = ffmpegBesideVlc(cfg.getVlcPath());
        if (fromVlc != null) {
            return fromVlc;
        }
        try {
            return Loader.load(ffmpeg.class);
        } catch (Throwable t) {
            return "ffmpeg";
        }
    }

    private static String ffmpegBesideVlc(String vlcPath) {
        if (vlcPath == null || vlcPath.trim().isEmpty()) {
            return null;
        }
        File vlcDir = new File(vlcPath.trim());
        if (!vlcDir.isDirectory()) {
            return null;
        }
        String[] candidates = {"ffmpeg.exe", "ffmpeg"};
        for (String name : candidates) {
            File exe = new File(vlcDir, name);
            if (exe.isFile()) {
                return exe.getAbsolutePath();
            }
        }
        return null;
    }
}
