# Totoro Nexus

一个可配置的知识 Agent 与运维分析工作台。Console 管配置、资料和评测，前台用于对话；同一套权限、证据、预算和运行记录支持 ReAct、Plan–Execute–Replan、Workflow 三种执行模式。

这是个人项目与面试演示，不是经过生产规模验证的企业服务。Workflow 只读分析用户提供的告警/日志，不会执行生产修复命令。

<!-- public-demo-status:start -->
面试演示 URL：[https://totoronexus.tail8c1da2.ts.net/](https://totoronexus.tail8c1da2.ts.net/)

此地址已在 2026-09-22 13:00 +08:00 验证可返回项目登录页。需要项目普通成员账号，账号密码由项目所有者单独提供；不要使用或公开管理员账号。在线状态取决于本机、Docker、项目及 Tailscale 网络连接。
<!-- public-demo-status:end -->

## 启动

需要 Docker Compose、JDK 17 或兼容 JDK、Maven；测试前端另需 Node.js，运行验收脚本另需 Python 3。模型通过阿里云百炼调用，实际可用模型与权限以自己的账号为准。

```powershell
Copy-Item .env.example .env
# 编辑 .env：填写自己的模型凭据和数据库密码，然后：
./start.ps1
```

工作台 `http://localhost:9900/`，本机 Console `http://localhost:9900/console.html`。初始管理员为 `admin`，自动生成的密码存于 `uploads/.platform/admin-initial-password.txt`；请登录后修改。源码不包含演示站的账户、知识库、聊天记录或密钥。首次安装后在 Console 上传资料并配置 Agent。

已有镜像启动：`./start.ps1 -SkipBuild`。当前电脑开启既有公网入口：`./start-demo.ps1`。新电脑的公网设备身份需要自行配置，见 [部署与恢复](docs/deployment.md)。

## 工程结构

```text
src/main/java/       后端、运行控制、检索与证据校验
src/main/resources/ 数据库迁移、前端和冻结的模拟场景
src/test/           保护行为的 Java / JavaScript 测试
scripts/            构建指纹与可重复验收
deploy/public-demo/ 公网反向代理与 Tailscale 配置
eval/               评测案例定义（不含真实运行结果）
docs/               架构、部署、验收边界
```

`uploads/`、`volumes/` 和 PostgreSQL 卷是私有运行数据；`target/` 是构建输出。它们都不进入 Git。测试源码属于工程的一部分，临时截图、浏览器登录态和旧版本副本不属于发布源码。

## 能力与限制

- Agent 版本固定于会话，配置包括任务说明、执行模式、知识库范围、模型和预算。意图判断决定回答依据，不替用户切换模式。
- RAG 支持向量、BM25、混合召回、精排、命中附近的原文窗口和分页读文；引用绑定实际保存的资料版本。
- 最近有效对话用于追问；不是无限记忆，也没有自动总结所有更早对话。详见 [执行模式](docs/execution-modes.md)。
- 前台通过 SSE 展示进度与耗时；知识回答完成结构化提交和校验后再显示全文。SSE 不等于所有答案逐字输出。
- 工具耗时、模型总耗时、可观测的上游首包和用量分开记录。嵌套检索与并行 Worker 的耗时不能相加当作总耗时。
- 引用校验检查来源/原文对应关系，不能证明每句推断都正确。模型仍可能答非所问、遗漏限定条件、拒答或超时。
- 模型调用、评测和真实对话消耗外部 API 额度；本地有限验收不代表并发性能、企业 SLA 或任意问题零幻觉。

## 验证

```powershell
mvn test
node --test src/test/js/*.test.js scripts/workbench-contract.test.cjs
python scripts/verify_runtime_replay.py --base http://localhost:9900
python scripts/accept_conversation_context.py --base http://localhost:9900
```

后两条会实际调用模型并写入调试记录。每次发布先运行 `scripts/write-build-info.ps1`；部署版本以 `/build-info.json` 的指纹为准。旧版本的评测成绩保留为历史，不冒充当前构建成绩。验收结果和未解决限制见 [收尾验收](docs/acceptance.md)。

保留原项目的 Apache-2.0 许可证。第三方依赖、知识库内容和模型服务分别适用其自身授权；运行时上传的资料不随本仓库发布。

当前目录为最终维护与演示环境，旧 `Project` 保留作默认停止的 UAT（9901）。干净源码包位于 `target/totoro-nexus-source.zip`，只包含 Git 跟踪文件，不包含运行数据或凭据。不要直接压缩整个运行目录上传 GitHub。
