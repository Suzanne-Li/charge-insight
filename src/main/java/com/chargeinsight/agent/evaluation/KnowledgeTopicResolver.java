package com.chargeinsight.agent.evaluation;

import java.util.Map;

/**
 * Maps equivalent document variants onto the same labelled knowledge topic for offline retrieval
 * evaluation. It prevents a more specific, newer source from being counted as a miss solely
 * because an older overview document was labelled first.
 */
final class KnowledgeTopicResolver {
    private static final Map<String, String> EQUIVALENT_TOPICS = Map.ofEntries(
            Map.entry("metric.catalog.available-pile-count", "metric.available-pile-count"),
            Map.entry("metric.catalog.average-available-pile-count", "metric.average-available-pile-count"),
            Map.entry("metric.catalog.energy-kwh", "metric.energy-kwh"),
            Map.entry("metric.catalog.gmv-amount", "metric.gmv-amount"),
            Map.entry("metric.catalog.share-success-rate", "metric.share-success-rate"),
            Map.entry("metric.catalog.offline-rate", "metric.offline-rate"),
            Map.entry("schema.operation.dimensions-time-region", "schema.daily-group-operation"),
            Map.entry("schema.operation.dimensions-group", "schema.daily-group-operation"),
            Map.entry("schema.operation.device-state-fields", "schema.daily-group-operation"),
            Map.entry("schema.operation.order-fields", "schema.daily-group-operation"),
            Map.entry("schema.operation.business-fields", "schema.daily-group-operation"),
            Map.entry("schema.operation.ratio-fields", "schema.daily-group-operation"),
            Map.entry("schema.fault.dimensions-time-region", "schema.daily-fault-analysis"),
            Map.entry("schema.fault.dimensions-group-vendor", "schema.daily-fault-analysis"),
            Map.entry("schema.fault.type-code-fields", "schema.daily-fault-analysis"),
            Map.entry("schema.fault.impact-fields", "schema.daily-fault-analysis"),
            Map.entry("schema.cross-view.join-keys", "schema.join-and-access-scope"),
            Map.entry("tool.operation-overview", "sql.operation-overview"),
            Map.entry("tool.fault-ranking", "sql.communication-fault-ranking"),
            Map.entry("tool.anomaly-comparison", "sql.gmv-anomaly"));

    String topicOf(String documentId) {
        return EQUIVALENT_TOPICS.getOrDefault(documentId, documentId);
    }
}
