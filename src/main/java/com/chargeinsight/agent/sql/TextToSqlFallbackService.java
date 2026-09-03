package com.chargeinsight.agent.sql;

import com.chargeinsight.agent.knowledge.MetricKnowledgeService;
import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.tool.AnalysisPeriodResolver;
import com.chargeinsight.security.RegionAccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** Generates one tightly bounded read-only query only when no domain tool can answer the plan. */
@Service
public class TextToSqlFallbackService {
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final MetricKnowledgeService knowledgeService;
    private final AnalysisPeriodResolver periodResolver;
    private final RegionAccessPolicy regionAccessPolicy;
    private final ControlledSqlService controlledSqlService;

    public TextToSqlFallbackService(ChatClient.Builder builder, ObjectMapper objectMapper,
                                    MetricKnowledgeService knowledgeService,
                                    AnalysisPeriodResolver periodResolver,
                                    RegionAccessPolicy regionAccessPolicy,
                                    ControlledSqlService controlledSqlService) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;
        this.periodResolver = periodResolver;
        this.regionAccessPolicy = regionAccessPolicy;
        this.controlledSqlService = controlledSqlService;
    }

    public FallbackExecution execute(String question, AnalysisPlan plan) {
        String region = plan.scope().region().trim();
        regionAccessPolicy.assertAllowed(region);
        AnalysisPeriodResolver.ResolvedPeriod period = periodResolver.resolve(plan.scope().timeRange());
        String knowledge = knowledge(plan, question);
        RuntimeException firstFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                SqlGeneration generation = generate(question, region, period.startDate(), period.endDate(), knowledge,
                        firstFailure == null ? null : firstFailure.getMessage());
                ControlledSqlService.SqlExecution execution = controlledSqlService.executeAgentFallback(
                        generation.sql(), region, period.startDate(), period.endDate());
                return new FallbackExecution(generation.title(), generation.explanation(), execution.normalizedSql(),
                        execution.views(), execution.estimatedRows(), execution.rows(), region,
                        period.startDate(), period.endDate(), attempt);
            } catch (RuntimeException exception) {
                if (attempt == 2) throw new IllegalArgumentException("自动查询在一次修复后仍未通过校验或执行", exception);
                firstFailure = exception;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private SqlGeneration generate(String question, String region, LocalDate startDate, LocalDate endDate,
                                   String knowledge, String previousError) {
        String repair = previousError == null ? "" : "\n上一次 SQL 未通过系统校验，错误：" + previousError + "。请只修正 SQL。";
        String raw = chatClient.prompt().system("""
                你是充换电运营问数系统的受限 SQL 生成节点，不负责回答用户。
                只输出严格 JSON：{"title":"面向运营的结果标题","sql":"单条 MySQL SELECT","explanation":"一句话说明查询口径"}。
                SQL 规则：
                1. 只能查询给定 Schema 中的一个语义视图；禁止 JOIN、UNION、子查询、CTE、注释和写操作。
                2. WHERE 必须使用 AND，同时包含 region_name = 给定区域，以及 stat_date BETWEEN 给定开始日期 AND 结束日期。
                3. 标识符必须与 Schema 完全一致；聚合字段使用清晰的中文别名；结果最多 100 行。
                4. 可用桩是每日快照；GMV、充电量和订单量才可在周期内累计。不要混淆存量和流量指标。
                """).user("Schema 与示例：\n" + knowledge
                + "\n\n服务端限定区域：" + region + "\n服务端限定日期：" + startDate + " 至 " + endDate
                + "\n用户问题：" + question + repair).call().content();
        try {
            return objectMapper.readValue(stripFence(raw), SqlGeneration.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("SQL 生成结果不是有效 JSON", exception);
        }
    }

    private String knowledge(AnalysisPlan plan, String question) {
        String query = question + " " + String.join(" ", plan.metrics());
        List<MetricKnowledgeService.Document> schema = knowledgeService.retrieve(query,
                MetricKnowledgeService.KnowledgeType.SCHEMA, 2);
        List<MetricKnowledgeService.Document> examples = knowledgeService.retrieve(query,
                MetricKnowledgeService.KnowledgeType.SQL_EXAMPLE, 2);
        return java.util.stream.Stream.concat(schema.stream(), examples.stream())
                .map(document -> "[" + document.source() + "]\n" + document.content())
                .reduce("", (left, right) -> left + "\n\n" + right);
    }

    private String stripFence(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("```json") && trimmed.endsWith("```")) return trimmed.substring(7, trimmed.length() - 3).trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) return trimmed.substring(3, trimmed.length() - 3).trim();
        return trimmed;
    }

    public record SqlGeneration(String title, String sql, String explanation) { }
    public record FallbackExecution(String title, String explanation, String sql, List<String> views,
                                    long estimatedRows, List<Map<String, Object>> rows, String region,
                                    LocalDate startDate, LocalDate endDate, int attempts) { }
}
