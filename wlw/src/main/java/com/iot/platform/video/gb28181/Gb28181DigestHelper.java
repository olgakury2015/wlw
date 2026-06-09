package com.iot.platform.video.gb28181;

import javax.sip.header.AuthorizationHeader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

public final class Gb28181DigestHelper {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Gb28181DigestHelper() {
    }

    public static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte value : b) {
            sb.append(String.format(Locale.ROOT, "%02x", value));
        }
        return sb.toString();
    }

    /** 海康等设备常用 qop=auth */
    public static String wwwAuthenticateHeader(String realm, String nonce) {
        return "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\", algorithm=MD5, qop=\"auth\"";
    }

    public static boolean verifyAuthorization(
            String method,
            String uri,
            AuthorizationHeader auth,
            String realm,
            String nonce,
            String password) {
        if (auth == null || auth.getResponse() == null || password == null) {
            return false;
        }
        String username = auth.getUsername();
        String qop = auth.getQop();
        String nc = formatNonceCount(auth.getNonceCount(), qop);
        String cnonce = auth.getCNonce();
        String expected = computeResponse(method, uri, username, realm, nonce, password, qop, nc, cnonce);
        return expected.equalsIgnoreCase(auth.getResponse().trim());
    }

    /** JAIN-SIP 中 getNonceCount() 为 int；无 qop 时可为 null */
    private static String formatNonceCount(int nonceCount, String qop) {
        if (qop == null || qop.trim().isEmpty()) {
            return null;
        }
        int n = nonceCount > 0 ? nonceCount : 1;
        return String.format(Locale.ROOT, "%08x", n);
    }

    /** 平台作为 UAC 发 INVITE 时计算 Authorization response */
    public static String clientInviteResponse(
            String uri,
            String username,
            String realm,
            String nonce,
            String password,
            String qop) {
        String cnonce = newNonce().substring(0, 16);
        return computeResponse("INVITE", uri, username, realm, nonce, password, qop, "00000001", cnonce);
    }

    static String computeResponse(
            String method,
            String uri,
            String username,
            String realm,
            String nonce,
            String password,
            String qop,
            String nc,
            String cnonce) {
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        if (qop != null && !qop.trim().isEmpty()) {
            String q = qop.trim();
            String n = nc != null ? nc : "00000001";
            String c = cnonce != null ? cnonce : "";
            return md5(ha1 + ":" + nonce + ":" + n + ":" + c + ":" + q + ":" + ha2);
        }
        return md5(ha1 + ":" + nonce + ":" + ha2);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.ISO_8859_1));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
