package cn.chyuan.ai.domain.envoykernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 过滤器链（工单 0941 EA6，envoy 思想）。
 * 注册顺序执行/短路响应/透传传递。
 */
public final class FilterChain {

    /** 过滤上下文：请求文本 + 短路响应 */
    public static final class Context {
        public String request;
        public String response;

        public Context(String request) {
            this.request = request;
        }

        public boolean shortCircuited() {
            return response != null;
        }
    }

    @FunctionalInterface
    public interface Filter {
        void apply(Context ctx);
    }

    private final List<Filter> filters = new ArrayList<>();

    /** 追加过滤器：保持注册顺序 */
    public void add(Filter filter) {
        filters.add(filter);
    }

    /** 执行链：按注册顺序透传，任一过滤器短路即停 */
    public Context execute(String request) {
        Context ctx = new Context(request);
        for (Filter filter : filters) {
            filter.apply(ctx);
            if (ctx.shortCircuited()) {
                break;
            }
        }
        return ctx;
    }

    public int size() {
        return filters.size();
    }
}
