package cn.chyuan.ai.domain.mqttkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQTT 会话内核测试（工单 1074-1081 EQ1-EQ8，emqx 思想）。
 * CONNECT 会话 takeover/QoS 出站状态机/inflight 窗口/订阅通配/retained/遗嘱/keepalive/端口组合管线。
 */
class MqttKernelTest {

    @Test
    void connectSessions() {
        MqttPort port = MqttPort.inMemory(4, 100, 0);
        assertEquals("CONNECTED", port.connect("c1", true, null, null, 0, 30));
        assertTrue(port.isOnline("c1"));
        String takeover = port.connect("c1", false, null, null, 0, 30);
        assertTrue(takeover.startsWith("TAKEOVER:"), "同 clientId 再接连管顶替旧连接");
        assertTrue(port.isOnline("c1"), "接管后新连接在线不并存");
        assertEquals("DISCONNECTED", port.disconnect("c1"));
        assertFalse(port.isOnline("c1"));
        assertThrows(IllegalStateException.class, () -> port.disconnect("c1"), "重复断开拒绝");
        assertThrows(IllegalArgumentException.class, () -> port.connect(" ", true, null, null, 0, 1));
        assertEquals("CONNECTED", port.connect("c1", false, null, null, 0, 30), "非 cleanStart 重连沿用会话");
    }

    @Test
    void qosOutboundStateMachines() {
        QosFlows flows = new QosFlows();
        assertEquals(QosFlows.Phase.AWAIT_PUBACK, flows.publishQos1(1));
        assertEquals(QosFlows.Phase.COMPLETE, flows.puback(1));
        assertFalse(flows.inFlight(1));

        assertEquals(QosFlows.Phase.AWAIT_PUBREC, flows.publishQos2(2));
        assertEquals(QosFlows.Phase.AWAIT_PUBCOMP, flows.pubrec(2));
        assertEquals(QosFlows.Phase.COMPLETE, flows.pubcomp(2));
        assertFalse(flows.inFlight(2));

        assertThrows(IllegalArgumentException.class, () -> flows.puback(99), "未发起报文 ack 拒绝");
        assertEquals(QosFlows.Phase.AWAIT_PUBREC, flows.publishQos2(4));
        assertThrows(IllegalStateException.class, () -> flows.pubcomp(4), "错序 ack 跳段拒绝");
        assertEquals(QosFlows.Phase.AWAIT_PUBACK, flows.publishQos1(5));
        assertThrows(IllegalStateException.class, () -> flows.pubrec(5), "QoS1 流程收 PUBREC 拒绝");
        assertThrows(IllegalStateException.class, () -> flows.publishQos2(5), "未完结重复发起拒绝");
        assertThrows(IllegalArgumentException.class, () -> new QosFlows().phase(6));
    }

    @Test
    void inflightWindow() {
        InflightWindows window = new InflightWindows(2);
        assertTrue(window.offer(1, 0, 100));
        assertTrue(window.offer(2, 0, 100));
        assertFalse(window.offer(3, 0, 100), "窗口满暂停新发");
        window.release(1);
        assertTrue(window.offer(3, 50, 100));
        window.holdRelay(2);
        assertEquals(2, window.inflight(), "PUBREL 后不清窗");
        window.release(3);
        assertTrue(window.dueResend(100).isEmpty(), "到期端点未超时");
        assertEquals(List.of(2), window.dueResend(101), "超时重传");
        assertEquals(1, window.retries(2));
        assertTrue(window.dueResend(301).isEmpty(), "退避倍增后未到期");
        assertEquals(List.of(2), window.dueResend(302));
        assertThrows(IllegalArgumentException.class, () -> window.release(99));
        assertThrows(IllegalArgumentException.class, () -> new InflightWindows(0));
    }

    @Test
    void subscriptionMatching() {
        assertTrue(Subscriptions.matches("a/b/c", "a/+/c"));
        assertFalse(Subscriptions.matches("a/b", "a/+/c"));
        assertTrue(Subscriptions.matches("a", "a/#"));
        assertTrue(Subscriptions.matches("a/b/c", "a/#"));
        assertFalse(Subscriptions.matches("ab", "a/#"));
        assertTrue(Subscriptions.matches("x/b", "+/b"));
        assertFalse(Subscriptions.matches("x/y/b", "+/b"));
        assertTrue(Subscriptions.matches("a/b", "a/b"));
        assertThrows(IllegalArgumentException.class, () -> Subscriptions.matches("a", "#/a"));

        Subscriptions subs = new Subscriptions();
        subs.subscribe("c1", "a/#", 1);
        subs.subscribe("c1", "a/b", 0);
        List<Subscriptions.Sub> route = subs.route("a/b");
        assertEquals(1, route.size(), "重叠订阅按客户端去重不重复投递");
        assertEquals(1, route.get(0).qos(), "去重保留最大 QoS");
        subs.subscribe("c2", "a/+", 2);
        assertEquals(2, subs.route("a/b").size());
        assertEquals(0, Subscriptions.deliveryQos(0, 2), "投递 QoS 取较小");
        assertEquals(1, Subscriptions.deliveryQos(2, 1));
        subs.unsubscribe("c1", "a/#");
        assertEquals(2, subs.route("a/b").size(), "c1 余 a/b 与 c2 a/+ 仍命中");
        assertThrows(IllegalArgumentException.class, () -> subs.unsubscribe("c1", "a/#"), "未订阅退订拒绝");
        assertThrows(IllegalArgumentException.class, () -> subs.subscribe("c1", "a/#/b", 1), "# 非末段拒绝");
        subs.clear("c1");
        assertEquals(0, subs.count("c1"));
    }

    @Test
    void retainedMessages() {
        RetainedStore store = new RetainedStore();
        assertTrue(store.retain("t/1", "hello", 1));
        assertTrue(store.retain("t/1", "world", 2), "同主题覆盖至多一条");
        assertEquals(1, store.size());
        assertEquals(List.of("t/1:1:world"), store.onSubscribe("t/+", 1), "新订阅即时投递且 QoS 取较小");
        assertTrue(store.retain("t/1", "", 1), "空载荷清除");
        assertEquals(0, store.size());
        assertTrue(store.onSubscribe("#", 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.retain(" ", "x", 0));
    }

    @Test
    void willMessages() {
        Wills wills = new Wills();
        wills.register("c1", "w/topic", "bye", 1);
        assertEquals(Optional.empty(), wills.publishDue("c1", Wills.Reason.NORMAL), "正常断开不投");
        assertTrue(wills.has("c1"));
        assertEquals(Optional.empty(), wills.publishDue("c1", Wills.Reason.TAKEOVER), "takeover 不触发");
        Wills.Will published = wills.publishDue("c1", Wills.Reason.ABNORMAL).orElseThrow();
        assertEquals("w/topic", published.topic());
        assertEquals("bye", published.payload());
        assertEquals(1, published.qos());
        assertFalse(wills.has("c1"), "投后清除");
        assertEquals(Optional.empty(), wills.publishDue("c1", Wills.Reason.ABNORMAL), "只投一次");
        assertThrows(IllegalArgumentException.class, () -> wills.register("c2", " ", "x", 0));
        assertThrows(IllegalArgumentException.class, () -> wills.register("c2", "t", "x", 3));
    }

    @Test
    void keepaliveCheck() {
        KeepAlives keepAlives = new KeepAlives();
        keepAlives.arm("c1", 10, 0);
        keepAlives.heartbeat("c1", 5000);
        assertTrue(keepAlives.expired(20000).isEmpty(), "1.5 倍窗口端点未判开");
        assertEquals(List.of("c1"), keepAlives.expired(20001), "超 1.5 倍未心跳判断开");
        keepAlives.arm("c2", 0, 0);
        assertTrue(keepAlives.expired(10_000_000).isEmpty(), "keepalive=0 免检");
        assertThrows(IllegalArgumentException.class, () -> keepAlives.heartbeat("nope", 1));
        assertThrows(IllegalArgumentException.class, () -> keepAlives.arm("c3", -1, 0));
    }

    @Test
    void mqttPortPipeline() {
        MqttPort port = MqttPort.inMemory(2, 100, 0);
        assertEquals("CONNECTED", port.connect("dev", true, "w/t", "last-words", 1, 10));
        port.retain("s/x", "retained-payload", 2);
        assertEquals(List.of("s/x:1:retained-payload"), port.subscribe("dev", "s/+", 1),
                "订阅即时投递 retained 且 QoS 取较小");

        int qos2 = port.publishOut("dev", 2);
        assertTrue(qos2 > 0);
        assertEquals("PUBREL", port.pubrec("dev", qos2));
        assertEquals("COMPLETE", port.pubcomp("dev", qos2));

        int first = port.publishOut("dev", 1);
        int second = port.publishOut("dev", 1);
        assertTrue(first > 0);
        assertTrue(second > 0);
        assertEquals(-1, port.publishOut("dev", 1), "窗口满暂停新发");
        assertTrue(port.publishOut("dev", 0) > 0, "QoS0 即发即忘不占窗口");
        assertEquals("COMPLETE", port.puback("dev", first));
        assertTrue(port.publishOut("dev", 1) > 0, "PUBACK 释放滑窗");

        assertEquals("CONNECTED", port.connect("sub", true, null, null, 0, 0));
        port.subscribe("sub", "s/#", 1);
        assertEquals(List.of("dev:1", "sub:1"), port.deliver("s/x", "m", 2), "两级订阅均命中");
        assertEquals(List.of("sub:1"), port.deliver("s/deep/1", "m", 2), "＋单层不匹配多层主题");
        assertTrue(port.deliver("x/y", "m", 1).isEmpty());

        assertTrue(port.keepaliveExpired(15000).isEmpty(), "keepalive 端点未判开");
        List<String> gone = port.keepaliveExpired(15001);
        assertEquals(1, gone.size());
        assertEquals("dev:w/t:last-words", gone.get(0), "判开联动遗嘱投递");
        assertFalse(port.isOnline("dev"));
        assertThrows(IllegalStateException.class, () -> port.publishOut("dev", 1), "离线出站拒绝");

        assertEquals("DISCONNECTED", port.disconnect("sub"));
        assertEquals(List.of("ns", "sid", "state"), port.sessionShape(), "socketkernel 会话形状只读联动");
        assertThrows(IllegalArgumentException.class, () -> port.publishOut("sub", 9), "QoS 越界拒绝");
    }
}
