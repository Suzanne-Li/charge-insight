package com.chargeinsight.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SecurityContextTaskDecoratorTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void propagatesAuthenticationAndRestoresWorkerContext() throws InterruptedException {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "HS256").subject("admin")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        AtomicReference<String> subjectInsideTask = new AtomicReference<>();
        AtomicReference<Authentication> authenticationAfterTask = new AtomicReference<>();
        Runnable decorated = new SecurityContextTaskDecorator().decorate(() -> {
            JwtAuthenticationToken authentication = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
            subjectInsideTask.set(authentication.getToken().getSubject());
        });
        SecurityContextHolder.clearContext();

        Thread worker = new Thread(() -> {
            decorated.run();
            authenticationAfterTask.set(SecurityContextHolder.getContext().getAuthentication());
        });
        worker.start();
        worker.join();

        assertThat(subjectInsideTask).hasValue("admin");
        assertThat(authenticationAfterTask.get()).isNull();
    }
}
