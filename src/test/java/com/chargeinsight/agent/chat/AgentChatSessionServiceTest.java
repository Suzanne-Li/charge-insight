package com.chargeinsight.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentChatSessionServiceTest {

    @Test
    void restoresContextBeforeAppendingCurrentUserMessage() {
        RecordingSessionService service = new RecordingSessionService();

        String contextualized = service.prepareUserTurn("session", "该区域上一轮情况如何？");

        assertThat(contextualized).isEqualTo("contextualized");
        assertThat(service.calls).containsExactly("contextualize", "append:USER");
    }

    private static final class RecordingSessionService extends AgentChatSessionService {
        private final List<String> calls = new ArrayList<>();

        private RecordingSessionService() {
            super(null, null);
        }

        @Override
        public String contextualize(String sessionId, String question) {
            calls.add("contextualize");
            return "contextualized";
        }

        @Override
        public void append(String sessionId, String role, String content, String traceId) {
            calls.add("append:" + role);
        }
    }
}
