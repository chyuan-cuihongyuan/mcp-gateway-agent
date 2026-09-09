package cn.chyuan.ai.domain.governance.adapter.repository;

import cn.chyuan.ai.domain.governance.model.entity.AuditCommandEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 治理面审计日志仓储端口（工单 0017）
 *
 * @author chyuan
 */
public interface IAuditLogRepository {

    void insert(AuditCommandEntity entity);

    List<AuditLogVO> queryPage(String resourceType, String resourceId, String type, String actor, int page, int size);

    long count(String resourceType, String resourceId);

    long count(String resourceType, String resourceId, String type, String actor);

    /** 审计日志值对象 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class AuditLogVO {
        private Long id;
        private String actor;
        private String type;
        private String action;
        private String resourceType;
        private String resourceId;
        private String beforeJson;
        private String afterJson;
        private Date createdAt;
    }
}
