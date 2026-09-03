package com.chargeinsight.agent.api;

import com.chargeinsight.agent.runtime.AnalyticsAgentRuntime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Streams bounded workflow milestones; model token streaming is deliberately not exposed as unverified evidence. */
@RestController
@RequestMapping("/api/agent")
public class AnalyticsAgentStreamController {
    private final AnalyticsAgentRuntime runtime;
    private final TaskExecutor executor;

    public AnalyticsAgentStreamController(AnalyticsAgentRuntime runtime, @Qualifier("agentSseExecutor") TaskExecutor executor) {
        this.runtime = runtime;
        this.executor = executor;
    }

    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@Valid @RequestBody StreamRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                runtime.analyze(request.question(), request.sessionId(), event -> send(emitter, event));
                emitter.complete();
            } catch (RuntimeException exception) {
                send(emitter, new AnalyticsAgentRuntime.StreamEvent("failed",
                        Map.of("status", "FAILED", "message", "分析服务暂时不可用，请稍后重试")));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, AnalyticsAgentRuntime.StreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
        } catch (IOException exception) {
            throw new IllegalStateException("SSE 客户端连接已断开", exception);
        }
    }

    public record StreamRequest(@NotBlank String question, String sessionId) { }
}
