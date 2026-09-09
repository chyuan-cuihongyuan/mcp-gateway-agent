package cn.chyuan.ai.domain.governance.adapter.repository;

import java.util.Map;

/**
 * 配置快照端口（工单 0077：治理配置导出/导入）
 *
 * <p>IO 由基础设施实现（直连既有 DAO）；脱敏/差异/应用策略在领域服务
 * {@code ConfigSnapshotService}。行结构为快照行（Map），字段口径见该服务。
 *
 * @author chyuan
 */
public interface IConfigSnapshotPort {

    /** 读当前全量配置（快照行形态；密钥行已不含哈希与明文——由实现侧保证不出库） */
    Map<String, Object> readCurrent();

    /**
     * 按自然键 upsert 单行到目标存储。
     *
     * @param store 存储名（gateways/tools/protocolHttp/celTemplates/celRules/externalAttaches/llmChannels/webhookEndpoints）
     * @param row   快照行
     * @return "created" 或 "updated"
     */
    String upsert(String store, Map<String, Object> row);
}
