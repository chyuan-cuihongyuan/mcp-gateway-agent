-- V0014（工单 0224 AD5）：特性开关目标定向（借鉴 Unleash targeting）
ALTER TABLE mcp_feature_flag ADD COLUMN tenant_whitelist VARCHAR(512);
ALTER TABLE mcp_feature_flag ADD COLUMN user_whitelist VARCHAR(512);
ALTER TABLE mcp_feature_flag ADD COLUMN percentage INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN mcp_feature_flag.tenant_whitelist IS '租户白名单 CSV（命中即开，优先于百分比）';
COMMENT ON COLUMN mcp_feature_flag.user_whitelist IS '用户白名单 CSV（命中即开，优先于百分比）';
COMMENT ON COLUMN mcp_feature_flag.percentage IS '灰度百分比 0-100（按键稳定哈希，stickiness）';
