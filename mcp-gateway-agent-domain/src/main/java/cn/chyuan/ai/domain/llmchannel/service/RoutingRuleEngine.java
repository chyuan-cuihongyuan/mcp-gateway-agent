package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.llmchannel.model.valobj.RoutingRuleVO;

import java.util.List;
import java.util.Locale;

/**
 * tag 路由规则匹配纯函数（工单 0160）
 *
 * <p>匹配口径：
 * ①请求 tags 中任一标签与规则 "tagKey=tagValue" 精确相等（trim + 大小写归一）即命中——
 *   部分命中（请求带多个标签、规则只需其一）视同命中；
 * ②多条规则同时命中取 priority 最高（大者优先），同级取 id 最小（确定性）；
 * ③不命中（或无启用规则/请求无标签）返回 null=全渠道兼容。
 *
 * @author chyuan
 */
public final class RoutingRuleEngine {

    /** 渠道默认组（channel_group 空=默认组） */
    public static final String DEFAULT_GROUP = "default";

    private RoutingRuleEngine() {
        // 纯函数工具类，禁止实例化
    }

    /**
     * 规则匹配。
     *
     * @param activeRules 启用态规则清单
     * @param tags        请求标签（"key=value" 形态）
     * @return 命中的最高优先级规则；不命中返回 null（全渠道）
     */
    public static RoutingRuleVO match(List<RoutingRuleVO> activeRules, List<String> tags) {
        if (activeRules == null || activeRules.isEmpty() || tags == null || tags.isEmpty()) {
            return null;
        }
        RoutingRuleVO best = null;
        for (RoutingRuleVO rule : activeRules) {
            if (rule == null || !RoutingRuleVO.STATUS_ACTIVE.equals(rule.getStatus())) {
                continue;
            }
            String token = tokenOf(rule.getTagKey(), rule.getTagValue());
            if (token == null) {
                continue;
            }
            boolean hit = false;
            for (String tag : tags) {
                if (tag != null && token.equals(tag.trim().toLowerCase(Locale.ROOT))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            if (best == null || rule.getPriority() != null && rule.getPriority() > priorityOf(best)
                    || rule.getPriority() != null && rule.getPriority().equals(priorityOf(best))
                    && rule.getId() != null && rule.getId() < idOf(best)) {
                best = rule;
            }
        }
        return best;
    }

    /** 渠道所属组（channel_group 空/空白=默认组） */
    public static String groupOf(String channelGroup) {
        return channelGroup == null || channelGroup.isBlank() ? DEFAULT_GROUP : channelGroup.trim();
    }

    /** 规则标签对规范形 "key=value"（小写归一；键值缺失返回 null=不可匹配） */
    static String tokenOf(String tagKey, String tagValue) {
        if (tagKey == null || tagKey.isBlank() || tagValue == null || tagValue.isBlank()) {
            return null;
        }
        return (tagKey.trim() + "=" + tagValue.trim()).toLowerCase(Locale.ROOT);
    }

    private static int priorityOf(RoutingRuleVO rule) {
        return rule.getPriority() == null ? 0 : rule.getPriority();
    }

    private static long idOf(RoutingRuleVO rule) {
        return rule.getId() == null ? Long.MAX_VALUE : rule.getId();
    }
}
