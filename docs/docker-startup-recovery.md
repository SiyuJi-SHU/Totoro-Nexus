# Windows Docker Desktop 启动故障

## 2026-09-23 新目录复核

迁移时遗漏了本恢复脚本及调用入口。本次从 `Totoro Nexus.zip` 恢复 `scripts/ensure-docker-desktop.ps1`，并将本地启动、登录启动和演示启动入口接入同一检查，保留新目录路径和现有 ngrok 配置。登录入口现位于 `scripts/start-on-login.ps1`。

本次再次出现相同的 Ingest socket 错误。确认引擎未运行后，脚本将 `Docker/run` 与 `docker-secrets-engine` 两个仅含已知零字节 socket 的目录改名保留，再启动 Docker 成功。没有删除 socket 备份、镜像数据卷或应用数据。直接使用 Docker 自带图标或 `docker desktop start` 不会经过项目恢复脚本，仍可能复发。

以下保留早上恢复过程与安全边界。

2026-09-23，Docker Desktop 4.90.0 在初始化时失败，报 Windows 错误 1920（The file cannot be accessed by the system），涉及两处残留 AF_UNIX socket：

- `%LOCALAPPDATA%/Docker/run/sailor-ingest.sock`
- `%LOCALAPPDATA%/docker-secrets-engine/engine.sock`

项目旧记录 `Project/eval/reports/model_upgrade_qwen37_20260909.md` 已记录过同类恢复。这次故障发生在应用启动前；当时内存和磁盘空间充足，没有证据将本次故障归因于业务代码或内存耗尽。为何这台机器的 socket 会失效，尚未完全查明。

Docker 官方 GitHub 仓库有同版本的未关闭用户报告：[docker/for-win#15064](https://github.com/docker/for-win/issues/15064)。报告指出从删除 socket 改为重命名 socket 仍可能失败；重命名父目录可以绕开对失效子文件的访问。这是故障报告，并非上游已经确认根治。

本次在确认引擎未运行后，结束失败的 Desktop 进程，将上述两个临时目录一起改名保留，再启动 Desktop，恢复了原有容器。未恢复出厂设置、注销 WSL、删除卷或重建业务镜像。一次滞留的 `docker desktop stop` 曾在恢复后才执行完成，因此恢复前还需排除待执行的生命周期命令。

## 日常启动

双击 `启动平台.cmd` 或运行 `./scripts/start.ps1 -SkipBuild`。这两个入口现在调用 `scripts/ensure-docker-desktop.ps1`；`start-on-login.ps1` 也复用它，但本次未新增或修改 Windows 开机启动注册。

检查逻辑：

1. 引擎正常则直接返回，不重启正在运行的容器。
2. Desktop 正在启动时等待；仅在本轮日志出现上述明确错误、且引擎不可用时结束失败的 Desktop 进程。
3. 确认没有待执行的 Docker start/stop/restart 命令，检查两个目录只包含已知的零字节 socket，然后改名备份并重新启动一次。
4. 遇到未知文件、未知故障或恢复失败则停止，避免循环重启。备份目录保留在原父目录中，带 `-recovery-时间戳` 后缀。

也可单独运行 `./scripts/ensure-docker-desktop.ps1`。直接点击 Docker Desktop 自带图标不经过本项目的恢复检查，仍可能再次遇到上游故障。此措施是本地恢复机制，不是 Docker 缺陷的根治补丁。

## 验证

恢复后 Docker Engine 返回 29.7.2，应用 `/actuator/health` 返回 `UP`，9900 与受保护网关 9902 的构建指纹一致，仍为 `a494a7bdbbc69d009f56e9fc44cf94c7ae0bd88e865afc371a81d07b6e21e57a`。不为测试恢复脚本而主动制造崩溃。
