package com.chargeinsight.agent.evaluation;

import com.chargeinsight.agent.chat.AgentChatSessionService;
import com.chargeinsight.agent.runtime.AnalyticsAgentRuntime;
import com.chargeinsight.agent.planning.MetricNameNormalizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/** Runs versioned regression cases against the bounded Agent workflow and reports contract mismatches. */
@Service
public class AgentEvaluationService {
    private static final String CASES_RESOURCE = "agent-evaluation/cases.json";
    private final AnalyticsAgentRuntime runtime;
    private final AgentChatSessionService chatSessions;
    private final List<EvaluationCase> cases;

    public AgentEvaluationService(AnalyticsAgentRuntime runtime, ObjectMapper objectMapper,
                                  AgentChatSessionService chatSessions) {
        this.runtime = runtime;
        this.chatSessions = chatSessions;
        this.cases = loadCases(objectMapper);
    }

    public EvaluationReport run() {
        Instant startedAt = Instant.now();
        List<CaseResult> results = cases.stream().map(this::runCase).toList();
        long passed = results.stream().filter(CaseResult::passed).count();
        return new EvaluationReport(cases.size(), passed, cases.size() - passed, Duration.between(startedAt, Instant.now()).toMillis(), results);
    }

    public List<EvaluationCase> cases() {
        return cases;
    }

    private CaseResult runCase(EvaluationCase evaluationCase) {
        Instant startedAt = Instant.now();
        SecurityContext originalSecurityContext = SecurityContextHolder.getContext();
        String sessionId = null;
        try {
            authenticateEvaluationCase(evaluationCase);
            sessionId = chatSessions.ensureSession(null, evaluationCase.question()).sessionId();
            AnalyticsAgentRuntime.AnalysisResult result = runtime.analyze(
                    evaluationCase.question(), sessionId, ignored -> { });
            CaseResult assessed = assess(evaluationCase, result, Duration.between(startedAt, Instant.now()).toMillis());
            if (evaluationCase.expectedException() == null) return assessed;
            List<String> mismatches = new java.util.ArrayList<>(assessed.mismatches());
            mismatches.add("期望抛出异常：" + evaluationCase.expectedException());
            return new CaseResult(evaluationCase.id(), false, assessed.durationMs(), List.copyOf(mismatches), result.traceId());
        } catch (RuntimeException exception) {
            if (matchesExpectedException(evaluationCase.expectedException(), exception)) {
                return new CaseResult(evaluationCase.id(), true, Duration.between(startedAt, Instant.now()).toMillis(), List.of(), null);
            }
            return new CaseResult(evaluationCase.id(), false, Duration.between(startedAt, Instant.now()).toMillis(),
                    List.of("执行失败；请通过对应 trace 排查模型、数据库或工具链状态"), null);
        } finally {
            if (sessionId != null) {
                try {
                    chatSessions.delete(sessionId);
                } catch (RuntimeException ignored) {
                    // The case result remains authoritative; cleanup failure must not mask it.
                }
            }
            restoreSecurityContext(originalSecurityContext);
        }
    }

    private void authenticateEvaluationCase(EvaluationCase evaluationCase) {
        if (evaluationCase.runAsUsername() == null || evaluationCase.runAsUsername().isBlank()) return;
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("evaluation-" + evaluationCase.id()).header("alg", "HS256")
                .subject(evaluationCase.runAsUsername()).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("ANALYST")).claim("regionScopes", evaluationCase.regionScopes()).build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
    }

    private boolean matchesExpectedException(String expectedException, Throwable exception) {
        if (expectedException == null || expectedException.isBlank()) return false;
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (expectedException.equals(current.getClass().getSimpleName())
                    || expectedException.equals(current.getClass().getName())) return true;
        }
        return false;
    }

    private void restoreSecurityContext(SecurityContext originalSecurityContext) {
        SecurityContextHolder.setContext(originalSecurityContext);
    }

    CaseResult assess(EvaluationCase evaluationCase, AnalyticsAgentRuntime.AnalysisResult result, long durationMs) {
        java.util.ArrayList<String> mismatches = new java.util.ArrayList<>();
        if (!evaluationCase.expectedStatus().equals(result.status())) {
            mismatches.add("status 期望 " + evaluationCase.expectedStatus() + "，实际 " + result.status());
        }
        if (result.plan() == null) {
            mismatches.add("未返回有效分析计划");
        } else {
            if (!evaluationCase.expectedIntent().equals(result.plan().intent().name())) {
                mismatches.add("intent 期望 " + evaluationCase.expectedIntent() + "，实际 " + result.plan().intent());
            }
            Set<String> actualMetrics = result.plan().metrics().stream().map(this::normalize).collect(java.util.stream.Collectors.toSet());
            for (String requiredMetric : evaluationCase.requiredMetrics()) {
                if (!actualMetrics.contains(normalize(requiredMetric))) {
                    mismatches.add("缺少必需指标：" + requiredMetric);
                }
            }
        }
        Set<String> actualTools = result.toolExecution() == null ? Set.of() : Set.copyOf(result.toolExecution().invokedTools());
        for (String requiredTool : evaluationCase.requiredTools()) {
            if (!actualTools.contains(requiredTool)) {
                mismatches.add("缺少必需工具：" + requiredTool);
            }
        }
        if (evaluationCase.expectedSqlFallback() != null) {
            boolean actualSqlFallback = actualTools.contains("controlledTextToSql");
            if (evaluationCase.expectedSqlFallback() != actualSqlFallback) {
                mismatches.add("受控 SQL 兜底期望 " + evaluationCase.expectedSqlFallback() + "，实际 " + actualSqlFallback);
            }
        }
        if (result.dataset() == null) {
            if (!evaluationCase.requiredDatasetFields().isEmpty() || evaluationCase.minimumRows() > 0) {
                mismatches.add("未返回 Dataset");
            }
        } else {
            Set<String> actualFields = result.dataset().fields().stream()
                    .map(field -> normalizeDatasetField(field.key()))
                    .collect(java.util.stream.Collectors.toSet());
            for (String requiredField : evaluationCase.requiredDatasetFields()) {
                if (!actualFields.contains(normalizeDatasetField(requiredField))) {
                    mismatches.add("Dataset 缺少必需字段：" + requiredField);
                }
            }
            if (result.dataset().rows().size() < evaluationCase.minimumRows()) {
                mismatches.add("Dataset 最少需要 " + evaluationCase.minimumRows() + " 行，实际 " + result.dataset().rows().size() + " 行");
            }
        }
        return new CaseResult(evaluationCase.id(), mismatches.isEmpty(), durationMs, List.copyOf(mismatches), result.traceId());
    }

    private String normalize(String value) {
        return MetricNameNormalizer.normalize(value);
    }

    /** SQL aliases are presentation labels; retain semantic field contracts across aliases such as GMV and 单日GMV（元）. */
    private String normalizeDatasetField(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s_()（）]", "");
        return normalized.contains("gmv") ? "gmv" : normalized;
    }

    private List<EvaluationCase> loadCases(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(CASES_RESOURCE).getInputStream()) {
            List<EvaluationCase> loaded = objectMapper.readValue(input, new TypeReference<>() { });
            if (loaded.isEmpty()) {
                throw new IllegalStateException("评测集不能为空");
            }
            return List.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载评测集：" + CASES_RESOURCE, exception);
        }
    }

    public record EvaluationCase(String id, String question, String expectedStatus, String expectedIntent,
                                 List<String> requiredMetrics, List<String> requiredTools,
                                 List<String> requiredDatasetFields, int minimumRows, Boolean expectedSqlFallback,
                                 String runAsUsername, List<String> regionScopes, String expectedException) {
        public EvaluationCase {
            requiredMetrics = requiredMetrics == null ? List.of() : List.copyOf(requiredMetrics);
            requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
            requiredDatasetFields = requiredDatasetFields == null ? List.of() : List.copyOf(requiredDatasetFields);
            regionScopes = regionScopes == null ? List.of() : List.copyOf(regionScopes);
        }
    }
    public record CaseResult(String caseId, boolean passed, long durationMs, List<String> mismatches, String traceId) { }
    public record EvaluationReport(int total, long passed, long failed, long durationMs, List<CaseResult> cases) { }
}
