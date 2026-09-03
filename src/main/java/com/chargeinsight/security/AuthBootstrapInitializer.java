package com.chargeinsight.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates exactly one initial administrator only when an explicit bootstrap password is supplied. */
@Component
@DependsOn("authSchemaInitializer")
@ConditionalOnProperty(name = "charge.security.enabled", havingValue = "true")
public class AuthBootstrapInitializer {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AuthBootstrapInitializer(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
            @Value("${charge.security.bootstrap-admin-username:admin}") String username,
            @Value("${charge.security.bootstrap-admin-password:}") String password) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @PostConstruct
    void bootstrap() {
        if (password.isBlank() || jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_user", Long.class) != 0) return;
        jdbcTemplate.update("INSERT INTO auth_user(username, password_hash, enabled) VALUES (?, ?, 1)", username, passwordEncoder.encode(password));
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM auth_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("INSERT INTO auth_user_role(user_id, role_name) VALUES (?, 'ADMIN')", userId);
        jdbcTemplate.update("INSERT INTO auth_user_region_scope(user_id, region_name) VALUES (?, '*')", userId);
    }
}
