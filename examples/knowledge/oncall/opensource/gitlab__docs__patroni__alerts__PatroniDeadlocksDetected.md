# PatroniDeadlocksDetected

## Overview

This alert fires when PostgreSQL reports a sustained, elevated number of **deadlocks** on the
node named in the alert (`{{ $labels.type }}` / `{{ $labels.fqdn }}`) — this can be the primary
or a replica.

A deadlock is when two transactions each hold a lock the other needs, forming a cycle where
neither can proceed. PostgreSQL detects this and aborts one transaction (it receives a
`deadlock detected` error); the other continues.

A single deadlock is usually harmless and self-healing — the aborted transaction is normally
retried by the application. This alert does **not** fire on one-off deadlocks; it fires only
when the rate stays elevated for 10 minutes, which points to a real, repeating locking
pattern (e.g. two code paths taking row locks in opposite order, or long / idle-in-transaction
sessions holding locks). It was created as a corrective action after
[INC-643](https://app.incident.io/gitlab/incidents/643), where automatic CI partition creation
deadlocked against the live workload and went unnoticed.

The Database team owns triage: use the dashboard to find which node, which workload, and the
exact conflicting statements, then involve the owning application/feature team to fix the
query or lock ordering.

## Services

- [Patroni Service](../README.md)

## Metrics

- Based on **`pg_stat_database_deadlocks`** — a cumulative, per-database counter of deadlocks
  PostgreSQL has detected.
- The alert evaluates `increase(pg_stat_database_deadlocks[5m])` across **all nodes**
  (primary and replicas both take real deadlocks) — i.e. the number of deadlocks in a
  rolling 5-minute window — and fires when that count stays above the cluster's threshold
  for **10 minutes** (`for: 10m`).
- Dashboard: [PostgreSQL Deadlocks](https://dashboards.gitlab.net/d/postgresql-deadlocks/postgresql-deadlocks)
  — choose the cluster in the `cluster` dropdown; every panel is broken out per node (`fqdn`).
- The threshold is **per cluster**, because baselines differ ~100×. Backtested over 7 days of
  production data:
  - `patroni` (main) — **5** deadlocks / 5 min (normal ~0)
  - `patroni-ci` — **3** / 5 min (normal ~0)
  - `patroni-registry` — **3** / 5 min (normal ~0)
  - `patroni-sec` — **50** / 5 min (routine, self-healing baseline: p99 ~21, peak ~112)
- Under normal conditions the deadlock panel is flat at 0 for patroni / patroni-ci /
  patroni-registry, and a low steady line for patroni-sec. The alert is `severity::4` and does
  not page.

## Alert Behavior

- Clears automatically once the deadlock rate drops below the cluster threshold. Silence only
  during a related, approved Change Request.
- Rare on patroni / patroni-ci / patroni-registry. patroni-sec has a routine, self-healing
  deadlock baseline from its normal write workload; its threshold (50) sits above that
  baseline, so it alerts only on a genuine spike. When it fires, investigate using the steps
  below.
- [Previous incidents for this alert](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?sort=created_date&state=all&label_name%5B%5D=a%3APatroniDeadlocksDetected)

## Verification

- Deadlock rate on the dashboard (pick the cluster):
  [PostgreSQL Deadlocks](https://dashboards.gitlab.net/d/postgresql-deadlocks/postgresql-deadlocks).
- The exact conflicting statements are only in the logs — the `deadlock detected` entry
  (GPRD / GSTG links on the dashboard's Root cause panel) lists both statements and the lock
  cycle.

## Recent changes

- Check recent [production change requests](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/?label_name%5B%5D=change)
  and application deploys for the affected feature category — a new query, migration, or
  changed lock order is a common trigger.

## Troubleshooting

Open the [PostgreSQL Deadlocks dashboard](https://dashboards.gitlab.net/d/postgresql-deadlocks/postgresql-deadlocks),
pick the affected cluster in the `cluster` (`$type`) dropdown, and read it top to bottom:
signal, then contention, then culprit, then logs. Every panel is per node (`fqdn`), so you
always know which host.

### Reading the dashboard

What each metric is, and what you should see:

- **Current primary node** — the host currently primary (`pg_replication_is_replica == 0`).
  Follows failovers automatically.
- **Deadlocks (5m increase)** — `increase(pg_stat_database_deadlocks[5m])`, the alert metric.
  Normal: flat 0 on patroni/ci/registry; a low steady line on patroni-sec. Dig in: any
  sustained line above this cluster's threshold (see Metrics). Tells you deadlocks are
  happening, how many, and on which node.
- **Transaction rate: commits vs rollbacks** — `rate(pg_stat_database_xact_commit[5m])` and
  `rate(pg_stat_database_xact_rollback[5m])`. Normal: rollbacks tiny next to commits. Dig in:
  rollbacks rising together with the deadlock line — a deadlock aborts a transaction, which
  counts as a rollback, so this corroborates the spike.
- **Rollback ratio** — rollbacks / (commits + rollbacks). Normal: well under ~0.1%. Dig in: a
  climbing ratio; it shows the rollback spike relative to traffic, so high volume can't hide
  it.
- **Idle-in-transaction sessions** — `pg_stat_activity_count{state="idle in transaction"}`.
  Normal: near 0. Dig in: any sustained count — these sessions hold locks while doing nothing
  and are the top deadlock precursor.
- **Lock wait events (rate)** — `rate(pg_wait_sampling_agg_count{wait_type="Lock"}[5m])` by
  `wait_event`. Normal: low / flat. Dig in: spikes, especially `transactionid` or `tuple`
  waits — these are the lock waits that build into deadlocks.
- **Locks held by mode** — `pg_locks_count` by `mode`. Normal: mostly `accesssharelock` /
  `rowexclusivelock`. Dig in: a surge in `exclusivelock` / `rowexclusivelock` — heavy
  write-lock contention.
- **Top workload by node / application / endpoint / command** —
  `pg_stat_activity_marginalia_sampler_active_count`. Normal: a familiar mix. Dig in: one
  endpoint or command clearly dominating — the likely culprit path.
- **Longest-running transaction age** —
  `pg_stat_activity_marginalia_sampler_max_tx_age_in_seconds`. Normal: seconds. Dig in:
  minutes or more — long transactions hold locks the whole time.
- **Root cause (Kibana links)** — direct `deadlock detected` log searches for GPRD and GSTG;
  the log entry has the actual conflicting statements (see below).

### Inspect live on the node

Deadlocks resolve instantly, so the specific deadlock is usually gone by the time you look —
but the underlying **contention** is often still visible. On the node from the alert
(`{{ $labels.fqdn }}` — this may be the primary or a replica):

    sudo gitlab-psql

Deadlock counter (per database, cumulative — always returns rows):

    SELECT datname, deadlocks FROM pg_stat_database
    WHERE datname NOT LIKE 'template%' AND datname <> '';

Who is blocking whom right now:

    SELECT pid, pg_blocking_pids(pid) AS blocked_by, wait_event_type, wait_event, state,
           now() - xact_start AS xact_age, query
    FROM pg_stat_activity
    WHERE cardinality(pg_blocking_pids(pid)) > 0
    ORDER BY xact_age DESC;

Idle-in-transaction sessions holding locks:

    SELECT pid, now() - xact_start AS xact_age, query
    FROM pg_stat_activity
    WHERE state = 'idle in transaction'
    ORDER BY xact_start;

The last two queries return rows only while there is active blocking or an open idle
transaction — an empty result is normal and just means there is no contention right now.

For a full blocking/blocked **tree** (which query is the root of the chain) and how to safely
cancel or terminate a blocker, see
[PostgreSQL locking → How to see locking and locked activity](../postgresql-locking.md#how-to-see-locking-and-locked-activity).

### Read the deadlock log entry (the smoking gun)

Metrics tell you that deadlocks happened and on which node; the PostgreSQL log tells you
exactly which two statements collided. Use the dashboard's Root-cause Kibana link (GPRD or
GSTG) for `deadlock detected`. A typical entry:

    ERROR:  deadlock detected
    DETAIL: Process 123 waits for ShareLock on transaction 456; blocked by process 789.
            Process 789 waits for ShareLock on transaction 654; blocked by process 123.
            Process 123: UPDATE ...
            Process 789: UPDATE ...
    CONTEXT: while updating tuple (x,y) in relation "some_table"

Read it as a cycle: process A holds what B needs and vice-versa. The two `Process ...:`
statements and the `relation` are what you hand to the app team.

### Involve the application team

The Database team identifies the deadlocking workload (the two statements from the log, plus
the dominating endpoint/command from the **Top workload** panel), then engages the owning
feature team with those statements to fix the root cause — usually by making both code paths
acquire locks in a consistent order, shortening/splitting a large transaction, or removing an
idle-in-transaction hold.

## Possible Resolutions

- Correct inconsistent lock ordering between two code paths so they acquire locks in the same
  order.
- Shorten or split large transactions (often a Sidekiq job) that hold locks too long.
- Eliminate `idle in transaction` sessions that hold locks.

## Escalation

- If the recipient of this alert cannot determine the cause of the deadlocks and correct it
  using the troubleshooting steps above, it may be necessary to escalate
- Slack channels where help is likely to be found: `#g_infra_database_reliability`

## Related Links

- [Related alerts](https://gitlab.com/gitlab-com/runbooks/-/tree/master/docs/patroni/alerts?ref_type=heads)
- [PostgreSQL locking and deadlocks](../postgresql-locking.md)
- [PatroniLongRunningTransactionDetected](PatroniLongRunningTransactionDetected.md)
