# 11542 - cimain preflight_sv_check: no validator-app pod in namespace sv, SV runbook pods in ImagePullBackOff on the istio sidecar

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the cluster. Deploy cimain on 2026-11-17
(all times UTC), splice 772d394449, 0.10.3-snapshot.20261117.4399.0.v772d3944. The job ran no test: its wait for
the validator-app pod of namespace sv (the SV runbook node) found no pod matching the selector 201 times from
16:16:59 to 16:35:14 and gave up. It is the same wait, on the same namespace, as the preflight_check job of the same
run (11541), started 4 minutes later. During the whole wait the 9 pods of namespace sv on the node the autoscaler
had just added stayed in ImagePullBackOff on `registry.istio.io/release/proxyv2:1.30.5` (403 Forbidden on the
anonymous token, first at 16:01:58); no pod of that namespace was created or started in the window, so the
validator release of the SV runbook never existed. Cause is outside splice (registry or node egress); nothing to
fix in the test.

- Workflow / job: Deploy cimain, job preflight_sv_check (Deploy basic / SV preflight check). The run has 5 failed
  jobs; the other refs are in the 11541 packet.
- Public splice commit: 772d394449; cluster version 0.10.3-snapshot.20261117.4399.0.v772d3944.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice 772d394449, from
  the 11541 packet of the same run).
- Component: infra (image pull on a freshly added cluster node), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under). Mechanism (node
  scale-up at 16:01:13, all other nodes with the image cached) is in
  `evidence/11541-cimain-preflight-sv-runbook-pods-istio-proxy-pull-403.md`, not repeated here.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

No sbt run, no failed test, no checkErrors: the job dies in the wait before the tests. The wait for the
validator-app pod of namespace sv starts at 16:16:59, finds no matching pod on each of 201 tries and gives up at
16:35:14; the job exits 1. This is the first form of the family BE signature (no matching pod until the wait runs
out of attempts).

## 2. Family BE check: the 403 on the sidecar image, one node only

Failed image pulls of the cluster from the start of the runbook deploys (15:55) to the end of this job (16:36): 45
in namespace sv and 5 in namespace validator, all for `registry.istio.io/release/proxyv2:1.30.5`, all on the one
new node, all 403 Forbidden; plus one transient timeout on another image. First at 16:01:58, last at 16:05:46. The
45 in namespace sv are 5 each for 9 pods: sequencer, mediator, their 3 pge pods, scan-app,
scan-web-ui, sv-app and sv-web-ui. Same node, image and error as 11541. The pull-failure events stop at 16:05:46
because the kubelet then reports the pods under the back-off reasons.

## 3. During this job's wait (16:16-16:36): same 9 pods still in back-off, nothing created or started in sv

In that window namespace sv has 36 ImagePullBackOff and 36 back-off events on the sidecar image, 4 for each of the
same 9 pods, all on the new node (first 16:16:18, last 16:32:48), and no event of a pod being pulled, created or
started. There is no event at all for an object named validator-app in namespace sv from 15:56 to 16:36: the pod
this job waits for was never created, because the SV runbook deploy (ref 11546) was still retrying its Canton and
SV app releases (11541 packet, section 2).

## Verdict

Duplicate of 11541, family BE (new cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real
infra failure, not a test flake; nothing to change in splice tests. Fix location is infra, as in the family entry
(serve the istio images from a registry the cluster controls); the reason for the 403 is still open there.
No fix branch. Close as duplicate of 11541 proposed, not done.

Not verified: the splice sha and the canton pin are taken from the 11541 packet of the same run and from the
cluster version string, not re-derived. The 403 Forbidden answer itself is logged only until 16:05:46; for the
job's own window the evidence is the back-off events on the same node, image and pods.
