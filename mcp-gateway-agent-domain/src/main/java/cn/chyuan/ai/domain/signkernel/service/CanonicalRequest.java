package cn.chyuan.ai.domain.signkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * canonical request（工单 1013 EJ1，minio SigV4 思想）。
 * 方法路径规范化/查询按键排序/头小写排序同名合并/重复值保留序。
 */
public final class CanonicalRequest {

    private CanonicalRequest() {
    }

    /** 构造规范请求：METHOD\npath\n排序查询\n排序头\n签名头清单\n载荷哈希 */
    public static String build(String method, String path,
                               Map<String, String> query,
                               Map<String, List<String>> headers,
                               String payloadHash) {
        if (method == null || method.isEmpty()) {
            throw new IllegalArgumentException("方法为空");
        }
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("路径为空");
        }
        if (payloadHash == null || payloadHash.isEmpty()) {
            throw new IllegalArgumentException("载荷哈希为空");
        }
        StringBuilder canonical = new StringBuilder();
        canonical.append(method.toUpperCase()).append('\n');
        canonical.append(encodePath(path)).append('\n');
        canonical.append(canonicalQuery(query)).append('\n');
        canonical.append(canonicalHeaders(headers)).append('\n');
        canonical.append(signedHeaderList(headers)).append('\n');
        canonical.append(payloadHash);
        return canonical.toString();
    }

    /** 路径规范化：空格转 %20 */
    static String encodePath(String path) {
        return path.replace(" ", "%20");
    }

    /** 查询按键排序，键值 URI 转义（空格转 %20） */
    static String canonicalQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>();
        query.forEach((k, v) -> sorted.put(uriEncode(k), uriEncode(v == null ? "" : v)));
        List<String> pairs = new ArrayList<>();
        sorted.forEach((k, v) -> pairs.add(k + "=" + v));
        return String.join("&", pairs);
    }

    static String uriEncode(String value) {
        return value.replace(" ", "%20");
    }

    /** 头键小写并排序；同名多值按序合并（逗号连接），值 trim */
    static String canonicalHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("签名头为空");
        }
        Map<String, List<String>> lower = new TreeMap<>();
        headers.forEach((k, values) -> {
            if (k == null || k.isEmpty()) {
                throw new IllegalArgumentException("头键为空");
            }
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("头值为空: " + k);
            }
            lower.computeIfAbsent(k.toLowerCase(), x -> new ArrayList<>()).addAll(values);
        });
        StringBuilder lines = new StringBuilder();
        lower.forEach((k, values) -> lines.append(k).append(':')
                .append(String.join(",", values.stream().map(String::trim).toList()))
                .append('\n'));
        return lines.toString().stripTrailing();
    }

    /** 签名头清单：小写键分号连接，按序 */
    static String signedHeaderList(Map<String, List<String>> headers) {
        Map<String, List<String>> lower = new TreeMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(), v));
        return String.join(";", lower.keySet());
    }
}
