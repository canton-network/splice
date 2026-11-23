# 11555 - cimain deploy_cluster: splitwell validator postgres and sv-da-1 participant postgres never Ready, 8 pods stuck on the istio sidecar pull on the node of 11541

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the cimain cluster. On 2026-11-17 (all times
UTC) the step that applies the pulumi configuration to the cluster (20:15:30-20:40:26) fails on the first attempt:
the validator postgres StatefulSet of splitwell (20:29:21) and the participant postgres StatefulSet of sv-da-1
(20:29:28) timed out waiting to be Ready with their pods still initializing on the istio init and sidecar
containers, and the Helm release global-domain-0 of sv-3 was not available within its timeout. The later attempts
fail on a locked stack and canceled updates (all 11 operations failed at 20:32:34 and 20:40:26, then the retries
are exhausted). The kubelet of the node of 11541, 11548 and 11552 fails to pull
`registry.istio.io/release/proxyv2:1.30.5` with 403 Forbidden for 8 pods of this deploy (40 events, 5 per pod,
20:19:34-20:23:29), among them exactly those two postgres pods and the sv-3 mediator; nine other nodes find the
image already present. Cause is outside splice (registry or node egress).

- Workflow / job: Deploy cimain (manually dispatched), job deploy_cluster (Deploy basic / Deploy cluster),
  20:14:11-20:40:44. It is the only failed job of the run.
- Public splice commit: d52fe77b48 (#8550).
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice d52fe77b48).
- Component: infra (image pull on one cluster node), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

The two StatefulSet errors come at 20:29:21 (splitwell validator postgres) and 20:29:28 (sv-da-1 participant
postgres). The Helm release global-domain-0 of sv-3 fails on its timeout at 20:29:52 and triggers the abort at
20:30:02, which cancels the other stacks. Seven Helm releases report that they were created but failed: the
splitwell validator, global-domain-0, scan and sv-app of sv-3, scan and sv-app of sv-da-1, and the validator1
validator. From 20:32:17 the errors are a locked stack (38 lines) and canceled updates;
the summaries at 20:32:34 and 20:40:26 list all 11 operations as failed, and the retries are exhausted at
20:40:26. A warning about a pod still initializing appears for 25 postgres pods in the log; most are transient
(family note of 11548): only the two StatefulSets above time out on it.

## 2. Family BE check: the sidecar image pull is refused with 403

Failed image pulls of the cluster from 19:45 to 20:45: 40, all `registry.istio.io/release/proxyv2:1.30.5` with 403
Forbidden on the anonymous token request, all from the one node of 11541, 11548 and 11552, 5 each for 8 pods, from
20:19:34 to 20:23:29. The pods: the validator postgres pod of splitwell; in sv-3 the mediator and two pge pods
of the mediator and sequencer; in sv-da-1 the participant postgres pod and scan-app; in validator1
validator-app and one pge pod. The splitwell validator postgres pod and the sv-da-1 participant
postgres pod are the two pods of section 1; the three sv-3 pods explain the Helm timeout of global-domain-0 in
sv-3, scan-app of sv-da-1 the one of scan in sv-da-1, validator-app of validator1 the one of the validator1
validator release.

## 3. Other nodes have the image; the node never pulls it and was not replaced

From 19:45 to 20:45 the same nine nodes as in 11548 and 11552 start sidecar pods from the cached image (10 to 40
events each of the image already present); the node of 11541 has 200 back-off events (19:47:03 to 20:44:36) and no
successful pull. The back-off events start with pods of the previous deploy (11552), and no node registers or
becomes Ready from 19:00 to 20:45, so the node survived the cluster reset job of this run (19:48:53-19:54:46)
without the image and the scheduler placed 8 pods of the new deploy on it.

## Verdict

Duplicate of 11541, family BE (cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real infra
failure, not a flake of the deploy code; nothing to change in splice. Fix location is infra, as in the family entry
(serve the istio images from a registry the cluster controls, or drain and replace the node); the reason for the 403
is still open there. Fourth cimain run of 2026-11-17 lost to this node: cimain deploys are hard broken until the
node is drained or the registry answers again. No fix branch. Close as duplicate of 11541 proposed, not done.

Not verified: container logs of the affected pods were not queried (the family entry states there are none).
Whether the registry still answers 403 after 20:45 was not checked. How many of the 6 attempts ran in full was not
counted: the log has two operation summaries, both with all 11 operations failed on locks or cancels. The skipped
jobs of the run (runbooks, preflights) did not run.
