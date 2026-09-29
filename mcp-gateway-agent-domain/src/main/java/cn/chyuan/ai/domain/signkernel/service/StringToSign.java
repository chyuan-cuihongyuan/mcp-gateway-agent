package cn.chyuan.ai.domain.signkernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * string-to-sign（工单 1014 EJ2，minio SigV4 思想）。
 * 算法时间范围拼装/缺时间拒绝/规范请求哈希落定。
 */
public final class StringToSign {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private StringToSign() {
    }

    /** 规范请求 SHA-256 哈希落定（十六进制小写） */
    public static String hash(String canonicalRequest) {
        if (canonicalRequest == null || canonicalRequest.isEmpty()) {
            throw new IllegalArgumentException("规范请求为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** string-to-sign 拼装：算法\n时间\n范围\n规范请求哈希；缺时间/缺范围段拒绝 */
    public static String build(String timestamp, String scope, String canonicalRequest) {
        if (timestamp == null || timestamp.isEmpty()) {
            throw new IllegalArgumentException("签名为缺时间");
        }
        requireScope(scope);
        String hashed = hash(canonicalRequest);
        return ALGORITHM + "\n" + timestamp + "\n" + scope + "\n" + hashed;
    }

    /** 范围四段：date/region/service/terminal，缺段拒绝 */
    public static void requireScope(String scope) {
        if (scope == null || scope.isEmpty()) {
            throw new IllegalArgumentException("范围为空");
        }
        String[] parts = scope.split("/");
        if (parts.length != 4) {
            throw new IllegalArgumentException("范围须四段 date/region/service/terminal: " + scope);
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("范围段为空: " + scope);
            }
        }
    }
}
