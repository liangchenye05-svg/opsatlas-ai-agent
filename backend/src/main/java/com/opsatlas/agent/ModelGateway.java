package com.opsatlas.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ModelGateway {
    private final ChatClient client;
    private final String model;
    private final boolean deepSeek;

    public ModelGateway(ObjectMapper json, @Value("${OPSATLAS_MODEL_API_KEY:}") String key,
                        @Value("${OPSATLAS_MODEL:deepseek-flash}") String model,
                        @Value("${OPSATLAS_MODEL_URL:https://api.deepseek.com}") String url,
                        @Value("${OPSATLAS_MODEL_TIMEOUT:12s}") Duration timeout) {
        this.model = model;
        this.deepSeek = "api.deepseek.com".equalsIgnoreCase(URI.create(url).getHost());
        if (key.isBlank()) {
            client = null;
            return;
        }
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        requestFactory.setReadTimeout(timeout);
        RestClient.Builder http = RestClient.builder().requestFactory(requestFactory);
        if (deepSeek) {
            http.requestInterceptor((request, body, execution) -> {
                // DeepSeek's default thinking mode rejects forced function choice.
                ObjectNode payload = (ObjectNode) json.readTree(body);
                payload.putObject("thinking").put("type", "disabled");
                byte[] encoded = json.writeValueAsBytes(payload);
                request.getHeaders().set(HttpHeaders.CONTENT_LENGTH, Integer.toString(encoded.length));
                return execution.execute(request, encoded);
            });
        }
        var api = OpenAiApi.builder().baseUrl(url).completionsPath("/chat/completions")
                .apiKey(key).restClientBuilder(http).build();
        var provider = OpenAiChatModel.builder().openAiApi(api)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        client = ChatClient.create(provider);
    }

    public boolean available() {
        return client != null;
    }

    public AssistantMessage complete(List<Message> messages, List<ToolCallback> tools, boolean required) {
        if (!available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "真实模型尚未配置，请设置 OpsAtlas 的 API Key");
        }
        var options = OpenAiChatOptions.builder().model(model).maxTokens(900)
                .internalToolExecutionEnabled(false).toolCallbacks(tools);
        if (!tools.isEmpty()) {
            options.toolChoice(required ? "required" : "auto");
            if (!deepSeek) {
                options.parallelToolCalls(false);
            }
        }
        try {
            var response = client.prompt(new Prompt(messages, options.build())).call().chatResponse();
            return response.getResult().getOutput();
        } catch (ResourceAccessException failure) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "模型连接超时或暂不可用");
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(ModelGateway.class).warn("model_failed type={}", failure.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型服务未能完成请求");
        }
    }
}
