package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpChannelHealthSnapshotPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 渠道健康分快照 DAO（工单 0159）
 */
@Mapper
public interface IChannelHealthSnapshotDao {

    int insert(McpChannelHealthSnapshotPO po);

    /** 全渠道最近一次快照（按渠道取 sampled_at 最大行） */
    List<McpChannelHealthSnapshotPO> queryLatestPerChannel();

    int deleteBefore(@Param("before") java.util.Date before);
}
