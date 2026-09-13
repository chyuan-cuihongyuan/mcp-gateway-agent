package cn.chyuan.ai.domain.configcenter.service;

import cn.chyuan.ai.domain.configcenter.service.ConfigGrayRouter.GrayRule;
import cn.chyuan.ai.domain.configcenter.service.ConfigGrayRouter.Resolved;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置灰度路由单测（工单 0256 AG6）：stable/白名单/百分比三分支 + stickiness + 暂停。
 */
class ConfigGrayRouterTest {

    @Test
    void 白名单与百分比与stable三分支() {
        ConfigGrayRouter router = new ConfigGrayRouter();
        router.upsert(new GrayRule("ns", ConfigGrayRouter.MODE_GRAY,
                Set.of("tenant-a"), 100, 3, false));
        // 白名单命中灰度版本
        assertEquals(3, router.resolve("ns", "tenant-a", 2).version());
        assertEquals("white", router.resolve("ns", "tenant-a", 2).by());
        // 百分比 100 → 全部灰度
        Resolved all = router.resolve("ns", "tenant-x", 2);
        assertEquals(3, all.version());
        assertEquals("percentage", all.by());
        // stable 模式全量
        router.upsert(new GrayRule("other", ConfigGrayRouter.MODE_STABLE, Set.of(), 0, 0, false));
        assertEquals(5, router.resolve("other", "tenant-a", 5).version());
    }

    @Test
    void stickiness同租户同结果且分布合理() {
        // 同租户同命名空间恒定
        int first = ConfigGrayRouter.stickinessPercent("t1", "ns");
        for (int i = 0; i < 10; i++) {
            assertEquals(first, ConfigGrayRouter.stickinessPercent("t1", "ns"));
        }
        // 不同租户分散（100 个租户中落在 0-99 且不全相同）
        long distinct = java.util.stream.IntStream.range(0, 100)
                .map(i -> ConfigGrayRouter.stickinessPercent("t" + i, "ns"))
                .distinct().count();
        assertTrue(distinct > 20, "分布应分散，实际 distinct=" + distinct);
    }

    @Test
    void 百分比切面近似() {
        ConfigGrayRouter router = new ConfigGrayRouter();
        router.upsert(new GrayRule("ns", ConfigGrayRouter.MODE_GRAY, Set.of(), 50, 9, false));
        long gray = java.util.stream.IntStream.range(0, 1000)
                .filter(i -> router.resolve("ns", "t" + i, 1).version() == 9)
                .count();
        // 50% 切面允许放宽（伪随机波动）
        assertTrue(gray > 400 && gray < 600, "50% 切面应近似一半，实际=" + gray);
    }

    @Test
    void 暂停灰度全量走stable() {
        ConfigGrayRouter router = new ConfigGrayRouter();
        router.upsert(new GrayRule("ns", ConfigGrayRouter.MODE_GRAY, Set.of("a"), 100, 3, false));
        router.setSuspended("ns", true);
        Resolved resolved = router.resolve("ns", "a", 2);
        assertEquals(2, resolved.version());
        assertEquals("stable", resolved.by());
        assertTrue(router.ruleOf("ns").suspended());
        // 无规则命名空间暂停拒绝
        assertThrows(IllegalArgumentException.class, () -> router.setSuspended("none", true));
    }

    @Test
    void 规则校验与轨迹() {
        assertThrows(IllegalArgumentException.class,
                () -> new GrayRule("", ConfigGrayRouter.MODE_GRAY, Set.of(), 0, 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new GrayRule("ns", "bogus", Set.of(), 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new GrayRule("ns", ConfigGrayRouter.MODE_GRAY, Set.of(), 101, 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new GrayRule("ns", ConfigGrayRouter.MODE_GRAY, Set.of(), 0, 0, false));
        ConfigGrayRouter router = new ConfigGrayRouter();
        router.upsert(new GrayRule("ns", ConfigGrayRouter.MODE_STABLE, Set.of(), 0, 0, false));
        router.remove("ns");
        // 删除规则后回退 stable 语义（返回 stableVersion）
        assertEquals(7, router.resolve("ns", "t", 7).version());
        assertTrue(!router.trail().isEmpty());
    }
}
