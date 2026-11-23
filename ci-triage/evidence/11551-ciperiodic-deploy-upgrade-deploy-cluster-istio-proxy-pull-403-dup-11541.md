# 11551 - ciperiodic deploy_upgrade deploy_cluster_for_upgrade: 10 of 11 pulumi operations fail, 25 postgres pods stuck in PodInitializing on the istio sidecar pull

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the ciperiodic cluster. On 2026-11-17 (all
times UTC) the step that applies the pulumi configuration to the cluster fails 10 of 11 operations on the first
attempt and on all 5 retries; only the operation for the shared network stack succeeds. Every pod named in the step
output is a postgres pod still initializing on the istio init and sidecar containers: 25 distinct pods in
namespaces sv-1, sv-2, sv-3, sv-da-1, splitwell and validator1. The kubelets of the three app nodes fail to pull
`registry.istio.io/release/proxyv2:1.30.5` with 403 Forbidden 130 times (26 pods, 5 per pod, 18:23:23-18:28:02),
then only back-off events until the end of the job. No node pulls the sidecar image in the window. This is the same
cluster and the same three nodes as 11547 (deploy_sv_reonboard, 90 minutes earlier); the nodes were not recreated
in between and still have no copy of the image. Cause is outside splice (registry or node egress).

- Workflow / job: ciperiodic, workflow deploy_upgrade, job deploy_cluster_for_upgrade, canceled after 1h 14m 24s.
- Public splice commit: 48f45bf33 ("Backport #8456 to release-line-0.10.2 (#8462)").
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at splice 48f45bf33).
- Component: infra (image pull on cluster nodes without a cached sidecar image), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

Every step before it succeeds; the failing step is the one that applies the pulumi configuration to the cluster
(1h 8m 24s of the 1h 14m 24s job). The five notifications the step posts between attempts carry the timestamps
18:33:35, 18:44:52, 18:56:16, 19:07:32 and 19:18:48; the issue was filed at 19:30:43.

The first error block is for the Canton stack of sv-3: the sequencer postgres StatefulSet was created but 0 of 1
replicas passed the readiness checks. Over the whole step output:

- 6 operation summaries (first attempt plus 5 retries), each with 10 of 11 operations failed; retries exhausted
  once, at the end.
- The shared network stack succeeds; the Canton stack and the SV stack of each of sv-1, sv-2, sv-3 and sv-da-1,
  and the splitwell and validator1 stacks fail.
- 312 error blocks; 600 lines that name the istio init and sidecar containers.
- All pods named in the errors have one single state, still initializing on the istio init and sidecar containers,
  for 25 distinct postgres pods: participant, splitwell and validator databases in splitwell; participant and
  validator databases in validator1; and for each of sv-1, sv-2, sv-3, sv-da-1 the apps, mediator, participant,
  sequencer and BFT sequencer databases.
- The step output itself has no line naming the image, the registry or an image pull error; the reason is only in
  the Kubernetes events.

## 2. Family BE check: the sidecar image pull is refused with 403

Failed image pulls of the cluster from 18:05 to 19:40: 130, all `registry.istio.io/release/proxyv2:1.30.5` with
403 Forbidden on the anonymous token request: 45, 45 and 40 on the three app nodes, 5 per pod for 26 pods, in
exactly the six namespaces of the failed operations (sv-1 25, sv-2 25, sv-3 25, sv-da-1 25, splitwell 15,
validator1 15), from 18:23:23 to 18:28:02.

## 3. Same nodes as 11547, and nothing pulls the image

No node registered or became Ready between 18:05 and 19:40. The three hosts are the nodes of 11547 (Ready
16:51:51-16:52:24 in that packet), so they survived the cluster reset of this workflow without ever having pulled
the image. They carry 341, 339 and 280 back-off events on the sidecar image, and there is no event of the image
being pulled or found already present on any node in the window. The back-off events fill the whole window (first
18:06:54, before this job's pods exist, so those belong to the pods of 11547; last 19:39:59, the end of the
window). Same registry answer, image and version as 11541 (cimain, first 403 16:01:58) and 11547.

## Verdict

Duplicate of 11541, family BE (new cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real
infra failure, not a flake of the deploy code; nothing to change in splice. Fix location is infra, as in the family
entry (serve the istio images from a registry the cluster controls); the reason for the 403 is still open there.
No fix branch. Close as duplicate of 11541 proposed, not done.

For the family: the image is not on a node as long as the registry refuses, so "new node" is only how the nodes
got into that state. Every ciperiodic and cimain deploy keeps failing this way until the registry answers again
or the image comes from elsewhere; the window is now 16:01:58-18:28:02 at least.

Not verified: exact step start and end times were not read (the window above comes from the notification
timestamps in the output, the job duration and the issue creation time). The step output names 25 pods, the events
26; the 26th was not identified. The deployed splice commit is taken from the deployment configuration history, not
from the job itself. Container logs of the affected pods were not queried (the family entry states there are
none). The later jobs of the workflow did not run (the job cancels the workflow on failure).
