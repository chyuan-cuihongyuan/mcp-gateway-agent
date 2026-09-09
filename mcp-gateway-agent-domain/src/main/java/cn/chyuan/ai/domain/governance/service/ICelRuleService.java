package cn.chyuan.ai.domain.governance.service;

import cn.chyuan.ai.domain.governance.model.valobj.CelRuleVO;

import java.util.List;
import java.util.Map;

/**
 * CEL 规则服务端口（工单 0018；0048 增 dry-run）
 *
 * @author chyuan
 */
public interface ICelRuleService {

    /** 校验表达式可编译且变量已声明；返回 null 表示合法，否则返回可读错误原因 */
    String validateExpression(String expression);

    /** 创建规则：保存前编译校验，非法表达式抛 AppException（附原因）；写审计 */
    CelRuleVO create(CelRuleVO rule);

    /** 更新规则：保存前编译校验；写审计并失效本实例规则快照 */
    CelRuleVO update(CelRuleVO rule);

    /** 删除规则；写审计并失效本实例规则快照 */
    void delete(Long id);

    CelRuleVO getById(Long id);

    List<CelRuleVO> page(String keyword, int page, int size);

    long count(String keyword);

    /**
     * 当前适用的已编译规则快照（30s TTL，写操作即时失效本实例；
     * 0011 决策②热更新机制）。
     */
    List<CompiledCelRule> activeRuleSnapshot();

    /**
     * 在线试跑（工单 0048，playground/模板实例化预检复用）：
     * 以给定变量上下文求值表达式，不落库、不计数。
     */
    DryRunResult dryRun(String expression, Map<String, Object> variables);

    /**
     * 假想调用上下文试跑（工单 0076：治理台 playground 入口）：
     * 由工具名/来源渠道/网关/JWT 身份/客户端 IP 组装完整变量面后求值，
     * 与线上求值共用 CelVariables 绑定，试出的行为与线上一致。
     */
    DryRunResult dryRunWithContext(String expression, String gatewayId, String method,
            String toolName, String toolSource, String jwtSub,
            java.util.List<String> jwtRoles, String clientIp);

    /**
     * dry-run 结果：PASS/DENY 为正常求值；COMPILE_ERROR/RUNTIME_ERROR 为故障形态
     * （与线上 fail-closed 语义对应——线上故障即拒绝）。
     */
    record DryRunResult(String outcome, boolean allowed, String detail) {

        public static DryRunResult pass() {
            return new DryRunResult("PASS", true, null);
        }

        public static DryRunResult denied() {
            return new DryRunResult("DENIED", false, null);
        }

        public static DryRunResult compileError(String detail) {
            return new DryRunResult("COMPILE_ERROR", false, detail);
        }

        public static DryRunResult runtimeError(String detail) {
            return new DryRunResult("RUNTIME_ERROR", false, detail);
        }
    }
}
