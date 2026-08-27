package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.adapter.repository.IAuditLogRepository;
import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 治理面审计服务（工单 0017）
 *
 * @author chyuan
 */
@Slf4j
@Service
public class AuditService implements IAuditService {

    @Resource
    private IAuditLogRepository repository;

    @Override
    public void record(AuditCommandEntity entity) {
        try {
            repository.insert(entity);
        } catch (Exception e) {
            // 审计失败不阻断业务主流程，但必须留痕告警
            log.error("审计日志写入失败 action:{} resource:{}:{}", entity.getAction(),
                    entity.getResourceType(), entity.getResourceId(), e);
        }
    }

    @Override
    public List<IAuditLogRepository.AuditLogVO> page(String resourceType, String resourceId, int page, int size) {
        return repository.queryPage(resourceType, resourceId, Math.max(page - 1, 0) * size, size);
    }

    @Override
    public long count(String resourceType, String resourceId) {
        return repository.count(resourceType, resourceId);
    }
}
