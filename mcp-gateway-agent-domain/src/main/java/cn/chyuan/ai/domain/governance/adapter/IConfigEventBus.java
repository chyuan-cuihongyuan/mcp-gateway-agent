package cn.chyuan.ai.domain.governance.adapter;

/**
 * 配置热更新事件总线端口（工单 0078）
 *
 * <p>治理写路径发布「配置已变更」事件；各实例订阅后失效对应本地缓存，
 * 实现 30s TTL 之外的秒级跨实例生效（APISIX etcd watch 思想的 Redis pub-sub 裁剪）。
 * 尽力而为语义：发布失败不得影响写路径成功；总线不可用时退化为纯 TTL。
 *
 * @author chyuan
 */
public interface IConfigEventBus {

    /**
     * 发布配置变更事件（尽力而为）。
     *
     * @param objectType 对象类型（VIRTUAL_KEY/CEL_RULE/ATTACH/LLM_CHANNEL/GATEWAY_CONFIG）
     * @param objectId   对象标识（可空；ATTACH/GATEWAY_CONFIG 为 gatewayId）
     */
    void publish(String objectType, String objectId);
}
