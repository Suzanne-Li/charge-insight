package com.chargeinsight.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ControlledSqlReadDataSourceConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
            .withUserConfiguration(PrimaryDataSourceConfiguration.class, ControlledSqlReadDataSourceConfiguration.class)
            .withPropertyValues(
                    "charge.sql.reader.enabled=true",
                    "charge.sql.reader.url=jdbc:mysql://localhost:3306/charging_analytics",
                    "charge.sql.reader.username=reader",
                    "charge.sql.reader.password=reader-password");

    @Test
    void readerClientDoesNotSuppressPrimaryJdbcTemplateAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("jdbcTemplate").hasBean("controlledSqlReadClient");
            assertThat(context).getBean("jdbcTemplate").isInstanceOf(JdbcTemplate.class);
            assertThat(context).getBean("controlledSqlReadClient").isInstanceOf(ControlledSqlReadClient.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryDataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource();
        }
    }
}
