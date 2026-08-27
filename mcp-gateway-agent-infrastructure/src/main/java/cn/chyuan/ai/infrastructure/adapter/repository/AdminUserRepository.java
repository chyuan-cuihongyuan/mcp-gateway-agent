package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.IAdminUserRepository;
import cn.chyuan.ai.infrastructure.dao.IAdminUserDao;
import cn.chyuan.ai.infrastructure.dao.po.McpAdminUserPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

/**
 * admin 用户仓储实现（工单 0017）
 *
 * @author chyuan
 */
@Repository
public class AdminUserRepository implements IAdminUserRepository {

    @Resource
    private IAdminUserDao adminUserDao;

    @Override
    public AdminUserVO findByUsername(String username) {
        McpAdminUserPO po = adminUserDao.queryByUsername(username);
        if (po == null) {
            return null;
        }
        return AdminUserVO.builder()
                .id(po.getId())
                .username(po.getUsername())
                .passwordHash(po.getPasswordHash())
                .role(po.getRole())
                .status(po.getStatus())
                .createdAt(po.getCreatedAt())
                .build();
    }

    @Override
    public boolean existsAny() {
        Integer count = adminUserDao.countAll();
        return count != null && count > 0;
    }

    @Override
    public void insert(String username, String passwordHash, String role) {
        adminUserDao.insert(McpAdminUserPO.builder()
                .username(username)
                .passwordHash(passwordHash)
                .role(role)
                .status("ACTIVE")
                .build());
    }
}
