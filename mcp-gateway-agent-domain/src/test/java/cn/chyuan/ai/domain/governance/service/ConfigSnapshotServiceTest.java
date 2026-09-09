package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IConfigSnapshotPort;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置快照服务测试（工单 0077）：脱敏 / 幂等差异 / dry-run / 审计
 */
@DisplayName("配置快照服务测试")
class ConfigSnapshotServiceTest {

    private IConfigSnapshotPort port;
    private IAuditService auditService;
    private ConfigSnapshotService service;

    /** 内存 fake 端口：readCurrent 返回可变底稿；upsert 就地应用 */
    private Map<String, Object> current;

    @BeforeEach
    void setUp() {
        port = Mockito.mock(IConfigSnapshotPort.class);
        auditService = Mockito.mock(IAuditService.class);
        service = new ConfigSnapshotService();
        // 依赖经 @Resource 注入：测试以反射塞入（与仓内既有服务测试同风格）
        try {
            for (java.lang.reflect.Field field : ConfigSnapshotService.class.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == IConfigSnapshotPort.class) {
                    field.set(service, port);
                } else if (field.getType() == IAuditService.class) {
                    field.set(service, auditService);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }

        current = new LinkedHashMap<>();
        current.put(ConfigSnapshotService.S_GATEWAYS, new ArrayList<>(List.of(
                row("gatewayId", "gateway_001", "gatewayName", "默认网关", "status", 1))));
        current.put(ConfigSnapshotService.S_LLM, new ArrayList<>(List.of(
                row("name", "deepseek-main", "baseUrl", "https://api.deepseek.com", "models", "deepseek-v4-pro"))));
        current.put(ConfigSnapshotService.S_VK, new ArrayList<>(List.of(
                row("keyName", "旧密钥", "apiKeyHash", "sha256:abc", "rpmLimit", 60))));
        current.put(ConfigSnapshotService.S_CEL_TEMPLATES, new ArrayList<>(List.of(
                row("code", "builtin-tool-whitelist", "builtin", 1),
                row("code", "custom-x", "builtin", 0))));
        Mockito.when(port.readCurrent()).thenAnswer(invocation -> deepCopy(current));
        Mockito.when(port.upsert(Mockito.anyString(), Mockito.anyMap()))
                .thenAnswer(invocation -> {
                    String store = invocation.getArgument(0);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = invocation.getArgument(1);
                    ((List<Map<String, Object>>) current.get(store)).add(deepCopyRow(row));
                    return "created";
                });
    }

    @Test
    @DisplayName("导出：schemaVersion 版本化 + 密钥哈希剥离 + 内置模板不出")
    void exportScrubsSecrets() {
        Map<String, Object> snapshot = service.export();

        Assertions.assertEquals(ConfigSnapshotService.SCHEMA_VERSION, snapshot.get("schemaVersion"));
        Assertions.assertNotNull(snapshot.get("exportedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stores = (Map<String, Object>) snapshot.get("stores");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) stores.get(ConfigSnapshotService.S_VK);
        Assertions.assertFalse(keys.get(0).containsKey("apiKeyHash"), "密钥行不得含哈希");
        Assertions.assertTrue(keys.get(0).containsKey("rpmLimit"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> templates = (List<Map<String, Object>>) stores.get(ConfigSnapshotService.S_CEL_TEMPLATES);
        Assertions.assertEquals(1, templates.size(), "内置模板不随快照导出");
        Assertions.assertEquals("custom-x", templates.get(0).get("code"));
    }

    @Test
    @DisplayName("导入 dry-run：新增/更新/未变化三分 + 密钥存储跳过说明 + 不落库")
    void dryRunDiff() {
        Map<String, Object> target = snapshotOf(
                row("gatewayId", "gateway_001", "gatewayName", "改名网关", "status", 1), // 更新
                row("gatewayId", "gateway_002", "gatewayName", "新网关", "status", 1));  // 新增
        addStore(target, ConfigSnapshotService.S_LLM,
                row("name", "deepseek-main", "baseUrl", "https://api.deepseek.com", "models", "deepseek-v4-pro")); // 未变化
        addStore(target, ConfigSnapshotService.S_VK, row("keyName", "旧密钥", "rpmLimit", 60)); // 跳过

        Map<String, Object> result = service.importSnapshot(target, true);

        Assertions.assertEquals(Boolean.TRUE, result.get("dryRun"));
        Assertions.assertEquals(Map.of(ConfigSnapshotService.S_GATEWAYS, 1), result.get("created"));
        Assertions.assertEquals(Map.of(ConfigSnapshotService.S_GATEWAYS, 1), result.get("updated"));
        Assertions.assertEquals(Map.of(ConfigSnapshotService.S_LLM, 1), result.get("unchanged"));
        Assertions.assertFalse(((List<?>) result.get("skipped")).isEmpty(), "密钥存储应有跳过说明");
        Mockito.verify(port, Mockito.never()).upsert(Mockito.anyString(), Mockito.anyMap());
        Mockito.verify(auditService, Mockito.never()).record(Mockito.any());
    }

    @Test
    @DisplayName("正式导入：逐对象 upsert 落库 + 审计 CONFIG_IMPORT")
    void applyImport() {
        Map<String, Object> target = snapshotOf(
                row("gatewayId", "gateway_002", "gatewayName", "新网关", "status", 1));

        Map<String, Object> result = service.importSnapshot(target, false);

        Assertions.assertEquals(Map.of(ConfigSnapshotService.S_GATEWAYS, 1), result.get("created"));
        ArgumentCaptor<AuditCommandEntity> captor = ArgumentCaptor.forClass(AuditCommandEntity.class);
        Mockito.verify(auditService).record(captor.capture());
        Assertions.assertEquals("CONFIG_IMPORT", captor.getValue().getAction());
        // 幂等：同一快照二次导入全部命中未变化
        Map<String, Object> second = service.importSnapshot(target, false);
        Assertions.assertEquals(Map.of(), second.get("created"));
        Assertions.assertEquals(Map.of(), second.get("updated"));
        Assertions.assertEquals(Map.of(ConfigSnapshotService.S_GATEWAYS, 1), second.get("unchanged"));
    }

    @Test
    @DisplayName("结构校验：缺 schemaVersion 或不支持版本即拒绝")
    void validateStructure() {
        Assertions.assertThrows(AppException.class, () -> service.importSnapshot(Map.of(), false));
        Assertions.assertThrows(AppException.class,
                () -> service.importSnapshot(Map.of("schemaVersion", 99, "stores", Map.of()), false));
    }

    // ---------- 测试脚手架 ----------

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> snapshotOf(Map<String, Object>... gatewayRows) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", ConfigSnapshotService.SCHEMA_VERSION);
        Map<String, Object> stores = new LinkedHashMap<>();
        snapshot.put("stores", stores);
        stores.put(ConfigSnapshotService.S_GATEWAYS, new ArrayList<>(List.of(gatewayRows)));
        return snapshot;
    }

    private static void addStore(Map<String, Object> snapshot, String store, Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        Map<String, Object> stores = (Map<String, Object>) snapshot.get("stores");
        stores.put(store, new ArrayList<>(List.of(row)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() instanceof List<?> list) {
                List<Object> rows = new ArrayList<>();
                for (Object item : list) {
                    rows.add(item instanceof Map ? deepCopyRow((Map<String, Object>) item) : item);
                }
                copy.put(entry.getKey(), rows);
            } else {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static Map<String, Object> deepCopyRow(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }
}
