package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * CEL 假想上下文试跑请求（工单 0076：治理台 playground）
 *
 * @author chyuan
 */
@Data
public class CelDryRunRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待试跑表达式 */
    private String expression;

    /** 假想网关 ID */
    private String gatewayId;

    /** 假想 MCP 方法（如 tools/call） */
    private String method;

    /** 假想工具名 */
    private String toolName;

    /** 假想来源渠道（外部挂接名或 LOCAL） */
    private String toolSource;

    /** 假想 JWT sub（留空为匿名主体） */
    private String jwtSub;

    /** 假想 JWT roles */
    private List<String> jwtRoles;

    /** 假想客户端 IP */
    private String clientIp;
}
