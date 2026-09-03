CREATE TABLE IF NOT EXISTS analytics_mcp_work_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    work_order_no VARCHAR(64) NOT NULL UNIQUE,
    region_name VARCHAR(64) NOT NULL,
    group_id BIGINT NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    title VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    severity VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_mcp_work_order_scope (region_name, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP本地模拟工单';
