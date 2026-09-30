package cn.chyuan.ai.domain.txmsgkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 顺序消费队列锁（工单 1139 EX5，rocketmq 思想）。
 * 同队列按提交序投递/队列锁互斥后到等待/锁续期/锁过期或释放后接管续投。
 */
public final class OrderlyConsume {

    /** 队列锁：持有者 + 到期 tick */
    public record QueueLock(String holder, long expireTick) {
    }

    private final Map<Integer, QueueLock> locks = new LinkedHashMap<>();

    /** 尝试加锁：他人未过期持有返回 false（等待）；自己持有续期；无锁或已过期接管 */
    public boolean tryLock(int queueId, String consumer, long nowTick, long ttlTicks) {
        if (consumer == null || consumer.isBlank() || ttlTicks <= 0) {
            throw new IllegalArgumentException("消费者与 TTL 不合法");
        }
        QueueLock lock = locks.get(queueId);
        if (lock != null && lock.expireTick() > nowTick) {
            if (lock.holder().equals(consumer)) {
                locks.put(queueId, new QueueLock(consumer, nowTick + ttlTicks));
                return true;
            }
            return false;
        }
        locks.put(queueId, new QueueLock(consumer, nowTick + ttlTicks));
        return true;
    }

    /** 续期：仅持有者本人；无锁或他人持有拒绝 */
    public void renew(int queueId, String consumer, long nowTick, long ttlTicks) {
        QueueLock lock = locks.get(queueId);
        if (lock == null || !lock.holder().equals(consumer) || lock.expireTick() <= nowTick) {
            throw new IllegalStateException("非持有者不可续期: " + queueId);
        }
        locks.put(queueId, new QueueLock(consumer, lock.expireTick() + ttlTicks));
    }

    /** 释放：仅持有者本人；释放后他人可接管 */
    public void release(int queueId, String consumer) {
        QueueLock lock = locks.get(queueId);
        if (lock == null || !lock.holder().equals(consumer)) {
            throw new IllegalStateException("非持有者不可释放: " + queueId);
        }
        locks.remove(queueId);
    }

    public QueueLock lockOf(int queueId) {
        return locks.get(queueId);
    }
}
