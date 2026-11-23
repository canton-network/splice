# 11548 - cimain deploy_cluster: sv-3 mediator postgres and sv-1 global-domain-0 never Ready, 8 pods stuck on the istio sidecar pull on the node of 11541

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the cimain cluster. On 2026-11-17 (all times
UTC) the step that applies the pulumi configuration to the cluster (17:24:28-18:01:20) fails on the first attempt
and on all 5 retries. First attempt: the mediator postgres StatefulSet of sv-3 timed out waiting to be Ready with
its pod still initializing on the istio init and sidecar containers (17:38:22), and the Helm release
global-domain-0 of sv-1 was not available within its timeout (17:38:39); the abort cancels the other running
stacks. A retry fails on the same StatefulSet at 17:51:36 (8 of 11 operations failed), the later ones within
minutes on a locked stack and canceled updates. The kubelet of the node of 11541 fails to pull
`registry.istio.io/release/proxyv2:1.30.5` with 403 Forbidden for 8 pods of this deploy (40 events, 5 per pod,
17:28:33-17:31:59), among them the sv-3 mediator postgres pod and the sequencer and three pge pods
of sv-1; nine other nodes find the image already present. Cause is outside splice (registry or node egress).

- Workflow / job: Deploy cimain (manually dispatched, attempt 1), job deploy_cluster (Deploy basic / Deploy
  cluster), 17:22:43-18:01:40. It is the only failed job of the run.
- Public splice commit: 408f7ed9e7.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice 408f7ed9e7).
- Component: infra (image pull on one cluster node), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

The step that applies the pulumi configuration is the only failed step of the job. Its genuine errors are the sv-3
mediator postgres StatefulSet (17:38:22, 0 of 1 replicas ready, pod still initializing on the istio init and
sidecar containers; again at 17:51:36 after a retry) and the Helm release global-domain-0 of sv-1 (17:38:39, not
available within the timeout). After the abort at 17:38:40 the other stacks are cancelled and their Helm releases
report a canceled context. The summary at 17:52:00 lists 8 of 11 operations as failed. The summaries at 17:54:27,
17:56:56 and 18:01:20 list all 11 operations as failed, on a locked stack, on pending
operations left by the previous deployment and on canceled updates; they add no new cause. The retries are
exhausted at 18:01:20. The log has four operation summaries for six attempts; the boundaries of the six attempts
were not reconstructed.

## 2. Family BE check: the sidecar image pull is refused with 403

Failed image pulls of the cluster from 17:10 to 18:05: 40, all `registry.istio.io/release/proxyv2:1.30.5` with 403
Forbidden on the anonymous token request, all from the one node of 11541 (ready 16:01:15 per that packet), 5 each
for 8 pods, from 17:28:33 to 17:31:59. The pods: in sv-1 the sequencer and three pge pods (one of the
mediator, two of the sequencer); in sv-2 one pge pod; in sv-3 the mediator postgres pod and
scan-app; in sv-da-1 scan-web-ui. The sv-3 mediator postgres pod is the pod of the first error in section 1; the
sequencer and the three pge pods of sv-1 belong to the Helm release global-domain-0, the second
error. The sv-2, sv-3 scan and sv-da-1 pods belong to stacks that were cancelled before their own timeout.

## 3. Other nodes have the image; the node never pulls it

From 17:10 to 18:05 nine nodes start sidecar pods from the cached image (10 to 30 events each of the image already
present); the node of 11541 has 235 back-off events (17:11:19 to 18:04:21) and no successful pull at all. Its
back-off set also contains 10 pods of namespaces sv and validator (the runbook pods of 11541-11546, the previous
run), still backing off at 17:11: the node survived the cluster reset job of this run (17:14:09-17:21:22) without
the image, and the scheduler placed 8 pods of the new deploy on it.

## Verdict

Duplicate of 11541, family BE (new cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real
infra failure, not a flake of the deploy code; nothing to change in splice. Fix location is infra, as in the family
entry (serve the istio images from a registry the cluster controls, or drain and replace the node); the reason for
the 403 is still open there. New in this occurrence: the broken node persists across a cluster reset, so every
cimain deploy fails until the node is gone or can pull. No fix branch. Close as duplicate of 11541 proposed, not
done.

Not verified: container logs of the affected pods were not queried (the family entry states there are none).
Whether the registry still answers 403 after 18:05 was not checked. The pending operations reported at the start of
the first attempt (7 on the shared network stack, 1 each on validator1 and splitwell, left by the previous deploy)
were not followed; the first attempt's own two errors are both pods on the node above. The skipped jobs of the run
(runbooks, preflights) did not run.
