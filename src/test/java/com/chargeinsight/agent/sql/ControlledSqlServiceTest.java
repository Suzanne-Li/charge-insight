package com.chargeinsight.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

class ControlledSqlServiceTest {
    private final ControlledSqlService sqlService = new ControlledSqlService(null);

    @Test
    void validatesWhitelistedSelectAndAppendsLimit() {
        ControlledSqlService.SqlValidation result = sqlService.validate("SELECT region_name, SUM(gmv_amount) FROM v_daily_group_operation GROUP BY region_name");

        assertThat(result.views()).containsExactly("v_daily_group_operation");
        assertThat(result.normalizedSql()).endsWith("LIMIT 100");
    }

    @Test
    void rejectsWriteStatementAndNonWhitelistedTable() {
        assertThatIllegalArgumentException().isThrownBy(() -> sqlService.validate("DELETE FROM v_daily_group_operation"));
        assertThatIllegalArgumentException().isThrownBy(() -> sqlService.validate("SELECT * FROM charging_order"));
    }

    @Test
    void rejectsUnsafeOrOversizedLimit() {
        assertThatIllegalArgumentException().isThrownBy(() -> sqlService.validate("SELECT * FROM v_daily_group_operation LIMIT 101"));
        assertThatIllegalArgumentException().isThrownBy(() -> sqlService.validate("SELECT * FROM v_daily_group_operation; DELETE FROM charging_order"));
    }

    @Test
    void validatesAgentFallbackScopeAndDateRange() {
        var result = sqlService.validateAgentFallback("""
                SELECT city_name, SUM(gmv_amount) AS GMV
                FROM v_daily_group_operation
                WHERE region_name = '华东' AND stat_date BETWEEN '2026-08-20' AND '2026-08-26'
                GROUP BY city_name
                """, "华东", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26));

        assertThat(result.normalizedSql()).contains("region_name = '华东'").endsWith("LIMIT 100");
    }

    @Test
    void rejectsFallbackThatCouldBroadenServerScope() {
        assertThatIllegalArgumentException().isThrownBy(() -> sqlService.validateAgentFallback("""
                SELECT city_name, SUM(gmv_amount)
                FROM v_daily_group_operation
                WHERE region_name = '华东' OR region_name = '华南'
                  AND stat_date BETWEEN '2026-08-20' AND '2026-08-26'
                GROUP BY city_name
                """, "华东", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26)));
        assertThatIllegalArgumentException().isThrownBy(() -> sqlService.validateAgentFallback("""
                SELECT city_name, SUM(gmv_amount)
                FROM v_daily_group_operation
                WHERE stat_date BETWEEN '2026-08-20' AND '2026-08-26'
                GROUP BY city_name
                """, "华东", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 26)));
    }
    @Test
    void refusesExecutionWhenNoDedicatedReadOnlyDatasourceExists() {
        assertThatIllegalStateException().isThrownBy(() -> sqlService.execute("SELECT * FROM v_daily_group_operation"))
                .withMessage("受控 SQL 执行需要启用独立只读数据源");
    }

    @Test
    void reportsOnlySafeReaderStateWhenReadOnlyDatasourceIsNotConfigured() {
        assertThat(sqlService.readOnlyDatasourceStatus().state()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void controlledSqlFailureExposesOnlyExecutionPhase() {
        var exception = new ControlledSqlService.ControlledSqlExecutionException("EXPLAIN", new RuntimeException("driver detail"));

        assertThat(exception.phase()).isEqualTo("EXPLAIN");
        assertThat(exception.getMessage()).doesNotContain("driver detail");
    }

    @Test
    void explainUsesTheSuppliedPrimaryTemplate() {
        JdbcTemplate primary = mock(JdbcTemplate.class);
        when(primary.queryForList("EXPLAIN SELECT * FROM v_daily_group_operation LIMIT 1"))
                .thenReturn(List.of(Map.of("rows", 9L)));

        assertThat(ControlledSqlService.estimatedRows(primary, "SELECT * FROM v_daily_group_operation LIMIT 1")).isEqualTo(9L);
        verify(primary).queryForList("EXPLAIN SELECT * FROM v_daily_group_operation LIMIT 1");
    }
}
