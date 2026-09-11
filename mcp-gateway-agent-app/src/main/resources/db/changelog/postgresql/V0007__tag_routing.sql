-- =============================================================================
-- V0007__tag_routing.sql —— tag 路由规则（四期工单 0160，PostgreSQL changelog）
-- 内容：
--   1) mcp_routing_rule 建表（tag 键值 → 渠道组/优先级；表 22，编号顺延）。
--   2) mcp_llm_channel 增 channel_group 列（路由限定目标；空=默认组 default）。
-- 运行口径：请求 tags 命中启用规则（"key=value" 精确匹配，大小写归一）→ 调度限定组内渠道，
--           多规则命中取最高优先级；不命中=全渠道兼容；记账 tags 补 route:<group> 标记。
-- 说明：MySQL 侧见 resources/sql/mysql-upgrade-v4-resilience.sql。
-- =============================================================================

CREATE TABLE IF NOT EXISTS mcp_routing_rule (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_name       VARCHAR(128) NOT NULL,
  tag_key         VARCHAR(64)  NOT NULL,
  tag_value       VARCHAR(64)  NOT NULL,
  channel_group_id VARCHAR(64) NOT NULL,
  priority        INT          NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_routing_rule_name UNIQUE (rule_name)
);
COMMENT ON TABLE mcp_routing_rule IS 'tag 路由规则（工单 0160）';
CREATE INDEX IF NOT EXISTS idx_routing_rule_tag ON mcp_routing_rule (tag_key, tag_value);

ALTER TABLE mcp_llm_channel ADD COLUMN IF NOT EXISTS channel_group VARCHAR(64) NULL;
COMMENT ON COLUMN mcp_llm_channel.channel_group IS '渠道组（工单 0160 tag 路由限定；空=默认组 default）';
