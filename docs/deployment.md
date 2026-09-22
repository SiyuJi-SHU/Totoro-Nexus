# 部署、数据与恢复

## 环境边界

`Totoro Nexus` 是最终维护工程与演示部署目录，Compose 项目 `totoro-nexus-prod`，本机端口 9900。这里的 prod 只是部署命名，不代表生产认证。`PRD` 通常指需求文档，生产环境简称 `PROD`，验收环境简称 `UAT`。

旧 `Project/agent-core` 留作 UAT 与回退资料，独立 Compose 项目和独立数据。迁移后 UAT 使用 9901，默认停止；不要同时运行两套未经隔离的 9900 应用。公网入口只代理最终部署的 9900。

应用、PostgreSQL、Milvus、etcd、MinIO 是必要运行服务。Attu 为可选管理界面：`docker compose --profile admin up -d attu`。新工程只向宿主机回环地址开放应用和可选管理端口，数据库和向量服务通过 Docker 内部网络通信。

## 三类数据必须一起保护

1. PostgreSQL：账户、六个现有 Agent 及其历史版本、资料目录、会话、评测和运行事件。
2. `uploads/`：文档不可变副本、附件、本地检索索引与私有启动资料。
3. `volumes/`：Milvus / etcd / MinIO 数据。三者一起保留，不能只复制 Milvus 目录。

`.env` 是私有运行配置。源码压缩包不包含这些数据，新安装不会自动拥有当前演示站内容。迁移通过完整数据库备份与运行目录快照保留现有身份、配置和引用。

## 备份

先确认没有进行中的对话、上传、评测，停止应用写入，再备份数据库。停止向量相关服务后复制 `uploads/` 与 `volumes/`；复制运行中的存储目录不能当作一致性备份。

```powershell
docker compose stop app
docker compose exec -T platform-db pg_dump -U agent_platform -d agent_platform -Fc -f /tmp/platform.dump
docker compose cp platform-db:/tmp/platform.dump ./backups/platform.dump
docker compose stop standalone etcd minio
# 使用备份软件复制 uploads/、volumes/ 和私有 .env 至仓库外的受保护目录。
docker compose up -d --wait
```

执行前创建 `backups/`，备份完成后移除容器内临时 dump。备份含用户数据和密钥，只能保存在受保护位置，不能上传 GitHub。

## 恢复

先在独立目录和 Compose 项目演练，停止应用，恢复相应的 `uploads/` 与整套 `volumes/`，启动数据库，使用 `pg_restore --clean --if-exists --no-owner -U agent_platform -d agent_platform` 恢复 dump，再启动其余服务。`--clean` 会覆盖目标数据库，必须确认目标是恢复环境。

恢复成功的标准不是容器变绿：还要核对 Agent ID/版本、文档数量，打开旧会话及引用，实际执行一次有来源的问答，并检查 `/build-info.json`。保留旧环境直到这些检查通过。

## 公网入口

`deploy/public-demo/compose.yaml` 使用独立项目 `totoro-nexus` 与外部卷 `totoro-nexus_tailscale-state`。当前电脑保留这个身份卷，就能继续使用原域名。新电脑首次需要创建身份卷、完成 Tailscale 登录并获准开启 Funnel；源码不包含设备授权。

```powershell
docker volume create totoro-nexus_tailscale-state
./share-demo.ps1 -Action Start
```

公网只开放前台，Console、管理 API 和监控端点由代理阻止访问。使用普通成员账号，凭据单独交付；不要在 README 中写密码。电脑必须保持开机、联网且不休眠。

## 更新与回退

先通过测试，再生成构建信息、Maven 打包、构建镜像。确认没有活动任务后重建应用，避免用户提交被重启打断。应用启动会把之前未完成的任务标为 interrupted。

同一数据库迁移版本内可回退到已保存镜像；涉及数据库模式变化必须核对 Flyway 兼容性。不要把 `docker compose down -v`、全局 `docker system prune --volumes` 当作日常清理。旧 Agent / 文档版本可能被历史记录引用，不按版本号大小删除。
