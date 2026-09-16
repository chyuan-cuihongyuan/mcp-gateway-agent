-- ============================================================
-- MCP 网关云帆业务工具注册 SQL
-- 用途：注册云帆(yunfanoil)退款和开票相关的 MCP 工具
-- 数据库：mcp_gateway_agent
-- 执行前请先备份数据库！
-- ============================================================

-- ============================================================
-- 1. 注册 HTTP 协议配置（Agent API 地址）
-- 注意：yunfanoil 本地开发默认运行在 127.0.0.1:8877
-- ============================================================

-- 1.1 查询订单详情
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    2001,
    'http://127.0.0.1:8877/agent/api/order/query',
    'POST',
    '{"Content-Type":"application/json","X-Agent-Auth":"agent-api-key-yunfanoil-2026"}',
    10000,
    1,
    1,
    NOW(), NOW()
);

-- 1.2 查询订单状态
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    2002,
    'http://127.0.0.1:8877/agent/api/order/status',
    'POST',
    '{"Content-Type":"application/json","X-Agent-Auth":"agent-api-key-yunfanoil-2026"}',
    10000,
    1,
    1,
    NOW(), NOW()
);

-- 1.3 申请退款
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    2003,
    'http://127.0.0.1:8877/agent/api/order/refund/apply',
    'POST',
    '{"Content-Type":"application/json","X-Agent-Auth":"agent-api-key-yunfanoil-2026"}',
    15000,
    1,
    1,
    NOW(), NOW()
);

-- 1.4 执行退款
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    2004,
    'http://127.0.0.1:8877/agent/api/order/refund/execute',
    'POST',
    '{"Content-Type":"application/json","X-Agent-Auth":"agent-api-key-yunfanoil-2026"}',
    30000,
    1,
    1,
    NOW(), NOW()
);

-- 1.5 查询CP开票信息
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    2005,
    'http://127.0.0.1:8877/agent/api/invoice/query',
    'POST',
    '{"Content-Type":"application/json","X-Agent-Auth":"agent-api-key-yunfanoil-2026"}',
    10000,
    1,
    1,
    NOW(), NOW()
);

-- 1.6 查询高德发票信息
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    2006,
    'http://127.0.0.1:8877/agent/api/invoice/info/query',
    'POST',
    '{"Content-Type":"application/json","X-Agent-Auth":"agent-api-key-yunfanoil-2026"}',
    10000,
    1,
    1,
    NOW(), NOW()
);

-- 1.7 提交开票信息
INSERT INTO mcp_protocol_http (protocol_id, http_url, http_method, http_headers, timeout, retry_times, status, create_time, update_time)
VALUES (
    2007,
    'http://127.0.0.1:8877/agent/api/invoice/submit',
    'POST',
    '{"Content-Type":"application/json","X-Agent-Auth":"agent-api-key-yunfanoil-2026"}',
    15000,
    1,
    1,
    NOW(), NOW()
);

-- ============================================================
-- 2. 注册协议参数映射
-- ============================================================

-- 2.1 查询订单详情参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(2001, 'request', NULL, 'orderId', 'orderId', 'string', '订单ID', 1, 1, NOW(), NOW());

-- 2.2 查询订单状态参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(2002, 'request', NULL, 'orderId', 'orderId', 'string', '订单ID', 1, 1, NOW(), NOW());

-- 2.3 申请退款参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(2003, 'request', NULL, 'orderId', 'orderId', 'string', '订单ID', 1, 1, NOW(), NOW()),
(2003, 'request', NULL, 'reason', 'reason', 'string', '退款原因', 1, 2, NOW(), NOW());

-- 2.4 执行退款参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(2004, 'request', NULL, 'orderId', 'orderId', 'string', '订单ID', 1, 1, NOW(), NOW()),
(2004, 'request', NULL, 'approved', 'approved', 'boolean', '是否审核通过', 1, 2, NOW(), NOW());

-- 2.5 查询CP开票信息参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(2005, 'request', NULL, 'orderId', 'orderId', 'string', '订单ID', 1, 1, NOW(), NOW());

-- 2.6 查询高德发票信息参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(2006, 'request', NULL, 'orderId', 'orderId', 'string', '订单ID', 1, 1, NOW(), NOW());

-- 2.7 提交开票信息参数映射
INSERT INTO mcp_protocol_mapping (protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_type, mcp_desc, is_required, sort_order, create_time, update_time)
VALUES
(2007, 'request', NULL, 'orderId', 'orderId', 'string', '订单ID', 1, 1, NOW(), NOW()),
(2007, 'request', NULL, 'type', 'type', 'string', '开票信息类型(orderInvoice/gaodeInvoice)', 1, 2, NOW(), NOW()),
(2007, 'request', NULL, 'invoiceInfo', 'invoiceInfo', 'object', '开票信息对象', 1, 3, NOW(), NOW());

-- ============================================================
-- 3. 注册工具配置（复用现有 gateway_business 网关）
-- ============================================================

INSERT INTO mcp_gateway_tool (gateway_id, tool_id, tool_name, tool_type, tool_description, tool_version, protocol_id, protocol_type, create_time, update_time)
VALUES
-- 查询订单详情
('gateway_business', 2001, 'agent_order_query', 'function',
 '根据订单ID查询订单详细信息，包括油站信息、金额、支付方式、渠道、订单状态、退款申请状态、开票方等。返回订单完整信息用于业务判断',
 '1.0', 2001, 'http', NOW(), NOW()),

-- 查询订单状态
('gateway_business', 2002, 'agent_order_status', 'function',
 '根据订单ID查询订单的简要状态信息，包括订单状态(odStatus:0-5)、退款申请状态(odRfdAplSts:0-3)、开票方(odInvoicingParty:0-2)、是否可退款、是否可开票。用于快速判断订单当前状态',
 '1.0', 2002, 'http', NOW(), NOW()),

-- 申请退款
('gateway_business', 2003, 'agent_order_refund_apply', 'function',
 '为已支付的订单提交退款申请，需要提供订单ID和退款原因。系统会根据订单渠道自动路由到对应的退款服务。仅订单状态为0(支付成功)且退款申请状态为2(未申请)时可调用。提交后退款申请状态变为1(申请中)，需要等待管理员审核',
 '1.0', 2003, 'http', NOW(), NOW()),

-- 执行退款
('gateway_business', 2004, 'agent_order_refund_execute', 'function',
 '对已审核通过的退款申请执行退款操作。系统会根据订单平台和渠道自动调用对应的退款接口。仅退款申请已审核通过(odRfdAplSts=0)时可调用。退款执行成功后，订单状态变为5(已退款)',
 '1.0', 2004, 'http', NOW(), NOW()),

-- 查询CP开票信息
('gateway_business', 2005, 'agent_invoice_query', 'function',
 '查询订单的CP开票信息(FlowOrderInvoice表)，包括公司名称、信用代码、开户行、银行账号、地址、电话、邮箱、开票状态等。适用于CP线上/线下开票的订单(odInvoicingParty=1/2)',
 '1.0', 2005, 'http', NOW(), NOW()),

-- 查询高德发票信息
('gateway_business', 2006, 'agent_invoice_info_query', 'function',
 '查询订单的高德发票信息(FlowInvoiceInfo表)，包括发票抬头、类型、形式、纳税人识别号、发票状态、发票金额、发票号码、PDF下载链接等。适用于高德渠道的开票订单',
 '1.0', 2006, 'http', NOW(), NOW()),

-- 提交开票信息
('gateway_business', 2007, 'agent_invoice_submit', 'function',
 '提交订单的开票信息。type="orderInvoice"时提交CP开票信息（需要公司名称、统一社会信用代码、开户行、银行账号等）；type="gaodeInvoice"时提交高德发票信息（需要发票抬头、类型、形式、纳税人识别号、金额等）。已提交过开票信息的订单不能重复提交',
 '1.0', 2007, 'http', NOW(), NOW());

-- ============================================================
-- 完成！
-- ============================================================
-- 执行完成后，可以通过以下 SQL 验证：
-- SELECT * FROM mcp_gateway_tool WHERE gateway_id = 'gateway_business' AND tool_id BETWEEN 2001 AND 2007;
-- SELECT * FROM mcp_protocol_http WHERE protocol_id BETWEEN 2001 AND 2007;
-- SELECT * FROM mcp_protocol_mapping WHERE protocol_id BETWEEN 2001 AND 2007;
-- ============================================================
