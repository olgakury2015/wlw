package com.iot.platform.util;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从遥测负载中查找 NMEA {@code $GNRMC} / {@code $GPRMC} 句子并解析为 WGS84 经纬度（度）。
 * 纬度、经度为 NMEA 的 ddmm.mmmmm / dddmm.mmmmm 格式。
 * <p>与接入协议无关：HTTP、TCP、MQTT 等只要进入同一套遥测缓存的 {@code payload} 结构即可被遍历解析。</p>
 */
public final class GnrmcLocationExtractor {

    private GnrmcLocationExtractor() {
    }

    public static final class Wgs84Point {
        private final double latitude;
        private final double longitude;

        public Wgs84Point(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }
    }

    /**
     * 深度优先遍历 payload（Map / List / 可解析 JSON 字符串），返回首个成功解析的 RMC 位置。
     */
    public static Optional<Wgs84Point> tryFindFromTelemetryPayload(Object root) {
        if (root == null) {
            return Optional.empty();
        }
        return walk(root);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Wgs84Point> walk(Object node) {
        Optional<Wgs84Point> fromStr = tryParseEmbeddedGnrmc(node);
        if (fromStr.isPresent()) {
            return fromStr;
        }
        if (node instanceof Map) {
            for (Object v : ((Map<?, ?>) node).values()) {
                Optional<Wgs84Point> r = walk(v);
                if (r.isPresent()) {
                    return r;
                }
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                Optional<Wgs84Point> r = walk(item);
                if (r.isPresent()) {
                    return r;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Wgs84Point> tryParseEmbeddedGnrmc(Object node) {
        if (!(node instanceof String)) {
            return Optional.empty();
        }
        return parseSentence((String) node);
    }

    static Optional<Wgs84Point> parseSentence(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        int idx = raw.indexOf("$GNRMC");
        if (idx < 0) {
            idx = raw.indexOf("$GPRMC");
        }
        if (idx < 0) {
            return Optional.empty();
        }
        int star = raw.indexOf('*', idx);
        String sentence = star > idx ? raw.substring(idx, star) : raw.substring(idx).trim();
        return parseCommaSeparatedRmc(sentence);
    }

    private static Optional<Wgs84Point> parseCommaSeparatedRmc(String sentence) {
        String[] p = sentence.split(",", -1);
        if (p.length < 7) {
            return Optional.empty();
        }
        String latDm = safeTrim(p[3]);
        String ns = safeTrim(p[4]);
        String lonDm = safeTrim(p[5]);
        String ew = safeTrim(p[6]);
        if (latDm.isEmpty() || lonDm.isEmpty()) {
            return Optional.empty();
        }
        try {
            double lat = nmeaLatToDecimal(latDm, ns);
            double lon = nmeaLonToDecimal(lonDm, ew);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                return Optional.empty();
            }
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                return Optional.empty();
            }
            return Optional.of(new Wgs84Point(lat, lon));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    /** NMEA latitude: DDMM.mmmmm + N/S */
    private static double nmeaLatToDecimal(String dm, String ns) {
        double v = Double.parseDouble(dm.replace(',', '.'));
        int deg = (int) (v / 100);
        double minutes = v - deg * 100;
        double dec = deg + minutes / 60.0;
        if (ns != null && ns.equalsIgnoreCase("S")) {
            dec = -dec;
        }
        return dec;
    }

    /** NMEA longitude: DDDMM.mmmmm + E/W */
    private static double nmeaLonToDecimal(String dm, String ew) {
        double v = Double.parseDouble(dm.replace(',', '.'));
        int deg = (int) (v / 100);
        double minutes = v - deg * 100;
        double dec = deg + minutes / 60.0;
        if (ew != null && ew.equalsIgnoreCase("W")) {
            dec = -dec;
        }
        return dec;
    }
}
