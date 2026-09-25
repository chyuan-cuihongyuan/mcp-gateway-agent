package cn.chyuan.ai.domain.permkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权器与令牌审计（工单 0864-0868 CX3-CX7，deno 权限思想）。
 * 未授权拒绝流与白名单放行/运行期授予幂等/权限令牌签发校验过期/令牌与许可撤销立即生效/授予拒绝撤销审计流水。
 */
public final class Authorizer {

    public record Decision(boolean allowed, String code, String message) {
        public static Decision allow() {
            return new Decision(true, "OK", "allowed");
        }
    }

    public static final class Token {
        public final String id;
        public final String subject;
        public final Map<PermFlags.Op, PermFlags.Scope> scopes;
        public long expiresAt;
        boolean revoked = false;

        Token(String id, String subject, Map<PermFlags.Op, PermFlags.Scope> scopes, long expiresAt) {
            this.id = id;
            this.subject = subject;
            this.scopes = scopes;
            this.expiresAt = expiresAt;
        }
    }

    public enum AuditKind { GRANT, DENY, ALLOW, REVOKE, ISSUE }

    public record AuditEvent(AuditKind kind, String op, String resource, long step) {
    }

    private final Map<PermFlags.Op, PermFlags.Scope> scopes = new LinkedHashMap<>();
    private final Map<String, Token> tokens = new HashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();
    private long now = 0;
    private long seq = 0;

    public Authorizer(List<PermFlags.Scope> initial) {
        for (PermFlags.Op op : PermFlags.Op.values()) {
            scopes.put(op, PermFlags.Scope.none(op));
        }
        for (PermFlags.Scope s : initial) {
            scopes.put(s.op(), s);
        }
    }

    public void advance() {
        now++;
    }

    /** 检查：NONE 拒绝/LIST 白名单/ALL 放行，事件入审计 */
    public Decision check(PermFlags.Op op, String resource) {
        PermFlags.Scope scope = scopes.get(op);
        Decision d;
        switch (scope.mode()) {
            case NONE -> d = new Decision(false, "E_PERM_NONE", op + " 未授权: " + resource);
            case ALL -> d = Decision.allow();
            default -> {
                boolean hit = scope.entries().stream().anyMatch(e -> PermFlags.matches(op, e, resource));
                d = hit ? Decision.allow()
                        : new Decision(false, "E_PERM_DENIED", op + " 白名单未命中: " + resource);
            }
        }
        audit.add(new AuditEvent(d.allowed() ? AuditKind.ALLOW : AuditKind.DENY, op.name(), resource, now));
        return d;
    }

    /** 运行期授予：NONE→LIST，LIST 并集去重（幂等），ALL 保持 */
    public void grant(PermFlags.Op op, List<String> entries) {
        PermFlags.Scope prev = scopes.get(op);
        if (prev.mode() == PermFlags.Mode.ALL) {
            audit.add(new AuditEvent(AuditKind.GRANT, op.name(), "*", now));
            return;
        }
        Set<String> merged = new java.util.LinkedHashSet<>(prev.entries());
        merged.addAll(entries);
        scopes.put(op, PermFlags.Scope.list(op, new ArrayList<>(merged)));
        audit.add(new AuditEvent(AuditKind.GRANT, op.name(), String.join(",", entries), now));
    }

    /** 撤销整类许可：立即 NONE；重复撤销幂等 */
    public void revokeGrant(PermFlags.Op op) {
        scopes.put(op, PermFlags.Scope.none(op));
        audit.add(new AuditEvent(AuditKind.REVOKE, op.name(), "*", now));
    }

    /** 签发权限令牌：绑定许可集与过期步 */
    public String issue(String subject, Map<PermFlags.Op, PermFlags.Scope> perms, long ttlSteps) {
        String id = "deno-tk-" + (++seq);
        tokens.put(id, new Token(id, subject, Map.copyOf(perms), now + ttlSteps));
        audit.add(new AuditEvent(AuditKind.ISSUE, "TOKEN", subject, now));
        return id;
    }

    /** 令牌校验：缺失/已撤销/过期/许可集逐级判定 */
    public Decision checkToken(String id, PermFlags.Op op, String resource) {
        Token token = tokens.get(id);
        if (token == null) {
            Decision d = new Decision(false, "E_TOKEN_UNKNOWN", "未知令牌: " + id);
            audit.add(new AuditEvent(AuditKind.DENY, op.name(), resource, now));
            return d;
        }
        if (token.revoked) {
            Decision d = new Decision(false, "E_TOKEN_REVOKED", "令牌已撤销: " + id);
            audit.add(new AuditEvent(AuditKind.DENY, op.name(), resource, now));
            return d;
        }
        if (now >= token.expiresAt) {
            Decision d = new Decision(false, "E_TOKEN_EXPIRED", "令牌已过期: " + id);
            audit.add(new AuditEvent(AuditKind.DENY, op.name(), resource, now));
            return d;
        }
        PermFlags.Scope scope = token.scopes.getOrDefault(op, PermFlags.Scope.none(op));
        Decision d = switch (scope.mode()) {
            case NONE -> new Decision(false, "E_PERM_NONE", op + " 令牌未授权");
            case ALL -> Decision.allow();
            default -> scope.entries().stream().anyMatch(e -> PermFlags.matches(op, e, resource))
                    ? Decision.allow()
                    : new Decision(false, "E_PERM_DENIED", op + " 令牌白名单未命中: " + resource);
        };
        audit.add(new AuditEvent(d.allowed() ? AuditKind.ALLOW : AuditKind.DENY, op.name(), resource, now));
        return d;
    }

    /** 撤销令牌：立即拒绝；重复撤销幂等 */
    public void revokeToken(String id) {
        Token token = tokens.get(id);
        if (token != null && !token.revoked) {
            token.revoked = true;
            audit.add(new AuditEvent(AuditKind.REVOKE, "TOKEN", id, now));
        }
    }

    public List<AuditEvent> auditFiltered(AuditKind kind, String opContains) {
        List<AuditEvent> out = new ArrayList<>();
        for (AuditEvent e : audit) {
            if (e.kind() == kind && (opContains == null || e.op().contains(opContains))) {
                out.add(e);
            }
        }
        return out;
    }

    public List<AuditEvent> auditAll() {
        return List.copyOf(audit);
    }
}
