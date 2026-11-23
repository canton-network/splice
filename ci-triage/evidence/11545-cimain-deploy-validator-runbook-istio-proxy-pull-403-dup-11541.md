# 11545 - cimain deploy_validator_runbook: Helm release validator never available, validator-app pod in ImagePullBackOff on the istio sidecar

DUPLICATE of 11541 (family BE), confirmed by the Kubernetes events of the cluster. Deploy cimain on 2026-11-17
(all times UTC), splice 0.10.3-snapshot.20261117.4399.0.v772d3944. The validator runbook stack created 21 resources
and then failed on the Helm release validator of namespace validator: not available within its 600 s timeout, on
the first attempt (16:00:27-16:10:36) and on all 5 retries (last 16:56:15-17:06:34). The release has one pod that
never became Ready, the validator-app pod: created 16:00:35, no node with room, triggered the scale-up, scheduled
16:02:22 on the new node, the node of 11541. Its sidecar image `registry.istio.io/release/proxyv2:1.30.5` got 403
Forbidden on the anonymous token five times (16:02:36-16:05:46) and the pod stayed in ImagePullBackOff through the
end of the job (last back-off event 17:07:44). The retries only re-waited on the same pod (same ReplicaSet, never
recreated). All other pods of the namespace run on other nodes with the image cached. Cause is outside splice
(registry or node egress).

- Workflow / job: Deploy cimain, job deploy_validator_runbook (Deploy basic / Deploy validator runbook), step that
  applies the validator runbook to the cluster. The run has 5 failed jobs; 11541, 11542, 11544 are the three
  preflight jobs. The fifth, the SV runbook deploy, was not read for this packet.
- Cluster / splice version: 0.10.3-snapshot.20261117.4399.0.v772d3944.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (from the 11541 packet of the same run, not re-derived).
- Component: infra (image pull on a freshly added cluster node), not splice, not Canton.
- Parent: 11541 (open, not closed as a duplicate, the ref the family BE entry is filed under). Mechanism (node
  scale-up, all other nodes with the image cached) is in
  `evidence/11541-cimain-preflight-sv-runbook-pods-istio-proxy-pull-403.md`, not repeated here.
- Relation to 11544: that job (preflight_validator_check) waited on the very same pod; this job is the deploy that
  created it. Third form of the family BE signature: the Helm timeout in the deploy job itself.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step

The validator-participant release is created in 31 s (15:59:54-16:00:26). The validator release starts at 16:00:27
and fails after 609 s because it did not become available within the timeout. Each of the 5 retries (started
16:11:28, 16:22:42, 16:33:57, 16:45:07, 16:56:15) updates the same release and fails the same way after about
618 s; the retries are exhausted at 17:06:40 and the job exits 1. Six failures in total, one on create and five on
update. Only the validator release fails; validator-participant and the 20 other resources are created.

## 2. Family BE check: the 403 on the sidecar image, one node only

Failed image pulls of the cluster over the whole job (15:55-17:08): 45 in namespace sv and 5 in namespace
validator, all for `registry.istio.io/release/proxyv2:1.30.5`, all on the one new node, all 403 Forbidden on the
anonymous token request; plus one transient timeout on another image. Same node, image and error as 11541. All 5
events of namespace validator (16:02:36 to 16:05:46) are on the one pod of the failing release.

## 3. Namespace validator, 15:55-17:08: every pod but validator-app starts

Seven pods are scheduled in the namespace and six start: the ANS and wallet web UIs, the participant and the
postgres and pge pods, all on three other nodes. The validator-app pod is the only one on the new node and the
only one with no event of an image pulled or a container started. Its events: unschedulable on 14 and then 15 nodes
(insufficient CPU on 9), scale-up triggered, scheduled, volume attached, 5 pull attempts and 5 failed pulls of the
sidecar image, then 28 ImagePullBackOff and 33 back-off events from 16:02:36 to 17:07:44. There is one ReplicaSet
and one pod for the whole job, so the 5 retries waited on the same stuck pod. The back-off events run past the end
of the job.

## Verdict

Duplicate of 11541, family BE (new cluster node cannot pull the istio sidecar image, registry.istio.io 403). Real
infra failure, not a flake of the deploy code; nothing to change in splice. Fix location is infra, as in the
family entry (serve the istio images from a registry the cluster controls); the reason for the 403 is still open
there. No fix branch. Close as duplicate of 11541 proposed, not done.

Not verified: the canton pin is taken from the 11541 packet of the same run; the splice version is the cluster
version string. The 403 Forbidden answer itself is logged only until 16:05:46; for the rest of the job's window the
evidence is the back-off events on the same pod, node and image. The failed SV runbook deploy job of the same run
was not read for this packet.
