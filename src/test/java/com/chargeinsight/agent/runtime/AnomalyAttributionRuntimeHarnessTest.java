package com.chargeinsight.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.chargeinsight.agent.knowledge.ProductionKnowledgeRetriever;
import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.tool.AnalysisPeriodResolver;
import com.chargeinsight.agent.tool.AnalyticsQueryPolicy;
import com.chargeinsight.agent.tool.AnalyticsQueryTools;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import com.chargeinsight.agent.tool.GroupEntityResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

/**
 * Versioned, in-process Harness for the real root-cause state machine. It deliberately
 * replaces only external data access; decision policy, router, result checker and renderer
 * are the same production classes used by the runtime.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnomalyAttributionRuntimeHarnessTest {
    private static final String PERIOD = "2026-08-20 至 2026-08-26";

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    void runsVersionedAttributionCasesThroughActualRuntimeLoop(HarnessCase scenario) {
        AnalyticsQueryPolicy queryPolicy = new AnalyticsQueryPolicy();
        AnalysisPeriodResolver periodResolver = new AnalysisPeriodResolver(null, queryPolicy);
        GroupEntityResolver groupResolver = org.mockito.Mockito.mock(GroupEntityResolver.class);
        when(groupResolver.resolve(anyString(), anyString(), anyString())).thenReturn(resolvedGroup());

        AnalyticsToolRouter router = new AnalyticsToolRouter(new HarnessQueryTools(scenario), periodResolver, groupResolver);
        AnalyticsToolSchemaRegistry schemas = new AnalyticsToolSchemaRegistry();
        RootCauseEvidencePolicy rootCausePolicy = new RootCauseEvidencePolicy();
        NextActionPolicy nextActionPolicy = new NextActionPolicy(schemas, rootCausePolicy);
        AnalyticsAgentLoop loop = new AnalyticsAgentLoop(List.of(
                planningNode(plan()),
                new NextActionAgentNode(nextActionPolicy),
                new QueryExecutionAgentNode(router, null, schemas, rootCausePolicy),
                new ResultCheckAgentNode(new AnalyticsResultChecker(periodResolver)),
                new ResultDecisionAgentNode(nextActionPolicy),
                new OperationsAnswerAgentNode()));

        List<AnalyticsToolRouter.ToolAudit> emittedAudits = new ArrayList<>();
        AnalyticsAgentContext context = new AnalyticsAgentContext("a".repeat(32), scenario.question(), Instant.EPOCH,
                emittedAudits::add);

        loop.run(context, ignored -> { });

        assertThat(context.finished()).isTrue();
        assertThat(context.planning().plan().intent().name()).isEqualTo(scenario.expectedIntent());
        assertThat(context.steps()).extracting(AnalyticsAgentContext.AgentLoopStep::node)
                .containsExactlyElementsOf(scenario.expectedNodeSequence());
        assertThat(emittedAudits).extracting(AnalyticsToolRouter.ToolAudit::toolName)
                .containsExactlyElementsOf(scenario.requiredTools());
        assertThat(emittedAudits).hasSizeLessThanOrEqualTo(scenario.maxToolCalls());
        assertThat(emittedAudits.get(0).toolName()).isEqualTo("compareAnomalyEvidence");
        assertThat(context.execution().toolAudits()).hasSize(emittedAudits.size());
        assertThat(context.checkResult().answerable()).isTrue();
        assertThat(context.answer()).contains(scenario.requiredAnswerPhrases())
                .doesNotContain(scenario.forbiddenAnswerPhrases());
    }

    Stream<HarnessCase> cases() throws Exception {
        try (var input = new ClassPathResource("agent-evaluation/attribution-golden-cases.json").getInputStream()) {
            return new ObjectMapper().readValue(input, new TypeReference<List<HarnessCase>>() { }).stream();
        }
    }

    private AnalysisPlan plan() {
        return new AnalysisPlan(AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE, List.of("gmv_amount"),
                new AnalysisPlan.Scope("华东", "上海", "私桩共享桩群", PERIOD),
                List.of("同期对比"), List.of("同期对比"));
    }

    private AnalyticsAgentNode planningNode(AnalysisPlan plan) {
        return new AnalyticsAgentNode() {
            @Override public String name() { return "PLANNING_AGENT"; }
            @Override public boolean supports(AnalyticsAgentContext context) { return context.planning() == null; }
            @Override public NodeResult act(AnalyticsAgentContext context) {
                context.planning(new AnalyticsPlanningService.PlanningOutcome("SUCCESS", plan, "harness-plan", "",
                        List.of("harness"), List.of(), new ProductionKnowledgeRetriever.RetrievalAudit(
                                "HARNESS", List.of(), List.of(), List.of(), "")));
                return new NodeResult("CREATE_QUERY_PLAN", "source=versioned-harness");
            }
        };
    }

    private GroupEntityResolver.ResolvedGroup resolvedGroup() {
        return new GroupEntityResolver.ResolvedGroup("华东", "上海", "私桩共享桩群",
                new GroupEntityResolver.GroupCandidate(101L, "上海", "私桩共享桩群1"),
                GroupEntityResolver.ResolutionMode.EXACT);
    }

    private static final class HarnessQueryTools extends AnalyticsQueryTools {
        private final HarnessCase scenario;

        private HarnessQueryTools(HarnessCase scenario) {
            super(null, null, null);
            this.scenario = scenario;
        }

        @Override
        public AnomalyEvidence compareAnomalyEvidence(String region, String city, String group,
                                                      LocalDate currentStart, LocalDate currentEnd,
                                                      LocalDate previousStart, LocalDate previousEnd) {
            List<FaultBreakdown> faults = scenario.expectedFaultCode() == null ? List.of() : List.of(new FaultBreakdown(
                    "通信异常", scenario.expectedFaultCode(), 2, scenario.expectedFaultDurationMinutes(), 4));
            return new AnomalyEvidence(overview(scenario.current(), currentStart, currentEnd),
                    overview(scenario.previous(), previousStart, previousEnd), faults);
        }

        @Override
        public List<GroupRanking> rankGroups(String region, String city, GroupRankingMetric metric,
                                             LocalDate startDate, LocalDate endDate, Integer limit) {
            return List.of(new GroupRanking(101L, "私桩共享桩群1", metric, new BigDecimal("800.00"),
                    new BigDecimal("5"), new BigDecimal("3"), 2, 10, new BigDecimal("0.3000")));
        }

        @Override
        public List<TrendPoint> queryOperationTrend(String region, String city, String group, TrendMetric metric,
                                                     LocalDate startDate, LocalDate endDate) {
            return List.of(new TrendPoint(startDate, metric, BigDecimal.ONE), new TrendPoint(endDate, metric, BigDecimal.TEN));
        }

        private OperationOverview overview(PeriodEvidence evidence, LocalDate startDate, LocalDate endDate) {
            return new OperationOverview("华东", "上海", "私桩共享桩群1", startDate, endDate, endDate,
                    evidence.availablePileCount(), 10, BigDecimal.valueOf(evidence.availablePileCount()),
                    BigDecimal.valueOf(evidence.offlineRate() * 10), evidence.orderCount(), evidence.orderCount(),
                    new BigDecimal("100.0"), BigDecimal.valueOf(evidence.gmvAmount()), BigDecimal.valueOf(evidence.offlineRate()), BigDecimal.ONE);
        }
    }

    record HarnessCase(String id, String question, String expectedIntent, List<String> requiredTools,
                       List<String> expectedNodeSequence, int maxToolCalls, List<String> requiredAnswerPhrases,
                       List<String> forbiddenAnswerPhrases, PeriodEvidence current, PeriodEvidence previous,
                       String expectedFaultCode, Long expectedFaultDurationMinutes) { }
    record PeriodEvidence(double gmvAmount, long orderCount, long availablePileCount, double offlineRate) { }
}
