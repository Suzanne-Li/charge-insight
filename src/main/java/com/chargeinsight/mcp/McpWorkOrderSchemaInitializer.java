package com.chargeinsight.mcp;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Makes the local MCP work-order adapter available on existing demo databases. */
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class McpWorkOrderSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;
    public McpWorkOrderSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS analytics_mcp_work_order (id BIGINT PRIMARY KEY AUTO_INCREMENT, work_order_no VARCHAR(64) NOT NULL UNIQUE, region_name VARCHAR(64) NOT NULL, group_id BIGINT NOT NULL, group_name VARCHAR(128) NOT NULL, title VARCHAR(160) NOT NULL, description TEXT NOT NULL, severity VARCHAR(8) NOT NULL, status VARCHAR(16) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, KEY idx_mcp_work_order_scope (region_name, status, created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
