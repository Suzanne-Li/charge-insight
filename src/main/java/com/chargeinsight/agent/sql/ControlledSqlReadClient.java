package com.chargeinsight.agent.sql;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dedicated holder for the controlled-SQL connection. It deliberately is not a JdbcTemplate bean:
 * registering a second JdbcTemplate makes Spring Boot skip creation of the application's primary
 * JdbcTemplate, which would let a read-only identity leak into normal application services.
 */
public final class ControlledSqlReadClient implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    ControlledSqlReadClient(HikariDataSource dataSource, int timeoutSeconds) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(Math.max(1, timeoutSeconds));
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
