package com.iot.platform.video.service;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import static org.bytedeco.opencv.global.opencv_imgcodecs.IMWRITE_JPEG_QUALITY;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imencode;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_YUV2BGR_I420;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;

/**
 * JavaCV Frame → JPEG。海康 HEVC 常为 YUV 平面，需先转 BGR 再 imencode；失败时回退 Java2D。
 */
public final class OpenCvRtspJpegEncoder {

    private static final Logger log = LoggerFactory.getLogger(OpenCvRtspJpegEncoder.class);

    private static final OpenCVFrameConverter.ToMat TO_MAT = new OpenCVFrameConverter.ToMat();
    private static final Java2DFrameConverter JAVA2D = new Java2DFrameConverter();

    private OpenCvRtspJpegEncoder() {
    }

    public static byte[] encode(Frame frame, int maxWidth, double jpegQuality01) {
        if (frame == null || frame.image == null) {
            return null;
        }
        byte[] viaOpenCv = encodeViaOpenCv(frame, maxWidth, jpegQuality01);
        if (viaOpenCv != null && viaOpenCv.length >= 100) {
            return viaOpenCv;
        }
        byte[] viaJava2d = encodeViaJava2D(frame, maxWidth, jpegQuality01);
        if (viaJava2d == null && viaOpenCv == null) {
            log.trace("编码失败 channels={} planes={} {}x{}",
                    frame.imageChannels,
                    frame.image != null ? frame.image.length : 0,
                    frame.imageWidth,
                    frame.imageHeight);
        }
        return viaJava2d;
    }

    private static byte[] encodeViaOpenCv(Frame frame, int maxWidth, double jpegQuality01) {
        Mat raw = null;
        Mat bgrOwned = null;
        Mat scaled = null;
        try {
            raw = TO_MAT.convert(frame);
            if (raw == null || raw.empty()) {
                return null;
            }
            Mat bgr;
            if (isYuvPlanes(frame)) {
                bgrOwned = new Mat();
                cvtColor(raw, bgrOwned, COLOR_YUV2BGR_I420);
                bgr = bgrOwned;
            } else if (raw.channels() >= 3) {
                bgr = raw;
            } else {
                return null;
            }
            Mat encodeMat = bgr;
            if (maxWidth > 0 && bgr.cols() > maxWidth) {
                scaled = new Mat();
                int w = maxWidth;
                int h = Math.max(1, (int) Math.round(bgr.rows() * ((double) w / bgr.cols())));
                resize(bgr, scaled, new Size(w, h), 0, 0, INTER_LINEAR);
                encodeMat = scaled;
            }
            return imencodeToBytes(encodeMat, jpegQuality01);
        } catch (Exception e) {
            log.trace("OpenCV 路径: {}", e.toString());
            return null;
        } finally {
            if (scaled != null) {
                scaled.close();
            }
            if (bgrOwned != null) {
                bgrOwned.close();
            }
            if (raw != null) {
                raw.close();
            }
        }
    }

    private static byte[] imencodeToBytes(Mat encodeMat, double jpegQuality01) {
        BytePointer buf = null;
        IntPointer params = null;
        try {
            int q = (int) Math.round(Math.max(5, Math.min(100, jpegQuality01 * 100)));
            buf = new BytePointer();
            params = new IntPointer(IMWRITE_JPEG_QUALITY, q, 0);
            if (!imencode(".jpg", encodeMat, buf, params) || buf.limit() <= 0) {
                return null;
            }
            byte[] bytes = new byte[(int) buf.limit()];
            buf.get(bytes);
            return bytes;
        } finally {
            if (params != null) {
                params.close();
            }
            if (buf != null) {
                buf.close();
            }
        }
    }

    private static boolean isYuvPlanes(Frame frame) {
        return frame.image != null && frame.image.length >= 3;
    }

    private static byte[] encodeViaJava2D(Frame frame, int maxWidth, double jpegQuality01) {
        try {
            BufferedImage bi = JAVA2D.convert(frame);
            if (bi == null) {
                return null;
            }
            if (maxWidth > 0 && bi.getWidth() > maxWidth) {
                bi = scaleImage(bi, maxWidth);
            }
            return writeJpeg(bi, jpegQuality01);
        } catch (Exception e) {
            log.trace("Java2D 路径: {}", e.toString());
            return null;
        }
    }

    private static BufferedImage scaleImage(BufferedImage src, int maxWidth) {
        int w = maxWidth;
        int h = Math.max(1, src.getHeight() * maxWidth / src.getWidth());
        int type = src.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_3BYTE_BGR : src.getType();
        BufferedImage dst = new BufferedImage(w, h, type);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] writeJpeg(BufferedImage image, double quality01) throws java.io.IOException {
        float q = (float) Math.max(0.05, Math.min(1.0, quality01));
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            return baos.toByteArray();
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(q);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
