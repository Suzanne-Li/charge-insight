CREATE TABLE IF NOT EXISTS dim_region (
    region_id BIGINT PRIMARY KEY,
    region_name VARCHAR(64) NOT NULL,
    city_name VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_region_city (region_name, city_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运营区域与城市维度';

CREATE TABLE IF NOT EXISTS station_group (
    group_id BIGINT PRIMARY KEY,
    group_code VARCHAR(32) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    region_id BIGINT NOT NULL,
    operator_name VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_station_group_code (group_code),
    KEY idx_station_group_region (region_id),
    CONSTRAINT fk_station_group_region FOREIGN KEY (region_id) REFERENCES dim_region (region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私桩共享桩群';

CREATE TABLE IF NOT EXISTS charging_pile (
    pile_id BIGINT PRIMARY KEY,
    pile_code VARCHAR(64) NOT NULL,
    group_id BIGINT NOT NULL,
    vendor VARCHAR(64) NOT NULL,
    pile_type VARCHAR(32) NOT NULL,
    install_date DATE NOT NULL,
    lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_charging_pile_code (pile_code),
    KEY idx_charging_pile_group (group_id),
    CONSTRAINT fk_charging_pile_group FOREIGN KEY (group_id) REFERENCES station_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电桩档案';

CREATE TABLE IF NOT EXISTS share_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    pile_id BIGINT NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    share_status VARCHAR(32) NOT NULL,
    effective_at DATETIME NOT NULL,
    expired_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_share_config_pile_time (pile_id, effective_at),
    KEY idx_share_config_owner (owner_id),
    CONSTRAINT fk_share_config_pile FOREIGN KEY (pile_id) REFERENCES charging_pile (pile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私桩共享配置历史';

CREATE TABLE IF NOT EXISTS pile_status_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_time DATETIME NOT NULL,
    pile_id BIGINT NOT NULL,
    online_status VARCHAR(32) NOT NULL,
    available_status VARCHAR(32) NOT NULL,
    fault_code VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_snapshot_pile_time (pile_id, snapshot_time),
    KEY idx_snapshot_time (snapshot_time),
    CONSTRAINT fk_snapshot_pile FOREIGN KEY (pile_id) REFERENCES charging_pile (pile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电桩状态快照';

CREATE TABLE IF NOT EXISTS charging_order (
    order_id VARCHAR(64) PRIMARY KEY,
    pile_id BIGINT NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NULL,
    energy_kwh DECIMAL(12,3) NOT NULL DEFAULT 0,
    amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    owner_income DECIMAL(12,2) NOT NULL DEFAULT 0,
    platform_fee DECIMAL(12,2) NOT NULL DEFAULT 0,
    order_status VARCHAR(32) NOT NULL,
    pay_channel VARCHAR(32) NOT NULL,
    split_status VARCHAR(32) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_start_time (start_time),
    KEY idx_order_pile_time (pile_id, start_time),
    KEY idx_order_channel_status (pay_channel, order_status),
    CONSTRAINT fk_charging_order_pile FOREIGN KEY (pile_id) REFERENCES charging_pile (pile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电订单与钱包结算结果';

CREATE TABLE IF NOT EXISTS fault_event (
    fault_id VARCHAR(64) PRIMARY KEY,
    pile_id BIGINT NOT NULL,
    fault_code VARCHAR(64) NOT NULL,
    fault_type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    start_time DATETIME NOT NULL,
    recover_time DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_fault_start_time (start_time),
    KEY idx_fault_pile_time (pile_id, start_time),
    KEY idx_fault_type (fault_type),
    CONSTRAINT fk_fault_event_pile FOREIGN KEY (pile_id) REFERENCES charging_pile (pile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备故障事件';

CREATE TABLE IF NOT EXISTS daily_group_operation (
    stat_date DATE NOT NULL,
    group_id BIGINT NOT NULL,
    total_pile_count INT NOT NULL DEFAULT 0,
    shared_pile_count INT NOT NULL DEFAULT 0,
    online_pile_count INT NOT NULL DEFAULT 0,
    available_pile_count INT NOT NULL DEFAULT 0,
    offline_pile_count INT NOT NULL DEFAULT 0,
    fault_pile_count INT NOT NULL DEFAULT 0,
    order_count INT NOT NULL DEFAULT 0,
    success_order_count INT NOT NULL DEFAULT 0,
    energy_kwh DECIMAL(14,3) NOT NULL DEFAULT 0,
    gmv_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    owner_income DECIMAL(14,2) NOT NULL DEFAULT 0,
    platform_fee DECIMAL(14,2) NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stat_date, group_id),
    CONSTRAINT fk_daily_operation_group FOREIGN KEY (group_id) REFERENCES station_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='桩群日运营汇总';

CREATE TABLE IF NOT EXISTS daily_fault_analysis (
    stat_date DATE NOT NULL,
    group_id BIGINT NOT NULL,
    vendor VARCHAR(64) NOT NULL,
    fault_type VARCHAR(64) NOT NULL,
    fault_code VARCHAR(64) NOT NULL,
    fault_pile_count INT NOT NULL DEFAULT 0,
    fault_duration_minutes BIGINT NOT NULL DEFAULT 0,
    affected_order_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stat_date, group_id, vendor, fault_type, fault_code),
    CONSTRAINT fk_daily_fault_group FOREIGN KEY (group_id) REFERENCES station_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='桩群日故障分析汇总';

CREATE TABLE IF NOT EXISTS data_import_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    data_type VARCHAR(32) NOT NULL,
    source_file_name VARCHAR(255) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    import_status VARCHAR(32) NOT NULL,
    started_at DATETIME NOT NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_import_batch_type_time (data_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据导入批次审计';

CREATE TABLE IF NOT EXISTS data_import_error (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id VARCHAR(64) NOT NULL,
    import_row_number INT NOT NULL,
    raw_content TEXT NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_import_error_batch (batch_id),
    CONSTRAINT fk_import_error_batch FOREIGN KEY (batch_id) REFERENCES data_import_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据导入行级错误';

CREATE OR REPLACE VIEW v_daily_group_operation AS
SELECT
    operation.stat_date,
    region.region_name,
    region.city_name,
    station.group_id,
    station.group_code,
    station.group_name,
    station.operator_name,
    operation.total_pile_count,
    operation.shared_pile_count,
    operation.online_pile_count,
    operation.available_pile_count,
    operation.offline_pile_count,
    operation.fault_pile_count,
    operation.order_count,
    operation.success_order_count,
    operation.energy_kwh,
    operation.gmv_amount,
    operation.owner_income,
    operation.platform_fee,
    CASE WHEN operation.total_pile_count = 0 THEN 0
         ELSE ROUND(operation.offline_pile_count / operation.total_pile_count, 4) END AS offline_rate,
    CASE WHEN operation.order_count = 0 THEN 0
         ELSE ROUND(operation.success_order_count / operation.order_count, 4) END AS share_success_rate
FROM daily_group_operation operation
JOIN station_group station ON station.group_id = operation.group_id
JOIN dim_region region ON region.region_id = station.region_id;

CREATE OR REPLACE VIEW v_daily_fault_analysis AS
SELECT
    analysis.stat_date,
    region.region_name,
    region.city_name,
    station.group_id,
    station.group_code,
    station.group_name,
    analysis.vendor,
    analysis.fault_type,
    analysis.fault_code,
    analysis.fault_pile_count,
    analysis.fault_duration_minutes,
    analysis.affected_order_count
FROM daily_fault_analysis analysis
JOIN station_group station ON station.group_id = analysis.group_id
JOIN dim_region region ON region.region_id = station.region_id;
