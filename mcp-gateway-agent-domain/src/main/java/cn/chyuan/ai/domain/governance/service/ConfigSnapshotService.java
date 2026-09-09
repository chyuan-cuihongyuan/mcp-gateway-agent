package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IConfigSnapshotPort;
import cn.chyuan.ai.types.enums.McpErrorCodes;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 配置快照服务（工单 0077：治理配置导出/导入）
 *
 * <p>导出：全量治理配置 JSON 快照（schemaVersion 版本化）；密钥行只含元数据——
 * 哈希与明文绝不出库（导入侧钥匙需重新签发）。外部挂接与 LLM 渠道凭证以密文
 * 原样导出，跨环境导入要求 GOVERNANCE_ENC_KEY 一致（docs 口径）。
 *
 * <p>导入：先整体校验（schemaVersion/结构），逐对象按自然键 upsert（幂等——
 * 重复导入不重复创建）；dryRun 只算差异预览不落库；全程审计。
 *
 * @author chyuan
 */
@Slf4j
@Service
public class ConfigSnapshotService {

    public static final int SCHEMA_VERSION = 1;

    public static final String S_GATEWAYS = "gateways";
    public static final String S_TOOLS = "tools";
    public static final String S_PROTOCOLS = "protocolHttp";
    public static final String S_VK = "virtualKeys";
    public static final String S_CEL_RULES = "celRules";
    public static final String S_CEL_TEMPLATES = "celTemplates";
    public static final String S_ATTACHES = "externalAttaches";
    public static final String S_LLM = "llmChannels";
    public static final String S_WEBHOOKS = "webhookEndpoints";

    /** 全部存储（快照内固定顺序，导入应用序 = 声明序：协议先于工具） */
    static final List<String> ALL_STORES = List.of(
            S_PROTOCOLS, S_GATEWAYS, S_TOOLS, S_CEL_TEMPLATES, S_CEL_RULES,
            S_ATTACHES, S_LLM, S_WEBHOOKS, S_VK);

    /** 只导出不导入的存储（密钥：哈希/明文不出库 → 导入侧需重发） */
    static final Set<String> EXPORT_ONLY = Set.of(S_VK);

    /** 密钥行的禁出字段（双保险：实现侧已不产出，领域侧再剥离一次） */
    private static final Set<String> VK_FORBIDDEN_FIELDS = Set.of(
            "apiKeyHash", "prevKeyHash", "plaintext", "plaintextOnce", "graceUntil", "lastActiveAt");

    @Resource
    private IConfigSnapshotPort port;

    @Resource
    private IAuditService auditService;

    /** 导出全量快照（脱敏后） */
    public Map<String, Object> export() {
        Map<String, Object> current = port.readCurrent();
        scrub(current);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", SCHEMA_VERSION);
        snapshot.put("exportedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        snapshot.put("stores", current);
        return snapshot;
    }

    /**
     * 导入快照。
     *
     * @param target 目标快照（结构同 export() 产出）
     * @param dryRun true=只算差异预览不落库
     * @return 结果：created/updated/unchanged 按存储计数 + skipped/warnings 说明
     */
    public Map<String, Object> importSnapshot(Map<String, Object> target, boolean dryRun) {
        validate(target);
        @SuppressWarnings("unchecked")
        Map<String, Object> targetStores = (Map<String, Object>) target.get("stores");
        Map<String, Object> currentStores = port.readCurrent();
        scrub(currentStores);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        Map<String, Integer> created = new TreeMap<>();
        Map<String, Integer> updated = new TreeMap<>();
        Map<String, Integer> unchanged = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String store : ALL_STORES) {
            List<Map<String, Object>> rows = rowsOf(targetStores, store);
            if (rows == null) {
                continue;
            }
            if (EXPORT_ONLY.contains(store)) {
                skipped.add(store + ": " + rows.size() + " 行仅导出不导入（密钥哈希与明文不出库，导入侧需重新签发）");
                continue;
            }
            Map<String, Map<String, Object>> currentByKey = indexByNaturalKey(store, currentStores);
            int c = 0;
            int u = 0;
            int n = 0;
            for (Map<String, Object> row : rows) {
                String key = naturalKey(store, row);
                if (key == null) {
                    warnings.add(store + ": 存在缺少自然键字段的行，已跳过");
                    continue;
                }
                Map<String, Object> existing = currentByKey.get(key);
                try {
                    if (existing == null) {
                        if (!dryRun) {
                            port.upsert(store, row);
                        }
                        c++;
                    } else if (!normalized(row).equals(normalized(existing))) {
                        if (!dryRun) {
                            port.upsert(store, row);
                        }
                        u++;
                    } else {
                        n++;
                    }
                } catch (Exception e) {
                    warnings.add(store + "[" + key + "]: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
            if (c > 0) {
                created.put(store, c);
            }
            if (u > 0) {
                updated.put(store, u);
            }
            if (n > 0) {
                unchanged.put(store, n);
            }
        }
        result.put("created", created);
        result.put("updated", updated);
        result.put("unchanged", unchanged);
        result.put("skipped", skipped);
        result.put("warnings", warnings);

        if (!dryRun) {
            int total = created.values().stream().mapToInt(Integer::intValue).sum()
                    + updated.values().stream().mapToInt(Integer::intValue).sum();
            auditService.record(cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity.builder()
                    .actor("admin")
                    .action("CONFIG_IMPORT")
                    .resourceType("CONFIG_SNAPSHOT")
                    .resourceId("schemaVersion=" + target.get("schemaVersion"))
                    .afterJson("{\"applied\":" + total + ",\"created\":" + created + ",\"updated\":" + updated + "}")
                    .build());
        }
        return result;
    }

    /** 结构校验：schemaVersion 支持域 + stores 必须是对象 */
    private void validate(Map<String, Object> target) {
        if (target == null || !(target.get("schemaVersion") instanceof Integer version) || version != SCHEMA_VERSION) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS,
                    "快照结构非法或不支持的 schemaVersion（当前支持 " + SCHEMA_VERSION + "）");
        }
        Object stores = target.get("stores");
        if (!(stores instanceof Map)) {
            throw new AppException(McpErrorCodes.INVALID_PARAMS, "快照缺少 stores 结构");
        }
    }

    /** 脱敏：密钥行剥离哈希/明文类字段；模板只保留自建（内置由种子幂等保证） */
    private void scrub(Map<String, Object> stores) {
        Object vkRows = stores.get(S_VK);
        if (vkRows instanceof List<?> keys) {
            for (Object row : keys) {
                if (row instanceof Map<?, ?> map) {
                    VK_FORBIDDEN_FIELDS.forEach(f -> map.remove(f));
                }
            }
        }
        Object templates = stores.get(S_CEL_TEMPLATES);
        if (templates instanceof List<?> list) {
            list.removeIf(row -> row instanceof Map<?, ?> map && Integer.valueOf(1).equals(map.get("builtin")));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> stores, String store) {
        Object rows = stores.get(store);
        return rows instanceof List ? (List<Map<String, Object>>) rows : null;
    }

    /** 自然键：每存储的稳定业务标识（跨环境可用） */
    static String naturalKey(String store, Map<String, Object> row) {
        return switch (store) {
            case S_GATEWAYS -> str(row, "gatewayId");
            case S_TOOLS -> str(row, "gatewayId") == null ? null : str(row, "gatewayId") + "|" + str(row, "toolName");
            case S_PROTOCOLS -> str(row, "httpUrl") == null ? null : str(row, "httpUrl") + "|" + str(row, "httpMethod");
            case S_VK -> str(row, "keyName");
            case S_CEL_RULES -> str(row, "ruleName") == null ? null
                    : str(row, "ruleName") + "|" + str(row, "scopeType") + "|" + nz(str(row, "gatewayId"))
                            + "|" + nz(row.get("virtualKeyId") == null ? null : String.valueOf(row.get("virtualKeyId")));
            case S_CEL_TEMPLATES -> str(row, "code");
            case S_ATTACHES -> str(row, "gatewayId") == null ? null : str(row, "gatewayId") + "|" + str(row, "attachName");
            case S_LLM -> str(row, "name");
            case S_WEBHOOKS -> str(row, "url");
            default -> null;
        };
    }

    private static Map<String, Map<String, Object>> indexByNaturalKey(String store, Map<String, Object> stores) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        List<Map<String, Object>> rows = rowsOf(stores, store);
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                String key = naturalKey(store, row);
                if (key != null) {
                    index.put(key, row);
                }
            }
        }
        return index;
    }

    /** 归一化比较形态：剥离 null 值（导出侧省略与显式 null 等价） */
    private static Map<String, Object> normalized(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static String str(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
