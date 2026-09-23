# 2026-09-23 目录收束与切换记录

后续复核补充：旧目录归档中包含早上增加的 Docker 启动恢复脚本，初次迁移遗漏了该脚本与入口调用，现已补齐，见 [恢复记录](docker-startup-recovery.md)。业务 Java、JS、CSS、HTML 及相关测试共 206 个文件与旧最终版压缩包逐字节一致；新目录构建指纹与运行版本一致。下文是初次迁移时的检查快照。

用户确认旧目录已移入回收站后，按保留最新版本的要求，已移除 `agent-core`、旧 `totoro-nexus` 两组共 8 个停止的容器，以及 27 个旧应用镜像标签（26 个不同镜像，包含迁移前回退镜像）。主应用保留 `totoro-nexus-app:final`；当前配套服务、演示网关和其他项目保留。所有 Docker 数据卷均未删除，私有操作清单在 `runtime/migration/docker-cleanup-20260923.json`。两个旧目录 ZIP 继续保留。

本机唯一维护和运行目录已切换为 `Totoro-Nexus`。本次整理代码、部署入口和数据路径，保留最新已验收业务实现，没有重写 Agent、RAG、前台或 Console。

## 来源与保留范围

迁移前核对 `Project` 与 `Totoro Nexus` 两个目录，清单覆盖 4,854 个文件（不含 Git 内部文件）。以 `Totoro Nexus` 中最新已运行版本为基线，保留之后已经完成的附件、上下文、模型调用与耗时修复。业务 Java、JavaScript、CSS、HTML 与该基线按字节一致。

完整保留前台与 Console、按 Agent 选择模型、ReAct / Plan–Execute–Replan / Workflow、知识库与资料版本、RAG、附件、多轮会话、来源校验、SSE 事件及调用耗时、权限、调试与评测。必要测试、数据库迁移和仍被使用的兼容代码继续保留。

| 内容 | 最终位置或处置 |
|---|---|
| 当前业务代码与测试 | `src/` |
| 评测案例定义 | `eval/` |
| 构建、验收、本机启动辅助 | `scripts/` |
| 可选公网入口配置与脚本 | `deploy/public-demo/` |
| 架构、部署、验收说明 | `docs/` |
| 可公开的 OnCall / Odyssey 示例及来源许可 | `examples/knowledge/` |
| 原文、附件、资料版本和本地索引 | `runtime/uploads/` |
| Milvus / etcd / MinIO 数据 | `runtime/volumes/` |
| 数据库备份、迁移清单和测试原始记录 | `runtime/backups/`、`runtime/migration/`，Git 忽略 |
| 旧实验、截图、学习资料、历史输出和凭据 CSV | 留在旧目录，随用户的私有归档保留，不混入发布源码 |

原 Git 历史保留。相对原 Git 提交存在的业务代码差异主要来自此前已经完成的功能修复，不能误认为本次目录整理新增了这些逻辑。本次运行配置调整包括 `runtime/` 文件路径、显式数据库卷名、启动脚本位置和默认评测路径。

## 数据验证

当前 PostgreSQL 继续使用同一个 Docker 数据卷：`totoro-nexus-prod_platform-db-data`。没有新建第二套业务数据，也没有把旧库覆盖导入当前库。

迁移前停止应用与向量服务写入，导出一致性数据库备份，再复制文件。恢复服务后、实际模型测试前，对全部 19 张表逐表比较行数及排序后行 JSON 的 SHA-256，完全一致。

基线包含 4 个账号、6 个 Agent、31 个 Agent 版本、1,514 个会话、1,726 次运行、65 个文档、104 个文档版本、30 个附件、28 个评测集及 90 个评测任务。完整行数和摘要见私有 `runtime/migration/before-database.json` 与 `after-database-before-tests.json`。

文件与接口核对：

- 停写时复制上传目录 231 个文件、向量服务目录 195 个文件；426 个文件 SHA-256 全部一致。
- 104 个存储文档版本逐一按原始字节核对哈希，通过。
- 31 个附件核对存储大小及 HTTP 下载内容，通过；其中 30 个原附件，1 个本次测试附件。
- 6 个 Agent 配置和最近 21 次用户验收运行，迁移前后 API 内容一致。
- 旧 Project 数据库按独立 ID 比对的各表没有仅存在于旧库的记录；这是记录覆盖核对，不代表两个数据库逐字段相同。旧上传目录也没有当前目录缺少的独有文件内容。

两份私有备份：

| 文件 | 大小 | 用途 |
|---|---:|---|
| `runtime/backups/postgres-before-migration.dump` | 83,995,529 字节 | 当前数据库切换前快照 |
| `runtime/backups/postgres-old-project.dump` | 77,104,654 字节 | 旧 Project 数据库归档 |

两份备份均通过 `pg_restore --list` 检查，各 101 项；没有进行完整恢复演练。备份不是另一套运行中的数据库。只压缩旧源码文件夹不会包含 PostgreSQL 的 Docker 数据卷。

## 构建和运行验证

- Maven package：296 项测试，0 失败、0 错误、6 项跳过。
- Node 前端测试：24 项通过。
- PowerShell 语法、Compose 配置、启动脚本 ValidateOnly、Git 空白检查通过。
- app、PostgreSQL、Milvus、etcd、MinIO 健康。
- 前台、Console、登录页 HTTP 200；本地网关登录页 200，Console 和管理 API 403。
- 本地测试 JAR 与容器内 JAR SHA-256 一致。

构建 sourceHash：

```text
dc7debc5106948ef3b4e5de809462724f92d86997a4f963998135d960666bbbe
```

构建时间：`2026-09-23T01:08:31.5442353Z`。该指纹覆盖 `src/`（排除 build-info 本身）与 `pom.xml`，不代表文档和所有部署脚本的哈希。

五次真实模型请求均使用独立 debug 会话：

| 场景 | 结果 | 本次耗时 |
|---|---|---:|
| ReAct 知识库 TrafficAbsent 问答 | completed | 11.803 秒 |
| 同会话上传 Odyssey 附件并概括 | completed | 9.279 秒 |
| 同会话明确排除附件、返回知识库 | completed，未调用附件读取 | 9.524 秒 |
| Plan 知识库 TrafficAbsent 问答 | completed | 10.689 秒 |
| Workflow 分析模拟超时告警 | completed | 18.475 秒 |

测试新增 3 个 debug 会话、5 次运行及 1 个附件，所以测试后的总行数会高于迁移核对基线。未观察到上游响应停滞或结构化运行失败；这些是有限冒烟样例，不是完整重跑评测或性能承诺。

Workflow 有一项未经验证的推断被现有校验移除。原有答案质量边界继续见 [验收记录](acceptance.md)。本次没有重新全量点击所有 Console 操作，也没有验收长期公网链接。

## 本机入口与旧目录退役

当前入口为 `http://localhost:9900/`，Console 为 `http://localhost:9900/console.html`。本地受限网关是 `http://127.0.0.1:9902/`，不等于已开启公网隧道。

运行容器的挂载已核对：没有运行中的 Docker bind mount 指向两个旧目录。应用和网关配置来自新目录；容器内 `/app/uploads` 等路径保持不变，历史附件路径继续有效。Windows 当前用户的 `OnCallAgent` 登录启动项改为新目录的 `scripts/start-on-login.ps1`。

六个已停止的旧 UAT 容器已关闭自动重启，未删除容器或数据卷。保留迁移前镜像 `totoro-nexus-app:before-directory-migration-20260923`。本次未清理其他项目的 Docker 资源。

用户可以按以下顺序退役旧目录：

1. 只使用新目录启动、查看已有账号、Agent、历史对话和资料。
2. 压缩完整 `Project` 和 `Totoro Nexus`（包含隐藏配置和历史材料），私有保存，验证压缩包完整性并试解压。
3. 另外保存上述 SQL dump；它们目前位于新目录，不在两个旧目录压缩包里。持续备份新目录的私有配置、`runtime/uploads/` 与 `runtime/volumes/`。
4. 完成归档检查后再删除两个旧文件夹。不要删除新目录的 `runtime/`，不要删除当前 PostgreSQL 数据卷，不要运行 `docker compose down -v`。

本次没有删除旧目录，没有上传 GitHub，也没有启动新的公网隧道。GitHub 发布只包含源码、配置模板、文档、测试及可公开示例；真实 `.env`、账号和对话数据、附件、备份、日志和构建产物不进入仓库。不要把整个运行目录压缩上传 GitHub。
