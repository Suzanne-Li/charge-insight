package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.chat.AgentChatSessionService;
import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.trace.AnalyticsTraceService;
import com.chargeinsight.agent.tool.AnalyticsToolRouter;
import com.chargeinsight.agent.tool.GroupEntityResolver;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Entry point for planning and the bounded analytics Agent loop. */
@Service
public class AnalyticsAgentRuntime {
    private final JdbcTemplate jdbcTemplate;
    private final AnalyticsPlanningService planningService;
    private final AnalyticsAgentLoop agentLoop;
    private final AgentChatSessionService chatSessions;
    private final AnalyticsTraceService traceService;

    public AnalyticsAgentRuntime(
            JdbcTemplate jdbcTemplate,
            AnalyticsPlanningService planningService,
            AnalyticsAgentLoop agentLoop,
            AgentChatSessionService chatSessions) {
        this(jdbcTemplate, planningService, agentLoop, chatSessions, new AnalyticsTraceService(jdbcTemplate));
    }

    @Autowired
    public AnalyticsAgentRuntime(
            JdbcTemplate jdbcTemplate,
            AnalyticsPlanningService planningService,
            AnalyticsAgentLoop agentLoop,
            AgentChatSessionService chatSessions,
            AnalyticsTraceService traceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.planningService = planningService;
        this.agentLoop = agentLoop;
        this.chatSessions = chatSessions;
        this.traceService = traceService;
    }

    public AgentPlan plan(String question) {
        String traceId = traceService.start(question, null).traceId();
        Instant startedAt = Instant.now();
        try {
            var outcome = planningService.plan(question);
            saveStep(traceId, 1, "PLANNING_AGENT", "status=" + outcome.status()
                    + ", knowledgeSources=" + outcome.knowledgeSources().size() + ", "
                    + outcome.retrievalAudit().summary());
            jdbcTemplate.update("UPDATE analytics_agent_trace SET retrieved_context=?, analysis_plan=?, status=?, completed_at=NOW() WHERE trace_id=?",
                    outcome.retrievedContext(), outcome.rawPlan(), outcome.status(), traceId);
            return "SUCCESS".equals(outcome.status())
                    ? AgentPlan.success(traceId, outcome.plan(), outcome.rawPlan(), outcome.knowledgeSources(), startedAt)
                    : AgentPlan.degraded(traceId, outcome.knowledgeSources(), startedAt, String.join("；", outcome.warnings()));
        } catch (RuntimeException exception) {
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='FAILED', completed_at=NOW() WHERE trace_id=?", traceId);
            saveStep(traceId, 2, "MODEL_ERROR", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            throw exception;
        }
    }

    /** Executes the bounded planner-tool-answer workflow and returns evidence-backed business language. */
    public AnalysisResult analyze(String question) {
        return analyze(question, null, ignored -> { });
    }

    /** Executes the bounded workflow and emits only client-safe lifecycle events. */
    public AnalysisResult analyze(String question, Consumer<StreamEvent> eventConsumer) {
        return analyze(question, null, eventConsumer);
    }

    public AnalysisResult analyze(String question, String requestedSessionId, Consumer<StreamEvent> eventConsumer) {
        eventConsumer.accept(new StreamEvent("started", "分析任务已开始"));
        eventConsumer.accept(new StreamEvent("planning", "正在理解问题并生成查询计划"));
        var chatSession = chatSessions.ensureSession(requestedSessionId, question);
        String planningQuestion = chatSessions.prepareUserTurn(chatSession.sessionId(), question);
        String traceId = traceService.start(question, null).traceId();
        Instant startedAt = Instant.now();
        AtomicInteger toolStepNumber = new AtomicInteger(100);
        AnalyticsAgentContext context = new AnalyticsAgentContext(traceId, planningQuestion, startedAt,
                audit -> {
                    traceService.recordTool(traceId, toolStepNumber.getAndIncrement(), audit.toolName(), audit.parameters(),
                            audit.durationMs(), audit.resultSummary());
                    eventConsumer.accept(new StreamEvent("tool", audit));
                });
        try {
            agentLoop.run(context, step -> {
                saveStep(traceId, step.number(), step.node(), step.action() + "; " + step.summary()
                        + "; durationMs=" + step.durationMs());
                eventConsumer.accept(new StreamEvent("node", step));
            });
            var outcome = context.planning();
            if (outcome.plan() != null) chatSessions.updateScope(chatSession.sessionId(), outcome.plan().scope());
            jdbcTemplate.update("UPDATE analytics_agent_trace SET retrieved_context=?, analysis_plan=? WHERE trace_id=?",
                    outcome.retrievedContext(), outcome.rawPlan(), traceId);
            AgentPlan agentPlan = "SUCCESS".equals(outcome.status())
                    ? AgentPlan.success(traceId, outcome.plan(), outcome.rawPlan(), outcome.knowledgeSources(), startedAt)
                    : AgentPlan.degraded(traceId, outcome.knowledgeSources(), startedAt, String.join("；", outcome.warnings()));
            eventConsumer.accept(new StreamEvent("plan", agentPlan));
            if (!"SUCCESS".equals(outcome.status())) {
                jdbcTemplate.update("UPDATE analytics_agent_trace SET status='DEGRADED', completed_at=NOW() WHERE trace_id=?", traceId);
                AnalysisResult result = AnalysisResult.degraded(chatSession.sessionId(), agentPlan);
                eventConsumer.accept(new StreamEvent("completed", result));
                return result;
            }
            eventConsumer.accept(new StreamEvent("tools", context.execution()));
            eventConsumer.accept(new StreamEvent("dataset", context.dataset()));
            String status = context.checkResult().answerable() ? "SUCCESS" : context.checkResult().status();
            saveStep(traceId, context.steps().size() + 1, "FINAL_ANSWER", context.answer());
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status=?, completed_at=NOW() WHERE trace_id=?", status, traceId);
            chatSessions.append(chatSession.sessionId(), "ASSISTANT", context.answer(), traceId);
            AnalysisResult result = AnalysisResult.completed(status, chatSession.sessionId(), agentPlan, context.answer(), context.dataset(),
                    context.execution(), context.checkResult().warnings());
            eventConsumer.accept(new StreamEvent("completed", result));
            return result;
        } catch (GroupEntityResolver.ScopeResolutionException exception) {
            String warning = exception.getMessage();
            saveStep(traceId, context.steps().size() + 1, "ENTITY_RESOLUTION", warning);
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='NEEDS_CLARIFICATION', completed_at=NOW() WHERE trace_id=?", traceId);
            var outcome = context.planning();
            AgentPlan agentPlan = AgentPlan.success(traceId, outcome.plan(), outcome.rawPlan(), outcome.knowledgeSources(), startedAt);
            AnalysisResult result = AnalysisResult.needsClarification(chatSession.sessionId(), agentPlan, exception);
            chatSessions.append(chatSession.sessionId(), "ASSISTANT", result.answer(), traceId);
            eventConsumer.accept(new StreamEvent("completed", result));
            return result;
        } catch (RuntimeException exception) {
            jdbcTemplate.update("UPDATE analytics_agent_trace SET status='FAILED', completed_at=NOW() WHERE trace_id=?", traceId);
            saveStep(traceId, context.steps().size() + 1, "AGENT_LOOP_ERROR",
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            throw exception;
        }
    }

    private void saveStep(String traceId, int number, String type, String summary) {
        traceService.recordStep(traceId, number, type, summary);
    }

    public record AgentPlan(
            String status,
            String traceId,
            AnalysisPlan plan,
            String rawPlan,
            List<String> knowledgeSources,
            List<String> warnings,
            Instant startedAt,
            Instant completedAt) {
        private static AgentPlan success(String traceId, AnalysisPlan plan, String rawPlan, List<String> knowledgeSources, Instant startedAt) {
            return new AgentPlan("SUCCESS", traceId, plan, rawPlan, knowledgeSources, List.of(), startedAt, Instant.now());
        }

        private static AgentPlan degraded(String traceId, List<String> knowledgeSources, Instant startedAt, String warning) {
            return new AgentPlan("DEGRADED", traceId, null, null, knowledgeSources, List.of(warning), startedAt, Instant.now());
        }
    }

    public record AnalysisResult(
            String status,
            String traceId,
            String sessionId,
            String answer,
            AnalyticsDataset dataset,
            AnalysisPlan plan,
            AnalyticsToolRouter.ToolExecution toolExecution,
            List<String> knowledgeSources,
            List<String> warnings,
            List<GroupEntityResolver.GroupCandidate> scopeCandidates,
            Instant startedAt,
            Instant completedAt) {
        private static AnalysisResult completed(String status, String sessionId, AgentPlan agentPlan, String answer, AnalyticsDataset dataset,
                AnalyticsToolRouter.ToolExecution execution, List<String> warnings) {
            return new AnalysisResult(status, agentPlan.traceId(), sessionId, answer, dataset, agentPlan.plan(), execution,
                    agentPlan.knowledgeSources(), warnings, List.of(), agentPlan.startedAt(), Instant.now());
        }

        private static AnalysisResult degraded(String sessionId, AgentPlan agentPlan) {
            return new AnalysisResult("DEGRADED", agentPlan.traceId(), sessionId, null, null, null, null,
                    agentPlan.knowledgeSources(), agentPlan.warnings(), List.of(), agentPlan.startedAt(), Instant.now());
        }

        private static AnalysisResult needsClarification(String sessionId, AgentPlan agentPlan, GroupEntityResolver.ScopeResolutionException exception) {
            String answer = exception.ambiguous()
                    ? "无法唯一确定桩群“" + exception.requestedGroup() + "”。请指定城市或完整桩群名称后重试。"
                    : "在“" + exception.region() + "”未找到桩群“" + exception.requestedGroup() + "”。请检查名称后重试。";
            return new AnalysisResult("NEEDS_CLARIFICATION", agentPlan.traceId(), sessionId, answer, null, agentPlan.plan(), null,
                    agentPlan.knowledgeSources(), List.of(exception.getMessage()), exception.candidates(), agentPlan.startedAt(), Instant.now());
        }
    }

    public record StreamEvent(String type, Object data) { }
}
