package cn.chyuan.ai.cases.admin.auth;

import cn.chyuan.ai.cases.admin.IAdminAuthService;
import cn.chyuan.ai.domain.auth.model.entity.RegisterCommandEntity;
import cn.chyuan.ai.domain.auth.service.IAuthRegisterService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 运营；认证配置管理
 *
 * @author chyuan
 *         2026/3/24 08:11
 */
@Slf4j
@Service
public class AdminAuthService implements IAdminAuthService {

    @Resource
    private IAuthRegisterService authRegisterService;

    @Override
    public void saveGatewayAuth(RegisterCommandEntity commandEntity) {
        authRegisterService.register(commandEntity);
    }

    @Override
    public void deleteGatewayAuth(String gatewayId) {
        authRegisterService.deleteGatewayAuth(gatewayId);
    }

}
