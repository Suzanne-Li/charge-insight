package com.chargeinsight.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chargeinsight.agent.chat.AgentChatSessionService;
import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.runtime.AnalyticsAgentRuntime;
import com.chargeinsight.agent.runtime.AnalyticsDataset;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import com.chargeinsight.security.RegionAccessPolicy.RegionAccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AgentEvaluationServiceTest {
    private final AgentEvaluationService evaluationService = new AgentEvaluationService(null, new ObjectMapper(), null);

    @Test
    void loadsVersionedCasesWithStableContracts() {
        assertThat(evaluationService.cases()).hasSize(23).allSatisfy(evaluationCase -> {
            assertThat(evaluationCase.id()).isNotBlank();
            assertThat(evaluationCase.question()).isNotBlank();
            assertThat(evaluationCase.expectedIntent()).isNotBlank();
        });
        assertThat(evaluationService.cases()).filteredOn(evaluationCase -> evaluationCase.expectedException() == null)
                .allSatisfy(evaluationCase -> {
                    assertThat(evaluationCase.requiredTools()).isNotEmpty();
                    assertThat(evaluationCase.requiredDatasetFields()).isNotEmpty();
                });
        assertThat(evaluationService.cases()).filteredOn(evaluationCase -> evaluationCase.expectedException() != null)
                .hasSize(2).allSatisfy(evaluationCase -> {
                    assertThat(evaluationCase.runAsUsername()).isEqualTo("evaluation-east-analyst");
                    assertThat(evaluationCase.regionScopes()).containsExactly("华东");
                    assertThat(evaluationCase.expectedException()).isEqualTo("RegionAccessDeniedException");
                });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void runsCasesWithSyntheticScopeCleansEveryTemporarySessionAndRestoresCallerContext() {
        FakeChatSessions sessions = new FakeChatSessions();
        FakeRuntime runtime = new FakeRuntime();
        AgentEvaluationService service = new AgentEvaluationService(runtime, new ObjectMapper(), sessions);
        JwtAuthenticationToken originalAuthentication = authentication("caller-admin", List.of("*"));
        SecurityContext originalContext = SecurityContextHolder.createEmptyContext();
        originalContext.setAuthentication(originalAuthentication);
        SecurityContextHolder.setContext(originalContext);

        AgentEvaluationService.EvaluationReport report = service.run();

        assertThat(report.total()).isEqualTo(23);
        assertThat(report.cases()).allSatisfy(caseResult ->
                assertThat(caseResult.passed()).as(caseResult.caseId() + ": " + caseResult.mismatches()).isTrue());
        assertThat(report.passed()).isEqualTo(23);
        assertThat(report.failed()).isZero();
        assertThat(sessions.createdSessionIds).hasSize(23);
        assertThat(sessions.deletedSessionIds).containsExactlyElementsOf(sessions.createdSessionIds);
        assertThat(runtime.evaluationSubject).isEqualTo("evaluation-east-analyst");
        assertThat(runtime.evaluationScopes).containsExactly("华东");
        assertThat(runtime.deniedCases).isEqualTo(2);
        assertThat(SecurityContextHolder.getContext()).isSameAs(originalContext);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(originalAuthentication);
    }

    @Test
    void treatsPresentationAliasForGmvAsTheSameDatasetMetric() {
        AgentEvaluationService.EvaluationCase evaluationCase = evaluationService.cases().stream()
                .filter(value -> value.id().equals("daily-gmv-threshold-controlled-sql-short-form")).findFirst().orElseThrow();
        AnalyticsDataset dataset = new AnalyticsDataset("动态查询", List.of(
                new AnalyticsDataset.Field("stat_date", "日期", "DATE", ""),
                new AnalyticsDataset.Field("group_name", "桩群", "TEXT", ""),
                new AnalyticsDataset.Field("单日GMV（元）", "单日GMV（元）", "NUMBER", "元")),
                List.of(Map.of("stat_date", "2026-08-20", "group_name", "桩群1", "单日GMV（元）", 272.8)));
        AnalysisPlan plan = new AnalysisPlan(AnalysisPlan.Intent.AD_HOC_QUERY, List.of("gmv_amount"),
                new AnalysisPlan.Scope("华东", "", "", "2026-08-20 至 2026-08-26"), List.of(), List.of());
        AnalyticsToolRouter.ToolExecution execution = new AnalyticsToolRouter.ToolExecution(List.of("controlledTextToSql"), Map.of(), List.of());
        AnalyticsAgentRuntime.AnalysisResult result = new AnalyticsAgentRuntime.AnalysisResult("SUCCESS", "trace", "session", "answer",
                dataset, plan, execution, List.of(), List.of(), List.of(), Instant.now(), Instant.now());

        assertThat(evaluationService.assess(evaluationCase, result, 1).passed()).isTrue();
    }

    private static JwtAuthenticationToken authentication(String username, List<String> regionScopes) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "HS256").subject(username)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("roles", List.of("ADMIN")).claim("regionScopes", regionScopes).build();
        return new JwtAuthenticationToken(jwt);
    }

    private static final class FakeChatSessions extends AgentChatSessionService {
        private final List<String> createdSessionIds = new ArrayList<>();
        private final List<String> deletedSessionIds = new ArrayList<>();

        private FakeChatSessions() { super(null, null); }

        @Override
        public SessionSummary ensureSession(String requestedSessionId, String firstQuestion) {
            String id = String.format("%032x", createdSessionIds.size() + 1);
            createdSessionIds.add(id);
            return new SessionSummary(id, firstQuestion, null, null, null, null, Instant.now(), Instant.now());
        }

        @Override
        public void delete(String sessionId) {
            deletedSessionIds.add(sessionId);
        }
    }

    private static final class FakeRuntime extends AnalyticsAgentRuntime {
        private String evaluationSubject;
        private List<String> evaluationScopes;
        private int deniedCases;

        private FakeRuntime() { super(null, null, null, null); }

        @Override
        public AnalysisResult analyze(String question, String requestedSessionId, java.util.function.Consumer<StreamEvent> events) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (question.contains("华南") || question.contains("华北")) {
                Jwt jwt = (Jwt) authentication.getPrincipal();
                evaluationSubject = jwt.getSubject();
                evaluationScopes = jwt.getClaimAsStringList("regionScopes");
                deniedCases++;
                throw new RegionAccessDeniedException("华南");
            }
            AnalysisPlan.Intent intent = question.contains("为什么") ? AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE
                    : question.contains("通信故障") && question.contains("五个") ? AnalysisPlan.Intent.RANKING
                    : question.contains("通信故障") ? AnalysisPlan.Intent.FAULT_ANALYSIS
                    : question.contains("趋势") ? AnalysisPlan.Intent.TREND
                    : question.contains("离线率最高") || question.contains("GMV 最低") ? AnalysisPlan.Intent.RANKING
                    : question.contains("超过") || question.contains("按城市") ? AnalysisPlan.Intent.AD_HOC_QUERY : AnalysisPlan.Intent.METRIC;
            List<String> metrics = intent == AnalysisPlan.Intent.FAULT_ANALYSIS ? List.of()
                    : intent == AnalysisPlan.Intent.RANKING && question.contains("离线率") ? List.of("offline_rate")
                    : question.contains("按城市") ? List.of("energy_kwh")
                    : List.of("gmv_amount", "available_pile_count", "energy_kwh");
            List<String> tools = switch (intent) {
                case ANOMALY_ROOT_CAUSE -> List.of("compareAnomalyEvidence", "queryOperationTrend", "rankGroups");
                case FAULT_ANALYSIS -> question.contains("上海") ? List.of("queryFaultBreakdown") : List.of("rankFaultGroups");
                case TREND -> List.of("queryOperationTrend");
                case RANKING -> question.contains("通信故障") ? List.of("rankFaultGroups") : List.of("rankGroups");
                case AD_HOC_QUERY -> List.of("controlledTextToSql");
                default -> List.of("queryOperationOverview");
            };
            List<String> fieldKeys = switch (intent) {
                case ANOMALY_ROOT_CAUSE -> List.of("period", "gmvAmount");
                case FAULT_ANALYSIS -> question.contains("上海") ? List.of("faultType", "faultDurationMinutes")
                        : List.of("groupName", "faultPileDays", "faultDurationMinutes");
                case TREND -> List.of("statDate", "metricValue");
                case RANKING -> question.contains("通信故障") ? List.of("groupName", "faultPileDays", "faultDurationMinutes")
                        : question.contains("离线率") ? List.of("groupName", "offlineRate")
                        : List.of("groupName", "gmvAmount");
                case AD_HOC_QUERY -> question.contains("按城市") ? List.of("stat_date", "city_name")
                        : List.of("stat_date", "group_name", "GMV");
                default -> List.of("snapshotDate", "availablePileCount", "energyKwh", "gmvAmount");
            };
            Map<String, Object> row = fieldKeys.stream()
                    .collect(java.util.stream.Collectors.toMap(key -> key, key -> "value"));
            List<Map<String, Object>> rows = intent == AnalysisPlan.Intent.ANOMALY_ROOT_CAUSE
                    ? List.of(row, Map.copyOf(row)) : List.of(row);
            AnalyticsDataset dataset = new AnalyticsDataset("test", fieldKeys.stream()
                    .map(key -> new AnalyticsDataset.Field(key, key, "TEXT", "")).toList(), rows);
            AnalysisPlan plan = new AnalysisPlan(intent, metrics,
                    new AnalysisPlan.Scope("华东", null, null, "最近一周"), List.of(), List.of());
            AnalyticsToolRouter.ToolExecution execution = new AnalyticsToolRouter.ToolExecution(tools, Map.of(), List.of());
            return new AnalysisResult("SUCCESS", "trace", requestedSessionId, "answer", dataset, plan, execution,
                    List.of(), List.of(), List.of(), Instant.now(), Instant.now());
        }
    }
}
