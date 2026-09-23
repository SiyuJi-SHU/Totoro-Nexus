# ngrok 免费演示入口

当前入口：[Totoro Nexus](https://elective-cash-revival.ngrok-free.dev/)。2026-09-23 已完成本机网络下的公网 HTTPS、登录、附件、SSE 回答与历史读取验证；尚不能据此保证不同地区访客的速度。运行项目与以下脚本均位于最终目录 `D:\Simon\downloads\SimonWiki\Totoro-Nexus`。

## 接入

1. 在 [ngrok 控制台](https://dashboard.ngrok.com/) 登录免费账号；注册、验证码由本人完成。不需要购买域名或付费套餐。
2. 官方 Windows 客户端放在 `runtime/tools/ngrok-client/ngrok.exe`，启动脚本检查 Authenticode 签名。下载来源为 [ngrok 官方下载页](https://ngrok.com/download/windows)。
3. 在本地终端运行 `./deploy/public-demo/share-ngrok.ps1 -Action Configure`，按提示粘贴控制台提供的 **Authtoken**（不是 API Key）。输入隐藏，保存到被 Git 忽略的 `deploy/public-demo/.env.ngrok`，不要发进聊天或提交 Git。
4. 日常双击根目录的 `启动平台.cmd`，或执行 `./scripts/start-platform.ps1`：检查本地服务、准备防护网关、后台启动 ngrok，并验证公网和本地构建指纹一致。已运行的正确隧道会被复用，不重复启动。需要仅启动、不打开浏览器时加 `-NoBrowser`。
5. 用 `./deploy/public-demo/share-ngrok.ps1 -Action Status` 查询公网网址。后台日志位于被 Git 忽略的 `runtime/logs/ngrok.stdout.log` 和 `ngrok.stderr.log`。启动命令结束后后台隧道继续运行；电脑重启后需再次双击同一个启动入口。

手动调试可执行 `./deploy/public-demo/share-ngrok.ps1 -Action Start`，这是前台模式，Ctrl+C 停止隧道。`-Action Background` 仅启动后台隧道，要求本地服务和网关已就绪。演示入口不再使用 OpenFrp。

本地代理检查接口只监听 `127.0.0.1:9903`，HTTP 请求内容检查关闭。公网只转发至 `127.0.0.1:9902` 的网关。按用户要求，完整应用（含 Console 及管理接口）均可经外链访问，登录、ADMIN/MEMBER 角色授权和 CSRF 校验由应用原有机制执行；网关保留登录及运行限流。Console 仍需管理员账号，普通成员权限不变。外链与本地共用同一套数据，Console 的修改会直接生效。仅打开 Docker Desktop 不保证 ngrok 自动启动，当前未注册新的开机任务。

## 2026-09-23 实测

- 普通浏览器首次显示 ngrok 的 Visit Site 页面，点击后能进入项目登录页。这不是证书错误，也不代表项目异常。
- 独立 HTTPS 请求约 1.2–2.1 秒；另一次新连接 0.53 秒，同一连接后续请求 0.16、0.29 秒。样本很少，仅代表当时本机网络，不是浏览器完整加载耗时或异地 SLA。
- 公网登录成功。一个独立验收会话上传约 120 字节的合成文本附件，模型正确返回其中的校验码；任务请求发起后 2.68 秒收到首个 SSE 事件，5.10 秒收到最终答案和 done。最终状态 completed，历史可读取。未据此推断大附件耗时。
- SSE 响应为 `text/event-stream`，无压缩；事件分批到达。后续按用户要求移除了网关的页面与管理路径封锁：公网 Console 页面返回 200；管理接口未登录返回 401，管理员登录后可读取。应用原有权限与 CSRF 检查继续生效。
- 公网构建与本地一致：`dc7debc5106948ef3b4e5de809462724f92d86997a4f963998135d960666bbbe`。
- 本地验收记录：`runtime/tmp/ngrok-acceptance-20260923.json`。验收会话与小附件保留供 Console 检查，本次未批量清理数据。

尚待验证：手机关闭 Wi-Fi 后通过流量访问、真实面试账号操作体验、长时间在线稳定性。成员账号可使用工作台，Console 需要管理员角色。

## 免费档边界与验收

官方免费档提供一个账号绑定的固定开发域名、自动 HTTPS；每月 1 GB 出站流量和 2 万 HTTP 请求。普通浏览器首次访问有提示页，用户点击 Visit 后，同域名 7 天内免提示。不要将命令行加跳过提示头的测速结果冒充普通访客首次体验。

验收范围：可信 HTTPS、登录页和构建指纹、应用角色权限、首次及复用连接耗时、普通浏览器提示页、登录、Console、历史、附件和 SSE。主要速度验收应使用手机流量或另一网络；服务商域名、名义带宽或本机单次结果都不能保证面试官体验。

来源：[免费档限制](https://ngrok.com/docs/pricing-limits/free-plan-limits) · [客户端配置](https://ngrok.com/docs/agent/config/v3)
