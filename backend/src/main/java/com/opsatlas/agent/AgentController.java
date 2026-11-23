package com.opsatlas.agent;

import com.opsatlas.security.IdentityService;
import com.opsatlas.report.ReportService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AgentController {
    record Request(@NotBlank @Size(max = 1200) String question, @Positive Long storeId, boolean local) {}

    private final IdentityService identities;
    private final ModelGateway model;
    private final AgentService agent;
    private final ReportService reports;
    private final ExecutorService workers = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));

    public AgentController(IdentityService identities, ModelGateway model, AgentService agent, ReportService reports) {
        this.identities = identities;
        this.model = model;
        this.agent = agent;
        this.reports = reports;
    }

    @GetMapping("/api/agent/status")
    public Map<String, Boolean> status() {
        return Map.of("modelConfigured", model.available());
    }

    @PostMapping(path = "/api/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
        long userId = identities.actor(identities.userId(jwt)).id();
        var emitter = new SseEmitter(65_000L);
        try {
            var future = workers.submit(() -> {
                String id = null;
                try {
                    id = reports.begin(userId, request.question());
                    String analysisId = id;
                    var result = agent.analyze(userId, request.storeId(), request.question(), request.local(), step -> {
                        reports.audit(userId, step.stage(), analysisId, "success");
                        send(emitter, "step", step);
                    });
                    send(emitter, "done", reports.ready(userId, id, result));
                    emitter.complete();
                } catch (ResponseStatusException failure) {
                    if (id != null) {
                        reports.failed(userId, id, failure.getReason());
                    }
                    send(emitter, "failure", Map.of("status", failure.getStatusCode().value(), "message", failure.getReason()));
                    emitter.complete();
                } catch (UncheckedIOException failure) {
                    if (id != null) {
                        reports.failed(userId, id, "分析连接已断开");
                    }
                    emitter.completeWithError(failure);
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(AgentController.class).warn("analysis_failed type={}", failure.getClass().getSimpleName());
                    if (id != null) {
                        reports.failed(userId, id, "分析运行失败");
                    }
                    send(emitter, "failure", Map.of("status", 500, "message", "分析运行失败，请重新发起"));
                    emitter.complete();
                }
            });
            emitter.onTimeout(() -> {
                future.cancel(true);
                emitter.complete();
            });
        } catch (RejectedExecutionException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "分析任务较多，请稍后重试");
        }
        return emitter;
    }

    private void send(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @PreDestroy
    void close() {
        workers.shutdownNow();
    }
}
