# OpenFrp 旧方案记录（已搁置）

当前使用 [ngrok 演示入口](ngrok-demo.md)。`scripts/start-platform.ps1` 和根目录 `启动平台.cmd` 已改为 ngrok，不再按下文启动 OpenFrp。以下仅保留历史排障记录。

## 当时状态（2026-09-22）

域名接入暂时搁置：xpdns 公开页的四个后缀均为 disabled，已登录页面也无法选择。认证成功不代表域名可申请。另已连通无需账号的 localhost.run 临时隧道，实测较慢，仍非正式方案，见 [免费外链试验](public-access-trial.md)。

OpenFrp 免费入口处于接入阶段；账号、节点、域名、可信证书和外网实测未完成前，不宣称公网可用。

浏览器 → OpenFrp HTTPS 节点 → 本机 OpenFrp 客户端 → Caddy（证书、入口隔离、压缩）→ Nginx（请求限流）→ 本地应用 9900。应用、知识库、数据库都留在本机，不存在两套数据同步。访客不需要安装客户端。

2026-09-22 本地验证：新网关登录页 200，业务接口未登录 401，Console/管理 API/健康诊断 403；CSS gzip 与条件请求 304 生效；登录限流返回 429；网关与应用构建指纹一致。以上只证明本机代理链路正常，未验证外网速度、可信公网证书、SSE 或附件全链路。

## 先完成账号和免费节点选择

1. 在 https://console.openfrp.net/ 登录自己的账号；注册、验证码与实名步骤由账号所有者完成。
2. 选择免费且明确支持 HTTPS 的节点，确认账户额度。大陆 HTTP/HTTPS 节点要求实名认证及已备案域名；没有备案域名时先试香港等非大陆节点。免费不代表固定性能。
3. 准备一个可控制 DNS 的域名。官方提供 [免费二级域名指引](https://docs.openfrp.net/use/other/free-domain)，其实际申请资格和可用性以服务端为准。
4. 创建 **HTTPS** 隧道，绑定该域名，本地地址为 **127.0.0.1**，端口 **8443**。客户端与网关共享 Docker 网络，所以这里不是 Windows 的 localhost。关闭 OpenFrp Auto TLS；Caddy 负责真正的 TLS 终止。
5. 域名 CNAME 指向该隧道详情里实际给出的节点地址。节点必须允许 TLS 原样转发，包括 TLS-ALPN-01 证书验证；否则需要改用 DNS 验证，不能靠跳过证书警告完成验收。

OpenFrp 自动生成的自签名证书不能作为面试官可直接访问的 HTTPS。这里使用 Caddy 申请公开信任的证书，首次申请依赖隧道及 DNS 已正确配置。

2026-09-22 账号内实查：免费账户显示 12 Mbps 上/下行、2 条隧道额度及 1 GiB 初始流量；香港 #29 节点可选 HTTPS。控制台链接的免费域名平台 xpdns.com 要求实名认证，未实名账号会被拒绝登录。实名认证页面显示当前无首次免费额度，一次认证费用为 2 元，失败或超时不退款，需本人微信扫码人脸核身。因此新账号采用此域名路径并非完全零费用；也可使用已有域名。免费节点不提供稳定性承诺，以上名义带宽不等于实测速度。

## 本机准备与启动

只验证本地网关（不需要账号，不开启公网）：

```powershell
./deploy/public-demo/share-demo.ps1 -Action Prepare
```

登录 OpenFrp 并创建隧道后，将 `deploy/public-demo/.env.example` 复制为同目录 `.env`，在本地填写访问密钥、隧道 ID、域名。不要把访问密钥发进聊天或提交 Git。`.env` 已被 Git 忽略。

```powershell
./scripts/start.ps1 -SkipBuild
./deploy/public-demo/share-demo.ps1 -Action Start
./deploy/public-demo/share-demo.ps1 -Action Status
```

上述命令用于手动启用历史 OpenFrp 方案；日常 `启动平台.cmd` 使用 ngrok。OpenFrp 脚本只有可信 HTTPS 登录页和构建指纹验证通过才把网址写回 README。验证暂未通过时保留服务以便检查 DNS 或证书日志，然后重试 Start。没有账号配置时不会重启旧入口。

入口使用独立 Compose 项目 `totoro-nexus-demo`，容器设置 `restart: unless-stopped`；已经正常启动且未被手动停止的容器会随 Docker 恢复。显式停止后，下次使用上述启动脚本恢复。应用冷启动期间外链可能暂不可用。

本地调试地址 `http://127.0.0.1:9902/login.html` 只监听回环地址。公网保留普通成员工作台；Console、管理 API、诊断端点仍仅供本地使用。

新入口的三个服务统一放在 `totoro-nexus-demo` 组：`openfrp`、`gateway`、`guard`。目前仅后两者运行，用于本地验证。保留原入口的共享限流：登录 5 次/分钟（突发 5）、任务接口 6 次/分钟（突发 3），以及 6 MiB 请求体上限；因此这是少量访客演示入口，多人密集试用可能收到 429。

## 镜像与实际验收

客户端已从控制台链接的官方静态资源站下载，版本 `OF_0.68.0_37f78258_260326`，并封装为本地镜像 `totoro-nexus-openfrp:0.68.0-37f78258`。已执行 `--version` 验证可以运行。网关使用固定摘要的官方 Caddy 镜像。

本机 DaoCloud 镜像源不允许下载 OpenFrp 镜像，Docker Hub 直连和官方备用镜像源也失败，因此采用官方二进制构建，不修改全局 Docker 网络。`prepare-demo-client.ps1` 固定下载地址、归档 SHA-256 和基础镜像摘要；校验值记录自此次 HTTPS 下载，用于后续复现，不是厂商签名。下载和解压产物位于被 Git 忽略的 `target/openfrp-client/`。

首次 Start 缺少镜像时会自动准备，也可提前运行：

```powershell
./deploy/public-demo/prepare-demo-client.ps1
```

当前仅待域名、隧道绑定、可信证书及公网验收。

用外部网络验收：首次打开和刷新、登录、Agent 切换、历史记录、来源弹窗、附件上传、SSE 实时进度和断线续传。对比本地与公网的页面/接口时间，并把模型生成时间单独记录。可信 HTTPS、完整操作链路、外部网络性能均通过后，才把网址作为面试正式入口。

停止公网但保留本地应用与数据：

```powershell
./deploy/public-demo/share-demo.ps1 -Action Stop
```

参考：[OpenFrp 官方文档](https://docs.openfrp.net/) · [网站配置要求](https://docs.openfrp.net/use/configuration/others) · [Docker 与 TLS 配置](https://docs.openfrp.net/use/frpc)
