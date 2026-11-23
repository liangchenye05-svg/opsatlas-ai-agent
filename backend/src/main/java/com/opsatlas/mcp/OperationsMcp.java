package com.opsatlas.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsatlas.business.BusinessService;
import com.opsatlas.report.ReportService;
import com.opsatlas.security.IdentityService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class OperationsMcp {
    private static final String PRINCIPAL = "opsatlasPrincipal";
    private final BusinessService business;
    private final IdentityService identities;
    private final ReportService reports;

    public OperationsMcp(BusinessService business, IdentityService identities, ReportService reports) {
        this.business = business;
        this.identities = identities;
        this.reports = reports;
    }

    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(ObjectMapper json) {
        return WebMvcStatelessServerTransport.builder().jsonMapper(new JacksonMcpJsonMapper(json))
                .messageEndpoint("/mcp")
                .contextExtractor(request -> request.principal().filter(JwtAuthenticationToken.class::isInstance)
                        .map(JwtAuthenticationToken.class::cast)
                        .map(token -> McpTransportContext.create(Map.of(PRINCIPAL, identities.userId(token.getToken()))))
                        .orElse(McpTransportContext.EMPTY))
                .build();
    }

    @Bean
    List<SyncToolSpecification> readOnlyMcpTools(ObjectMapper json) {
        var tool = McpSchema.Tool.builder().name("get_opsatlas_metrics")
                .description("只读查询已授权门店上周经营指标和前一等长周期")
                .inputSchema(new JacksonMcpJsonMapper(json), """
                    {"type":"object","properties":{"storeId":{"type":"integer","minimum":1}},
                     "required":["storeId"],"additionalProperties":false}
                    """).build();
        return List.of(new SyncToolSpecification(tool, (context, request) -> {
            if (!(context.get(PRINCIPAL) instanceof Long userId)) {
                return McpSchema.CallToolResult.builder().isError(true).addTextContent("MCP 请求需要有效登录").build();
            }
            var arguments = request.arguments();
            var input = arguments.get("storeId");
            if (!arguments.keySet().equals(Set.of("storeId"))
                    || !(input instanceof Integer || input instanceof Long) || ((Number) input).longValue() <= 0) {
                return McpSchema.CallToolResult.builder().isError(true).addTextContent("门店编号必须为正整数").build();
            }
            long storeId = ((Number) input).longValue();
            try {
                var result = business.dashboard(userId, storeId, null, null, null);
                reports.audit(userId, "mcp_metrics", "store:" + storeId, "success");
                return McpSchema.CallToolResult.builder().structuredContent(json.valueToTree(result)).build();
            } catch (ResponseStatusException failure) {
                reports.audit(userId, "mcp_metrics", "store:" + storeId, "rejected");
                return McpSchema.CallToolResult.builder().isError(true).addTextContent(failure.getReason()).build();
            }
        }));
    }
}
