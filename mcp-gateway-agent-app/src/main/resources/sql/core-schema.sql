-- =============================================================================
-- MCP 网关一期核心 5 表建表脚本（MySQL 版）
-- 工单 0123（三期 O3：PG 全量 DDL 翻译）补账：为从未入库过 DDL 的表补建脚本。
-- 反推口径：列集来自 mapper XML（mcp_gateway_mapper / mcp_gateway_auth_mapper /
--   mcp_gateway_tool_mapper / mcp_protocol_http_mapper / mcp_protocol_mapping_mapper）
--   的全部 SQL 列集 + mcp-gateway-agent-infrastructure dao/po 对应 PO 字段类型，
--   2026-09-10 三期 0123 补账。
-- 唯一键反推依据（重点注明）：
--   mcp_gateway      uk_gateway_id ：insert 带 ON DUPLICATE KEY UPDATE 且冲突更新列
--                                   （名称/描述/版本/鉴权/状态）不含 gateway_id 自身，
--                                   冲突键即 gateway_id；queryMcpGatewayByGatewayId
--                                   亦按 gateway_id 精确定位。
--   mcp_gateway_auth uk_gateway_apikey：queryMcpGatewayAuthPO 按 (gateway_id, api_key)
--                                   联合精确匹配定位凭据，认证语义上二元组唯一。
--   mcp_gateway_tool uk_tool_id    ：insert 的 ON DUPLICATE KEY UPDATE 更新列含
--                                   gateway_id 但不含 tool_id（支持同一 tool 迁移
--                                   网关），冲突键为 tool_id；deleteByToolId 按
--                                   tool_id 单值删除印证。
--   mcp_protocol_http / mcp_protocol_mapping：无唯一键——同一 protocol_id 允许多行
--                                   （http 侧 queryMcpProtocolHttpByProtocolId 取
--                                   最新启用一条 LIMIT 1；mapping 侧一协议多映射行）。
-- 类型口径：TINYINT 对应 PO Integer 状态/开关列；INT 为业务量值（rate_limit 等）；
--   MEDIUMTEXT/TEXT→TEXT；不使用 ON UPDATE CURRENT_TIMESTAMP，updated 时间统一由
--   应用层在 UPDATE/upsert 语句显式写 update_time = NOW()（mapper 已如此）。
-- =============================================================================

-- 1. MCP 网关配置表（一期 mcp_gateway 域主表）
CREATE TABLE IF NOT EXISTS mcp_gateway (
  id           BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  gateway_id   VARCHAR(64)  NOT NULL COMMENT '网关唯一标识（uk 冲突键，upsert 依据）',
  gateway_name VARCHAR(128) NOT NULL COMMENT '网关名称',
  gateway_desc VARCHAR(512) NULL COMMENT '网关描述',
  version      VARCHAR(32)  NOT NULL COMMENT '协议版本',
  auth         TINYINT      NOT NULL DEFAULT 0 COMMENT '鉴权开关：0-不校验，1-强校验',
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_gateway_id (gateway_id)
) COMMENT 'MCP网关配置表';

-- 2. 用户网关权限表（网关接入凭据）
CREATE TABLE IF NOT EXISTS mcp_gateway_auth (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  gateway_id  VARCHAR(64)  NOT NULL COMMENT '网关唯一标识',
  api_key     VARCHAR(256) NOT NULL COMMENT 'API密钥',
  rate_limit  INT          NOT NULL DEFAULT 0 COMMENT '速率限制（次/小时）',
  expire_time DATETIME     NULL COMMENT '过期时间，NULL 永不过期',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_gateway_apikey (gateway_id, api_key)
) COMMENT '用户网关权限表';

-- 3. MCP 网关工具表（网关下挂载的工具注册项）
CREATE TABLE IF NOT EXISTS mcp_gateway_tool (
  id               BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
  gateway_id       VARCHAR(64)  NOT NULL COMMENT '所属网关唯一标识',
  tool_id          BIGINT       NOT NULL COMMENT '工具ID（全局唯一，uk 冲突键）',
  tool_name        VARCHAR(256) NOT NULL COMMENT 'MCP工具名称（如 JavaSDKMCPClient_getCompanyEmployee）',
  tool_type        VARCHAR(32)  NOT NULL DEFAULT 'function' COMMENT '工具类型：function/resource',
  tool_description TEXT         NULL COMMENT '工具描述',
  tool_version     VARCHAR(32)  NULL COMMENT '工具版本',
  protocol_id      BIGINT       NULL COMMENT '协议ID（关联 mcp_protocol_http/mcp_protocol_mapping）',
  protocol_type    VARCHAR(32)  NULL COMMENT '协议类型：http/dubbo/rabbitmq',
  create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  UNIQUE KEY uk_tool_id (tool_id),
  KEY idx_tool_gateway_name (gateway_id, tool_name)
) COMMENT 'MCP网关工具表';

-- 4. MCP 协议 HTTP 配置表（一个 protocol_id 允许多行版本化，取最新启用一条）
CREATE TABLE IF NOT EXISTS mcp_protocol_http (
  id           BIGINT        AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  protocol_id  BIGINT        NOT NULL COMMENT '协议ID（非唯一：按 update_time 取最新启用行）',
  http_url     VARCHAR(512)  NOT NULL COMMENT 'HTTP接口地址',
  http_method  VARCHAR(16)   NOT NULL DEFAULT 'GET' COMMENT 'HTTP请求方法：GET/POST/PUT/DELETE',
  http_headers VARCHAR(2048) NULL COMMENT 'HTTP请求头（JSON格式）',
  timeout      INT           NOT NULL DEFAULT 30000 COMMENT '超时时间（毫秒）',
  retry_times  INT           NOT NULL DEFAULT 0 COMMENT '重试次数',
  status       TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
  create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  KEY idx_protocol_http_pid (protocol_id)
) COMMENT 'MCP协议HTTP配置表';

-- 5. MCP 协议映射配置表（一个 protocol_id 多条字段映射行，无唯一键）
CREATE TABLE IF NOT EXISTS mcp_protocol_mapping (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  protocol_id BIGINT       NOT NULL COMMENT '协议ID',
  mapping_type VARCHAR(16) NOT NULL COMMENT '映射类型：request-请求参数映射，response-响应数据映射',
  parent_path VARCHAR(256) NULL COMMENT '父级路径（根节点为NULL，用于构建嵌套结构）',
  field_name  VARCHAR(128) NOT NULL COMMENT '字段名称（如 city、company、name）',
  mcp_path    VARCHAR(512) NOT NULL COMMENT 'MCP完整路径（如 xxxRequest01.city）',
  mcp_type    VARCHAR(32)  NOT NULL COMMENT 'MCP数据类型：string/number/boolean/object/array',
  mcp_desc    VARCHAR(512) NULL COMMENT 'MCP字段描述',
  is_required TINYINT      NOT NULL DEFAULT 0 COMMENT '是否必填：0-否，1-是（生成required数组）',
  sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序顺序（同级字段排序）',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（应用层维护）',
  KEY idx_mapping_pid (protocol_id)
) COMMENT 'MCP映射配置表';
