package cn.chyuan.ai.domain.txmsgkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 事务消息组合实现（工单 1142 EX8，rocketmq 思想）。
 * executeLocal 异常即回滚；UNKNOWN 留 HALF 待回查；顺序消费按提交序过滤队列。
 */
public final class TxMessageHub implements TxMsgPort {

    private final HalfMessages halves = new HalfMessages();
    private final LocalTxExecutions locals = new LocalTxExecutions();
    private final CommitRollbacks finals = new CommitRollbacks(halves);
    private final TransactionCheckbacks checkbacks = new TransactionCheckbacks(halves, finals);
    private final OrderlyConsume locks = new OrderlyConsume();
    private long tick;

    @Override
    public String prepare(String bizKey, String topic, int queueId, String tags) {
        return halves.prepare(bizKey, topic, queueId, tags, tick).msgId();
    }

    @Override
    public void executeLocal(String bizKey, String outcome) {
        HalfMessages.TxMessage message = halves.byBizKey(bizKey);
        LocalTxExecutions.Outcome declared = switch (outcome == null ? "" : outcome.toUpperCase()) {
            case "OK" -> LocalTxExecutions.Outcome.OK;
            case "FAIL" -> LocalTxExecutions.Outcome.FAIL;
            case "UNKNOWN" -> LocalTxExecutions.Outcome.UNKNOWN;
            default -> throw new IllegalArgumentException("未知执行结论: " + outcome);
        };
        LocalTxExecutions.Outcome resolved;
        try {
            resolved = locals.execute(bizKey, declared);
        } catch (RuntimeException e) {
            resolved = LocalTxExecutions.Outcome.FAIL;
        }
        switch (LocalTxExecutions.actionOf(resolved)) {
            case "commit" -> finals.commit(message.msgId());
            case "rollback" -> finals.rollback(message.msgId());
            default -> {
                // UNKNOWN 留 HALF 待回查
            }
        }
    }

    @Override
    public void failNextLocal(String bizKey) {
        halves.byBizKey(bizKey);
        locals.failNext(bizKey);
    }

    @Override
    public String lookup(String bizKey) {
        return halves.byBizKey(bizKey).state().name();
    }

    @Override
    public void commit(String msgId) {
        finals.commit(msgId);
    }

    @Override
    public void rollback(String msgId) {
        finals.rollback(msgId);
    }

    @Override
    public boolean deliverable(String msgId) {
        return finals.deliverable(msgId);
    }

    @Override
    public List<String> pendingCheckbacks() {
        return checkbacks.pending();
    }

    @Override
    public void checkback(String bizKey, String decision) {
        checkbacks.checkback(bizKey, decision);
    }

    @Override
    public int checkbacks(String bizKey) {
        return checkbacks.checks(bizKey);
    }

    @Override
    public boolean tryLockQueue(int queueId, String consumer, long ttlTicks) {
        return locks.tryLock(queueId, consumer, tick, ttlTicks);
    }

    @Override
    public void renewQueueLock(int queueId, String consumer, long ttlTicks) {
        locks.renew(queueId, consumer, tick, ttlTicks);
    }

    @Override
    public void releaseQueueLock(int queueId, String consumer) {
        locks.release(queueId, consumer);
    }

    @Override
    public List<String> consume(int queueId) {
        List<String> delivered = new ArrayList<>();
        for (String msgId : finals.committedLog()) {
            HalfMessages.TxMessage message = halves.byMsgId(msgId);
            if (message.queueId() == queueId) {
                delivered.add(message.bizKey());
            }
        }
        return List.copyOf(delivered);
    }

    @Override
    public long deliverAt(int level, long bornTick) {
        return DelayLevels.deliverAt(level, bornTick);
    }

    @Override
    public TagFilters.Decision filter(String subscription, String tags) {
        return TagFilters.decide(subscription, tags);
    }

    @Override
    public void setTick(long tick) {
        if (tick < this.tick) {
            throw new IllegalArgumentException("虚拟时钟不可回拨: " + tick);
        }
        this.tick = tick;
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public List<String> messageShape() {
        return List.of("offset", "topic", "payload", "appendedAtMs");
    }
}
