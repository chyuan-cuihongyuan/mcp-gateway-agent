-- =============================================================================
-- 治理面表结构（工单 0011 定稿 / 0017 落地）
-- 执行方式：mysql -u<user> -p mcp_gateway_agent < governance-schema.sql
-- 幂等性：CREATE TABLE IF NOT EXISTS，可重复执行
-- =============================================================================

-- 1. 虚拟密钥主表（vk-，SHA-256 哈希存储）
CREATE TABLE IF NOT EXISTS mcp_virtual_key (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  api_key_hash          CHAR(64)      NOT NULL COMMENT 'vk- 凭证的 SHA-256 十六进制哈希',
  key_name              VARCHAR(128)  NOT NULL COMMENT '管理员可读名称',
  owner_user_id         VARCHAR(64)   NULL COMMENT '绑定身份（可选）',
  tenant_id             VARCHAR(64)   NULL COMMENT '租户标识（可选）',
  status                VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / REVOKED',
  expires_at            DATETIME      NULL COMMENT '过期时间，NULL 永不过期',
  rpm_limit             INT           NULL COMMENT '每分钟请求限额，NULL 不限',
  daily_request_limit   INT           NULL COMMENT '日请求配额，NULL 不限',
  daily_tool_call_limit INT           NULL COMMENT '日工具调用配额，NULL 不限',
  tpm_limit             INT           NULL COMMENT 'TPM 预留（本期不执行，仅建列）',
  daily_cost_limit      DECIMAL(12,4) NULL COMMENT '日成本配额预留（本期不执行，仅建列）',
  created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_api_key_hash (api_key_hash),
  KEY idx_status (status)
) COMMENT 'MCP 虚拟密钥（vk-）';

-- 2. 密钥↔网关多对多授权（替代 mcp_gateway_auth 一对多；旧表保留只读供回滚核对）
CREATE TABLE IF NOT EXISTS mcp_virtual_key_gateway (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  key_id      BIGINT      NOT NULL COMMENT 'mcp_virtual_key.id',
  gateway_id  VARCHAR(64) NOT NULL COMMENT 'mcp_gateway.gateway_id',
  granted_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_key_gateway (key_id, gateway_id),
  KEY idx_gateway (gateway_id)
) COMMENT '虚拟密钥↔网关授权';

-- 3. CEL 规则表（工单 0018 消费，随本脚本一并建表）
CREATE TABLE IF NOT EXISTS mcp_cel_rule (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_name      VARCHAR(128) NOT NULL COMMENT '规则名（唯一）',
  expression     TEXT         NOT NULL COMMENT 'CEL 表达式，求值结果须为 bool',
  scope_type     VARCHAR(16)  NOT NULL COMMENT 'GLOBAL / GATEWAY / VIRTUAL_KEY',
  gateway_id     VARCHAR(64)  NULL COMMENT 'scope=GATEWAY 时的目标网关',
  virtual_key_id BIGINT       NULL COMMENT 'scope=VIRTUAL_KEY 时的目标密钥',
  status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rule_name (rule_name),
  KEY idx_scope (scope_type, gateway_id, virtual_key_id)
) COMMENT 'CEL 工具治理规则';

-- 4. admin 用户表（JWT 角色登录）
CREATE TABLE IF NOT EXISTS mcp_admin_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  role          VARCHAR(16)  NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN（读写）/ READONLY（只读）',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) COMMENT 'admin 控制台用户';

-- 5. 审计日志表（只增不改）
CREATE TABLE IF NOT EXISTS mcp_audit_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor         VARCHAR(128) NOT NULL COMMENT 'admin 用户名或 system(migration)',
  action        VARCHAR(64)  NOT NULL COMMENT 'CREATE_KEY / UPDATE_KEY / REVOKE_KEY / GRANT / REVOKE_GRANT / CREATE_RULE / UPDATE_RULE / DELETE_RULE / UPDATE_QUOTA / MIGRATE / LOGIN',
  resource_type VARCHAR(32)  NOT NULL COMMENT 'VIRTUAL_KEY / CEL_RULE / GRANT / QUOTA / ADMIN_USER',
  resource_id   VARCHAR(64)  NOT NULL,
  before_json   TEXT         NULL COMMENT '变更前快照（脱敏）',
  after_json    TEXT         NULL COMMENT '变更后快照（脱敏）',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_resource (resource_type, resource_id),
  KEY idx_created (created_at)
) COMMENT '治理面审计日志';
