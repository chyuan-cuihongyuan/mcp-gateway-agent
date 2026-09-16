package cn.chyuan.ai.domain.msgkernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/**
 * 至少一次投递的幂等去重（工单 0447 BB5）。
 * 消息指纹（producer×序号 SHA-256）+ 窗口 TTL 过期淘汰（时钟端口注入）
 * + 重复投递拦截计数 + 至少一次语义口径（重放重复全拦、副作用只一次）。
 */
public class IdempotentDedup {

    /** 时钟端口 */
    public interface Clock {

        long nowMs();
    }

    /** 判定结果 */
    public record Decision(boolean duplicate, String fingerprint, long suppressedTotal) {
    }

    private final long windowMs;
    private final Clock clock;
    private final Map<String, Long> seenAt = new HashMap<>();
    private long suppressed;

    public IdempotentDedup(long windowMs, Clock clock) {
        if (windowMs < 1) {
            throw new IllegalArgumentException("窗口 TTL 至少 1ms");
        }
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** 消息指纹：producer×序号 SHA-256 */
    public static String fingerprint(String producerId, long sequence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((producerId + "#" + sequence).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 判定是否重复；顺手淘汰窗口外指纹 */
    public synchronized Decision check(String producerId, long sequence) {
        evict();
        String fingerprint = fingerprint(producerId, sequence);
        if (seenAt.containsKey(fingerprint)) {
            suppressed++;
            return new Decision(true, fingerprint, suppressed);
        }
        seenAt.put(fingerprint, clock.nowMs());
        return new Decision(false, fingerprint, suppressed);
    }

    public synchronized long suppressedTotal() {
        return suppressed;
    }

    public synchronized int tracked() {
        return seenAt.size();
    }

    private void evict() {
        long now = clock.nowMs();
        Iterator<Map.Entry<String, Long>> iterator = seenAt.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() > windowMs) {
                iterator.remove();
            }
        }
    }
}
