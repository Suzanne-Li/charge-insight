package com.chargeinsight.agent.evaluation;

import com.chargeinsight.agent.knowledge.MetricKnowledgeService;
import com.chargeinsight.agent.knowledge.DenseKnowledgeRetriever;
import com.chargeinsight.agent.knowledge.MixedKnowledgeRetriever;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Offline, labelled evaluation for the small local knowledge base. */
@Service
public class RetrievalEvaluationService {
    private static final int EVALUATION_CANDIDATE_K = 15;
    private static final int EVALUATION_TOP_K = 5;
    private static final String CASES_RESOURCE = "agent-evaluation/retrieval-cases.json";
    private static final String EXPANDED_CASES_RESOURCE = "agent-evaluation/retrieval-expanded-cases.json";
    private static final String COLLOQUIAL_HOLDOUT_RESOURCE = "agent-evaluation/retrieval-colloquial-holdout-cases.json";
    private final MetricKnowledgeService knowledgeService;
    private final DenseKnowledgeRetriever denseRetriever;
    private final MixedKnowledgeRetriever mixedRetriever;
    private final KnowledgeTopicResolver topicResolver = new KnowledgeTopicResolver();
    private final List<RetrievalCase> cases;

    public RetrievalEvaluationService(MetricKnowledgeService knowledgeService, ObjectMapper objectMapper) {
        this(knowledgeService, null, null, objectMapper);
    }

    @Autowired
    public RetrievalEvaluationService(MetricKnowledgeService knowledgeService, DenseKnowledgeRetriever denseRetriever,
            MixedKnowledgeRetriever mixedRetriever, ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.denseRetriever = denseRetriever;
        this.mixedRetriever = mixedRetriever;
        this.cases = loadCases(objectMapper);
    }

    public RetrievalReport run() {
        return run(EvaluationSplit.ALL);
    }

    public RetrievalReport run(EvaluationSplit split) {
        List<RetrievalCase> selected = cases.stream().filter(value -> split.includes(splitOf(value))).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("评测分组没有案例：" + split);
        Map<MetricKnowledgeService.RetrievalStrategy, StrategyMetrics> results =
                new EnumMap<>(MetricKnowledgeService.RetrievalStrategy.class);
        for (MetricKnowledgeService.RetrievalStrategy strategy : MetricKnowledgeService.RetrievalStrategy.values()) {
            results.put(strategy, evaluate(selected, strategy));
        }
        return new RetrievalReport(split, selected.size(), Map.copyOf(results));
    }

    /** Runs the real configured Dense index against the same labelled cases as the lexical baseline. */
    public CandidateReport runDense(EvaluationSplit split) {
        requireDenseCandidates();
        List<RetrievalCase> selected = selectedCases(split);
        return new CandidateReport("DENSE", split, selected.size(), evaluate(selected, question -> denseRetriever.retrieve(question, EVALUATION_CANDIDATE_K).stream()
                .map(value -> value.document().id()).toList()));
    }

    /** Runs BM25 + Dense RRF using the same candidates and metrics; it does not change Planner routing. */
    public CandidateReport runRrf(EvaluationSplit split) {
        requireDenseCandidates();
        List<RetrievalCase> selected = selectedCases(split);
        return new CandidateReport("BM25_DENSE_RRF", split, selected.size(), evaluate(selected, question -> mixedRetriever.retrieve(question, EVALUATION_CANDIDATE_K).stream()
                .map(value -> value.document().id()).toList()));
    }

    /**
     * Returns per-question ranked topic IDs for offline error analysis. This deliberately omits
     * knowledge text and model prompts; it must not be used to alter production routing directly.
     */
    public RetrievalAuditReport audit(EvaluationSplit split) {
        requireDenseCandidates();
        List<RetrievalCase> selected = selectedCases(split);
        return new RetrievalAuditReport(split, selected.size(), selected.stream().map(retrievalCase -> {
            List<String> expected = retrievalCase.relevantDocumentIds().stream().map(topicResolver::topicOf).distinct().toList();
            List<String> bm25 = canonicalTopTopics(knowledgeService.retrieve(retrievalCase.question(), EVALUATION_CANDIDATE_K, MetricKnowledgeService.RetrievalStrategy.BM25)
                    .stream().map(MetricKnowledgeService.Document::id).map(topicResolver::topicOf).toList());
            List<String> dense = canonicalTopTopics(denseRetriever.retrieve(retrievalCase.question(), EVALUATION_CANDIDATE_K).stream()
                    .map(value -> value.document().id()).map(topicResolver::topicOf).toList());
            List<String> rrf = canonicalTopTopics(mixedRetriever.retrieve(retrievalCase.question(), EVALUATION_CANDIDATE_K).stream()
                    .map(value -> value.document().id()).map(topicResolver::topicOf).toList());
            return new RetrievalAuditCase(retrievalCase.id(), retrievalCase.question(), expected,
                    strategyAudit(bm25, expected), strategyAudit(dense, expected), strategyAudit(rrf, expected));
        }).toList());
    }

    private StrategyMetrics evaluate(List<RetrievalCase> selected, MetricKnowledgeService.RetrievalStrategy strategy) {
        return evaluate(selected, question -> knowledgeService.retrieve(question, EVALUATION_CANDIDATE_K, strategy).stream()
                .map(MetricKnowledgeService.Document::id).toList());
    }

    private StrategyMetrics evaluate(List<RetrievalCase> selected, java.util.function.Function<String, List<String>> retrieve) {
        double hitAt3 = 0;
        double recallAt5 = 0;
        double reciprocalRankAt5 = 0;
        for (RetrievalCase retrievalCase : selected) {
            Set<String> expected = retrievalCase.relevantDocumentIds().stream().map(topicResolver::topicOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<String> topFive = canonicalTopTopics(retrieve.apply(retrievalCase.question()).stream().map(topicResolver::topicOf).toList());
            Set<String> topThree = new HashSet<>(topFive.subList(0, Math.min(3, topFive.size())));
            topThree.retainAll(expected);
            if (!topThree.isEmpty()) hitAt3++;
            Set<String> recalled = new HashSet<>(topFive);
            recalled.retainAll(expected);
            recallAt5 += (double) recalled.size() / expected.size();
            for (int index = 0; index < topFive.size(); index++) {
                if (expected.contains(topFive.get(index))) {
                    reciprocalRankAt5 += 1.0 / (index + 1);
                    break;
                }
            }
        }
        int total = selected.size();
        return new StrategyMetrics(round(hitAt3 / total), round(recallAt5 / total), round(reciprocalRankAt5 / total));
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private StrategyAudit strategyAudit(List<String> candidates, List<String> expected) {
        int rank = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (expected.contains(candidates.get(index))) {
                rank = index + 1;
                break;
            }
        }
        return new StrategyAudit(candidates, rank > 0 && rank <= 3, rank == 0 ? null : rank);
    }

    private List<String> canonicalTopTopics(List<String> candidates) {
        return candidates.stream().distinct().limit(EVALUATION_TOP_K).toList();
    }

    private List<RetrievalCase> syntheticColloquialVariants(List<RetrievalCase> seeds) {
        List<String> prefixes = List.of("帮我看下，", "麻烦看下，", "我这边想问，", "运营上想确认下，");
        List<String> suffixes = List.of("", "，麻烦了", "，能看吗", "，别太绕", "，给个说法");
        List<RetrievalCase> variants = new java.util.ArrayList<>();
        for (RetrievalCase seed : seeds) for (int prefix = 0; prefix < prefixes.size(); prefix++)
            for (int suffix = 0; suffix < suffixes.size(); suffix++)
                variants.add(new RetrievalCase(seed.id() + "-synthetic-" + prefix + '-' + suffix,
                        prefixes.get(prefix) + seed.question() + suffixes.get(suffix), seed.relevantDocumentIds(), "COLLOQUIAL_SYNTHETIC"));
        return List.copyOf(variants);
    }

    private List<RetrievalCase> loadCases(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(CASES_RESOURCE).getInputStream()) {
            List<RetrievalCase> loaded = new java.util.ArrayList<>(objectMapper.readValue(input, new TypeReference<>() { }));
            try (var expandedInput = new ClassPathResource(EXPANDED_CASES_RESOURCE).getInputStream()) {
                loaded.addAll(objectMapper.readValue(expandedInput, new TypeReference<>() { }));
            }
            try (var colloquialInput = new ClassPathResource(COLLOQUIAL_HOLDOUT_RESOURCE).getInputStream()) {
                List<RetrievalCase> colloquial = objectMapper.readValue(colloquialInput, new TypeReference<>() { });
                loaded.addAll(colloquial);
                loaded.addAll(syntheticColloquialVariants(colloquial));
            }
            if (loaded.isEmpty()) throw new IllegalStateException("检索评测集不能为空");
            return List.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载检索评测集", exception);
        }
    }

    private EvaluationSplit splitOf(RetrievalCase retrievalCase) {
        if (retrievalCase.split() == null || retrievalCase.split().isBlank()) return EvaluationSplit.REGRESSION;
        return EvaluationSplit.valueOf(retrievalCase.split());
    }

    private List<RetrievalCase> selectedCases(EvaluationSplit split) {
        List<RetrievalCase> selected = cases.stream().filter(value -> split.includes(splitOf(value))).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("评测分组没有案例：" + split);
        return selected;
    }

    private void requireDenseCandidates() {
        if (denseRetriever == null || mixedRetriever == null) {
            throw new IllegalStateException("当前构造方式不支持 Dense/RRF 评测");
        }
    }

    public enum EvaluationSplit {
        ALL, DEVELOPMENT, HOLDOUT, COLLOQUIAL_HOLDOUT, COLLOQUIAL_SYNTHETIC, REGRESSION;
        boolean includes(EvaluationSplit candidate) { return this == ALL || this == candidate; }
    }

    public record RetrievalCase(String id, String question, List<String> relevantDocumentIds, String split) {
        public RetrievalCase {
            if (id == null || id.isBlank() || question == null || question.isBlank()
                    || relevantDocumentIds == null || relevantDocumentIds.isEmpty()) {
                throw new IllegalArgumentException("检索评测案例缺少必填字段");
            }
            relevantDocumentIds = List.copyOf(relevantDocumentIds);
        }
    }

    public record StrategyMetrics(double hitAt3, double recallAt5, double mrrAt5) { }
    public record CandidateReport(String strategy, EvaluationSplit split, int caseCount, StrategyMetrics metrics) { }
    public record RetrievalAuditReport(EvaluationSplit split, int caseCount, List<RetrievalAuditCase> cases) { }
    public record RetrievalAuditCase(String id, String question, List<String> expectedTopics,
                                     StrategyAudit bm25, StrategyAudit dense, StrategyAudit rrf) { }
    public record StrategyAudit(List<String> candidateTopics, boolean hitAt3, Integer firstRelevantRank) { }
    public record RetrievalReport(EvaluationSplit split, int caseCount,
                                  Map<MetricKnowledgeService.RetrievalStrategy, StrategyMetrics> strategies) { }
}
