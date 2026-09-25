package cn.chyuan.ai.domain.rpckernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RPC 契约、状态码与元数据（工单 0907/0909/0913 DC1·DC3·DC7，grpc 思想）。
 * service·method 定义与四种流型/状态码映射未知拒绝/元数据小写键与 -bin 后缀标记。
 */
public final class RpcContract {

    public enum MethodType { UNARY, SERVER_STREAMING, CLIENT_STREAMING, BIDI_STREAMING }

    /** 方法：全名 /service/Name */
    public record Method(String service, String name, MethodType type) {
        public String fullName() {
            return "/" + service + "/" + name;
        }
    }

    private final Map<String, Method> methods = new LinkedHashMap<>();

    /** 定义方法：重复拒绝 */
    public void define(String service, String name, MethodType type) {
        Method m = new Method(service, name, type);
        if (methods.containsKey(m.fullName())) {
            throw new IllegalArgumentException("方法重复定义: " + m.fullName());
        }
        methods.put(m.fullName(), m);
    }

    public Method lookup(String fullName) {
        Method m = methods.get(fullName);
        if (m == null) {
            throw new IllegalArgumentException("未定义方法: " + fullName);
        }
        return m;
    }

    public List<Method> methods() {
        return new ArrayList<>(methods.values());
    }

    /** 状态码（grpc 子集） */
    public enum Status {
        OK(0), CANCELLED(1), UNKNOWN(2), DEADLINE_EXCEEDED(4), NOT_FOUND(5),
        INTERNAL(13), UNAVAILABLE(14);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Status fromCode(int code) {
            for (Status s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知状态码: " + code);
        }
    }

    /** 元数据：键必须全小写；-bin 后缀标记二进制（值为 Base64 文本） */
    public static final class Metadata {
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        public void put(String key, String value) {
            String normalized = key.toLowerCase(Locale.ROOT);
            if (!normalized.equals(key)) {
                throw new IllegalArgumentException("metadata 键必须小写: " + key);
            }
            values.computeIfAbsent(normalized, k -> new ArrayList<>()).add(value);
        }

        public List<String> get(String key) {
            return List.copyOf(values.getOrDefault(key.toLowerCase(Locale.ROOT), List.of()));
        }

        public boolean isBinary(String key) {
            return key.toLowerCase(Locale.ROOT).endsWith("-bin");
        }

        public int size() {
            return values.size();
        }

        public Map<String, List<String>> asMap() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            values.forEach((k, v) -> out.put(k, List.copyOf(v)));
            return Map.copyOf(out);
        }
    }
}
