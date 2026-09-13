package cn.chyuan.ai.domain.configcenter.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置 bundle 编解码（工单 0259 AG9，借鉴 Terraform 状态导出）—
 * 命名空间配置集（键/当前版本/内容/敏感标记）+ 校验和导出为确定性 JSON；
 * 导入先验校验和 → 冲突策略 skip/overwrite → 报告计数。
 * 敏感项导出保持密文（enc-v1:*）不解密出站。
 *
 * @author chyuan
 */
public final class ConfigBundleCodec {

    public static final String CONFLICT_SKIP = "skip";
    public static final String CONFLICT_OVERWRITE = "overwrite";

    /** 导出条目（content 对敏感项为密文） */
    public record BundleEntry(String configKey, int version, String content,
            String contentMd5, boolean sensitive) {
    }

    /** bundle：命名空间 + 条目集 + 校验和（对规范化载荷 SHA-256） */
    public record ConfigBundle(String namespace, List<BundleEntry> entries, String checksum) {
    }

    /** 导入报告 */
    public record ImportReport(int added, int overwritten, int skipped, List<String> errors) {

        public boolean success() {
            return errors.isEmpty();
        }
    }

    private ConfigBundleCodec() {
    }

    /** 导出 bundle（条目按键排序，确定性；checksum 对规范化载荷计算） */
    public static ConfigBundle export(String namespace, List<BundleEntry> entries) {
        List<BundleEntry> sorted = entries.stream()
                .sorted(java.util.Comparator.comparing(BundleEntry::configKey))
                .toList();
        String canonical = canonical(namespace, sorted);
        return new ConfigBundle(namespace, List.copyOf(sorted), sha256(canonical));
    }

    /** bundle 序列化（确定性键序 JSON） */
    public static String toJson(ConfigBundle bundle) {
        JSONObject root = new JSONObject(true);
        root.put("namespace", bundle.namespace());
        JSONArray arr = new JSONArray();
        for (BundleEntry entry : bundle.entries()) {
            JSONObject item = new JSONObject(true);
            item.put("configKey", entry.configKey());
            item.put("version", entry.version());
            item.put("content", entry.content());
            item.put("contentMd5", entry.contentMd5());
            item.put("sensitive", entry.sensitive());
            arr.add(item);
        }
        root.put("entries", arr);
        root.put("checksum", bundle.checksum());
        return root.toJSONString();
    }

    /** 反序列化（结构非法抛 IllegalArgumentException；checksum 不校验——由 import 显式校验） */
    public static ConfigBundle fromJson(String json) {
        try {
            JSONObject root = JSON.parseObject(json);
            String namespace = root.getString("namespace");
            String checksum = root.getString("checksum");
            if (namespace == null || namespace.isBlank() || checksum == null || checksum.isBlank()) {
                throw new IllegalArgumentException("bundle 缺 namespace/checksum");
            }
            List<BundleEntry> entries = new ArrayList<>();
            JSONArray arr = root.getJSONArray("entries");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    entries.add(new BundleEntry(item.getString("configKey"),
                            item.getIntValue("version"), item.getString("content"),
                            item.getString("contentMd5"), item.getBooleanValue("sensitive")));
                }
            }
            return new ConfigBundle(namespace, List.copyOf(entries), checksum);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("bundle JSON 非法: " + e.getMessage());
        }
    }

    /**
     * 导入：校验和验证（不符拒绝）→ 逐条按冲突策略处理。
     * existing = 当前键 → 内容视图；loader 落库由调用方回调执行（codec 纯函数不做 IO）。
     * action: "add" / "overwrite" / "skip"
     */
    public static ImportReport importBundle(ConfigBundle bundle, Map<String, String> existing,
            String conflictPolicy, java.util.function.BiConsumer<String, BundleEntry> action) {
        String canonical = canonical(bundle.namespace(), sortedEntries(bundle.entries()));
        if (!sha256(canonical).equals(bundle.checksum())) {
            return new ImportReport(0, 0, 0, List.of("校验和不匹配，bundle 已被篡改"));
        }
        if (!CONFLICT_SKIP.equals(conflictPolicy) && !CONFLICT_OVERWRITE.equals(conflictPolicy)) {
            return new ImportReport(0, 0, 0, List.of("非法冲突策略: " + conflictPolicy));
        }
        int added = 0;
        int overwritten = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (BundleEntry entry : sortedEntries(bundle.entries())) {
            String current = existing.get(entry.configKey());
            if (current == null) {
                action.accept("add", entry);
                added++;
            } else if (current.equals(entry.content())) {
                action.accept("skip", entry);
                skipped++;
            } else if (CONFLICT_OVERWRITE.equals(conflictPolicy)) {
                action.accept("overwrite", entry);
                overwritten++;
            } else {
                action.accept("skip", entry);
                skipped++;
            }
        }
        return new ImportReport(added, overwritten, skipped, List.copyOf(errors));
    }

    /** 规范化载荷（键排序 + 固定行格式，供 checksum 计算/校验） */
    static String canonical(String namespace, List<BundleEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("namespace=").append(namespace).append('\n');
        for (BundleEntry entry : entries) {
            sb.append(entry.configKey()).append('#').append(entry.version())
                    .append('#').append(entry.sensitive() ? '1' : '0')
                    .append('#').append(entry.contentMd5() == null ? "" : entry.contentMd5())
                    .append('#').append(entry.content() == null ? "" : entry.content())
                    .append('\n');
        }
        return sb.toString();
    }

    private static List<BundleEntry> sortedEntries(List<BundleEntry> entries) {
        List<BundleEntry> sorted = new ArrayList<>(entries);
        sorted.sort(java.util.Comparator.comparing(BundleEntry::configKey));
        return sorted;
    }

    static String sha256(String data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 键序确定 Map（导出方便利） */
    static Map<String, String> sortedCopy(Map<String, String> map) {
        return new TreeMap<>(map);
    }
}
