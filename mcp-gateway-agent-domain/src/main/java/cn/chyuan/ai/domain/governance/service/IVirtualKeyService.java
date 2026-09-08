package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.entity.VirtualKeyCommandEntity;
import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;

import java.util.List;

/**
 * 虚拟密钥管理服务（工单 0017）
 *
 * @author chyuan
 */
public interface IVirtualKeyService {

    /** 创建密钥：生成 vk- 凭证，明文仅本次返回 */
    VirtualKeyVO create(VirtualKeyCommandEntity command);

    /** 更新元数据（配额/过期/名称/身份） */
    VirtualKeyVO update(VirtualKeyCommandEntity command);

    /** 吊销（REVOKED，不可恢复） */
    void revoke(Long id);

    /**
     * 轮换（工单 0049）：生成新明文（仅本次返回），旧凭证进入宽限期并存，
     * 宽限期外失效；再次轮换时上一代立即失效（只保留一代）。
     */
    VirtualKeyVO regenerate(Long id);

    /** 授权给网关 */
    void grant(Long id, String gatewayId);

    /** 回收网关授权 */
    void revokeGrant(Long id, String gatewayId);

    VirtualKeyVO getById(Long id);

    List<String> getGrants(Long id);

    List<VirtualKeyVO> page(String keyword, int page, int size);

    long count(String keyword);

    /** 存量 gw- apiKey 等价迁移（启动幂等，0011 决策④）：返回本次实际迁移条数 */
    int migrateLegacyKeys();

    /** 禁用（工单 0052：DISABLED，立即失效缓存，可 unblock 恢复） */
    void block(Long id);

    /** 解禁（工单 0052：恢复 ACTIVE） */
    void unblock(Long id);

    /** 批量 block/unblock（工单 0052）：返回实际成功数 */
    int bulkUpdateStatus(java.util.List<Long> ids, boolean block);

    /** 临时提额（工单 0052：生效硬线上浮 increase 至 expiresAt，过期惰性回落） */
    void applyTempBudget(Long id, long increase, java.util.Date expiresAt);
}