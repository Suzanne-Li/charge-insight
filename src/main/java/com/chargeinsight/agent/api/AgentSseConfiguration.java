package com.chargeinsight.agent.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
public class AgentSseConfiguration {
    @Bean("agentSseExecutor")
    public TaskExecutor agentSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-sse-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setTaskDecorator(new SecurityContextTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}

/** Captures request authentication for SSE work and restores the pooled thread afterwards. */
final class SecurityContextTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable delegate) {
        SecurityContext captured = SecurityContextHolder.createEmptyContext();
        captured.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        return () -> {
            SecurityContext previous = SecurityContextHolder.getContext();
            try {
                SecurityContextHolder.setContext(captured);
                delegate.run();
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        };
    }
}
