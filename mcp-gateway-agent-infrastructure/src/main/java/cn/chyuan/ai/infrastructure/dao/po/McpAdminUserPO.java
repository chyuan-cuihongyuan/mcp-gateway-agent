package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * admin 用户表（工单 0017）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpAdminUserPO implements Serializable {

    private Long id;

    private String username;

    /** BCrypt */
    private String passwordHash;

    /** ADMIN（读写）/ READONLY（只读） */
    private String role;

    /** ACTIVE / DISABLED */
    private String status;

    private Date createdAt;

    private Date updatedAt;
}
