package cn.chyuan.ai.domain.socketkernel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实时会话内核测试（工单 0960-0967 ED1-ED8，socket.io 思想）。
 * 命名空间隔离/握手中间件/房间管理/广播排除发送者/ack 回执超时/volatile 丢弃/重连退避/端口状态机。
 */
class SocketKernelTest {

    @Test
    void namespaceRegistrationAndIsolation() {
        SocketNamespaces ns = new SocketNamespaces();
        ns.register("/chat");
        ns.register("/chat");
        assertEquals(1, ns.count(), "重复注册幂等");
        ns.require("/chat");
        assertThrows(IllegalArgumentException.class, () -> ns.require("/admin"), "未注册拒绝");
        assertThrows(IllegalArgumentException.class, () -> ns.register("chat"), "非 / 起头拒绝");
    }

    @Test
    void handshakeMiddlewareChain() {
        Handshakes handshakes = new Handshakes();
        handshakes.add(ctx -> {
            if (!"t0k3n".equals(ctx.header("auth"))) {
                ctx.reject("未授权");
            }
        });
        handshakes.add(ctx -> ctx.header("stage", "passed"));
        assertEquals(2, handshakes.size());
        Handshakes.Context ok = handshakes.handshake(Map.of("auth", "t0k3n"));
        assertFalse(ok.rejected(), "通过全部中间件");
        assertEquals("passed", ok.header("stage"), "短路后续不执行");
        Handshakes.Context bad = handshakes.handshake(Map.of("auth", "wrong"));
        assertTrue(bad.rejected());
        assertEquals("未授权", bad.reason(), "拒绝原因");
        assertNull(bad.header("stage"), "拒绝后短路");
    }

    @Test
    void roomJoinLeaveMembers() {
        Rooms rooms = new Rooms();
        rooms.join("/chat", "s1", "room1");
        rooms.join("/chat", "s1", "room1");
        rooms.join("/chat", "s2", "room1");
        rooms.join("/admin", "s3", "room1");
        assertEquals(List.of("s1", "s2"), rooms.members("/chat", "room1"), "重复 join 幂等");
        assertTrue(rooms.leave("/chat", "s2", "room1"));
        assertFalse(rooms.leave("/chat", "s2", "room1"), "重复 leave false");
        assertEquals(List.of("s1"), rooms.members("/chat", "room1"));
        assertEquals(List.of("s3"), rooms.targets("/admin", "room1", null), "跨 ns 房间互不可见");
    }

    @Test
    void broadcastExcludeSenderAndAll() {
        Rooms rooms = new Rooms();
        rooms.join("/chat", "s1", "r");
        rooms.join("/chat", "s2", "r");
        rooms.join("/chat", "s3", "r");
        assertEquals(List.of("s2", "s3"), rooms.targets("/chat", "r", "s1"), "排除发送者");
        rooms.join("/chat", "s4", "other");
        assertEquals(List.of("s1", "s2", "s3", "s4"), rooms.targets("/chat", null, null), "全员广播含所有房间");
        assertEquals(List.of("s1", "s2", "s3"), rooms.targets("/chat", null, "s4"), "全员广播排除发送者");
        assertEquals(List.of(), rooms.targets("/chat", "ghost", null), "空房间无目标");
    }

    @Test
    void ackRegisterResolveTimeout() {
        Acks acks = new Acks(2);
        String id = acks.register();
        assertThrows(IllegalStateException.class, () -> acks.take(id), "未回填等待中");
        acks.resolve(id, "ok");
        assertEquals("ok", acks.take(id));
        assertThrows(IllegalStateException.class, () -> acks.take(id), "重复收取拒绝");
        assertThrows(IllegalStateException.class, () -> acks.resolve("ghost", "x"), "未知 ack 拒绝");
        String pending = acks.register();
        acks.tick();
        assertEquals(1, acks.waiting(), "未到期待保留");
        acks.tick();
        assertEquals(0, acks.waiting(), "超时移除");
        assertThrows(IllegalStateException.class, () -> acks.take(pending), "超时后收取拒绝");
    }

    @Test
    void volatileSendDropsOffline() {
        SocketHub hub = new SocketHub();
        hub.namespace("/chat");
        hub.connect("/chat", "a", Map.of());
        SocketHub.Delivery online = hub.emit("a", "a", "ping", "self", false);
        assertNotNull(online, "在线照发");
        SocketHub.Delivery dropped = hub.emit("a", "ghost", "msg", "x", true);
        assertNull(dropped, "volatile 离线丢弃不排队");
        assertThrows(IllegalStateException.class, () -> hub.emit("a", "ghost", "msg", "x", false), "非 volatile 离线拒绝");
    }

    @Test
    void reconnectBackoffSequenceAndCap() {
        ReconnectBackoff backoff = new ReconnectBackoff(500L, 2.0, 4000L);
        assertThrows(IllegalStateException.class, backoff::nextDelayMillis, "未断线无退避");
        backoff.onDisconnect();
        assertEquals(500L, backoff.nextDelayMillis());
        backoff.onDisconnect();
        assertEquals(1000L, backoff.nextDelayMillis());
        backoff.onDisconnect();
        assertEquals(2000L, backoff.nextDelayMillis());
        backoff.onDisconnect();
        assertEquals(4000L, backoff.nextDelayMillis());
        backoff.onDisconnect();
        assertEquals(4000L, backoff.nextDelayMillis(), "上限封顶");
        backoff.reset();
        assertEquals(0, backoff.attempts(), "重连成功重置");
    }

    @Test
    void portStateMachineComposite() {
        SocketPort port = SocketPort.inMemory();
        port.namespace("/chat");
        port.middleware(ctx -> {
            if (!"good".equals(ctx.header("token"))) {
                ctx.reject("bad token");
            }
        });
        assertThrows(IllegalArgumentException.class, () -> port.connect("/nope", "s1", Map.of()), "未注册 ns 拒绝");
        assertThrows(IllegalStateException.class, () -> port.connect("/chat", "s1", Map.of("token", "bad")), "中间件拒绝");
        assertEquals(SocketHub.State.DISCONNECTED, port.state("s1"), "拒绝后断开态");

        port.connect("/chat", "s1", Map.of("token", "good"));
        port.connect("/chat", "s2", Map.of("token", "good"));
        assertEquals(SocketHub.State.CONNECTED, port.state("s1"));
        assertThrows(IllegalStateException.class, () -> port.connect("/chat", "s1", Map.of("token", "good")), "重复连接拒绝");

        port.join("/chat", "s1", "r");
        port.join("/chat", "s2", "r");
        List<SocketHub.Delivery> out = port.broadcast("/chat", "r", "s1", "hello", "hi");
        assertEquals(1, out.size());
        assertEquals("s2", out.get(0).sid);
        assertEquals("42[\"hello\",\"hi\"]", out.get(0).frame, "streamkernel 帧文本下行形态只读联动");

        String ackId = port.emitWithAck("s1", "s2", "needReply", "q");
        port.resolveAck(ackId, "ans");
        assertEquals("ans", port.takeAck(ackId));

        port.disconnect("s2");
        assertEquals(SocketHub.State.DISCONNECTED, port.state("s2"));
        assertTrue(port.broadcast("/chat", "r", "s1", "hello", "again").isEmpty(), "断开后房间自动退出");
        assertThrows(IllegalStateException.class, () -> port.emit("s1", "s2", "msg", "x", false), "对端离线拒绝");

        port.prepareReconnect("s2");
        assertEquals(SocketHub.State.RECONNECTING, port.state("s2"));
        port.connect("/chat", "s2", Map.of("token", "good"));
        assertEquals(SocketHub.State.CONNECTED, port.state("s2"), "重连恢复");
        port.join("/chat", "s2", "r");
        assertEquals(1, port.broadcast("/chat", "r", "s1", "back", "y").size());
    }
}
