package cn.chyuan.ai.domain.objkernel.service;

import java.util.List;
import java.util.TreeMap;

/**
 * bucket 管理（工单 1127 EW1，minio 思想）。
 * 创建/命名 3-63 小写字母数字连点且首尾字母数字校验/重复拒绝/删除需空桶/列表字典序。
 */
public final class Buckets {

    private final TreeMap<String, Integer> buckets = new TreeMap<>();

    /** 命名校验：3-63 位、小写字母数字连点、首尾字母数字、无连续点 */
    public static void validateName(String name) {
        if (name == null || name.length() < 3 || name.length() > 63) {
            throw new IllegalArgumentException("bucket 名须 3-63 位: " + name);
        }
        if (!name.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]")) {
            throw new IllegalArgumentException("bucket 名须小写字母数字连点且首尾字母数字: " + name);
        }
        if (name.contains("..")) {
            throw new IllegalArgumentException("bucket 名不得含连续点: " + name);
        }
    }

    /** 创建：命名非法/重复拒绝 */
    public void create(String name) {
        validateName(name);
        if (buckets.containsKey(name)) {
            throw new IllegalArgumentException("重复 bucket: " + name);
        }
        buckets.put(name, 0);
    }

    public boolean exists(String name) {
        return buckets.containsKey(name);
    }

    /** 断言存在 */
    public void require(String name) {
        if (!buckets.containsKey(name)) {
            throw new IllegalArgumentException("未知 bucket: " + name);
        }
    }

    /** 版本化开关（EW3）：仅记标记，未开桶为 0 */
    public void enableVersioning(String name) {
        require(name);
        buckets.put(name, 1);
    }

    public boolean versioned(String name) {
        return buckets.getOrDefault(name, 0) == 1;
    }

    /** 删除：仅空桶可删 */
    public void delete(String name, boolean empty) {
        require(name);
        if (!empty) {
            throw new IllegalStateException("桶非空不可删除: " + name);
        }
        buckets.remove(name);
    }

    /** 字典序列表（只读） */
    public List<String> list() {
        return List.copyOf(buckets.keySet());
    }
}
