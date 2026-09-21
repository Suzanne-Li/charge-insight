CREATE TABLE IF NOT EXISTS analytics_long_term_memory (
    memory_id VARCHAR(32) PRIMARY KEY,
    owner_username VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_long_memory_owner_active_updated (owner_username, active, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主动录入的长期记忆';
