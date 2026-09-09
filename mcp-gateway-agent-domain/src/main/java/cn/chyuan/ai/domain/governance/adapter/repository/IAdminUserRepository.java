package cn.chyuan.ai.domain.governance.adapter.repository;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * admin 用户仓储端口（工单 0017）
 *
 * @author chyuan
 */
public interface IAdminUserRepository {

    AdminUserVO findByUsername(String username);

    boolean existsAny();

    void insert(String username, String passwordHash, String role);

    /** 是否存在指定角色的用户（工单 0109：超管保底升级判定） */
    boolean existsByRole(String role);

    /** 改角色（工单 0109/0110：超管升级与账户管理） */
    int updateRole(String username, String role);

    /** admin 用户值对象 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class AdminUserVO {
        private Long id;
        private String username;
        private String passwordHash;
        /** SUPER_ADMIN / ADMIN / AUDITOR / READONLY（工单 0109 四态） */
        private String role;
        private String status;
        private Date createdAt;
    }
}
