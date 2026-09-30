package cn.chyuan.ai.domain.mqttkernel.service;

import java.util.HashMap;
import java.util.Map;

/**
 * QoS 出站投递状态机（工单 1075 EQ2，emqx 思想）。
 * QoS0 即发即忘（无状态记录）/QoS1 待 PUBACK 完成/
 * QoS2 PUBREC→PUBREL→PUBCOMP 三段（PUBREC 后应答 PUBREL）/
 * 非法跃迁（错序 ack、重复 ack、未发起 ack）拒绝。
 */
public final class QosFlows {

    /** 出站相位 */
    public enum Phase { AWAIT_PUBACK, AWAIT_PUBREC, AWAIT_PUBCOMP, COMPLETE }

    private final Map<Integer, Phase> flows = new HashMap<>();

    /** QoS1 出站：登记待 PUBACK */
    public Phase publishQos1(int packetId) {
        return begin(packetId, Phase.AWAIT_PUBACK);
    }

    /** QoS2 出站：登记待 PUBREC */
    public Phase publishQos2(int packetId) {
        return begin(packetId, Phase.AWAIT_PUBREC);
    }

    /** QoS1 确认：AWAIT_PUBACK → COMPLETE */
    public Phase puback(int packetId) {
        return transition(packetId, Phase.AWAIT_PUBACK, Phase.COMPLETE);
    }

    /** QoS2 首段确认：AWAIT_PUBREC → AWAIT_PUBCOMP（应发 PUBREL） */
    public Phase pubrec(int packetId) {
        return transition(packetId, Phase.AWAIT_PUBREC, Phase.AWAIT_PUBCOMP);
    }

    /** QoS2 末段确认：AWAIT_PUBCOMP → COMPLETE */
    public Phase pubcomp(int packetId) {
        return transition(packetId, Phase.AWAIT_PUBCOMP, Phase.COMPLETE);
    }

    public Phase phase(int packetId) {
        Phase phase = flows.get(packetId);
        if (phase == null) {
            throw new IllegalArgumentException("未知报文拒绝: " + packetId);
        }
        return phase;
    }

    public boolean inFlight(int packetId) {
        Phase phase = flows.get(packetId);
        return phase != null && phase != Phase.COMPLETE;
    }

    private Phase begin(int packetId, Phase initial) {
        if (flows.containsKey(packetId) && flows.get(packetId) != Phase.COMPLETE) {
            throw new IllegalStateException("报文未完结拒绝重复发起: " + packetId);
        }
        flows.put(packetId, initial);
        return initial;
    }

    private Phase transition(int packetId, Phase expect, Phase next) {
        Phase phase = phase(packetId);
        if (phase != expect) {
            throw new IllegalStateException(
                    "非法跃迁拒绝: packetId=" + packetId + " 期望 " + expect + " 实际 " + phase);
        }
        flows.put(packetId, next);
        return next;
    }
}
