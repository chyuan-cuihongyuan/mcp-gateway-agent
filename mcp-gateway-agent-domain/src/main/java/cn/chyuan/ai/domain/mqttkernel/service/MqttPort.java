package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.List;

/**
 * MQTT 会话端口（工单 1081 EQ8，emqx 思想）。
 * connect·publish·subscribe 入口统一编排：会话 takeover/QoS 出站状态机/
 * inflight 窗口重传/retained/遗嘱/keepalive 组合管线；
 * socketkernel 会话形状只读联动（形状键 ns·sid·state 对齐，不 import socketkernel）/
 * mqtt-kernel.enabled 默认关（开启才改变行为）。
 */
public interface MqttPort {

    // —— 会话与遗嘱（EQ1/EQ6/EQ7）——
    String connect(String clientId, boolean cleanStart, String willTopic, String willPayload,
            int willQos, int keepAliveSecs);

    String disconnect(String clientId);

    boolean isOnline(String clientId);

    void heartbeat(String clientId, long nowMs);

    List<String> keepaliveExpired(long nowMs);

    // —— 订阅与 retained（EQ4/EQ5）——
    List<String> subscribe(String clientId, String filter, int qos);

    void unsubscribe(String clientId, String filter);

    List<String> deliver(String topic, String payload, int qos);

    void retain(String topic, String payload, int qos);

    // —— 出站投递（EQ2/EQ3）——
    int publishOut(String clientId, int qos);

    String puback(String clientId, int packetId);

    String pubrec(String clientId, int packetId);

    String pubcomp(String clientId, int packetId);

    List<Integer> resendDue(String clientId, long nowMs);

    // —— socketkernel 会话形状只读联动（EQ8）——
    List<String> sessionShape();

    static MqttPort inMemory(int windowSize, long retryTimeoutMs, long nowMs) {
        return new MqttHub(windowSize, retryTimeoutMs, nowMs);
    }
}
