-- =============================================================================
-- V0001__pg_baseline.sql —— MCP 网关 PostgreSQL 基线（三期工单 0125，MigrationPlan 首批脚本）
-- 内容：网关终态 20 表 = 15 治理表（源 dev-ops/postgresql/01-gateway-seed.sql）
--       + 一期核心 5 表（源 resources/sql/core-schema-postgresql.sql）。
-- 幂等：全部 CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS / COMMENT ON。
-- 类型映射与唯一键口径见两份源文件头部说明（IDENTITY 主键、TIMESTAMP、SMALLINT
-- 保 Integer PO 映射、ON UPDATE CURRENT_TIMESTAMP 移除改应用层维护）。
-- =============================================================================

-- ───────────────────────── 治理 15 表（二期终态） ─────────────────────────

-- 1. 虚拟密钥主表（vk-，SHA-256 哈希存储；终态含生命周期/轮换/预算列）
CREATE TABLE IF NOT EXISTS mcp_virtual_key (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  api_key_hash          CHAR(64)      NOT NULL,
  key_name              VARCHAR(128)  NOT NULL,
  owner_user_id         VARCHAR(64)   NULL,
  tenant_id             VARCHAR(64)   NULL,
  status                VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
  expires_at            TIMESTAMP     NULL,
  last_active_at        TIMESTAMP     NULL,
  ip_allow_list         VARCHAR(1024) NULL,
  rotation_count        INT           NOT NULL DEFAULT 0,
  last_rotation_at      TIMESTAMP     NULL,
  prev_key_hash         VARCHAR(64)   NULL,
  grace_until           TIMESTAMP     NULL,
  budget_soft           BIGINT        NULL,
  budget_hard           BIGINT        NULL,
  budget_duration_hours INT           NULL,
  budget_reset_at       TIMESTAMP     NULL,
  budget_used           BIGINT        NOT NULL DEFAULT 0,
  cost_soft_limit       DECIMAL(12,6) NULL,
  cost_hard_limit       DECIMAL(12,6) NULL,
  skip_guardrail_allowed BOOLEAN      NULL,
  temp_budget_hard      BIGINT        NULL,
  temp_budget_expires   TIMESTAMP     NULL,
  rpm_limit             INT           NULL,
  daily_request_limit   INT           NULL,
  daily_tool_call_limit INT           NULL,
  tpm_limit             INT           NULL,
  daily_cost_limit      DECIMAL(12,4) NULL,
  created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_api_key_hash UNIQUE (api_key_hash)
);
COMMENT ON TABLE mcp_virtual_key IS 'MCP 虚拟密钥（vk-）';
CREATE INDEX IF NOT EXISTS idx_vk_status ON mcp_virtual_key (status);

-- 2. 密钥↔网关多对多授权（ON CONFLICT 依赖 uk_key_gateway）
CREATE TABLE IF NOT EXISTS mcp_virtual_key_gateway (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  key_id      BIGINT      NOT NULL,
  gateway_id  VARCHAR(64) NOT NULL,
  granted_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_key_gateway UNIQUE (key_id, gateway_id)
);
COMMENT ON TABLE mcp_virtual_key_gateway IS '虚拟密钥↔网关授权';
CREATE INDEX IF NOT EXISTS idx_vkg_gateway ON mcp_virtual_key_gateway (gateway_id);

-- 3. CEL 规则表
CREATE TABLE IF NOT EXISTS mcp_cel_rule (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_name      VARCHAR(128) NOT NULL,
  expression     TEXT         NOT NULL,
  scope_type     VARCHAR(16)  NOT NULL,
  gateway_id     VARCHAR(64)  NULL,
  virtual_key_id BIGINT       NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_rule_name UNIQUE (rule_name)
);
COMMENT ON TABLE mcp_cel_rule IS 'CEL 工具治理规则';
CREATE INDEX IF NOT EXISTS idx_cel_scope ON mcp_cel_rule (scope_type, gateway_id, virtual_key_id);

-- 4. admin 用户表（JWT 角色登录）
CREATE TABLE IF NOT EXISTS mcp_admin_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(16)  NOT NULL DEFAULT 'ADMIN',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_username UNIQUE (username)
);
COMMENT ON TABLE mcp_admin_user IS 'admin 控制台用户';

-- 5. 审计日志表（只增不改）
CREATE TABLE IF NOT EXISTS mcp_audit_log (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor         VARCHAR(128) NOT NULL,
  type          VARCHAR(16)  NOT NULL DEFAULT 'ADMIN',
  action        VARCHAR(64)  NOT NULL,
  resource_type VARCHAR(32)  NOT NULL,
  resource_id   VARCHAR(64)  NOT NULL,
  before_json   TEXT         NULL,
  after_json    TEXT         NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE mcp_audit_log IS '治理面审计日志';
CREATE INDEX IF NOT EXISTS idx_audit_resource ON mcp_audit_log (resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON mcp_audit_log (created_at);

-- 6. 外部 MCP 挂接配置（终态含渠道化调度/熔断计数/鉴权列）
CREATE TABLE IF NOT EXISTS mcp_external_attach (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway_id       VARCHAR(64)  NOT NULL,
  attach_name      VARCHAR(64)  NOT NULL,
  transport_type   VARCHAR(16)  NOT NULL,
  endpoint         VARCHAR(512) NULL,
  api_key          VARCHAR(512) NULL,
  command          VARCHAR(512) NULL,
  args             VARCHAR(2048) NULL,
  env              VARCHAR(2048) NULL,
  request_timeout_ms INT        NOT NULL DEFAULT 30000,
  status           SMALLINT    NOT NULL DEFAULT 1,
  weight           INT         NOT NULL DEFAULT 1,
  priority         INT         NOT NULL DEFAULT 0,
  test_time        TIMESTAMP   NULL,
  response_time_ms BIGINT      NULL,
  cooldown_until   TIMESTAMP   NULL,
  fail_connect     INT         NOT NULL DEFAULT 0,
  fail_timeout     INT         NOT NULL DEFAULT 0,
  fail_http        INT         NOT NULL DEFAULT 0,
  auth_type        VARCHAR(16) NOT NULL DEFAULT 'NONE',
  auth_config      VARCHAR(2048) NULL,
  connect_status   VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  connect_error    VARCHAR(1024) NULL,
  connect_time     TIMESTAMP   NULL,
  create_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_gateway_attach UNIQUE (gateway_id, attach_name)
);
COMMENT ON TABLE mcp_external_attach IS '外部 MCP server 挂接配置（渠道化）';
CREATE INDEX IF NOT EXISTS idx_attach_gateway ON mcp_external_attach (gateway_id);

-- 7. 用量明细账本（工单 0046，LiteLLM SpendLogs 口径）
CREATE TABLE IF NOT EXISTS mcp_usage_log (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id        VARCHAR(64)  NOT NULL,
  virtual_key_id    BIGINT       NOT NULL DEFAULT 0,
  api_key_hash      VARCHAR(64)  NULL,
  gateway_id        VARCHAR(64)  NULL,
  traffic_type      VARCHAR(8)   NOT NULL DEFAULT 'MCP',
  tool_or_model     VARCHAR(128) NOT NULL DEFAULT '',
  channel_id        VARCHAR(128) NOT NULL DEFAULT '',
  status            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
  duration_ms       INT          NULL,
  prompt_tokens     BIGINT       NULL,
  completion_tokens BIGINT       NULL,
  cost              DECIMAL(12,6) NULL,
  tags              VARCHAR(170) NULL,
  cache_hit         SMALLINT     NULL,
  client_ip         VARCHAR(64)  NULL,
  session_id        VARCHAR(128) NULL,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE mcp_usage_log IS '用量明细账本';
CREATE INDEX IF NOT EXISTS idx_usage_created ON mcp_usage_log (created_at);
CREATE INDEX IF NOT EXISTS idx_usage_key_time ON mcp_usage_log (virtual_key_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_tool ON mcp_usage_log (tool_or_model);

-- 8. 用量日度聚合（惰性 upsert 累加；ON CONFLICT 依赖 uk_daily_dim）
CREATE TABLE IF NOT EXISTS mcp_usage_daily (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  stat_date         DATE          NOT NULL,
  virtual_key_id    BIGINT        NOT NULL DEFAULT 0,
  tool_or_model     VARCHAR(128)  NOT NULL DEFAULT '',
  channel_id        VARCHAR(128)  NOT NULL DEFAULT '',
  call_count        BIGINT        NOT NULL DEFAULT 0,
  fail_count        BIGINT        NOT NULL DEFAULT 0,
  total_duration_ms BIGINT        NOT NULL DEFAULT 0,
  token_sum         BIGINT        NOT NULL DEFAULT 0,
  updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_daily_dim UNIQUE (stat_date, virtual_key_id, tool_or_model, channel_id)
);
COMMENT ON TABLE mcp_usage_daily IS '用量日度聚合（应用层维护 updated_at）';
CREATE INDEX IF NOT EXISTS idx_daily_date ON mcp_usage_daily (stat_date);

-- 9. 告警 webhook 端点（工单 0051）
CREATE TABLE IF NOT EXISTS mcp_webhook_endpoint (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(64)  NOT NULL,
  url         VARCHAR(512) NOT NULL,
  events      VARCHAR(1024) NULL,
  secret      VARCHAR(128) NULL,
  enabled     SMALLINT     NOT NULL DEFAULT 1,
  create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE mcp_webhook_endpoint IS '治理告警 webhook 端点';

-- 10. CEL 规则模板（工单 0057；内置模板启动幂等种子）
CREATE TABLE IF NOT EXISTS mcp_cel_rule_template (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code           VARCHAR(64)   NOT NULL,
  name           VARCHAR(128)  NOT NULL,
  expression     VARCHAR(2048) NOT NULL,
  variables_desc VARCHAR(2048) NULL,
  builtin        SMALLINT      NOT NULL DEFAULT 0,
  create_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_template_code UNIQUE (code)
);
COMMENT ON TABLE mcp_cel_rule_template IS 'CEL 规则模板';

-- 11/12. 本地 Prompt / Resource（工单 0053）
CREATE TABLE IF NOT EXISTS mcp_prompt (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway_id     VARCHAR(64)  NOT NULL,
  name           VARCHAR(128) NOT NULL,
  description    VARCHAR(512) NULL,
  arguments_json VARCHAR(2048) NULL,
  template       TEXT         NOT NULL,
  status         SMALLINT     NOT NULL DEFAULT 1,
  create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_prompt UNIQUE (gateway_id, name)
);
COMMENT ON TABLE mcp_prompt IS '本地 Prompt';

CREATE TABLE IF NOT EXISTS mcp_resource (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway_id  VARCHAR(64)  NOT NULL,
  uri         VARCHAR(512) NOT NULL,
  name        VARCHAR(128) NULL,
  description VARCHAR(512) NULL,
  mime_type   VARCHAR(128) NULL,
  content     TEXT         NOT NULL,
  status      SMALLINT     NOT NULL DEFAULT 1,
  create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_resource UNIQUE (gateway_id, uri)
);
COMMENT ON TABLE mcp_resource IS '本地 Resource';

-- 13. LLM 渠道（工单 0063，one-api 口径；终态含重试/探测/余额列）
CREATE TABLE IF NOT EXISTS mcp_llm_channel (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name             VARCHAR(64)   NOT NULL,
  base_url         VARCHAR(512)  NOT NULL,
  credential       VARCHAR(2048) NULL,
  models           VARCHAR(1024) NOT NULL,
  model_mapping    VARCHAR(2048) NULL,
  weight           INT           NOT NULL DEFAULT 1,
  priority         INT           NOT NULL DEFAULT 0,
  status           SMALLINT      NOT NULL DEFAULT 1,
  timeout_ms       INT           NOT NULL DEFAULT 60000,
  test_time        TIMESTAMP     NULL,
  response_time_ms BIGINT        NULL,
  create_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_channel_name UNIQUE (name)
);
COMMENT ON TABLE mcp_llm_channel IS 'LLM 渠道';

-- 14. 模型计价（工单 0085，LiteLLM cost map 口径）
CREATE TABLE IF NOT EXISTS mcp_model_pricing (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model            VARCHAR(128)  NOT NULL,
    input_cost_per_m DECIMAL(12,4) NULL,
    output_cost_per_m DECIMAL(12,4) NULL,
    currency         VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    enabled          SMALLINT      NOT NULL DEFAULT 1,
    remark           VARCHAR(256)  NULL,
    create_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pricing_model UNIQUE (model)
);
COMMENT ON TABLE mcp_model_pricing IS '模型计价';

-- 15. 治理护栏（工单 0091，内容安全执行链）
CREATE TABLE IF NOT EXISTS mcp_guardrail (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(128)  NOT NULL,
    type         VARCHAR(32)   NOT NULL,
    mode         VARCHAR(16)   NOT NULL,
    config       VARCHAR(2048) NULL,
    traffic_mask VARCHAR(8)    NOT NULL DEFAULT 'ALL',
    priority     INT           NOT NULL DEFAULT 100,
    enabled      SMALLINT      NOT NULL DEFAULT 1,
    create_time  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_guardrail_name UNIQUE (name)
);
COMMENT ON TABLE mcp_guardrail IS '治理护栏';

-- ───────────────────────── 一期核心 5 表 ─────────────────────────

-- 16. MCP 网关配置表（mcp_gateway 域主表；uk_gateway_id 为 upsert 冲突键）
CREATE TABLE IF NOT EXISTS mcp_gateway (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway_id   VARCHAR(64)  NOT NULL,
  gateway_name VARCHAR(128) NOT NULL,
  gateway_desc VARCHAR(512) NULL,
  version      VARCHAR(32)  NOT NULL,
  auth         SMALLINT     NOT NULL DEFAULT 0,
  status       SMALLINT     NOT NULL DEFAULT 1,
  create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_gateway_id UNIQUE (gateway_id)
);
COMMENT ON TABLE mcp_gateway IS 'MCP网关配置表';

-- 17. 用户网关权限表（网关接入凭据）
CREATE TABLE IF NOT EXISTS mcp_gateway_auth (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway_id  VARCHAR(64)  NOT NULL,
  api_key     VARCHAR(256) NOT NULL,
  rate_limit  INT          NOT NULL DEFAULT 0,
  expire_time TIMESTAMP    NULL,
  status      SMALLINT     NOT NULL DEFAULT 1,
  create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_gateway_apikey UNIQUE (gateway_id, api_key)
);
COMMENT ON TABLE mcp_gateway_auth IS '用户网关权限表';

-- 18. MCP 网关工具表（uk_tool_id 为 upsert 冲突键）
CREATE TABLE IF NOT EXISTS mcp_gateway_tool (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  gateway_id       VARCHAR(64)  NOT NULL,
  tool_id          BIGINT       NOT NULL,
  tool_name        VARCHAR(256) NOT NULL,
  tool_type        VARCHAR(32)  NOT NULL DEFAULT 'function',
  tool_description TEXT         NULL,
  tool_version     VARCHAR(32)  NULL,
  protocol_id      BIGINT       NULL,
  protocol_type    VARCHAR(32)  NULL,
  create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_tool_id UNIQUE (tool_id)
);
COMMENT ON TABLE mcp_gateway_tool IS 'MCP网关工具表';
CREATE INDEX IF NOT EXISTS idx_tool_gateway_name ON mcp_gateway_tool (gateway_id, tool_name);

-- 19. MCP 协议 HTTP 配置表（一个 protocol_id 允许多行版本化，取最新启用一条）
CREATE TABLE IF NOT EXISTS mcp_protocol_http (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  protocol_id  BIGINT       NOT NULL,
  http_url     VARCHAR(512) NOT NULL,
  http_method  VARCHAR(16)  NOT NULL DEFAULT 'GET',
  http_headers VARCHAR(2048) NULL,
  timeout      INT          NOT NULL DEFAULT 30000,
  retry_times  INT          NOT NULL DEFAULT 0,
  status       SMALLINT     NOT NULL DEFAULT 1,
  create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE mcp_protocol_http IS 'MCP协议HTTP配置表';
CREATE INDEX IF NOT EXISTS idx_protocol_http_pid ON mcp_protocol_http (protocol_id);

-- 20. MCP 协议映射配置表（一个 protocol_id 多条字段映射行，无唯一键）
CREATE TABLE IF NOT EXISTS mcp_protocol_mapping (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  protocol_id  BIGINT      NOT NULL,
  mapping_type VARCHAR(16) NOT NULL,
  parent_path  VARCHAR(256) NULL,
  field_name   VARCHAR(128) NOT NULL,
  mcp_path     VARCHAR(512) NOT NULL,
  mcp_type     VARCHAR(32)  NOT NULL,
  mcp_desc     VARCHAR(512) NULL,
  is_required  SMALLINT    NOT NULL DEFAULT 0,
  sort_order   INT         NOT NULL DEFAULT 0,
  create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE mcp_protocol_mapping IS 'MCP映射配置表';
CREATE INDEX IF NOT EXISTS idx_mapping_pid ON mcp_protocol_mapping (protocol_id);
