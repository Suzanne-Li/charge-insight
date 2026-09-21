package com.chargeinsight.agent.tool;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.runtime.AgentAction;
import com.chargeinsight.agent.runtime.AgentDecision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsToolRouterStepTest {
    @Test
    void rejectsBatchAttributionDecisionBeforeAnyToolCanRun() {
        AnalyticsToolRouter router = new AnalyticsToolRouter(null, null, null);
        AgentDecision batch = new AgentDecision(AgentAction.EXECUTE_DOMAIN_TOOLS,
                List.of("compareAnomalyEvidence", "rankGroups"), "invalid", List.of());

        assertThatIllegalArgumentException().isThrownBy(() -> router.executeAnomalyStep(plan(), batch, Map.of(), ignored -> { }))
                .withMessage("异常归因步骤必须只允许一个工具");
    }

    private AnalysisPlan plan() {
        return new AnalysisPlan(AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE, List.of("gmv_amount"),
                new AnalysisPlan.Scope("华东", "上海", "私桩共享桩群1", "最近一周"), List.of(), List.of());
    }

}
