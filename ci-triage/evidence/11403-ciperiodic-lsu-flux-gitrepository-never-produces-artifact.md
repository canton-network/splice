# 11403 - deploy_lsu_operator: stack canton-network never created, Flux GitRepository splice-node-deployment produces no artifact (CircleCI pipeline 88922)

New, cause partly open. CircleCI job 531634 `deploy_cluster_base_version` of workflow deploy_lsu_operator on
ciperiodic (cni build/ciperiodic-lsu 28ea47d) failed in step 024 `wait for all pulumi stacks to be updated`: the
stack canton-network was not created within 35 min. The operator stack was applied (step 018, 17 resources). In
namespace operator the Flux GitRepository `splice-node-deployment` waited for its include
`splice-node-deployment-base` until 23:22:13; the base artifact was stored at 23:22:26; after that the source
controller logs nothing more for `splice-node-deployment` up to the end of the export (23:59:53): no stored
artifact, no error. The Pulumi Stack `deployment` stays stalled with `Flux source has no artifact`, so it never runs
and never creates the canton-network Stack. Why the GitRepository reconcile produced nothing in 37 min is not shown
by the container logs. Not AY (11379): different job and step, the cluster deployment never started.

- Run: CircleCI pipeline 88922, job 531634 (issue body), started 2026-11-12T23:15:36Z.
- Evidence: user exports in `ci-triage/export/11403/` (host): `export_circleci.py 11403 531634 --all-steps`,
  `export_gcp_logs.py 11403 --project da-cn-ci --cluster cn-ciperiodicnet --start 2026-11-12T23:10:00Z --end
  2026-11-13T00:00:00Z --query operator-all 'resource.labels.namespace_name="operator"'` (182 entries, all INFO or
  DEBUG; the same window filtered on `severity>=WARNING OR "canton-network"` returned 0 entries).
- Component: infra (Flux source controller 2.14.1 chart / Pulumi Kubernetes Operator 2.10.0 on ciperiodic).

The exports are gitignored and stay on the machine of whoever ran them; rerun the export commands above to recreate them (GCP logs only while Cloud Logging still holds them).

Below `X=ci-triage/export/11403`.

## 1. Failing step

```
cat $X/531634-steps.txt | grep -E 'job|failed'; f="$X/531634-024-wait_for_all_pulumi_stacks_to_be_updated..txt"; grep -c 'Waiting for stack canton-network to be created' "$f"; grep -m2 -E 'waiting for stack|to be created' "$f"; tail -4 "$f" | head -2
```
```
job 531634 deploy_cluster_base_version status=failed branch=main vcs_revision=e0008eafcc7c7014aba4b7aa34f15cfed1eecd51 start=2026-11-12T23:15:36.135Z
024 failed wait for all pulumi stacks to be updated.
415
[2026-11-12 23:21:00 UTC] waiting for stack canton-network being updated with commit refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c11a4617846cdedc0e7963ccb
[2026-11-12 23:21:00 UTC] Waiting for stack canton-network to be created...
[2026-11-12 23:56:02 UTC] Timeout reached without finding stack canton-network has the updated with expected commit refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c11a4617846cdedc0e7963ccb.
Exceeded maximum retries (1 / 0), no more attempts wait for all pulumi stacks to be updated.
```

## 2. Step 018 applied the operator stack: both GitRepositories and the Stack deployment

```
sed -E 's/\x1b\[[0-9;]*m//g' $X/531634-018-Apply_the_deployment_stack.txt | grep -E '^\s+\+ kubernetes:(source|pulumi)|fromPath|toPath|name: "splice-node-deployment-base"|interval|created|Duration|^\s+version +: "2' | awk '!s[$0]++'
```
```
Duration: 1s
        version                 : "2.14.1"
        version                 : "2.10.0"
            version   : "2.14.1"
    + kubernetes:source.toolkit.fluxcd.io/v1:GitRepository: (create)
                    fromPath  : "cluster/stacks/ci/deployment/Pulumi.deployment.ciperiodic.yaml"
                        name: "splice-node-deployment-base"
                    toPath    : "splice/cluster/pulumi/deployment/Pulumi.deployment.ciperiodic.yaml"
            interval         : "5m"
                            f:interval         : {}
            version   : "2.10.0"
    + kubernetes:pulumi.com/v1:Stack: (create)
            retryMaxBackoffDurationSeconds: 60
                            f:retryMaxBackoffDurationSeconds: {}
    + 17 created
Duration: 28s
```

## 3. Source controller: all GitRepository lines of the window

The last line for `splice-node-deployment` is the wait at 23:22:13; the base is stored 12 s later and then
re-checked every 5 min; `splice-node-deployment` never logs again.

```
python3 -I -c '
import json
d=json.load(open("ci-triage/export/11403/gcp/operator-all.json")); d.sort(key=lambda e:e["timestamp"])
for e in d:
    j=e.get("jsonPayload") or {}
    if e["resource"]["labels"]["pod_name"].startswith("source-controller") and j.get("controllerKind")=="GitRepository" and j.get("name"):
        print(e["timestamp"][11:23], j.get("name"), "|", j.get("msg")[:110], "|", j.get("reason",""))'
```
```
23:20:43.597 splice-node-deployment | reconciliation waiting | no artifact available for include 'splice-node-deployment-base'
23:21:13.711 splice-node-deployment | reconciliation waiting | no artifact available for include 'splice-node-deployment-base'
23:21:43.829 splice-node-deployment | reconciliation waiting | no artifact available for include 'splice-node-deployment-base'
23:22:13.947 splice-node-deployment | reconciliation waiting | no artifact available for include 'splice-node-deployment-base'
23:22:26.064 splice-node-deployment-base | stored artifact for commit 'Set the operator deployment reference to build/cip...' | 
23:27:22.439 splice-node-deployment-base | no changes since last reconcilation: observed revision 'refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c1 | 
23:32:31.028 splice-node-deployment-base | no changes since last reconcilation: observed revision 'refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c1 | 
23:37:37.815 splice-node-deployment-base | no changes since last reconcilation: observed revision 'refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c1 | 
23:42:31.248 splice-node-deployment-base | no changes since last reconcilation: observed revision 'refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c1 | 
23:47:41.382 splice-node-deployment-base | no changes since last reconcilation: observed revision 'refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c1 | 
23:52:50.007 splice-node-deployment-base | no changes since last reconcilation: observed revision 'refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c1 | 
23:58:02.190 splice-node-deployment-base | no changes since last reconcilation: observed revision 'refs/heads/build/ciperiodic-lsu@sha1:28ea47d4063f905c1 | 
```

## 4. Pulumi operator: Stack deployment stalled on the missing source artifact

Count, first and last time per message of the stack controller for `deployment`; the only two status updates say
the source has no artifact, and 80 reconciles (every 30 s to the end of the export) change nothing.

```
python3 -I -c '
import json,collections
d=json.load(open("ci-triage/export/11403/gcp/operator-all.json"))
c=collections.Counter(); first={}; last={}
for e in sorted(d,key=lambda e:e["timestamp"]):
    j=e.get("jsonPayload") or {}
    if j.get("name")=="deployment" and j.get("controller")=="stack-controller":
        k=j.get("msg")[:40]+" | "+"; ".join(x.get("message","") for x in j.get("conditions",[]))
        c[k]+=1; first.setdefault(k,e["timestamp"][11:23]); last[k]=e["timestamp"][11:23]
for k,v in c.items(): print(v, first[k], last[k], k)
print(collections.Counter((e.get("severity")) for e in d))'
```
```
80 23:20:50.010 23:59:53.285 Reconciling Stack | 
1 23:20:50.056 23:20:50.056 metadata.finalizers: "finalizer.stack.pu | 
2 23:20:50.169 23:20:50.218 Status updated | reconciliation is stalled; Flux source has no artifact
Counter({'INFO': 171, 'DEBUG': 7, None: 4})
```

## 5. Duplicate check

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Waiting for stack canton-network to be created"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:100])"'
```
```
#8596 closed/completed 2026-05-22 Timed out Waiting for stack canton-network to be created...
#8838 closed/not_planned 2026-06-18 Pipeline 70327 : workflow deploy_lsu_operator Failed :fire:
#8751 closed/duplicate 2026-06-10 Pipeline 69357 : workflow deploy_lsu_operator Failed :fire:
#8742 closed/completed 2026-06-10 stack canton-network is currently on commit: . Waiting for the expecting commit 
#7802 closed/completed 2026-03-30 3.5 - flux artifacts exceed max size 
```
- 8596, 8838: same step output, closed without a cause (8838: suspected overlapping deployments); no operator logs.
- 8742: the stack was created after 13 min, then failed later; different.
- 7802: flux artifact over the size limit; that logs an error, here the source controller logs none (section 3).
- 11379 (AY, previous night): the deployment ran, the re-onboarded SV failed init; different job and step.

## Verdict

New, infra. Shown: the Stack `deployment` never ran because its Flux source `splice-node-deployment` had no
artifact for the whole 35 min wait, and the source controller logged neither an artifact nor an error for it after
the include became available at 23:22:26. NOT shown: what the source controller did with `splice-node-deployment`
after 23:22:13 (a clone with submodules that hangs or is very slow, or no reconcile at all); the container logs at
INFO are silent. Deciding it needs the GitRepository status and events (`kubectl -n operator describe gitrepository
splice-node-deployment`) from a cluster in that state, or the k8s events of namespace operator for the window.
Second nightly failure of deploy_lsu_operator in a row, with a different cause than 11379. No splice change.
