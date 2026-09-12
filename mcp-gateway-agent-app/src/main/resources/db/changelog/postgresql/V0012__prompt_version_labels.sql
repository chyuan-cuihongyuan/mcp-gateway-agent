-- V0012（工单 0197 AA2）：提示版本标签（借鉴 Langfuse labels：production/staging/latest）
ALTER TABLE mcp_prompt_version ADD COLUMN label VARCHAR(32);
COMMENT ON COLUMN mcp_prompt_version.label IS '标签（同名单一标签持有：production/staging/latest，NULL=未打标）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_prompt_label ON mcp_prompt_version (label) WHERE label IS NOT NULL;
