package com.opsatlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsatlas.agent.AgentService;
import com.opsatlas.agent.ModelGateway;
import com.opsatlas.security.IdentityService;
import com.opsatlas.tool.MetricTool;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AgentIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired IdentityService identities;
    @Autowired List<ToolCallback> tools;
    @Autowired MockMvc mvc;
    @Value("${opsatlas.demo-password}") String demoPassword;

    @Test
    void springAiSendsToolSchemaAndUsesRealMysql() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        HttpServer server = fakeProvider("{}", requests, false);
        try {
            var result = agent(server, Duration.ofSeconds(3)).analyze(3, null, "分析上周退款", false, step -> {});
            assertThat(result.mode()).isEqualTo("model");
            assertThat(result.metrics().path("current").path("orders").asInt()).isEqualTo(42);
            assertThat(result.metrics().path("storeIds").get(0).asInt()).isEqualTo(1);
            assertThat(requests).hasSize(3);
            assertThat(result.citations().isArray()).isTrue();
            assertThat(json.readTree(requests.get(0)).path("tool_choice").asText()).isEqualTo("required");
            var metricSchema = json.readTree(requests.get(0)).path("tools").get(0).path("function").path("parameters");
            assertThat(metricSchema.path("properties").has("from")).isTrue();
            assertThat(metricSchema.path("required").size()).isZero();
            assertThat(requests.get(1)).contains("call-demo", "refundedOrders").doesNotContain("password");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsIsoDateArgumentsFromSpringAiTools() throws Exception {
        var period = java.time.LocalDate.now(com.opsatlas.business.BusinessService.ZONE)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        var args = json.createObjectNode().put("from", period.minusWeeks(1).toString())
                .put("to", period.minusDays(1).toString()).toString();
        HttpServer server = fakeProvider(args, new CopyOnWriteArrayList<>(), false);
        try {
            var result = agent(server, Duration.ofSeconds(3)).analyze(3, 1L, "核对指定周退款", false, step -> {});
            assertThat(result.metrics().path("current").path("orders").asInt()).isEqualTo(42);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deniesToolAccessAndRejectsInvalidArguments() throws Exception {
        for (Map.Entry<String, Integer> test : Map.of("{\"storeId\":3,\"userId\":1}", 403, "{\"storeId\":-1}", 400,
                "{\"from\":\"last_week\",\"to\":\"last_week\"}", 400).entrySet()) {
            HttpServer server = fakeProvider(test.getKey(), new CopyOnWriteArrayList<>(), false);
            try {
                assertThatThrownBy(() -> agent(server, Duration.ofSeconds(3))
                        .analyze(3, null, "尝试修改身份", false, step -> {}))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode().value()).isEqualTo(test.getValue()));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void modelTimeoutHasBoundedFailure() throws Exception {
        HttpServer server = fakeProvider("{}", new CopyOnWriteArrayList<>(), true);
        try {
            assertThatThrownBy(() -> agent(server, Duration.ofMillis(200))
                    .analyze(3, null, "分析上周", false, step -> {}))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getStatusCode().value()).isEqualTo(504);
                        assertThat(error.getReason()).doesNotContain("demo-provider-test");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsExplicitEvidenceModeAsSse() throws Exception {
        String token = identities.login("xuhui", demoPassword).accessToken();
        var first = mvc.perform(post("/api/agent/stream").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"question\":\"查看退款\",\"storeId\":1,\"local\":true}"))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).contains("text/event-stream");
        assertThat(response.getContentAsString()).contains("event:step", "event:done", "\"mode\":\"evidence\"", "\"orders\":42");
    }

    private AgentService agent(HttpServer server, Duration timeout) {
        var gateway = new ModelGateway(json, "demo-provider-test", "stub-protocol",
                "http://127.0.0.1:" + server.getAddress().getPort(), timeout);
        return new AgentService(gateway, identities, json, tools);
    }

    private HttpServer fakeProvider(String arguments, List<String> requests, boolean slow) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (slow) {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                boolean first = requests.size() == 1;
                boolean second = requests.size() == 2;
                Map<String, ?> message = first
                        ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call-demo", "type", "function",
                            "function", Map.of("name", MetricTool.NAME, "arguments", arguments))))
                        : second ? Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                            "id", "call-policy", "type", "function", "function", Map.of(
                            "name", "search_company_policy", "arguments", "{\"query\":\"退款质量投诉制度\"}"))))
                        : Map.of("role", "assistant", "content", "复核商品批次与售后登记。");
                byte[] data = json.writeValueAsBytes(Map.of("id", "mock-http-chat", "object", "chat.completion",
                        "created", 1000, "model", "stub-protocol", "choices", List.of(Map.of("index", 0,
                        "finish_reason", (first || second) ? "tool_calls" : "stop", "message", message)),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10, "total_tokens", 20)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
        });
        server.start();
        return server;
    }
}
