package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每密钥并发闸（工单 0056，Kong request-size/concurrency 防护口径）
 *
 * <p>内存态在途计数（keyId → 计数），tryAcquire/Release 严格配对（调用方 try/finally）；
 * 限值 0 = 不限（默认，与现状一致）。单实例口径——跨实例并发的权威口径归配额 Redis 桶（0019）。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConcurrencyGuardService {

    @Value("${governance.request.max-concurrent-per-key:0}")
    private int maxConcurrentPerKey;

    private final Map<Long, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /** 尝试占位：未配置限值或未超限返回 true */
    public boolean tryAcquire(GovernancePrincipal principal) {
        if (principal == null || principal.getVirtualKeyId() == null || maxConcurrentPerKey <= 0) {
            return true;
        }
        AtomicInteger counter = inFlight.computeIfAbsent(principal.getVirtualKeyId(), k -> new AtomicInteger());
        int now = counter.incrementAndGet();
        if (now > maxConcurrentPerKey) {
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    /** 释放占位（幂等下界 0） */
    public void release(GovernancePrincipal principal) {
        if (principal == null || principal.getVirtualKeyId() == null) {
            return;
        }
        AtomicInteger counter = inFlight.get(principal.getVirtualKeyId());
        if (counter != null) {
            int now = counter.decrementAndGet();
            if (now <= 0) {
                inFlight.remove(principal.getVirtualKeyId(), counter);
            }
        }
    }

    /** 当前在途（观测/测试用） */
    public int inFlightOf(Long virtualKeyId) {
        AtomicInteger counter = inFlight.get(virtualKeyId);
        return counter == null ? 0 : counter.get();
    }
}
