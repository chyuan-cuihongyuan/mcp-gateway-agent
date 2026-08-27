package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;

import java.util.List;

/**
 * 治理面审计服务（工单 0017）
 *
 * @author chyuan
 */
public interface IAuditService {

    void record(AuditCommandEntity entity);

    List<IAuditLogRepository.AuditLogVO> page(String resourceType, String resourceId, int page, int size);

    long count(String resourceType, String resourceId);
}
