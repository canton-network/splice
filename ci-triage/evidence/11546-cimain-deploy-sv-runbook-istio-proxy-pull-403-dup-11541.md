# 11546 - cimain deploy_sv_runbook: Helm releases global-domain-0, scan, sv-app of namespace sv never available, 9 pods in ImagePullBackOff on the istio sidecar

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the cluster. Deploy cimain on 2026-11-17
(all times UTC), splice 0.10.3-snapshot.20261117.4399.0.v772d3944. The SV runbook deploy runs three pulumi
operations: the one with the participant and postgres succeeds, the Canton one fails on the Helm release
global-domain-0 and the SV runbook one fails on the releases scan and sv-app, each not available within its
timeout, on the first attempt (16:00:44-16:11:04) and on all 5 retries (last 17:09:16). All 9 pods of those three
releases (sequencer, mediator, their 3 pge pods, scan-app, scan-web-ui, sv-app, sv-web-ui)
were scheduled on the new autoscaler node, the node of 11541. Their sidecar image
`registry.istio.io/release/proxyv2:1.30.5` got 403 Forbidden on the anonymous token 5 times per pod
(16:01:58-16:05:29) and they stayed in ImagePullBackOff through the end of the job (last back-off event 17:07:38);
none of them ever started. The 9 other pods of namespace sv run on other nodes. Cause is outside splice (registry
or node egress).

- Workflow / job: Deploy cimain, job deploy_sv_runbook (Deploy basic / Deploy SV runbook). The other 4 failed jobs
  of the run are 11541, 11542, 11544, 11545.
- Cluster / splice version: 0.10.3-snapshot.20261117.4399.0.v772d3944.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (from the 11541 packet of the same run, not re-derived).
- Component: infra (image pull on a freshly added cluster node), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under). Mechanism (node
  scale-up, all other nodes with the image cached) is in
  `evidence/11541-cimain-preflight-sv-runbook-pods-istio-proxy-pull-403.md`, not repeated here.
- Relation to the siblings: this is the deploy job that created the stuck sv pods; 11541 and 11542 are the
  preflights that waited for a validator-app pod in namespace sv, which this job never got to create.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

The three operations start together at 15:58:42. On each of the 6 attempts (started 15:58:42, 16:11:39, 16:23:48,
16:35:24, 16:46:49, 16:58:22) the operation with the participant and postgres succeeds within about two minutes
and the two operations with the sequencer, mediator, scan and SV app releases fail about ten minutes later. The
first failure is the scan release at 16:10:52: created, but not available within the timeout. Each of the three
releases global-domain-0, scan and sv-app reports that error on every attempt. The retries are exhausted at
17:09:17 and the job exits 1.

## 2. Family BE check: the 403 on the sidecar image, one node only

Failed image pulls of the cluster over the whole job (15:55-17:10): 45 in namespace sv and 5 in namespace
validator, all for `registry.istio.io/release/proxyv2:1.30.5`, all on the one new node, all 403 Forbidden on the
anonymous token request; plus one transient timeout on another image. In namespace sv the first is at 16:01:58 and
the last at 16:05:29, 5 for each of 9 pods. Same node, image and error as 11541. The 9 pods are exactly the pods of
the three failing releases (global-domain-0: sequencer, mediator and their pge pods; scan:
scan-app, scan-web-ui; sv-app: sv-app, sv-web-ui).

## 3. Namespace sv, 15:55-17:10: the 9 pods on the new node never start, the 9 others do

18 pods are scheduled in the namespace and 9 start. The 9 that start (participant, postgres and pge pods) are on two
other nodes. The 9 on the new node have no container-started event; each carries 32 or 33 back-off events on the
sidecar image from 16:01:58 to 17:07:38. They are the same pods throughout (same ReplicaSets, never recreated), so
the 5 retries re-waited on the same stuck pods.

## Verdict

Duplicate of 11541, family BE (new cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real
infra failure, not a flake of the deploy code; nothing to change in splice. Fix location is infra, as in the
family entry (serve the istio images from a registry the cluster controls); the reason for the 403 is still open
there. No fix branch. Close as duplicate of 11541 proposed, not done.

Not verified: the canton pin is taken from the 11541 packet of the same run; the splice version is the cluster
version string. The 403 Forbidden answer itself is logged only until 16:05:29 in namespace sv; for the rest of the
job's window the evidence is the back-off events on the same pods, node and image. Container logs of the affected
pods were not queried (the family entry states there are none).
