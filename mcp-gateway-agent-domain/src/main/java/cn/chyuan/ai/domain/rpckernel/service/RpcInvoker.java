package cn.chyuan.ai.domain.rpckernel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * RPC 调用器与拦截器链（工单 0912 DC6，grpc 思想）。
 * 拦截器注册顺序执行（先注册在最外层）/短路响应/取消与 deadline 前置检查。
 */
public final class RpcInvoker {

    /** 调用请求 */
    public record Call(String methodFullName, byte[] request, RpcContract.Metadata metadata,
                       RpcWire.Deadline deadline, RpcWire.Cancellation cancellation, long now) {
    }

    /** 调用响应 */
    public record Response(RpcContract.Status status, byte[] payload) {
        public static Response ok(byte[] payload) {
            return new Response(RpcContract.Status.OK, payload);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Call call);
    }

    @FunctionalInterface
    public interface Interceptor {
        Response intercept(Call call, Handler next);
    }

    private final RpcContract contract;
    private final List<Interceptor> interceptors = new ArrayList<>();

    public RpcInvoker(RpcContract contract) {
        this.contract = contract;
    }

    public void addInterceptor(Interceptor interceptor) {
        interceptors.add(interceptor);
    }

    /** 调用：契约校验→取消→deadline→拦截器链→处理器 */
    public Response invoke(Call call) {
        contract.lookup(call.methodFullName());
        if (call.cancellation() != null && call.cancellation().cancelled()) {
            return new Response(RpcContract.Status.CANCELLED, new byte[0]);
        }
        if (call.deadline() != null && call.deadline().expired(call.now())) {
            return new Response(RpcContract.Status.DEADLINE_EXCEEDED, new byte[0]);
        }
        Handler handler = buildChain(interceptors, call2 -> Response.ok(call2.request()));
        return handler.handle(call);
    }

    private static Handler buildChain(List<Interceptor> interceptors, Handler terminal) {
        Handler next = terminal;
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            Interceptor interceptor = interceptors.get(i);
            Handler current = next;
            next = call -> interceptor.intercept(call, current);
        }
        return next;
    }
}
