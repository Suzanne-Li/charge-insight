package com.chargeinsight.agent.chat;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentChatSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public AgentChatSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS analytics_chat_session (
                    session_id VARCHAR(32) PRIMARY KEY,
                    owner_username VARCHAR(64) NOT NULL,
                    title VARCHAR(160) NOT NULL,
                    scope_region VARCHAR(64) NULL,
                    scope_city VARCHAR(64) NULL,
                    scope_group VARCHAR(128) NULL,
                    scope_time_range VARCHAR(64) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_chat_session_owner_updated (owner_username, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数会话'
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS analytics_chat_message (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    session_id VARCHAR(32) NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content TEXT NOT NULL,
                    trace_id VARCHAR(64) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    KEY idx_chat_message_session (session_id, id),
                    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id)
                        REFERENCES analytics_chat_session(session_id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数会话消息'
                """);
    }
}
