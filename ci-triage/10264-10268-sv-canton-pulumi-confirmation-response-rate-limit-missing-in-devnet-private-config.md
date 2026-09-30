# 10264, 10265, 10266, 10267, 10268 - cluster deploys fail in every `pulumi up` attempt: sv-canton throws `Cannot read properties of undefined (reading 'globalTpsCap')` because #7546 reads `messages.confirmationResponse`, which the DevNet `sequencer-rate-limits.json` in configs-private does not have (CircleCI builds 508564, 508611, 508636, 508682, 508741)

New, real (family Q, deployment config regression), deterministic, not a flake. Five CircleCI deploy jobs on
2026-09-30 (cimain deploy_basic x3, ciperiodic sv-reonboard, ciperiodic upgrade) fail step 114 `Apply Pulumi
configuration to cluster` after 6 of 6 attempts. In each of the 30 attempts, all four `sv-canton.<sv>-migration-0`
stacks throw the same TypeError at program evaluation in `getSequencerRateLimitConfig`
(`cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts:47`). That function gained a third message type in
#7546 (5375bb71c3, merged 2026-09-30 14:12:50Z): `rateLimits.messages.confirmationResponse`. The CI clusters run with
`IS_DEVNET=1`, so the file is `configs/DevNet/sequencer-rate-limits.json` in the internal configs-private checkout. It
exists, since there is no `could not read JSON` line, but it has no `confirmationResponse` key. #7546 says it is "to be merged once
canton-foundation/configs-private#3673 is merged"; either that PR does not cover DevNet or the checkout the deploy uses
does not contain it yet. The Helm `context canceled`, Postgres StatefulSet `Resource operation was cancelled` and
`interrupted while creating` errors on the sv, validator1 and splitwell stacks come from the multi-stack runner's shared
abort controller cancelling everything after the first sv-canton failure. They are not independent failures.

- Builds (user-supplied step logs, `log/<ref>/build_<n>_step_114_container_0.txt`):

  | ref | CircleCI build | deploy branch | splice deployed | attempts (first `startTime:` of each) |
  |-----|----------------|---------------|-----------------|------------------------------------------|
  | 10264 | 508564 | build/cimain-deploy-basic | 0.9.0-snapshot.20260930.3919.0.v7aaa1e94 | 15:36:43Z - 15:45:52Z |
  | 10265 | 508611 | build/cimain-deploy-basic | 0.9.0-snapshot.20260930.3920.0.vc79eb214 | 16:15:04Z - 16:24:47Z |
  | 10266 | 508636 | build/ciperiodic-sv-reonboard | 0.9.0-snapshot.20260930.3921.0.v5592838f | 16:35:24Z - 16:46:07Z |
  | 10267 | 508682 | build/cimain-deploy-basic | 0.9.0-snapshot.20260930.3921.0.v5592838f | 16:41:49Z - 16:51:04Z |
  | 10268 | 508741 | build/ciperiodic-upgrade | 0.9.0-snapshot.20260930.3921.0.v5592838f | 19:16:51Z - 19:25:14Z |

- Runtime canton: 3.6.0-snapshot.20260929.20331.0.v07b3f95b (same pin at 7aaa1e94 and 5592838f46; irrelevant, no
  Canton node was reached).
- Component: cluster deployment (Pulumi `sv-canton` program plus the internal configs-private DevNet file). Not a
  test, not a splice app, not infra.

## 1. Every build: all 6 attempts used, sv-canton stacks among the failed operations

```
$ for r in 10264 10265 10266 10267 10268; do f=$(ls log/$r/*.txt); printf '%s %s ' $r "$(basename $f)"; sed 's/\x1b\[[0-9;]*m//g' $f | grep -a -o -m1 -E 'build/[a-z-]+' | tr '\n' ' '; sed 's/\x1b\[[0-9;]*m//g' $f | grep -a -o -m1 -E '0\.9\.0-snapshot\.[0-9]+\.[0-9]+\.0\.v[0-9a-f]+' | tr '\n' ' '; echo; sed 's/\x1b\[[0-9;]*m//g' $f | grep -a -E '^Ran [0-9]+ operations|^Exceeded maximum retries' | tail -2 | cut -c1-200; done
10264 build_508564_step_114_container_0.txt build/cimain-deploy-basic 0.9.0-snapshot.20260930.3919.0.v7aaa1e94
Ran 11 operations. 10 failed: up-canton-M0-sv-1, up-canton-M0-sv-2, up-canton-M0-sv-3, up-canton-M0-sv-da-1, up-sv-sv-1, up-sv-sv-2, up-sv-sv-3, up-sv-sv-da-1, up-organization/validator1/validator1.ci
Ran 11 operations. 10 failed: up-canton-M0-sv-1, up-canton-M0-sv-2, up-canton-M0-sv-3, up-canton-M0-sv-da-1, up-sv-sv-1, up-sv-sv-2, up-sv-sv-3, up-sv-sv-da-1, up-organization/validator1/validator1.ci
10265 build_508611_step_114_container_0.txt build/cimain-deploy-basic 0.9.0-snapshot.20260930.3920.0.vc79eb214
Ran 11 operations. 10 failed: up-canton-M0-sv-1, up-canton-M0-sv-2, up-canton-M0-sv-3, up-canton-M0-sv-da-1, up-sv-sv-1, up-sv-sv-2, up-sv-sv-3, up-sv-sv-da-1, up-organization/validator1/validator1.ci
Ran 11 operations. 10 failed: up-canton-M0-sv-1, up-canton-M0-sv-2, up-canton-M0-sv-3, up-canton-M0-sv-da-1, up-sv-sv-1, up-sv-sv-2, up-sv-sv-3, up-sv-sv-da-1, up-organization/validator1/validator1.ci
10266 build_508636_step_114_container_0.txt build/ciperiodic-sv-reonboard 0.9.0-snapshot.20260930.3921.0.v5592838f
Ran 11 operations. 10 failed: up-canton-M0-sv-1, up-canton-M0-sv-2, up-canton-M0-sv-3, up-canton-M0-sv-da-1, up-sv-sv-1, up-sv-sv-2, up-sv-sv-3, up-sv-sv-da-1, up-organization/validator1/validator1.ci
Exceeded maximum retries (6 / 5), no more attempts Apply Pulumi configuration to cluster
10267 build_508682_step_114_container_0.txt build/cimain-deploy-basic 0.9.0-snapshot.20260930.3921.0.v5592838f
Ran 11 operations. 10 failed: up-canton-M0-sv-1, up-canton-M0-sv-2, up-canton-M0-sv-3, up-canton-M0-sv-da-1, up-sv-sv-1, up-sv-sv-2, up-sv-sv-3, up-sv-sv-da-1, up-organization/validator1/validator1.ci
Exceeded maximum retries (6 / 5), no more attempts Apply Pulumi configuration to cluster
10268 build_508741_step_114_container_0.txt build/ciperiodic-upgrade 0.9.0-snapshot.20260930.3921.0.v5592838f
Ran 11 operations. 4 failed: up-canton-M0-sv-1, up-canton-M0-sv-2, up-canton-M0-sv-3, up-canton-M0-sv-da-1
Exceeded maximum retries (6 / 5), no more attempts Apply Pulumi configuration to cluster
```

In 10268's last attempt, only the four sv-canton operations failed; sv, validator1 and splitwell all succeeded. So
the other stacks are healthy once nothing cancels them.

## 2. The first error in every attempt: the same TypeError in all four sv-canton stacks

The count covers each attempt, split at the `cluster` stack's `Running Pulumi Command` line. It counts distinct
sv-canton stacks with the stack-prefixed TypeError line, then sv-canton `pulumi up` commands that failed. A 3
in the first column is an interleaved line that lost its prefix; the second column is 4 everywhere except 10266
attempt 1, where the first column is 4.

```
$ for r in 10264 10265 10266 10267 10268; do sed 's/\x1b\[[0-9;]*m//g' log/$r/*.txt | awk -v r=$r '
/Running Pulumi Command : pulumi --cwd .*stacks\/ci\/cluster /{a++}
/(startTime): 20/ && !(a in st){st[a]=$2}
/\] - error - error: TypeError: Cannot read properties of undefined \(reading .globalTpsCap.\)/{match($0,/sv-canton\.[a-z0-9-]+-migration-0/); k=a SUBSEP substr($0,RSTART+10,RLENGTH-22); if(!(k in seen)){seen[k]=1; n[a]++}}
/^Command failed with exit code 1: .*--stack organization\/sv-canton\//{f[a]++}
END{for(i=1;i<=a;i++) printf "%s attempt %d first-op-start %s sv-canton stacks with globalTpsCap TypeError (prefixed)=%d, failed sv-canton pulumi up=%d\n", r, i, st[i], n[i], f[i]}'; done
10264 attempt 1 first-op-start 2026-09-30T15:36:43.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10264 attempt 2 first-op-start 2026-09-30T15:39:05.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10264 attempt 3 first-op-start 2026-09-30T15:40:47.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10264 attempt 4 first-op-start 2026-09-30T15:42:28.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=3, failed sv-canton pulumi up=4
10264 attempt 5 first-op-start 2026-09-30T15:44:09.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10264 attempt 6 first-op-start 2026-09-30T15:45:52.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10265 attempt 1 first-op-start 2026-09-30T16:15:04.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10265 attempt 2 first-op-start 2026-09-30T16:17:57.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=3, failed sv-canton pulumi up=4
10265 attempt 3 first-op-start 2026-09-30T16:19:44.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10265 attempt 4 first-op-start 2026-09-30T16:21:24.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10265 attempt 5 first-op-start 2026-09-30T16:23:05.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10265 attempt 6 first-op-start 2026-09-30T16:24:47.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10266 attempt 1 first-op-start 2026-09-30T16:35:24.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=3
10266 attempt 2 first-op-start 2026-09-30T16:39:01.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10266 attempt 3 first-op-start 2026-09-30T16:40:48.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10266 attempt 4 first-op-start 2026-09-30T16:42:33.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10266 attempt 5 first-op-start 2026-09-30T16:44:22.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10266 attempt 6 first-op-start 2026-09-30T16:46:07.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10267 attempt 1 first-op-start 2026-09-30T16:41:49.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10267 attempt 2 first-op-start 2026-09-30T16:44:14.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10267 attempt 3 first-op-start 2026-09-30T16:45:57.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10267 attempt 4 first-op-start 2026-09-30T16:47:39.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10267 attempt 5 first-op-start 2026-09-30T16:49:21.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10267 attempt 6 first-op-start 2026-09-30T16:51:04.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10268 attempt 1 first-op-start 2026-09-30T19:16:51.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10268 attempt 2 first-op-start 2026-09-30T19:18:36.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10268 attempt 3 first-op-start 2026-09-30T19:20:18.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10268 attempt 4 first-op-start 2026-09-30T19:21:57.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10268 attempt 5 first-op-start 2026-09-30T19:23:37.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
10268 attempt 6 first-op-start 2026-09-30T19:25:14.000Z, sv-canton stacks with globalTpsCap TypeError (prefixed)=4, failed sv-canton pulumi up=4
```

Stack trace, 10268 attempt 1 (every occurrence in the five logs has the same frames):

```
$ sed 's/\x1b\[[0-9;]*m//g' log/10268/*.txt | sed -n '23526,23536p' | cut -c1-260
[organization/sv-canton/sv-canton.sv-2-migration-0.ciperiodic] - error - error: TypeError: Cannot read properties of undefined (reading 'globalTpsCap')
    at limitsForMessageType (/home/********/project/splice/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts:47:36)
    at getSequencerRateLimitConfig (/home/********/project/splice/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts:55:5)
    at InStackCantonBftDecentralizedSynchronizerNode.installDecentralizedSynchronizer (/home/********/project/splice/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts:127:29)
    at new InStackCantonBftDecentralizedSynchronizerNode (/home/********/project/splice/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts:328:10)
    at installCantonComponents (/home/********/project/splice/cluster/pulumi/sv-canton/src/canton.ts:121:9)
    at processTicksAndRejections (node:internal/process/task_queues:104:5)
    at async installNode (/home/********/project/splice/cluster/pulumi/sv-canton/src/installNode.ts:47:10)
    at async auth0CacheAndInstallNode (/home/********/project/splice/cluster/pulumi/sv-canton/src/index.ts:22:16)
    at async /home/********/project/splice/cluster/pulumi/sv-canton/src/index.ts:34:53
    at async applyHelperAsync (/home/********/project/splice/cluster/pulumi/node_modules/@pulumi/output.ts:516:25)
```

## 3. Everything else is the abort cascade

`cluster/pulumi/pulumiOperations.ts:29/58/115` (origin/main) calls `abortController.abort(...)` on any caught
exception. `PulumiAbortController.abort` (`cluster/pulumi/pulumi.ts:167-186`) waits 10-11 s and then aborts every
operation that shares the controller. Pulumi receives that as `^C`, and every Helm or StatefulSet await in the other
stacks ends as `context canceled` or `Resource operation was cancelled`. Resources cancelled mid-create become pending
operations (`interrupted while creating`) that the next attempt warns about.

```
$ for r in 10264 10268; do echo "== $r"; sed 's/\x1b\[[0-9;]*m//g' log/$r/*.txt | grep -a -n -E 'Aborting (after the wait time|because|:)' | head -4 | cut -c1-200; done
== 10264
43868:Aborting after the wait time: organization/sv-canton/sv-canton.sv-3-migration-0.cimain - Aborting because of caught exception
63330:Aborting: organization/sv-canton/sv-canton.sv-3-migration-0.cimain - Aborting because of caught exception
89669:Aborting after the wait time: organization/sv-canton/sv-canton.sv-1-migration-0.cimain - Aborting because of caught exception
99788:Aborting: organization/sv-canton/sv-canton.sv-1-migration-0.cimain - Aborting because of caught exception
== 10268
23797:Aborting after the wait time: organization/sv-canton/sv-canton.sv-2-migration-0.ciperiodic - Aborting because of caught exception
30673:Aborting: organization/sv-canton/sv-canton.sv-2-migration-0.ciperiodic - Aborting because of caught exception
65179:Aborting after the wait time: organization/sv-canton/sv-canton.sv-1-migration-0.ciperiodic - Aborting because of caught exception
75017:Aborting: organization/sv-canton/sv-canton.sv-1-migration-0.ciperiodic - Aborting because of caught exception
```

```
$ sed 's/\x1b\[[0-9;]*m//g' log/10268/*.txt | sed -n '30674,30675p;30692,30697p' | cut -c1-260
[organization/sv/sv.sv-2.ciperiodic]^C received; cancelling. If you would like to terminate immediately, press ^C again.
Note that terminating immediately may lead to orphaned resources and other inconsistencies.
[organization/sv/sv.sv-1.ciperiodic] - error - error: 1 error occurred:
	* Helm release "sv-1/participant" failed to initialize completely. Use Helm CLI to investigate: failed to become available within allocated timeout. Error: Helm Release sv-1/participant: context canceled


[organization/sv/sv.sv-1.ciperiodic] - error - error: 1 error occurred:
	* Helm release "sv-1/scan" failed to initialize completely. Use Helm CLI to investigate: failed to become available within allocated timeout. Error: Helm Release sv-1/scan: context canceled
```

## 4. The code: #7546 added a third, mandatory message type

```
$ git show 5375bb71c3 -- cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts cluster/configs | sed -n '1,14p;/^diff/,$p'
commit 5375bb71c3c18a47cc6c107192359533feb1bfc1
Author: moritzkiefer-da <45630097+moritzkiefer-da@users.noreply.github.com>
Date:   Wed Sep 30 16:12:50 2026 +0200

    Configure confirmation response limits from configs-private (#7546)
    
    [static]
    
    to be merged once
    https://github.com/canton-foundation/configs-private/pull/3673/changes
    is merged.
    
    Signed-off-by: moritz.kiefer@digitalasset.com <moritz.kiefer@purelyfunctional.org>
    Co-authored-by: moritz.kiefer@digitalasset.com <moritz.kiefer@purelyfunctional.org>
diff --git a/cluster/configs/configs-private/configs/TestNet/sequencer-rate-limits.json b/cluster/configs/configs-private/configs/TestNet/sequencer-rate-limits.json
index 0d25eb9a19..5a14bfc37e 100644
--- a/cluster/configs/configs-private/configs/TestNet/sequencer-rate-limits.json
+++ b/cluster/configs/configs-private/configs/TestNet/sequencer-rate-limits.json
@@ -6,6 +6,12 @@
             "globalKbpsCap": 200,
             "perClientKbpsCap": 100
         },
+        "confirmationResponse": {
+            "globalTpsCap": 6,
+            "perClientTpsCap": 5,
+            "globalKbpsCap": 600,
+            "perClientKbpsCap": 500
+        },
         "topology": {
             "globalTpsCap": 4,
             "perClientTpsCap": 3,
diff --git a/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts b/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts
index f281cdcb08..5c8cf16080 100644
--- a/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts
+++ b/cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts
@@ -52,6 +52,7 @@ const getSequencerRateLimitConfig = (): string | undefined => {
     ].join('\n');
   return [
     limitsForMessageType('confirmation-request', rateLimits.messages.confirmationRequest),
+    limitsForMessageType('confirmation-response', rateLimits.messages.confirmationResponse),
     limitsForMessageType('topology', rateLimits.messages.topology),
   ].join('\n');
 };
```

`decentralizedSynchronizerNode.ts:47:36` in the trace is `config.globalTpsCap` inside `limitsForMessageType`, called
from line 55, the new `confirmationResponse` line. So `rateLimits.messages.confirmationResponse` is `undefined`.
`confirmationRequest` and `topology`, at lines 54 and 56, are present, because line 55 is reached only after 54 and
56 is never reached. All three deployed versions contain #7546:

```
$ for s in 7aaa1e94 c79eb214 5592838f; do echo "$s $(git merge-base --is-ancestor 5375bb71c3 $s && echo contains-7546 || echo lacks-7546) $(git show -s --format='%ci %s' $s)"; done
7aaa1e94 contains-7546 2026-09-30 14:59:27 +0000 Use import.meta.dirname instead of deprecated __dirname in vite config (#7536)
c79eb214 contains-7546 2026-09-30 15:45:32 +0000 Bump vitest to 4.1.11 in token-standard/ (#7532)
5592838f contains-7546 2026-09-30 17:47:12 +0200 Don't `enablePublicTokenRegistry` by default (#7547)
```

## 5. Why the splice repo's own check passes: it only has the TestNet stub, the CI clusters read DevNet

`getPathToPrivateConfigFile` returns `${PRIVATE_CONFIGS_PATH}/configs/${clusterDirectory}/<file>` and
`clusterDirectory` is `DevNet` when `IS_DEVNET` is set (`cluster/pulumi/common/src/utils.ts:164-191`, origin/main). In
the CI deploy, `PRIVATE_CONFIGS_PATH` is the internal repo's `cluster/configs/configs-private`, not the splice stub, and
`IS_DEVNET=1`:

```
$ for r in 10264 10265 10266 10267 10268; do echo "== $r"; f=$(ls log/$r/*.txt); sed 's/\x1b\[[0-9;]*m//g' $f | grep -a -c 'could not read JSON'; sed 's/\x1b\[[0-9;]*m//g' $f | grep -a -o -E '[^ "]*configs-private[^ "]*' | sort | uniq -c; sed 's/\x1b\[[0-9;]*m//g' $f | grep -a -m1 -o 'Environment Flag IS_DEVNET = .*'; done
== 10264
0
    155 /home/********/project/cluster/configs/configs-private
Environment Flag IS_DEVNET = true (1)
== 10265
0
    155 /home/********/project/cluster/configs/configs-private
Environment Flag IS_DEVNET = true (1)
== 10266
0
    158 /home/********/project/cluster/configs/configs-private
Environment Flag IS_DEVNET = true (1)
== 10267
0
    156 /home/********/project/cluster/configs/configs-private
Environment Flag IS_DEVNET = true (1)
== 10268
0
    145 /home/********/project/cluster/configs/configs-private
Environment Flag IS_DEVNET = true (1)
```

No `could not read JSON` line, from `loadJsonFromFile` (`utils.ts:145-154`), so the DevNet file exists and parses.
It just lacks `messages.confirmationResponse`. The splice stub has only `configs/TestNet/`, which #7546 updated, so
`cluster/expected/sv-canton/expected.json` (also updated in #7546) and the static checks passed:

```
$ ls cluster/configs/configs-private/configs/
TestNet
```

## Verdict

- New family Q (deployment config regression). The same cause accounts for all five refs; 10264 is the parent. Not a
  flake. Every cimain and ciperiodic deploy that pulls a splice with #7546 fails until the DevNet config has
  `confirmationResponse` or the code tolerates its absence. The deploy right before the #7546 merge was not
  looked at; the first failing attempt here is 15:36:43Z, 1 h 24 min after the merge.
- No splice commit after 5375bb71c3 touches the function or the stub (`git log 5375bb71c3..origin/main --
  cluster/pulumi/sv-canton/src/decentralizedSynchronizerNode.ts cluster/configs/configs-private` is empty at
  origin/main 9c7ffd2cbb).
- Fix, owner #7546 (moritzkiefer-da), either of:
  1. configs-private: add `messages.confirmationResponse` to `configs/DevNet/sequencer-rate-limits.json`, plus any other
     cluster directory whose file lacks it, and make sure the internal repo's configs-private checkout includes it.
     This is what #7546's description assumes (configs-private#3673).
  2. splice: make `getSequencerRateLimitConfig` skip message types absent from the file, so a new limit can land
     before every private config has it. Otherwise the same ordering hazard recurs on the next message type.
  Reverting line 55 also unblocks CI immediately.
- Clusters: every failed attempt cancelled sv, validator1 and splitwell stacks mid-create. cimain and ciperiodic were
  left partly deployed with pending Pulumi operations; the next successful deploy has to clear them (10268's final
  attempt shows those stacks can complete).
- Not verified: the content of the DevNet file and the state of configs-private#3673. Neither is readable from the
  sandbox (`gh api repos/canton-foundation/configs-private/pulls/3673` and the internal repo both return 404). No
  cluster logs were needed; the step logs show the program exception directly.
