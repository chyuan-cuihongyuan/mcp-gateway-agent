package cn.chyuan.ai.domain.msgkernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息流指标（工单 0450 BB8）。
 * 追加/投递/确认/重试/DLQ 五计数 + 主题滞后水位分级（OK/WARN/CRIT 阈值可配）
 * + 确定性快照导出（时间戳注入）。msg-kernel.enabled 默认关无新表沿 AT 先例。
 */
public class MsgMetrics {

    /** 滞后分级 */
    public enum LagLevel {
        OK, WARN, CRIT
    }

    private long appended;
    private long delivered;
    private long acknowledged;
    private long retried;
    private long deadLettered;
    private final long warnThreshold;
    private final long critThreshold;

    public MsgMetrics(long warnThreshold, long critThreshold) {
        if (warnThreshold < 0 || critThreshold < warnThreshold) {
            throw new IllegalArgumentException("阈值须 0 ≤ warn ≤ crit");
        }
        this.warnThreshold = warnThreshold;
        this.critThreshold = critThreshold;
    }

    public synchronized void recordAppend() {
        appended++;
    }

    public synchronized void recordDeliver() {
        delivered++;
    }

    public synchronized void recordAck() {
        acknowledged++;
    }

    public synchronized void recordRetry() {
        retried++;
    }

    public synchronized void recordDeadLetter() {
        deadLettered++;
    }

    /** 滞后水位分级 */
    public LagLevel lagLevel(long lag) {
        if (lag >= critThreshold) {
            return LagLevel.CRIT;
        }
        return lag >= warnThreshold ? LagLevel.WARN : LagLevel.OK;
    }

    /** 确定性快照（传入时间戳注入） */
    public synchronized Map<String, Object> snapshot(long nowMs, long lag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appended", appended);
        out.put("delivered", delivered);
        out.put("acknowledged", acknowledged);
        out.put("retried", retried);
        out.put("deadLettered", deadLettered);
        out.put("lag", lag);
        out.put("lagLevel", lagLevel(lag).name());
        out.put("capturedAtMs", nowMs);
        return out;
    }
}
