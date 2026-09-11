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

    /** 分型/操作者筛选查询（工单 0111/0112） */
    java.util.List<cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository.AuditLogVO> page(
            String resourceType, String resourceId, String type, String actor, int page, int size);

    long count(String resourceType, String resourceId, String type, String actor);

    /** 审计统计（工单 0186 Z3）：byType 分型计数 + topOperators 操作者 TopN（近 days 天） */
    java.util.Map<String, Object> stats(int days);
}
