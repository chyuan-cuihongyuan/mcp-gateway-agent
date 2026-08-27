package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import cn.chyuan.ai.infrastructure.dao.IAuditLogDao;
import cn.chyuan.ai.infrastructure.dao.po.McpAuditLogPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * 治理面审计日志仓储实现（工单 0017）
 *
 * @author chyuan
 */
@Repository
public class AuditLogRepository implements IAuditLogRepository {

    @Resource
    private IAuditLogDao auditLogDao;

    @Override
    public void insert(AuditCommandEntity entity) {
        auditLogDao.insert(McpAuditLogPO.builder()
                .actor(entity.getActor())
                .action(entity.getAction())
                .resourceType(entity.getResourceType())
                .resourceId(entity.getResourceId())
                .beforeJson(entity.getBeforeJson())
                .afterJson(entity.getAfterJson())
                .build());
    }

    @Override
    public List<AuditLogVO> queryPage(String resourceType, String resourceId, int offset, int size) {
        McpAuditLogPO query = new McpAuditLogPO();
        query.setResourceType(resourceType);
        query.setResourceId(resourceId);
        query.setLimitStart(offset);
        query.setLimitCount(size);
        List<McpAuditLogPO> list = auditLogDao.queryPage(query);
        return list == null ? Collections.emptyList() : list.stream().map(this::toVo).toList();
    }

    @Override
    public long count(String resourceType, String resourceId) {
        McpAuditLogPO query = new McpAuditLogPO();
        query.setResourceType(resourceType);
        query.setResourceId(resourceId);
        Long count = auditLogDao.queryCount(query);
        return count == null ? 0 : count;
    }

    private AuditLogVO toVo(McpAuditLogPO po) {
        return AuditLogVO.builder()
                .id(po.getId())
                .actor(po.getActor())
                .action(po.getAction())
                .resourceType(po.getResourceType())
                .resourceId(po.getResourceId())
                .beforeJson(po.getBeforeJson())
                .afterJson(po.getAfterJson())
                .createdAt(po.getCreatedAt())
                .build();
    }
}
