-- =============================================================================
-- mysql-upgrade-v5-generation-governance.sql —— 五期（AA 0196-0203 + AD 0224-0227）MySQL 增量 DDL
-- 说明：
--   * PG 侧增量走 db/changelog/postgresql/V*.sql（MigrationRunner 自动执行一次）；
--     MySQL 侧沿用 legacy 幂等种子 + 既有库手工 ALTER（本文件）。
--   * ALTER TABLE ... ADD COLUMN 在 MySQL 无 IF NOT EXISTS（8.0 同），重复执行会报
--     Duplicate column——执行前先用 SHOW COLUMNS FROM <表> LIKE '<列>' 判存。
--   * 新表沿用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- =============================================================================

-- ── 工单 0196 AA1：提示版本管理 ──
CREATE TABLE IF NOT EXISTS mcp_prompt_version (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  prompt_name VARCHAR(128) NOT NULL COMMENT '提示名',
  version     INT          NOT NULL COMMENT '版本号（同名递增）',
  template    TEXT         NOT NULL COMMENT '模板内容',
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ROLLBACK',
  note        VARCHAR(256) NULL COMMENT '备注',
  operator    VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '操作人',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_prompt_version (prompt_name, version),
  KEY idx_prompt_version_name (prompt_name, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示版本表（工单 0196；同名单一 PUBLISHED 不变式）';

-- ── 工单 0197 AA2：提示版本标签（同名单一标签持有）──
-- 执行前判存：SHOW COLUMNS FROM mcp_prompt_version LIKE 'label';
ALTER TABLE mcp_prompt_version ADD COLUMN label VARCHAR(32) NULL COMMENT '标签（production/staging/latest，NULL=未打标）';
ALTER TABLE mcp_prompt_version ADD UNIQUE KEY uk_prompt_label (label);

-- ── 工单 0202 AA7：标注回复 ──
CREATE TABLE IF NOT EXISTS mcp_annotation_qa (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  question_key   VARCHAR(256) NOT NULL COMMENT '问题归一化键（去空白/小写）',
  question       TEXT         NOT NULL COMMENT '原始问题',
  answer         TEXT         NOT NULL COMMENT '标注答案（命中直接回复）',
  hit_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '命中计数',
  enabled        SMALLINT     NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
  operator       VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '操作人',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_annotation_qkey (question_key),
  KEY idx_annotation_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标注回复表（工单 0202；命中免模型调用）';

-- ── 工单 0224 AD5：特性开关目标定向 ──
-- 执行前判存：SHOW COLUMNS FROM mcp_feature_flag LIKE 'tenant_whitelist';
ALTER TABLE mcp_feature_flag ADD COLUMN tenant_whitelist VARCHAR(512) NULL COMMENT '租户白名单 CSV（命中即开）';
ALTER TABLE mcp_feature_flag ADD COLUMN user_whitelist VARCHAR(512) NULL COMMENT '用户白名单 CSV（命中即开）';
ALTER TABLE mcp_feature_flag ADD COLUMN percentage INT NOT NULL DEFAULT 0 COMMENT '灰度百分比 0-100（稳定哈希 stickiness）';
