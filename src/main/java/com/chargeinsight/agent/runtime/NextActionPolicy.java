package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.planning.AnalysisPlan;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Creates a constrained execution decision from the model-produced, schema-validated analysis plan. */
@Component
public class NextActionPolicy {
    private final AnalyticsToolSchemaRegistry schemas;
    private final RootCauseEvidencePolicy rootCauseEvidencePolicy;

    @Autowired
    public NextActionPolicy(AnalyticsToolSchemaRegistry schemas, RootCauseEvidencePolicy rootCauseEvidencePolicy) {
        this.schemas = schemas;
        this.rootCauseEvidencePolicy = rootCauseEvidencePolicy;
    }

    NextActionPolicy(AnalyticsToolSchemaRegistry schemas) {
        this(schemas, new RootCauseEvidencePolicy());
    }

    public AgentDecision decide(AnalysisPlan plan) {
        AnalyticsToolSchemaRegistry.ToolSchema schema = schemas.schemaFor(plan.intent());
        AgentAction action = plan.intent() == AnalysisPlan.Intent.AD_HOC_QUERY
                ? AgentAction.EXECUTE_SQL_FALLBACK : AgentAction.EXECUTE_DOMAIN_TOOLS;
        List<String> expectedEvidence = plan.evidenceNeeded().isEmpty() ? schema.evidence() : plan.evidenceNeeded();
        String rationale = "已校验意图为 " + plan.intent() + "，" + schema.purpose()
                + "由受控工具覆盖，预期获得" + String.join("、", expectedEvidence);
        AgentDecision decision = plan.intent() == AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE
                ? rootCauseEvidencePolicy.initialDecision(expectedEvidence)
                : new AgentDecision(action, schema.tools(), rationale, expectedEvidence);
        schemas.validate(decision, plan);
        return decision;
    }

    public AgentDecision decideAfterObservation(AnalyticsResultChecker.CheckResult result) {
        if (result.answerable()) {
            return new AgentDecision(AgentAction.ANSWER, List.of(), "结果校验通过或仅含提示，可生成运营回答", List.of());
        }
        return new AgentDecision(AgentAction.REQUEST_CLARIFICATION, List.of(),
                "结果校验未满足回答条件，需要用户调整范围或补充信息", List.of());
    }
}
