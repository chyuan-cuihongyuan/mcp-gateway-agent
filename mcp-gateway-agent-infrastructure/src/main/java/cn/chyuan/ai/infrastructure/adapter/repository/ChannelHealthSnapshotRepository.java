package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.llmchannel.adapter.repository.IChannelHealthSnapshotRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.ChannelHealthSnapshotVO;
import cn.chyuan.ai.infrastructure.dao.IChannelHealthSnapshotDao;
import cn.chyuan.ai.infrastructure.dao.po.McpChannelHealthSnapshotPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * 渠道健康分快照仓储实现（工单 0159）
 *
 * @author chyuan
 */
@Repository
public class ChannelHealthSnapshotRepository implements IChannelHealthSnapshotRepository {

    @Resource
    private IChannelHealthSnapshotDao dao;

    @Override
    public void insert(ChannelHealthSnapshotVO snapshot) {
        dao.insert(McpChannelHealthSnapshotPO.builder()
                .channelId(snapshot.getChannelId())
                .channelName(snapshot.getChannelName())
                .score(snapshot.getScore())
                .errorRate(snapshot.getErrorRate())
                .probeScore(snapshot.getProbeScore())
                .avgLatencyMs(snapshot.getAvgLatencyMs())
                .sampledAt(snapshot.getSampledAt())
                .build());
    }

    @Override
    public List<ChannelHealthSnapshotVO> latestPerChannel() {
        List<McpChannelHealthSnapshotPO> list = dao.queryLatestPerChannel();
        return list == null ? Collections.emptyList() : list.stream().map(this::toVo).toList();
    }

    @Override
    public int deleteBefore(java.util.Date before) {
        return dao.deleteBefore(before);
    }

    private ChannelHealthSnapshotVO toVo(McpChannelHealthSnapshotPO po) {
        return ChannelHealthSnapshotVO.builder()
                .id(po.getId()).channelId(po.getChannelId()).channelName(po.getChannelName())
                .score(po.getScore()).errorRate(po.getErrorRate()).probeScore(po.getProbeScore())
                .avgLatencyMs(po.getAvgLatencyMs()).sampledAt(po.getSampledAt())
                .build();
    }
}
