package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 会话编排实现（工单 1081 EQ8，emqx 思想）。
 * connect 语义（cleanStart/takeover 不投遗嘱）/出站 QoS 状态机与 inflight 窗口联动/
 * 订阅路由与 retained 即时投递/keepalive 判开联动遗嘱投递。
 */
public final class MqttHub implements MqttPort {

    private final Sessions sessions = new Sessions();
    private final Subscriptions subscriptions = new Subscriptions();
    private final RetainedStore retainedStore = new RetainedStore();
    private final Wills wills = new Wills();
    private final KeepAlives keepAlives = new KeepAlives();
    private final Map<String, QosFlows> flows = new HashMap<>();
    private final Map<String, InflightWindows> windows = new HashMap<>();
    private final Map<String, Integer> packetSeq = new HashMap<>();
    private final int windowSize;
    private final long retryTimeoutMs;
    private long clock;

    public MqttHub(int windowSize, long retryTimeoutMs, long nowMs) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("窗口容量必须为正: " + windowSize);
        }
        if (retryTimeoutMs <= 0) {
            throw new IllegalArgumentException("重传超时必须为正: " + retryTimeoutMs);
        }
        this.windowSize = windowSize;
        this.retryTimeoutMs = retryTimeoutMs;
        this.clock = nowMs;
    }

    @Override
    public String connect(String clientId, boolean cleanStart, String willTopic, String willPayload,
            int willQos, int keepAliveSecs) {
        Sessions.ConnectResult result = sessions.connect(clientId, cleanStart);
        if (cleanStart) {
            subscriptions.clear(clientId);
            wills.clear(clientId);
        }
        if (willTopic != null && !willTopic.isBlank()) {
            wills.register(clientId, willTopic, willPayload, willQos);
        }
        keepAlives.arm(clientId, keepAliveSecs, clock);
        return result.kickedConnection() == null ? "CONNECTED" : "TAKEOVER:" + result.kickedConnection();
    }

    @Override
    public String disconnect(String clientId) {
        sessions.disconnect(clientId);
        keepAlives.disarm(clientId);
        return "DISCONNECTED";
    }

    @Override
    public boolean isOnline(String clientId) {
        return sessions.isOnline(clientId);
    }

    @Override
    public void heartbeat(String clientId, long nowMs) {
        keepAlives.heartbeat(clientId, nowMs);
    }

    @Override
    public List<String> keepaliveExpired(long nowMs) {
        List<String> events = new ArrayList<>();
        for (String clientId : keepAlives.expired(nowMs)) {
            sessions.disconnect(clientId);
            keepAlives.disarm(clientId);
            String event = wills.publishDue(clientId, Wills.Reason.ABNORMAL)
                    .map(will -> clientId + ":" + will.topic() + ":" + will.payload())
                    .orElse(clientId + ":no-will");
            events.add(event);
        }
        return events;
    }

    @Override
    public List<String> subscribe(String clientId, String filter, int qos) {
        requireOnline(clientId);
        subscriptions.subscribe(clientId, filter, qos);
        return retainedStore.onSubscribe(filter, qos);
    }

    @Override
    public void unsubscribe(String clientId, String filter) {
        requireOnline(clientId);
        subscriptions.unsubscribe(clientId, filter);
    }

    @Override
    public List<String> deliver(String topic, String payload, int qos) {
        List<String> deliveries = new ArrayList<>();
        for (Subscriptions.Sub sub : subscriptions.route(topic)) {
            if (sessions.isOnline(sub.clientId())) {
                deliveries.add(sub.clientId() + ":" + Subscriptions.deliveryQos(qos, sub.qos()));
            }
        }
        return deliveries;
    }

    @Override
    public void retain(String topic, String payload, int qos) {
        retainedStore.retain(topic, payload, qos);
    }

    @Override
    public int publishOut(String clientId, int qos) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS 越界拒绝: " + qos);
        }
        requireOnline(clientId);
        int packetId = packetSeq.merge(clientId, 1, Integer::sum);
        if (qos == 0) {
            return packetId;
        }
        InflightWindows window = windows.computeIfAbsent(clientId, key -> new InflightWindows(windowSize));
        if (!window.offer(packetId, clock, retryTimeoutMs)) {
            return -1;
        }
        QosFlows flow = flows.computeIfAbsent(clientId, key -> new QosFlows());
        if (qos == 1) {
            flow.publishQos1(packetId);
        } else {
            flow.publishQos2(packetId);
        }
        return packetId;
    }

    @Override
    public String puback(String clientId, int packetId) {
        flow(clientId).puback(packetId);
        window(clientId).release(packetId);
        return "COMPLETE";
    }

    @Override
    public String pubrec(String clientId, int packetId) {
        flow(clientId).pubrec(packetId);
        window(clientId).holdRelay(packetId);
        return "PUBREL";
    }

    @Override
    public String pubcomp(String clientId, int packetId) {
        flow(clientId).pubcomp(packetId);
        window(clientId).release(packetId);
        return "COMPLETE";
    }

    @Override
    public List<Integer> resendDue(String clientId, long nowMs) {
        return window(clientId).dueResend(nowMs);
    }

    @Override
    public List<String> sessionShape() {
        return List.of("ns", "sid", "state");
    }

    private QosFlows flow(String clientId) {
        QosFlows flow = flows.get(clientId);
        if (flow == null) {
            throw new IllegalArgumentException("无出站流拒绝: " + clientId);
        }
        return flow;
    }

    private InflightWindows window(String clientId) {
        InflightWindows window = windows.get(clientId);
        if (window == null) {
            throw new IllegalArgumentException("无 inflight 窗口拒绝: " + clientId);
        }
        return window;
    }

    private void requireOnline(String clientId) {
        if (!sessions.isOnline(clientId)) {
            throw new IllegalStateException("会话未在线拒绝: " + clientId);
        }
    }
}
