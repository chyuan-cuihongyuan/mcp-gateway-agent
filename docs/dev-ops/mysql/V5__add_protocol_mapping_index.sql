-- ============================================================
-- V5: mcp_protocol_mapping 补 protocol_id 二级索引
-- 来源：SELFLOOP7 loop-809 索引健康审计（工单 3017/3018）
-- 缺口：该表被 3 个方法按 protocol_id 过滤（工具配置加载热路径），DDL 原有
--       4 个索引全在次要列（mapping_type/parent_path/mcp_path/sort_order），
--       无 protocol_id 索引 → 全表扫描。
-- 幂等性：MySQL 8.0 无 ADD INDEX IF NOT EXISTS，重复执行报 1061 可忽略；
--         已含 idx 的库（新 archetype 建库）跳过本迁移。
-- ============================================================

ALTER TABLE `mcp_protocol_mapping`
  ADD KEY `idx_protocol_id` (`protocol_id`);
