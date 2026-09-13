-- V0016（工单 0261 AH2）：policy_definition 策略定义表（借鉴 Casbin PERM）
CREATE TABLE IF NOT EXISTS mcp_policy_definition (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    sub_pattern    VARCHAR(256) NOT NULL,
    obj_pattern    VARCHAR(256) NOT NULL,
    act_pattern    VARCHAR(256) NOT NULL,
    condition_expr VARCHAR(1024),
    effect         VARCHAR(16)  NOT NULL DEFAULT 'DENY',
    priority       INT          NOT NULL DEFAULT 0,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    note           VARCHAR(256),
    operator       VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE mcp_policy_definition IS '策略定义表（PERM：sub/obj/act 模式 + 条件表达式 + effect + 优先级；deny-overrides 合并）';
COMMENT ON COLUMN mcp_policy_definition.effect IS 'ALLOW/DENY；deny-overrides：任一 DENY 命中即拒绝';
CREATE UNIQUE INDEX IF NOT EXISTS uk_policy_name ON mcp_policy_definition (name);
