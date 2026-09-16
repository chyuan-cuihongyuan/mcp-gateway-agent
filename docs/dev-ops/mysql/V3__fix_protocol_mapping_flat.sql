-- ============================================================
-- 修复 mcp_protocol_mapping 参数映射：去除 request 嵌套包装
-- 将 parent_path='request', mcp_path='request.xxx' 改为扁平结构
-- 执行前请先备份！
-- ============================================================

UPDATE mcp_protocol_mapping
SET parent_path = NULL,
    mcp_path    = REPLACE(mcp_path, 'request.', '')
WHERE protocol_id BETWEEN 2001 AND 2007
  AND mapping_type = 'request'
  AND parent_path = 'request'
  AND mcp_path LIKE 'request.%';

-- 验证
SELECT protocol_id, mapping_type, parent_path, field_name, mcp_path, mcp_desc, is_required
FROM mcp_protocol_mapping
WHERE protocol_id BETWEEN 2001 AND 2007
ORDER BY protocol_id, sort_order;
