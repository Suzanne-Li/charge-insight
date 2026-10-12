-- Incremental schema for deployments initialized before trace/MCP correlation was introduced.
-- MySQL 8.4 supports IF NOT EXISTS; the application initializer applies the same additive changes at startup.
ALTER TABLE analytics_agent_trace ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64) NULL AFTER trace_id;
ALTER TABLE analytics_agent_trace ADD COLUMN IF NOT EXISTS parent_trace_id VARCHAR(64) NULL AFTER correlation_id;
ALTER TABLE analytics_agent_trace ADD INDEX IF NOT EXISTS idx_agent_trace_correlation_created (correlation_id, created_at);

ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS tool_name VARCHAR(128) NULL AFTER step_type;
ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS parameters_json TEXT NULL AFTER tool_name;
ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS duration_ms BIGINT NULL AFTER parameters_json;
ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS result_summary TEXT NULL AFTER duration_ms;
