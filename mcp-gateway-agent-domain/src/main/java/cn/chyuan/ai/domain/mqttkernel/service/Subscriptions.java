package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅语义（工单 1077 EQ4，emqx 思想）。
 * 订阅增删/＋单层＃多层最小通配匹配/重叠订阅按客户端去重（保留最大 QoS）不重复投递/
 * 投递 QoS 取订阅与消息较小。
 */
public final class Subscriptions {

    /** 订阅：客户端 + 过滤器 + QoS */
    public record Sub(String clientId, String filter, int qos) {
    }

    private final Map<String, Map<String, Integer>> byClient = new LinkedHashMap<>();

    /** 订阅：同客户端同过滤器覆盖 QoS；非法过滤器拒绝 */
    public void subscribe(String clientId, String filter, int qos) {
        requireFilter(filter);
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS 越界拒绝: " + qos);
        }
        byClient.computeIfAbsent(clientId, key -> new LinkedHashMap<>()).put(filter, qos);
    }

    /** 退订：未订阅拒绝 */
    public void unsubscribe(String clientId, String filter) {
        Map<String, Integer> filters = byClient.get(clientId);
        if (filters == null || filters.remove(filter) == null) {
            throw new IllegalArgumentException("未订阅拒绝退订: " + clientId + " " + filter);
        }
    }

    /** 清空客户端全部订阅（cleanStart 用） */
    public void clear(String clientId) {
        byClient.remove(clientId);
    }

    /** 路由：匹配主题的订阅，按客户端去重保留最大 QoS（不重复投递） */
    public List<Sub> route(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        Map<String, Integer> best = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> client : byClient.entrySet()) {
            int maxQos = -1;
            String hitFilter = null;
            for (Map.Entry<String, Integer> filter : client.getValue().entrySet()) {
                if (matches(topic, filter.getKey()) && filter.getValue() > maxQos) {
                    maxQos = filter.getValue();
                    hitFilter = filter.getKey();
                }
            }
            if (hitFilter != null) {
                best.put(client.getKey(), maxQos);
            }
        }
        List<Sub> subs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : best.entrySet()) {
            subs.add(new Sub(entry.getKey(), "", entry.getValue()));
        }
        return subs;
    }

    /** 投递 QoS = min(消息 QoS, 订阅 QoS) */
    public static int deliveryQos(int messageQos, int subscriptionQos) {
        return Math.min(messageQos, subscriptionQos);
    }

    /** 通配匹配：＋恰一层、＃仅末段匹配零或多层、其余逐段相等 */
    public static boolean matches(String topic, String filter) {
        String[] topicParts = topic.split("/", -1);
        String[] filterParts = filter.split("/", -1);
        for (int index = 0; index < filterParts.length; index++) {
            String part = filterParts[index];
            if (part.equals("#")) {
                if (index != filterParts.length - 1) {
                    throw new IllegalArgumentException("# 仅能作为末段: " + filter);
                }
                return topicParts.length >= index;
            }
            if (index >= topicParts.length) {
                return false;
            }
            if (!part.equals("+") && !part.equals(topicParts[index])) {
                return false;
            }
        }
        return topicParts.length == filterParts.length;
    }

    public int count(String clientId) {
        Map<String, Integer> filters = byClient.get(clientId);
        return filters == null ? 0 : filters.size();
    }

    private void requireFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            throw new IllegalArgumentException("过滤器不能为空");
        }
        String[] parts = filter.split("/", -1);
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].equals("#") && index != parts.length - 1) {
                throw new IllegalArgumentException("# 仅能作为末段: " + filter);
            }
        }
    }
}
