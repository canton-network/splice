# 11557 - ciperiodic deploy_lsu_operator preflight_sv_check_post_redeploy: validator-app in namespace sv never created after the SV runbook redeploy

DUPLICATE of 11379 (family AY), confirmed by the family's signature in the sv-app log. Same workflow, job, step and
step output as 11379 (job window 2026-11-18 01:13:15-01:36:46 UTC): after the LSU the SV runbook stacks are destroyed
and redeployed by the operator, and the job's wait for a ready validator-app pod in namespace sv finds no such pod
for 16 min 55 s (201 attempts). The SV app of the redeployed SV has a legacy block with the global-domain-0 nodes in
its config, reaches the sequencer onboarding step at 01:18:37, polls the status of global-domain-0-sequencer (whose
status response carries no synchronizer id), gives up after 120 retries and fails its initialization at 01:25:53 and
again at 01:34:22; a third attempt was running when the wait ended. It never reaches the onboarding of its sequencer
through the sponsoring SV. Not BE (the pods run, no pull failures) and not a regression of splice#7795 (the
onboarding snapshot download succeeds at 01:17:15). No test ran, no artifact, no fix branch.

- Cluster version: `0.10.3-snapshot.20261117.4408.0.vfacc9443` (splice facc9443d3, 2026-11-17 20:03 UTC).
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice facc9443d3).
- Component: splice SV app or cluster config (as 11379).
- Parent: 11379 (open, not closed as a duplicate, the ref the family AY entry is filed under).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step and workflow

The failed step is the wait for the sv-runbook deployment to finish, 01:19:27-01:36:22; the job has no artifacts.
The step sees the cluster report the expected version and the namespace sv become active, then retries the wait for
a ready validator-app pod 201 times without a match and gives up. This is the step output of 11379 with a different
cluster version.

Order of the workflow from the LSU on (UTC): upgrade_logical_synchronizer 00:09:46-00:21:18,
preflight_sv_check_post_upgrade 00:21:44-00:34:50, retire_old_physical_synchronizer 00:38:36-00:48:43,
preflight_sv_check_post_cleanup 00:49:08-01:03:59, backup_cluster_after_upgrade 01:05:32-01:21:14,
deploy_fresh_sv_runbook_after_upgrade 01:05:36-01:12:55, preflight_sv_check_post_redeploy 01:13:15-01:36:46 (failed),
restore_node 01:21:29-01:40:20, archive_legacy_physical_synchronizer 01:40:35-01:57:17, autoscaler_allow_scaledown
01:57:35-02:04:37; reset_cluster did not run. The same SV preflight passed twice on the pre-reset SV (post_upgrade,
post_cleanup); only the wait after the redeploy failed. archive_legacy_physical_synchronizer started 4 minutes after
the failed wait ended.

The reset step of deploy_fresh_sv_runbook_after_upgrade (01:11:41-01:12:46) only destroys the four SV runbook stacks;
the operator redeploys them afterwards. The Helm values of the destroyed sv-runbook release list global-domain-1
mediator and sequencer as current and the pre-LSU global-domain-0 mediator and sequencer as legacy.

## 2. The AY precondition holds for the redeployed SV

The cluster configuration in effect during the redeploy (changed at 00:41:55, before the reset at 01:11) declares
migration 1 as active and migration 0 as legacy, so the stack the operator recreates gets the same legacy values.

The SV app at the deployed commit still builds a node config for every legacy node, without the skip that successor
nodes have (the AY mechanism, unchanged since 11379):
```
$ git show facc9443:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala | sed -n 203,206p
    val legacyEntryFuture =
      synchronizerNodes.legacy.traverse { legacyNode =>
        buildNodeConfig(legacyNode)
      }
```
No fix for 11379 is on main (issue 11379 open, no fix PR linked):
```
$ git log --since=2026-11-05 --format='%h %ci %s' origin/main -- apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/LocalSynchronizerNode.scala
02b5a51214 2026-11-17 18:29:32 +0100 [static] Remove TODO references to the old canton-network-node repo from apps and frontend (#8543)
69b922dca8 2026-11-17 09:31:21 +0000 Download the sequencer onboarding state from the sponsor as a file (#7795)
1844b95301 2026-11-10 14:17:04 +0100 Remove more cometbft references (#8404)
```

## 3. Family AY signature in the sv-app log of namespace sv

Window 01:10-01:40. Two sv-app pods log in it: the pre-reset one shuts down at 01:12:06, the redeployed one logs
from 01:15:00.

- First part of the AY confirm: the config dump of each of the three starts of the new pod (01:15:00, 01:26:23,
  01:35:03) has, under its local synchronizer nodes, a current entry with the global-domain-1 mediator and sequencer
  and a legacy entry with the global-domain-0 mediator and sequencer.
- Second part: from the start of the sequencer onboarding step (01:18:37) the sequencer status requests go to
  global-domain-0-sequencer; they succeed at the gRPC level, and the operation that sets the domain config fails
  with a retryable UNAVAILABLE error because the status response has no synchronizer id.
- Counts over the window: 315 retries on the missing synchronizer id, 2 times the retry maximum (120) exceeded,
  3 starts of the sequencer onboarding step, 0 onboardings of the sequencer through the sponsoring SV.
- Two failed initializations inside the wait of the job: retries exhausted and SV app initialization failed (join of
  the DSO with key) at 01:25:53 and 01:34:22, each followed by a restart (01:26:23, 01:35:03). The third attempt
  reaches the sequencer onboarding step at 01:35:48 and is still retrying at the end of the window (last retry
  01:39:58).

## 4. Ruled out

- A regression of splice#7795 (69b922dca8, sequencer onboarding state and snapshot download from the sponsor as a
  file; ancestor of the deployed facc9443 and first deployed by this workflow in this run): the download of the
  onboarding snapshot from the sponsor has two transient retries at 01:17:14-01:17:15 and then succeeds (received
  01:17:15, imported 01:17:16), 82 s before the SV app reaches the sequencer onboarding. The failure is before the
  sponsor is asked for the sequencer state, as in 11379.
- Family BE (11541, pods stuck on an image pull): the sv-app pod runs and logs from 01:15:00, and it reaches the
  participant and both sequencers of the namespace, so the pods of namespace sv pulled their images. There is no
  pull failure or back-off event for namespace sv in the window.
- 11403 (the parent of the two previous deploy_lsu_operator failures 11424 and 11529): different job and step
  (deploy_cluster_base_version, stack never created); that job passed here.

## Verdict

Duplicate of 11379 (family AY; parent open and not itself a duplicate). Real failure, not a test flake: every SV that
is redeployed after the LSU while the config still declares the old migration as legacy fails this way until
`archive_legacy_physical_synchronizer` removes the legacy block, which this workflow runs only after the preflight.
No test-side fix exists; the fix is in the SV app (skip uninitialized legacy nodes like successors) or in the cluster
config or workflow order (no legacy block for an SV joining after the LSU), as recorded for 11379. No fix branch.
Not verified: why some nights pass this job (not checked against a passing run); the transient error text of the two
snapshot download retries (the download then succeeds).
