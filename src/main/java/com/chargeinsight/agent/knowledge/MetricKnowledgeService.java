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
            loadChunks("agent-knowledge/sql-examples.md"))
            .stream().flatMap(List::stream).toList();
    private final Map<String, Double> inverseDocumentFrequency = inverseDocumentFrequency();

    public List<Document> retrieve(String query, int topK) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }
        Map<String, Integer> queryTerms = termFrequency(query);
        return documents.stream()
                .map(document -> new Scored(document, hybridScore(queryTerms, document.content())))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(scored -> scored.document().source()))
                .limit(topK)
                .map(Scored::document)
                .toList();
    }

    public List<Document> retrieve(String query, KnowledgeType type, int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK 必须大于 0");
        Map<String, Integer> queryTerms = termFrequency(query);
        return documents.stream().filter(document -> document.type() == type)
                .map(document -> new Scored(document, hybridScore(queryTerms, document.content())))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(scored -> scored.document().source()))
                .limit(topK).map(Scored::document).toList();
    }

    private double hybridScore(Map<String, Integer> queryTerms, String content) {
        if (queryTerms.isEmpty()) {
            return 0;
        }
        Map<String, Integer> documentTerms = termFrequency(content);
        Set<String> overlap = new HashSet<>(queryTerms.keySet());
        overlap.retainAll(documentTerms.keySet());
        double keywordRecall = (double) overlap.size() / queryTerms.size();
        double lexicalVectorSimilarity = cosine(queryTerms, documentTerms);
        double bm25 = bm25(queryTerms.keySet(), documentTerms);
        double exactTermBonus = queryTerms.keySet().stream()
                .filter(term -> term.length() >= 3 && content.toLowerCase(Locale.ROOT).contains(term))
                .findAny().isPresent() ? 0.10 : 0;
        return 0.25 * keywordRecall + 0.35 * lexicalVectorSimilarity + 0.30 * bm25 + exactTermBonus;
    }

    private double bm25(Set<String> queryTerms, Map<String, Integer> documentTerms) {
        double score = 0;
        double length = Math.max(1, documentTerms.values().stream().mapToInt(Integer::intValue).sum());
        double averageLength = Math.max(1, documents.stream().mapToInt(document ->
                termFrequency(document.content()).values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1));
        for (String term : queryTerms) {
            int tf = documentTerms.getOrDefault(term, 0);
            if (tf == 0) continue;
            long df = documents.stream().filter(document -> termFrequency(document.content()).containsKey(term)).count();
            double idf = Math.log(1 + (documents.size() - df + 0.5) / (df + 0.5));
            score += idf * (tf * 2.5) / (tf + 1.5 * (0.25 + 0.75 * length / averageLength));
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
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (Document document : documents) {
            for (String term : termFrequency(document.content()).keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        Map<String, Double> idf = new HashMap<>();
        documentFrequency.forEach((term, frequency) ->
                idf.put(term, Math.log((double) (documents.size() + 1) / (frequency + 1)) + 1));
        return Map.copyOf(idf);
    }

    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> terms = new HashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            terms.merge(matcher.group(), 1, Integer::sum);
        }
        return terms;
    }

    private List<Document> loadChunks(String path) {
        try (var inputStream = new ClassPathResource(path).getInputStream()) {
            String markdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            String[] chunks = markdown.lines().filter(line -> line.startsWith("# ")).count() > 1
                    ? markdown.split("(?m)(?=^# )")
                    : markdown.split("\\R\\s*\\R");
            List<Document> result = new ArrayList<>();
            for (String chunk : chunks) {
                if (chunk.isBlank()) continue;
                result.add(new Document(path + "#chunk-" + result.size(), chunk.trim(), type(path)));
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load agent knowledge: " + path, exception);
        }
    }
    private KnowledgeType type(String path) {
        if (path.contains("schema")) return KnowledgeType.SCHEMA;
        if (path.contains("sql-examples")) return KnowledgeType.SQL_EXAMPLE;
        if (path.contains("anomaly")) return KnowledgeType.ANALYSIS_RULE;
        return KnowledgeType.METRIC;
    }

    public enum KnowledgeType { METRIC, SCHEMA, SQL_EXAMPLE, ANALYSIS_RULE }
    public record Document(String source, String content, KnowledgeType type) { }
    private record Scored(Document document, double score) { }
}
