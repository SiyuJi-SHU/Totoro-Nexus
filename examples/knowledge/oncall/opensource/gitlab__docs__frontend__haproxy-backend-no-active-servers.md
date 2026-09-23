# HAProxy Backend Has No Active Servers

## Symptoms

- PagerDuty alert: `HAProxyBackendNoActiveServers`
- Alert labels identify the affected fleet and backend:
  - `type` — one of `frontend`, `ci`, `registry`, `web-pages`
  - `backend` — the HAProxy backend (e.g. `web`, `api`, `https_git`, `registry`, `pages_https`)

- Below is a table that co-relates which type corresponds to which Haproxy fleet

| Type label  | HAProxy fleet      |
| ----------- | ------------------ |
| `ci`        | `haproxy-ci`       |
| `frontend`  | `haproxy-main`     |
| `registry`  | `haproxy-registry` |
| `web-pages` | `haproxy-pages`    |


- Customer-visible impact: traffic to the named backend in that fleet is failing.
  Depending on the backend, this may surface as 5xx errors, git/SSH connection
  failures, registry pulls failing, or Pages outages.
  For CI this could mean Runners not being able to process jobs at all

- This alert excludes the `stats` , `ci_gateway_catch_all`, `ci_gateway_git_repo_redirection`, `packhorse` backends since they have no `active servers` even in UP state.

## What the alert means

Every HAProxy node in the fleet (`type=<fleet>`) reports `haproxy_backend_active_servers == 0`
for the named backend. HAProxy has marked every upstream server in that backend as DOWN,
so it has nowhere to send traffic. The alert is aggregated with
`max by (backend, type, environment)`, so it only fires when *all* nodes in the fleet agree.

## Triage

1. Identify the impacted fleet and backend from the alert payload.
2. Open the HAProxy dashboards and filter by `type` and `backend`:
   - HAProxy overview — <https://dashboards.gitlab.net/d/haproxy/haproxy>
   - Frontend overview — <https://dashboards.gitlab.net/d/frontend-main/frontend-overview>
3. Confirm the situation in Mimir:

   ```promql
   haproxy_backend_active_servers{tier="lb", env="gprd", type="<fleet>", backend="<backend>"}
   ```

   Expect this to be `0` for the affected `(type, backend)` across every `fqdn` / `instance`.
4. Cross-check upstream health for that backend. The backend maps to a Kubernetes
   Deployment (Service IP or NGINX ingress) — see
   [HAProxy Management at GitLab → Backends](./haproxy.md#backends) for the mapping.
   For example `web` / `main_web` → `gitlab-webservice-web`, `registry` → `gitlab-registry`,
   `pages_https` → `pages` deployments.
5. Cross-check backend states , ssh into the relevant HAproxy node depending on which backend and fleet is affected

   ```
   ssh <haproxy-node-fqdn>
   echo "show stat" | sudo socat stdio /var/run/haproxy/admin.sock \

  | awk -F, 'NR==1 { next } $2=="BACKEND" { printf "%-25s %-6s %-12s %-5s %-30s %-18s\n", $1, $18, $37, $38, $57, $74 }'

  ```


## Common causes

- Upstream deployment is unhealthy: pods crash-looping, readiness probes failing,
  rollout in a bad state.
- All backend servers were drained (e.g. via
  [`set-server-state`](./haproxy.md#set-server-state)) and never re-enabled.
- HAProxy health-check misconfiguration after a `chef-repo` change.
- Network or DNS issue between HAProxy and the upstream Service IP / NGINX ingress.
- Certificate or TLS issue on the upstream causing health checks to fail.

## Resolution

- If the upstream Kubernetes Deployment is unhealthy, follow the runbook for
  that service (e.g. webservice, registry, gitaly, pages) and restore it. The
  alert clears automatically once HAProxy health checks pass.
- If servers were drained intentionally, restore them with:

  ```

  chef-repo$ ./bin/set-server-state {gprd,gstg} ready <filter>

  ```

  See [`set-server-state`](./haproxy.md#set-server-state).
- If a recent `chef-repo` change to `roles/<env>-base-haproxy-<fleet>.json` looks
  related, revert it and run chef on the affected HAProxy nodes.
- To confirm from a HAProxy node: `sudo hatop -s /run/haproxy/admin.sock` and
  check the backend's server status, or
  [`get-server-state`](./haproxy.md#get-server-state) from chef-repo.
- Logs on a HAProxy node: `/var/log/haproxy.log`.

## Escalation

- If you cannot quickly identify the upstream owner, declare an incident
  (this is an `s1` page) and tag the service owner from the
  [services catalog](https://gitlab.com/gitlab-com/runbooks/-/tree/master/services).
- If multiple backends or multiple fleets are alerting simultaneously, treat as
  a wider frontend / network incident and follow
  [gitlab.com is down](./gitlab-com-is-down.md).
