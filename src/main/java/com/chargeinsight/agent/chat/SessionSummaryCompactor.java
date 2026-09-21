package com.chargeinsight.agent.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Creates a bounded, deterministic summary of completed chat turns. It never receives model
 * reasoning: only persisted USER/ASSISTANT messages and the already-validated session scope.
 */
public final class SessionSummaryCompactor {
    private static final int MAX_ITEMS_PER_ROLE = 3;
    private static final int MAX_ITEM_LENGTH = 240;

    public boolean shouldCompact(int messageCount, int triggerMessageCount) {
        return messageCount > Math.max(2, triggerMessageCount);
    }

    public List<AgentChatSessionService.Message> messagesToCompact(List<AgentChatSessionService.Message> messages,
                                                                    int windowMessageLimit) {
        int endExclusive = Math.max(0, messages.size() - Math.max(2, windowMessageLimit));
        return messages.subList(0, endExclusive);
    }

    public StructuredSummary compact(List<AgentChatSessionService.Message> messages,
                                     AgentChatSessionService.SessionSummary session) {
        if (messages.isEmpty()) throw new IllegalArgumentException("没有可压缩的会话消息");
        List<String> requests = messages.stream().filter(message -> "USER".equals(message.role()))
                .map(message -> abbreviate(message.content())).filter(value -> !value.isBlank())
                .limit(MAX_ITEMS_PER_ROLE).toList();
        List<String> conclusions = messages.stream().filter(message -> "ASSISTANT".equals(message.role()))
                .map(message -> abbreviate(message.content())).filter(value -> !value.isBlank())
                .limit(MAX_ITEMS_PER_ROLE).toList();
        long coveredThroughMessageId = messages.get(messages.size() - 1).id();
        String sourceHash = hash(messages, session, coveredThroughMessageId);
        return new StructuredSummary(coveredThroughMessageId, sourceHash,
                new ConfirmedScope(session.region(), session.city(), session.group(), session.timeRange()),
                requests, conclusions, List.of());
    }

    private String abbreviate(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_ITEM_LENGTH ? normalized : normalized.substring(0, MAX_ITEM_LENGTH) + "…";
    }

    private String hash(List<AgentChatSessionService.Message> messages, AgentChatSessionService.SessionSummary session,
                        long coveredThroughMessageId) {
        StringBuilder value = new StringBuilder(session.sessionId()).append('|').append(coveredThroughMessageId);
        value.append('|').append(nullSafe(session.region())).append('|').append(nullSafe(session.city()))
                .append('|').append(nullSafe(session.group())).append('|').append(nullSafe(session.timeRange()));
        messages.forEach(message -> value.append('|').append(message.id()).append(':').append(message.role())
                .append(':').append(nullSafe(message.content())));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String nullSafe(String value) { return value == null ? "" : value; }

    public record StructuredSummary(long coveredThroughMessageId, String sourceHash, ConfirmedScope confirmedScope,
                                    List<String> userRequests, List<String> verifiedConclusions,
                                    List<String> pendingItems) { }
    public record ConfirmedScope(String region, String city, String group, String timeRange) { }
}
