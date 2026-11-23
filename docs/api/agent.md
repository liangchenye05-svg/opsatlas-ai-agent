# 经营分析接口

GET /api/agent/status 返回模型配置是否就绪。POST /api/agent/stream 请求带已验证 JWT 与 JSON question、可选 storeId。local 为 true 时运行数据证据预览，不发模型请求。SSE 有 step、done、failure 三类事件，失败携带状态码和短的用户提示。

模型通过 Spring AI ChatClient 选择 get_store_metrics，服务端通过 ToolContext 传递当前账号和所选门店。模型无法传递可信账号。工具仅查询授权经营指标，最多三次工具调用。前端呈现确定的指标字段，模型建议中的数字主张通过过滤。

[Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
