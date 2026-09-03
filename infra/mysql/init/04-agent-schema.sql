CREATE TABLE IF NOT EXISTS analytics_agent_trace (
    trace_id VARCHAR(64) PRIMARY KEY,
    question TEXT NOT NULL,
    retrieved_context TEXT,
    analysis_plan TEXT,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME NOT NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_trace_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营分析Agent运行轨迹';

CREATE TABLE IF NOT EXISTS analytics_agent_trace_step (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    step_number INT NOT NULL,
    step_type VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_agent_trace_step_trace (trace_id),
    CONSTRAINT fk_agent_trace_step_trace FOREIGN KEY (trace_id) REFERENCES analytics_agent_trace(trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营分析Agent步骤轨迹';
