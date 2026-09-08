package cn.chyuan.ai.domain.governance.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 治理面认证主体（统一认证过滤器产出，工单 0017）
 *
 * <p>承载一次请求认证后的全部身份与配额元数据，
 * 供 CEL 求值（0018）与配额限流（0019）消费。
 *
 * @author chyuan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernancePrincipal {

    /** 主体写入 Servlet Request Attribute 的键（认证过滤器产出，委派路由/MCP 传输消费；工单 0020 起为跨层单一来源） */
    public static final String REQUEST_ATTR = "GOVERNANCE_PRINCIPAL";

    /** 认证类型 */
    private AuthType authType;

    /** 命中的虚拟密钥 ID（仅 VIRTUAL_KEY） */
    private Long virtualKeyId;

    /** 凭证 SHA-256 哈希（会话元数据关联用） */
    private String apiKeyHash;

    /** 密钥绑定的身份（JWT 认证时为 sub） */
    private String ownerUserId;

    /** 租户标识 */
    private String tenantId;

    /** 角色（JWT 认证来自 roles claim；vk 认证为空） */
    private List<String> roles;

    /** 来源 IP（认证过滤器提取，用量账本/CEL client.ip 消费，工单 0045/0046） */
    private String clientIp;

    /** 预算软线（工单 0050；空=未启用） */
    private Long budgetSoft;

    /** 预算硬线（工单 0050；空=未启用） */
    private Long budgetHard;

    /** 每分钟请求限额（NULL 不限，-1 表示未知） */
    private Integer rpmLimit;

    /** 日请求配额（NULL 不限，-1 表示未知） */
    private Integer dailyRequestLimit;

    /** 日工具调用配额（NULL 不限，-1 表示未知） */
    private Integer dailyToolCallLimit;

    /** 匿名主体（网关未开启强校验时放行） */
    public static GovernancePrincipal anonymous() {
        return GovernancePrincipal.builder().authType(AuthType.ANONYMOUS).build();
    }

    /** 认证类型 */
    public enum AuthType {
        /** 匿名（网关未开启强校验） */
        ANONYMOUS,
        /** 虚拟密钥（vk-）认证 */
        VIRTUAL_KEY,
        /** JWT 认证 */
        JWT
    }
}
