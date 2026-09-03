package com.chargeinsight.agent.runtime;

import com.chargeinsight.agent.knowledge.MetricKnowledgeService;
import com.chargeinsight.agent.planning.AnalysisPlan;
import com.chargeinsight.agent.planning.AnalysisPlanParser;
import com.chargeinsight.agent.planning.AnalysisPlanPolicy;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** LLM-backed planning stage. It never executes SQL or returns a business answer. */
@Service
public class AnalyticsPlanningService {
    private final ChatClient chatClient;
    private final MetricKnowledgeService knowledgeService;
    private final AnalysisPlanParser planParser;
    private final AnalysisPlanPolicy planPolicy;

    public AnalyticsPlanningService(ChatClient.Builder builder, MetricKnowledgeService knowledgeService,
                                    AnalysisPlanParser planParser, AnalysisPlanPolicy planPolicy) {
        this.chatClient = builder.build();
        this.knowledgeService = knowledgeService;
        this.planParser = planParser;
        this.planPolicy = planPolicy;
    }

    public PlanningOutcome plan(String question) {
        List<MetricKnowledgeService.Document> documents = knowledgeService.retrieve(question, 4);
        String context = documents.stream().map(document -> "[" + document.source() + "]\n" + document.content())
                .reduce("", (left, right) -> left + "\n\n" + right);
        String rawPlan = null;
        try {
            rawPlan = chatClient.prompt().system("""
                    你是充换电运营问数 Agent 的规划节点。不要回答最终业务结论，不要编造数据，也不要直接编写 SQL。
                    依据给定业务知识，只输出严格 JSON：
                    {"intent":"METRIC|RANKING|TREND|ANOMALY_ROOT_CAUSE|FAULT_ANALYSIS|AD_HOC_QUERY","metrics":[...],"scope":{"region":"","city":"","group":"","timeRange":""},"steps":[...],"evidenceNeeded":[...]}
                    region 填运营大区，city 填城市。METRIC、RANKING 和区域故障排行的 group 填空字符串。
                    “最高的五个桩群”“排行”属于 RANKING；区域运营总览属于 METRIC；区域内哪些桩群故障较多属于 FAULT_ANALYSIS。
                    “通信故障”“通信超时”对应 metrics 填 communication_timeout。
                    只有固定工具无法满足的临时维度组合、明细筛选或自定义聚合才使用 AD_HOC_QUERY；已有工具可回答时禁止使用。
                    可用桩是存量指标，周期问题使用最后一个有数据日期的快照；充电量、GMV 和订单量是周期累计指标。
                    对“为什么下降”问题，步骤依次包含本期/上期对比、异常桩群定位、运营指标变化、故障分析和结果检查。
                    """).user("业务知识：\n" + context + "\n\n用户问题：" + question).call().content();
            AnalysisPlan plan = planPolicy.normalize(question, planParser.parse(rawPlan));
            return PlanningOutcome.success(plan, rawPlan, context,
                    documents.stream().map(MetricKnowledgeService.Document::source).toList());
        } catch (AnalysisPlanParser.InvalidAnalysisPlanException exception) {
            return PlanningOutcome.degraded(rawPlan, context,
                    documents.stream().map(MetricKnowledgeService.Document::source).toList(),
                    "模型规划输出校验失败：" + exception.getMessage());
        }
    }

    public record PlanningOutcome(String status, AnalysisPlan plan, String rawPlan, String retrievedContext,
                                  List<String> knowledgeSources, List<String> warnings) {
        static PlanningOutcome success(AnalysisPlan plan, String rawPlan, String context, List<String> sources) {
            return new PlanningOutcome("SUCCESS", plan, rawPlan, context, sources, List.of());
        }

        static PlanningOutcome degraded(String rawPlan, String context, List<String> sources, String warning) {
            return new PlanningOutcome("DEGRADED", null, rawPlan, context, sources, List.of(warning));
        }
    }
}
