package cn.chyuan.ai.domain.governance.service;

import dev.cel.runtime.CelRuntime;

/**
 * 已编译 CEL 规则（工单 0018）
 *
 * <p>{@code program == null} 表示该规则在快照构建时编译失败（库中被绕过校验改坏），
 * 求值一律按 fail-closed 拒绝处理。
 *
 * @author chyuan
 */
public record CompiledCelRule(
        Long id,
        String ruleName,
        String scopeType,
        String gatewayId,
        Long virtualKeyId,
        CelRuntime.Program program) {
}
