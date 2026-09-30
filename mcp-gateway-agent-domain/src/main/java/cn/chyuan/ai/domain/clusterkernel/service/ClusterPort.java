package cn.chyuan.ai.domain.clusterkernel.service;

import java.util.List;

/**
 * 集群容错端口（工单 1089 ER8，dubbo 思想）。
 * refer·invoke 入口统一编排：服务目录/五容错策略/四负载均衡/引用计数组合管线；
 * rpckernel 契约形状只读联动（形状键 service·method·fullName·methodType 对齐，不 import rpckernel）/
 * cluster-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ClusterPort {

    // —— 服务目录（ER1）——
    void register(String service, String provider, int weight);

    void unregister(String service, String provider);

    List<String> providers(String service);

    List<String> directoryEvents(String service);

    // —— 容错调用（ER2/ER3/ER4/ER5）——
    void behave(String provider, boolean ok, String result);

    String invoke(String service, String policy, int attempts);

    List<String> broadcast(String service);

    List<String> failures(String provider);

    // —— 负载均衡（ER6）——
    String select(String service, String strategy, String argument);

    void active(String provider, int delta);

    // —— 引用计数（ER7）——
    void acquire(String key);

    void release(String key);

    int refCount(String key);

    boolean refDestroyed(String key);

    // —— rpckernel 契约形状只读联动（ER8）——
    List<String> contractShape();

    static ClusterPort inMemory(long seed) {
        return new ClusterInvoker(seed);
    }
}
