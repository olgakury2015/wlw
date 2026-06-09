package com.iot.platform.management.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 国内主要城市/地区近似中心点（WGS84），不依赖外网；用于安装位置快速落点。
 */
public final class DeviceLocationPresets {

    private DeviceLocationPresets() {
    }

    public static final class Preset {
        private final String code;
        private final String label;
        private final double lat;
        private final double lon;

        public Preset(String code, String label, double lat, double lon) {
            this.code = code;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }

        public String getCode() {
            return code;
        }

        public String getLabel() {
            return label;
        }

        public double getLat() {
            return lat;
        }

        public double getLon() {
            return lon;
        }
    }

    private static final List<Preset> ALL = Collections.unmodifiableList(Arrays.asList(
            new Preset("beijing", "北京市（城区近似）", 39.9042, 116.4074),
            new Preset("shanghai", "上海市（城区近似）", 31.2304, 121.4737),
            new Preset("guangzhou", "广州市（城区近似）", 23.1291, 113.2644),
            new Preset("shenzhen", "深圳市（城区近似）", 22.5431, 114.0579),
            new Preset("hangzhou", "杭州市（城区近似）", 30.2741, 120.1551),
            new Preset("nanjing", "南京市（城区近似）", 32.0603, 118.7969),
            new Preset("chengdu", "成都市（城区近似）", 30.5728, 104.0668),
            new Preset("wuhan", "武汉市（城区近似）", 30.5928, 114.3055),
            new Preset("xian", "西安市（城区近似）", 34.3416, 108.9398),
            new Preset("chongqing", "重庆市（城区近似）", 29.5630, 106.5516),
            new Preset("tianjin", "天津市（城区近似）", 39.3434, 117.3616),
            new Preset("suzhou", "苏州市（城区近似）", 31.2989, 120.5853),
            new Preset("zhengzhou", "郑州市（城区近似）", 34.7466, 113.6254),
            new Preset("changsha", "长沙市（城区近似）", 28.2280, 112.9388),
            new Preset("hefei", "合肥市（城区近似）", 31.8206, 117.2272),
            new Preset("fuzhou", "福州市（城区近似）", 26.0745, 119.2965),
            new Preset("kunming", "昆明市（城区近似）", 25.0389, 102.7183),
            new Preset("jinan", "济南市（城区近似）", 36.6512, 117.1201),
            new Preset("dalian", "大连市（城区近似）", 38.9140, 121.6147),
            new Preset("harbin", "哈尔滨市（城区近似）", 45.8038, 126.5350),
            new Preset("changchun", "长春市（城区近似）", 43.8171, 125.3235),
            new Preset("nanchang", "南昌市（城区近似）", 28.6820, 115.8579),
            new Preset("nanning", "南宁市（城区近似）", 22.8170, 108.3669),
            new Preset("urumqi", "乌鲁木齐市（城区近似）", 43.8256, 87.6168),
            new Preset("lhasa", "拉萨市（城区近似）", 29.6500, 91.1000),
            new Preset("taipei", "台北市（城区近似）", 25.0330, 121.5654),
            new Preset("hongkong", "香港（城区近似）", 22.3193, 114.1694),
            new Preset("macau", "澳门（城区近似）", 22.1987, 113.5439),
            new Preset("xiamen", "厦门市（城区近似）", 24.4798, 118.0894),
            new Preset("qingdao", "青岛市（城区近似）", 36.0671, 120.3826),
            new Preset("ningbo", "宁波市（城区近似）", 29.8683, 121.5440)
    ));

    public static List<Preset> all() {
        return ALL;
    }

    public static Preset byCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (Preset p : ALL) {
            if (p.code.equals(c)) {
                return p;
            }
        }
        return null;
    }
}
