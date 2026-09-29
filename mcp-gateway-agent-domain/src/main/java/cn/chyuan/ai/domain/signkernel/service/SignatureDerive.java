package cn.chyuan.ai.domain.signkernel.service;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 签名派生（工单 1015 EJ3，minio SigV4 思想）。
 * HMAC-SHA256 链 date→region→service→sign/十六进制落定/secret 缺失拒绝。
 */
public final class SignatureDerive {

    private SignatureDerive() {
    }

    /** HMAC-SHA256 */
    public static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 派生签名：kDate→kRegion→kService→kSigning→hex(signature) */
    public static String derive(String secretKey, String date, String region,
                                String service, String stringToSign) {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("secret 缺失");
        }
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        return HexFormat.of().formatHex(hmac(kSigning, stringToSign));
    }

    /** 十六进制落定（小写） */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
