# 11544 - cimain preflight_validator_check: validator-app pod of the validator runbook never Ready, ImagePullBackOff on the istio sidecar

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the cluster. Deploy cimain on 2026-11-17
(all times UTC), splice 772d394449, 0.10.3-snapshot.20261117.4399.0.v772d3944. The job ran no test: its wait for
the validator-app pod of namespace validator (the validator runbook node) timed out on that pod 201 times from
16:11:17 to 16:38:13 and gave up. The pod was created at 16:00:35, did not fit on any existing node, triggered the
scale-up and was scheduled at 16:02:22 onto the new node, the node of 11541. There its sidecar image
`registry.istio.io/release/proxyv2:1.30.5` got 403 Forbidden on the anonymous token five times (16:02:36-16:05:46)
and the pod stayed in ImagePullBackOff through the end of the job (last back-off event 16:37:39). Cause is outside
splice (registry or node egress); nothing to fix in the test.

- Workflow / job: Deploy cimain, job preflight_validator_check (Deploy basic / Validator preflight check). The run
  has 5 failed jobs; the other refs are in the 11541 packet.
- Public splice commit: 772d394449; cluster version 0.10.3-snapshot.20261117.4399.0.v772d3944.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice 772d394449, from
  the 11541 packet of the same run).
- Component: infra (image pull on a freshly added cluster node), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under). Mechanism (node
  scale-up at 16:01:13, all other nodes with the image cached) is in
  `evidence/11541-cimain-preflight-sv-runbook-pods-istio-proxy-pull-403.md`, not repeated here.
- Difference to 11541 and 11542: those waited on namespace sv, where the validator release was never created (no
  matching pod). Here the pod exists and is itself one of the pods stuck on the pull, which is the second form of
  the family BE signature (timed out waiting for the validator-app pod to become Ready).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

No sbt run, no failed test, no checkErrors: the job dies in the wait before the tests. The cluster reports version
0.10.3-snapshot.20261117.4399.0.v772d3944 at 16:11:11, the wait for the validator-app pod of namespace validator
starts at 16:11:14, and each of its 201 tries (16:11:17 to 16:38:13) times out on the same existing pod; the job
then exits 1. The wait never reports a missing pod.

## 2. Family BE check: the 403 on the sidecar image, one node only

Failed image pulls of the cluster from the start of the runbook deploys (15:55) to the end of this job (16:39): 45
in namespace sv and 5 in namespace validator, all for `registry.istio.io/release/proxyv2:1.30.5`, all on the one
new node, all 403 Forbidden on the anonymous token request; plus one transient timeout on another image. Same node,
image and error as 11541. The 5 failures in namespace validator (16:02:36, 16:02:48, 16:03:17, 16:04:12, 16:05:46)
are on exactly the pod this job waits for.

## 3. Life of the awaited pod, 15:55-16:39

- 16:00:35: the validator-app deployment is scaled up from 0 to 1 and the pod is created.
- The pod is unschedulable (insufficient CPU on 9 nodes, insufficient memory on 3, untolerated taints on 5 or 6)
  and triggers the scale-up.
- 16:02:22: it is scheduled on the new node and its volume is attached.
- 16:02:36 to 16:37:39: 5 pull attempts of the sidecar image, 5 failed pulls, then 22 ImagePullBackOff and 27
  back-off events.

There is no event of the image being pulled or of a container being started for this pod in the whole window.

## Verdict

Duplicate of 11541, family BE (new cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real
infra failure, not a test flake; nothing to change in splice tests. Fix location is infra, as in the family entry
(serve the istio images from a registry the cluster controls); the reason for the 403 is still open there.
No fix branch. Close as duplicate of 11541 proposed, not done.

Not verified: the splice sha and the canton pin are taken from the 11541 packet of the same run and from the
cluster version string, not re-derived. The 403 Forbidden answer itself is logged only until 16:05:46; for the rest
of the job's window the evidence is the back-off events on the same pod, node and image. The failed validator
runbook deploy job of the same run was not read for this packet.
