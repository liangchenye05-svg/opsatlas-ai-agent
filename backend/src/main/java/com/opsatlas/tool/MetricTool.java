package com.opsatlas.tool;

import com.opsatlas.business.BusinessService;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class MetricTool {
    public static final String NAME = "get_store_metrics";
    public record Arguments(
            @ToolParam(required = false, description = "用户明确指定的数字门店编号") @Positive Long storeId,
            @ToolParam(required = false, description = "用户明确指定的数字区域编号") @Positive Long regionId,
            @ToolParam(required = false, description = "具体开始日期 YYYY-MM-DD，相对日期请省略") LocalDate from,
            @ToolParam(required = false, description = "具体结束日期 YYYY-MM-DD，相对日期请省略") LocalDate to) {}
    public record Scope(long userId, Long selectedStore) {}

    @Bean
    ToolCallback metricsCallback(BusinessService business, Validator validator) {
        return FunctionToolCallback.builder(NAME, (Arguments arguments, org.springframework.ai.chat.model.ToolContext context) -> {
            if (!validator.validate(arguments).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "门店和区域编号必须为正整数");
            }
            var scope = (Scope) context.getContext().get("scope");
            Long store = arguments.storeId();
            if (scope.selectedStore() != null) {
                if (store != null && !store.equals(scope.selectedStore())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "工具不能访问本次所选门店以外的数据");
                }
                store = scope.selectedStore();
            }
            return business.dashboard(scope.userId(), store, arguments.regionId(), arguments.from(), arguments.to());
        }).inputType(Arguments.class)
                .description("查询获授权门店的真实营业统计和前一等长周期。未明确给出数字编号时省略 storeId 和 regionId。分析上周时省略 from 和 to，由系统计算。只有用户给出具体年月日时才成对填写 YYYY-MM-DD。")
                .build();
    }
}
