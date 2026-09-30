package cn.chyuan.ai.domain.objkernel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对象读写（工单 1128 EW2，minio 思想）。
 * PUT 生成 ETag 内容摘要/GET 返回内容一致/大小上限拒绝/未知对象拒绝/覆盖更新换 ETag。
 */
public final class Objects {

    /** 对象版本：版本 id + 内容 + ETag + 修改 tick + 删除标记 */
    public record ObjectVersion(long versionId, String content, String etag, long modifiedTick, boolean deleteMarker) {
    }

    /** 同 key 多版本容器：版本 id 全桶单调递增 */
    public static final class KeyVersions {
        private final String key;
        private final java.util.List<ObjectVersion> versions = new java.util.ArrayList<>();

        KeyVersions(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        void append(ObjectVersion version) {
            versions.add(version);
        }

        public ObjectVersion latest() {
            return versions.get(versions.size() - 1);
        }

        public ObjectVersion at(long versionId) {
            for (ObjectVersion version : versions) {
                if (version.versionId() == versionId) {
                    return version;
                }
            }
            throw new IllegalArgumentException("未知版本: " + key + "@" + versionId);
        }

        public int count() {
            return versions.size();
        }

        public boolean latestIsMarker() {
            return latest().deleteMarker();
        }
    }

    /** 内容大小上限（测试口径 1MB） */
    public static final long MAX_SIZE = 1024 * 1024;
    private final Map<String, KeyVersions> objects = new LinkedHashMap<>();
    private long versionCounter;

    public static String etagOf(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }

    /** PUT：空 key/超限拒绝；开桶追加新版本，未开桶覆盖替换；返回本次版本 */
    public ObjectVersion put(String key, String content, long tick, boolean versioned) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("对象 key 不能为空");
        }
        if (content == null || content.length() > MAX_SIZE) {
            throw new IllegalArgumentException("对象超大小上限: " + (content == null ? 0 : content.length()));
        }
        KeyVersions versions = objects.computeIfAbsent(key, k -> new KeyVersions(k));
        if (!versioned) {
            versions.versions.clear();
        }
        ObjectVersion version = new ObjectVersion(++versionCounter, content, etagOf(content), tick, false);
        versions.append(version);
        return version;
    }

    /** GET 最新：未知对象或最新为删除标记拒绝 */
    public ObjectVersion get(String key) {
        KeyVersions versions = objects.get(key);
        if (versions == null) {
            throw new IllegalArgumentException("未知对象: " + key);
        }
        ObjectVersion latest = versions.latest();
        if (latest.deleteMarker()) {
            throw new IllegalArgumentException("对象已删除: " + key);
        }
        return latest;
    }

    /** 按版本 id 检索：删除标记版同样可检（版本化语义） */
    public ObjectVersion getAt(String key, long versionId) {
        KeyVersions versions = objects.get(key);
        if (versions == null) {
            throw new IllegalArgumentException("未知对象: " + key);
        }
        return versions.at(versionId);
    }

    /** DELETE：写删除标记不物理删；未开桶物理移除 */
    public void delete(String key, long tick, boolean versioned) {
        KeyVersions versions = objects.get(key);
        if (versions == null) {
            throw new IllegalArgumentException("未知对象: " + key);
        }
        if (versioned) {
            versions.append(new ObjectVersion(++versionCounter, "", "", tick, true));
        } else {
            objects.remove(key);
        }
    }

    public KeyVersions versionsOf(String key) {
        KeyVersions versions = objects.get(key);
        if (versions == null) {
            throw new IllegalArgumentException("未知对象: " + key);
        }
        return versions;
    }

    /** 物理移除（生命周期清理用）；不存在返回 false */
    public boolean remove(String key) {
        return objects.remove(key) != null;
    }

    /** 存活 key（最新非删除标记）字典序 */
    public java.util.List<String> liveKeys() {
        return objects.values().stream()
                .filter(v -> !v.latestIsMarker())
                .map(KeyVersions::key)
                .sorted()
                .toList();
    }

    /** 全量 key（含删除标记）字典序 */
    public java.util.List<String> allKeys() {
        return objects.keySet().stream().sorted().toList();
    }

    public boolean isEmpty() {
        return objects.isEmpty();
    }

    public void clear() {
        objects.clear();
    }
}
