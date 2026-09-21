package com.chargeinsight.agent.planning;

import org.springframework.stereotype.Component;

/** Applies deterministic business routing rules after model output has passed schema validation. */
@Component
public class AnalysisPlanPolicy {
    public AnalysisPlan normalize(String question, AnalysisPlan plan) {
        AnalysisPlan.Intent intent = plan.intent();
        if (isFaultRanking(question, plan)) {
            intent = AnalysisPlan.Intent.RANKING;
        } else if (isGroupFaultBreakdown(question, plan)) {
            intent = AnalysisPlan.Intent.FAULT_ANALYSIS;
        } else if (isCustomDailyDimensionQuery(question, plan)) {
            intent = AnalysisPlan.Intent.AD_HOC_QUERY;
        }
        if (intent == plan.intent()) return plan;
        return new AnalysisPlan(intent, plan.metrics(), plan.scope(), plan.steps(), plan.evidenceNeeded());
    }

    private boolean isFaultRanking(String question, AnalysisPlan plan) {
        return plan.metrics().contains("communication_timeout")
                && (question.contains("排行") || question.contains("较多") || question.contains("最高")
                || question.contains("前五") || question.contains("五个桩群"));
    }

    /** A named group's fault summary is covered by the domain tool; only explicit daily detail needs SQL fallback. */
    private boolean isGroupFaultBreakdown(String question, AnalysisPlan plan) {
        boolean asksCommunicationFault = question.contains("通信故障") || question.contains("通信超时")
                || plan.metrics().contains("communication_timeout");
        boolean hasGroup = plan.scope() != null && plan.scope().group() != null && !plan.scope().group().isBlank();
        boolean asksDailyDetail = question.contains("每日") || question.contains("每天") || question.contains("按日");
        return plan.intent() != AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE
                && asksCommunicationFault && hasGroup && !asksDailyDetail;
    }

    private boolean isCustomDailyDimensionQuery(String question, AnalysisPlan plan) {
        return plan.intent() == AnalysisPlan.Intent.METRIC
                && (question.contains("按城市") || question.contains("按桩群"))
                && (question.contains("每日") || question.contains("每天"));
    }
}
