package cn.chyuan.ai.domain.txmsgkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事务消息内核测试（工单 1135-1142 EX1-EX8，rocketmq 思想）。
 * 半消息/本地事务判定/提交回滚/事务回查/顺序队列锁/延迟等级/tag 过滤/端口组合管线。
 */
class TxMsgKernelTest {

    @Test
    void halfMessage() {
        HalfMessages halves = new HalfMessages();
        HalfMessages.TxMessage first = halves.prepare("order-1", "order_events", 3, "created", 5);
        assertEquals("HALF", first.state().name(), "prepare 落 HALF");
        assertEquals(5, first.bornTick());
        assertEquals(3, first.queueId(), "主题队列定位");
        assertTrue(first.msgId().startsWith("msg-"), "唯一 msgId");
        assertThrows(IllegalArgumentException.class, () -> halves.prepare("order-1", "t", 0, null, 6), "重复业务键拒绝");
        assertThrows(IllegalArgumentException.class, () -> halves.prepare(" ", "t", 0, null, 6), "空业务键拒绝");
        assertThrows(IllegalArgumentException.class, () -> halves.prepare("k", " ", 0, null, 6), "空主题拒绝");
        assertThrows(IllegalArgumentException.class, () -> halves.prepare("k2", "t", -1, null, 6), "负队列拒绝");
    }

    @Test
    void localTransaction() {
        assertEquals("commit", LocalTxExecutions.actionOf(LocalTxExecutions.Outcome.OK));
        assertEquals("rollback", LocalTxExecutions.actionOf(LocalTxExecutions.Outcome.FAIL));
        assertEquals("checkback", LocalTxExecutions.actionOf(LocalTxExecutions.Outcome.UNKNOWN));

        TxMsgPort port = TxMsgPort.inMemory();
        port.prepare("ok-1", "t", 0, null);
        port.executeLocal("ok-1", "OK");
        assertEquals("COMMITTED", port.lookup("ok-1"), "OK 提交");
        port.prepare("fail-1", "t", 0, null);
        port.executeLocal("fail-1", "FAIL");
        assertEquals("ROLLED_BACK", port.lookup("fail-1"), "FAIL 回滚");
        port.prepare("unknown-1", "t", 0, null);
        port.executeLocal("unknown-1", "UNKNOWN");
        assertEquals("HALF", port.lookup("unknown-1"), "UNKNOWN 留半消息待回查");
        port.prepare("boom-1", "t", 0, null);
        port.failNextLocal("boom-1");
        port.executeLocal("boom-1", "OK");
        assertEquals("ROLLED_BACK", port.lookup("boom-1"), "执行异常按回滚");
        assertThrows(IllegalArgumentException.class, () -> port.executeLocal("ok-1", "MAYBE"), "未知结论拒绝");
    }

    @Test
    void commitRollback() {
        TxMsgPort port = TxMsgPort.inMemory();
        String msgId = port.prepare("biz-1", "t", 0, null);
        assertFalse(port.deliverable(msgId), "HALF 不投递");
        port.commit(msgId);
        assertTrue(port.deliverable(msgId), "COMMITTED 可投递");
        port.commit(msgId);
        assertTrue(port.deliverable(msgId), "重复 commit 幂等");
        assertThrows(IllegalStateException.class, () -> port.rollback(msgId), "已提交不可回滚（终态不可逆）");
        assertThrows(IllegalArgumentException.class, () -> port.rollback("nope"), "未知消息拒绝");

        TxMsgPort rolled = TxMsgPort.inMemory();
        String id = rolled.prepare("biz-2", "t", 0, null);
        rolled.rollback(id);
        assertEquals("ROLLED_BACK", rolled.lookup("biz-2"));
        rolled.rollback(id);
        assertThrows(IllegalStateException.class, () -> rolled.commit(id), "回滚后提交拒绝");
    }

    @Test
    void transactionCheckback() {
        TxMsgPort port = TxMsgPort.inMemory();
        port.prepare("ck-1", "t", 0, null);
        port.executeLocal("ck-1", "UNKNOWN");
        assertEquals(List.of("ck-1"), port.pendingCheckbacks(), "UNKNOWN 入回查名单");
        port.checkback("ck-1", "commit");
        assertEquals("COMMITTED", port.lookup("ck-1"), "回查判定提交生效");
        assertEquals(1, port.checkbacks("ck-1"));
        assertTrue(port.pendingCheckbacks().isEmpty());
        assertThrows(IllegalStateException.class, () -> port.checkback("ck-1", "commit"), "非半消息不可回查");
        assertThrows(IllegalArgumentException.class, () -> port.checkback("ck-2", "commit"), "未知业务键拒绝");

        TxMsgPort exhausted = TxMsgPort.inMemory();
        exhausted.prepare("ck-3", "t", 0, null);
        for (int i = 0; i < TransactionCheckbacks.MAX_CHECKS; i++) {
            exhausted.checkback("ck-3", "unknown");
        }
        assertEquals("ROLLED_BACK", exhausted.lookup("ck-3"), "回查次数上限判回滚");
        assertEquals(TransactionCheckbacks.MAX_CHECKS, exhausted.checkbacks("ck-3"));
    }

    @Test
    void orderlyConsume() {
        OrderlyConsume locks = new OrderlyConsume();
        assertTrue(locks.tryLock(1, "c1", 0, 10));
        assertFalse(locks.tryLock(1, "c2", 5, 10), "他人未过期持有互斥等待");
        assertTrue(locks.tryLock(1, "c1", 8, 10), "自己持有即续期");
        assertEquals(18, locks.lockOf(1).expireTick(), "续期延长到期");
        locks.release(1, "c1");
        assertTrue(locks.tryLock(1, "c2", 9, 10), "释放后接管");
        assertThrows(IllegalStateException.class, () -> locks.release(1, "c1"), "非持有者释放拒绝");
        assertTrue(locks.tryLock(1, "c3", 100, 10), "锁过期后接管");

        TxMsgPort port = TxMsgPort.inMemory();
        String m1 = port.prepare("o-1", "t", 7, null);
        String m2 = port.prepare("o-2", "t", 7, null);
        String m3 = port.prepare("o-3", "t", 8, null);
        port.commit(m2);
        port.commit(m1);
        port.commit(m3);
        assertEquals(List.of("o-2", "o-1"), port.consume(7), "同队列按提交序投递");
        assertEquals(List.of("o-3"), port.consume(8), "跨队列各自有序");
    }

    @Test
    void delayLevels() {
        assertEquals(1, DelayLevels.deliverAt(1, 0), "1 级 1s");
        assertEquals(5, DelayLevels.deliverAt(2, 0), "2 级 5s");
        assertEquals(3600, DelayLevels.deliverAt(13, 0), "13 级 1h");
        assertTrue(DelayLevels.due(2, 0, 5), "到期推进");
        assertFalse(DelayLevels.due(2, 0, 4), "到期前不投递");
        assertThrows(IllegalArgumentException.class, () -> DelayLevels.deliverAt(0, 0), "非法等级拒绝");
        assertThrows(IllegalArgumentException.class, () -> DelayLevels.deliverAt(19, 0), "超界等级拒绝");
    }

    @Test
    void tagFilter() {
        assertTrue(TagFilters.decide("*", "any").delivered(), "星号全投");
        assertTrue(TagFilters.decide(null, "any").delivered(), "空订阅全投");
        assertTrue(TagFilters.decide("created", "created").delivered());
        assertFalse(TagFilters.decide("created", "paid").delivered(), "不匹配丢弃");
        assertEquals("tag-miss", TagFilters.decide("created", "paid").reason(), "丢弃留痕");
        assertTrue(TagFilters.decide("created||paid", "paid").delivered(), "多 tag 之一命中即投");
        assertFalse(TagFilters.decide("created||paid", "refunded").delivered());
    }

    @Test
    void portPipeline() {
        TxMsgPort port = TxMsgPort.inMemory();
        String a = port.prepare("pipe-a", "orders", 1, "created");
        String b = port.prepare("pipe-b", "orders", 1, "paid");
        port.executeLocal("pipe-a", "OK");
        port.executeLocal("pipe-b", "UNKNOWN");
        assertEquals(List.of("pipe-a"), port.consume(1), "半消息未终局不入投递序");
        port.checkback("pipe-b", "commit");
        assertEquals(List.of("pipe-a", "pipe-b"), port.consume(1));
        assertEquals("COMMITTED", port.lookup("pipe-b"));
        assertTrue(port.deliverable(a));
        assertTrue(port.tryLockQueue(1, "c1", 10));
        assertEquals(2, DelayLevels.levelCount() > 0 ? 2 : 0, "延迟等级表可用");
        assertTrue(port.filter("created", "created").delivered());
        assertEquals(List.of("offset", "topic", "payload", "appendedAtMs"), port.messageShape(), "msgkernel WAL Entry 形状只读联动");
        assertThrows(IllegalArgumentException.class, () -> port.setTick(-1), "虚拟时钟不可回拨");
    }
}
