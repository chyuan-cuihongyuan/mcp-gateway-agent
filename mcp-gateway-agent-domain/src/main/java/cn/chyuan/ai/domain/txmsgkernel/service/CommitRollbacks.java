package cn.chyuan.ai.domain.txmsgkernel.service;

/**
 * 提交回滚（工单 1137 EX3，rocketmq 思想）。
 * commit 转 COMMITTED 可投递/rollback 转 ROLLED_BACK/重复 commit 幂等/终态不可逆。
 */
public final class CommitRollbacks {

    private final HalfMessages halves;
    private final java.util.List<String> committedLog = new java.util.ArrayList<>();

    public CommitRollbacks(HalfMessages halves) {
        this.halves = halves;
    }

    /** 提交：HALF→COMMITTED；已 COMMITTED 幂等；ROLLED_BACK 终态拒绝 */
    public void commit(String msgId) {
        HalfMessages.TxMessage message = halves.byMsgId(msgId);
        switch (message.state()) {
            case HALF -> {
                message.transition(HalfMessages.State.COMMITTED);
                committedLog.add(msgId);
            }
            case COMMITTED -> {
                // 幂等
            }
            case ROLLED_BACK -> throw new IllegalStateException("终态不可逆: " + msgId);
        }
    }

    /** 回滚：HALF/COMMITTED→ROLLED_BACK 中 COMMITTED 拒绝；已 ROLLED_BACK 幂等 */
    public void rollback(String msgId) {
        HalfMessages.TxMessage message = halves.byMsgId(msgId);
        switch (message.state()) {
            case HALF -> message.transition(HalfMessages.State.ROLLED_BACK);
            case COMMITTED -> throw new IllegalStateException("已提交消息不可回滚: " + msgId);
            case ROLLED_BACK -> {
                // 幂等
            }
        }
    }

    /** 可投递判定：仅 COMMITTED */
    public boolean deliverable(String msgId) {
        return halves.byMsgId(msgId).state() == HalfMessages.State.COMMITTED;
    }

    /** 提交序 msgId 日志（顺序消费投递序依据） */
    public java.util.List<String> committedLog() {
        return java.util.List.copyOf(committedLog);
    }
}
