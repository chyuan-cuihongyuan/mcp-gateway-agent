package cn.chyuan.ai.infrastructure.dao;

import cn.chyuan.ai.infrastructure.dao.po.McpAnnotationQaPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 标注回复 DAO（工单 0202 AA7）
 */
@Mapper
public interface IAnnotationQaDao {

    int insert(McpAnnotationQaPO po);

    int update(McpAnnotationQaPO po);

    int delete(@Param("id") Long id);

    int incrementHit(@Param("id") Long id);

    McpAnnotationQaPO query(@Param("id") Long id);

    McpAnnotationQaPO queryByKey(@Param("questionKey") String questionKey);

    List<McpAnnotationQaPO> queryEnabled();
}
