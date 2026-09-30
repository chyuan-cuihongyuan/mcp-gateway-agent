package cn.chyuan.ai.domain.txmsgkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务回查（工单 1138 EX4，rocketmq 思想）。
 * 回查队列轮询 UNKNOWN（HALF）/回查判定提交或回滚生效/回查次数上限判回滚（MAX_CHECKS=16）。
 */
public final class TransactionCheckbacks {

    /** 回查次数上限（rocketmq 默认 15 次上限的保守口径取 16 含首查） */
    public static final int MAX_CHECKS = 16;

    private final HalfMessages halves;
    private final CommitRollbacks finals;
    private final Map<String, Integer> checkCounts = new LinkedHashMap<>();

    public TransactionCheckbacks(HalfMessages halves, CommitRollbacks finals) {
        this.halves = halves;
        this.finals = finals;
    }

    /** 待回查名单：仍处 HALF 的业务键，按诞生序 */
    public List<String> pending() {
        List<String> pending = new ArrayList<>();
        for (HalfMessages.TxMessage message : halves.snapshot()) {
            if (message.state() == HalfMessages.State.HALF) {
                pending.add(message.bizKey());
            }
        }
        return List.copyOf(pending);
    }

    /** 回查：判定 commit/rollback 生效；次数达上限强制回滚 */
    public void checkback(String bizKey, String decision) {
        HalfMessages.TxMessage message = halves.byBizKey(bizKey);
        if (message.state() != HalfMessages.State.HALF) {
            throw new IllegalStateException("非半消息不可回查: " + bizKey);
        }
        int count = checkCounts.merge(bizKey, 1, Integer::sum);
        if (count >= MAX_CHECKS) {
            finals.rollback(message.msgId());
            return;
        }
        if ("commit".equals(decision)) {
            finals.commit(message.msgId());
        } else if ("rollback".equals(decision)) {
            finals.rollback(message.msgId());
        } else if ("unknown".equals(decision)) {
            // 不决断：留 HALF，仅累计回查次数
        } else {
            throw new IllegalArgumentException("未知回查判定: " + decision);
        }
    }

    public int checks(String bizKey) {
        return checkCounts.getOrDefault(bizKey, 0);
    }
}
