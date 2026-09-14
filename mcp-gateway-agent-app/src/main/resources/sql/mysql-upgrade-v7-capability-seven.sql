-- ── 七期 AP 簇 MySQL 增量 v7（工单 0331-0338：工具生态与 Schema 契约）──
-- 说明：PostgreSQL 侧见 db/changelog/postgresql/V0019-V0021。

-- 工单 0337 AP7：工具注册表
CREATE TABLE IF NOT EXISTS mcp_tool_registry (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tool_name         VARCHAR(128) NOT NULL COMMENT '工具名（唯一）',
  description       VARCHAR(512) NULL COMMENT '描述',
  parameter_schema  TEXT         NULL COMMENT '参数 JSON Schema',
  tags              VARCHAR(256) NULL COMMENT '标签（逗号拼接）',
  source_fingerprint VARCHAR(64) NULL COMMENT '来源 OpenAPI 文档指纹',
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  operator          VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '操作人',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_registry_name (tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具注册表（工单 0337 AP7）';

-- 工单 0333 AP3：工具链定义表
CREATE TABLE IF NOT EXISTS mcp_tool_chain (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  chain_name  VARCHAR(128) NOT NULL COMMENT '链名（唯一）',
  description VARCHAR(512) NULL COMMENT '描述',
  steps_json  TEXT         NOT NULL COMMENT '步骤 DSL JSON',
  tenant_id   VARCHAR(64)  NULL COMMENT '租户',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_chain_name (chain_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具链定义（工单 0333 AP3）';

-- 工单 0335 AP5：工具调用审计表
CREATE TABLE IF NOT EXISTS mcp_tool_call_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  call_id       VARCHAR(64)  NOT NULL COMMENT '留痕ID（唯一）',
  tenant_id     VARCHAR(64)  NOT NULL COMMENT '租户',
  tool_name     VARCHAR(128) NOT NULL COMMENT '工具名',
  param_summary VARCHAR(512) NULL COMMENT '参数摘要（脱敏截断）',
  status        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/TIMEOUT/ERROR/TRUNCATED/QUOTA_REJECTED',
  cost_ms       BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时毫秒',
  token_estimate INT         NOT NULL DEFAULT 0 COMMENT 'token 估算',
  at_ms         BIGINT       NOT NULL COMMENT '调用时间毫秒',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tool_call_id (call_id),
  KEY idx_tool_call_tenant_tool (tenant_id, tool_name, at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用审计（工单 0335 AP5）';
