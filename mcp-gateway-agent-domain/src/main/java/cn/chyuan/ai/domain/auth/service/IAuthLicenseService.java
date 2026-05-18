package cn.chyuan.ai.domain.auth.service;

import cn.chyuan.ai.domain.auth.model.entity.LicenseCommandEntity;

/**
 * 权限证书服务接口
 *
 * @author chyuan
 *         2026/2/22 10:11
 */
public interface IAuthLicenseService {

    boolean checkLicense(LicenseCommandEntity commandEntity);

}
