-- V0013（工单 0202 AA7）：annotation_qa 标注回复表（借鉴 Dify annotation reply）
CREATE TABLE IF NOT EXISTS mcp_annotation_qa (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_key   VARCHAR(256) NOT NULL,
    question       TEXT         NOT NULL,
    answer         TEXT         NOT NULL,
    hit_count      BIGINT       NOT NULL DEFAULT 0,
    enabled        SMALLINT     NOT NULL DEFAULT 1,
    operator       VARCHAR(64)  NOT NULL DEFAULT 'unknown',
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_annotation_qkey UNIQUE (question_key)
);
COMMENT ON TABLE mcp_annotation_qa IS '标注回复表（归一化精确命中+编辑距离兜底，命中免模型调用）';
COMMENT ON COLUMN mcp_annotation_qa.question_key IS '问题归一化键（去空白/小写）';
CREATE INDEX IF NOT EXISTS idx_annotation_enabled ON mcp_annotation_qa (enabled);
