package cn.chyuan.ai.domain.governance.adapter.repository;

import cn.chyuan.ai.domain.governance.model.valobj.VirtualKeyVO;

import java.util.Date;
import java.util.List;

/**
 * 虚拟密钥仓储端口（工单 0017；domain 不引框架，infrastructure 落地 MyBatis）
 *
 * @author chyuan
 */
public interface IVirtualKeyRepository {

    /**
     * 按凭证哈希查询可用密钥（含状态/过期判定调用方自理）
     *
     * @return 未命中返回 null
     */
    VirtualKeyVO findByHash(String apiKeyHash);

    VirtualKeyVO findById(Long id);

    /**
     * 新建密钥（凭证哈希入库）
     *
     * @return 带主键回填的 VO
     */
    VirtualKeyVO insert(String apiKeyHash, VirtualKeyVO vo);

    int updateStatus(Long id, String status);

    int updateMeta(Long id, VirtualKeyVO vo);

    /** 最后活跃时间更新（认证命中去抖后调用，工单 0045） */
    void touchLastActive(Long id);

    /** 密钥↔网关授权 */
    boolean existsGrant(Long keyId, String gatewayId);

    void insertGrant(Long keyId, String gatewayId);

    int deleteGrant(Long keyId, String gatewayId);

    List<String> queryGrants(Long keyId);

    /** 分页列表（脱敏形态） */
    List<VirtualKeyVO> queryPage(String keyword, int page, int size);

    long count(String keyword);

    /**
     * 存量 gw- apiKey 等价迁移扫描源：返回 (gatewayId, apiKey, rateLimit(次/小时), expireTime, status) 元组
     * —— 由 infrastructure 查询旧表 mcp_gateway_auth
     */
    List<LegacyAuthRecord> queryLegacyAuthRecords();

    /**
     * 迁移结果持久化（新密钥 + 授权，已存在则跳过）
     *
     * @return 实际迁移条数
     */
    int migrateLegacy(String apiKeyHash, String keyName, String status, Integer rpmLimit, Date expiresAt, String gatewayId);

    /** 旧表记录（迁移扫描用） */
    record LegacyAuthRecord(String gatewayId, String apiKey, Integer rateLimitPerHour, Date expireTime, Integer status) {
    }
}
