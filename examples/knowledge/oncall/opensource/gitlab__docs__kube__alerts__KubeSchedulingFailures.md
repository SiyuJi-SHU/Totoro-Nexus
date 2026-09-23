# KubeSchedulingFailures

This runbook covers the related Kubernetes scheduling alerts on the `kube` service:

| Alert | Severity | Pages? | Measures |
| --- | --- | --- | --- |
| [`KubePodsUnschedulable`](#kubepodsunschedulable) | `s2` | yes (PagerDuty) | Workload pods individually stuck with `PodScheduled=False, reason=Unschedulable` for at least 15 minutes. DaemonSet-owned pods are excluded. |
| [`KubeDaemonSetPodsUnschedulable`](#kubedaemonsetpodsunschedulable) | `s3` | no | DaemonSet-owned pods individually stuck `Unschedulable` for at least 15 minutes. Visibility only. |
| [`KubeServiceClusterScaleupsErrorSLOViolation`](#kubeserviceclusterscaleupserrorsloviolation) | `s3` | no | The GKE Cluster Autoscaler's scale-up error ratio violates its SLO. Diagnostic, cause-side signal. |

`KubePodsUnschedulable` is the user-visible symptom: the scheduler cannot place a workload pod on any node. `KubeServiceClusterScaleupsErrorSLOViolation` is the most common upstream cause: the Cluster Autoscaler is failing to provision the capacity the scheduler is asking for. When responding to either, check the state of the other. `KubeDaemonSetPodsUnschedulable` is a separate non-paging signal for the DaemonSet case, which usually points at a stuck node rather than a workload-scheduling failure.

## Services

- [kube Service Overview](../kubernetes.md)
- Owner: [Fleet Management](https://handbook.gitlab.com/handbook/engineering/infrastructure-platforms/production-engineering/fleet-management/)

## Alerts

### KubePodsUnschedulable

This alert fires when one or more workload pods has been individually `Unschedulable` for at least 15 minutes, in any namespace, in any of our GKE clusters. DaemonSet-owned pods are excluded; see [`KubeDaemonSetPodsUnschedulable`](#kubedaemonsetpodsunschedulable).

"Individually" matters. The alert counts pods that have been continuously unschedulable for the entire 15-minute window, not the running total of unschedulable pods over a 15-minute window. This avoids false positives during long scale-up events where individual pending pods churn in and out of the unschedulable set while the namespace total stays positive.

What this means in practice:

- The Kubernetes scheduler cannot find a node that satisfies the pod's resource requests, node selectors, tolerations, or topology constraints.
- The Cluster Autoscaler has either not added capacity that would satisfy the pod, or has tried and failed; see `KubeServiceClusterScaleupsErrorSLOViolation` below.
- Workloads that depend on horizontal scaling (HPA-driven and otherwise) may stall, which can cause saturation or deployment failures downstream.

The recipient of this alert should:

1. Identify which cluster, namespace, and workload(s) are affected.
2. Determine why the pod cannot be scheduled (see Troubleshooting).
3. Take corrective action, or escalate if it cannot be self-resolved.

### KubeDaemonSetPodsUnschedulable

This alert fires when one or more DaemonSet-owned pods has been individually `Unschedulable` for at least 15 minutes. It is non-paging at severity `s3`. The persistence semantics are the same as for `KubePodsUnschedulable`: each pod must be continuously unschedulable for the entire 15-minute window.

DaemonSet-owned unschedulable pods are noisy. A node can become stuck for many reasons: taints, cordons, capacity, networking, drain churn, or a broken kubelet. An unschedulable DaemonSet pod produces a `NotReady` node from that DaemonSet's perspective, but does not directly prevent workload pods from being scheduled on healthy nodes. It is not worth paging on, but it is worth surfacing: broken DaemonSet specs (missing tolerations, bad node selectors) and persistently broken nodes both show up here.

Common causes:

- A node has been cordoned or tainted (planned or unplanned) and the DaemonSet does not tolerate the taint.
- A node is stuck `NotReady` due to a kubelet, networking, or capacity problem.
- The DaemonSet pod spec was changed with a new node selector or toleration set that does not match the current fleet.
- A node-pool rollout left some nodes in a transitional state.

If this alert is firing without `KubePodsUnschedulable` also firing, user workloads are very likely unaffected. The investigation focus is the node, not the cluster's scheduling capacity.

### KubeServiceClusterScaleupsErrorSLOViolation

This alert fires when the GKE [Cluster Autoscaler](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/cluster-autoscaler) fails to scale up node pools at a rate that violates our SLO.

The `cluster_scaleups` SLI for the `kube` service treats each scale-up decision by the Cluster Autoscaler as an operation and each scale-up failure as an error. The alert fires when the error ratio exceeds `14.4 × 5%` (~72%) over both a 1h and 5m window, with at least 1 op/s of scale-up activity, sustained for 2 minutes.

This alert was previously paged at `s2`, but was found to be noisy on its own. Scale-up errors do not always translate into pods being unable to schedule:

- A transient zonal stockout or quota blip can cause a scale-up failure that the autoscaler retries successfully on the next iteration.
- One node pool failing to scale up does not mean every node pool is failing. Pods that tolerate it are often scheduled on a different node pool while the failing one is still backing off, so there is no user-visible scheduling failure.

It is now `s3` and non-paging, kept as a diagnostic signal that gives context to `KubePodsUnschedulable`.

## Metrics

### KubePodsUnschedulable

The alert evaluates the `kube_pod_status_unschedulable` metric exported by `kube-state-metrics`, joined with `kube_pod_owner` to exclude DaemonSet-owned pods. The base metric is `1` on pods whose `PodScheduled` condition has been set to `False` with `reason=Unschedulable` by the scheduler, that is, the pods that emit `FailedScheduling` events.

PromQL:

```promql
sum by (env, environment, cluster, namespace) (
  (
    kube_pod_status_unschedulable{job="kube-state-metrics"} == 1
    and
    kube_pod_status_unschedulable{job="kube-state-metrics"} offset 15m == 1
    and
    (
      min_over_time(
        kube_pod_status_unschedulable{job="kube-state-metrics"}[15m]
      ) == 1
    )
  )
  unless on (cluster, namespace, pod)
  (
    kube_pod_owner{job="kube-state-metrics", owner_kind="DaemonSet"} == 1
  )
)
```

Threshold rationale:

- The alert fires only on pods that have been individually Unschedulable for the entire 15-minute window. Three terms in the expression enforce this:
  - `kube_pod_status_unschedulable == 1` says the pod is unschedulable right now.
  - `... offset 15m == 1` says the pod was already unschedulable 15 minutes ago. The label set (including `pod`) must match, so this is the same physical pod.
  - `min_over_time(...[15m]) == 1` says every sample over the last 15 minutes was `1`, so the pod has never briefly transitioned to scheduled or to a different state.
- `sum by (env, environment, cluster, namespace)` then counts the qualifying pods per namespace.
- `> 0` floor: any single pod stuck Unschedulable for the full window is a real scheduling failure that the scheduler and autoscaler retry loop has not resolved.
- `for: 1m`: a small buffer for evaluation jitter. The 15-minute persistence is enforced inside the expression, not by `for:`.
- The earlier form (`sum(...) > 0` with `for: 15m`) counted distinct pods over time. During a scale-up event lasting more than 15 minutes, individual pending pods can churn (one gets scheduled, another arrives) while the namespace sum stays `> 0`, which fires the alert even though no single pod was stuck. The current form alerts only on per-pod stuckness.

The `unless on (cluster, namespace, pod) kube_pod_owner{..., owner_kind="DaemonSet"}` clause drops pods whose direct owner is a DaemonSet. Pods without an owner (rare, e.g. a stray `kubectl run`) and pods owned by ReplicaSets, StatefulSets, Jobs, and so on all remain in the paging alert.

### KubeDaemonSetPodsUnschedulable

Same per-pod persistence shape, joined the other way to keep only DaemonSet-owned pods:

```promql
sum by (env, environment, cluster, namespace) (
  (
    kube_pod_status_unschedulable{job="kube-state-metrics"} == 1
    and
    kube_pod_status_unschedulable{job="kube-state-metrics"} offset 15m == 1
    and
    (
      min_over_time(
        kube_pod_status_unschedulable{job="kube-state-metrics"}[15m]
      ) == 1
    )
  )
  and on (cluster, namespace, pod)
  (
    kube_pod_owner{job="kube-state-metrics", owner_kind="DaemonSet"} == 1
  )
)
```

Threshold rationale:

- The per-pod persistence (`offset 15m == 1` plus `min_over_time(...[15m]) == 1`) and the `> 0` floor are the same as in the paging alert. DaemonSet pod scheduling normally completes well inside the 15-minute window when a node is healthy, so a DaemonSet pod that has been continuously unschedulable for the full window is a real signal worth surfacing, just not worth paging on.
- `for: 1m`: same reasoning as the paging alert.

### KubeServiceClusterScaleupsErrorSLOViolation

The SLI is defined in [`metrics-catalog/services/kube.jsonnet`](../../../metrics-catalog/services/kube.jsonnet) under the `cluster_scaleups` component, and is built from two Stackdriver log-based metrics exported from the GKE Cluster Autoscaler visibility logs:

- `stackdriver_k_8_s_cluster_logging_googleapis_com_user_k_8_s_cluster_autoscaler_scaleup_decisions`: operations (each scale-up attempt).
- `stackdriver_k_8_s_cluster_logging_googleapis_com_user_k_8_s_cluster_autoscaler_scaleup_errors`: errors (each scale-up failure).

Threshold rationale:

- The error budget is `monitoringThresholds.errorRatio: 0.95`, that is, we tolerate up to 5% scale-up errors.
- The alert uses a multi-window burn-rate of `14.4 × 0.05` over both 1h and 5m windows. This is the standard fast-burn pattern for a 30-day SLO.
- The minimum-traffic gate (`>= 1 op/s`) prevents the alert from firing during periods with no autoscaler activity, because log-based metrics gap-fill with zero.

Expected normal behavior:

- The Cluster Autoscaler runs scale-up evaluations every 10 seconds or so.
- Transient scale-up failures (for example a single zone stockout that resolves on retry) are expected at low rates and absorbed by the error budget.
- Sustained high error ratios indicate a structural problem: quota, IP exhaustion, max-nodes cap, or IAM regression.

Dashboards:

- [`kube-overview`](https://dashboards.gitlab.net/d/kube-main/kube-overview): filter by `environment` and `stage` from the alert labels.

## Alert Behavior

### KubePodsUnschedulable

- Paged via PagerDuty at severity `s2`.
- Avoid broad silences. If a silence is needed (for example a known terraform change is in flight), scope it to the smallest viable set of labels, typically `cluster` and `namespace`.

### KubeDaemonSetPodsUnschedulable

- Non-paging at severity `s3`. Visible in alertmanager and Slack only.
- During planned node-drain or node-pool rollout operations it is normal to see brief firings. Scope any silences to `(cluster, namespace)` and to the maintenance window.
- A sustained firing without `KubePodsUnschedulable` on the same cluster usually points at a stuck node rather than at scheduling capacity. The fix is most often to investigate that node (cordon, taint, capacity, kubelet, or networking), not the DaemonSet spec. Broken DaemonSet specs (missing toleration for a newly added taint, bad node selector) also surface here.

### KubeServiceClusterScaleupsErrorSLOViolation

- Non-paging at severity `s3`. Visible in alertmanager and Slack only.
- Treat this as a context signal: when investigating `KubePodsUnschedulable`, check whether this alert is also firing on the same cluster. That points at autoscaler scale-up failures as the cause.
- The underlying metrics are Stackdriver log-based and gap-fill with zero, so a brief firing followed by quick recovery can indicate a one-off zonal stockout or quota blip. Repeat firings within a short window are the more important signal.

## Incident Severities

- Default Incident Severity for `KubePodsUnschedulable`: **s3**.
- Consider escalating to **s2** if any of the following are true:
  - A user-impacting workload (`web`, `api`, `sidekiq`, `gitaly`) is unable to schedule new pods.
  - This alert is firing alongside `KubeContainersWaitingInError`, `GKENodeCountCritical`, or other saturation alerts on the same cluster.
  - The root cause is a GCP quota or capacity issue that cannot be self-resolved within the on-call shift.
- Impact assessment:
  - Internal-only: unschedulable pods on infrastructure node pools that are not on the customer hot path.
  - Customer-facing: unschedulable pods on node pools backing `web`, `api`, `sidekiq`, or `gitaly` workloads when load is rising.

## Verification

Confirm the alert reflects a real, ongoing problem before deep diagnosis.

### KubePodsUnschedulable

1. Break down by cluster and namespace to identify which workloads are blocked:

    ```promql
    sum by (cluster, namespace) (
      kube_pod_status_unschedulable{job="kube-state-metrics", env="gprd"} == 1
    )
    ```

2. Confirm from the cluster:

    ```bash
    kubectl get pods -A --field-selector=status.phase=Pending
    kubectl get events -A --field-selector reason=FailedScheduling
    ```

3. For a specific pod, `kubectl describe pod -n <namespace> <pod>` will show the scheduler's reason (`Insufficient cpu`, `node(s) didn't match Pod's node affinity/selector`, `0/N nodes are available`, etc.).

### KubeServiceClusterScaleupsErrorSLOViolation

1. Open the [`kube-overview` dashboard](https://dashboards.gitlab.net/d/kube-main/kube-overview) (the link is also in the alert annotation) filtered to the firing `environment` and `stage`.
2. Confirm the SLI ratio is elevated:

    ```promql
    gitlab_component_errors:ratio_5m{component="cluster_scaleups",env="gprd",type="kube"}
    ```

3. Break down errors by cluster to identify which cluster(s) are affected:

    ```promql
    sum by (cluster_name) (
      avg_over_time(stackdriver_k_8_s_cluster_logging_googleapis_com_user_k_8_s_cluster_autoscaler_scaleup_errors[5m])
    )
    ```

    Example output during a firing:

    ```
    {cluster_name="gprd-us-east1-b"}  0.83
    {cluster_name="gprd-us-east1-c"}  0
    {cluster_name="gprd-us-east1-d"}  0
    ```

    A non-zero value for one cluster and zero for the others means the problem is localized to that cluster, and usually to a specific node pool within it.

4. Cross-check from the cluster itself by inspecting the autoscaler status ConfigMap (see Troubleshooting). If the SLI shows errors but the ConfigMap shows everything healthy, suspect a metric pipeline lag (Stackdriver to Mimir) rather than a real fault.

Stackdriver log links for raw error details are wired into the metrics catalog as tooling links and are surfaced from Grafana and the alert details:

- [Kubernetes Autoscaler Logs](https://docs.cloud.google.com/logging/docs)
- [Kubernetes Autoscaler Errors](https://docs.cloud.google.com/logging/docs) (filtered on `jsonPayload.resultInfo.results.errorMsg.messageId`)

## Recent changes

- [Recent related production change requests](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=change%3A%3Acomplete)
- [Recent `config-mgmt` MRs](https://ops.gitlab.net/gitlab-com/gl-infra/config-mgmt/-/merge_requests?sort=merged_at_desc&state=merged). Node pool sizes, max-nodes caps, instance types, zones, IAM, and quotas are managed here.
- [Recent ArgoCD MRs](https://gitlab.com/gitlab-com/gl-infra/argocd/apps/-/merge_requests/?sort=merged_at_desc&state=merged) and [recent `k8s-workloads` MRs](https://gitlab.com/groups/gitlab-com/gl-infra/k8s-workloads/-/merge_requests?sort=merged_at_desc&state=merged). Workloads with new resource requests, affinities, or tolerations can leave pods unschedulable.
- To roll back a change, find the MR that introduced it (typically in `config-mgmt` for node pool or quota changes, or in ArgoCD or `k8s-workloads` for workload changes) and revert it. Confirm the pipeline completes.

## Troubleshooting

The same investigation order applies to both alerts, since `KubeServiceClusterScaleupsErrorSLOViolation` is the most common cause of `KubePodsUnschedulable`.

1. Identify the firing cluster(s) and stage from the alert labels and the per-cluster PromQL in the Verification section.
2. Often GKE clusters cannot scale up because there are errors growing the underlying machine groups. You can check gce_instance errors to grow the group and see why it's failing quickly. [GPRD Instance create or insert errors](https://cloudlogging.app.goo.gl/xUkeGzTAv2ZVvAY88)
3. Connect to the cluster:

    ```bash
    glsh kube use-cluster <env>
    ```

> [!tip]
> You can get a list of clusters to connect to by running `glsh kube use-cluster` with no environment specified.

4. Identify the pending or unschedulable pods to understand what is being blocked:

    ```bash
    kubectl get pods -A --field-selector=status.phase=Pending
    kubectl get events -A --field-selector reason=FailedScheduling
    ```

    For each affected pod, `kubectl describe pod -n <namespace> <pod>` will show the scheduler's reason in the `Events` section. Common reasons:

    - `Insufficient cpu` or `Insufficient memory`: the cluster needs to scale up. Go to step 5.
    - `node(s) didn't match Pod's node affinity/selector`: workload is constrained to a node pool or zone that has no capacity.
    - `node(s) had untolerated taint {...}`: workload is missing a toleration, or a taint was added.
    - `0/N nodes are available: N node(s) didn't have free ports`: host-port conflict.
    - `pod has unbound immediate PersistentVolumeClaims`: PVC or storage problem.

5. Review the Cluster Autoscaler's own status snapshot:

    ```bash
    kubectl describe configmap cluster-autoscaler-status -n kube-system
    ```

    This is usually the fastest way to pinpoint the failing node pool. Look for:

    - `Health` per node group. A `Healthy: False` block names the node group and the reason.
    - `ScaleUp` block. States are `InProgress`, `NoActivity`, or `Backoff`. A `Backoff` block includes the last error and the retry time.
    - Node group sizes: `cloudProviderTarget`, `minSize`, `maxSize`. A node group at `maxSize` cannot scale further; this often correlates with the `component_saturation_slo_out_of_bounds:kube_pool_max_nodes` saturation alert.
    - Last transition timestamps. Correlate with the alert firing time.

    The ConfigMap is updated about every 10 seconds and reflects live state.

6. Open the Stackdriver Cluster Autoscaler error logs (the link is on the alert and on the Grafana dashboard) and read the `jsonPayload.resultInfo.results.errorMsg.messageId` field. The most common causes we have seen in production are listed in the table below.

7. If a node pool is at its cap, inspect its terraform-managed limits:

    ```bash
    gcloud container node-pools describe <node-pool> \
      --project="${GOOGLE_PROJECT}" \
      --region="${GOOGLE_REGION}" \
      --cluster="${CLUSTER_NAME}"
    ```

    The authoritative max-node configuration lives in [`config-mgmt`](https://ops.gitlab.net/gitlab-com/gl-infra/config-mgmt/).

8. Check the [GCP quotas page](https://console.cloud.google.com/iam-admin/quotas) for the project, in particular CPUs, in-use IP addresses, Hyperdisk, SSD persistent disk, and the regional or zonal quota for the relevant instance family.

### Top causes we have seen in production

| `messageId` / cause | Meaning | First-line action |
| --- | --- | --- |
| `scale.up.error.quota.exceeded` | A GCP quota was hit (CPUs, IPs, Hyperdisk, SSD, instance group size). | Cross-check the [GCP quota runbook](../../uncategorized/alerts/gcp_quota_limit.md). Request a quota increase via the project's GCP console, or open a Google Cloud support case. |
| `scale.up.error.out.of.resources` | GCE stockout in the target zone for the requested instance type. | Usually transient; the autoscaler will retry. If sustained, add a new node pool with a different machine family via Terraform in [`config-mgmt`](https://ops.gitlab.net/gitlab-com/gl-infra/config-mgmt/). |
| `scale.up.error.ip.space.exhausted` | Pod or node CIDR is exhausted for the cluster. Each node allocates a `/24` CIDR block from the pod IP range(s) and fails to provision if it cannot. | Pod subnet exhausted: add a secondary pod subnet in [`config-mgmt`](https://ops.gitlab.net/gitlab-com/gl-infra/config-mgmt/) (example: [`config-mgmt!13329`](https://ops.gitlab.net/gitlab-com/gl-infra/config-mgmt/-/merge_requests/13329)). Cluster (node) subnet exhausted: the cluster must be reprovisioned with a larger subnet. Coordinate with networking; this is not a quick fix. |
| `scale.up.error.waiting.for.instances.timeout` | GCE instance creation timed out before the node became Ready. | Check the [GCP status page](https://status.cloud.google.com/), retry, and inspect the node pool image and startup. If recent, correlate with image version or terraform changes. |
| Max nodes reached (Terraform cap) | The node pool is at its configured maximum and the autoscaler cannot grow it. | Correlates with `component_saturation_slo_out_of_bounds:kube_pool_max_nodes`. Raise the cap in [`config-mgmt`](https://ops.gitlab.net/gitlab-com/gl-infra/config-mgmt/) only after confirming headroom is needed. Note: `maxSize` cannot exceed the number of IPs available in the cluster subnet. If the subnet is the binding limit, see the `scale.up.error.ip.space.exhausted` row instead. |
| Workload misconfiguration | Node affinity, nodeSelector, taints or tolerations, or topology spread constraints prevent scheduling on any existing node, and no scale-up will help. | Revert the offending workload MR (typically in [`k8s-workloads`](https://gitlab.com/groups/gitlab-com/gl-infra/k8s-workloads) or [`argocd`](https://gitlab.com/gitlab-com/gl-infra/argocd)). |

For the full list of GKE Cluster Autoscaler `messageId` values, see the [GKE cluster autoscaler error reference](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/cluster-autoscaler#messages).

## Possible Resolutions

- [Previous `KubePodsUnschedulable` incidents](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=a%3AKubePodsUnschedulable)
- [Previous `KubeServiceClusterScaleupsErrorSLOViolation` incidents](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=a%3AKubeServiceClusterScaleupsErrorSLOViolation)

When resolving an incident under either alert, please add a link here so future on-call engineers can learn from it.

## Dependencies

- GCP project quotas (CPUs, in-use IPs, Hyperdisk, SSD persistent disk, instance group size).
- GCE zonal capacity for the instance types used by our node pools.
- Cloud Logging ingestion. The `cluster_scaleups` SLI is built from log-based metrics, so a Stackdriver outage can affect that signal (but not `KubePodsUnschedulable`, which is sourced from kube-state-metrics).
- `kube-state-metrics` availability for `KubePodsUnschedulable`.
- Terraform-managed node pool definitions in [`config-mgmt`](https://ops.gitlab.net/gitlab-com/gl-infra/config-mgmt).
- IAM and service account configuration for the node pools.

## Escalation

- Primary: `#g_fleet_management`
- Adjacent: Delivery for workload-owner questions when a specific GitLab.com deployment is affected.
- For GCP quota or stockout issues that cannot be self-resolved within the on-call shift, open a support case with Google Cloud and link it from the incident.

## Definitions

- `KubePodsUnschedulable` and `KubeDaemonSetPodsUnschedulable` are defined in [`libsonnet/alerts/kube-pods-unschedulable-alerts.libsonnet`](../../../libsonnet/alerts/kube-pods-unschedulable-alerts.libsonnet) and rendered via [`mimir-rules-jsonnet/kube-pods-unschedulable-alerts.jsonnet`](../../../mimir-rules-jsonnet/kube-pods-unschedulable-alerts.jsonnet). The tunable parameters are the lookback window (currently `15m`, controlling the per-pod persistence threshold via both the `offset 15m` and `min_over_time(...[15m])` clauses) and the `> 0` floor. The DaemonSet partitioning is done via `kube_pod_owner{owner_kind="DaemonSet"}`.
- `KubeServiceClusterScaleupsErrorSLOViolation` is defined in [`metrics-catalog/services/kube.jsonnet`](../../../metrics-catalog/services/kube.jsonnet) under `components.cluster_scaleups`. The tunable parameter is `monitoringThresholds.errorRatio` on the SLI. Raising it widens the error budget; do this only if there is a justified, persistent operational reason and a corresponding plan to address the underlying cause.
- Generated alert rules:
  - `mimir-rules/gitlab-{gprd,gstg,pre,ops}/kube/kube-pods-unschedulable-alerts.yml`
  - `mimir-rules/gitlab-{gprd,gstg,pre,ops}/kube/autogenerated-*-kube-service-level-alerts.yml`
- [Edit this playbook](https://gitlab.com/gitlab-com/runbooks/-/edit/master/docs/kube/alerts/KubeSchedulingFailures.md)
- [Update the template used to format this playbook](https://gitlab.com/gitlab-com/runbooks/-/edit/master/docs/template-alert-playbook.md)

## Related Links

- [Related alerts](./)
- [`kubernetes.md`](../kubernetes.md) — cluster inventory and repository map.
- [`KubeContainersWaitingInError`](./KubeContainersWaitingInError.md)
- [GCP quota limit runbook](../../uncategorized/alerts/gcp_quota_limit.md)
- [GKE Cluster Autoscaler concepts](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/cluster-autoscaler)
