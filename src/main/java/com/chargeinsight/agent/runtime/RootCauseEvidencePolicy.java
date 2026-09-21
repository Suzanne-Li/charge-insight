package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Chooses at most one follow-up root-cause tool from structured period-comparison evidence.
 * This is an auditable policy, rather than exposing model chain-of-thought.
 */
@Component
public class RootCauseEvidencePolicy {
    static final String PERIOD_COMPARISON = "同期对比";
    static final String OFFLINE_GROUP_RANKING = "离线率桩群排行";
    static final String AVAILABILITY_TREND = "可用桩趋势";
    static final String GMV_TREND = "GMV趋势";

    public AgentDecision initialDecision(List<String> requestedEvidence) {
        return new AgentDecision(AgentAction.EXECUTE_DOMAIN_TOOLS, List.of("compareAnomalyEvidence"),
                "先核验本期与上期的 GMV、可用桩、订单和离线率，再按观测结果决定是否补充定位证据",
                requestedEvidence.isEmpty() ? List.of(PERIOD_COMPARISON) : List.copyOf(requestedEvidence));
    }

    public Optional<AgentDecision> selectFollowUp(AnalyticsQueryTools.AnomalyEvidence evidence) {
        var current = evidence.currentPeriod();
        var previous = evidence.previousPeriod();
        if (current.offlineRate().compareTo(previous.offlineRate()) > 0) {
            return Optional.of(decision("rankGroups", OFFLINE_GROUP_RANKING,
                    "观测到本期离线率上升，补查离线率靠前桩群以定位受影响对象"));
        }
        if (current.availablePileCount() < previous.availablePileCount()) {
            return Optional.of(decision("queryOperationTrend", AVAILABILITY_TREND,
                    "观测到本期可用桩减少，补查可用桩趋势以确认变化是否集中在特定日期"));
        }
        if (current.gmvAmount().compareTo(previous.gmvAmount()) < 0 || current.orderCount() < previous.orderCount()) {
            return Optional.of(decision("queryOperationTrend", GMV_TREND,
                    "观测到 GMV 或订单量下降且供给指标未恶化，补查 GMV 日趋势以区分持续变化与单日波动"));
        }
        return Optional.empty();
    }

    private AgentDecision decision(String tool, String evidence, String rationale) {
        return new AgentDecision(AgentAction.EXECUTE_DOMAIN_TOOLS, List.of(tool), rationale, List.of(evidence));
    }
}
