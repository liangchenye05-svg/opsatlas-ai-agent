# OpsAtlas 隔离演示

使用 Flyway 合成数据库与项目内置制度，选择徐汇店，查看上周退款率和前一周对比。进入 Agent 后提交以下问题，选择已配置的官方 DeepSeek 模型。

> 分析徐汇门店上周退款异常情况，与前一周比较，结合企业售后制度生成经营周报。

展开工具事件，回查制度原文，预览并确认保存周报。前往周报与审计复核。演示账号 xuhui、east 和 hq 来自项目初始化。密码保存在本机配置，由项目生成。

在根目录准备 JAR、dist 和依赖后，用独立 Compose 项目 opsatlas-showcase 复现，使用端口 8192、13393、16343 及专属卷。

```powershell
. .\deploy\init-local.ps1
$env:OPSATLAS_PORT = '8192'
$env:OPSATLAS_DB_PORT = '13393'
$env:OPSATLAS_QDRANT_PORT = '16343'
docker compose -p opsatlas-showcase --env-file .runtime/local.env -f deploy/compose.yaml up -d --build --wait
node docs/showcase/capture.cjs
```

当前截图的 Agent 为官方 deepseek-flash、mode=model。未配置密钥时可显式以 --evidence 运行，脚本会标识本地证据模式。该脚本在隔离演示库写入一份经确认的周报，本地证据 JSON 单独存档。请先归档现有截图，再用同名路径重新截取。

- [经营总览](screenshots/desktop/01-overview.jpg) · [趋势](screenshots/desktop/02-trend.jpg)
- [Agent](screenshots/desktop/03-agent.jpg) · [工具步骤](screenshots/desktop/04-tools.jpg) · [RAG 回查](screenshots/desktop/05-citation.jpg)
- [周报确认](screenshots/desktop/06-report-confirmation.jpg) · [知识库](screenshots/desktop/07-knowledge.jpg) · [审计](screenshots/desktop/08-audit.jpg)
- [手机工作台](screenshots/mobile/01-agent.jpg) · [素材来源](assets-license.md)
