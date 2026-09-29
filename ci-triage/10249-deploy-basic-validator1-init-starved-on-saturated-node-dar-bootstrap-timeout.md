# 10249 - deploy_basic on cimain: validator1's validator-app never finishes init; three validator-apps packed onto one CPU-saturated node, and validator1's DAR-upload bootstrap aborts after 5 min, restarting it forever (CircleCI build 507404)

New, infra (family J): no code regression. CircleCI workflow `deploy_basic`, job `deploy_cluster`, step `Apply Pulumi
configuration to cluster` (build 507404, step 114), cluster cimain, splice `0.9.0-snapshot.20260929.3903.0.v6b4c166b`
(main 6b4c166b71). Six `pulumi up` attempts; from attempt 4 on only `validator1/validator-validator1` fails its 600 s
Helm wait. Its validator-app pod ran on node `gke-cn-cimainnet-cn-apps-node-pool-hd-1465684f-k48f` together with the
splitwell and sv-3 validator-apps, splitwell-app and sv-da-1's sequencer and mediator. That node sat at 69-105 % of
allocatable CPU from 17:45 to 18:40 while every other node was at 2-9 %. On k48f the startup script took 357-362 s to
start (normally 8-14 s) and the `Getting BFT scan connection` init step took 21-27 min (normally 30-65 s); the same build
on node dkj9 (sv-1, sv-2, sv-da-1) was normal. splitwell and sv-3 simply started late and recovered by attempt 4.
validator1 is the only validator with `appDars`, so its `bootstrap.sc` waits `waitForInitialization(5 min)` before
uploading DARs; on k48f init never finishes within that, the script throws, the app shuts down and the container
restarts, five times between 17:40 and 18:45.

- Build: CircleCI build 507404 step 114 (`deploy_basic` / `deploy_cluster`), log `log/10249/build_507404_step_114_container_0.txt`
  (supplied by the user). Deployed splice 6b4c166b71 ("Bump vitest to v4 (#7522)").
- Runtime canton: 3.6.0-snapshot.20260928.20326.0.v5616afeb (`git show 6b4c166b71:nix/canton-sources.json | grep -m1 version`).
- Component: infra (GKE node packing and CPU requests) plus deployment robustness (`cluster/images/validator-app/bootstrap.sc`).
- Previous passing deploy: splice 5fb974056a (user report); the logs below also show clean deploys of 1293c69b23 at 15:xx.

## 0. Fetching the cluster logs (run on a host with gcloud access to da-cn-ci)

The cluster was reset after the build, so everything comes from Cloud Logging and Cloud Monitoring. The first script
wrote into `log/10249/10249/`, the others next to themselves in `log/10249/`.

```
#!/usr/bin/env bash
# Run from the splice repo root on a host with gcloud access to da-cn-ci.
set -euo pipefail
P=da-cn-ci
D=log/10249
W='timestamp>="2026-09-29T17:25:00Z" AND timestamp<="2026-09-29T18:45:00Z"'
K="resource.type=\"k8s_container\" AND resource.labels.cluster_name=\"cn-cimainnet\""

gcloud logging read "$W AND logName=\"projects/$P/logs/events\" AND jsonPayload.involvedObject.namespace=\"validator1\"" \
  --project $P --order=asc --limit=5000 --format=json | gzip > $D/events-validator1.json.gz

gcloud logging read "$W AND $K AND resource.labels.namespace_name=\"validator1\" AND severity>=WARNING" \
  --project $P --order=asc --limit=50000 --format=json | gzip > $D/validator1-warn.json.gz

gcloud logging read "$W AND $K AND resource.labels.namespace_name=\"validator1\" AND resource.labels.container_name=\"validator-app\"" \
  --project $P --order=asc --limit=200000 --format=json | gzip > $D/validator-app.json.gz

gcloud logging read "$W AND logName=\"projects/$P/logs/events\" AND jsonPayload.involvedObject.namespace=\"splitwell\"" \
  --project $P --order=asc --limit=5000 --format=json | gzip > $D/events-splitwell.json.gz

ls -la $D
```

```
#!/usr/bin/env bash
set -euo pipefail
P=da-cn-ci
D=$(cd "$(dirname "$0")" && pwd)
M='(jsonPayload.message:"app initialization: " OR jsonPayload.message:"Starting Splice version" OR jsonPayload.message:"Running script" OR jsonPayload.message:"=== Bootstrapping application ===")'

gcloud logging read "timestamp>=\"2026-09-25T00:00:00Z\" AND timestamp<=\"2026-09-29T19:00:00Z\" AND resource.type=\"k8s_container\" AND resource.labels.cluster_name=\"cn-cimainnet\" AND resource.labels.container_name=\"validator-app\" AND $M" \
  --project $P --order=asc --limit=100000 --format=json | gzip > $D/init-steps-cimain-validators.json.gz

gcloud logging read "timestamp>=\"2026-09-29T17:25:00Z\" AND timestamp<=\"2026-09-29T19:00:00Z\" AND resource.type=\"k8s_container\" AND resource.labels.cluster_name=\"cn-ciperiodicnet\" AND resource.labels.container_name=\"validator-app\" AND $M" \
  --project $P --order=asc --limit=100000 --format=json | gzip > $D/init-steps-ciperiodic-validators.json.gz

ls -la $D
```

```
#!/usr/bin/env bash
set -euo pipefail
P=da-cn-ci
D=$(cd "$(dirname "$0")" && pwd)
S=2026-09-29T17:25:00Z
E=2026-09-29T18:50:00Z

gcloud logging read "timestamp>=\"$S\" AND timestamp<=\"$E\" AND logName=\"projects/$P/logs/events\" AND resource.labels.cluster_name=\"cn-cimainnet\" AND (jsonPayload.reason=\"Scheduled\" OR jsonPayload.involvedObject.kind=\"Node\")" \
  --project $P --order=asc --limit=20000 --format=json | gzip > $D/events-cimain-scheduled-and-nodes.json.gz

T=$(gcloud auth print-access-token)
q() {
  curl -sS -G -H "Authorization: Bearer $T" "https://monitoring.googleapis.com/v3/projects/$P/timeSeries" \
    --data-urlencode "filter=$1" \
    --data-urlencode "interval.startTime=$S" --data-urlencode "interval.endTime=$E" \
    --data-urlencode "aggregation.alignmentPeriod=60s" --data-urlencode "aggregation.perSeriesAligner=$2"
}
q 'metric.type="kubernetes.io/node/cpu/allocatable_utilization" AND resource.labels.cluster_name="cn-cimainnet"' ALIGN_MEAN \
  | gzip > $D/metrics-node-cpu-allocatable-utilization.json.gz
q 'metric.type="kubernetes.io/container/cpu/core_usage_time" AND resource.labels.cluster_name="cn-cimainnet" AND resource.labels.container_name="validator-app"' ALIGN_RATE \
  | gzip > $D/metrics-validator-app-cpu-cores.json.gz

ls -la $D
```

Note: the events filter in the first script does not pin the cluster; `cn-ciperiodicnet` also has `validator1` and
`splitwell` namespaces, so every events query below filters `resource.labels.cluster_name` or the node name.

## 1. The step: six attempts, validator1 fails every one

Attempt 1 fails on sv-da-1's sv-app and cancels the rest; attempts 2-3 time out on the sv-3, splitwell and validator1
validators; attempts 4-6 on validator1 only. The Slack retry timestamps are 17:44:28, 17:56:00, 18:07:31, 18:19:18,
18:31:07 UTC (`date -u -d @<ts>`).

```
sed -E 's/\x1b\[[0-9;]*m//g; s/<\{%[a-z0-9 ]*%\}>//g' log/10249/build_507404_step_114_container_0.txt \
  | grep -a -E '^Ran [0-9]+ operations|failed to initialize completely|"ts":"[0-9]+' \
  | sed -E 's/Use Helm CLI to investigate: failed to become available within allocated timeout. //; s/^.*"ts":"([0-9]+)\..*/slack retry message ts=\1/' | uniq
```
```
	* Helm release "sv-da-1/sv-app" was created, but failed to initialize completely. Error: Helm Release sv-da-1/sv-app: context deadline exceeded
	* Helm release "splitwell/validator-splitwell" was created, but failed to initialize completely. Error: Helm Release splitwell/validator-splitwell: context canceled
	* Helm release "validator1/validator-validator1" was created, but failed to initialize completely. Error: Helm Release validator1/validator-validator1: context canceled
	* Helm release "sv-3/validator-sv-3" was created, but failed to initialize completely. Error: Helm Release sv-3/validator-sv-3: context canceled
	* Helm release "splitwell/validator-splitwell" was created, but failed to initialize completely. Error: Helm Release splitwell/validator-splitwell: context canceled
slack retry message ts=1790703868
	* Helm release "sv-3/validator-sv-3" failed to initialize completely. Error: Helm Release sv-3/validator-sv-3: context deadline exceeded
	* Helm release "validator1/validator-validator1" failed to initialize completely. Error: Helm Release validator1/validator-validator1: context deadline exceeded
	* Helm release "sv-3/validator-sv-3" failed to initialize completely. Error: Helm Release sv-3/validator-sv-3: context deadline exceeded
	* Helm release "splitwell/validator-splitwell" failed to initialize completely. Error: Helm Release splitwell/validator-splitwell: context deadline exceeded
	* Helm release "validator1/validator-validator1" failed to initialize completely. Error: Helm Release validator1/validator-validator1: context deadline exceeded
	* Helm release "splitwell/validator-splitwell" failed to initialize completely. Error: Helm Release splitwell/validator-splitwell: context deadline exceeded
Ran 11 operations. 3 failed: up-sv-sv-3, up-organization/validator1/validator1.cimain, up-organization/splitwell/splitwell.cimain
slack retry message ts=1790704560
	* Helm release "sv-3/validator-sv-3" failed to initialize completely. Error: Helm Release sv-3/validator-sv-3: context deadline exceeded
	* Helm release "splitwell/validator-splitwell" failed to initialize completely. Error: Helm Release splitwell/validator-splitwell: context deadline exceeded
	* Helm release "validator1/validator-validator1" failed to initialize completely. Error: Helm Release validator1/validator-validator1: context deadline exceeded
	* Helm release "splitwell/validator-splitwell" failed to initialize completely. Error: Helm Release splitwell/validator-splitwell: context deadline exceeded
	* Helm release "validator1/validator-validator1" failed to initialize completely. Error: Helm Release validator1/validator-validator1: context deadline exceeded
Ran 11 operations. 3 failed: up-sv-sv-3, up-organization/validator1/validator1.cimain, up-organization/splitwell/splitwell.cimain
slack retry message ts=1790705251
	* Helm release "validator1/validator-validator1" failed to initialize completely. Error: Helm Release validator1/validator-validator1: context deadline exceeded
Ran 11 operations. 1 failed: up-organization/validator1/validator1.cimain
slack retry message ts=1790705958
	* Helm release "validator1/validator-validator1" failed to initialize completely. Error: Helm Release validator1/validator-validator1: context deadline exceeded
Ran 11 operations. 1 failed: up-organization/validator1/validator1.cimain
slack retry message ts=1790706667
	* Helm release "validator1/validator-validator1" failed to initialize completely. Error: Helm Release validator1/validator-validator1: context deadline exceeded
Ran 11 operations. 1 failed: up-organization/validator1/validator1.cimain
```

## 2. validator1's validator-app: every boot stalls at the same init step until the bootstrap timeout

Pod `validator-app-67667cc4b4-tx2rw`, scheduled on k48f at 17:38:49. Each boot reaches `Getting BFT scan connection
started`, then the same thread logs nothing for 15-21 min; the bootstrap script's 5-minute `waitForInitialization`
has expired by the time the init thread moves on, so the app shuts down and the container restarts.

```
zcat log/10249/10249/validator-app.json.gz | python3 -c '
import json,sys,re
p=re.compile(r"Starting Splice version|Running script|=== Bootstrapping application|Waiting for validator to finish|Getting BFT scan connection|Retrieving key validator_scan|Timeout while waiting|Shutting down\.\.\.")
for e in json.load(sys.stdin):
    j=e.get("jsonPayload") or {}; m=j.get("message") or ""
    if p.search(m): print(e["timestamp"][11:23], j.get("thread_name","")[:16].ljust(16), m[:110])'
```
```
17:40:38.054 main             Starting Splice version 0.9.0-snapshot.20260929.3903.0.v6b4c166b
17:42:37.360 main             Running script Some(/app/bootstrap-entrypoint.sc) (SHA-256:986a81c3c0a8...)
17:43:22.402 canton-env-ec-69 validator_backend app initialization: Getting BFT scan connection started
17:48:39.158 main             === Bootstrapping application ===
17:48:39.784 main             Waiting for validator to finish init...
18:04:19.804 canton-env-ec-69 Retrieving key validator_scan_internal_config_key
18:04:20.753 main             Timeout while waiting for initialization of validator_backend
18:04:20.789 Thread-1         Shutting down...
18:04:28.669 canton-env-ec-46 validator_backend app initialization: Getting BFT scan connection finished after PT21M6.267017961S
18:04:52.201 main             Starting Splice version 0.9.0-snapshot.20260929.3903.0.v6b4c166b
18:06:09.786 main             Running script Some(/app/bootstrap-entrypoint.sc) (SHA-256:986a81c3c0a8...)
18:06:37.098 canton-env-ec-63 validator_backend app initialization: Getting BFT scan connection started
18:10:40.499 main             === Bootstrapping application ===
18:10:40.759 main             Waiting for validator to finish init...
18:22:37.229 canton-env-ec-63 Retrieving key validator_scan_internal_config_key
18:22:38.158 main             Timeout while waiting for initialization of validator_backend
18:22:38.201 Thread-1         Shutting down...
18:22:43.108 canton-env-ec-63 validator_backend app initialization: Getting BFT scan connection finished after PT16M6.010380169S
18:23:08.220 main             Starting Splice version 0.9.0-snapshot.20260929.3903.0.v6b4c166b
18:24:04.030 main             Running script Some(/app/bootstrap-entrypoint.sc) (SHA-256:986a81c3c0a8...)
18:24:17.848 canton-env-ec-69 validator_backend app initialization: Getting BFT scan connection started
18:26:35.946 main             === Bootstrapping application ===
18:26:36.418 main             Waiting for validator to finish init...
18:39:40.511 canton-env-ec-69 Retrieving key validator_scan_internal_config_key
18:39:41.409 main             Timeout while waiting for initialization of validator_backend
18:39:41.467 Thread-1         Shutting down...
18:39:49.028 canton-env-ec-46 validator_backend app initialization: Getting BFT scan connection finished after PT15M31.180398549S
18:40:09.280 main             Starting Splice version 0.9.0-snapshot.20260929.3903.0.v6b4c166b
18:40:54.378 main             Running script Some(/app/bootstrap-entrypoint.sc) (SHA-256:986a81c3c0a8...)
18:41:06.300 canton-env-ec-46 validator_backend app initialization: Getting BFT scan connection started
18:43:20.074 main             === Bootstrapping application ===
18:43:20.417 main             Waiting for validator to finish init...
```

## 3. One thread blocked, not the pool; the console thread released together with it

Everything logged in boot 1's stall window except probes and DB statistics. Other `canton-env-ec` threads run
(18:02), so the pool is not starved. The console `main` thread, which started polling at 17:48:39.784, logs its first
`GetHealthStatus` only at 18:04:19.790, 14 ms before `canton-env-ec-69` continues.

```
zcat log/10249/10249/validator-app.json.gz | python3 -c '
import json,sys,re
for e in json.load(sys.stdin):
    t=e["timestamp"]
    if not ("2026-09-29T17:43:22.3" <= t <= "2026-09-29T18:04:19.9"): continue
    j=e.get("jsonPayload") or {}; m=(j.get("message") or "").replace("\n"," ")
    if re.search(r"/api/validator/(readyz|livez)|most expensive database queries", m): continue
    print(t[11:23], j.get("thread_name","")[:16].ljust(16), m[:110])'
```
```
17:43:22.402 canton-env-ec-69 validator_backend app initialization: Getting BFT scan connection started
17:48:39.158 main             === Bootstrapping application ===
17:48:39.784 main             Waiting for validator to finish init...
18:02:01.817 canton-env-ec-47 The operation 'com.digitalasset.canton.resource.DbLock.runLockCheck' was not successful. New kind of error: no
18:02:02.423 canton-env-ec-48 Now retrying operation 'com.digitalasset.canton.resource.DbLock.runLockCheck'. 
18:02:07.564 canton-env-ec-61 The operation 'com.digitalasset.canton.resource.DbLock.runLockCheck' was not successful. New kind of error: no
18:02:07.772 canton-env-ec-61 Now retrying operation 'com.digitalasset.canton.resource.DbLock.runLockCheck'. 
18:04:19.790 main             Running on validator_backend command GetHealthStatus(/api/validator,org.lfdecentralizedtrust.splice.console.Ht
18:04:19.804 canton-env-ec-69 Retrieving key validator_scan_internal_config_key
18:04:19.823 canton-env-ec-74 Validator bootstrapping with 1 seed URLs: List(http://scan-app.sv-1:5012)
```

## 4. Timing history: no build regression

Per boot: startup-script delay (`Running script` to `=== Bootstrapping application ===`) and the `Getting BFT scan
connection` duration, grouped by hour and version. Every build from 2026-09-25 to 1293c69b23 (15:xx) is normal,
including cf4f8dd5c4 (#7466, lazy DarResources). 6b4c166b71 differs from 1293c69b23 by one commit, #7522 (frontend
package.json and lockfiles only). ciperiodic in the same hour is the control.

```
cat > log/10249/boots.py <<'EOF'
import json,gzip,re,sys,collections
from datetime import datetime
def ts(s): return datetime.fromisoformat(s[:19])
def dur(s):
    m=re.match(r"PT(?:(\d+)M)?([\d.]+)S",s or ""); return None if not m else int(m.group(1) or 0)*60+float(m.group(2))
def boots(f):
    b={}
    for e in json.load(gzip.open(f)):
        j=e.get("jsonPayload") or {}; m=j.get("message") or ""
        r=e["resource"]["labels"]; k=(r["namespace_name"],r["pod_name"]); t=e["timestamp"][:19]
        if "Starting Splice version" in m: b.setdefault(k,[]).append(dict(start=t,ver=m.split()[-1],script=None,ready=None,bft=None))
        elif k in b:
            x=b[k][-1]
            if "Running script" in m: x["script"]=t
            elif "=== Bootstrapping application" in m: x["ready"]=t
            elif "Getting BFT scan connection finished after" in m: x["bft"]=dur(m.split("after ")[-1])
    return [(k[0],k[1],x) for k,l in b.items() for x in l]
def sc(x): return (ts(x["ready"])-ts(x["script"])).total_seconds() if x["script"] and x["ready"] else None
mode,f=sys.argv[1],sys.argv[2]
if mode=="hist":
    g=collections.defaultdict(list)
    for ns,p,x in boots(f): g[(x["start"][:13],x["ver"])].append(x)
    mm=lambda v: f"{min(v):.0f}-{max(v):.0f}" if v else "-"
    print("hour(UTC)     version                                   boots script_s  bft_s")
    for (h,v),L in sorted(g.items()):
        print(f"{h} {v:42} {len(L):5} {mm([s for s in map(sc,L) if s is not None]):>8} {mm([x['bft'] for x in L if x['bft'] is not None]):>7}")
else:
    for ns,p,x in sorted(boots(f), key=lambda r:r[2]["start"]):
        if x["start"]>=sys.argv[3]: print(x["start"][11:], ns.ljust(10), p.ljust(32), "script_s", sc(x), "bft_s", x["bft"])
EOF
python3 log/10249/boots.py hist log/10249/init-steps-cimain-validators.json.gz
python3 log/10249/boots.py hist log/10249/init-steps-ciperiodic-validators.json.gz
```
```
hour(UTC)     version                                   boots script_s  bft_s
2026-09-25T12 0.9.0-snapshot.20260925.3880.0.v3ccdd688      12     8-14   43-54
2026-09-25T13 0.9.0-snapshot.20260925.3880.0.v3ccdd688       1      8-8   48-48
2026-09-25T13 0.9.0-snapshot.20260925.3881.0.v4582171c      10     8-10   39-50
2026-09-25T14 0.9.0-snapshot.20260925.3881.0.v4582171c       2    10-14   49-56
2026-09-25T14 0.9.0-snapshot.20260925.3882.0.v3e44d22e       7     9-10   41-50
2026-09-25T15 0.9.0-snapshot.20260925.3882.0.v3e44d22e       6     9-12   44-51
2026-09-25T17 0.9.0-snapshot.20260925.3884.0.vccc8e266      12     8-12   39-65
2026-09-25T18 0.9.0-snapshot.20260925.3884.0.vccc8e266       2    10-14   49-49
2026-09-28T05 0.9.0-snapshot.20260928.3885.0.vcf4f8dd5      13     8-14   40-57
2026-09-28T08 0.9.0-snapshot.20260928.3886.0.v5eb871da      10     9-11   30-57
2026-09-28T13 0.9.0-snapshot.20260928.3887.0.v25ce873f       8     8-14   33-62
2026-09-28T14 0.9.0-snapshot.20260928.3887.0.v25ce873f       3     8-14   47-56
2026-09-28T15 0.9.0-snapshot.20260928.3888.0.va9c71781      14     9-12   40-55
2026-09-28T16 0.9.0-snapshot.20260928.3889.0.v7b250547      11     9-12   39-56
2026-09-28T17 0.9.0-snapshot.20260928.3889.0.v7b250547       1      9-9   48-48
2026-09-29T06 0.9.0-snapshot.20260929.3890.0.v5fb97405       8     9-13   44-56
2026-09-29T07 0.9.0-snapshot.20260929.3890.0.v5fb97405       4     8-10   46-49
2026-09-29T08 0.9.0-snapshot.20260929.3891.0.v9dd73aad      14     9-12   41-53
2026-09-29T09 0.9.0-snapshot.20260929.3896.0.v58a8ad73      10     9-11   39-54
2026-09-29T10 0.9.0-snapshot.20260929.3896.0.v58a8ad73       2      9-9   48-48
2026-09-29T11 0.9.0-snapshot.20260929.3898.0.v0a2f9871      10     8-12   34-58
2026-09-29T12 0.9.0-snapshot.20260929.3901.0.v802b9fae      10     8-12   40-60
2026-09-29T13 0.9.0-snapshot.20260929.3901.0.v802b9fae       3    10-10   49-49
2026-09-29T15 0.9.0-snapshot.20260929.3902.0.v1293c69b      11     8-14   42-56
2026-09-29T17 0.9.0-snapshot.20260929.3903.0.v6b4c166b       6    9-362 46-1633
2026-09-29T18 0.9.0-snapshot.20260929.3903.0.v6b4c166b       4  113-271 775-966
hour(UTC)     version                                   boots script_s  bft_s
2026-09-29T17 0.9.0-snapshot.20260929.3902.0.v1293c69b       1    10-10   50-50
2026-09-29T18 0.8.4                                          9     8-12   33-57
```

## 5. Same build, split by node

On 6b4c166b71 the three validator-apps on k48f are slow and the three on dkj9 are normal.

```
python3 log/10249/boots.py pods log/10249/init-steps-cimain-validators.json.gz 2026-09-29T17
zcat log/10249/events-cimain-scheduled-and-nodes.json.gz | python3 -c '
import json,sys,re
for e in json.load(sys.stdin):
    m=(e.get("jsonPayload") or {}).get("message") or ""
    x=re.search(r"assigned (\S+)/(validator-app\S*) to (\S+)",m)
    if x: print(e["timestamp"][11:19], x.group(1).ljust(10), x.group(2).ljust(32), x.group(3))'
```
```
17:39:07 sv-1       validator-app-76ff458695-v97mn   script_s 9.0 bft_s 53.487870226
17:40:37 splitwell  validator-app-d6764f459-6mhnm    script_s 358.0 bft_s 1461.782337053
17:40:38 validator1 validator-app-67667cc4b4-tx2rw   script_s 362.0 bft_s 1266.267017961
17:41:23 sv-2       validator-app-8f5bcf9b4-pf2hc    script_s 9.0 bft_s 51.316560503
17:42:13 sv-3       validator-app-6849c5d49d-xrzzf   script_s 357.0 bft_s 1632.514925102
17:47:14 sv-da-1    validator-app-c94c9465-4rkj9     script_s 9.0 bft_s 46.405594646
18:04:52 validator1 validator-app-67667cc4b4-tx2rw   script_s 271.0 bft_s 966.010380169
18:23:08 validator1 validator-app-67667cc4b4-tx2rw   script_s 151.0 bft_s 931.180398549
18:40:09 validator1 validator-app-67667cc4b4-tx2rw   script_s 146.0 bft_s 775.384176816
18:54:23 validator1 validator-app-67667cc4b4-tx2rw   script_s 113.0 bft_s None
17:38:46 splitwell  validator-app-d6764f459-6mhnm    gke-cn-cimainnet-cn-apps-node-pool-hd-1465684f-k48f
17:38:49 validator1 validator-app-67667cc4b4-tx2rw   gke-cn-cimainnet-cn-apps-node-pool-hd-1465684f-k48f
17:38:56 sv-1       validator-app-76ff458695-v97mn   gke-cn-cimainnet-cn-apps-node-pool-hd-1465684f-dkj9
17:41:18 sv-2       validator-app-8f5bcf9b4-pf2hc    gke-cn-cimainnet-cn-apps-node-pool-hd-1465684f-dkj9
17:41:19 sv-3       validator-app-6849c5d49d-xrzzf   gke-cn-cimainnet-cn-apps-node-pool-hd-1465684f-k48f
17:47:09 sv-da-1    validator-app-c94c9465-4rkj9     gke-cn-cimainnet-cn-apps-node-pool-hd-1465684f-dkj9
```

## 6. k48f is CPU-saturated for the whole hour

Node CPU (allocatable utilization), validator-app CPU (cores), and the non-daemonset pods placed on k48f plus its OOM
kills. The slow validator-apps each burn 1.1-3.3 cores for 20+ minutes; the normal ones about 1.4 cores for one minute,
then 0.01.

```
zcat log/10249/metrics-node-cpu-allocatable-utilization.json.gz | python3 -c '
import json,sys
for s in sorted(json.load(sys.stdin)["timeSeries"], key=lambda s:s["resource"]["labels"]["node_name"]):
    n=s["resource"]["labels"]["node_name"]; pts=sorted((p["interval"]["endTime"][11:16],p["value"]["doubleValue"]) for p in s["points"])
    print(n.replace("gke-cn-cimainnet-","").ljust(30), " ".join(f"{t}:{v*100:3.0f}%" for t,v in pts if t[4] in "05" and "17:40"<=t<="18:40"))'
```
```
cn-apps-node-pool-hd-1465684f-dkj9 17:40: 23% 17:45:  5% 17:50:  4% 17:55:  4% 18:00:  4% 18:05:  4% 18:10:  4% 18:15:  4% 18:20:  4% 18:25:  3% 18:30:  4% 18:35:  3% 18:40:  3%
cn-apps-node-pool-hd-1465684f-g4f2 17:40: 12% 17:45:  6% 17:50:  5% 17:55:  5% 18:00:  7% 18:05:  6% 18:10:  8% 18:15:  6% 18:20:  9% 18:25:  5% 18:30:  7% 18:35:  5% 18:40:  6%
cn-apps-node-pool-hd-1465684f-jzgs 17:40: 23% 17:45:  4% 17:50:  4% 17:55:  3% 18:00:  3% 18:05:  3% 18:10:  3% 18:15:  3% 18:20:  3% 18:25:  2% 18:30:  3% 18:35:  2% 18:40:  3%
cn-apps-node-pool-hd-1465684f-k48f 17:40: 55% 17:45: 98% 17:50:105% 17:55: 90% 18:00: 91% 18:05: 76% 18:10:104% 18:15: 89% 18:20: 77% 18:25: 85% 18:30: 80% 18:35: 85% 18:40: 69%
cn-apps-node-pool-hd-1465684f-rsw6 17:40: 31% 17:45:  2% 17:50:  3% 17:55:  2% 18:00:  2% 18:05:  2% 18:10:  2% 18:15:  2% 18:20:  2% 18:25:  2% 18:30:  2% 18:35:  2% 18:40:  2%
cn-apps-node-pool-hd-1465684f-wsd2 17:40: 21% 17:45:  5% 17:50:  5% 17:55:  4% 18:00:  4% 18:05:  4% 18:10:  9% 18:15:  5% 18:20:  5% 18:25:  4% 18:30:  5% 18:35:  4% 18:40:  4%
cn-apps-node-pool-hd-5b8182c4-b7v5 17:40:  3% 17:45:  4% 17:50:  4% 17:55:  4% 18:00:  4% 18:05:  4% 18:10:  4% 18:15:  4% 18:20:  4% 18:25:  4% 18:30:  4% 18:35:  4% 18:40:  4%
cn-apps-node-pool-hd-5b8182c4-pm4j 17:40:  6% 17:45:  4% 17:50:  4% 17:55:  4% 18:00:  4% 18:05:  4% 18:10:  4% 18:15:  4% 18:20:  4% 18:25:  3% 18:30:  4% 18:35:  4% 18:40:  4%
cn-infra-node-pool-c-a8323e33-hltb 17:40:  6% 17:45:  4% 17:50:  4% 17:55:  4% 18:00:  4% 18:05:  4% 18:10:  4% 18:15:  4% 18:20:  3% 18:25:  3% 18:30:  4% 18:35:  3% 18:40:  3%
gke-pool-13e79245-pq9g         17:40:  3% 17:45:  3% 17:50:  3% 17:55:  3% 18:00:  3% 18:05:  3% 18:10:  3% 18:15:  3% 18:20:  3% 18:25:  3% 18:30:  3% 18:35:  3% 18:40:  3%
gke-pool-723ef7a6-r9gj         17:40:  3% 17:45:  3% 17:50:  3% 17:55:  3% 18:00:  3% 18:05:  2% 18:10:  3% 18:15:  3% 18:20:  3% 18:25:  3% 18:30:  3% 18:35:  3% 18:40:  3%
gke-pool-8ae55872-qeok         17:40:  3% 17:45:  3% 17:50:  3% 17:55:  3% 18:00:  3% 18:05:  2% 18:10:  3% 18:15:  3% 18:20:  3% 18:25:  3% 18:30:  3% 18:35:  3% 18:40:  3%
gke-pool-8eb1c767-73uf         17:40:  4% 17:45:  4% 17:50:  4% 17:55:  3% 18:00:  3% 18:05:  4% 18:10:  4% 18:15:  3% 18:20:  3% 18:25:  3% 18:30:  3% 18:35:  4% 18:40:  4%
```

```
zcat log/10249/metrics-validator-app-cpu-cores.json.gz | python3 -c '
import json,sys
for s in json.load(sys.stdin)["timeSeries"]:
    l=s["resource"]["labels"]; pts=sorted((p["interval"]["endTime"][11:16],p["value"]["doubleValue"]) for p in s["points"])
    print(l["namespace_name"].ljust(10), " ".join(f"{t}:{v:.2f}" for t,v in pts if t[4] in "05" and "17:40"<=t<="18:40"))'
```
```
splitwell  17:45:1.36 17:50:1.08 17:55:1.21 18:00:1.23 18:05:1.10 18:10:1.34 18:15:1.19 18:20:0.26 18:25:0.15 18:30:0.10 18:35:0.17 18:40:0.09
sv-1       17:40:1.43 17:45:0.01 17:50:0.01 17:55:0.01 18:00:0.01 18:05:0.01 18:10:0.01 18:15:0.01 18:20:0.01 18:25:0.01 18:30:0.01 18:35:0.01 18:40:0.01
sv-2       17:45:0.01 17:50:0.01 17:55:0.02 18:00:0.01 18:05:0.01 18:10:0.02 18:15:0.01 18:20:0.01 18:25:0.01 18:30:0.01 18:35:0.01 18:40:0.01
sv-3       17:45:1.60 17:50:1.77 17:55:1.33 18:00:1.33 18:05:1.17 18:10:0.90 18:15:0.14 18:20:0.11 18:25:0.13 18:30:0.11 18:35:0.06 18:40:0.05
sv-da-1    17:50:0.05 17:55:0.01 18:00:0.01 18:05:0.01 18:10:0.01 18:15:0.01 18:20:0.01 18:25:0.01 18:30:0.01 18:35:0.01 18:40:0.01
validator1 17:45:1.80 17:50:1.50 17:55:1.61 18:00:1.98 18:05:0.41 18:10:1.86 18:15:1.93 18:20:2.19 18:25:3.32 18:30:1.15 18:35:2.69 18:40:1.55
```

```
zcat log/10249/events-cimain-scheduled-and-nodes.json.gz | python3 -c '
import json,sys,re
for e in json.load(sys.stdin):
    j=e.get("jsonPayload") or {}; m=j.get("message") or ""
    x=re.search(r"assigned (\S+)/(\S+) to \S+-k48f$",m)
    if x and not re.match(r"(fluent-bit|gmp-system|kube-system|observability|sweet)$",x.group(1)): print(e["timestamp"][11:19], x.group(1).ljust(10), x.group(2))
    if j.get("reason")=="OOMKilling" and "(java)" in m: print(e["timestamp"][11:19], "OOMKilling", m[:120])'
```
```
17:36:37 sv-da-1    global-domain-0-mediator-7f4796b9c-7jppq
17:36:37 validator1 pge-validator-pg-helmless-validator1-845d497884-pjdcm
17:36:37 sv-da-1    pge-mediator-0-pg-helmless-global-domain-0-mediator-59fc95dr5f9
17:36:37 validator1 wallet-web-ui-7666cf566b-6qcr5
17:36:37 validator1 ans-web-ui-dff6487b-jqw58
17:36:37 splitwell  splitwell-app-6dcdd88594-9wrwg
17:36:37 splitwell  ans-web-ui-85bb8f89c7-rs62r
17:36:37 sv-da-1    pge-sequencer-0-pg-helmless-global-domain-0-sequencer-79d8qnbnk
17:36:37 sv-da-1    global-domain-0-sequencer-6577674f5b-m9sxq
17:38:46 splitwell  validator-app-d6764f459-6mhnm
17:38:49 validator1 validator-app-67667cc4b4-tx2rw
17:41:18 sv-2       pge-cn-apps-pg-helmless-validator-sv-2-56b4bd74bf-4v8x5
17:41:18 sv-3       pge-cn-apps-pg-helmless-validator-sv-3-7cf6bdf564-52j72
17:41:19 sv-3       validator-app-6849c5d49d-xrzzf
18:24:38 OOMKilling Memory cgroup out of memory: Killed process 12613 (java) total-vm:3157824kB, anon-rss:1566888kB, file-rss:25936kB, shmem
18:36:31 OOMKilling Memory cgroup out of memory: Killed process 14077 (java) total-vm:3136072kB, anon-rss:1566940kB, file-rss:25572kB, shmem
18:41:39 OOMKilling Memory cgroup out of memory: Killed process 14859 (java) total-vm:3134748kB, anon-rss:1566804kB, file-rss:25532kB, shmem
```

## 7. Why the init step is CPU-bound, and why only validator1 never recovers

`appInitStep` evaluates its body synchronously (`Try(f)`), and the first argument that does real work is the implicit
`templateDecoder`, a `lazy val` over `packageSignatures`, which decodes the signatures of every DAR version in
`packagesForJsonDecoding`. `bootstrap.sc` only waits (and then fails the app) when `SPLICE_APP_DARS` is non-empty;
only validator1 sets `appDars`, and nothing sets `SPLICE_APP_INITIALIZATION_TIMEOUT_MINUTES`, so the wait is 5 min.

```
R=6b4c166b71
git show $R:apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/ValidatorApp.scala | sed -n '178,180p'
git show $R:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/NodeBase.scala | sed -n '97,102p;203,205p;209p;215p'
git show $R:cluster/images/validator-app/bootstrap.sc | sed -n '7,19p'
git grep -n -E 'appDars:|SPLICE_APP_INITIALIZATION_TIMEOUT_MINUTES' $R -- cluster/pulumi cluster/helm
```
```
            scanConnection <- appInitStep("Getting BFT scan connection") {
              client.BftScanConnection(
                ledgerClient,
  lazy private val packageSignatures = {
    ResourceTemplateDecoder.loadPackageSignaturesFromResources(packagesForJsonDecoding)
  }

  lazy protected implicit val templateDecoder: TemplateJsonDecoder =
    new ResourceTemplateDecoder(packageSignatures, loggerFactory)
  protected def appInitStep[T](
      description: String
  )(f: => Future[T])(implicit tc: TraceContext): Future[T] =
        logger.debug(s"$appInitMessage: $description started")(tc)
        Try(f) match {
  val timeoutMinutes =
    decode[Int](sys.env.get("SPLICE_APP_INITIALIZATION_TIMEOUT_MINUTES").getOrElse("5")).getOrElse(
      sys.error("Failed to parse initialization timeout")
    )

  if (dars.isEmpty) {
    logger.info("No DARs specified to upload")
  } else {
    logger.info("Waiting for validator to finish init...")
    validator_backend.waitForInitialization(timeoutMinutes.minutes)

    logger.info(s"Uploading DARs: ${dars}")
    dars.foreach(validator_backend.participantClient.upload_dar_unless_exists(_))
6b4c166b71:cluster/pulumi/common-validator/src/validator.ts:188:      appDars: config.appDars || [],
6b4c166b71:cluster/pulumi/validator1/src/validator1.ts:105:    appDars: splitwellDarPaths,
```

## Verdict

- New, infra (family J). Not a code regression: identical build, normal on node dkj9, 25-35x slower on node k48f,
  which was CPU-saturated (69-105 %) for the whole hour while the other 12 nodes idled at 2-9 %. The scheduler packed
  three validator-apps, splitwell-app and sv-da-1's sequencer and mediator onto the node the scale-up had just created;
  validator-app requests 1 CPU but uses about 1.4 cores for its first minute (section 6).
- validator1 is the only casualty because of its DAR-upload bootstrap: a slow-but-progressing init hits
  `waitForInitialization(5 min)`, the script throws, the app shuts down and starts over from scratch (section 2), so it
  can never finish while the node stays loaded. splitwell and sv-3 have no `appDars` and recovered after 24-27 min.
- Retraction: an intermediate hypothesis in this triage blamed #7466 (cf4f8dd5c4, lazy DarResources). Section 4
  rules it out: builds containing it deployed normally from 2026-09-28 05:xx on.
- Resolution: rerun (the cluster was reset by the next deploy). Robustness fixes, described for the deployment owners,
  no branch written:
  (1) `bootstrap.sc` should not turn a DAR-upload wait timeout into an app shutdown, or validator1 should set
  `SPLICE_APP_INITIALIZATION_TIMEOUT_MINUTES` higher via the chart's `additionalEnvVars`
  (`cluster/helm/splice-validator/templates/validator.yaml:299`) in `cluster/pulumi/validator1/src/validator1.ts`;
  (2) spread validator-apps across nodes (topology spread or anti-affinity) or raise their CPU request to match startup use.
- Not verified: why the slow validator-apps consumed 20-40 core-minutes where normal boots use about 1.5 (contention
  alone does not add CPU time; GC or JIT behaviour under contention is a guess); which java process was OOM-killed on
  k48f at 18:24:38, 18:36:31 and 18:41:39 (1.5 GB RSS, container not identified); k48f's machine type; what exactly the
  console `main` thread waited on (the simultaneous release suggests the same lazy-val or class-init lock, but there is no
  thread dump); which earlier deploy left the "interrupted while creating" pending operations seen in attempt 1.
