package cn.chyuan.ai.domain.permkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 能力许可端口（工单 0869 CX8，deno 权限思想）。
 * parse·authorizer·grant·check·revoke 入口统一编排/与 vaultkernel 风格令牌串作签发主体输入形态只读联动（不 import）/
 * perm-kernel.enabled 默认关（开启才改变行为）。
 */
public interface PermPort {

    List<PermFlags.Scope> parse(List<String> args);

    Authorizer authorizer(List<PermFlags.Scope> scopes);

    /** vaultkernel 只读联动形态：vault 风格令牌签名串作为签发主体（形状数据不 import vaultkernel） */
    default String issueFromVault(Authorizer authorizer, String vaultToken,
                                 Map<PermFlags.Op, PermFlags.Scope> perms, long ttlSteps) {
        return authorizer.issue("vault:" + vaultToken, perms, ttlSteps);
    }

    static PermPort inMemory() {
        return new InMemoryPerm();
    }
}

final class InMemoryPerm implements PermPort {

    @Override
    public List<PermFlags.Scope> parse(List<String> args) {
        return PermFlags.parse(args);
    }

    @Override
    public Authorizer authorizer(List<PermFlags.Scope> scopes) {
        return new Authorizer(scopes);
    }
}
