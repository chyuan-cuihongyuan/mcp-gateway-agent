package cn.chyuan.ai.domain.configcenter.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 配置 plan 预览三态 diff（工单 0252 AG2，借鉴 Terraform plan）—
 * 当前值 vs 期望值展开为扁平键路径后行级比对：UNCHANGED/ADDED/REMOVED/CHANGED，
 * 附风险提示（删除键/变更占比超半）。纯函数无副作用，diff 不落库不生效。
 *
 * @author chyuan
 */
public final class ConfigPlanDiffer {

    public static final String UNCHANGED = "UNCHANGED";
    public static final String ADDED = "ADDED";
    public static final String REMOVED = "REMOVED";
    public static final String CHANGED = "CHANGED";

    /** 变更占比风险阈值（变更行/总行 > 0.5 视为大变更） */
    public static final double RISK_CHANGE_RATIO = 0.5;

    /** 单行 diff：path 键路径，current/incoming 为规范化字符串值 */
    public record PlanRow(String path, String current, String incoming, String change) {
    }

    /** 整份 diff 结果：行集 + 风险提示 + 分类计数 */
    public record PlanDiff(List<PlanRow> rows, List<String> risks,
            int added, int removed, int changed, int unchanged) {

        public boolean hasRisk() {
            return !risks.isEmpty();
        }

        public int total() {
            return rows.size();
        }
    }

    private ConfigPlanDiffer() {
    }

    /** diff 当前 JSON 与期望 JSON（两参均可为 "{}" 空对象；非法 JSON 抛 IllegalArgumentException） */
    public static PlanDiff diff(String currentJson, String incomingJson) {
        Map<String, String> current = flatten(currentJson);
        Map<String, String> incoming = flatten(incomingJson);

        List<PlanRow> rows = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        int unchanged = 0;
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String path = entry.getKey();
            if (!current.containsKey(path)) {
                rows.add(new PlanRow(path, null, entry.getValue(), ADDED));
                added++;
            } else if (!Objects.equals(current.get(path), entry.getValue())) {
                rows.add(new PlanRow(path, current.get(path), entry.getValue(), CHANGED));
                changed++;
            } else {
                rows.add(new PlanRow(path, current.get(path), entry.getValue(), UNCHANGED));
                unchanged++;
            }
        }
        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (!incoming.containsKey(entry.getKey())) {
                rows.add(new PlanRow(entry.getKey(), entry.getValue(), null, REMOVED));
                removed++;
            }
        }
        rows.sort((a, b) -> {
            int byChange = rank(a.change()) - rank(b.change());
            return byChange != 0 ? byChange : a.path().compareTo(b.path());
        });

        List<String> risks = new ArrayList<>();
        if (removed > 0) {
            risks.add("将删除 " + removed + " 个配置键");
        }
        int touched = added + removed + changed;
        if (!rows.isEmpty() && (double) touched / rows.size() > RISK_CHANGE_RATIO) {
            risks.add("变更占比超过 50%（" + touched + "/" + rows.size() + "）");
        }
        return new PlanDiff(List.copyOf(rows), List.copyOf(risks), added, removed, changed, unchanged);
    }

    /** JSON 对象展开为扁平键路径 → 规范化字符串值（嵌套以 . 连接，树序确定） */
    public static Map<String, String> flatten(String json) {
        if (json == null || json.isBlank()) {
            json = "{}";
        }
        JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("配置不是合法 JSON 对象: " + e.getMessage());
        }
        if (root == null) {
            throw new IllegalArgumentException("配置不是合法 JSON 对象");
        }
        Map<String, String> flat = new TreeMap<>();
        flattenInto(root, "", flat);
        return flat;
    }

    private static void flattenInto(JSONObject node, String prefix, Map<String, String> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof JSONObject nested) {
                flattenInto(nested, path, out);
                if (nested.isEmpty()) {
                    out.put(path, "{}");
                }
            } else {
                out.put(path, String.valueOf(value));
            }
        }
    }

    private static int rank(String change) {
        return switch (change) {
            case CHANGED -> 0;
            case ADDED -> 1;
            case REMOVED -> 2;
            default -> 3;
        };
    }
}
