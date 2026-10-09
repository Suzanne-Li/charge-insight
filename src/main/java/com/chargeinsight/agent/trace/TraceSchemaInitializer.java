package com.chargeinsight.agent.trace;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies additive trace fields when an existing database predates correlation support. */
@Component
public class TraceSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public TraceSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("ALTER TABLE analytics_agent_trace ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(64) NULL AFTER trace_id");
        jdbcTemplate.execute("ALTER TABLE analytics_agent_trace ADD COLUMN IF NOT EXISTS parent_trace_id VARCHAR(64) NULL AFTER correlation_id");
        jdbcTemplate.execute("ALTER TABLE analytics_agent_trace ADD INDEX IF NOT EXISTS idx_agent_trace_correlation_created (correlation_id, created_at)");
        jdbcTemplate.execute("ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS tool_name VARCHAR(128) NULL AFTER step_type");
        jdbcTemplate.execute("ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS parameters_json TEXT NULL AFTER tool_name");
        jdbcTemplate.execute("ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS duration_ms BIGINT NULL AFTER parameters_json");
        jdbcTemplate.execute("ALTER TABLE analytics_agent_trace_step ADD COLUMN IF NOT EXISTS result_summary TEXT NULL AFTER duration_ms");
    }
}
