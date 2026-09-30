package cn.chyuan.ai.domain.objkernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录列举（工单 1133 EW7，minio 思想）。
 * 前缀列举字典序/delimiter 折叠公共前缀/maxKeys 分页 isTruncated/空桶空列表。
 */
public final class ObjectListing {

    /** 列举结果：对象 key + 折叠公共前缀 + 是否截断 */
    public record Result(List<String> keys, List<String> commonPrefixes, boolean isTruncated, String nextMarker) {
    }

    private ObjectListing() {
    }

    /** 列举：keys 字典序（防御性排序）；delimiter 非空时同前缀段折叠为一条公共前缀；maxKeys 钳制并给出截断标记 */
    public static Result list(List<String> sortedKeys, String prefix, String delimiter, int maxKeys) {
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys 必须为正: " + maxKeys);
        }
        List<String> ordered = sortedKeys.stream().sorted().toList();
        List<String> keys = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        int budget = maxKeys;
        for (String key : ordered) {
            if (prefix != null && !prefix.isEmpty() && !key.startsWith(prefix)) {
                continue;
            }
            if (delimiter != null && !delimiter.isEmpty()) {
                int index = key.indexOf(delimiter, prefix == null ? 0 : prefix.length());
                if (index >= 0) {
                    String commonPrefix = key.substring(0, index + delimiter.length());
                    if (!prefixes.contains(commonPrefix)) {
                        if (budget == 0) {
                            return new Result(List.copyOf(keys), List.copyOf(prefixes), true, lastOf(keys, prefixes));
                        }
                        prefixes.add(commonPrefix);
                        budget--;
                    }
                    continue;
                }
            }
            if (budget == 0) {
                return new Result(List.copyOf(keys), List.copyOf(prefixes), true, lastOf(keys, prefixes));
            }
            keys.add(key);
            budget--;
        }
        return new Result(List.copyOf(keys), List.copyOf(prefixes), false, null);
    }

    private static String lastOf(List<String> keys, List<String> prefixes) {
        if (!prefixes.isEmpty()) {
            return prefixes.get(prefixes.size() - 1);
        }
        return keys.isEmpty() ? null : keys.get(keys.size() - 1);
    }
}
