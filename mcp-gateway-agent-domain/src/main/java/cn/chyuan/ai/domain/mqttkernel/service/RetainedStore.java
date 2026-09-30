package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * retained 消息（工单 1078 EQ5，emqx 思想）。
 * 新订阅即时投递匹配 retained/空载荷清除/同主题覆盖至多一条/按订阅 QoS 与消息 QoS 较小投递。
 */
public final class RetainedStore {

    /** retained 消息 */
    public record Retained(String topic, String payload, int qos) {
    }

    private final Map<String, Retained> retained = new LinkedHashMap<>();

    /** 保留消息：空载荷清除返回 false；同主题覆盖至多一条 */
    public boolean retain(String topic, String payload, int qos) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        if (payload == null || payload.isEmpty()) {
            return retained.remove(topic) != null;
        }
        retained.put(topic, new Retained(topic, payload, qos));
        return true;
    }

    /** 新订阅即时投递：返回匹配过滤器的 retained 及投递 QoS（取较小） */
    public List<String> onSubscribe(String filter, int subscriptionQos) {
        List<String> deliveries = new ArrayList<>();
        for (Retained message : retained.values()) {
            if (Subscriptions.matches(message.topic(), filter)) {
                deliveries.add(message.topic() + ":" + Subscriptions.deliveryQos(message.qos(), subscriptionQos)
                        + ":" + message.payload());
            }
        }
        return deliveries;
    }

    public int size() {
        return retained.size();
    }
}
