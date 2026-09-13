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

-- ── 工单 0261 AH2：策略定义 ──
CREATE TABLE IF NOT EXISTS mcp_policy_definition (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  name           VARCHAR(128) NOT NULL COMMENT '策略名（唯一）',
  sub_pattern    VARCHAR(256) NOT NULL COMMENT '主体模式（exact/*/正则 /.../）',
  obj_pattern    VARCHAR(256) NOT NULL COMMENT '客体模式',
  act_pattern    VARCHAR(256) NOT NULL COMMENT '动作模式',
  condition_expr VARCHAR(1024) NULL COMMENT '条件表达式（ExprKernel 语法，可空）',
  effect         VARCHAR(16)  NOT NULL DEFAULT 'DENY' COMMENT 'ALLOW/DENY（deny-overrides）',
  priority       INT          NOT NULL DEFAULT 0 COMMENT '优先级（高先）',
  enabled        TINYINT      NOT NULL DEFAULT 1 COMMENT '启用 0/1',
  note           VARCHAR(256) NULL COMMENT '备注',
  operator       VARCHAR(64)  NOT NULL DEFAULT 'unknown' COMMENT '操作人',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_policy_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略定义表（工单 0261；PERM + deny-overrides）';

-- ── 工单 0265 AH5：策略决策日志 ──
CREATE TABLE IF NOT EXISTS mcp_policy_decision_log (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  at_ms               BIGINT       NOT NULL COMMENT '时间戳毫秒',
  subject             VARCHAR(256) NOT NULL COMMENT '主体摘要（脱敏截断）',
  object              VARCHAR(256) NOT NULL COMMENT '客体',
  action              VARCHAR(128) NOT NULL COMMENT '动作',
  decision            VARCHAR(16)  NOT NULL COMMENT 'ALLOW/DENY',
  hit_statement_names VARCHAR(512) NOT NULL DEFAULT '' COMMENT '命中策略名（逗号拼接）',
  cached              TINYINT      NOT NULL DEFAULT 0 COMMENT '缓存命中 0/1',
  cost_ms             BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时毫秒',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_policy_decision_log_at (at_ms),
  KEY idx_policy_decision_log_decision (decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略决策日志表（工单 0265）';
