-- ============================================================
-- MCP 网关认证配置修复脚本
-- 问题：SSE连接失败，API密钥验证未通过
-- 解决：添加或更新网关认证配置
-- ============================================================

-- 检查当前配置
SELECT '=== 当前网关配置 ===' AS info;
SELECT gateway_id, gateway_name, auth, status FROM mcp_gateway WHERE gateway_id = 'gateway_business';

SELECT '=== 当前认证配置 ===' AS info;
SELECT * FROM mcp_gateway_auth WHERE gateway_id = 'gateway_business';

-- ============================================================
-- 方案A：启用认证模式（推荐生产环境）
-- ============================================================

-- 1. 创建或更新网关记录
INSERT INTO mcp_gateway (gateway_id, gateway_name, gateway_desc, version, auth, status, create_time, update_time)
VALUES (
    'gateway_business',
    '业务网关',
    '业务 API 转发网关，对接聚合智能体平台',
    '1.0',
    1,  -- auth=1 表示启用强校验
    1,  -- status=1 表示启用状态
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE
  gateway_name = VALUES(gateway_name),
  gateway_desc = VALUES(gateway_desc),
  auth = VALUES(auth),
  status = VALUES(status),
  update_time = NOW();

-- 2. 创建或更新认证配置
INSERT INTO mcp_gateway_auth (gateway_id, api_key, rate_limit, expire_time, status, create_time, update_time)
VALUES (
    'gateway_business',
    'gw-biz-key-2026-chyuan-ai-agent',
    100,  -- 速率限制：100次/小时
    '2027-12-31 23:59:59',  -- 过期时间
    1,  -- status=1 表示启用
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE
  api_key = VALUES(api_key),
  rate_limit = VALUES(rate_limit),
  expire_time = VALUES(expire_time),
  status = VALUES(status),
  update_time = NOW();

-- ============================================================
-- 方案B（备选）：禁用强校验（仅开发环境使用）
-- 如果不需要认证，取消下面注释并执行：
-- UPDATE mcp_gateway SET auth = 0 WHERE gateway_id = 'gateway_business';
-- ============================================================

-- 验证修复结果
SELECT '=== 修复后的网关配置 ===' AS info;
SELECT gateway_id, gateway_name, auth, status FROM mcp_gateway WHERE gateway_id = 'gateway_business';

SELECT '=== 修复后的认证配置 ===' AS info;
SELECT gateway_id, api_key, status, expire_time FROM mcp_gateway_auth WHERE gateway_id = 'gateway_business';

-- 完成提示
SELECT '=== 修复完成！请重启MCP网关服务 ===' AS info;
