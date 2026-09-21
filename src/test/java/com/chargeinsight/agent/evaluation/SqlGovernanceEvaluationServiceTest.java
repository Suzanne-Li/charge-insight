package com.chargeinsight.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.sql.ControlledSqlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SqlGovernanceEvaluationServiceTest {
    @Test
    void measuresValidQueryAcceptanceAndUnsafeQueryBlocking() {
        var service = new SqlGovernanceEvaluationService(new ControlledSqlService(null), new ObjectMapper());

        var report = service.run();

        assertThat(report.total()).isEqualTo(80);
        assertThat(report.expectedAllowed()).isEqualTo(24);
        assertThat(report.expectedBlocked()).isEqualTo(56);
        assertThat(report.validSqlAcceptRate()).isEqualTo(1.0);
        assertThat(report.unsafeSqlBlockRate()).isEqualTo(1.0);
        assertThat(report.passed()).isEqualTo(80);
        System.out.println("SQL_GOVERNANCE_EVALUATION=" + report);
    }
}
