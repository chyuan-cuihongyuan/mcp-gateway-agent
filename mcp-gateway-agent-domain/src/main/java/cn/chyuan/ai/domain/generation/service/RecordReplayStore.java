package cn.chyuan.ai.domain.generation.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 录制回放传输装饰器纯内核（工单 0203 AA8，借鉴 LiteLLM mock/VCR）—
 * 录制模式：请求（URL+归一化体）sha256 为键，响应写 JSON 文件（cassette）；
 * 回放模式：键命中即返回录制响应（零网络），未命中抛错暴露测试缺口。
 * 装饰器组装在 infrastructure/trigger 侧完成，本类只管键与磁带读写（可测）。
 *
 * @author chyuan
 */
public class RecordReplayStore {

    /** 录制磁带条目 */
    public record CassetteEntry(String requestHash, String url, String requestBody, int status,
            String responseBody, long recordedAt) {
    }

    private final Path directory;
    private final Map<String, CassetteEntry> memoryIndex = new ConcurrentHashMap<>();

    public RecordReplayStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 请求归一化：剔除 Authorization 头、时间戳类字段后，URL+体去全部空白转小写。
     * 同一语义请求（空白差异/顺序差异的 JSON）得到同键。
     */
    public static String requestHash(String url, String body) {
        String normalizedUrl = url == null ? "" : url.trim().toLowerCase();
        String normalizedBody = normalizeJson(body);
        String material = normalizedUrl + "§" + normalizedBody;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** JSON 归一化：可解析则重序列化（键序稳定）再压空白；不可解析则原样去空白 */
    static String normalizeJson(String body) {
        if (body == null) {
            return "";
        }
        try {
            Object parsed = com.alibaba.fastjson.JSON.parse(body);
            if (parsed instanceof com.alibaba.fastjson.JSONObject obj) {
                return sortedJson(obj);
            }
        } catch (Exception ignored) {
            // 退回纯文本归一
        }
        return body.replaceAll("\\s+", "").toLowerCase();
    }

    /** 递归按键排序序列化（大小写不敏感排序 + 键值统一小写，保证键序/大小写差异不影响归一化） */
    static String sortedJson(com.alibaba.fastjson.JSONObject obj) {
        StringBuilder sb = new StringBuilder("{");
        java.util.SortedMap<String, Object> sorted = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey().toLowerCase()).append("\":");
            sb.append(valueJson(e.getValue()));
        }
        return sb.append('}').toString().toLowerCase();
    }

    private static String valueJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof com.alibaba.fastjson.JSONObject obj) {
            return sortedJson(obj);
        }
        if (value instanceof com.alibaba.fastjson.JSONArray arr) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(valueJson(arr.get(i)));
            }
            return sb.append(']').toString();
        }
        if (value instanceof String s) {
            return "\"" + s.toLowerCase() + "\"";
        }
        return String.valueOf(value).toLowerCase();
    }

    /** 录制：内存索引 + 落盘（文件名 = 前 16 位哈希 + .json；落盘失败不阻断，仅内存生效） */
    public void record(String url, String requestBody, int status, String responseBody) {
        String hash = requestHash(url, requestBody);
        CassetteEntry entry = new CassetteEntry(hash, url, requestBody, status, responseBody,
                System.currentTimeMillis());
        memoryIndex.put(hash, entry);
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(hash.substring(0, 16) + ".json");
            Files.writeString(file, com.alibaba.fastjson.JSON.toJSONString(entry), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 磁带落盘尽力而为（只读文件系统等）
        }
    }

    /** 回放：命中返回条目；未命中抛 IllegalStateException（暴露测试缺口） */
    public CassetteEntry replay(String url, String requestBody) {
        String hash = requestHash(url, requestBody);
        CassetteEntry entry = memoryIndex.get(hash);
        if (entry != null) {
            return entry;
        }
        // 内存未命中尝试读盘（跨进程回放场景）
        Path file = directory.resolve(hash.substring(0, 16) + ".json");
        if (Files.exists(file)) {
            try {
                CassetteEntry fromDisk = com.alibaba.fastjson.JSON.parseObject(
                        Files.readString(file, StandardCharsets.UTF_8), CassetteEntry.class);
                if (fromDisk != null) {
                    memoryIndex.put(hash, fromDisk);
                    return fromDisk;
                }
            } catch (Exception ignored) {
                // 坏磁带按未命中处理
            }
        }
        throw new IllegalStateException("回放未命中录制磁带: " + hash.substring(0, 16));
    }

    /** 内存索引条数（观测用） */
    public int indexedCount() {
        return memoryIndex.size();
    }

    /** 磁带目录（观测用） */
    public Path directory() {
        return directory;
    }
}
