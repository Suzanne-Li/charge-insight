package com.chargeinsight.agent.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class MetricKnowledgeService {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-zA-Z0-9_]+");
    private final List<Document> documents = List.of(
            loadChunks("agent-knowledge/metric-definitions.md"),
            loadChunks("agent-knowledge/anomaly-playbook.md"),
            loadChunks("agent-knowledge/schema.md"),
            loadChunks("agent-knowledge/sql-examples.md"),
            loadChunks("agent-knowledge/metric-catalog.md"),
            loadChunks("agent-knowledge/time-and-scope.md"),
            loadChunks("agent-knowledge/view-field-dictionary.md"),
            loadChunks("agent-knowledge/tool-contracts.md"),
            loadChunks("agent-knowledge/query-governance.md"))
            .stream().flatMap(List::stream).toList();
    private final Map<String, Map<String, Integer>> documentTerms = documentTerms();
    private final Map<String, Integer> documentLengths = documentLengths();
    private final Map<String, Integer> documentFrequency = documentFrequency();
    private final double averageDocumentLength = Math.max(1, documentLengths.values().stream()
            .mapToInt(Integer::intValue).average().orElse(1));
    private final Map<String, Double> inverseDocumentFrequency = inverseDocumentFrequency();

    public List<Document> retrieve(String query, int topK) {
        return retrieve(query, topK, RetrievalStrategy.LEXICAL_FUSION);
    }

    /**
     * Exposes deterministic retrieval strategies for offline evaluation. Production planning continues
     * to use {@link RetrievalStrategy#LEXICAL_FUSION} through {@link #retrieve(String, int)}.
     */
    public List<Document> retrieve(String query, int topK, RetrievalStrategy strategy) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("检索策略不能为空");
        }
        Map<String, Integer> queryTerms = termFrequency(normalizeQuery(query));
        return documents.stream()
                .map(document -> new Scored(document, score(queryTerms, document, strategy)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(scored -> scored.document().source()))
                .limit(topK)
                .map(Scored::document)
                .toList();
    }

    public List<Document> retrieve(String query, KnowledgeType type, int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK 必须大于 0");
        Map<String, Integer> queryTerms = termFrequency(normalizeQuery(query));
        return documents.stream().filter(document -> document.type() == type)
                .map(document -> new Scored(document, lexicalFusionScore(queryTerms, document)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(scored -> scored.document().source()))
                .limit(topK).map(Scored::document).toList();
    }

    private double score(Map<String, Integer> queryTerms, Document document, RetrievalStrategy strategy) {
        Map<String, Integer> terms = documentTerms.get(document.id());
        return switch (strategy) {
            case KEYWORD -> keywordRecall(queryTerms, terms);
            case TF_IDF -> cosine(queryTerms, terms);
            case BM25 -> bm25(queryTerms.keySet(), document.id(), terms);
            case LEXICAL_FUSION -> lexicalFusionScore(queryTerms, document);
        };
    }

    private double lexicalFusionScore(Map<String, Integer> queryTerms, Document document) {
        if (queryTerms.isEmpty()) {
            return 0;
        }
        Map<String, Integer> terms = documentTerms.get(document.id());
        double keywordRecall = keywordRecall(queryTerms, terms);
        double lexicalVectorSimilarity = cosine(queryTerms, terms);
        double bm25 = bm25(queryTerms.keySet(), document.id(), terms);
        double exactTermBonus = queryTerms.keySet().stream()
                .filter(term -> term.length() >= 3 && document.content().toLowerCase(Locale.ROOT).contains(term))
                .findAny().isPresent() ? 0.10 : 0;
        return 0.25 * keywordRecall + 0.35 * lexicalVectorSimilarity + 0.30 * bm25 + exactTermBonus;
    }

    private double keywordRecall(Map<String, Integer> queryTerms, Map<String, Integer> documentTerms) {
        if (queryTerms.isEmpty()) return 0;
        Set<String> overlap = new HashSet<>(queryTerms.keySet());
        overlap.retainAll(documentTerms.keySet());
        return (double) overlap.size() / queryTerms.size();
    }

    private double bm25(Set<String> queryTerms, String documentId, Map<String, Integer> terms) {
        double score = 0;
        double length = documentLengths.get(documentId);
        for (String term : queryTerms) {
            int tf = terms.getOrDefault(term, 0);
            if (tf == 0) continue;
            long df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (documents.size() - df + 0.5) / (df + 0.5));
            score += idf * (tf * 2.5) / (tf + 1.5 * (0.25 + 0.75 * length / averageDocumentLength));
        }
        return score / (score + 1.0);
    }

    private double cosine(Map<String, Integer> left, Map<String, Integer> right) {
        double dotProduct = 0;
        for (Map.Entry<String, Integer> entry : left.entrySet()) {
            double idf = inverseDocumentFrequency.getOrDefault(entry.getKey(), 1.0);
            dotProduct += entry.getValue() * idf * right.getOrDefault(entry.getKey(), 0) * idf;
        }
        return dotProduct == 0 ? 0 : dotProduct / (norm(left) * norm(right));
    }

    private double norm(Map<String, Integer> terms) {
        double sum = 0;
        for (Map.Entry<String, Integer> entry : terms.entrySet()) {
            double weightedTermFrequency = entry.getValue() * inverseDocumentFrequency.getOrDefault(entry.getKey(), 1.0);
            sum += weightedTermFrequency * weightedTermFrequency;
        }
        return Math.sqrt(sum);
    }

    private Map<String, Double> inverseDocumentFrequency() {
        Map<String, Double> idf = new HashMap<>();
        documentFrequency.forEach((term, frequency) ->
                idf.put(term, Math.log((double) (documents.size() + 1) / (frequency + 1)) + 1));
        return Map.copyOf(idf);
    }

    private Map<String, Map<String, Integer>> documentTerms() {
        Map<String, Map<String, Integer>> terms = new HashMap<>();
        for (Document document : documents) {
            terms.put(document.id(), Map.copyOf(termFrequency(document.content())));
        }
        return Map.copyOf(terms);
    }

    private Map<String, Integer> documentLengths() {
        Map<String, Integer> lengths = new HashMap<>();
        documentTerms.forEach((id, terms) -> lengths.put(id,
                Math.max(1, terms.values().stream().mapToInt(Integer::intValue).sum())));
        return Map.copyOf(lengths);
    }

    private Map<String, Integer> documentFrequency() {
        Map<String, Integer> frequencies = new HashMap<>();
        documentTerms.values().forEach(terms -> terms.keySet()
                .forEach(term -> frequencies.merge(term, 1, Integer::sum)));
        return Map.copyOf(frequencies);
    }

    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> terms = new HashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            terms.merge(matcher.group(), 1, Integer::sum);
        }
        return terms;
    }

    /** Maps stable business colloquialisms to existing governed knowledge terminology. */
    public static String normalizeQuery(String query) {
        if (query == null) return "";
        StringBuilder expanded = new StringBuilder(query);
        if (query.contains("没啥单") || query.contains("没单")) expanded.append(" 订单量下降");
        if (query.contains("掉线")) expanded.append(" 离线率");
        if (query.contains("不能用")) expanded.append(" 可用桩");
        if (query.contains("啥毛病") || query.contains("坏得很厉害")) expanded.append(" 故障明细 故障分析");
        if (query.contains("别的地方的数据")) expanded.append(" 区域权限");
        if (query.contains("数据怎么对起来")) expanded.append(" 视图关联");
        if (query.contains("固定报表答不了") || query.contains("自由查")) expanded.append(" 受控动态查询");
        if (query.contains("多少钱") || query.contains("少这么多")) expanded.append(" GMV GMV下降");
        return expanded.toString();
    }

    private List<Document> loadChunks(String path) {
        try (var inputStream = new ClassPathResource(path).getInputStream()) {
            String markdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            String[] chunks = markdown.lines().filter(line -> line.startsWith("# ")).count() > 1
                    ? markdown.split("(?m)(?=^# )")
                    : markdown.split("\\R\\s*\\R");
            List<Document> result = new ArrayList<>();
            for (String chunk : chunks) {
                if (chunk.isBlank() || (chunk.trim().startsWith("# ") && !chunk.contains("\n"))) continue;
                result.add(new Document(stableId(path, chunk, result.size()), path + "#chunk-" + result.size(), chunk.trim(), type(path)));
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load agent knowledge: " + path, exception);
        }
    }
    private KnowledgeType type(String path) {
        if (path.contains("schema") || path.contains("field-dictionary")) return KnowledgeType.SCHEMA;
        if (path.contains("sql-examples")) return KnowledgeType.SQL_EXAMPLE;
        if (path.contains("anomaly") || path.contains("tool-contracts") || path.contains("governance")) return KnowledgeType.ANALYSIS_RULE;
        return KnowledgeType.METRIC;
    }

    private String stableId(String path, String chunk, int index) {
        Matcher explicitId = Pattern.compile("(?m)^<!-- knowledge-id: ([a-z0-9._-]+) -->$").matcher(chunk);
        if (explicitId.find()) return explicitId.group(1);
        if (path.contains("metric-definitions")) {
            if (chunk.contains("可用桩：`")) return "metric.available-pile-count";
            if (chunk.contains("日均可用桩：")) return "metric.average-available-pile-count";
            if (chunk.contains("离线率：")) return "metric.offline-rate";
            if (chunk.contains("共享成功率：")) return "metric.share-success-rate";
            if (chunk.contains("GMV：")) return "metric.gmv-amount";
            if (chunk.contains("充电量：")) return "metric.energy-kwh";
            if (chunk.contains("桩主收入：")) return "metric.owner-income";
            if (chunk.contains("平台服务费：")) return "metric.platform-fee";
        }
        if (path.contains("anomaly-playbook")) {
            if (chunk.contains("先比较本期与上期 GMV")) return "rule.gmv-period-comparison";
            if (chunk.contains("通信故障码")) return "rule.communication-fault-impact";
            if (chunk.contains("结论必须带时间范围")) return "rule.evidence-boundary";
        }
        if (path.contains("schema")) {
            if (chunk.startsWith("`v_daily_group_operation` 是桩群日运营视图")) return "schema.daily-group-operation";
            if (chunk.startsWith("`v_daily_fault_analysis` 是桩群日故障视图")) return "schema.daily-fault-analysis";
            if (chunk.startsWith("两个视图通过")) return "schema.join-and-access-scope";
        }
        if (path.contains("sql-examples")) {
            if (chunk.contains("区域周期运营总览")) return "sql.operation-overview";
            if (chunk.contains("通信故障桩群排行")) return "sql.communication-fault-ranking";
            if (chunk.contains("GMV 异常分析")) return "sql.gmv-anomaly";
        }
        return "knowledge." + type(path).name().toLowerCase(Locale.ROOT) + "." + index;
    }

    /** Exposes the loaded corpus size and identities to deterministic offline evaluation only. */
    public List<Document> documents() {
        return documents;
    }

    public enum KnowledgeType { METRIC, SCHEMA, SQL_EXAMPLE, ANALYSIS_RULE }
    /** All strategies here are lexical; mixed retrieval is implemented separately after dense evaluation. */
    public enum RetrievalStrategy { KEYWORD, TF_IDF, BM25, LEXICAL_FUSION }
    public record Document(String id, String source, String content, KnowledgeType type) { }
    private record Scored(Document document, double score) { }
}
