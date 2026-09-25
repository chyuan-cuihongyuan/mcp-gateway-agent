package cn.chyuan.ai.domain.rpckernel.service;

import java.util.List;

/**
 * RPC 契约端口（工单 0914 DC8，grpc 思想）。
 * contract·invoke 入口统一编排/与 protokernel 消息字节作帧载荷形态只读联动（泛型字节不 import）/
 * rpc-kernel.enabled 默认关（开启才改变行为）。
 */
public interface RpcPort {

    RpcContract contract();

    RpcInvoker invoker();

    byte[] frame(byte[] payload);

    List<byte[]> unframe(byte[] stream);

    static RpcPort inMemory() {
        return new InMemoryRpc();
    }
}

final class InMemoryRpc implements RpcPort {

    private final RpcContract contract = new RpcContract();
    private final RpcInvoker invoker = new RpcInvoker(contract);

    @Override
    public RpcContract contract() {
        return contract;
    }

    @Override
    public RpcInvoker invoker() {
        return invoker;
    }

    @Override
    public byte[] frame(byte[] payload) {
        return RpcWire.encode(payload);
    }

    @Override
    public List<byte[]> unframe(byte[] stream) {
        return RpcWire.decode(stream);
    }
}
