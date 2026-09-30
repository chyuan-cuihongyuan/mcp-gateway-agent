package cn.chyuan.ai.domain.oidckernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC 与摘要助手（ET 簇内部，JDK 内建实现）。
 * HMAC-SHA256 十六进制签名/PKCE S256 的 SHA-256 BASE64URL 摘要。
 */
final class Hmacs {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char[] B64URL = (
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_").toCharArray();

    private Hmacs() {
    }

    /** HMAC-SHA256 十六进制签名 */
    static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 不可用", exception);
        }
    }

    /** SHA-256 的 BASE64URL 编码（无填充，PKCE S256 challenge 形态） */
    static String sha256Base64Url(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return base64Url(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    static String base64Url(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < bytes.length; index += 3) {
            int first = bytes[index] & 0xFF;
            int second = index + 1 < bytes.length ? bytes[index + 1] & 0xFF : 0;
            int third = index + 2 < bytes.length ? bytes[index + 2] & 0xFF : 0;
            int triple = (first << 16) | (second << 8) | third;
            builder.append(B64URL[(triple >> 18) & 0x3F]);
            builder.append(B64URL[(triple >> 12) & 0x3F]);
            if (index + 1 < bytes.length) {
                builder.append(B64URL[(triple >> 6) & 0x3F]);
            }
            if (index + 2 < bytes.length) {
                builder.append(B64URL[triple & 0x3F]);
            }
        }
        return builder.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            builder.append(HEX[(item >> 4) & 0xF]);
            builder.append(HEX[item & 0xF]);
        }
        return builder.toString();
    }
}
