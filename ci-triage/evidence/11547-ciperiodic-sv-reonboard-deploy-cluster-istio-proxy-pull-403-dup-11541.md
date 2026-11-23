# 11547 - ciperiodic deploy_sv_reonboard deploy_cluster: 10 of 11 pulumi operations fail, 25 postgres pods stuck in PodInitializing on the istio sidecar pull

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the ciperiodic cluster. On 2026-11-17 (all
times UTC) the step that applies the pulumi configuration to the cluster (16:49:50-17:58:00) fails 10 of 11
operations on the first attempt and on all 5 retries; only the operation for the shared network stack succeeds.
Every error is a postgres StatefulSet that timed out waiting to be Ready with its pod still initializing on the
istio init and sidecar containers: 25 distinct pods in namespaces sv-1, sv-2, sv-3, sv-da-1, splitwell and
validator1. The three app nodes of the cluster became Ready at 16:51:51-16:52:24, after the cluster reset; from
16:52:15 the kubelets of all three fail to pull `registry.istio.io/release/proxyv2:1.30.5` with 403 Forbidden (130
events, 26 pods, 5 per pod, until 16:56:50), then only back-off events until the end of the job (last 18:03:54).
No node pulls the sidecar image in the window. Cause is outside splice (registry or node egress).

- Workflow / job: ciperiodic, workflow deploy_sv_reonboard, job deploy_cluster.
- Public splice commit: 408f7ed9e7.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice 408f7ed9e7).
- Component: infra (image pull on freshly created cluster nodes), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

The job (16:44:20-17:58:30) ends canceled; its only step that does not succeed is the one that applies the pulumi
configuration to the cluster. Its first error block is for the Canton stack of sv-1: the BFT sequencer postgres
StatefulSet timed out waiting to be Ready, 0 of 1 replicas passed the readiness checks, and its pod was still
initializing on the istio init and sidecar containers. The step ends with a summary of 11 operations run and 10
failed, exhausted retries and exit status 1.

On the last attempt the shared network stack succeeds and these ten operations fail: the Canton stack and the SV
stack of each of sv-1, sv-2, sv-3 and sv-da-1, and the splitwell and validator1 stacks. All pods named in the
errors are in the same state, still initializing on the istio init and sidecar containers; they are 25 distinct
postgres pods (among them sequencer, BFT sequencer, mediator, participant, splitwell and validator databases)
across the six namespaces.

## 2. Family BE check: the sidecar image pull is refused with 403

Failed image pulls of the cluster from 16:40 to 18:05: 133. Of these, 130 are
`registry.istio.io/release/proxyv2:1.30.5` with 403 Forbidden on the anonymous token request: 65, 60 and 5 on the
three app nodes, 5 per pod for 26 pods, in exactly the six namespaces of the failed operations (sv-1 25, sv-2 25,
sv-3 25, sv-da-1 25, splitwell 15, validator1 15), from 16:52:15 to 16:56:50. The other 3 are failures on other
images that are not 403 and not part of this.

## 3. The nodes are new, and nothing pulls the image afterwards

The three app nodes become Ready and register between 16:51:51 and 16:52:25, 24 s or less before the first 403
(16:52:15). From 16:52:15 to 18:03:54 they carry 441, 408 and 34 back-off events on the sidecar image. There is no
event of the sidecar image being pulled or found already present on any node in the window: unlike 11541 (one new
node next to nodes with the image cached), here every app node is new, so no sidecar pod of the deployment starts
and 10 of 11 operations fail instead of the stacks of one namespace. Same registry answer, same image and version,
same day as 11541 (cimain, first 403 16:01:58, still 403 at 18:35).

## Verdict

Duplicate of 11541, family BE (new cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real
infra failure, not a flake of the deploy code; nothing to change in splice. Fix location is infra, as in the family
entry (serve the istio images from a registry the cluster controls); the reason for the 403 is still open there.
No fix branch. Close as duplicate of 11541 proposed, not done.

Not verified: only the tail of the step output was available, so the operation counts in section 1 are those of the
last attempt, and the earlier attempts are covered only by the exhausted retries and by the back-off events
spanning the whole step. The step output names 25 pods, the events 26; the 26th was not identified. The deployed
splice commit is taken from the deployment configuration history, not from the job itself. Container logs of the
affected pods were not queried (the family entry states there are none). The preflight jobs of the workflow did
not run (the job cancels the workflow on failure).
