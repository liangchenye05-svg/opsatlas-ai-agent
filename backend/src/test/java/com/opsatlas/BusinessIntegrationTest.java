package com.opsatlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsatlas.business.BusinessService;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BusinessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @Autowired BusinessService business;
    @Value("${opsatlas.demo-password}") String demoPassword;

    @Test
    void authenticatesAndRejectsBadCredentials() throws Exception {
        mvc.perform(get("/api/stores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stores").header("Authorization", "Bearer broken")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", "xuhui", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", token("xuhui")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STORE"));
    }

    @Test
    void scopesEveryRoleAndDeniesDirectStoreId() throws Exception {
        mvc.perform(get("/api/stores").header("Authorization", token("hq")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        String east = token("east");
        mvc.perform(get("/api/stores").header("Authorization", east))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/dashboard").param("regionId", "2").header("Authorization", east))
                .andExpect(status().isForbidden());
        String store = token("xuhui");
        mvc.perform(get("/api/stores").header("Authorization", store))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(1));
        for (String path : new String[] {"/api/dashboard", "/api/orders", "/api/refunds"}) {
            mvc.perform(get(path).param("storeId", "3").header("Authorization", store))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void computesStatsAndMatchesIndependentSql() throws Exception {
        String token = token("xuhui");
        var dashboard = business.dashboard(3, 1L, null, null, null);
        mvc.perform(get("/api/dashboard").header("Authorization", token)
                .param("from", dashboard.period().from().toString()).param("to", dashboard.period().to().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.orders").value(dashboard.current().orders()))
                .andExpect(jsonPath("$.trend.length()").value(7));
        LocalDate from = dashboard.period().from();
        LocalDate end = dashboard.period().to().plusDays(1);
        long orders = database.queryForObject("SELECT COUNT(*) FROM retail_order WHERE store_id=1 AND ordered_at>=? AND ordered_at<?",
                Long.class, from, end);
        long refunds = database.queryForObject("""
                SELECT COUNT(DISTINCT o.id) FROM retail_order o JOIN retail_refund r ON r.order_id=o.id
                WHERE o.store_id=1 AND o.ordered_at>=? AND o.ordered_at<? AND r.refunded_at<? AND r.status='APPROVED'
                """, Long.class, from, end, end);
        assertThat(dashboard.current().orders()).isEqualTo(orders).isEqualTo(42);
        assertThat(dashboard.current().refundedOrders()).isEqualTo(refunds).isEqualTo(7);
        assertThat(dashboard.previous().refundedOrders()).isEqualTo(1);
        assertThat(dashboard.current().refundRate()).isEqualByComparingTo("16.67");
        assertThat(dashboard.previous().refundRate()).isEqualByComparingTo("2.38");
        mvc.perform(get("/api/orders").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(42));
        mvc.perform(get("/api/refunds").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    void validatesDatesAndPaging() throws Exception {
        String token = token("xuhui");
        mvc.perform(get("/api/dashboard").param("from", "2026-01-01").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("page", "-1").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    String token(String username) throws Exception {
        String result = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username", username, "password", demoPassword))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(result).get("accessToken").asText();
    }
}
