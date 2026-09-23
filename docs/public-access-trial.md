# 临时外链历史试验（已停用）

当前使用 [ngrok 演示入口](ngrok-demo.md)，通过根目录 `启动平台.cmd` 启动。下文保留 2026-09-22 的试验和当时判断，不是当前接入说明。

OpenFrp 的本地准备已完成，但 xpdns 免费后缀在公开页面全部标为禁用，用户在已登录页面也无法选择。实名认证不等于获得域名；不应继续要求用户为这个不确定的入口付费。OpenFrp 域名接入目前搁置。

## 已试通 localhost.run

无需账号、域名或额外客户端，使用 Windows 自带 OpenSSH，将 HTTPS 公网请求转到已有的受保护网关 9902，再进入本地应用 9900。应用与数据保持本地同一份。

```powershell
# 本地应用需已经运行；没有公网 .env 配置时可准备本地网关：
./deploy/public-demo/share-demo.ps1 -Action Prepare
# 前台运行，打印临时 URL；Ctrl+C 停止外链：
./deploy/public-demo/share-trial.ps1
```

脚本不使用用户 SSH 私钥、不转发 SSH agent；服务端主机密钥首次记录在被 Git 忽略的 `target/public-access-trial/known_hosts`，后续变化会被 SSH 拒绝。隧道只指向 9902，不绕过网关直接开放 9900。不会启动或重建应用容器。

2026-09-22 实测：可信 HTTPS 登录页 200、登录页面内容正确、构建指纹与本地一致；Console 和管理 API 403，未登录业务 API 401。curl 实测登录页约 3.50 秒（其中 TLS 约 1.86 秒），压缩 CSS 约 3.64 秒/7351 字节。其他接口耗时约 2.1–7.2 秒。这是本机经公网绕行的少量样本，不是面试官网络实测。

该结果只证明入口已连通，速度未达到本地体验。尚未验收公网登录、附件、SSE 和断线恢复。不会因为登录页成功就宣称完整项目验收通过。

官方免费档明确限制速度，域名会定期变化。关闭隧道或重连后旧链接可能失效，因此不适合作为固定简历网址，也不自动写入 README 为正式演示地址。公网模型调用仍使用原有 API 额度，免费的是隧道。

## 当时的固定免费网址候选

ngrok 官方免费档提供一个账号绑定的 dev domain 和自动 HTTPS，需注册账号。普通浏览器首次访问会显示提示页，点击 Visit 后同域名 7 天内免提示；免费额度和国内实测速率仍需核实。它是下一候选，尚未配置，不应宣称已验证。

参考：[localhost.run 免费档](https://localhost.run/docs/forever-free/) · [HTTP 隧道](https://localhost.run/docs/http-tunnels/) · [FAQ](https://localhost.run/docs/faq/) · [ngrok 免费档限制](https://ngrok.com/docs/pricing-limits/free-plan-limits)
