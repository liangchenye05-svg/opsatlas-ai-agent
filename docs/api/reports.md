# 周报与审计 API

POST /api/agent/stream 依次返回步骤，完成事件返回 id、question、state、draft、failure、createdAt。draft 保存模式、经营指标、异常判断、建议、企业制度引用与步骤。失败的会话可在历史中查询。

GET /api/analysis/sessions 查看当前登录者的会话。POST /api/reports/{draftId}/confirm 必须传入 {"confirmed":true}，并且草稿属于当前账号。重复确认返回同一份报告。GET /api/reports 和 GET /api/audit 仅展示当前账号记录。

模型和 MCP 工具都无法确认或保存报告。应用启动时将上一次异常退出留下的 RUNNING 会话改为 FAILED，可重新发起分析。
