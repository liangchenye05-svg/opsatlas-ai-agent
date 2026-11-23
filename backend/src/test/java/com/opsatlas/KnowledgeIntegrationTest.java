package com.opsatlas;

import com.opsatlas.rag.KnowledgeService;
import com.opsatlas.rag.PolicyVectorIndex;
import com.opsatlas.security.IdentityService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeIntegrationTest {
    @Autowired KnowledgeService knowledge;
    @Autowired PolicyVectorIndex vectors;
    @Autowired IdentityService identities;
    @Autowired MockMvc mvc;
    @Value("${opsatlas.demo-password}") String password;

    @Test
    void retrievesFromActualQdrantAndResolvesCitation() throws Exception {
        assertThat((Long) vectors.status().get("points")).isGreaterThanOrEqualTo(16);
        var found = knowledge.search("商品质量投诉需要登记购买凭证和商品批次", 4);
        assertThat(found.lexical()).isNotEmpty();
        assertThat(found.vector()).isNotEmpty();
        var citation = found.citations().get(0);
        assertThat(citation.source()).endsWith("refund-quality.md");
        mvc.perform(get("/api/knowledge/citations/" + citation.id()).header("Authorization", storeToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value(citation.content()));
        mvc.perform(get("/api/knowledge/answer").param("q", "火星天气和大气组成").header("Authorization", storeToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grounded").value(false));
    }

    @Test
    void verifiesUploadPermissionAndUtf8Parsing() throws Exception {
        var file = new MockMultipartFile("file","test-policy.txt","text/plain",
                "测试指南保存验收用的政策资料。".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/knowledge/documents").file(file).param("title", "验收测试制度")
              .header("Authorization", storeToken())).andExpect(status().isForbidden());
        String hq = "Bearer " + identities.login("hq", password).accessToken();
        mvc.perform(multipart("/api/knowledge/documents").file(file).param("title", "验收测试制度")
               .header("Authorization", hq)).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("upload/test-policy.txt"));
        assertThat(knowledge.search("测试指南保存验收政策资料", 4).citations())
                .anySatisfy(item -> assertThat(item.source()).isEqualTo("upload/test-policy.txt"));
    }

    private String storeToken() {
        return "Bearer " + identities.login("xuhui", password).accessToken();
    }
}
