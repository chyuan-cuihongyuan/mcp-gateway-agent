package cn.chyuan.ai.domain.toolchain.service;

import cn.chyuan.ai.domain.toolchain.model.valobj.ToolCallRecordVO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用审计与配额（工单 0325→0335 AP5）。
 * 审计：调用留痕内存登记 + 按租户/工具/时间过滤查询；
 * 配额：租户×工具滑动窗口次数限额，超限拒绝留痕（QUOTA_REJECTED）。
 * 落库为 tool_call_log（V0021）。domain 纯函数内核。
 */
public class ToolCallAuditor {

    private final List<ToolCallRecordVO> records = new ArrayList<>();
    private final Map<String, Deque<Long>> windows = new HashMap<>();
    private final int quotaLimit;
    private final long windowMs;

    public ToolCallAuditor(int quotaLimit, long windowMs) {
        if (quotaLimit <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("限额与窗口必须为正数");
        }
        this.quotaLimit = quotaLimit;
        this.windowMs = windowMs;
    }

    /** 配额检查：租户×工具滑动窗口内次数未超限返回 true（不消耗配额） */
    public synchronized boolean checkQuota(String tenantId, String toolName, long nowMs) {
        Deque<Long> window = windows.computeIfAbsent(key(tenantId, toolName), k -> new ArrayDeque<>());
        evict(window, nowMs);
        return window.size() < quotaLimit;
    }

    /** 留痕登记（配额拒绝也留痕）；成功调用消耗配额窗口 */
    public synchronized void record(ToolCallRecordVO record) {
        if (record == null || record.getCallId() == null || record.getCallId().isBlank()) {
            throw new IllegalArgumentException("留痕ID不能为空");
        }
        records.add(record);
        if ("SUCCESS".equals(record.getStatus())) {
            Deque<Long> window = windows.computeIfAbsent(
                    key(record.getTenantId(), record.getToolName()), k -> new ArrayDeque<>());
            window.addLast(record.getAtMs());
        }
    }

    /** 查询过滤（租户/工具/时间区间均可空） */
    public synchronized List<ToolCallRecordVO> query(String tenantId, String toolName,
                                                     Long fromMs, Long toMs) {
        List<ToolCallRecordVO> out = new ArrayList<>();
        for (ToolCallRecordVO record : records) {
            if (tenantId != null && !tenantId.equals(record.getTenantId())) {
                continue;
            }
            if (toolName != null && !toolName.equals(record.getToolName())) {
                continue;
            }
            if (fromMs != null && record.getAtMs() < fromMs) {
                continue;
            }
            if (toMs != null && record.getAtMs() > toMs) {
                continue;
            }
            out.add(record);
        }
        return out;
    }

    public synchronized int totalRecords() {
        return records.size();
    }

    private void evict(Deque<Long> window, long nowMs) {
        while (!window.isEmpty() && nowMs - window.peekFirst() >= windowMs) {
            window.pollFirst();
        }
    }

    private String key(String tenantId, String toolName) {
        return (tenantId == null ? "-" : tenantId) + "|" + (toolName == null ? "-" : toolName);
    }
}
