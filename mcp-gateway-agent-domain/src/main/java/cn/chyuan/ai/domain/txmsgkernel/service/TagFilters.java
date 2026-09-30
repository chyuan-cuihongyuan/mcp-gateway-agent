package cn.chyuan.ai.domain.txmsgkernel.service;

/**
 * tag 过滤（工单 1141 EX7，rocketmq 思想）。
 * tag 匹配投递/不匹配丢弃留痕/订阅多 tag（|| 分隔）之一命中即投/空或 * 全投。
 */
public final class TagFilters {

    /** 过滤决定：是否投递 + 原因留痕 */
    public record Decision(boolean delivered, String reason) {
    }

    private TagFilters() {
    }

    /** 判定：订阅表达式 null/空/* 全投；|| 分隔多 tag 任一命中即投；否则丢弃留痕 */
    public static Decision decide(String subscription, String messageTags) {
        String tags = messageTags == null ? "" : messageTags;
        if (subscription == null || subscription.isBlank() || "*".equals(subscription.trim())) {
            return new Decision(true, "subscribe-all");
        }
        for (String tag : subscription.split("\\|\\|")) {
            String candidate = tag.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            for (String own : tags.split("\\s+")) {
                if (candidate.equals(own)) {
                    return new Decision(true, "tag-hit:" + candidate);
                }
            }
        }
        return new Decision(false, "tag-miss");
    }
}
