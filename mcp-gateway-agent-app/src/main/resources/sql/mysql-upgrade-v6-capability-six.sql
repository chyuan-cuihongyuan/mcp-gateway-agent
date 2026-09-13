-- =============================================================================
-- mysql-upgrade-v6-capability-six.sql —— 六期（AG 0251-0259 / AH 0260-0267 / AJ 0277-0284）MySQL 增量 DDL
-- 说明：
--   * PG 侧增量走 db/changelog/postgresql/V*.sql（MigrationRunner 自动执行一次）；
--     MySQL 侧沿用 legacy 幂等种子 + 既有库手工 ALTER（本文件）。
--   * 新表沿用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- =============================================================================

-- ── 工单 0251 AG1：配置快照版本机 ──
CREATE TABLE IF NOT EXISTS mcp_config_snapshot (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  namespace   VARCHAR(128) NOT NULL COMMENT '命名空间',
  config_key  VARCHAR(256) NOT NULL COMMENT '配置键',
  version     INT          NOT NULL COMMENT '版本号（同键递增）',
  content     TEXT         NOT NULL COMMENT '内容（敏感项为 enc-v1 密文）',
  content_md5 VARCHAR(64)  NOT NULL COMMENT '内容摘要',
  sensitive   TINYINT      NOT NULL DEFAULT 0 COMMENT '敏感标记 0/1',
  publisher   VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '发布人',
  note        VARCHAR(256) NULL COMMENT '备注',
  status      VARCHAR(16)  NOT NULL DEFAULT 'CURRENT' COMMENT 'CURRENT/SUPERSEDED',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_config_snapshot (namespace, config_key, version),
  KEY idx_config_snapshot_ns (namespace, config_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置快照表（工单 0251；同键单一 CURRENT 不变式）';
