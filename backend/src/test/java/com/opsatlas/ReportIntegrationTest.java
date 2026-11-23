package com.opsatlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsatlas.report.ReportService;
import com.opsatlas.security.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired ObjectMapper json;
    @Autowired ReportService reports;
    @Value("${opsatlas.demo-password}") String password;

    @Test
    void confirmsOnlyOwnedDraftExactlyOnceAndAudits() throws Exception {
        String xuhui = token("xuhui");
        var request = mvc.perform(post("/api/agent/stream").header("Authorization", xuhui)
                .contentType("application/json").content("{\"question\":\"退款质量投诉异常及售后规则\",\"storeId\":1,\"local\":true}"))
                .andExpect(request().asyncStarted()).andReturn();
        String result = mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var frame = json.readTree(result.lines().filter(line -> line.startsWith("data:")).reduce((a,b) -> b).orElseThrow().substring(5));
        String id = frame.get("id").asText();
        assertThat(frame.path("draft").path("anomaly").asText()).isEqualTo("REFUND_RATE_RISE");
        assertThat(frame.path("draft").path("citations").size()).isPositive();

        String path = "/api/reports/" + id + "/confirm";
        mvc.perform(post(path).header("Authorization", xuhui).contentType("application/json")
                   .content("{\"confirmed\":false}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization", token("tianhe")).contentType("application/json")
                   .content("{\"confirmed\":true}")).andExpect(status().isNotFound());
        String body = mvc.perform(post(path).header("Authorization", xuhui).contentType("application/json")
                   .content("{\"confirmed\":true}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post(path).header("Authorization", xuhui).contentType("application/json")
                   .content("{\"confirmed\":true}")).andExpect(status().isOk())
                   .andExpect(jsonPath("$.id").value(json.readTree(body).get("id").asText()));
        assertThat(reports.auditRows(3)).filteredOn(row -> row.target().equals(id) && row.actionCode().equals("report_confirmed"))
                .hasSize(1);
    }

    @Test
    void interruptedAnalysisIsVisibleAndCannotBeConfirmed() throws Exception {
        String pending = reports.begin(3, "验收中断任务");
        reports.recover();
        assertThat(reports.sessions(3)).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(pending);
            assertThat(item.state()).isEqualTo("FAILED");
            assertThat(item.draft()).isNull();
        });
        mvc.perform(post("/api/reports/" + pending + "/confirm").header("Authorization", token("xuhui"))
                .contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isConflict());
    }

    @Test
    void rejectsUnauthorizedMcpBeforeToolInvocation() throws Exception {
        mvc.perform(post("/mcp").contentType("application/json").accept("application/json","text/event-stream")
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String token(String username) {
        return "Bearer " + identities.login(username, password).accessToken();
    }
}
