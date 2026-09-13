package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpConfigSnapshotPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 配置快照 DAO（工单 0251 AG1）
 */
@Mapper
public interface IConfigSnapshotDao {

    int insert(McpConfigSnapshotPO po);

    int update(McpConfigSnapshotPO po);

    McpConfigSnapshotPO query(@Param("namespace") String namespace,
            @Param("configKey") String configKey, @Param("version") Integer version);

    Integer queryMaxVersion(@Param("namespace") String namespace, @Param("configKey") String configKey);

    List<McpConfigSnapshotPO> queryByKey(@Param("namespace") String namespace,
            @Param("configKey") String configKey);

    List<McpConfigSnapshotPO> queryByNamespace(@Param("namespace") String namespace);

    List<McpConfigSnapshotPO> queryAll();
}
