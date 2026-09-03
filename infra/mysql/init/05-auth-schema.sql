CREATE TABLE IF NOT EXISTS auth_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地认证用户';

CREATE TABLE IF NOT EXISTS auth_user_role (
    user_id BIGINT NOT NULL,
    role_name VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_name),
    CONSTRAINT fk_auth_user_role_user FOREIGN KEY (user_id) REFERENCES auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地认证角色';

CREATE TABLE IF NOT EXISTS auth_user_region_scope (
    user_id BIGINT NOT NULL,
    region_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, region_name),
    CONSTRAINT fk_auth_user_region_user FOREIGN KEY (user_id) REFERENCES auth_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户可访问运营大区';
