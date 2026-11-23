# MCP

OpsAtlas 通过 Spring AI 的无状态 Streamable HTTP 服务在 /mcp 提供只读 get_opsatlas_metrics 工具。客户端须传入 Authorization Bearer，并接受 application/json 和 text/event-stream。

安全过滤器验证 JWT。WebMVC 从验证后的认证主体提取账号编号，放入 MCP 传输上下文。工具只接收门店编号，交由同一 BusinessService 执行业务权限校验，并记录允许或拒绝的访问。

MCP 不自动导出内部 ToolCallback。报告确认接口仍独立于工具服务。 [Spring AI Stateless WebMVC](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-stateless-server-boot-starter-docs.html)
