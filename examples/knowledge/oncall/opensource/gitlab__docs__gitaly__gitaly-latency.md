# Gitaly latency is too high

## Note

This runbook will be deprecated in favor of the [gitaly pprof runbook](https://gitlab.com/gitlab-org/gitaly/-/issues/776) once `pprof` is available in production.

## Symptoms

* Alert on PagerDuty _Gitaly latency on <hostname> has been over 1m during the last 5m_
* General SLO alert on Gitaly service latency.
* This may also be affecting web / git-frontend latency.

## 1. Check the triage dashboard to assess the impact

* Visit the **[Triage Dashboard](https://dashboards.gitlab.net/d/RZmbBr7mk/gitlab-triage)**.
* Check the **Gitaly p95 latency** graph and identify the offending server or servers.
* Check repository CPU [cgroup](./gitaly-repos-cgroup.md)

## 2. Drill down

* Look at the [RPC time by project
  graph](https://log.gprd.gitlab.net/app/kibana#/visualize/edit/AW3YxmNOzxfRAgEaOtW6).
  Does it reveal any few projects that are responsible for RPC time?
* If a project is responsible for a lot of RPC time, filter the graph by that
  project and change the X-axis grouping to method.

## 3. Common causes and remedies

### PostUploadPack on popular project

This usually means that a lot of clients are fetching the project. Performance
issues here are usually transient.

### GetBlob on project

Open the [Rails request duration by controller per project
graph](https://log.gprd.gitlab.net/app/kibana#/visualize/edit/AW3Z_bgiQ7jyVXjiZ19E).
Change the project filter appropriately. If the RawController is using most
time, it's possible that the repo is being used as a static content backend.
This is often fine, but it's worth looking inside the repo using your admin
account to see what sort of files are being served up. Exercise judgement in
whether or not to block the account, notifying support and/or SecOps if you do.

## 4. Restart Gitaly

When you haven't found a cause for the saturation, and traffic doesn't seem to
be stabilizing again, you might want to restart the `gitaly` process.

To restart Gitaly, log into the affected server and follow one of the following
procedures.

NOTE: Be aware this will disrupt traffic to the Gitaly node. But considering the
node is saturated already, this might not be an issue.

### Restart Gitaly through gitlab-ctl

When you want to restart Gitaly, it's preferred to do this through `gitlab-ctl`:

```sh
sudo gitlab-ctl restart gitaly
```

This will ensure the minimum of downtime.

### Stop and start Gitaly

If you want to be sure all child `git` processes get drained you can consider to
stop Gitaly, but keep in mind this will block all traffic for a while.

In this case it's preferred to soft shutdown Gitaly. Shutting down Gitaly with a
`SIGABRT` signal, will make it print Goroutine information into the logs. To
soft shutdown Gitaly:

1. Elevate yourself to root with `sudo -i`
1. Find the process id of Gitaly with: `sudo gitlab-ctl status gitaly`. You can
   find the pid in the output:

        run: gitaly: (pid 828315) 12043s; run: log: (pid 4018578) 8456659s

1. Send SIGABRT to this process:

        kill -6 828315

1. Locate the log file at `/var/log/gitlab/gitaly/current`. Copy it to your
   machine and share it with the Gitaly engineers.

If these steps don't work, stop Gitaly through `gitlab-ctl`:

```sh
sudo gitlab-ctl stop gitaly
```

Now you can check with `ps` if any `git` process is running:

```sh
ps aux | grep git
```

If all is stopped, you can start Gitaly again:

```sh
sudo gitlab-ctl start gitaly
```
