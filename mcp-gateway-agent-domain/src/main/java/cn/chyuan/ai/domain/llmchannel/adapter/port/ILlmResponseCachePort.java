package cn.chyuan.ai.domain.llmchannel.adapter.port;

/**
 * LLM 响应缓存端口（工单 0097）
 *
 * <p>精确缓存语义：命中返回原响应 JSON；未命中/Redis 不可用返回 null（调用侧直通上游）。
 * 实现侧保证尽力而为——任何异常不 fail 请求。
 *
 * @author chyuan
 */
public interface ILlmResponseCachePort {

    /**
     * 构造缓存键（实现侧 sha256(vkId|model|参与字段)）；不可用时返回 null。
     */
    String cacheKey(Long virtualKeyId, String model, String normalizedRequest);

    /**
     * 读缓存。
     *
     * @param cacheKey 缓存键（实现侧 sha256 规范）
     * @return 命中的响应 JSON；未命中/不可用 null
     */
    String get(String cacheKey);

    /** 写缓存（TTL 实现侧配置；尽力而为） */
    void put(String cacheKey, String response);

    /** 缓存能力是否可用（Redis 装配 + 总开关） */
    boolean available();

    // ---- 管理面（工单 0100）----

    /** 按键删除；true=已删，false=未删/不可用 */
    boolean delete(String cacheKey);

    /** 前缀清空；返回删除数，-1=不可用 */
    long purge();

    /** 统计：available/ttlSeconds/keys（-1=不可用） */
    java.util.Map<String, Object> stats();
}
