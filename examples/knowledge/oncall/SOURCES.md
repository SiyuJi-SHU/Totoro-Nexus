# AIOps 知识库来源清单

## 知识库构成

- 开源真实文档：35 篇（GitLab Runbooks 27 篇、PagerDuty Incident Response Docs 8 篇）。
- AI 生成旧文档：原有 35 篇已按补救 Step R0 清理，不再作为知识库或评测依据。
- 当前知识库：35 篇开源 Markdown 正文；`SOURCES.md` 和 `LICENSES/` 仅用于溯源，不参与索引。

开源正文保存在本目录下的 `opensource/`。本地文件名增加了仓库和原始路径前缀以避免重名，正文未改写。GitLab 文件已逐篇与固定提交中的 Git blob 哈希比对；PagerDuty 文件从固定提交的 GitHub raw 地址下载。

## 固定版本与许可证

| 仓库 | 固定提交 | License | 本地许可证副本 |
|---|---|---|---|
| GitLab Runbooks | `6c00e37903535305ea0bcdf44fc4ab25ed0fe513` | MIT | `opensource/LICENSES/GitLab-Runbooks-MIT.txt` |
| PagerDuty Incident Response Docs | `464fc9d3e47e19e9d8da17cec1a41dc09624e95a` | Apache-2.0 | `opensource/LICENSES/PagerDuty-Apache-2.0.txt` |

## 逐篇溯源

| 本地文件 | 原始 URL | 所属仓库 | License | 选取理由 |
|---|---|---|---|---|
| `gitlab__docs__alerts__ApdexSLOViolation.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/alerts/ApdexSLOViolation.md | GitLab Runbooks | MIT | 覆盖延迟/Apdex SLO 违规后的通用诊断路径。 |
| `gitlab__docs__alerts__ErrorSLOViolation.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/alerts/ErrorSLOViolation.md | GitLab Runbooks | MIT | 覆盖错误率 SLO 违规、服务定位和升级流程。 |
| `gitlab__docs__alerts__TrafficAbsent.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/alerts/TrafficAbsent.md | GitLab Runbooks | MIT | 用于无流量告警，区分预期静默与真实服务中断。 |
| `gitlab__docs__blackbox__alerts__BlackboxProbeFailures.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/blackbox/alerts/BlackboxProbeFailures.md | GitLab Runbooks | MIT | 包含外部探测、网络、DNS/TLS 等多层排查线索。 |
| `gitlab__docs__cloud-sql__alerts__CloudSQLDatabaseDown.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/cloud-sql/alerts/CloudSQLDatabaseDown.md | GitLab Runbooks | MIT | 真实托管数据库宕机和恢复决策流程。 |
| `gitlab__docs__elastic__disk_space_saturation.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/elastic/disk_space_saturation.md | GitLab Runbooks | MIT | 覆盖 Elasticsearch 磁盘水位、容量和恢复处理。 |
| `gitlab__docs__elastic__troubleshooting__elk_mapper_parsing_exception.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/elastic/troubleshooting/elk_mapper_parsing_exception.md | GitLab Runbooks | MIT | 提供字段映射冲突导致日志写入失败的具体案例。 |
| `gitlab__docs__fleet-management__config_management__alerts__ComponentResourceRunningOut_disk_space.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/fleet-management/config_management/alerts/ComponentResourceRunningOut_disk_space.md | GitLab Runbooks | MIT | 节点磁盘耗尽告警，包含容量确认和处置分支。 |
| `gitlab__docs__frontend__high-error-rate.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/frontend/high-error-rate.md | GitLab Runbooks | MIT | 短篇前端高错误率排查，可与长 runbook 形成长度对照。 |
| `gitlab__docs__frontend__haproxy-backend-no-active-servers.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/frontend/haproxy-backend-no-active-servers.md | GitLab Runbooks | MIT | 负载均衡器无可用后端的真实故障处理。 |
| `gitlab__docs__gitaly__gitaly-down.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/gitaly/gitaly-down.md | GitLab Runbooks | MIT | 存储服务不可用时的定位、隔离与恢复步骤。 |
| `gitlab__docs__gitaly__gitaly-error-rate.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/gitaly/gitaly-error-rate.md | GitLab Runbooks | MIT | Git RPC 错误率升高的服务级排查。 |
| `gitlab__docs__gitaly__gitaly-latency.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/gitaly/gitaly-latency.md | GitLab Runbooks | MIT | 仓库存储延迟问题，适合构造性能类检索题。 |
| `gitlab__docs__gitaly__gitaly-repository-corruption.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/gitaly/gitaly-repository-corruption.md | GitLab Runbooks | MIT | 长篇仓库损坏诊断和安全恢复流程。 |
| `gitlab__docs__kube__alerts__KubeContainersWaitingInError.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/kube/alerts/KubeContainersWaitingInError.md | GitLab Runbooks | MIT | 覆盖多种容器 Waiting 状态及对应排查分支。 |
| `gitlab__docs__kube__alerts__KubeSchedulingFailures.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/kube/alerts/KubeSchedulingFailures.md | GitLab Runbooks | MIT | 25KB 以上复杂调度故障 runbook，提供大量跨章节上下文。 |
| `gitlab__docs__monitoring__alertmanager-notification-failures.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/monitoring/alertmanager-notification-failures.md | GitLab Runbooks | MIT | 告警通知链路失败，覆盖 Alertmanager 和下游接收方。 |
| `gitlab__docs__monitoring__prometheus-high-memory.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/monitoring/prometheus-high-memory.md | GitLab Runbooks | MIT | 短篇 Prometheus 内存压力处置。 |
| `gitlab__docs__monitoring__prometheus-not-ingesting.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/monitoring/prometheus-not-ingesting.md | GitLab Runbooks | MIT | Prometheus 停止摄取指标的简洁排障步骤。 |
| `gitlab__docs__monitoring__prometheus-wal-corruption.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/monitoring/prometheus-wal-corruption.md | GitLab Runbooks | MIT | 最短真实文档之一，覆盖 WAL 损坏恢复。 |
| `gitlab__docs__patroni__alerts__PatroniDeadlocksDetected.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/patroni/alerts/PatroniDeadlocksDetected.md | GitLab Runbooks | MIT | PostgreSQL 死锁检测、SQL 取证和缓解流程。 |
| `gitlab__docs__patroni__postgres-data-corruption.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/patroni/postgres-data-corruption.md | GitLab Runbooks | MIT | 数据损坏事故的确认、影响评估和修复。 |
| `gitlab__docs__patroni__postgresql-disk-space.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/patroni/postgresql-disk-space.md | GitLab Runbooks | MIT | PostgreSQL 磁盘空间告警和扩容/清理选择。 |
| `gitlab__docs__patroni__postgresql-locking.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/patroni/postgresql-locking.md | GitLab Runbooks | MIT | 长篇锁等待诊断，包含多类 SQL 和决策依据。 |
| `gitlab__docs__redis__redis-survival-guide-for-sres.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/redis/redis-survival-guide-for-sres.md | GitLab Runbooks | MIT | 30KB 综合 Redis SRE 指南，测试长上下文切分效果。 |
| `gitlab__docs__sidekiq__sidekiq-queue-not-being-processed.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/sidekiq/sidekiq-queue-not-being-processed.md | GitLab Runbooks | MIT | 队列停止消费的短篇现场排查。 |
| `gitlab__docs__sidekiq__sidekiq_error_rate_high.md` | https://gitlab.com/gitlab-com/runbooks/-/blob/6c00e37903535305ea0bcdf44fc4ab25ed0fe513/docs/sidekiq/sidekiq_error_rate_high.md | GitLab Runbooks | MIT | Sidekiq 高错误率的日志、队列和依赖分析。 |
| `pagerduty__docs__oncall__alerting_principles.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/oncall/alerting_principles.md | PagerDuty Incident Response Docs | Apache-2.0 | 真实告警质量原则，补充什么情况应触发人工响应。 |
| `pagerduty__docs__oncall__being_oncall.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/oncall/being_oncall.md | PagerDuty Incident Response Docs | Apache-2.0 | 覆盖值班准备、通知方式、升级与交接职责。 |
| `pagerduty__docs__before__severity_levels.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/before/severity_levels.md | PagerDuty Incident Response Docs | Apache-2.0 | 事故分级、影响判定和不同级别响应动作。 |
| `pagerduty__docs__before__complex_incidents.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/before/complex_incidents.md | PagerDuty Incident Response Docs | Apache-2.0 | 多团队复杂事故中的分组、协调和信息流。 |
| `pagerduty__docs__before__different_roles.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/before/different_roles.md | PagerDuty Incident Response Docs | Apache-2.0 | Incident Commander、Scribe、SME 等角色职责。 |
| `pagerduty__docs__during__during_an_incident.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/during/during_an_incident.md | PagerDuty Incident Response Docs | Apache-2.0 | 事故发生期间的结构化响应和沟通规范。 |
| `pagerduty__docs__during__security_incident_response.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/during/security_incident_response.md | PagerDuty Incident Response Docs | Apache-2.0 | 安全事故与普通运维事故的差异化处理。 |
| `pagerduty__docs__after__post_mortem_process.md` | https://github.com/PagerDuty/incident-response-docs/blob/464fc9d3e47e19e9d8da17cec1a41dc09624e95a/docs/after/post_mortem_process.md | PagerDuty Incident Response Docs | Apache-2.0 | 事故后复盘、行动项和组织学习闭环。 |

## 选择原则

1. 主体使用真实生产 runbook 和真实事故响应流程，而不是重新生成或改写内容。
2. 同时覆盖短文档、长 runbook、无标题文档、深层嵌套标题和代码/命令块。
3. 主题横跨应用、数据库、缓存、队列、Kubernetes、监控、负载均衡和事故管理，便于后续构造 simple / medium / hard 评测题。
4. 每个外部文件保留固定提交 URL 和许可证，确保 R2/R3/R4 可复现、可审计。

## R1 语料差异性验收

以下统计只计算 35 篇开源正文，不把本文件和许可证计入知识文档：

| 指标 | 结果 |
|---|---:|
| 文档字符数范围 | 507–30,961 |
| 文档字符数中位数 | 5,296 |
| 无 Markdown 标题文档 | 1 |
| 按现有 Java 标题算法得到的章节 | 382 |
| 最长标题章节 | 6,360 字符 |
| 超过 500 字符的章节 | 152 |
| 超过 800 字符的章节 | 91 |
| 超过 1200 字符的章节 | 50 |

这些数据证明 500/50、800/100、1200/200 和 heading-only 会在后续 R4 中触发不同处理，不再重复第一轮“全部章节短于最小阈值”的无效实验。

发布脱敏：`gitlab__docs__monitoring__alertmanager-notification-failures.md` 中的 Slack Webhook 地址已替换为占位符，其余正文和来源许可保留。
