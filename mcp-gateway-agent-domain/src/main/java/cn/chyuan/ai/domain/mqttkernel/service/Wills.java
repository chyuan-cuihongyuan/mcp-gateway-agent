package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遗嘱 will（工单 1079 EQ6，emqx 思想）。
 * 异常断开投递 will/正常 DISCONNECT 不投/takeover 不触发/
 * will 按 QoS·主题声明投递且只投一次（投后清除）。
 */
public final class Wills {

    /** 遗嘱 */
    public record Will(String clientId, String topic, String payload, int qos) {
    }

    /** 断开语义 */
    public enum Reason { NORMAL, ABNORMAL, TAKEOVER }

    private final Map<String, Will> wills = new ConcurrentHashMap<>();

    /** 登记遗嘱（同客户端覆盖） */
    public void register(String clientId, String topic, String payload, int qos) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("遗嘱主题不能为空");
        }
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS 越界拒绝: " + qos);
        }
        wills.put(clientId, new Will(clientId, topic, payload == null ? "" : payload, qos));
    }

    /** 断开时按语义裁定：仅 ABNORMAL 投递一次并清除；NORMAL/TAKEOVER 不投 */
    public Optional<Will> publishDue(String clientId, Reason reason) {
        Will will = wills.get(clientId);
        if (reason != Reason.ABNORMAL || will == null) {
            return Optional.empty();
        }
        wills.remove(clientId);
        return Optional.of(will);
    }

    /** 清除遗嘱（cleanStart） */
    public void clear(String clientId) {
        wills.remove(clientId);
    }

    public boolean has(String clientId) {
        return wills.containsKey(clientId);
    }
}
