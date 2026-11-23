package com.opsatlas.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsatlas.security.IdentityService;
import com.opsatlas.tool.MetricTool;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceTest {
    @Test
    void reportsBusinessToolFailureWithoutExposingItsDetails() {
        var identities = mock(IdentityService.class);
        var tool = mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name(MetricTool.NAME)
                .description("经营指标").inputSchema("{\"type\":\"object\"}").build());
        when(tool.call(eq("{}"), any(ToolContext.class))).thenThrow(new IllegalStateException("internal storage error"));
        var agent = new AgentService(mock(ModelGateway.class), identities, new ObjectMapper(), List.of(tool));

        assertThatThrownBy(() -> agent.analyze(3, 1L, "分析退款", true, step -> {}))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(error.getReason()).isEqualTo("业务工具执行失败");
                });
        verify(identities).storeIds(3, 1L, null);
    }
}
