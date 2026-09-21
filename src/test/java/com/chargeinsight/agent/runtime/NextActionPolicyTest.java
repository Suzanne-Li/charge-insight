package com.chargeinsight.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.planning.AnalysisPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

class NextActionPolicyTest {
    private final NextActionPolicy policy = new NextActionPolicy(new AnalyticsToolSchemaRegistry());

    @Test
    void derivesBoundedDomainActionFromValidatedPlan() {
        AnalysisPlan plan = plan(AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE, List.of("本期与上期对比", "故障影响"));

        AgentDecision decision = policy.decide(plan);

        assertThat(decision.action()).isEqualTo(AgentAction.EXECUTE_DOMAIN_TOOLS);
        assertThat(decision.allowedTools()).containsExactly("compareAnomalyEvidence");
        assertThat(decision.expectedEvidence()).containsExactly("本期与上期对比", "故障影响");
        assertThat(decision.rationaleSummary()).contains("先核验本期与上期").contains("观测结果");
    }

    @Test
    void routesAdHocPlanOnlyToControlledSqlAction() {
        AgentDecision decision = policy.decide(plan(AnalysisPlan.Intent.AD_HOC_QUERY, List.of("动态查询结果")));

        assertThat(decision.action()).isEqualTo(AgentAction.EXECUTE_SQL_FALLBACK);
        assertThat(decision.allowedTools()).containsExactly("controlledTextToSql");
    }

    @Test
    void turnsCheckedObservationIntoOnlyAllowedTerminalActions() {
        assertThat(policy.decideAfterObservation(new AnalyticsResultChecker.CheckResult("VALID", List.of())).action())
                .isEqualTo(AgentAction.ANSWER);
        assertThat(policy.decideAfterObservation(new AnalyticsResultChecker.CheckResult("INVALID", List.of("范围不一致"))).action())
                .isEqualTo(AgentAction.REQUEST_CLARIFICATION);
    }

    private AnalysisPlan plan(AnalysisPlan.Intent intent, List<String> evidence) {
        return new AnalysisPlan(intent, List.of("gmv_amount"),
                new AnalysisPlan.Scope("华东", "", "", "最近一周"), List.of("执行查询"), evidence);
    }
}
