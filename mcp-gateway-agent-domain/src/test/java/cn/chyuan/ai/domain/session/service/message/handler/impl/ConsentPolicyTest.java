package cn.chyuan.ai.domain.session.service.message.handler.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 危险工具 consent 策略契约（SELFLOOP2 loop-233）：
 * 四型通配匹配 + 默认关闭 + 多模式并集。
 */
@DisplayName("ConsentPolicy 通配匹配契约")
class ConsentPolicyTest {

    @Test
    @DisplayName("默认（空配置）完全关闭")
    void disabledByDefault() {
        ConsentPolicy policy = new ConsentPolicy("");
        assertFalse(policy.requiresConsent("delete_all"));
        assertFalse(policy.requiresConsent(null));
    }

    @Test
    @DisplayName("四型匹配：前缀*/精确/*后缀/*包含*")
    void fourMatchTypes() {
        ConsentPolicy policy = new ConsentPolicy("drop_table, delete_*, *_force, *admin*");
        assertTrue(policy.requiresConsent("drop_table"));
        assertTrue(policy.requiresConsent("delete_user"));
        assertFalse(policy.requiresConsent("truncate_table"));
        assertTrue(policy.requiresConsent("purge_force"));
        assertTrue(policy.requiresConsent("user_admin_reset"));
        assertFalse(policy.requiresConsent("query_list"));
    }

    @Test
    @DisplayName("matches 静态四型单测")
    void staticMatches() {
        assertTrue(ConsentPolicy.matches("delete_*", "delete_user"));
        assertFalse(ConsentPolicy.matches("delete_*", "remove_user"));
        assertTrue(ConsentPolicy.matches("*_force", "purge_force"));
        assertTrue(ConsentPolicy.matches("*admin*", "user_admin_reset"));
        assertTrue(ConsentPolicy.matches("exact_tool", "exact_tool"));
        assertFalse(ConsentPolicy.matches("exact_tool", "exact_tool2"));
    }

    @Test
    @DisplayName("空白与垃圾配置安全忽略")
    void blankEntriesIgnored() {
        ConsentPolicy policy = new ConsentPolicy(" , ,,delete_*, ");
        assertTrue(policy.requiresConsent("delete_x"));
        assertFalse(policy.requiresConsent("x"));
    }
}
