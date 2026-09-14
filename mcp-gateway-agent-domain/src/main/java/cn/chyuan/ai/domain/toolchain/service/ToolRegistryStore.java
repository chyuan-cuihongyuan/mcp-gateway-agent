package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.RegistryEntryVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（工单 0337 AP7，治理台数据面）。
 * 导入幂等（同指纹跳过/变更指纹更新）+ 三类检索（名称前缀/标签过滤/描述关键词）+ 分页。
 * 内存内核；落库为 tool_registry（V0019）与 tool_chain（V0020）。domain 纯函数。
 */
public class ToolRegistryStore {

    private final Map<String, RegistryEntryVO> store = new LinkedHashMap<>();

    /** 注册/更新：同指纹跳过（false），新工具或指纹变更落库（true） */
    public synchronized boolean register(RegistryEntryVO entry) {
        if (entry == null || entry.getName() == null || entry.getName().isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        RegistryEntryVO existing = store.get(entry.getName());
        if (existing != null && existing.getSourceFingerprint() != null
                && existing.getSourceFingerprint().equals(entry.getSourceFingerprint())) {
            return false;
        }
        store.put(entry.getName(), RegistryEntryVO.builder()
                .name(entry.getName())
                .description(entry.getDescription())
                .parameterSchemaJson(entry.getParameterSchemaJson())
                .tags(entry.getTags())
                .sourceFingerprint(entry.getSourceFingerprint())
                .status(entry.getStatus() == null ? "ACTIVE" : entry.getStatus())
                .updatedAtMs(entry.getUpdatedAtMs())
                .build());
        return true;
    }

    /** 检索：名称前缀 / 标签过滤 / 描述关键词（LIKE），名称序稳定 + 分页 */
    public synchronized List<RegistryEntryVO> search(String namePrefix, String tag,
                                                     String keyword, int page, int size) {
        if (page <= 0 || size <= 0) {
            throw new IllegalArgumentException("分页参数必须为正数");
        }
        List<RegistryEntryVO> matched = new ArrayList<>();
        for (RegistryEntryVO entry : store.values()) {
            if (namePrefix != null && !namePrefix.isEmpty()
                    && !entry.getName().startsWith(namePrefix)) {
                continue;
            }
            if (tag != null && !tag.isEmpty()
                    && (entry.getTags() == null || !List.of(entry.getTags().split(",")).contains(tag))) {
                continue;
            }
            if (keyword != null && !keyword.isEmpty()
                    && (entry.getDescription() == null || !entry.getDescription().contains(keyword))) {
                continue;
            }
            matched.add(entry);
        }
        matched.sort(Comparator.comparing(RegistryEntryVO::getName));
        int from = Math.min((page - 1) * size, matched.size());
        int to = Math.min(from + size, matched.size());
        return List.copyOf(matched.subList(from, to));
    }

    public synchronized RegistryEntryVO find(String name) {
        return store.get(name);
    }

    public synchronized int size() {
        return store.size();
    }
}
