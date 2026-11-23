package com.opsatlas.tool;

import com.opsatlas.rag.KnowledgeService;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class PolicyTool {
    public static final String NAME = "search_company_policy";
    public record Query(@NotBlank @Size(max=400) String query) {}

    @Bean
    ToolCallback policyCallback(KnowledgeService knowledge, Validator validator) {
        return FunctionToolCallback.builder(NAME, (Query query) -> {
            if (!validator.validate(query).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "制度检索问题为空或超过 400 字");
            }
            return knowledge.search(query.query(), 4).citations();
        }).inputType(Query.class).description("查找与售后、退款、复盘等相关的企业制度片段。返回可核对的原文编号，无相关资料时返回空数组。").build();
    }
}
