package cn.chyuan.ai.domain.socketkernel.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实时会话内核（工单 0965 ED6 / 0967 ED8，socket.io 思想）。
 * 连接状态机 connect·emit·broadcast 入口统一编排/volatile 对端离线丢弃不排队/
 * 与 streamkernel 帧文本作下行报文形态只读联动（泛型文本不 import）/
 * socket-kernel.enabled 默认关（开启才改变行为）。
 */
public final class SocketHub implements SocketPort {

    /** 连接状态机 */
    public enum State { CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING }

    public static final class Delivery {
        public final String sid;
        public final String frame;

        Delivery(String sid, String frame) {
            this.sid = sid;
            this.frame = frame;
        }
    }

    private final SocketNamespaces namespaces = new SocketNamespaces();
    private final Handshakes handshakes = new Handshakes();
    private final Rooms rooms = new Rooms();
    private final Acks acks = new Acks(2);
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Set<String> online = new LinkedHashSet<>();
    private final Map<String, String> sidNs = new LinkedHashMap<>();

    SocketNamespaces namespaces() {
        return namespaces;
    }

    Handshakes handshakes() {
        return handshakes;
    }

    Rooms rooms() {
        return rooms;
    }

    Acks acks() {
        return acks;
    }

    /** 注册命名空间 */
    public void namespace(String name) {
        namespaces.register(name);
    }

    /** 握手中间件链追加 */
    public void middleware(Handshakes.Middleware middleware) {
        handshakes.add(middleware);
    }

    /** 连接：命名空间校验 → 握手链 → CONNECTED；重复连接拒绝 */
    public void connect(String ns, String sid, Map<String, String> handshakeHeaders) {
        namespaces.require(ns);
        State state = states.get(sid);
        if (state == State.CONNECTED) {
            throw new IllegalStateException("已连接: " + sid);
        }
        Handshakes.Context ctx = handshakes.handshake(handshakeHeaders);
        if (ctx.rejected()) {
            states.put(sid, State.DISCONNECTED);
            throw new IllegalStateException("握手拒绝: " + ctx.reason());
        }
        states.put(sid, State.CONNECTED);
        online.add(sid);
        sidNs.put(sid, ns);
    }

    /** 断开：CONNECTED → DISCONNECTED，退出全部房间，掉出在线表 */
    public void disconnect(String sid) {
        requireOnline(sid);
        online.remove(sid);
        states.put(sid, State.DISCONNECTED);
        rooms.leaveAll(sidNs.get(sid), sid);
    }

    /** 重连预备：DISCONNECTED → RECONNECTING（实际连接再走 connect） */
    public void prepareReconnect(String sid) {
        State state = states.get(sid);
        if (state != State.DISCONNECTED) {
            throw new IllegalStateException("非断开态不可重连: " + sid + "=" + state);
        }
        states.put(sid, State.RECONNECTING);
    }

    public State state(String sid) {
        return states.get(sid);
    }

    public boolean isOnline(String sid) {
        return online.contains(sid);
    }

    /** 房间操作 */
    public void join(String ns, String sid, String room) {
        requireOnline(sid);
        namespaces.require(ns);
        rooms.join(ns, sid, room);
    }

    public void leave(String ns, String sid, String room) {
        rooms.leave(ns, sid, room);
    }

    /** 单发：volatile 对端离线丢弃返回 null，非 volatile 离线拒绝 */
    public Delivery emit(String from, String to, String event, String payload, boolean volatileSend) {
        requireConnectedSender(from);
        if (!isOnline(to)) {
            if (volatileSend) {
                return null;
            }
            throw new IllegalStateException("对端离线: " + to);
        }
        return new Delivery(to, frame(event, payload));
    }

    /** 广播：房间或全员，排除发送者 */
    public List<Delivery> broadcast(String ns, String room, String from, String event, String payload) {
        requireConnectedSender(from);
        namespaces.require(ns);
        return rooms.targets(ns, room, from).stream()
                .filter(this::isOnline)
                .map(sid -> new Delivery(sid, frame(event, payload)))
                .toList();
    }

    /** 带 ack 事件：登记回执，返回 ack id */
    public String emitWithAck(String from, String to, String event, String payload) {
        requireConnectedSender(from);
        String ackId = acks.register();
        return ackId;
    }

    public void resolveAck(String ackId, String payload) {
        acks.resolve(ackId, payload);
    }

    public String takeAck(String ackId) {
        return acks.take(ackId);
    }

    /** 时钟步进：ack 超时 */
    public void tick() {
        acks.tick();
    }

    /** streamkernel 帧文本形态只读联动：事件+载荷 → 下行帧文本（形状数据不 import streamkernel） */
    static String frame(String event, String payload) {
        return "42[\"" + event + "\",\"" + payload + "\"]";
    }

    private void requireOnline(String sid) {
        if (!isOnline(sid)) {
            throw new IllegalStateException("对端离线: " + sid);
        }
    }

    private void requireConnectedSender(String sid) {
        if (states.get(sid) != State.CONNECTED) {
            throw new IllegalStateException("发送方未连接: " + sid + "=" + states.get(sid));
        }
    }
}
