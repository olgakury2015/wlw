package com.iot.platform.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小 ONVIF（SOAP）流程：GetCapabilities(Media) → GetProfiles → GetStreamUri(RTSP)。
 * 不同厂商返回 XML 略有差异，辅以正则兜底。
 */
@Slf4j
@Service
public class OnvifRtspResolverService {

    /** 常见 Media 服务路径（海康/大华等大小写、media_service / Media 均有） */
    private static final Pattern[] MEDIA_XADDR_PATTERNS = {
            Pattern.compile("(https?://[^<\\s\"']+/onvif/media_service[^<\\s\"']*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(https?://[^<\\s\"']+/onvif/Media[^<\\s\"']*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(https?://[^<\\s\"']+/onvif/media[^<\\s\"']*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<[^>]*:?XAddr[^>]*>\\s*(https?://[^<\\s]+/onvif/[^<\\s]*media[^<\\s]*)\\s*</", Pattern.CASE_INSENSITIVE),
    };
    private static final Pattern PROFILE_TOKEN = Pattern.compile(
            "<[^:>]*:?Profiles[^>]*\\btoken=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RTSP_URI = Pattern.compile(">\\s*(rtsp://[^<]+)\\s*<", Pattern.CASE_INSENSITIVE);

    private final SecureRandom random = new SecureRandom();

    /**
     * @param deviceServiceUrl 设备服务地址，如 http://ip/onvif/device_service
     * @return rtsp 主码流地址，失败时抛出 IllegalArgumentException
     */
    public String resolveRtspUrl(String deviceServiceUrl, String username, String password) {
        if (!StringUtils.hasText(deviceServiceUrl)) {
            throw new IllegalArgumentException("请填写 ONVIF 设备服务地址");
        }
        String deviceUrl = deviceServiceUrl.trim();
        String user = username != null ? username.trim() : "";
        String pass = password != null ? password : "";

        String capsXml = requestGetCapabilities(deviceUrl, user, pass);
        validateSoapResponse(capsXml, "GetCapabilities");
        if (capsXml.contains(":Fault") || capsXml.contains("Fault>")) {
            throw new IllegalArgumentException(parseFaultReason(capsXml));
        }
        String mediaXAddr = extractMediaXAddr(capsXml, deviceUrl);
        if (!StringUtils.hasText(mediaXAddr)) {
            throw new IllegalArgumentException(
                    "GetCapabilities 响应中未找到 Media 服务地址。请确认 ONVIF 地址可达、用户名密码正确，"
                            + "或直接在通道中填写 RTSP 地址（不依赖 ONVIF 解析）");
        }

        String profilesXml = postSoap(mediaXAddr, buildGetProfilesEnvelope(user, pass),
                "\"http://www.onvif.org/ver10/media/wsdl/GetProfiles\"");
        validateSoapResponse(profilesXml, "GetProfiles");
        if (profilesXml.contains(":Fault") || profilesXml.contains("Fault>")) {
            throw new IllegalArgumentException(parseFaultReason(profilesXml));
        }
        String token = extractFirstProfileToken(profilesXml);
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("GetProfiles 未解析到 ProfileToken");
        }

        String streamXml = postSoap(mediaXAddr, buildGetStreamUriEnvelope(user, pass, token),
                "\"http://www.onvif.org/ver10/media/wsdl/GetStreamUri\"");
        validateSoapResponse(streamXml, "GetStreamUri");
        if (streamXml.contains(":Fault") || streamXml.contains("Fault>")) {
            throw new IllegalArgumentException(parseFaultReason(streamXml));
        }
        Matcher m = RTSP_URI.matcher(streamXml);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 部分设备 Uri 无 rtsp 前缀空格差异
        int i = streamXml.indexOf("rtsp://");
        if (i >= 0) {
            int j = streamXml.indexOf('<', i);
            if (j > i) {
                return streamXml.substring(i, j).trim();
            }
        }
        throw new IllegalArgumentException("GetStreamUri 响应中未找到 RTSP 地址");
    }

    /**
     * 先 Media 类别，再 All；认证失败时尝试明文密码（部分国产 IPC 仅支持 PasswordText）。
     */
    private String requestGetCapabilities(String deviceUrl, String user, String pass) {
        String soapAction = "\"http://www.onvif.org/ver10/device/wsdl/GetCapabilities\"";
        String mediaCat = postSoap(deviceUrl, buildGetCapabilitiesEnvelope(user, pass, "Media"), soapAction);
        if (isAuthFault(mediaCat)) {
            mediaCat = postSoap(deviceUrl, buildGetCapabilitiesEnvelopePlaintext(user, pass, "Media"), soapAction);
        }
        if (!looksLikeHtml(mediaCat) && extractMediaXAddrFromXmlOnly(mediaCat) != null) {
            return mediaCat;
        }
        String allCat = postSoap(deviceUrl, buildGetCapabilitiesEnvelope(user, pass, "All"), soapAction);
        if (isAuthFault(allCat)) {
            allCat = postSoap(deviceUrl, buildGetCapabilitiesEnvelopePlaintext(user, pass, "All"), soapAction);
        }
        return allCat;
    }

    private static boolean isAuthFault(String xml) {
        if (xml == null) {
            return false;
        }
        String lower = xml.toLowerCase();
        return lower.contains("notauthorized") || lower.contains("unauthorized")
                || lower.contains("invalid username") || lower.contains("security")
                || (lower.contains("fault") && lower.contains("sender"));
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String t = body.trim();
        return t.regionMatches(true, 0, "<!DOCTYPE", 0, 9)
                || t.regionMatches(true, 0, "<html", 0, 5);
    }

    private static boolean looksLikeSoapXml(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String t = body.trim();
        return t.startsWith("<?xml") || t.contains(":Envelope") || t.contains("Envelope>");
    }

    private static void validateSoapResponse(String body, String step) {
        if (looksLikeHtml(body)) {
            throw new IllegalArgumentException(step
                    + " 返回了 HTML 页面（多为 ONVIF 地址错误、端口不对或用户名/密码错误），"
                    + "请核对设备服务 URL（如 http://IP:80/onvif/device_service）与账号密码");
        }
        if (!looksLikeSoapXml(body)) {
            throw new IllegalArgumentException(step + " 响应不是 SOAP/XML，请检查网络与 ONVIF 是否开启");
        }
    }

    private String extractMediaXAddr(String xml, String deviceServiceUrl) {
        String fromXml = extractMediaXAddrFromXmlOnly(xml);
        if (StringUtils.hasText(fromXml)) {
            return fromXml;
        }
        return deriveMediaUrlFromDeviceService(deviceServiceUrl);
    }

    private String extractMediaXAddrFromXmlOnly(String xml) {
        if (!StringUtils.hasText(xml) || looksLikeHtml(xml)) {
            return null;
        }
        for (Pattern p : MEDIA_XADDR_PATTERNS) {
            Matcher matcher = p.matcher(xml);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        if (!looksLikeSoapXml(xml)) {
            return null;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            List<String> xaddrs = collectTextByLocalName(doc.getDocumentElement(), "XAddr", new ArrayList<>());
            for (String x : xaddrs) {
                if (x != null && x.toLowerCase().contains("media")) {
                    return x.trim();
                }
            }
        } catch (Exception e) {
            log.debug("parse media xaddr DOM: {}", e.toString());
        }
        return null;
    }

    /** 由 device_service 推导常见 media_service（仅作 SOAP 已通但未解析出 XAddr 时的兜底） */
    private static String deriveMediaUrlFromDeviceService(String deviceServiceUrl) {
        if (!StringUtils.hasText(deviceServiceUrl)) {
            return null;
        }
        String u = deviceServiceUrl.trim();
        String lower = u.toLowerCase();
        String[] deviceSuffixes = {
                "/onvif/device_service", "/onvif/device", "/onvif/deviceservice"
        };
        for (String suf : deviceSuffixes) {
            if (lower.endsWith(suf)) {
                return u.substring(0, u.length() - suf.length()) + "/onvif/media_service";
            }
        }
        if (lower.contains("/onvif/")) {
            int i = lower.indexOf("/onvif/");
            return u.substring(0, i) + "/onvif/media_service";
        }
        return null;
    }

    private List<String> collectTextByLocalName(org.w3c.dom.Element el, String local, List<String> acc) {
        if (local.equals(el.getLocalName())) {
            String t = el.getTextContent();
            if (StringUtils.hasText(t)) {
                acc.add(t.trim());
            }
        }
        org.w3c.dom.NodeList ch = el.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            if (ch.item(i) instanceof org.w3c.dom.Element) {
                collectTextByLocalName((org.w3c.dom.Element) ch.item(i), local, acc);
            }
        }
        return acc;
    }

    private String extractFirstProfileToken(String xml) {
        if (looksLikeHtml(xml) || !looksLikeSoapXml(xml)) {
            return null;
        }
        Matcher m = PROFILE_TOKEN.matcher(xml);
        if (m.find()) {
            return m.group(1);
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = f.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return walkFirstProfilesToken(doc.getDocumentElement());
        } catch (Exception e) {
            log.debug("parse profile token: {}", e.toString());
        }
        return null;
    }

    private static String walkFirstProfilesToken(org.w3c.dom.Element el) {
        if ("Profiles".equals(el.getLocalName()) && el.hasAttribute("token")) {
            return el.getAttribute("token");
        }
        org.w3c.dom.NodeList ch = el.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            if (ch.item(i) instanceof org.w3c.dom.Element) {
                String t = walkFirstProfilesToken((org.w3c.dom.Element) ch.item(i));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private String parseFaultReason(String xml) {
        int i = xml.indexOf("Reason");
        if (i < 0) {
            return "ONVIF 调用失败";
        }
        int lt = xml.indexOf('>', i);
        int gt = xml.indexOf('<', lt + 1);
        if (lt > 0 && gt > lt) {
            return xml.substring(lt + 1, gt).trim();
        }
        return "ONVIF 调用失败";
    }

    private String postSoap(String endpoint, String envelope, String soapAction) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            if (StringUtils.hasText(soapAction)) {
                conn.setRequestProperty("SOAPAction", soapAction);
            }
            byte[] requestBody = envelope.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(requestBody.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IllegalArgumentException("HTTP " + code + " 无响应体，请检查 ONVIF 地址与端口");
            }
            byte[] responseBytes = readAll(in);
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            if (code >= 400 && looksLikeHtml(responseBody)) {
                throw new IllegalArgumentException("HTTP " + code
                        + "，设备返回 HTML（地址或认证可能不正确）");
            }
            return responseBody;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("请求失败：" + ex.getMessage(), ex);
        }
    }

    private byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) >= 0) {
            baos.write(b, 0, n);
        }
        return baos.toByteArray();
    }

    private String buildGetCapabilitiesEnvelope(String user, String pass, String category) {
        String sec = buildSecurityHeaderDigest(user, pass);
        String cat = StringUtils.hasText(category) ? category : "All";
        String body = "<tds:GetCapabilities xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">"
                + "<tds:Category>" + escapeXml(cat) + "</tds:Category></tds:GetCapabilities>";
        return wrapEnvelope(sec, body);
    }

    private String buildGetCapabilitiesEnvelopePlaintext(String user, String pass, String category) {
        String sec = buildSecurityHeaderPlaintext(user, pass);
        String cat = StringUtils.hasText(category) ? category : "All";
        String body = "<tds:GetCapabilities xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\">"
                + "<tds:Category>" + escapeXml(cat) + "</tds:Category></tds:GetCapabilities>";
        return wrapEnvelope(sec, body);
    }

    private String buildGetProfilesEnvelope(String user, String pass) {
        String sec = buildSecurityHeaderDigest(user, pass);
        String body = "<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>";
        return wrapEnvelope(sec, body);
    }

    private String buildGetStreamUriEnvelope(String user, String pass, String profileToken) {
        String sec = buildSecurityHeaderDigest(user, pass);
        String escapedToken = escapeXml(profileToken);
        String body = "<trt:GetStreamUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>"
                + "<trt:ProfileToken>" + escapedToken + "</trt:ProfileToken></trt:GetStreamUri>";
        return wrapEnvelope(sec, body);
    }

    private String wrapEnvelope(String securityHeader, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" "
                + "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\" "
                + "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" "
                + "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" "
                + "xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<s:Header>" + securityHeader + "</s:Header>"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
    }

    private String buildSecurityHeaderDigest(String user, String pass) {
        if (!StringUtils.hasText(user)) {
            return "";
        }
        try {
            byte[] nonceBytes = new byte[16];
            random.nextBytes(nonceBytes);
            String nonceB64 = Base64.getEncoder().encodeToString(nonceBytes);
            String created = ZonedDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(nonceBytes);
            md.update(created.getBytes(StandardCharsets.UTF_8));
            md.update(pass != null ? pass.getBytes(StandardCharsets.UTF_8) : new byte[0]);
            String digest = Base64.getEncoder().encodeToString(md.digest());
            return "<wsse:Security s:mustUnderstand=\"1\">"
                    + "<wsse:UsernameToken>"
                    + "<wsse:Username>" + escapeXml(user) + "</wsse:Username>"
                    + "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">"
                    + digest + "</wsse:Password>"
                    + "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                    + nonceB64 + "</wsse:Nonce>"
                    + "<wsu:Created>" + created + "</wsu:Created>"
                    + "</wsse:UsernameToken></wsse:Security>";
        } catch (Exception e) {
            throw new IllegalArgumentException("构造 WS-Security 失败：" + e.getMessage());
        }
    }

    /** 部分摄像头仅支持明文密码 */
    private static String buildSecurityHeaderPlaintext(String user, String pass) {
        if (!StringUtils.hasText(user)) {
            return "";
        }
        String p = pass != null ? pass : "";
        return "<wsse:Security s:mustUnderstand=\"1\">"
                + "<wsse:UsernameToken>"
                + "<wsse:Username>" + escapeXml(user) + "</wsse:Username>"
                + "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText\">"
                + escapeXml(p) + "</wsse:Password>"
                + "</wsse:UsernameToken></wsse:Security>";
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
