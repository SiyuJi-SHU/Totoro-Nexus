# BlackboxProbeFailures

## Overview

- The alert BlackboxProbeFailures is designed to notify you when the success rate of probes executed by the Blackbox exporter falls below 75% for 10 minutes. The instances in `gprd` are taken into consideration by the alert excluding the following
  - `https://ops.gitlab.net/users/sign_in`
  - `https://dev.gitlab.org.*`
  - `https://pre.gitlab.com`
  - `https://registry.pre.gitlab.com`
  - `https://status.gitlab.com`
  - `https://new-sentry.gitlab.net`
  - `https://staging.gitlab.com.*`

- A variety of factors can cause a probe to fail: a GCP outage, Cloudflare event, expired SSL certificate, or a breaking change.

- The service affected depends on the endpoint the probes failed for, the team owning the service can be determined in the [Service Catalog](https://gitlab.com/gitlab-com/runbooks/-/blob/master/services/service-catalog.yml) by searching for the service name.

- The recipient is supposed to check if the endpoint is reachable; if not, check for logs and try to figure out the cause of a endpoint being unreachable, and then fix it or escalate it.

## Services

- [Blackbox Exporter Service](https://github.com/prometheus/blackbox_exporter/blob/master/README.md)
- Team that owns the service: [Scalibility:Observability](https://handbook.gitlab.com/handbook/engineering/infrastructure-platforms/production-engineering/observability/)

## Metrics

- The metric in the provided Prometheus query is based on the success rate of probes executed by a Prometheus blackbox exporter. Link to the [metrics catalog](https://gitlab.com/gitlab-com/runbooks/-/blob/master/mimir-rules/gitlab-gprd/blackbox_alerts.yml#L5)

  `avg_over_time(probe_success{...}[10m]) * 100 < 75`: This part of the query calculates the average success rate over the past 10 minutes. The probe_success metric indicates whether the probe was successful (1 for success, 0 for failure). Multiplying by 100 converts this rate to a percentage. The condition < 75 triggers the alert if the average success rate falls below 75%.

- Given the reliance on DNS and network connectivity, the blackbox thresholds are chosen to minimize false alerts for minor and transient problems outside our control. It's still possible that a false alarm could result, but even if there is a non-service related cause for more than 10 minutes, we would want the engineer on call to be aware of it.

- [Dashboard with the alert expression](https://dashboards.gitlab.net/explore?schemaVersion=1&panes=%7B%220v3%22:%7B%22datasource%22:%22mimir-gitlab-gprd%22,%22queries%22:%5B%7B%22refId%22:%22A%22,%22expr%22:%22avg_over_time%28probe_success%7Bjob%3D%5C%22scrapeConfig%2Fmonitoring%2Fprometheus-agent-blackbox%5C%22,%20module%3D%5C%22http_2xx%5C%22,%20instance%21~%5C%22%28https:%2F%2Fops.gitlab.net%2Fusers%2Fsign_in%7Chttps:%2F%2Fdev.gitlab.org.%2A%7Chttps:%2F%2Fpre.gitlab.com%7Chttps:%2F%2Fregistry.pre.gitlab.com%7Chttps:%2F%2Frelease.gitlab.net%7Chttps:%2F%2Fstatus.gitlab.com%7Chttps:%2F%2Fnew-sentry.gitlab.net%29%5C%22,%20env%3D%5C%22gprd%5C%22%7D%5B10m%5D%29%20%2A%20100%20%3C%2075%22,%22range%22:true,%22instant%22:true,%22datasource%22:%7B%22type%22:%22prometheus%22,%22uid%22:%22mimir-gitlab-gprd%22%7D,%22editorMode%22:%22code%22,%22legendFormat%22:%22__auto%22%7D%5D,%22range%22:%7B%22from%22:%22now-24h%22,%22to%22:%22now%22%7D%7D%7D&orgId=1)

- Example of the metric while the alert is **firing**:

  ![Alert Firing](AlertFiring.png)

- Example of the metric under **normal** conditions:

  ![Alert Normal](AlertNormal.png)

- Any presence of a the metric below 75 shows some failures of the probes.

## Alert Behavior

- We can silence this alert by going [here](https://alerts.gitlab.net/#/alerts), finding the `BlackboxProbeFailures` and click on silence option. Silencing might be required if the alerts is caused by an external dependency out of our control.

- This alert is fairly common, past hits can be seen [here](https://nonprod-log.gitlab.net/app/r/s/vIAAl)

- [Previous incidents of this alert firing](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=a%3ABlackboxProbeFailures&first_page_size=100)

## Severities

- The incident severity can range from Sev4 to Sev1 depending on the endpoint.
- The impact depends on the endpoint being affected, as failures on certain endpoints will impact our customers.
- [Handbook Link]( https://handbook.gitlab.com/handbook/engineering/infrastructure-platforms/incident-management/#incident-severity) to better decide the severity of the incident.

## Verification

- [Prometheus link to query that triggered the alert](https://dashboards.gitlab.net/explore?schemaVersion=1&panes=%7B%220v3%22:%7B%22datasource%22:%22mimir-gitlab-gprd%22,%22queries%22:%5B%7B%22refId%22:%22A%22,%22expr%22:%22avg_over_time%28probe_success%7Bjob%3D%5C%22scrapeConfig%2Fmonitoring%2Fprometheus-agent-blackbox%5C%22,%20module%3D%5C%22http_2xx%5C%22,%20instance%21~%5C%22%28https:%2F%2Fops.gitlab.net%2Fusers%2Fsign_in%7Chttps:%2F%2Fdev.gitlab.org.%2A%7Chttps:%2F%2Fpre.gitlab.com%7Chttps:%2F%2Fregistry.pre.gitlab.com%7Chttps:%2F%2Frelease.gitlab.net%7Chttps:%2F%2Fstatus.gitlab.com%7Chttps:%2F%2Fnew-sentry.gitlab.net%29%5C%22,%20env%3D%5C%22gprd%5C%22%7D%5B10m%5D%29%20%2A%20100%20%3C%2075%22,%22range%22:true,%22instant%22:true,%22datasource%22:%7B%22type%22:%22prometheus%22,%22uid%22:%22mimir-gitlab-gprd%22%7D,%22editorMode%22:%22code%22,%22legendFormat%22:%22__auto%22%7D%5D,%22range%22:%7B%22from%22:%22now-24h%22,%22to%22:%22now%22%7D%7D%7D&orgId=1)

- [web service dashboard](https://dashboards.gitlab.net/d/web-main/web3a-overview?from=now-6h%2Fm&orgId=1&to=now-1m%2Fm&var-environment=gprd&var-stage=cny&viewPanel=1806708210)

## Recent changes

- [Recent Blackbox Production Change/Incident Issues](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=Service%3A%3ABlackbox&first_page_size=100)
- [Recent chef-repo Changes](https://gitlab.com/gitlab-com/gl-infra/chef-repo/-/merge_requests?scope=all&state=merged)
- [Recent k8s-workloads Changes](https://gitlab.com/gitlab-com/gl-infra/k8s-workloads/gitlab-com/-/merge_requests?scope=all&state=merged)

## Troubleshooting

- The blackbox exporter keeps logs from failed probes in memory and exposes them over a web interface.
  You can access it by using port forwarding, and then navigating to `http://localhost:9115`

  ```bash
    ssh blackbox-01-inf-gprd.c.gitlab-production.internal -L 9115:localhost:9115
  ```

  Please note that the exporter will only keep up to 1000 results, and drop older
  ones. So make sure to grab these as quickly as possible, before they expire.

- The troubleshooting process might differ depending on the endpoint that the blackbox probes failed for.
  - A good place to start is to trying figure out if it is an internal or external dependency.
  - A recent deployment could have caused this issue and a good place to confirm it would be checking the web service [dashboard](https://dashboards.gitlab.net/d/web-main/web3a-overview?from=now-6h%2Fm&orgId=1&to=now-1m%2Fm&var-environment=gprd&var-stage=cny&viewPanel=1806708210).
    - The next step in that case would be to contact the release managers and disabling canary and blocking deployments due to the incident
  - Check for [GCP](https://status.cloud.google.com/) and [Cloudflare](https://www.cloudflarestatus.com/) outages reported on their public status pages to see if they coincide with the downtime.

- [Blackbox git exporter is down](../blackbox-git-exporter.md)
- [design.gitlab.com Runbook](../../design/design-gitlab-com.md)
- [GitLab Docs website troubleshooting](../../docs-website/README.md)
- [CI Artifacts CDN](../../google-cloud-storage/artifacts-cdn.md)
- [Investors Relations (ir.gitlab.com) main troubleshoot documentation](../../ir.gitlab.com/overview.md)
- [Tuning and Modifying Alerts](../../monitoring/alert_tuning.md)
- [An impatient SRE's guide to deleting alerts](../../monitoring/deleting-alerts.md)
- [../../patroni/postgres.md](../../patroni/postgres.md)
- [../../patroni/postgresql-backups-wale-walg.md](../../patroni/postgresql-backups-wale-walg.md)
- [Container Registry CDN](../../registry/cdn.md)
- [../../spamcheck/index.md](../../spamcheck/index.md)
- [GitLab Job Completion](../../uncategorized/job_completion.md)
- [version.gitlab.com Runbook](../../version/version-gitlab-com.md)
- [Elasticsearch](https://log.gprd.gitlab.net/app/discover#/?_g=h@44136fa&_a=h@4de5c9b)
- For certificate expiry alerts, it may be helpful to refer to [this runbook](../../certificates/README.md) on certificates.

## Possible Resolutions

- [Issue 18268](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/18268)
- [Issue 17291](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/17291)

## Dependencies

- The alert might trigger due to a variety of factors, such as: a GCP outage, Cloudflare event, expired SSL certificate, or a breaking change related to the endpoint.

## Escalation

Slack channels to look for assistance:

- [`#production_engineering`](https://gitlab.enterprise.slack.com/archives/C07U6SAKS4D)
- [`#infrastructure_platforms`](https://gitlab.enterprise.slack.com/archives/C02D1HQRTKQ)

## Definitions

- [Link to tune alert](https://gitlab.com/gitlab-com/runbooks/-/blob/master/mimir-rules/gitlab-gprd/blackbox_alerts.yml#L5)
<!--- Advice or limitations on how we should or shouldn't tune the alert -->
- [Link to edit this playbook](BlackboxProbeFailures.md)
- [Update the template used to format this playbook](https://gitlab.com/gitlab-com/runbooks/-/edit/master/docs/template-alert-playbook.md)

## Related Links

- [Related alerts](./)
<!--- Related documentation -->
