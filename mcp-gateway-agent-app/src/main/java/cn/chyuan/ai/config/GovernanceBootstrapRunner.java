package cn.chyuan.ai.config;

import cn.chyuan.ai.domain.governance.adapter.codec.IPasswordCodec;
import cn.chyuan.ai.domain.governance.adapter.repository.IAdminUserRepository;
import cn.chyuan.ai.domain.governance.service.IVirtualKeyService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 治理面启动引导（工单 0017）
 *
 * <ol>
 *   <li>admin 用户引导：库中无用户且配置了 GOVERNANCE_ADMIN_PASSWORD 时，
 *       以 BCrypt 创建默认 admin（ADMIN 角色）；未配置则告警（登录不可用）。</li>
 *   <li>存量 gw- apiKey 等价迁移（0011 决策④：启动幂等，调用方无感）。</li>
 * </ol>
 *
 * @author chyuan
 */
@Slf4j
@Component
public class GovernanceBootstrapRunner implements ApplicationRunner {

    @Resource
    private IAdminUserRepository adminUserRepository;

    @Resource
    private IPasswordCodec passwordCodec;

    @Resource
    private IVirtualKeyService virtualKeyService;

    @Resource
    private Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        bootstrapAdminUser();
        migrateLegacyKeys();
    }

    private void bootstrapAdminUser() {
        try {
            if (adminUserRepository.existsAny()) {
                return;
            }
            String initialPassword = environment.getProperty("GOVERNANCE_ADMIN_PASSWORD");
            if (initialPassword == null || initialPassword.isBlank()) {
                log.warn("admin 用户表为空且未配置 GOVERNANCE_ADMIN_PASSWORD —— admin 登录不可用；"
                        + "设置该环境变量后重启以引导默认 admin 用户");
                return;
            }
            adminUserRepository.insert("admin", passwordCodec.encode(initialPassword), "ADMIN");
            log.info("已引导默认 admin 用户（ADMIN 角色），请尽快修改密码");
        } catch (Exception e) {
            log.error("admin 用户引导失败（表未建或库不可达）", e);
        }
    }

    private void migrateLegacyKeys() {
        try {
            int migrated = virtualKeyService.migrateLegacyKeys();
            if (migrated > 0) {
                log.info("存量 gw- 密钥迁移完成：本次迁移 {} 条（幂等，已迁移条目自动跳过）", migrated);
            }
        } catch (Exception e) {
            // 表未建（先执行 sql/governance-schema.sql）或旧表不存在时降级为告警，不阻断启动
            log.warn("存量 gw- 密钥迁移跳过：{}（请确认已执行 resources/sql/governance-schema.sql）", e.getMessage());
        }
    }
}
