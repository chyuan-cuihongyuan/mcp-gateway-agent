package cn.chyuan.ai.domain.socketkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 实时会话端口（工单 0967 ED8，socket.io 思想）。
 * connect·emit·broadcast 入口统一编排/与 streamkernel 帧文本作下行报文形态只读联动（泛型文本不 import）/
 * socket-kernel.enabled 默认关（开启才改变行为）。
 */
public interface SocketPort {

    void namespace(String name);

    void middleware(Handshakes.Middleware middleware);

    /** 连接握手：命名空间校验 + 中间件链，成功进入 CONNECTED */
    void connect(String ns, String sid, Map<String, String> headers);

    void disconnect(String sid);

    /** 断开后预备重连：DISCONNECTED → RECONNECTING */
    void prepareReconnect(String sid);

    SocketHub.State state(String sid);

    boolean isOnline(String sid);

    void join(String ns, String sid, String room);

    void leave(String ns, String sid, String room);

    /** 单发：volatile 对端离线丢弃返回 null；非 volatile 离线拒绝 */
    SocketHub.Delivery emit(String from, String to, String event, String payload, boolean volatileSend);

    /** 广播：房间或全员，排除发送者 */
    List<SocketHub.Delivery> broadcast(String ns, String room, String from, String event, String payload);

    /** 带 ack 事件：登记回执返回 ack id */
    String emitWithAck(String from, String to, String event, String payload);

    void resolveAck(String ackId, String payload);

    String takeAck(String ackId);

    void tick();

    static SocketPort inMemory() {
        return new SocketHub();
    }
}
