package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.AuditLogResponseDTO;
import cn.chyuan.ai.api.dto.CelRuleResponseDTO;
import cn.chyuan.ai.api.dto.CelRuleUpsertRequestDTO;
import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.LoginResponseDTO;
import cn.chyuan.ai.api.dto.UsageDailyResponseDTO;
import cn.chyuan.ai.api.dto.UsageLogResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyCreateRequestDTO;
import cn.chyuan.ai.api.dto.VirtualKeyResponseDTO;
import cn.chyuan.ai.api.dto.VirtualKeyUpdateRequestDTO;
import cn.chyuan.ai.api.response.ResponsePage;

import java.util.List;

/**
 * admin 治理面服务接口（工单 0017：虚拟密钥/登录/审计；0018：CEL 规则）
 *
 * @author chyuan
 */
public interface IAdminGovernanceService {

    LoginResponseDTO login(LoginRequestDTO requestDTO);

    /** 创建密钥：明文凭证仅本次响应返回 */
    VirtualKeyResponseDTO createVirtualKey(VirtualKeyCreateRequestDTO requestDTO);

    VirtualKeyResponseDTO updateVirtualKey(Long id, VirtualKeyUpdateRequestDTO requestDTO);

    void revokeVirtualKey(Long id);

    /** 轮换密钥（工单 0049）：新明文仅本次返回，旧钥进入宽限期 */
    VirtualKeyResponseDTO regenerateVirtualKey(Long id);

    /** 禁用/解禁（工单 0052，立即生效） */
    void blockVirtualKey(Long id);
    void unblockVirtualKey(Long id);

    /** 批量禁用/解禁（工单 0052）：返回实际成功数 */
    int bulkBlockVirtualKeys(java.util.List<Long> ids, boolean block);

    /** 临时提额（工单 0052）：预算硬线上浮增量至到期时间 */
    void applyTempBudget(Long id, long increase, String expiresAt);

    void grantGateway(Long id, String gatewayId);

    void revokeGrantGateway(Long id, String gatewayId);

    VirtualKeyResponseDTO getVirtualKey(Long id);

    ResponsePage<List<VirtualKeyResponseDTO>> pageVirtualKeys(String keyword, int page, int size);

    ResponsePage<List<AuditLogResponseDTO>> pageAuditLogs(String resourceType, String resourceId, int page, int size);

    /** 创建 CEL 规则：保存时编译校验，非法表达式拒绝并返回原因（工单 0018） */
    CelRuleResponseDTO createCelRule(CelRuleUpsertRequestDTO requestDTO);

    /** 更新 CEL 规则：保存时编译校验 */
    CelRuleResponseDTO updateCelRule(Long id, CelRuleUpsertRequestDTO requestDTO);

    void deleteCelRule(Long id);

    CelRuleResponseDTO getCelRule(Long id);

    /** 仅校验表达式（不落库），返回 null 表示合法 */
    String validateCelExpression(String expression);

    ResponsePage<List<CelRuleResponseDTO>> pageCelRules(String keyword, int page, int size);

    // ---- 用量账本（工单 0046）----

    /** 用量明细分页（时间/密钥/工具/状态/流量类型过滤；密钥哈希只回前 8 位摘要） */
    ResponsePage<List<UsageLogResponseDTO>> pageUsageLogs(String fromDate, String toDate,
            Long virtualKeyId, String toolOrModel, String status, String trafficType,
            String channelId, int page, int size);

    /** 用量日聚合明细（区间内全部维度行） */
    List<UsageDailyResponseDTO> dailyUsageDetail(String fromDate, String toDate);

    /** 用量按日汇总（仪表盘趋势） */
    List<UsageDailyResponseDTO> dailyUsageTotals(String fromDate, String toDate);
}
