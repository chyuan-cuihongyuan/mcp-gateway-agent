package cn.chyuan.ai.domain.llmchannel.adapter.repository;

import cn.chyuan.ai.domain.llmchannel.model.valobj.ChannelHealthSnapshotVO;

import java.util.List;

/**
 * 渠道健康分快照仓储端口（工单 0159；domain 不引框架，infrastructure 落地 MyBatis）
 *
 * @author chyuan
 */
public interface IChannelHealthSnapshotRepository {

    /** 落一条快照（采样任务批量逐条调用） */
    void insert(ChannelHealthSnapshotVO snapshot);

    /** 全渠道最近一次快照（按渠道取最新一条） */
    List<ChannelHealthSnapshotVO> latestPerChannel();

    /** 保留策略：清理 sampled_at 早于该时点的快照，返回清理行数 */
    int deleteBefore(java.util.Date before);
}
