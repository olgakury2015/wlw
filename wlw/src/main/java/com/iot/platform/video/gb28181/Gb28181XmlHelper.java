package com.iot.platform.video.gb28181;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Gb28181XmlHelper {

    private static final Pattern CMD_TYPE = Pattern.compile("<CmdType>\\s*([^<]+?)\\s*</CmdType>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVICE_ID = Pattern.compile("<DeviceID>\\s*([^<]+?)\\s*</DeviceID>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SN = Pattern.compile("<SN>\\s*([^<]+?)\\s*</SN>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATALOG_DEVICE_ID =
            Pattern.compile("<DeviceID>\\s*([0-9]{20})\\s*</DeviceID>", Pattern.CASE_INSENSITIVE);

    private Gb28181XmlHelper() {
    }

    static String cmdType(String xml) {
        return matchGroup(CMD_TYPE, xml);
    }

    static String deviceId(String xml) {
        return matchGroup(DEVICE_ID, xml);
    }

    static String sn(String xml) {
        return matchGroup(SN, xml);
    }

    /** @param targetDeviceId 被查询设备编码（摄像机 20 位 ID） */
    static String catalogQuery(String targetDeviceId, int sn) {
        return "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n"
                + "<Query>\r\n"
                + "<CmdType>Catalog</CmdType>\r\n"
                + "<SN>" + sn + "</SN>\r\n"
                + "<DeviceID>" + targetDeviceId + "</DeviceID>\r\n"
                + "</Query>";
    }

    /** 从 Catalog 上报 XML 提取全部 20 位 DeviceID（含设备本体与视频通道）。 */
    static List<String> catalogDeviceIds(String xml) {
        if (xml == null || xml.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher m = CATALOG_DEVICE_ID.matcher(xml);
        while (m.find()) {
            ids.add(m.group(1).trim());
        }
        return new ArrayList<>(ids);
    }

    static String catalogResponseOk(String platformId, int sn) {
        return "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n"
                + "<Response>\r\n"
                + "<CmdType>Catalog</CmdType>\r\n"
                + "<SN>" + sn + "</SN>\r\n"
                + "<DeviceID>" + platformId + "</DeviceID>\r\n"
                + "<Result>OK</Result>\r\n"
                + "</Response>";
    }

    private static String matchGroup(Pattern p, String xml) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }
}
