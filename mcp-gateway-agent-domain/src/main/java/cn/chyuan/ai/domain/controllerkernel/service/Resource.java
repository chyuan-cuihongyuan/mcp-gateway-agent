package cn.chyuan.ai.domain.controllerkernel.service;

import java.util.Map;
import java.util.Objects;

/**
 * 资源对象（工单 0793 CP1，kubernetes 控制器思想）。
 * 期望 spec 与实际 status 分离/kind·namespace·name 标识/generation 随 spec 变更递增。
 */
public final class Resource {

    private final String kind;
    private final String namespace;
    private final String name;
    private final String uid;
    private Map<String, Object> spec;
    private Map<String, Object> status = Map.of();
    private long generation = 1;

    public Resource(String kind, String namespace, String name, String uid, Map<String, Object> spec) {
        if (kind == null || kind.isEmpty() || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("kind/name 缺失");
        }
        this.kind = kind;
        this.namespace = namespace == null ? "default" : namespace;
        this.name = name;
        this.uid = uid == null ? kind + "-" + name + "-" + System.identityHashCode(this) : uid;
        this.spec = spec == null ? Map.of() : Map.copyOf(spec);
    }

    public String kind() {
        return kind;
    }

    public String namespace() {
        return namespace;
    }

    public String name() {
        return name;
    }

    public String uid() {
        return uid;
    }

    public Map<String, Object> spec() {
        return spec;
    }

    public Map<String, Object> status() {
        return status;
    }

    public long generation() {
        return generation;
    }

    /** 资源键 kind/namespace/name */
    public String key() {
        return kind + "/" + namespace + "/" + name;
    }

    /** spec 变更才递增 generation */
    public void updateSpec(Map<String, Object> next) {
        Map<String, Object> candidate = next == null ? Map.of() : Map.copyOf(next);
        if (!Objects.equals(candidate, this.spec)) {
            this.spec = candidate;
            this.generation++;
        }
    }

    public void status(Map<String, Object> status) {
        this.status = status == null ? Map.of() : Map.copyOf(status);
    }

    /** 深拷贝（集群实际态独立于期望态） */
    public Resource copy() {
        Resource r = new Resource(kind, namespace, name, uid, spec);
        r.status = this.status;
        r.generation = this.generation;
        return r;
    }

    /** spec 是否一致（不含 status） */
    public boolean specEquals(Resource other) {
        return Objects.equals(this.spec, other.spec);
    }
}
