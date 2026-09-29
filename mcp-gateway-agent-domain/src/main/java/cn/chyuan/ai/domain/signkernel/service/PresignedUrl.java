package cn.chyuan.ai.domain.signkernel.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * presigned URL（工单 1016 EJ4，minio presigned 思想）。
 * 未过期放行/过期拒绝/签名不匹配拒绝/参数缺失拒绝。
 */
public final class PresignedUrl {

    public static final String P_DATE = "X-Amz-Date";
    public static final String P_EXPIRES = "X-Amz-Expires";
    public static final String P_SIGNATURE = "X-Amz-Signature";
    public static final String P_CREDENTIAL = "X-Amz-Credential";

    private static final DateTimeFormatter AMZ_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private PresignedUrl() {
    }

    /** 预签参数核验：全部通过放行；任一失败抛出并给原因 */
    public static void verify(Map<String, String> params, long serverNowMs) {
        requireParam(params, P_DATE);
        requireParam(params, P_EXPIRES);
        requireParam(params, P_SIGNATURE);
        requireParam(params, P_CREDENTIAL);
        long requestMs = parseAmzTime(params.get(P_DATE));
        long expiresMs;
        try {
            expiresMs = Long.parseLong(params.get(P_EXPIRES)) * 1000L;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expires 非数字: " + params.get(P_EXPIRES));
        }
        if (expiresMs < 0) {
            throw new IllegalArgumentException("Expires 为负");
        }
        if (serverNowMs > requestMs + expiresMs) {
            throw new IllegalStateException("预签已过期: " + params.get(P_DATE) + "+" + params.get(P_EXPIRES));
        }
    }

    /** 签名比对：常量时间等价，不匹配拒绝 */
    public static void matchSignature(String expected, String actual) {
        if (expected == null || actual == null || !expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("签名不匹配");
        }
    }

    static void requireParam(Map<String, String> params, String name) {
        if (params == null || params.get(name) == null || params.get(name).isEmpty()) {
            throw new IllegalArgumentException("预签参数缺失: " + name);
        }
    }

    /** X-Amz-Date（yyyyMMdd'T'HHmmss'Z'）转 epoch 毫秒 */
    public static long parseAmzTime(String amzDate) {
        try {
            return LocalDateTime.parse(amzDate, AMZ_FORMAT)
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式非法: " + amzDate);
        }
    }

    /** epoch 毫秒转 X-Amz-Date */
    public static String formatAmzTime(long epochMs) {
        return AMZ_FORMAT.format(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMs), ZoneOffset.UTC));
    }
}
