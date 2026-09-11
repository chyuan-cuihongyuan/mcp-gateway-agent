-- V0010（工单 0178 Y2）：feature_flag 特性开关表
CREATE TABLE IF NOT EXISTS mcp_feature_flag (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    flag_key    VARCHAR(128) NOT NULL,
    enabled     SMALLINT     NOT NULL DEFAULT 0,
    note        VARCHAR(256),
    operator    VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_flag_key UNIQUE (flag_key)
);
COMMENT ON TABLE mcp_feature_flag IS '特性开关表（未注册开关评估=默认关；变更经 FLAG_CHANGE 事件留痕）';

-- 幂等种子（示例）
INSERT INTO mcp_feature_flag (flag_key, enabled, note, operator)
VALUES ('demo.flag', 0, '示例开关（默认关）', 'seed')
ON CONFLICT (flag_key) DO NOTHING;
