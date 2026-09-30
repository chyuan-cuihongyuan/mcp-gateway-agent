package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * keepalive（工单 1080 EQ7，emqx 思想）。
 * 1.5 倍 keepalive 未心跳判断开/心跳保活重置计时/keepalive=0 免检/
 * 判断开由调用方联动断开与遗嘱投递。
 */
public final class KeepAlives {

    private static final class Entry {
        long keepAliveMs;
        long lastHeartbeat;
        boolean exempt;
    }

    private final Map<String, Entry> clients = new HashMap<>();

    /** 装配保活：keepAliveSecs 为 0 免检 */
    public void arm(String clientId, long keepAliveSecs, long nowMs) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        if (keepAliveSecs < 0) {
            throw new IllegalArgumentException("keepalive 不可为负: " + keepAliveSecs);
        }
        Entry entry = new Entry();
        entry.keepAliveMs = keepAliveSecs * 1000L;
        entry.lastHeartbeat = nowMs;
        entry.exempt = keepAliveSecs == 0;
        clients.put(clientId, entry);
    }

    /** 心跳保活：重置计时；未知客户端拒绝 */
    public void heartbeat(String clientId, long nowMs) {
        Entry entry = clients.get(clientId);
        if (entry == null) {
            throw new IllegalArgumentException("未知客户端拒绝心跳: " + clientId);
        }
        entry.lastHeartbeat = nowMs;
    }

    /** 判定（消费语义）：超过 1.5 倍 keepalive 未心跳判开并摘除（免检客户端不含、判开为终态） */
    public List<String> expired(long nowMs) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Entry> client : clients.entrySet()) {
            Entry entry = client.getValue();
            if (entry.exempt) {
                continue;
            }
            long deadline = entry.lastHeartbeat + entry.keepAliveMs + entry.keepAliveMs / 2;
            if (nowMs > deadline) {
                expired.add(client.getKey());
            }
        }
        for (String clientId : expired) {
            clients.remove(clientId);
        }
        return expired;
    }

    /** 摘除保活（断开后） */
    public void disarm(String clientId) {
        clients.remove(clientId);
    }
}
