-- =============================================================================
-- MCP 网关一期核心 5 表建表脚本（PostgreSQL 版）
-- 工单 0123（三期 O3：PG 全量 DDL 翻译）补账：为从未入库过 DDL 的表补建脚本。
-- 反推口径：列集来自 mapper XML（mcp_gateway_mapper / mcp_gateway_auth_mapper /
--   mcp_gateway_tool_mapper / mcp_protocol_http_mapper / mcp_protocol_mapping_mapper）
--   的全部 SQL 列集 + mcp-gateway-agent-infrastructure dao/po 对应 PO 字段类型，
--   2026-09-10 三期 0123 补账；列集与同目录 core-schema.sql（MySQL 版）一一对应。
-- 类型映射（对齐 dev-ops/postgresql/01-gateway-seed.sql 口径）：
--   AUTO_INCREMENT → BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY；
--   DATETIME → TIMESTAMP；DEFAULT CURRENT_TIMESTAMP 双方言通用；
--   ON UPDATE CURRENT_TIMESTAMP 移除（双方言统一改应用层维护 update_time，
--   mapper 的 UPDATE/upsert 均已显式写 update_time = NOW()）；
--   PO 为 Integer 的状态/开关列 TINYINT → SMALLINT（保持 JDBC Integer 映射）；
--   MEDIUMTEXT/TEXT → TEXT；DECIMAL 双方言同名。
-- 唯一键反推依据（重点注明）：
--   mcp_gateway      uk_gateway_id ：insert 带 ON DUPLICATE KEY UPDATE 且冲突更新列
--                                   不含 gateway_id 自身，冲突键即 gateway_id；
--                                   queryMcpGatewayByGatewayId 按 gateway_id 精确定位。
--   mcp_gateway_auth uk_gateway_apikey：queryMcpGatewayAuthPO 按 (gateway_id, api_key)
--                                   联合精确匹配定位凭据，认证语义上二元组唯一。
--   mcp_gateway_tool uk_tool_id    ：ON DUPLICATE KEY UPDATE 更新列含 gateway_id 但
--                                   不含 tool_id（支持同一 tool 迁移网关），冲突键为
--                                   tool_id；deleteByToolId 单值删除印证。
--   mcp_protocol_http / mcp_protocol_mapping：无唯一键——同一 protocol_id 允许多行
--                                   （http 侧取最新启用一条 LIMIT 1；mapping 侧
--                                   一协议多映射行）。
-- =============================================================================

-- 1. MCP 网关配置表（一期 mcp_gateway 域主表）
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
COMMENT ON COLUMN mcp_gateway.id IS '主键ID';
COMMENT ON COLUMN mcp_gateway.gateway_id IS '网关唯一标识（uk 冲突键，upsert 依据）';
COMMENT ON COLUMN mcp_gateway.gateway_name IS '网关名称';
COMMENT ON COLUMN mcp_gateway.gateway_desc IS '网关描述';
COMMENT ON COLUMN mcp_gateway.version IS '协议版本';
COMMENT ON COLUMN mcp_gateway.auth IS '鉴权开关：0-不校验，1-强校验';
COMMENT ON COLUMN mcp_gateway.status IS '状态：0-禁用，1-启用';
COMMENT ON COLUMN mcp_gateway.create_time IS '创建时间';
COMMENT ON COLUMN mcp_gateway.update_time IS '更新时间（应用层维护）';

-- 2. 用户网关权限表（网关接入凭据）
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
COMMENT ON COLUMN mcp_gateway_auth.id IS '主键ID';
COMMENT ON COLUMN mcp_gateway_auth.gateway_id IS '网关唯一标识';
COMMENT ON COLUMN mcp_gateway_auth.api_key IS 'API密钥';
COMMENT ON COLUMN mcp_gateway_auth.rate_limit IS '速率限制（次/小时）';
COMMENT ON COLUMN mcp_gateway_auth.expire_time IS '过期时间，NULL 永不过期';
COMMENT ON COLUMN mcp_gateway_auth.status IS '状态：0-禁用，1-启用';
COMMENT ON COLUMN mcp_gateway_auth.create_time IS '创建时间';
COMMENT ON COLUMN mcp_gateway_auth.update_time IS '更新时间（应用层维护）';

-- 3. MCP 网关工具表（网关下挂载的工具注册项）
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
COMMENT ON COLUMN mcp_gateway_tool.id IS '自增ID';
COMMENT ON COLUMN mcp_gateway_tool.gateway_id IS '所属网关唯一标识';
COMMENT ON COLUMN mcp_gateway_tool.tool_id IS '工具ID（全局唯一，uk 冲突键）';
COMMENT ON COLUMN mcp_gateway_tool.tool_name IS 'MCP工具名称（如 JavaSDKMCPClient_getCompanyEmployee）';
COMMENT ON COLUMN mcp_gateway_tool.tool_type IS '工具类型：function/resource';
COMMENT ON COLUMN mcp_gateway_tool.tool_description IS '工具描述';
COMMENT ON COLUMN mcp_gateway_tool.tool_version IS '工具版本';
COMMENT ON COLUMN mcp_gateway_tool.protocol_id IS '协议ID（关联 mcp_protocol_http/mcp_protocol_mapping）';
COMMENT ON COLUMN mcp_gateway_tool.protocol_type IS '协议类型：http/dubbo/rabbitmq';
COMMENT ON COLUMN mcp_gateway_tool.create_time IS '创建时间';
COMMENT ON COLUMN mcp_gateway_tool.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_tool_gateway_name ON mcp_gateway_tool (gateway_id, tool_name);

-- 4. MCP 协议 HTTP 配置表（一个 protocol_id 允许多行版本化，取最新启用一条）
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
COMMENT ON COLUMN mcp_protocol_http.id IS '主键ID';
COMMENT ON COLUMN mcp_protocol_http.protocol_id IS '协议ID（非唯一：按 update_time 取最新启用行）';
COMMENT ON COLUMN mcp_protocol_http.http_url IS 'HTTP接口地址';
COMMENT ON COLUMN mcp_protocol_http.http_method IS 'HTTP请求方法：GET/POST/PUT/DELETE';
COMMENT ON COLUMN mcp_protocol_http.http_headers IS 'HTTP请求头（JSON格式）';
COMMENT ON COLUMN mcp_protocol_http.timeout IS '超时时间（毫秒）';
COMMENT ON COLUMN mcp_protocol_http.retry_times IS '重试次数';
COMMENT ON COLUMN mcp_protocol_http.status IS '状态：0-禁用，1-启用';
COMMENT ON COLUMN mcp_protocol_http.create_time IS '创建时间';
COMMENT ON COLUMN mcp_protocol_http.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_protocol_http_pid ON mcp_protocol_http (protocol_id);

-- 5. MCP 协议映射配置表（一个 protocol_id 多条字段映射行，无唯一键）
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
COMMENT ON COLUMN mcp_protocol_mapping.id IS '主键ID';
COMMENT ON COLUMN mcp_protocol_mapping.protocol_id IS '协议ID';
COMMENT ON COLUMN mcp_protocol_mapping.mapping_type IS '映射类型：request-请求参数映射，response-响应数据映射';
COMMENT ON COLUMN mcp_protocol_mapping.parent_path IS '父级路径（根节点为NULL，用于构建嵌套结构）';
COMMENT ON COLUMN mcp_protocol_mapping.field_name IS '字段名称（如 city、company、name）';
COMMENT ON COLUMN mcp_protocol_mapping.mcp_path IS 'MCP完整路径（如 xxxRequest01.city）';
COMMENT ON COLUMN mcp_protocol_mapping.mcp_type IS 'MCP数据类型：string/number/boolean/object/array';
COMMENT ON COLUMN mcp_protocol_mapping.mcp_desc IS 'MCP字段描述';
COMMENT ON COLUMN mcp_protocol_mapping.is_required IS '是否必填：0-否，1-是（生成required数组）';
COMMENT ON COLUMN mcp_protocol_mapping.sort_order IS '排序顺序（同级字段排序）';
COMMENT ON COLUMN mcp_protocol_mapping.create_time IS '创建时间';
COMMENT ON COLUMN mcp_protocol_mapping.update_time IS '更新时间（应用层维护）';
CREATE INDEX IF NOT EXISTS idx_mapping_pid ON mcp_protocol_mapping (protocol_id);
