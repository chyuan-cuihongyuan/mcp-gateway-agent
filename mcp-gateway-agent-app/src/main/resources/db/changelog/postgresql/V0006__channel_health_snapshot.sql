-- =============================================================================
-- V0006__channel_health_snapshot.sql —— 渠道健康分快照（四期工单 0159，PostgreSQL changelog）
-- 内容：mcp_channel_health_snapshot 建表（定时采样，每渠道周期快照）。
-- 表编号顺延：V0001 基线 20 表之后第 21 表。
-- 说明：MySQL 侧建表见 resources/sql/mysql-upgrade-v4-resilience.sql（CREATE TABLE IF NOT EXISTS）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS mcp_channel_health_snapshot (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  channel_id     BIGINT        NOT NULL,
  channel_name   VARCHAR(64)   NOT NULL,
  score          DOUBLE PRECISION NOT NULL,
  error_rate     DOUBLE PRECISION NULL,
  probe_score    DOUBLE PRECISION NULL,
  avg_latency_ms BIGINT        NULL,
  sampled_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_channel_sampled UNIQUE (channel_id, sampled_at)
);
COMMENT ON TABLE mcp_channel_health_snapshot IS '渠道健康分快照（工单 0159，定时采样）';
CREATE INDEX IF NOT EXISTS idx_health_snapshot_sampled ON mcp_channel_health_snapshot (sampled_at);
