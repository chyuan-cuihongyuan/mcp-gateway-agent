package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.types.util.IpCidrUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IP/CIDR 白名单匹配测试（工单 0045）
 */
@DisplayName("IP/CIDR 白名单匹配测试")
public class IpCidrUtilTest {

    @Test
    @DisplayName("空白名单 — 一律放行（不限制语义）")
    public void testEmptyList_AllowsAll() {
        assertTrue(IpCidrUtil.allows(null, "1.2.3.4"));
        assertTrue(IpCidrUtil.allows(Collections.emptyList(), "1.2.3.4"));
        assertTrue(IpCidrUtil.allows(List.of(""), "1.2.3.4"), "全空条目视为不限制");
    }

    @Test
    @DisplayName("单 IP 条目 — 精确匹配")
    public void testSingleIp() {
        List<String> list = List.of("192.168.1.10");
        assertTrue(IpCidrUtil.allows(list, "192.168.1.10"));
        assertFalse(IpCidrUtil.allows(list, "192.168.1.11"));
    }

    @Test
    @DisplayName("CIDR 网段条目 — 前缀匹配（含非整字节掩码）")
    public void testCidr() {
        List<String> list = List.of("10.0.0.0/8", "172.16.0.0/12");
        assertTrue(IpCidrUtil.allows(list, "10.255.0.1"));
        assertTrue(IpCidrUtil.allows(list, "172.31.200.9"));
        assertFalse(IpCidrUtil.allows(list, "172.32.0.1"), "/12 边界外");
        assertFalse(IpCidrUtil.allows(list, "11.0.0.1"));

        List<String> odd = List.of("192.168.2.0/25");
        assertTrue(IpCidrUtil.allows(odd, "192.168.2.127"));
        assertFalse(IpCidrUtil.allows(odd, "192.168.2.128"), "/25 边界");
    }

    @Test
    @DisplayName("fail-closed — 配了白名单但取不到来源 IP 或条目畸形")
    public void testFailClosed() {
        List<String> list = List.of("10.0.0.0/8");
        assertFalse(IpCidrUtil.allows(list, null));
        assertFalse(IpCidrUtil.allows(list, " "));
        assertFalse(IpCidrUtil.allows(list, "not-an-ip"));

        List<String> malformed = List.of("300.0.0.0/8", "abc");
        assertFalse(IpCidrUtil.allows(malformed, "10.0.0.1"), "畸形条目不匹配");
    }
}
