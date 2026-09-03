package com.chargeinsight.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Guards the user-facing browser shell against regressions in its safety-critical interactions. */
class FrontendShellContractTest {

    @Test
    void keepsLoginBlankAndUsesControlledClientInteractions() throws IOException {
        String page;
        try (var input = new ClassPathResource("static/index.html").getInputStream()) {
            page = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(page)
                .contains("<input id=\"username\" autocomplete=\"username\" required>")
                .contains("<input id=\"password\" type=\"password\" autocomplete=\"current-password\" required>")
                .doesNotContain("id=\"username\" value=")
                .contains("function confirmRemove(session)")
                .contains("/api/agent/sessions/${session.serverSessionId}")
                .contains("options=['华东','华南','华北','华中','西南','西北','东北','*']")
                .contains("d.dataset?.fields?.length&&d.dataset?.rows?.length")
                .containsOnlyOnce("function auth()")
                .containsOnlyOnce("function message(")
                .containsOnlyOnce("function renderSessions()")
                .containsOnlyOnce("function card(")
                .containsOnlyOnce("function drawer(")
                .containsOnlyOnce("function showAdmin()")
                .doesNotContain("MutationObserver")
                .doesNotContain("confirm(`");
    }
}
