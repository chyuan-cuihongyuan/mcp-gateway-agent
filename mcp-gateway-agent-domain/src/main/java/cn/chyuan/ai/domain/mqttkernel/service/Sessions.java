package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CONNECT 会话（工单 1074 EQ1，emqx 思想）。
 * cleanStart 建新会话清状态/会话存在接管 takeover 顶替旧连接（返回被踢旧连接）/
 * takeover 不投遗嘱（由 Wills 按语义裁定）/旧连接标记 DISCONNECTED 不并存。
 */
public final class Sessions {

    /** 会话状态 */
    public enum State { CONNECTED, DISCONNECTED }

    /** 会话 */
    public static final class Session {
        private final String clientId;
        private State state;

        Session(String clientId, State state) {
            this.clientId = clientId;
            this.state = state;
        }

        public String clientId() {
            return clientId;
        }

        public State state() {
            return state;
        }
    }

    /** 连接结果：新会话 + 被顶替的旧连接（takeover 时非空） */
    public record ConnectResult(Session session, String kickedConnection) {
    }

    private final Map<String, Session> sessions = new HashMap<>();

    /** 连接：cleanStart 清状态建新会话，否则沿用既有会话状态；旧连接 CONNECTED 时 takeover 顶替 */
    public ConnectResult connect(String clientId, boolean cleanStart) {
        requireClientId(clientId);
        Session existing = sessions.get(clientId);
        String kicked = null;
        if (existing != null && existing.state == State.CONNECTED) {
            existing.state = State.DISCONNECTED;
            kicked = clientId + "@old";
        }
        if (cleanStart || existing == null) {
            Session fresh = new Session(clientId, State.CONNECTED);
            sessions.put(clientId, fresh);
            return new ConnectResult(fresh, kicked);
        }
        existing.state = State.CONNECTED;
        return new ConnectResult(existing, kicked);
    }

    /** 断开（正常或异常由调用方语义裁定遗嘱）；未知会话与重复断开拒绝 */
    public Session disconnect(String clientId) {
        Session session = requireSession(clientId);
        if (session.state == State.DISCONNECTED) {
            throw new IllegalStateException("重复断开拒绝: " + clientId);
        }
        session.state = State.DISCONNECTED;
        return session;
    }

    public boolean isOnline(String clientId) {
        Session session = sessions.get(clientId);
        return session != null && session.state == State.CONNECTED;
    }

    public List<String> online() {
        List<String> ids = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.state == State.CONNECTED) {
                ids.add(session.clientId);
            }
        }
        return ids;
    }

    private Session requireSession(String clientId) {
        Session session = sessions.get(clientId);
        if (session == null) {
            throw new IllegalArgumentException("未知会话拒绝: " + clientId);
        }
        return session;
    }

    private void requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
    }
}
