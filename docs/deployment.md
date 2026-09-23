# 部署、数据与恢复

## 一个主工程

`Totoro-Nexus` 是当前唯一维护目录。工作台端口 9900，Compose 项目名固定为 `totoro-nexus-prod`。该命名不代表生产规模认证。两个旧目录只作迁移前备份，不应再从旧目录启动相同项目。

启动方式见根 README。Docker 构建仅接收预构建 JAR；数据库和向量服务走内部网络。Attu 按需使用 `docker compose --profile admin up -d attu`。

## 根目录文件各自做什么

这些文件共同组成一份项目，文件修改时间较早不代表它是旧版副本。

| 文件或目录 | 用途 | 日常需要操作吗 |
|---|---|---|
| `README.md` | 项目首页和使用入口 | 看这里即可 |
| `启动平台.cmd` | 唯一双击入口，调用 `scripts/start-platform.ps1`，启动本地项目和已配置的外链 | 日常使用或演示时双击 |
| `scripts/start.ps1` | 检查 Docker、构建或启动应用；双击入口选择跳过构建 | 通常不用手动运行 |
| `scripts/start-platform.ps1` | 统一组织本地启动、打开页面、准备网关和 ngrok；外链失败保留本地使用 | 通常不用手动运行 |
| `.env` | 本机真实 API Key、数据库密码等配置 | 私有，不上传 GitHub，不覆盖 |
| `.env.example` | 没有真实凭据的配置样板，供新安装填写 | 新安装时参考 |
| `pom.xml` | Maven 的 Java 依赖、编译和测试设置 | 开发构建使用 |
| `Dockerfile` | 把已编译 JAR 和 Java 运行环境制作成应用镜像 | 构建使用 |
| `docker-compose.yml` | 定义应用、PostgreSQL、Milvus、etcd、MinIO 的启动及数据挂载 | 启动脚本使用 |
| `.dockerignore` | 限制 Docker 构建输入，当前只允许 Dockerfile 和应用 JAR | 自动生效 |
| `.gitignore` | 排除密钥、运行数据、备份和构建产物，避免进入 Git | 自动生效 |
| `LICENSE` | 源码的开源许可条款 | 发布时保留 |
| `src/` | 后端代码、前台/Console 页面、SQL 结构迁移和必要测试 | 核心源码 |
| `scripts/` | Docker 恢复、开机启动、构建指纹和验收辅助 | 按需使用 |
| `deploy/` | 公网网关、隧道的部署配置 | 外链入口使用 |
| `eval/` | 评测案例文件；实际评测任务和结果在数据库 | 属于质量验证材料 |
| `examples/` | 可公开导入的示例资料与来源许可 | 新安装或演示时参考 |
| `docs/` | 架构、部署、验收、历史迁移说明 | 需要细节时查阅 |
| `runtime/` | 当前原文、附件、索引、向量服务文件、SQL 备份及日志 | 私有运行数据，保留 |
| `backups/` | 用户归档的两个旧目录 ZIP | 私有历史备份，不参与运行 |
| `target/` | Maven 编译与测试产物，包括可执行 JAR | 可由构建重新生成 |
| `.git/` | Git 的版本历史和本地仓库元数据 | 不手动修改 |

`.cmd` 与 `.ps1` 是入口和实现的关系，不是两套平台。`pom.xml`、`Dockerfile`、Compose 分别负责编译程序、制作镜像、启动整套服务；`.env.example` 与 `.env` 分别是模板和本机实际值。

## 新机器首次安装

源码构建需要 Docker Compose、JDK 17 和 Maven。Node.js 用于前端测试，Python 3 用于验收脚本。以下步骤仅用于没有既有 `.env` 和业务数据的新安装：

```powershell
Copy-Item .env.example .env
# 编辑 .env，填写自己的百炼凭据和数据库密码。
./scripts/start.ps1
```

新安装初始管理员为 `admin`，生成的密码在 `runtime/uploads/.platform/admin-initial-password.txt`；登录后修改。已有安装继续使用原账号，不能覆盖原 `.env` 或随意更改数据卷名称。

当前 Windows 本机使用 `启动平台.cmd` 即可，等价于 `./scripts/start-platform.ps1`：沿用现有最终镜像启动本地项目，并自动连接已配置的外链。仅需本地服务的开发命令仍为 `./scripts/start.ps1 -SkipBuild`。从 Maven 直接运行时，上传目录默认 `runtime/uploads`，评测目录默认 `eval`，数据库和 Milvus 连接需由环境变量配置。

仅克隆 GitHub 源码不会获得当前六个 Agent、账号或对话。全新安装在 Console 创建配置并导入示例；迁移原有业务需同时恢复数据库、文件和私有配置。

## 开发验证

```powershell
mvn test
node --test src/test/js/*.test.js scripts/workbench-contract.test.cjs
python scripts/verify_runtime_replay.py --base http://localhost:9900
```

最后一个脚本会执行真实验收并可能消耗模型 API 额度，不属于日常启动。已有测试结果及范围见 [验收记录](acceptance.md)。

## 数据位置

| 数据 | 位置 |
|---|---|
| 账号、Agent 与文档版本、对话、事件、评测 | Docker 卷 `totoro-nexus-prod_platform-db-data` |
| 原文、会话附件、不可变版本、本地索引 | `runtime/uploads/`，容器挂载为 `/app/uploads` |
| Milvus / etcd / MinIO | `runtime/volumes/` |
| 评测案例文件 | `eval/`，只读挂载到 `/app/eval` |
| 私有配置 | 根 `.env` 及可选入口的 `.env*` |

PostgreSQL 数据卷由 `PLATFORM_DB_VOLUME` 指定，默认沿用既有名称。首次启动新机器会创建数据库；同机迁移保持此名称和原密码。不要把目录改名等同于数据库复制或隔离。

## 一致性备份

先确认没有进行中的对话、上传或评测，再停止应用写入和向量服务：

```powershell
docker compose stop app standalone etcd minio
New-Item -ItemType Directory -Force runtime/backups
# PostgreSQL 服务保留运行，pg_dump 生成一致性快照。
docker compose exec -T platform-db pg_dump -U agent_platform -d agent_platform -Fc -f /tmp/platform.dump
docker compose cp platform-db:/tmp/platform.dump ./runtime/backups/platform.dump
```

此时复制 `runtime/uploads/`、整套 `runtime/volumes/` 和私有配置至离线备份，再恢复服务。不要用旧版 PowerShell 的文本重定向保存二进制 dump。备份完成后用 pg_restore --list 检查，并保留校验哈希。不要上传私有备份。

恢复数据库需在独立目标环境停应用后导入，恢复配套文件再启动，核对账号、Agent、历史会话、附件和来源。目录压缩包不包含 Docker 数据卷；只备份 SQL 也不完整。未经目标确认不要执行覆盖恢复或 `docker compose down -v`。

## 更新与回退

测试通过后运行 `scripts/write-build-info.ps1`，Maven package，构建 app 镜像。确认无活动任务后更新 app；`/build-info.json` 应与构建文件一致。目录迁移没有修改数据库 schema，不需要重复导入现有数据。

按用户保留最新版本的要求，旧应用镜像已于 2026-09-23 清理，当前仅保留 `totoro-nexus-app:final`。需要旧代码时从私有压缩归档重新构建；新运行产生数据后，不能直接切回旧目录的过期文件快照。应先停写并恢复/同步同一时间点的数据。详细迁移记录见 `migration-2026-09-23.md`。

Windows 启动入口会先运行 `scripts/ensure-docker-desktop.ps1`。它仅处理已知的 Desktop 遗留 socket 故障，保留临时目录副本，不重置 Docker 数据；说明见 [Docker 启动恢复](docker-startup-recovery.md)。

## 公网入口

本次不重新选择公网供应商。已有网关配置及辅助脚本统一位于 `deploy/public-demo/`，可用 `./deploy/public-demo/share-demo.ps1 -Action Prepare` 准备本地 9902 入口，不会开启公网隧道。

已有 OpenFrp、ngrok 与临时入口各自的接入状态见对应文档。公网验收必须另行核对可信 HTTPS、管理路由隔离、普通账号、SSE、附件和外网速度，不能以本机登录页 200 代替。
