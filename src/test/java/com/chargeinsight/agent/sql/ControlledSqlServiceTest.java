package com.chargeinsight.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;

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
}
