package cn.chyuan.ai.domain.txmsgkernel.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务消息端口（工单 1142 EX8，rocketmq 思想）。
 * prepare·commit·rollback·lookup 入口统一编排：半消息·本地事务判定·终局·
 * 回查·顺序队列锁·延迟等级·tag 过滤组合管线/msgkernel WAL 事件形状只读联动
 * （形状键与 WriteAheadLog.Entry 字段对齐，不 import msgkernel）/
 * txmsg-kernel.enabled 默认关（开启才改变行为）。
 */
public interface TxMsgPort {

    /** prepare 半消息：返回 msgId（EX1） */
    String prepare(String bizKey, String topic, int queueId, String tags);

    /** 本地事务执行：OK/FAIL/UNKNOWN（EX2） */
    void executeLocal(String bizKey, String outcome);

    /** 注入下一次本地事务执行异常（EX2） */
    void failNextLocal(String bizKey);

    /** lookup 状态名（EX3） */
    String lookup(String bizKey);

    void commit(String msgId);

    void rollback(String msgId);

    /** 可投递判定（EX3） */
    boolean deliverable(String msgId);

    /** 待回查业务键名单（EX4） */
    List<String> pendingCheckbacks();

    /** 回查判定（EX4） */
    void checkback(String bizKey, String decision);

    int checkbacks(String bizKey);

    /** 队列锁（EX5） */
    boolean tryLockQueue(int queueId, String consumer, long ttlTicks);

    void renewQueueLock(int queueId, String consumer, long ttlTicks);

    void releaseQueueLock(int queueId, String consumer);

    /** 顺序消费：同队列 COMMITTED 消息按提交序返回 bizKey（EX5） */
    List<String> consume(int queueId);

    /** 延迟投递时刻（EX6） */
    long deliverAt(int level, long bornTick);

    /** tag 过滤判定（EX7） */
    TagFilters.Decision filter(String subscription, String tags);

    /** 虚拟时钟（EX4/EX5 用） */
    void setTick(long tick);

    long tick();

    /** msgkernel WAL Entry 形状只读联动（offset/topic/payload/appendedAtMs） */
    List<String> messageShape();

    static TxMsgPort inMemory() {
        return new TxMessageHub();
    }
}
