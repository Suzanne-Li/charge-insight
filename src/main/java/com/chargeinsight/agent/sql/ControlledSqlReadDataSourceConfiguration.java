package com.chargeinsight.agent.sql;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps generated SQL on a dedicated read-only database identity. */
@Configuration
public class ControlledSqlReadDataSourceConfiguration {
    @Bean(name = "controlledSqlReadClient", destroyMethod = "close")
    @ConditionalOnProperty(name = "charge.sql.reader.enabled", havingValue = "true")
    ControlledSqlReadClient controlledSqlReadClient(
            @Value("${charge.sql.reader.url:}") String url,
            @Value("${charge.sql.reader.username:}") String username,
            @Value("${charge.sql.reader.password:}") String password,
            @Value("${charge.sql.reader.query-timeout-seconds:5}") int timeoutSeconds) {
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("受控 SQL 只读数据源已启用，但缺少 URL、用户名或密码配置");
        }
        HikariDataSource dataSource = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(url).username(username).password(password).driverClassName("com.mysql.cj.jdbc.Driver").build();
        return new ControlledSqlReadClient(dataSource, timeoutSeconds);
    }
}
