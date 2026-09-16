-- ============================================================
-- MCP 网关业务对接初始化 SQL
-- 用途：配置 mcp-gateway-agent 与业务系统的对接
-- 数据库：mcp_gateway_agent
-- 执行前请先备份数据库！
-- ============================================================

-- ============================================================
-- 1. 创建网关记录
-- ============================================================
INSERT INTO mcp_gateway (gateway_id, gateway_name, gateway_desc, version, auth, status, create_time, update_time)
VALUES (
    'gateway_business',
    '业务网关',
    '业务 API 转发网关，对接聚合智能体平台',
    '1.0',
    1,  -- 启用认证
    1,  -- 启用状态
    NOW(),
    NOW()
);

-- ============================================================
-- 2. 创建认证配置
-- ============================================================
INSERT INTO mcp_gateway_auth (gateway_id, api_key, rate_limit, expire_time, status, create_time, update_time)
VALUES (
    'gateway_business',
    'gw-biz-key-2026-chyuan-ai-agent',  -- API Key，客户端连接时使用
    100,  -- 速率限制：100次/小时
    '2027-12-31 23:59:59',  -- 过期时间
    1,  -- 启用状态
    NOW(),
    NOW()
);

-- ============================================================
-- 3. 注册 HTTP 协议配置（业务 API 地址）
-- ============================================================
-- 注意：以下 URL 假设 aggregation-support-agent 运行在 49.232.169.33:8091
-- 如需对接其他业务系统，请修改为实际地址

-- 3.1 查询智能体配置列表
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1001,
    'http://49.232.169.33:8091/api/v1/query_ai_agent_config_list',
    'GET',
    '{"Content-Type":"application/json"}',
    5000,
    1,
    1,
    NOW(),
    NOW()
);

-- 3.2 创建会话
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1002,
    'http://49.232.169.33:8091/api/v1/create_session',
    'POST',
    '{"Content-Type":"application/json"}',
    5000,
    1,
    1,
    NOW(),
    NOW()
);

-- 3.3 智能体对话
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1003,
    'http://49.232.169.33:8091/api/v1/chat',
    'POST',
    '{"Content-Type":"application/json"}',
    30000,  -- 对话请求超时时间较长
    1,
    1,
    NOW(),
    NOW()
);

-- 3.4 查询对话历史
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1004,
    'http://49.232.169.33:8091/api/v1/chat_history/query',
    'GET',
    '{"Content-Type":"application/json"}',
    5000,
    1,
    1,
    NOW(),
    NOW()
);

-- 3.5 保存对话历史
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1005,
    'http://49.232.169.33:8091/api/v1/chat_history/save',
    'POST',
    '{"Content-Type":"application/json"}',
    5000,
    1,
    1,
    NOW(),
    NOW()
);

-- 3.6 查询文档列表
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1006,
    'http://49.232.169.33:8091/api/v1/documents',
    'GET',
    '{"Content-Type":"application/json"}',
    5000,
    1,
    1,
    NOW(),
    NOW()
);

-- 3.7 文档检索（RAG）
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1007,
    'http://49.232.169.33:8091/api/v1/documents/search',
    'POST',
    '{"Content-Type":"application/json"}',
    10000,  -- RAG 检索超时时间较长
    1,
    1,
    NOW(),
    NOW()
);

-- 3.8 AIOps 告警分析
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    1008,
    'http://49.232.169.33:8091/api/v1/ai_ops',
    'POST',
    '{"Content-Type":"application/json"}',
    60000,  -- AIOps 分析超时时间最长
    1,
    1,
    NOW(),
    NOW()
);

-- ============================================================
-- 4. 注册协议参数映射
-- ============================================================

-- 4.1 查询智能体配置列表（无参数）
-- 无需映射

-- 4.2 创建会话参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(1002, 'request', 'request', 'agentId', 'request.agentId', 'string', '智能体ID', 1, 1, NOW(), NOW()),
(1002, 'request', 'request', 'userId', 'request.userId', 'string', '用户ID', 1, 2, NOW(), NOW());

-- 4.3 智能体对话参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(1003, 'request', 'request', 'agentId', 'request.agentId', 'string', '智能体ID', 1, 1, NOW(), NOW()),
(1003, 'request', 'request', 'userId', 'request.userId', 'string', '用户ID', 1, 2, NOW(), NOW()),
(1003, 'request', 'request', 'sessionId', 'request.sessionId', 'string', '会话ID', 0, 3, NOW(), NOW()),
(1003, 'request', 'request', 'message', 'request.message', 'string', '用户消息', 1, 4, NOW(), NOW());

-- 4.4 查询对话历史参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(1004, 'request', '', 'sessionId', 'sessionId', 'string', '会话ID', 1, 1, NOW(), NOW()),
(1004, 'request', '', 'userId', 'userId', 'string', '用户ID', 1, 2, NOW(), NOW());

-- 4.5 保存对话历史参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(1005, 'request', 'request', 'agentId', 'request.agentId', 'string', '智能体ID', 1, 1, NOW(), NOW()),
(1005, 'request', 'request', 'userId', 'request.userId', 'string', '用户ID', 1, 2, NOW(), NOW()),
(1005, 'request', 'request', 'sessionId', 'request.sessionId', 'string', '会话ID', 1, 3, NOW(), NOW()),
(1005, 'request', 'request', 'question', 'request.question', 'string', '用户问题', 1, 4, NOW(), NOW()),
(1005, 'request', 'request', 'answer', 'request.answer', 'string', '智能体回答', 1, 5, NOW(), NOW());

-- 4.6 查询文档列表（无参数）
-- 无需映射

-- 4.7 文档检索参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(1007, 'request', 'request', 'query', 'request.query', 'string', '检索查询语句', 1, 1, NOW(), NOW()),
(1007, 'request', 'request', 'topK', 'request.topK', 'number', '返回结果数量', 0, 2, NOW(), NOW());

-- 4.8 AIOps 告警分析参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(1008, 'request', 'request', 'message', 'request.message', 'string', '告警描述或分析请求', 1, 1, NOW(), NOW()),
(1008, 'request', 'request', 'userId', 'request.userId', 'string', '用户ID', 0, 2, NOW(), NOW());

-- ============================================================
-- 5. 注册工具配置
-- ============================================================

INSERT INTO mcp_gateway_tool (gateway_id, tool_id, tool_name, tool_type, tool_description, tool_version, protocol_id, protocol_type, create_time, update_time)
VALUES
-- 查询智能体列表
('gateway_business', 1001, 'query_agent_list', 'function', '查询可用的智能体配置列表，返回智能体ID、名称、描述', '1.0', 1001, 'http', NOW(), NOW()),

-- 创建会话
('gateway_business', 1002, 'create_session', 'function', '创建新的对话会话，需要提供智能体ID和用户ID', '1.0', 1002, 'http', NOW(), NOW()),

-- 智能体对话
('gateway_business', 1003, 'chat_with_agent', 'function', '与智能体进行对话，发送消息并获取回复', '1.0', 1003, 'http', NOW(), NOW()),

-- 查询对话历史
('gateway_business', 1004, 'query_chat_history', 'function', '查询指定会话的对话历史记录', '1.0', 1004, 'http', NOW(), NOW()),

-- 保存对话历史
('gateway_business', 1005, 'save_chat_history', 'function', '保存对话历史记录', '1.0', 1005, 'http', NOW(), NOW()),

-- 查询文档列表
('gateway_business', 1006, 'query_documents', 'function', '查询知识库中的文档列表', '1.0', 1006, 'http', NOW(), NOW()),

-- 文档检索
('gateway_business', 1007, 'search_documents', 'function', '从知识库中检索相关文档，支持语义搜索', '1.0', 1007, 'http', NOW(), NOW()),

-- AIOps 告警分析
('gateway_business', 1008, 'aiops_analyze', 'function', 'AIOps 智能运维告警分析，自动分析告警并生成运维报告', '1.0', 1008, 'http', NOW(), NOW());

-- ============================================================
-- 完成！
-- ============================================================
-- 执行完成后，可以通过以下 SQL 验证：
-- SELECT * FROM mcp_gateway WHERE gateway_id = 'gateway_business';
-- SELECT * FROM mcp_gateway_auth WHERE gateway_id = 'gateway_business';
-- SELECT * FROM mcp_gateway_tool WHERE gateway_id = 'gateway_business';
-- SELECT * FROM mcp_protocol_http WHERE protocol_id BETWEEN 1001 AND 1008;
-- SELECT * FROM mcp_protocol_mapping WHERE protocol_id BETWEEN 1001 AND 1008;
