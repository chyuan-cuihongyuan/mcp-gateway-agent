package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;

/**
 * CEL 工具治理求值端口（工单 0018）
 *
 * @author chyuan
 */
public interface ICelEvaluationService {

    /**
     * 判定当前主体在指定网关上对目标工具的访问是否放行。
     *
     * <p>求值语义（0011 决议）：GLOBAL → GATEWAY → VIRTUAL_KEY 三层全部适用规则
     * AND 合并，任一规则为 false 即拒绝；无适用规则放行；求值异常（变量缺失、
     * 程序损坏、非 bool 结果）一律按 fail-closed 拒绝。
     *
     * @param principal 认证主体（null 表示无统一认证上下文的遗留路径，直接放行）
     * @param gatewayId 网关 ID
     * @param method    JSON-RPC 方法（tools/list / tools/call）
     * @param toolName  目标工具名
     * @param toolSource 工具来源（LOCAL / PROTOCOL / EXTERNAL）
     * @return true 放行；false 拒绝（拒绝时已计入拦截指标）
     */
    boolean isToolAllowed(GovernancePrincipal principal, String gatewayId, String method,
            String toolName, String toolSource);
}
