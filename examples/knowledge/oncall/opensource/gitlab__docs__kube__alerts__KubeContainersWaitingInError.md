# KubeContainersWaitingInError

## Overview

More than 50% of the containers waiting to start for a Deployment are in an error state — any reason other than `ContainerCreating`. Existing running containers are unaffected; the risk is that they may be terminated while replacements cannot start.

Common causes:

- GCP quota limits blocking node pool scale-ups.
- A configuration error rolled out to the app, producing `CrashLoopBackOff` on startup.
- Image pull failure from the registry.
- A burst of new pods overwhelming create rate.
- Secret / ConfigMap / PVC not available.
- Calico-typha migration or failure (on clusters still running Calico — see [Calico related failures](#calico-related-failures) below).

## Services

- [Kubernetes Service Overview](../kubernetes.md)
- Owner: [Fleet Management](https://handbook.gitlab.com/handbook/engineering/infrastructure-platforms/production-engineering/fleet-management/)

## Metrics

- [Alerts: Containers Waiting Dashboard](https://dashboards.gitlab.net/d/alerts-kube_containers_waiting/alerts3a-containers-waiting?orgId=1&var-PROMETHEUS_DS=mimir-gitlab-gprd&var-environment=gprd&var-type=web&var-stage=main&var-cluster=gprd-us-east1-b) — reasons why containers are waiting.
- Normal operations see spikes during rolling deploys — `Pending` and `PodInitializing` reasons cycle quickly. The alert only fires when non-`ContainerCreating` reasons stay above 50% of surge capacity.
- [Example of alert firing](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/8714#note_1351307313).

## Alert Behavior

- Rare in steady state; when it fires it usually needs manual intervention.
- Avoid broad silences. If a silence is needed, scope it to the smallest set of labels (deployment, or cluster if cluster-wide).

## Severities

Depends on which workload is affected:

- **Outage-inducing** (start higher): `consul`, `nginx-ingress` — no PDB or fallback.
- **Degraded but functional**: `sidekiq`, `web`, `api` — PDB keeps old pods around while new ones fail; SLO may still hold.

Determine impact from the affected workload before assigning severity.

## Verification

- [Alerts: Containers Waiting Dashboard](https://dashboards.gitlab.net/d/alerts-kube_containers_waiting/alerts3a-containers-waiting?orgId=1&var-PROMETHEUS_DS=mimir-gitlab-gprd&var-environment=gprd&var-type=web&var-stage=main&var-cluster=gprd-us-east1-b) — filter to the firing `environment`, `type`, `cluster`.
- GKE events are ingested into Cloud Logging. Filter by cluster and namespace, look for pod `Warning` events.

## Recent changes

- [Recent production change requests](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=change%3A%3Acomplete)
- [Recent `k8s-workloads/gitlab-com` MRs](https://gitlab.com/gitlab-com/gl-infra/k8s-workloads/gitlab-com/-/merge_requests?scope=all&state=merged)
- [Recent `argocd/apps` MRs](https://gitlab.com/gitlab-com/gl-infra/argocd/apps/-/merge_requests?scope=all&state=merged)

To roll back a workload change, revert the MR that introduced it (usually in [`k8s-workloads/gitlab-com`](https://gitlab.com/gitlab-com/gl-infra/k8s-workloads/gitlab-com) or [`argocd/apps`](https://gitlab.com/gitlab-com/gl-infra/argocd/apps)) and confirm the pipeline / ArgoCD sync completes.

## Troubleshooting

1. Connect to the affected cluster: `glsh kube use-cluster <env>`.
2. Identify failing pods and their waiting reasons:

    ```sh
    kubectl get pods -A --field-selector=status.phase=Pending
    kubectl get pods -A -o jsonpath='{range .items[?(@.status.containerStatuses[-1:].state.waiting)]}{.metadata.name}: {@.status.containerStatuses[*].state.waiting.reason}{"\n"}{end}'
    kubectl get pods -A | grep -v Running
    ```

3. For each affected pod, inspect events and logs:

    ```sh
    kubectl describe pod -n <namespace> <pod>
    kubectl logs -n <namespace> <pod> [--previous]
    ```

4. Cross-check the container-waiting dashboard for the specific waiting reason and pattern.

PromQL to see deployments over the threshold:

```promql
sum by (type, env, tier, stage, cluster) (
  kube_pod_container_status_waiting_reason:labeled{reason!="ContainerCreating",stage!="",type!=""}
) > 0
>=
on (type, env, tier, stage, cluster) (
  topk by (type, env, tier, stage, cluster) (
    1, kube_deployment_spec_strategy_rollingupdate_max_surge:labeled{stage!="",tier!="",type!=""}
  ) * 0.5
)
```

### Calico related failures

Applies to clusters still running Calico (regional clusters and some legacy — see [kubernetes.md](../kubernetes.md#networking)). Rebuilt zonal clusters use [Dataplane V2](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/dataplane-v2) (Cilium) instead and do not have `calico-typha` pods.

If `KubeContainersWaitingInError` correlates with recent `calico-typha` restarts, check:

```sh
kubectl get pods -n kube-system | grep calico-typha
# Have any pods restarted recently around the incident time?

kubectl logs -n kube-system calico-typha-<pod>
```

## Possible resolutions

- [Previous incidents](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=a%3AKubeContainersWaitingInError&first_page_size=20)
- [2023-09-25: KubeContainersWaitingInError for canary services](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/16430)
- [2024-03-20: KubeContainersWaitingInError external-dns](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/17739)
- [2024-05-23: Containers are unable to start](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/18057)

## Dependencies

- Secret / ConfigMap availability (most common cause historically).
- Container image availability in the registry.
- PVC binding.
- Node pool capacity (see also [KubeSchedulingFailures](KubeSchedulingFailures.md)).

## Escalation

- Identify the affected workload; escalate to its owning team.
- `#g_fleet_management` for cluster-wide issues.

## Definitions

- [Alert definition (libsonnet)](../../../libsonnet/alerts/kube-cause-alerts.libsonnet)
- The tunable parameter is the percentage of errored containers we tolerate.
- [Edit this playbook](https://gitlab.com/gitlab-com/runbooks/-/edit/master/docs/kube/alerts/KubeContainersWaitingInError.md)

## Related Links

- [Related alerts](./)
- [Kubernetes overview](../kubernetes.md)
- [KubeSchedulingFailures](KubeSchedulingFailures.md) — pods stuck `Unschedulable`.
