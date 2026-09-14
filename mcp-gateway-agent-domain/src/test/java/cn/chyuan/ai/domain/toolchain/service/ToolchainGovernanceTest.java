package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.DependencyReportVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.RegistryEntryVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.ToolCallRecordVO;
import cn.chyuan.ai.domain.toolchain.model.valobj.ToolHealthVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP5-AP8 单测：审计配额/依赖图环检测/注册表检索/健康度统计。
 */
class ToolchainGovernanceTest {

    // ── AP5 审计与配额 ──

    @Test
    void 留痕登记与多维查询() {
        ToolCallAuditor auditor = new ToolCallAuditor(3, 1000);
        auditor.record(call("c1", "t1", "search", "SUCCESS", 1));
        auditor.record(call("c2", "t2", "search", "SUCCESS", 2));
        auditor.record(call("c3", "t1", "deploy", "ERROR", 3));
        assertEquals(3, auditor.totalRecords());
        assertEquals(2, auditor.query("t1", null, null, null).size());
        assertEquals(2, auditor.query(null, "search", null, null).size());
        assertEquals(1, auditor.query("t1", "deploy", null, null).size());
        assertEquals(1, auditor.query(null, null, 3L, null).size());
        assertEquals(2, auditor.query(null, null, null, 2L).size());
        assertThrows(IllegalArgumentException.class, () -> auditor.record(call(" ", "t", "x", "ERROR", 1)));
    }

    @Test
    void 滑动窗口限额命中与恢复() {
        ToolCallAuditor auditor = new ToolCallAuditor(2, 100);
        assertTrue(auditor.checkQuota("t1", "search", 1000));
        auditor.record(call("c1", "t1", "search", "SUCCESS", 1000));
        assertTrue(auditor.checkQuota("t1", "search", 1050));
        auditor.record(call("c2", "t1", "search", "SUCCESS", 1050));
        assertFalse(auditor.checkQuota("t1", "search", 1099), "窗口内第 3 次应超限");
        assertTrue(auditor.checkQuota("t1", "deploy", 1100), "不同工具独立限额");
        // 窗口滑过（>100ms 后）恢复
        assertTrue(auditor.checkQuota("t1", "search", 1200));
        // 配额拒绝留痕不消耗窗口（ERROR/QUOTA_REJECTED 不计入窗口）
        auditor.record(call("c3", "t1", "search", "QUOTA_REJECTED", 1200));
        assertEquals(1, auditor.query(null, null, null, null).stream()
                .filter(r -> "QUOTA_REJECTED".equals(r.getStatus())).count());
    }

    // ── AP6 依赖图 ──

    @Test
    void 拓扑序稳定与缺失依赖补节点() {
        ToolDependencyGraph graph = new ToolDependencyGraph();
        DependencyReportVO report = graph.analyze(Map.of(
                "c", List.of("a", "b"),
                "b", List.of("a"),
                "d", List.of("ghost")));
        assertFalse(report.isCyclic());
        // a 无依赖最先；b、ghost 依赖 a；c 依赖 a、b；d 依赖 ghost —— 全节点覆盖
        assertEquals(List.of("a", "b", "c", "d", "ghost"), report.getTopologicalOrder().stream().sorted().toList());
        assertTrue(report.getMissingDependencies().contains("ghost"), "缺失依赖自动补节点");
    }

    @Test
    void 环检测自环二环长环() {
        ToolDependencyGraph graph = new ToolDependencyGraph();
        assertTrue(graph.analyze(Map.of("a", List.of("a"))).isCyclic(), "自环");
        DependencyReportVO two = graph.analyze(Map.of("a", List.of("b"), "b", List.of("a")));
        assertTrue(two.isCyclic());
        assertTrue(two.getTopologicalOrder().isEmpty(), "全环节点成环时 Kahn 部分序为空");
        DependencyReportVO chain = graph.analyze(Map.of(
                "a", List.of("b"), "b", List.of("c"), "c", List.of("a")));
        assertTrue(chain.isCyclic());
        assertFalse(chain.getCyclePath().isEmpty(), "应报告环路径");
    }

    @Test
    void 空依赖图() {
        ToolDependencyGraph graph = new ToolDependencyGraph();
        DependencyReportVO empty = graph.analyze(Map.of());
        assertFalse(empty.isCyclic());
        assertTrue(empty.getTopologicalOrder().isEmpty());
    }

    // ── AP7 注册表 ──

    @Test
    void 注册幂等与三类检索() {
        ToolRegistryStore store = new ToolRegistryStore();
        assertTrue(store.register(entry("getUser", "查询用户", "user,admin", "fp-1")));
        assertFalse(store.register(entry("getUser", "查询用户", "user,admin", "fp-1")), "同指纹跳过");
        assertTrue(store.register(entry("getUser", "查询用户 V2", "user,admin", "fp-2")), "指纹变更更新");
        store.register(entry("createOrder", "创建订单", "order", "fp-3"));
        store.register(entry("deleteUser", "删除用户数据", "user", "fp-4"));
        assertEquals(3, store.size());
        // 名称前缀
        assertEquals(1, store.search("getUser", null, null, 1, 10).size());
        assertEquals(1, store.search("get", null, null, 1, 10).size(), "仅 getUser 以 get 开头");
        // 标签过滤
        assertEquals(2, store.search(null, "user", null, 1, 10).size());
        // 描述关键词
        assertEquals(1, store.search(null, null, "订单", 1, 10).size());
        // 分页
        assertEquals(2, store.search(null, null, null, 1, 2).size());
        assertEquals(1, store.search(null, null, null, 2, 2).size());
        assertThrows(IllegalArgumentException.class, () -> store.search(null, null, null, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> store.register(entry(" ", "x", "", "f")));
    }

    private RegistryEntryVO entry(String name, String description, String tags, String fingerprint) {
        return RegistryEntryVO.builder()
                .name(name).description(description).tags(tags)
                .sourceFingerprint(fingerprint).status("ACTIVE").updatedAtMs(1L)
                .build();
    }

    // ── AP8 健康度 ──

    @Test
    void 错误率分位数与评级() {
        ToolHealthStats stats = new ToolHealthStats(0.1, 0.3, 5);
        List<ToolCallRecordVO> records = List.of(
                timed("c1", "t", "search", "SUCCESS", 100, 100),
                timed("c2", "t", "search", "SUCCESS", 200, 200),
                timed("c3", "t", "search", "ERROR", 300, 300),
                timed("c4", "t", "search", "SUCCESS", 400, 400),
                timed("c5", "t", "search", "TIMEOUT", 500, 500),
                timed("c6", "t", "search", "SUCCESS", 600, 600),
                timed("c7", "t", "search", "SUCCESS", 700, 700),
                timed("c8", "t", "search", "SUCCESS", 800, 800),
                timed("c9", "t", "search", "SUCCESS", 900, 900),
                timed("c10", "t", "search", "SUCCESS", 1000, 1000));
        ToolHealthVO health = stats.evaluate("search", records, null, null, 5);
        assertEquals(10, health.getTotalCalls());
        assertEquals(0.2, health.getErrorRate(), 1e-9);
        assertEquals(500, health.getP50Ms(), "P50 为第 5 个（ceil(0.5*10)）");
        assertEquals(1000, health.getP95Ms(), "P95 为第 10 个（ceil(0.95*10)）");
        assertEquals("Degraded", health.getGrade(), "0.2 ∈ [0.1, 0.3)");
        assertEquals(2, health.getRecentFailures().size(), "最近失败限量 5 内实际 2 条");
    }

    @Test
    void 评级阈值边界与样本下限() {
        ToolHealthStats stats = new ToolHealthStats(0.1, 0.3, 5);
        assertEquals("Healthy", stats.grade(0.05, 100));
        assertEquals("Degraded", stats.grade(0.1, 100));
        assertEquals("Unhealthy", stats.grade(0.3, 100));
        assertEquals("Healthy", stats.grade(0.9, 4), "样本不足不误伤");
        assertEquals(0, ToolHealthStats.percentile(List.of(), 0.5), "空延迟为 0");
        // 时间窗过滤
        ToolHealthStats stats2 = new ToolHealthStats(0.1, 0.3, 2);
        List<ToolCallRecordVO> records = List.of(
                call("c1", "t", "search", "ERROR", 100),
                call("c2", "t", "search", "SUCCESS", 5000));
        assertEquals(0.0, stats2.evaluate("search", records, 4000L, null, 5).getErrorRate(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new ToolHealthStats(0, 0.3, 5));
        assertThrows(IllegalArgumentException.class, () -> new ToolHealthStats(0.3, 0.1, 5));
    }

    private ToolCallRecordVO call(String id, String tenant, String tool, String status, long atMs) {
        return call(id, tenant, tool, status, atMs, atMs % 100);
    }

    private ToolCallRecordVO timed(String id, String tenant, String tool, String status, long atMs, long costMs) {
        return call(id, tenant, tool, status, atMs, costMs);
    }

    private ToolCallRecordVO call(String id, String tenant, String tool, String status, long atMs, long costMs) {
        return ToolCallRecordVO.builder()
                .callId(id).tenantId(tenant).toolName(tool)
                .paramSummary("摘要").status(status).costMs(costMs)
                .tokenEstimate(10).atMs(atMs)
                .build();
    }
}
