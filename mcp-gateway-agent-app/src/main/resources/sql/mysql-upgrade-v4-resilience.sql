-- =============================================================================
-- mysql-upgrade-v4-resilience.sql —— 四期网关韧性（0155-0162）MySQL 增量 DDL
-- 说明：
--   * PG 侧增量走 db/changelog/postgresql/V*.sql（MigrationRunner 自动执行一次）；
--     MySQL 侧沿用 legacy 幂等种子 + 既有库手工 ALTER（本文件）。
--   * ALTER TABLE ... ADD COLUMN 在 MySQL 无 IF NOT EXISTS（8.0 同），重复执行会报
--     Duplicate column——执行前先用 SHOW COLUMNS FROM <表> LIKE '<列>' 判存。
--   * 新表沿用 CREATE TABLE IF NOT EXISTS，可重复执行。
-- =============================================================================

-- ── 工单 0155：渠道 fallback 链 ──
ALTER TABLE mcp_llm_channel ADD COLUMN fallback_channel_id BIGINT NULL COMMENT 'fallback 渠道 id（工单 0155；空=无降级，保存时防环校验）';

-- ── 工单 0156：渠道超时与请求大小预算 ──
ALTER TABLE mcp_llm_channel ADD COLUMN max_body_bytes BIGINT NULL COMMENT '渠道请求体预算字节（工单 0156；空=不限，超限 -32020）';
-- timeout_ms 列既有种子已含（DEFAULT 60000，渠道不配置=沿用该全局默认）；存量库如缺失可执行：
-- ALTER TABLE mcp_llm_channel ADD COLUMN timeout_ms INT NOT NULL DEFAULT 60000;

-- ── 工单 0157：API Key 模型白名单 ──
ALTER TABLE mcp_virtual_key ADD COLUMN allowed_models VARCHAR(1024) NULL COMMENT '模型白名单 JSON 数组（工单 0157；NULL=不限制）';

-- ── 工单 0158：滚动窗口配额 ──
ALTER TABLE mcp_virtual_key ADD COLUMN budget_window_type VARCHAR(16) NULL COMMENT '预算窗口类型（工单 0158；DAY/WEEK/MONTH 滚动，NULL=旧固定窗口）';

-- ── 工单 0159：渠道健康分快照（表 21，编号顺延 V0001 基线 20 表）──
CREATE TABLE IF NOT EXISTS mcp_channel_health_snapshot (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  channel_id     BIGINT        NOT NULL COMMENT 'mcp_llm_channel.id',
  channel_name   VARCHAR(64)   NOT NULL COMMENT '渠道名（快照自描述）',
  score          DOUBLE        NOT NULL COMMENT '综合健康分 0-100',
  error_rate     DOUBLE        NULL COMMENT '错误率 0-1（近 N 次账本）',
  probe_score    DOUBLE        NULL COMMENT '探测分 0-100（缺探测 NULL）',
  avg_latency_ms BIGINT        NULL COMMENT '平均延迟毫秒',
  sampled_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采样时间',
  UNIQUE KEY uk_channel_sampled (channel_id, sampled_at),
  KEY idx_health_snapshot_sampled (sampled_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '渠道健康分快照（工单 0159，定时采样）';

-- ── 工单 0160：tag 路由规则（表 22，编号顺延）──
CREATE TABLE IF NOT EXISTS mcp_routing_rule (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_name        VARCHAR(128) NOT NULL COMMENT '规则名（唯一）',
  tag_key          VARCHAR(64)  NOT NULL COMMENT '标签键',
  tag_value        VARCHAR(64)  NOT NULL COMMENT '标签值',
  channel_group_id VARCHAR(64)  NOT NULL COMMENT '命中后调度的渠道组',
  priority         INT          NOT NULL DEFAULT 0 COMMENT '优先级（大者优先）',
  status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_routing_rule_name (rule_name),
  KEY idx_routing_rule_tag (tag_key, tag_value)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'tag 路由规则（工单 0160）';
ALTER TABLE mcp_llm_channel ADD COLUMN channel_group VARCHAR(64) NULL COMMENT '渠道组（工单 0160 tag 路由限定；空=默认组 default）';

-- ── 工单 0161：渠道并发上限 ──
ALTER TABLE mcp_llm_channel ADD COLUMN max_concurrency INT NULL COMMENT '渠道并发上限（工单 0161；空/0=不限制，超限 -32022）';

-- ── 补账列（工单 0105 重试 / 0108 余额探测：mapper 已引用但 MySQL 种子脚本缺失，随本票补齐）──
ALTER TABLE mcp_llm_channel ADD COLUMN num_retries INT NULL COMMENT '重试次数上限（0/空=不重试，≤3）';
ALTER TABLE mcp_llm_channel ADD COLUMN retry_backoff_ms INT NULL COMMENT '重试退避基值毫秒';
ALTER TABLE mcp_llm_channel ADD COLUMN retry_on VARCHAR(64) NULL COMMENT '重试错误类型（429,5xx,timeout）';
ALTER TABLE mcp_llm_channel ADD COLUMN balance_probe_url VARCHAR(512) NULL COMMENT '余额探测 URL';
ALTER TABLE mcp_llm_channel ADD COLUMN balance_json_path VARCHAR(256) NULL COMMENT '余额 JSON 路径';
ALTER TABLE mcp_llm_channel ADD COLUMN balance VARCHAR(128) NULL COMMENT '最近一次余额';
ALTER TABLE mcp_llm_channel ADD COLUMN balance_time DATETIME NULL COMMENT '最近一次余额时间';
