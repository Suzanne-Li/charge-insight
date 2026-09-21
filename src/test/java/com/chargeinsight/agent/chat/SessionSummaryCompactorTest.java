package com.chargeinsight.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionSummaryCompactorTest {
    private final SessionSummaryCompactor compactor = new SessionSummaryCompactor();

    @Test
    void compactsOnlyMessagesOutsideTheConfiguredSlidingWindow() {
        List<AgentChatSessionService.Message> messages = List.of(
                message(1, "USER", "华东 GMV 怎么样"), message(2, "ASSISTANT", "GMV 已核验"),
                message(3, "USER", "再看订单"), message(4, "ASSISTANT", "订单已核验"),
                message(5, "USER", "最近一周呢"), message(6, "ASSISTANT", "请明确范围"));

        assertThat(compactor.shouldCompact(messages.size(), 5)).isTrue();
        List<AgentChatSessionService.Message> compacted = compactor.messagesToCompact(messages, 2);
        var summary = compactor.compact(compacted, session());

        assertThat(compacted).extracting(AgentChatSessionService.Message::id).containsExactly(1L, 2L, 3L, 4L);
        assertThat(summary.coveredThroughMessageId()).isEqualTo(4L);
        assertThat(summary.confirmedScope().region()).isEqualTo("华东");
        assertThat(summary.userRequests()).containsExactly("华东 GMV 怎么样", "再看订单");
        assertThat(summary.verifiedConclusions()).containsExactly("GMV 已核验", "订单已核验");
        assertThat(summary.sourceHash()).hasSize(64);
    }

    @Test
    void doesNotCompactAtOrBelowTheConfiguredThreshold() {
        assertThat(compactor.shouldCompact(5, 5)).isFalse();
        assertThat(compactor.shouldCompact(6, 5)).isTrue();
    }

    private AgentChatSessionService.Message message(long id, String role, String content) {
        return new AgentChatSessionService.Message(id, role, content, null, Instant.EPOCH);
    }

    private AgentChatSessionService.SessionSummary session() {
        return new AgentChatSessionService.SessionSummary("01234567890123456789012345678901", "test", "华东", "上海",
                "桩群1", "最近一周", Instant.EPOCH, Instant.EPOCH);
    }
}
