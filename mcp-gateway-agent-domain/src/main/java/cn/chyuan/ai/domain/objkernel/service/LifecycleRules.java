package cn.chyuan.ai.domain.objkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 生命周期规则（工单 1130 EW4，minio 思想）。
 * 过期规则按前缀+天数/虚拟时钟到期清理/同前缀规则冲突拒绝/无规则不清理/清理只删过期对象。
 */
public final class LifecycleRules {

    /** 规则：前缀 + 保留天数 */
    public record Rule(String prefix, int expireDays) {
    }

    private final List<Rule> rules = new ArrayList<>();

    /** 添加规则：空参/负天数拒绝；同前缀冲突拒绝 */
    public void add(String prefix, int expireDays) {
        if (prefix == null || expireDays <= 0) {
            throw new IllegalArgumentException("规则前缀与天数不合法");
        }
        for (Rule rule : rules) {
            if (rule.prefix().equals(prefix)) {
                throw new IllegalStateException("同前缀规则冲突: " + prefix);
            }
        }
        rules.add(new Rule(prefix, expireDays));
    }

    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    public boolean matches(String key) {
        for (Rule rule : rules) {
            if (key.startsWith(rule.prefix())) {
                return true;
            }
        }
        return false;
    }

    /** 求某 key 的保留天数；无规则返回 -1（不清理） */
    public int expireDaysOf(String key) {
        for (Rule rule : rules) {
            if (key.startsWith(rule.prefix())) {
                return rule.expireDays();
            }
        }
        return -1;
    }

    /** 到期判定：modifiedTick + 天数*每日 tick &lt;= nowTick 即过期（天数按每日 1 tick 虚拟口径） */
    public boolean expired(String key, long modifiedTick, long nowTick) {
        int days = expireDaysOf(key);
        return days > 0 && modifiedTick + days <= nowTick;
    }
}
