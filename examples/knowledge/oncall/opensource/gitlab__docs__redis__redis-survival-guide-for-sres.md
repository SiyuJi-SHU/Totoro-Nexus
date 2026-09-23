# A survival guide for SREs to working with Redis at GitLab

See also <https://docs.gitlab.com/development/redis/> which covers some of the same
ground, but with a developer orientation and the SRE-oriented [runbook](redis.md)

## What is Redis

Redis is a fast in-memory key-value store.   It offers a number of data types, from simple strings to sets, hashes,
or complex data types like HyperLogLog.  It is fundamentally single-threaded in its core loop, which keeps the
implementation simple and robust (e.g. tasks execute serially with no lock contention).  However, that means it is
constrained to the performance of a single CPU, and any slow task delays the start of other queued tasks.  We have had
scaling problems in the past on GitLab.com where the single CPU became the bottleneck, and our architectural design
has evolved to take that into account.

## Why do we use it?

We use it both as a cache (for performance savings) and to store various types of persistent data, either directly and
explicitly (typically from Rails), or implicitly by using Sidekiq or `Rails.cache`

Caching for performance is primarily to reduce the load on other systems, particularly Postgres and Gitaly which are often
bottlenecks (or single points of failure)  This can also save time in the web tier (e.g. rendering Markdown), but more
as a side-effect than the direct reason.

## Architecture

For gitlab.com we have 12 sets of Redis instances, each handling a distinct use case. Most are
Sentinel-based VMs or Redis Cluster, but two have since moved to GCP MemoryStore, where
failover, patching and backups are managed by GCP rather than by us:

| Role                                                                                                      | Nodes                            | Clients                                  | Sentinel?        | Persistence?                                            |
|-----------------------------------------------------------------------------------------------------------|----------------------------------|------------------------------------------|------------------|---------------------------------------------------------|
| Cache for `Rails.cache`                                                                                   | redis-cluster-cache-XX-YY        | Puma workers, Sidekiq workers            | No               | None                                                    |
| Cache for Chat ([To be decommissioned](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/19221)) | redis-cluster-chat-cache-XX-YY   | Puma workers                             | No               | None                                                    |
| Cache for Feature flag                                                                                    | redis-cluster-feature-flag-XX-YY | Puma workers                             | No               | None                                                    |
| Cache for Repository data                                                                                 | redis-repository-cache-XX        | Puma workers                             | Yes (localhost)  | None                                                    |
| Sidekiq job queues                                                                                        | redis-sidekiq-XX                 | Puma workers, Sidekiq workers            | Yes (localhost)  | RDB dump every 900 seconds                              |
| Persistent shared state                                                                                   | redis-XX                         | Puma workers, Sidekiq workers, Workhorse | Yes (localhost)  | RDB dump every 900 seconds                              |
| CI build trace chunks                                                                                     | memorystore-redis-tracechunks    | Puma workers (API), Sidekiq workers      | No (MemoryStore) | None (`allkeys-lru`)                                    |
| Sidekiq quarantine shard job queue                                                                        | memorystore-redis-sk-quarantine  | Puma workers, Sidekiq workers            | No (MemoryStore) | RDB snapshot hourly (gprd)                              |
| Ratelimiting (RackAttack/App)                                                                             | redis-cluster-ratelimiting-XX-YY | Puma workers                             | No               | None                                                    |
| Sessions                                                                                                  | redis-sessions-XX                | Puma workers                             | Yes (localhost)  | RDB dump every 900 seconds, but also an eviction policy |
| Latest WAL pointer                                                                                        | redis-db-load-balancing-XX       | Puma workers, Sidekiq workers            | Yes (localhost)  | RDB dump every 900 seconds, but also an eviction policy |
| Pub/Sub                                                                                                   | redis-pubsub-XX                  | Puma workers, Workhorse                  | Yes (localhost)  | No                                                      |

The split is *largely* a form of functional partitioning for scalability (see single threaded comments above), but also
because the application expects the non-cache instances to persist data across failures or restarts so those instances
must write data to disk periodically (in addition to replication/failover capability). It's not a full DBMS with
guaranteed write semantics (and performance implications to match), but it is sufficiently persistent that there wouldn't
be enormous implications if we did have a substantial failure and had to go back to the RDB files on disk.  However,
having this level of persistence for our large cache (currently ~60GB of cache) would be expensive in computation and I/O,
for insufficient benefit.  While we don't want to lose our cache regularly, we can certainly survive an occasional loss
in unlikely circumstances (all 3 nodes die at once, which would probably mean all or much of the rest of our
infrastructure is also down or badly affected).

At the time (mid 2021) we chose to split CI build trace chunks into it's own instance, CI trace chunks were responsible for roughly 60% of the
data throughput into/out of the shared state redis, and 16% of Redis calls (see <https://gitlab.com/gitlab-org/gitlab/-/issues/327469#note_556531587>)
which was sufficient reason for the split, along with the distinctive usage profile (transient data on its way to permanent storage).

When we split out Ratelimiting (latter-half of 2021) this was for CPU saturation; the cache instance was peaking at a little over
90% CPU, and we knew from when we [enabled RackAttack in November 2020](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/3034)
that it is responsible for at least 25% (absolute) of the CPU utilization, so splitting this out gives the cache instance room
to breath.  Note that the [data usage](https://gitlab.com/gitlab-com/gl-infra/observability/team/-/issues/1246#sizing) is tiny.

When we split out Sessions (very late 2021), this was for CPU saturation (antipicated something like 20% absolute savings)
and also to separate a workload that may be problematic for Redis Cluster, unblocking perhaps moving the persistent Redis
to Cluster in the future.

### CPUs

Redis VMs were the first nodes we switched to from N1 to '[C2](https://docs.cloud.google.com/compute/docs/machine-resource#c2_machine_types)'
node types for the best raw single-threaded CPU performance.  This [halved](https://gitlab.com/gitlab-com/gl-infra/observability/team/-/issues/230#note_312403063)
the CPU usage on our sidekiq cluster, and [almost the same](https://gitlab.com/gitlab-com/gl-infra/production-engineering/-/issues/9636)
on the cache cluster.   Just in case you were in any doubt as to how important the single-threaded CPU performance was
to redis.

Redis 6 has [multithreaded I/O](https://github.com/redis/redis/pull/6038/files) which helps by moving
some network I/O work to non-core threads, but the core work must still occur on the main thread, so it is only a
mitigation.

## High Availability

For each cluster we run 3 nodes, using [Redis Sentinel](https://redis.io/topics/sentinel) to manage failover.  All
traffic goes through the currently active primary, and writes are replicated to the two replicas asynchronously.  If the
primary fails (as determined by Sentinel), one of the replicas is promoted to primary.

Clients are configured to connect to the sentinels which will then direct them to the current primary; there is no load
balancer involved in this path.  Failover is automatic (handled by Sentinel) and results in clients being disconnected
from the primary and then reconnecting via the sentinels again.  It requires no operator intervention under normal circumstances

The configuration is subtly different across the clusters, for historical reasons; the persistent and sidekiq clusters
have sentinel running on the VMs alongside redis, whereas the cache cluster uses a distinct set of sentinel VMs.
<https://gitlab.com/gitlab-com/gl-infra/production-engineering/-/issues/11389> records the desire to clean this up.

### Node failure

If one or more replicas fail, nothing of note occurs to Redis availability.  The primary continues to be the primary,
and any remaining replicas continue to replicate.  We might receive various alerts (depending on the means of failure)
but gitlab.com will remain operational.  When the replicas come back online, they will be resynchronized with the primary

If the primary fails, sentinel notices and actively manages the failover process, promoting a remaining replica to
primary and re-establishing replication off the new primary.  When the ex-primary comes back online, it will be reconfigured
as a replica by sentinel and pointed to the current primary.

If all 3 nodes fail at once, everything grinds to a halt and gitlab.com will be down.

#### Slowness can cause failover

An important special case of a node being "down" is saturation-induced slowness.

Extremely slow response time from the Redis primary can cause Sentinel health checks to timeout, leading Sentinels
to force a failover.  In this case, the old primary host and its redis-server process are still running, but
redis-server is being overwhelmed by its workload.

Redis handles its clients' requests serially.  Normally it is able to service all active clients' requests
with little delay.  However, if the workload reaches the saturation point (100% of 1 vCPU), then a backlog of
client requests can potentially accumulate.

Redis treats its Sentinels like any other client.  Sentinels periodically send a cheap `PING` request, and if the
monitored Redis instance does not respond within Sentinel's configured timeout (`down-after-milliseconds` = 10 seconds),
then that Sentinel considers the unresponsive Redis instance to be "subjectively down".  When a quorum of Sentinels
agree (2 of 3), that unresponsive Redis instance is considered "objectively down".  If that unresponsive node
is the primary and a majority of Sentinels are up, the Sentinels initiate a failover.

Duing such a slowness-induced failover event, it is possible for the overwhelming workload to follow the failover
to the new primary node.  It takes time to recover the old primary as a working replica, and during that time it
is not treated as a failover candidate.  So in a 3-node replication set, at most 2 failovers can occur in
rapid succession.

For reference, historically we have observed a few patterns of client behavior that can lead to severe spikes
in Redis response time:

* *Periodic microbursts:* Large spikes of concurrent requests from many clients can induce CPU saturation.
  When thousands of clients participate in such microbursts, the unlucky clients at the tail of the queue can
  stall for several thousand of milliseconds.  For example, this can happen when many clients synchronously expire
  their local cached response to some very frequent Redis query (e.g. find which GitLab feature flags are enabled).
* *Large requests:* Some Redis operations can potentially be much more expensive than most (e.g. adding many elements
  to a large set via a single atomic `SADD` request).  Activity at higher layers in the application stack can
  sometimes increase the frequency of these 10+ millisecond operations, implicitly causing response time spikes
  for other clients that have to wait in the queue.

### Network partition

For the clusters with sentinel on the VM alongside redis, a simple network partition results in 2 nodes thinking the 3rd
is down, and the 3rd node thinking the other 2 are down.

If the primary is in the 2-node side, no failover will occur on Redis; when the partition resolves, the replica that was
alone will resynchronize replication.

If the primary is on the 1-node side, then Sentinel on the two-node side will retain quorum and initiate a failover
to one of the nodes on the 2-node side.  The old primary will *continue* to be a primary for any clients that can talk
to it, but when the network partition heals it will be demoted to a replica and any writes to it during the partition
will be lost.  See <https://redis.io/topics/sentinel#example-2-basic-setup-with-three-boxes>, in particular
`min-replicas-to-write` for more depth on this and the related tradeoffs

A 3-way partition will result in no changes; no sentinel will have quorum to force a failover, one primary will remain
and any clients that cannot get to the primary will be inoperable.

For the cache cluster it depends on the nature of the network partition.  At core it is based on what nodes the
sentinels can see are up, both between themselves for determining a quorum to make a failover decision, and which redis
nodes can be contacted from the sentinels for selecting a primary.  In the most "likely" case, where connectivity between AZs goes down, it is
equivalent to the behavior of the persistent clusters.  A partition between only the sentinels will have no effect.
A partition between only the redis nodes will break replication to the separated replica(s), but otherwise have no effect on
the operability of the cluster.  A partition between sentinels and redis nodes will cause a failover if the loss of
communication is between 2 sentinels (that can thus maintain authoritative quorum) and the current primary, as long as
the sentinels can still talk to a replica.  Consideration of more complicated failure scenarios is left as an exercise
for the reader.

### Persistence Guarantees

Redis replication is asynchronous (barring the explicit use of the [wait](https://redis.io/commands/wait) command by the
application, which we do not currently use).  This means any uncontrolled failover can result in the loss of some writes
accepted by the lost primary.  This is of very little concern for the cache cluster, but could be lightly problematic for
the others.  For sidekiq it likely means we'll not run jobs that should be run, or that some jobs will run twice.  For
the persistent cluster, results may vary (it will be deeply dependent on the specific key and how the application behaves).
For tracechunks we may lose the output of some CI jobs.

On the non-cache clusters, the data is saved to disk (RDB format) every 900 seconds (15 minutes) as long as at least
1 key has changed.  In the event that all 3 nodes fail at once and the in-memory contents is lost, we may lose up to
15 minutes of writes.

As noted elsewhere in this document, the cache clusters do not regularly write to disk (only indirectly as part of a
replication resynchronization).  If all 3 nodes fail at once, the entire cache will be wiped; this is not ideal but
acceptable as it will be refilled on demand.  For ratelimiting, the data has only short term usefulness anyway (generally
1 minute, with some up to 3 minutes) so complete loss is not significant.

## What do we store?

<https://gitlab.com/gitlab-com/gl-infra/observability/team/-/issues/419> contains some summary analysis of the keyspace across
the persistent and cache instances, as at July 2020.  This will change over time as the code base evolves, but the link
provides some indication what we're storing (it's not as wide a range of things as you might expect in the
persistent instance).

The data stored in the sidekiq instances is not really under our control (it's whatever sidekiq needs to do its job).
Some details on that are available in [Sidekiq Survival Guide for SREs](../sidekiq/sidekiq-survival-guide-for-sres.md).

The data in the tracechunks instance is exclusively the output of CI jobs on its way into Object Storage (received by
and API request, written into Redis, and a Sidekiq job immediately scheduled to asynchronously write it to Object Storage);
while important, it is also extremely transient, and under normal circumstances there should be no significant build up
of data in this instance.  Any such build up implies a problem (Sidekiq not processing, perhaps?) and so we have
particularly low alert thresholds for memory saturation on this instance.  At the time we chose to split this instance
from the primary persistent (shared state) instance (mid 2021), CI trace chunks were responsible for roughly 60% of the
data throughput into/out of the shared state redis, and 16% of Redis calls.

The data in the ratelimiting instance is a set of keys identifying actors (typically users, IP addresses, or in some
cases projects) and activities, with the value being the count of usage in the current period (the period is also encoded
into the key name).  TTLs are used to expire these automatically so there's no manual cleanup by clients, it is automatic
and internal to Redis.

The data in the sessions instance is exclusively that related to web sessions.

## Clients

Most of the clients are Ruby, specifically Rails code on the web, api, git, and sidekiq nodes.  Workhorse (in Go) also
[uses](https://gitlab.com/gitlab-org/gitlab-workhorse/-/blob/master/README.md#redis) Redis to do long polling for CI build requests.

Rails uses the generic [connection_pool](https://github.com/mperham/connection_pool) to maintain long running connections
which are used by threads/workers as necessary, which is particularly important as redis is typically expected to be
very fast and low latency.

See [High Availability](#high-availability) for some comments regarding connections via sentinels.

## What about redis cluster?

[Redis Cluster](https://redis.io/topics/cluster-spec) is a Redis feature that lets you horizontally shard data across
multiple machines. If any one of our Redis clusters grows too large to fit in memory on a single machine or such that
the single-threaded CPU becomes a hard limit, then Redis Cluster is a possible mitigation. Manually sharding the data
is an alternative approach, e.g. having more than one cache cluster and dividing the types of keys up in some way using
application controlled logic, or splitting the sidekiq processing onto multiple clusters.  Neither option is low effort.

See the [Redis Cluster documentation for more details](../redis/redis-cluster.md).

See:

1. <https://gitlab.com/gitlab-com/gl-infra/production-engineering/-/issues/9788>
1. <https://gitlab.com/gitlab-com/gl-infra/observability/team/-/issues/305>

## Maintanence

### Reconfigure

In order to apply config changes, we need to run a `sudo gitlab-ctl reconfigure`. However, this operation requires coordination, since it will usually restart the redis process.

In order to ensure we remain highly available, this operation should only be applied to redis replicas. In order to apply it to a primary, a failover should first be performed, turning it into a replica.

This process is automated via a script in the `runbooks` repository:

```
scripts/omnibus-redis-reconfigure.sh gstg redis-cache # if the instance is using omnibus-gitlab
scripts/redis-reconfigure.sh gstg redis-cache # if the instance is using the gitlab-redis cookbook
```

For Redis Cluster, the reconfiguration process is similar. But due to the varied cluster topologies, fetch the list of node FQDNs by run the following `knife` command in [chef-repo](https://gitlab.com/gitlab-com/gl-infra/chef-repo/):

```
knife search -i 'roles:<ENV>-base-db-redis-cluster-<INSTANCE NAME>' | sort -V  > tmp.txt

# for example
knife search -i 'roles:pre-base-db-redis-cluster-cache' | sort -V  > tmp.txt
```

Run the reconfiguration script with the file path as an argument.

```
scripts/redis-cluster-reconfigure.sh path/to/tmp.txt

```

## Debugging and Diagnosis

So you think something is wrong with Redis, either as a cause or a symptom.  What can you do to find out more?

### Identifying the primary

While the shell prompt on the servers tells you if it is the primary or a replica, you have to potentially shell into
multiple nodes to find the primary.

Thankfully, Prometheus/Thanos has this information already, and you can find the current primary for all 3 clusters [here](https://thanos.gitlab.net/graph?g0.range_input=1h&g0.max_source_resolution=0s&g0.expr=sum%28redis_instance_info%7Brole%3D%22master%22%2C%20type%3D~%22redis.*%22%2C%20environment%3D%22gprd%22%7D%29%20by%20%28fqdn%29&g0.tab=1)

### Basic stats

There is 1 core dashboard for redis, with a variant for each cluster:

* [Cache](https://dashboards.gitlab.net/d/redis-cluster-cache-main/redis-cluster-cache-overview?orgId=1)
* [ChatCache](https://dashboards.gitlab.net/d/redis-cluster-chat-cache-main/redis-cluster-chat-cache-overview?orgId=1) ([To be decommissioned](https://gitlab.com/gitlab-com/gl-infra/production/-/issues/19221))
* [DbLoadBalancing](https://dashboards.gitlab.net/d/redis-db-load-balancing-main/redis-db-load-balancing-overview?orgId=1)
* [FeatureFlag](https://dashboards.gitlab.net/d/redis-cluster-feature-flag-main/redis-cluster-feature-flag-overview?orgId=1)
* [Persistent/shared](https://dashboards.gitlab.net/d/redis-main/redis-overview?orgId=1)
* [Pubsub](https://dashboards.gitlab.net/d/redis-pubsub-main/redis-pubsub-overview?orgId=1)
* [Ratelimiting](https://dashboards.gitlab.net/d/redis-cluster-ratelimiting-main/redis-cluster-ratelimiting-overview?orgId=1)
* [RepositoryCache](https://dashboards.gitlab.net/d/redis-repository-cache-main/redis-repository-cache-overview?orgId=1)
* [Sessions](https://dashboards.gitlab.net/d/redis-sessions-main/redis-sessions-overview?orgId=1)
* [Sidekiq](https://dashboards.gitlab.net/d/redis-sidekiq-main/redis-sidekiq-overview?orgId=1)
* [Tracechunks](https://dashboards.gitlab.net/d/redis-tracechunks-main/redis-tracechunks-overview?orgId=1)

Note that many of the panels are have both Primary and Secondary variants; because only the primary is active, usually
only the primary graphs matter *and* the secondaries should be pretty quiet (other than some housekeeping/analysis
operations; see [Bigkeys](#bigkeys)).  High activity on the secondaries shouldn't ever affect

Assuming "look for changes" is something you'll do anyway, the following are some particularly critical details:

#### Redis Primary CPU

Given the single-threaded CPU nature, the 'Saturation' panel (rightmost of the first row, at this writing) has one
critical line: `redis_primary_cpu_component`.  This is the CPU used by the redis process, and 100% is the absolute
hard limit, so if this is 'high' we may have a problem.  Some gut feel numbers: above 50% is currently ok but interesting,
75% is getting a little worrisome if it's sustained and time we should be thinking about sharding or other approaches,
and above 85% it may be too late or else something weird and transient is going on (that could be what you're looking for)

#### Operation Rates

Thankfully we can get metrics per-command (operation), so you can see which specific commands are being called the most.
<https://redis.io/commands> provides excellent documentation of those commands, including time complexity (Big-O notation)
for the operation, so you can evaluate if it's one of the simple quick ones like GET (O(1)) or something a little more
complicated like LREM (O(N+M)).

These are obviously high level metrics and do not drill down by keys.

#### Operation Latency

This shows you which commands are most expensive either individually (Average Operation Latency) or in total impact on
the server (Total Operation Latency).  INFO is quite expensive so often shows up in the Average, but is called at a
very low rate so is barely noticeable in Total.

#### Scary but not bad

1. On the sidekiq dashboard in particular, you may notice 'Blocked Clients' is high.  This is normal; sidekiq uses
`brpop` to wait for work, which is a blocking call.  We fully expect that large number of clients will be in that state
at any point in time, waiting for work to be dispatched.  They're not 'Blocked' from doing something important; as always
*changes* may be interesting, but steady state high numbers are not.  However, non-sidekiq Redis's should *not*
have lots of blocked clients on a regular basis.

### Slowlogs

Any requests taking longer than a configurable limit (10ms as currently configured) are logged to the rotating slowlog
buffer within redis (128 entries, currently configured).  You can view these by in two ways

#### Elasticsearch

Slowlogs are captured using a fluentd plugin and ingested into ELK.  This link may work: <https://log.gprd.gitlab.net/goto/954807baeadd9bccd997cb95d3d33fcc>
but if not, in the redis index (`pubsub-redis-inf-gprd`) search for `json.tag: "redis.slowlog"`.  Normally we see only
a handful of these entries, often in small batches of related commands + keys, and at a rate of maybe 20 an hour on
average.  An increase in these would be very interesting, particularly if it was a specific command or set of keys.

#### CLI

You can obtain the 10 most recent entries:

```sudo gitlab-redis-cli
127.0.0.1:6379> SLOWLOG GET 10
```

See <https://redis.io/commands/slowlog> for details of the output format.

### ElasticSearch analysis

Rails request logs in ElasticSearch (I want to say "all", but I'm not sure that's the case) will have fields relating
to redis usage.  These are:

* calls
* duration_s
* read_bytes
* write_bytes

There is one set for each of the clusters, with a different prefix e.g. `redis` (persistent), `redis_cache_`,
`redis_queues` (sidekiq), `redis_tracechunks`, `redis_ratelimiting` and `redis_sessions`.  You can perform
the usual sort of visualizations and explorations that you might on other numeric fields, e.g. to find the
top 20 Controllers by average number of calls to Redis.

This would be an excellent approach to diagnosing the source of changes in Redis usage, although if the change was beyond
our log retention (7 days) you can only really reason about the current state (looking for outliers), which constrains
the usefulness.

### Monitor commands

It might be useful to observe live traffic.  SSH to the primary redis node (see [Identifying the primary](#identifying-the-primary) for
a quick way to do that) and run:

```
sudo gitlab-redis-cli monitor
```

This will show you not only the commands (including keys + values) being run, but also which client they are from.  Use
the usual linux tools like grep and awk to further analyze the traffic on demand.

### Flamegraphs

Every hour, on all redis nodes, we capture a perf trace of the redis server for 5 minutes, and put that, along with a
pre-generated stack flamegraph generated from the profile, into a GCS bucket, retaining them for 30 days.

We have not as yet written an ergonomic way to view these, but if you have access to the bucket you can go trawling around.
Possible uses include if an incident crosses the time when the profile was being captured (starts at XX:22:37 every hour
by default), or more likely if you are looking at changes in behavior over time.

The bucket is `gitlab-ENV-redis-analysis`

Reference: <https://gitlab.com/gitlab-com/gl-infra/observability/team/-/issues/321>

### Bigkeys

It can be interesting to see what keys in redis are the biggest, either by unit size (number of entries in a set, or list)
or by memory.  Thankfully redis-cli can show you this, but it's expensive as it scans the entire keyspace, so you shouldn't
run that on a primary, and historical data is also interesting.  Therefore we have a daily job that runs on a single
replica that runs the bigkeys and memkeys operations using redis-cli, extracts the data into a more re-usable JSON
format and puts that JSON into a GCS bucket.

In the repo (see below) there is a CLI curses-based tool to obtain and display that data with time-based scrolling
using the arrow keys.  See the README in the project for how to run that; it assumes you have a fairly standard (for an SRE)
set of GCP read access with your GCP credentials set up, i.e. if you can do `gsutil ls gs://gitlab-gprd-redis-analysis/bigkeys`
and it shows the files, then the reporting tool should Just Work™.

Reference: <https://gitlab.com/gitlab-com/gl-infra/redis_bigkeys>

#### Running on demand

The automated job above actually collects two views, the output of redis-cli with `--bigkeys` and the output with `--memkeys`.

`--bigkeys` looks at data in terms of the number of 'entries' in a key, e.g. for lists/sets/hashes, how many entries in those
structures.  It is a view of the *complexity* of the data which affects the performance of redis in reading and manipulating
the data structures, but not the total memory in use.

To get that, you use `--memkeys`, which uses the [MEMORY USAGE](https://redis.io/commands/memory-usage) command to find out
exactly how much data is in use by each key.  However there is a catch: `MEMORY USAGE` has an optional `SAMPLES` argument, that
defaults to 5.  For each data-structure key (list/set/hash etc), it samples that many entries and assumes the average of that sample
is the average of the total entries under that key.  This can be very wrong if the size distribution is heavily skewed and
the sample count is low.  You can provide an alternative sample count with `--memkeys-samples`, and I would generally recommend you do
so, specifically setting it to `-1` which forces all entries to be sampled (i.e. a fully accurate count, *not* a statistical sampling).

You might think you should use the value 0, but the CLI tool uses that internally to mean 'use the default', and -1 works around that.

Also, you might think that this would significantly increase the runtime, but in practice at Gitlab.com scale in early 2021, it adds
less than 10% runtime to the memkeys invocation

Here's how to run them directly:

```sudo gitlab-redis-cli --bigkeys```

```sudo gitlab-redis-cli --memkeys --memkeys-samples -1```
