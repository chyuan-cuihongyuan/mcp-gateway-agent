package cn.chyuan.ai.domain.llmchannel.service;

import cn.chyuan.ai.domain.llmchannel.model.valobj.RoutingRuleVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tag 路由规则匹配纯函数测试（工单 0160：多规则优先级/部分命中/不命中兼容）
 */
@DisplayName("tag 路由规则匹配纯函数测试")
public class RoutingRuleEngineTest {

    private static RoutingRuleVO rule(long id, String name, String key, String value, String group, int priority, String status) {
        return RoutingRuleVO.builder()
                .id(id).ruleName(name).tagKey(key).tagValue(value)
                .channelGroupId(group).priority(priority)
                .status(status == null ? RoutingRuleVO.STATUS_ACTIVE : status)
                .build();
    }

    @Test
    @DisplayName("多规则优先级 — 两规则均命中取 priority 最高；同级取 id 最小")
    public void testPriorityWins() {
        RoutingRuleVO low = rule(1L, "gray", "team", "alpha", "gray-group", 0, null);
        RoutingRuleVO high = rule(2L, "vip", "team", "alpha", "vip-group", 10, null);
        assertEquals(high, RoutingRuleEngine.match(List.of(low, high), List.of("team=alpha")),
                "高优先级胜出");

        RoutingRuleVO tie1 = rule(3L, "tie1", "env", "prod", "g1", 5, null);
        RoutingRuleVO tie2 = rule(4L, "tie2", "region", "cn", "g2", 5, null);
        assertEquals(tie1, RoutingRuleEngine.match(List.of(tie2, tie1), List.of("env=prod", "region=cn")),
                "同级取 id 最小，确定性");
    }

    @Test
    @DisplayName("部分命中 — 请求多标签只需其一命中规则即生效")
    public void testPartialHit() {
        RoutingRuleVO rule = rule(1L, "gray", "team", "alpha", "gray-group", 0, null);
        assertEquals(rule, RoutingRuleEngine.match(List.of(rule),
                List.of("tenant=a", "team=alpha", "tier=vip")), "多标签中一击即中");
    }

    @Test
    @DisplayName("不命中/无标签/停用规则 — 返回 null 全渠道兼容")
    public void testMissCompatible() {
        RoutingRuleVO rule = rule(1L, "gray", "team", "alpha", "gray-group", 0, null);
        assertNull(RoutingRuleEngine.match(List.of(rule), List.of("team=beta")), "值不同不命中");
        assertNull(RoutingRuleEngine.match(List.of(rule), List.of()), "空标签不命中");
        assertNull(RoutingRuleEngine.match(List.of(rule), null), "null 标签不命中");
        assertNull(RoutingRuleEngine.match(List.of(
                        rule(2L, "off", "team", "alpha", "g", 9, RoutingRuleVO.STATUS_DISABLED)),
                List.of("team=alpha")), "停用规则忽略");
        assertNull(RoutingRuleEngine.match(List.of(), List.of("team=alpha")), "无规则全渠道");
    }

    @Test
    @DisplayName("大小写归一 — 标签 token 忽略大小写匹配")
    public void testCaseInsensitive() {
        RoutingRuleVO rule = rule(1L, "env", "Team", "Alpha", "g", 0, null);
        assertEquals(rule, RoutingRuleEngine.match(List.of(rule), List.of("TEAM=ALPHA")));
    }

    @Test
    @DisplayName("渠道组归一 — 空 channel_group=默认组 default")
    public void testGroupOf() {
        assertEquals("default", RoutingRuleEngine.groupOf(null));
        assertEquals("default", RoutingRuleEngine.groupOf("  "));
        assertEquals("vip", RoutingRuleEngine.groupOf("vip"));
    }
}
