package cn.chyuan.ai.domain.socketkernel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 握手中间件（工单 0961 ED2，socket.io 思想）。
 * 连接握手链按注册顺序执行/中间件拒绝短路/通过进入命名空间。
 */
public final class Handshakes {

    /** 握手上下文：握手头 + 拒绝原因 */
    public static final class Context {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String rejectReason;

        public void header(String name, String value) {
            headers.put(name, value);
        }

        public String header(String name) {
            return headers.get(name);
        }

        public void reject(String reason) {
            rejectReason = reason;
        }

        public boolean rejected() {
            return rejectReason != null;
        }

        public String reason() {
            return rejectReason;
        }
    }

    @FunctionalInterface
    public interface Middleware {
        void apply(Context ctx);
    }

    private final List<Middleware> chain = new ArrayList<>();

    /** 追加中间件（注册序执行） */
    public void add(Middleware middleware) {
        chain.add(middleware);
    }

    /** 执行握手链：任一中间件拒绝即短路 */
    public Context handshake(Map<String, String> headers) {
        Context ctx = new Context();
        headers.forEach(ctx::header);
        for (Middleware middleware : chain) {
            middleware.apply(ctx);
            if (ctx.rejected()) {
                break;
            }
        }
        return ctx;
    }

    public int size() {
        return chain.size();
    }
}
