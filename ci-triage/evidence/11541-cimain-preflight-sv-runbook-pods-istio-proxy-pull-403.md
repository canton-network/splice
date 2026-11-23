# 11541 - cimain preflight_check: SV runbook never came up, new node cannot pull the istio sidecar image (registry.istio.io 403)

New, not a duplicate of a known family (family BE added). Deploy cimain on 2026-11-17 (all times UTC), splice
772d394449, 0.10.3-snapshot.20261117.4399.0.v772d3944. The job ran no test: its first step waits for the
validator-app pod in namespace sv (the SV runbook node) and gives up after 201 tries in 18 min, because the pod was
never created. The SV runbook deploy of the same run (ref 11546) was still failing: every pod of namespace sv
scheduled on the node that the autoscaler added at 16:01:13 for that deploy sat in ImagePullBackOff on the istio
sidecar image `registry.istio.io/release/proxyv2:1.30.5`: the registry answered 403 Forbidden to the anonymous
token request, from 16:01:58 until at least 18:35. All other nodes had pulled the image between 15:41:53 and
15:53:33 and had it cached. Cause is outside splice code (registry or node egress); nothing to fix in the test.

- Workflow / job: Deploy cimain, job preflight_check (Deploy basic / Preflight check).
- Public splice commit: 772d394449; cluster version 0.10.3-snapshot.20261117.4399.0.v772d3944.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice 772d394449).
- Component: infra (image pull on a freshly added cluster node), not splice, not Canton.
- Same run, same cause, other refs: 11542 preflight_sv_check, 11544 preflight_validator_check, 11545
  deploy_validator_runbook, 11546 deploy_sv_runbook.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

No sbt run, no failed test, no checkErrors: the job dies in the wait before the tests. The waits for splitwell,
sv-1, validator1, sv-2, sv-3 and sv-da-1 each passed within a second (16:12:50-16:12:58). The wait for the
validator-app pod of namespace sv started at 16:12:59 and found no pod matching the selector on each of its 201
tries, until it gave up at 16:31:13 and the job exited 1. There was no validator-app pod in namespace sv at all.

## 2. The SV runbook deploy of the same run was failing at that time

The preflight job does not depend on the runbook deploy jobs, it only waits for their pods. The SV runbook deploy
(ref 11546) never got past its Canton and SV app releases: from 16:10:52 the Helm releases scan, sv-app and
global-domain-0 of namespace sv each failed to become available within the timeout, and the job retried the deploy
until its retries were exhausted at 17:09:17. The validator release of that node was therefore never created.

## 3. Not the 11539 deadlock (family BD): the joining SV's containers never started

Family BD needs the joining SV app polling its sponsor. Here, from 15:58 to 16:32, no SV app in the cluster
logged an onboarding snapshot line, and namespace sv has no log line from an SV app, scan, validator, sequencer
or mediator container; only the participant (on an older node) started.

## 4. Kubernetes events of namespace sv: unschedulable, then a new node, then ImagePullBackOff on the sidecar

- 16:00:54-16:00:57: the sv-app and sequencer pods are unschedulable; none of the 14 nodes has room (9 with
  insufficient CPU, 1 with insufficient memory, 5 with untolerated taints).
- 16:01:11-16:01:15: a new node starts its kubelet, registers (16:01:13) and becomes Ready (16:01:15).
- 16:01:17: the sequencer pod is scheduled on the new node; 16:02:05 it is in ImagePullBackOff.
- 16:02:22: the sv-app pod is scheduled on the same node; 16:02:37 it is in ImagePullBackOff.

## 5. The pull error: registry.istio.io refuses the anonymous token, only on the new node

The first failed pull is at 16:01:58 on the new node: the image `registry.istio.io/release/proxyv2:1.30.5` cannot
be resolved because the request for an anonymous pull token to registry.istio.io is answered with 403 Forbidden.
In the window of the SV namespace events there are 45 such failures, all for this image and all on the new node.
Over the whole cluster from 15:30 to 17:15 the failed image pulls are: 45 in namespace sv and 5 in namespace
validator, all this image, this node and 403 Forbidden; plus 5 single transient timeouts on other images that
recovered. The 5 in namespace validator are the validator runbook pod on the same node (refs 11544, 11545).

## 6. Every other node pulled the same image earlier in the run; the new node never did

Sidecar image events per node, 15:30-17:15: nine other nodes each pulled the image successfully at least once,
the first at 15:41:53 and the last at 15:53:33, and afterwards start pods from the cached copy. The new node has
52 pull attempts, 50 failures and 338 back-off events and no successful pull; its last back-off event in the window
is at 17:12:50. From 17:15 to 20:30 the same node fails 75 more times with 403 Forbidden (17:28 to 18:35) and no
node makes a fresh successful pull of the image.

## 7. Where the image reference comes from

The sidecar image is the default of the istio charts at the version pinned in splice; no mirror or pull secret is
configured for it.
```
git -C ~/Projects/splice grep -n -i -E 'registry\.istio\.io|proxyv2' origin/main -- cluster ':!cluster/expected' | wc -l
git -C ~/Projects/splice grep -n "istio: '" origin/main -- cluster/pulumi/infra/src/istio.ts
git -C ~/Projects/splice log origin/main -1 --format='%h %cs %s' -L36,36:cluster/pulumi/infra/src/istio.ts | head -1
```
```
0
origin/main:cluster/pulumi/infra/src/istio.ts:36:  istio: '1.30.5',
30f013b4fe 2026-10-12 Bump istio to 1.30.5 (#7454)
```

## Verdict

New; infra. Not a duplicate of an existing issue: tracker searches for proxyv2, istio (since 2026-10-15) and
ImagePullBackOff return only closed issues from other causes (8360 a 502, 7027 a rerun collision), and the family
BD check (sponsor onboarding snapshot lines) finds nothing. Real for the run, not a test flake: the preflight
correctly reports that the SV runbook node is not there.

Chain: SV runbook deploy needs more CPU than the 14 nodes have (16:00:51 unschedulable) -> autoscaler adds a node
(ready 16:01:15) -> its first pull of `registry.istio.io/release/proxyv2:1.30.5` gets 403 Forbidden on the
anonymous token request (16:01:58) and keeps getting it (last seen 18:35) -> sequencer, mediator, scan, SV app and
both web UIs of namespace sv, and the validator runbook pod, stay in ImagePullBackOff -> Helm releases time out
(16:10:52) -> the validator release of namespace sv is never created -> preflight_check finds no validator-app pod
in namespace sv for 18 min and exits 1 (16:31:13).

Fix location: not the test, not the splice apps. Options, all outside a small splice change: serve the istio images
from a registry the cluster controls (a remote or mirrored repository, `global.hub` of the istio charts in
`cluster/pulumi/infra/src/istio.ts`), or find out why registry.istio.io refuses this node. No fix branch.

Refs of the same run with the same cause: 11542, 11544, 11545, 11546. Their jobs were read only as far as sections
1, 2 and 5 need (11542: the same 201 tries with no matching pod in namespace sv; 11544: timed out waiting on the
validator-app pod of section 5; 11545, 11546: Helm releases not available within the timeout). Suggested: keep one
of them as parent (11546 is the deploy job, 11541 the first filed) and close the rest as duplicates; not done, the
choice is the owner's.

NOT verified:
- Why the registry answers 403: a registry-side change or rate limit after 15:53, or something specific to the new
  node (egress address). No node made a fresh successful pull after 15:53:33, so the two cannot be told apart from
  the events. No request was made to the registry.
- Whether the two later Deploy cimain failures of the same day (both in the deploy_cluster job) have this cause;
  their jobs were not read for this packet. The node kept failing during both (17:28-18:35).
- Whether the node recovered or was removed after 18:35.
