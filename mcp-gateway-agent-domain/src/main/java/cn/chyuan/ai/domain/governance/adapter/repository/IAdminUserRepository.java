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

    /** admin 用户值对象 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class AdminUserVO {
        private Long id;
        private String username;
        private String passwordHash;
        /** ADMIN（读写）/ READONLY（只读） */
        private String role;
        private String status;
        private Date createdAt;
    }
}
