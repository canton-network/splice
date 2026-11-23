# 11552 - cimain deploy_cluster: sv-3 sequencer postgres and sv-2 apps postgres never Ready, 7 pods stuck on the istio sidecar pull on the node of 11541

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the cimain cluster. On 2026-11-17 (all times
UTC) the step that applies the pulumi configuration to the cluster (18:28:00-19:46:23) fails on the first attempt
and on all 5 retries, every summary with 8 of 11 operations failed. The only pods ever named in an error are the
BFT sequencer postgres pod of sv-3 and the apps postgres pod of sv-2, both still initializing on the istio init and
sidecar containers; the other failures are cancellations after the abort or Helm releases that depend on those two
databases. The kubelet of the node of 11541 and 11548 fails to pull `registry.istio.io/release/proxyv2:1.30.5` with
403 Forbidden for 7 pods of this deploy (35 events, 5 per pod, 18:32:00-18:35:24), among them exactly those two
pods; nine other nodes find the image already present. Cause is outside splice (registry or node egress).

- Workflow / job: Deploy cimain (manually dispatched), job deploy_cluster (Deploy basic / Deploy cluster),
  18:26:30-19:46:44. It is the only failed job of the run.
- Public splice commit: 02b5a51214 (#8543).
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice 02b5a51214).
- Component: infra (image pull on one cluster node), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

The step that applies the pulumi configuration is the only failed step of the job. First attempt: the BFT sequencer
postgres StatefulSet of sv-3 timed out waiting to be Ready (18:41:43); the abort at 18:41:58 cancels the other
stacks, so the apps postgres StatefulSet of sv-2 reports a cancelled operation with the same pod state and the Helm
releases report a canceled context. On each of the 5 retries (18:54, 19:07, 19:20, 19:33, 19:46) both StatefulSets
time out on their own, about 13 minutes apart, and the summary is 8 of 11 operations failed: the Canton stacks of
sv-1 and sv-3, the SV stacks of sv-1, sv-2, sv-3 and sv-da-1, validator1 and splitwell. The retries are exhausted
at 19:46:23.

Over the whole step only two pods are named in errors, 12 times each: the apps postgres pod of sv-2 and the BFT
sequencer postgres pod of sv-3, both still initializing on the istio init and sidecar containers. The log has five
operation summaries for six attempts; the first attempt prints none. The remaining failed operations (sv-1,
sv-da-1, validator1, splitwell) carry only Helm cancellations or timeouts and no pod of their own.

## 2. Family BE check: the sidecar image pull is refused with 403

Failed image pulls of the cluster from 18:00 to 19:50: 35, all `registry.istio.io/release/proxyv2:1.30.5` with 403
Forbidden on the anonymous token request, all from the one node of 11541 and 11548, 5 each for 7 pods, from
18:32:00 to 18:35:24. The pods: in sv-1 the mediator and one pge pod of the sequencer; in sv-2 the
apps postgres pod and the participant; in sv-3 the BFT sequencer postgres pod, scan-web-ui and sv-web-ui. The sv-3
BFT sequencer postgres pod and the sv-2 apps postgres pod are the two pods of section 1. The sv-1 mediator and
sequencer pge pods explain the Helm timeout of global-domain-0 in sv-1 (19:33:25), the sv-2
participant pod the one of the participant release in sv-2 (18:54:58).

## 3. Other nodes have the image; the node never pulls it

From 18:00 to 19:50 the same nine nodes as in 11548 start sidecar pods from the cached image (10 to 20 events each
of the image already present); the node of 11541 has 252 back-off events and no successful pull at all. Back-off
events start at 18:03:35, before this deploy (pods of the previous run), and continue past the end of the job (last
19:47:32): the node survived the cluster reset job of this run (18:04:05-18:10:22) without the image, and the
scheduler placed 7 pods of the new deploy on it.

## Verdict

Duplicate of 11541, family BE (cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real infra
failure, not a flake of the deploy code; nothing to change in splice. Fix location is infra, as in the family entry
(serve the istio images from a registry the cluster controls, or drain and replace the node); the reason for the 403
is still open there. Same node as 11541 and 11548: it has now broken three cimain runs of 2026-11-17 and persists
across cluster resets, so cimain deploys keep failing whenever a pod they wait on lands on it. No fix branch. Close
as duplicate of 11541 proposed, not done.

Not verified: container logs of the affected pods were not queried (the family entry states there are none).
Whether the registry still answers 403 after 19:50 was not checked. The pending operations reported at the start of
the first attempt (7 on the shared network stack, 1 each on validator1 and splitwell, left by the previous deploy)
and the warnings about already existing persistent volume claims (sv-2 sequencer, sv-1 mediator postgres, 18:31)
were not followed; neither concerns the two pods the step fails on. The skipped jobs of the run (runbooks,
preflights) did not run.
