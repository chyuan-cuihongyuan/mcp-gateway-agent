package cn.chyuan.ai.domain.objkernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * presigned URL（工单 1132 EW6，minio 思想）。
 * 签名含过期时刻与方法/过期拒绝/参数篡改拒绝/方法不匹配拒绝；纯计算无网络。
 */
public final class PresignedUrls {

    /** 预签票据：方法 + 桶 + key + 过期 tick + 签名 */
    public record Ticket(String method, String bucket, String key, long expireTick, String signature) {

        /** 票据串形状（minio presigned 查询串形态） */
        public String serialize() {
            return "X-Amz-Method=" + method + "&X-Amz-Bucket=" + bucket + "&X-Amz-Key=" + key
                    + "&X-Amz-Expires=" + expireTick + "&X-Amz-Signature=" + signature;
        }
    }

    private final String secret;

    public PresignedUrls(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("签名密钥不能为空");
        }
        this.secret = secret;
    }

    /** 签发：过期时刻必须在未来 */
    public Ticket sign(String method, String bucket, String key, long nowTick, long expireTick) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("方法不能为空");
        }
        if (expireTick <= nowTick) {
            throw new IllegalArgumentException("过期时刻必须晚于当前: " + expireTick);
        }
        return new Ticket(method, bucket, key, expireTick, hmac(method, bucket, key, expireTick));
    }

    /** 校验：签名不符（篡改）/过期/方法不匹配逐一拒绝；通过返回 null，否则抛 IAE */
    public void verify(Ticket ticket, String requestMethod, long nowTick) {
        String expected = hmac(ticket.method(), ticket.bucket(), ticket.key(), ticket.expireTick());
        if (!expected.equals(ticket.signature())) {
            throw new IllegalArgumentException("参数篡改签名不符: " + ticket.key());
        }
        if (nowTick >= ticket.expireTick()) {
            throw new IllegalArgumentException("票据已过期: " + ticket.expireTick());
        }
        if (!ticket.method().equals(requestMethod)) {
            throw new IllegalArgumentException("方法不匹配: 签发 " + ticket.method() + " 请求 " + requestMethod);
        }
    }

    private String hmac(String method, String bucket, String key, long expireTick) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = secret + "|" + method + "|" + bucket + "|" + key + "|" + expireTick;
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }
}
