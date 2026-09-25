package cn.chyuan.ai.domain.rpckernel.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RPC 契约内核测试（工单 0907-0914 DC1-DC8，grpc 思想）。
 * 契约流型/帧编解码/状态码/deadline 传递/取消传播/拦截器链短路/metadata/端口与 proto 消息联动。
 */
class RpcKernelTest {

    @Test
    void contractAndMethodTypes() {
        RpcContract contract = new RpcContract();
        contract.define("gw.Echo", "Get", RpcContract.MethodType.UNARY);
        contract.define("gw.Echo", "Stream", RpcContract.MethodType.SERVER_STREAMING);
        contract.define("gw.Echo", "Upload", RpcContract.MethodType.CLIENT_STREAMING);
        contract.define("gw.Echo", "Chat", RpcContract.MethodType.BIDI_STREAMING);
        assertEquals(4, contract.methods().size());
        assertEquals("/gw.Echo/Get", contract.lookup("/gw.Echo/Get").fullName());
        assertThrows(IllegalArgumentException.class,
                () -> contract.define("gw.Echo", "Get", RpcContract.MethodType.UNARY), "重复定义拒绝");
        assertThrows(IllegalArgumentException.class, () -> contract.lookup("/gw.Echo/Nope"), "未定义方法拒绝");
    }

    @Test
    void frameCodec() {
        byte[] payload = "hello grpc".getBytes(StandardCharsets.UTF_8);
        byte[] framed = RpcWire.encode(payload);
        assertEquals(payload.length + 4, framed.length);
        List<byte[]> frames = RpcWire.decode(framed);
        assertEquals(1, frames.size());
        assertArrayEquals(payload, frames.get(0));
        byte[] multi = RpcWire.encode("ab".getBytes(StandardCharsets.UTF_8));
        byte[] stream = java.util.Arrays.copyOf(framed, framed.length + multi.length);
        System.arraycopy(multi, 0, stream, framed.length, multi.length);
        assertEquals(2, RpcWire.decode(stream).size(), "多帧拆分");
        assertThrows(IllegalArgumentException.class,
                () -> RpcWire.decode(java.util.Arrays.copyOf(framed, 2)), "帧头截断拒绝");
        byte[] truncated = new byte[4 + payload.length - 1];
        System.arraycopy(framed, 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class, () -> RpcWire.decode(truncated), "载荷截断拒绝");
    }

    @Test
    void statusMapping() {
        assertEquals(0, RpcContract.Status.OK.code());
        assertEquals(RpcContract.Status.UNAVAILABLE, RpcContract.Status.fromCode(14));
        assertEquals(RpcContract.Status.DEADLINE_EXCEEDED, RpcContract.Status.fromCode(4));
        assertThrows(IllegalArgumentException.class, () -> RpcContract.Status.fromCode(99), "未知码拒绝");
    }

    @Test
    void deadlinePropagation() {
        RpcWire.Deadline parent = new RpcWire.Deadline(100);
        RpcWire.Deadline child = parent.child(50, 30);
        assertEquals(80, child.expiresAt(), "子调用取 min(父, now+timeout)");
        assertFalse(child.expired(79));
        assertTrue(child.expired(80));
        RpcWire.Deadline tight = parent.child(50, 100);
        assertEquals(100, tight.expiresAt(), "不超出父时限");
    }

    @Test
    void cancelPropagation() {
        RpcWire.Cancellation parent = new RpcWire.Cancellation();
        RpcWire.Cancellation child = parent.child();
        assertFalse(child.cancelled());
        parent.cancel();
        assertTrue(child.cancelled(), "父取消传播子");
        assertTrue(parent.isCancelled());
    }

    @Test
    void metadataRules() {
        RpcContract.Metadata meta = new RpcContract.Metadata();
        meta.put("authorization", "Bearer x");
        meta.put("trace-bin", "AQID");
        assertEquals(List.of("Bearer x"), meta.get("AUTHORIZATION"), "键大小写归一");
        assertTrue(meta.isBinary("trace-bin"), "-bin 后缀二进制标记");
        assertFalse(meta.isBinary("authorization"));
        assertThrows(IllegalArgumentException.class, () -> meta.put("Bad-Key", "v"), "大写键拒绝");
        assertEquals(2, meta.size());
    }

    @Test
    void interceptorChainAndShortCircuit() {
        RpcContract contract = new RpcContract();
        contract.define("gw.Echo", "Get", RpcContract.MethodType.UNARY);
        RpcInvoker invoker = new RpcInvoker(contract);
        List<String> order = new java.util.ArrayList<>();
        invoker.addInterceptor((call, next) -> {
            order.add("outer-in");
            RpcInvoker.Response response = next.handle(call);
            order.add("outer-out");
            return response;
        });
        invoker.addInterceptor((call, next) -> {
            order.add("inner-in");
            return next.handle(call);
        });
        RpcInvoker.Response response = invoker.invoke(new RpcInvoker.Call("/gw.Echo/Get",
                "req".getBytes(StandardCharsets.UTF_8), new RpcContract.Metadata(),
                null, null, 0));
        assertEquals(RpcContract.Status.OK, response.status());
        assertArrayEquals("req".getBytes(StandardCharsets.UTF_8), response.payload());
        assertEquals(List.of("outer-in", "inner-in", "outer-out"), order, "洋葱序：先注册在外层");
        RpcInvoker shortCircuit = new RpcInvoker(contract);
        shortCircuit.addInterceptor((call, next) ->
                new RpcInvoker.Response(RpcContract.Status.UNAVAILABLE, "blocked".getBytes(StandardCharsets.UTF_8)));
        RpcInvoker.Response blocked = shortCircuit.invoke(new RpcInvoker.Call("/gw.Echo/Get",
                "req".getBytes(StandardCharsets.UTF_8), new RpcContract.Metadata(), null, null, 0));
        assertEquals(RpcContract.Status.UNAVAILABLE, blocked.status(), "拦截器短路");
    }

    @Test
    void invokeCancelAndDeadline() {
        RpcContract contract = new RpcContract();
        contract.define("gw.Echo", "Get", RpcContract.MethodType.UNARY);
        RpcInvoker invoker = new RpcInvoker(contract);
        RpcWire.Cancellation cancel = new RpcWire.Cancellation();
        cancel.cancel();
        RpcInvoker.Response cancelled = invoker.invoke(new RpcInvoker.Call("/gw.Echo/Get",
                "req".getBytes(StandardCharsets.UTF_8), new RpcContract.Metadata(), null, cancel, 0));
        assertEquals(RpcContract.Status.CANCELLED, cancelled.status(), "取消优先");
        RpcInvoker.Response expired = invoker.invoke(new RpcInvoker.Call("/gw.Echo/Get",
                "req".getBytes(StandardCharsets.UTF_8), new RpcContract.Metadata(),
                new RpcWire.Deadline(10), null, 11));
        assertEquals(RpcContract.Status.DEADLINE_EXCEEDED, expired.status(), "超时拒绝");
    }

    @Test
    void portOrchestrationAndProtoLinkage() {
        RpcPort port = RpcPort.inMemory();
        port.contract().define("gw.Store", "Put", RpcContract.MethodType.UNARY);
        byte[] protoMessage = "proto-payload".getBytes(StandardCharsets.UTF_8);
        byte[] wire = port.frame(protoMessage);
        assertEquals(1, port.unframe(wire).size());
        assertArrayEquals(protoMessage, port.unframe(wire).get(0), "proto 消息字节作帧载荷只读联动");
        port.invoker().addInterceptor((call, next) -> next.handle(call));
        RpcInvoker.Response response = port.invoker().invoke(new RpcInvoker.Call("/gw.Store/Put",
                protoMessage, new RpcContract.Metadata(), null, null, 0));
        assertEquals(RpcContract.Status.OK, response.status());
        assertThrows(IllegalArgumentException.class,
                () -> port.invoker().invoke(new RpcInvoker.Call("/nope/Nope",
                        new byte[0], new RpcContract.Metadata(), null, null, 0)), "未定义方法拒绝");
    }
}
