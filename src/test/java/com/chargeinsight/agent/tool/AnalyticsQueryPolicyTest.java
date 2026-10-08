package com.chargeinsight.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.chargeinsight.security.RegionAccessPolicy;

class AnalyticsQueryPolicyTest {
    private final AnalyticsQueryPolicy policy = new AnalyticsQueryPolicy();

    @Test
    void acceptsInclusiveThirtyOneDayRangeAndDefaultLimit() {
        var range = policy.dateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(range.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(policy.limit(null)).isEqualTo(10);
    }

    @Test
    void rejectsOversizedRangeAndLimit() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> policy.dateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> policy.limit(21));
    }

    @Test
    void appliesCityAsAParameterizedPredicateForRankingsAndTrends() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        AnalyticsQueryTools tools = new AnalyticsQueryTools(jdbcTemplate, policy, new RegionAccessPolicy());
        LocalDate start = LocalDate.of(2026, 8, 20);
        LocalDate end = LocalDate.of(2026, 8, 26);

        tools.rankGroups("华东", "上海", AnalyticsQueryTools.GroupRankingMetric.GMV_LOW, start, end, 5);

        assertThat(jdbcTemplate.sql).contains("region_name = ? AND city_name = ? AND stat_date BETWEEN ? AND ?");
        assertThat(jdbcTemplate.arguments).containsExactly("华东", "上海", java.sql.Date.valueOf(start), java.sql.Date.valueOf(end), 5);

        tools.queryOperationTrend("华东", "上海", "私桩共享桩群1", AnalyticsQueryTools.TrendMetric.ORDER_COUNT, start, end);

        assertThat(jdbcTemplate.sql).contains("region_name = ? AND city_name = ? AND group_name = ?");
        assertThat(jdbcTemplate.arguments).containsExactly("华东", "上海", "私桩共享桩群1", java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));

        tools.queryOperationOverview("华东", "上海", start, end);

        assertThat(jdbcTemplate.sql).contains("WHERE region_name = ? AND city_name = ? AND stat_date BETWEEN ? AND ?");
        assertThat(jdbcTemplate.arguments).containsExactly("华东", "上海", java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));

        tools.rankFaultGroups("华东", "上海", "COMMUNICATION_TIMEOUT", start, end, 5);

        assertThat(jdbcTemplate.sql).contains("WHERE region_name = ? AND city_name = ? AND stat_date BETWEEN ? AND ? AND fault_code = ?");
        assertThat(jdbcTemplate.arguments).containsExactly("华东", "上海", java.sql.Date.valueOf(start), java.sql.Date.valueOf(end), "COMMUNICATION_TIMEOUT", 5);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] arguments;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return null;
        }
    }
}
