package cn.chyuan.ai.domain.txmsgkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 半消息（工单 1135 EX1，rocketmq 思想）。
 * prepare 落 HALF 不投递/唯一 msgId/主题队列定位/重复业务键拒绝。
 */
public final class HalfMessages {

    /** 消息终局状态：HALF 半消息/COMMITTED 可投递/ROLLED_BACK 已回滚 */
    public enum State { HALF, COMMITTED, ROLLED_BACK }

    /** 事务消息：业务键唯一定位，主题+队列投递坐标 */
    public static final class TxMessage {
        private final String msgId;
        private final String bizKey;
        private final String topic;
        private final int queueId;
        private final String tags;
        private final long bornTick;
        private State state = State.HALF;

        TxMessage(String msgId, String bizKey, String topic, int queueId, String tags, long bornTick) {
            this.msgId = msgId;
            this.bizKey = bizKey;
            this.topic = topic;
            this.queueId = queueId;
            this.tags = tags;
            this.bornTick = bornTick;
        }

        public String msgId() {
            return msgId;
        }

        public String bizKey() {
            return bizKey;
        }

        public String topic() {
            return topic;
        }

        public int queueId() {
            return queueId;
        }

        public String tags() {
            return tags;
        }

        public long bornTick() {
            return bornTick;
        }

        public State state() {
            return state;
        }

        void transition(State target) {
            this.state = target;
        }
    }

    private final Map<String, TxMessage> byBizKey = new LinkedHashMap<>();
    private final Map<String, TxMessage> byMsgId = new LinkedHashMap<>();
    private long idCounter;

    /** prepare：空业务键/空主题/负队列拒绝；重复业务键拒绝；落 HALF */
    public TxMessage prepare(String bizKey, String topic, int queueId, String tags, long bornTick) {
        if (bizKey == null || bizKey.isBlank() || topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("业务键与主题不能为空");
        }
        if (queueId < 0) {
            throw new IllegalArgumentException("队列号不能为负: " + queueId);
        }
        if (byBizKey.containsKey(bizKey)) {
            throw new IllegalArgumentException("重复业务键: " + bizKey);
        }
        TxMessage message = new TxMessage("msg-" + ++idCounter, bizKey, topic, queueId,
                tags == null ? "" : tags, bornTick);
        byBizKey.put(bizKey, message);
        byMsgId.put(message.msgId(), message);
        return message;
    }

    public TxMessage byBizKey(String bizKey) {
        TxMessage message = byBizKey.get(bizKey);
        if (message == null) {
            throw new IllegalArgumentException("未知业务键: " + bizKey);
        }
        return message;
    }

    public TxMessage byMsgId(String msgId) {
        TxMessage message = byMsgId.get(msgId);
        if (message == null) {
            throw new IllegalArgumentException("未知消息: " + msgId);
        }
        return message;
    }

    /** 诞生序只读快照 */
    public java.util.List<TxMessage> snapshot() {
        return java.util.List.copyOf(byBizKey.values());
    }
}
