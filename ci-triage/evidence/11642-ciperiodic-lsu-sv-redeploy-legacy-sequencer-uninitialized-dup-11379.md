# 11642 - ciperiodic deploy_lsu_operator preflight_sv_check_post_redeploy: validator-app in namespace sv never created after the SV runbook redeploy

DUPLICATE of 11379 (family AY), confirmed by the family's signature in the sv-app log. Third night in this shape
(11379 2026-11-12, 11557 2026-11-18, this one 2026-11-19, job window 01:10:06-01:34:49 UTC): same workflow, job, step
and step output. After the LSU the SV runbook stacks are destroyed and redeployed by the operator, and the job's wait
for a ready validator-app pod in namespace sv finds no such pod for 16 min 54 s (201 attempts). The SV app of the
redeployed SV has a legacy block with the global-domain-0 nodes in its config on all three of its starts, reaches the
sequencer onboarding step at 01:15:11, polls the status of global-domain-0-sequencer (whose status response carries
no synchronizer id), gives up after 120 retries and fails its initialization at 01:22:31 and 01:30:58. New in this
run: `archive_legacy_physical_synchronizer` ran during the wait (01:21:45-01:34:27) and the pod rolled out after it
(config dump 01:37:38 without a legacy block) reached the onboarding of its sequencer through the sponsoring SV at
01:38:27, 4 minutes after the wait had ended. No test ran, no artifact, no fix branch.

- Cluster version: `0.10.3-snapshot.20261118.4418.0.ve5938144` (splice e59381448e, 2026-11-18 21:57 UTC).
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice e59381448e).
- Component: splice SV app or cluster config (as 11379).
- Parent: 11379 (open, not closed as a duplicate, the ref the family AY entry is filed under). 11557 is closed as a
  duplicate of it.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step and workflow

The only step of the job that did not succeed is the wait for the sv-runbook deployment to finish,
01:17:24-01:34:19; the job has no artifacts. The step sees the cluster report the expected version and the namespace
sv become active, then retries the wait for a ready validator-app pod 201 times without a match and gives up. This
is the step output of 11379 and 11557 with a different cluster version.

Order of the workflow from the LSU on (UTC): upgrade_logical_synchronizer 00:12:28-00:24:07,
preflight_check_post_upgrade 00:24:30-00:35:59, preflight_sv_check_post_upgrade 00:24:32-00:32:57,
preflight_validator_check_post_upgrade 00:24:33-00:34:43, retire_old_physical_synchronizer 00:36:21-00:49:01,
post_upgrade_verify_identities 00:49:24-00:55:42, preflight_check_post_cleanup 00:49:26-01:02:14,
preflight_sv_check_post_cleanup 00:49:27-00:58:37, preflight_validator_check_post_cleanup 00:49:31-00:59:49,
deploy_fresh_sv_runbook_after_upgrade 01:02:39-01:09:43, backup_cluster_after_upgrade 01:02:41-01:13:32,
preflight_sv_check_post_redeploy 01:10:06-01:34:49 (failed), restore_node 01:13:50-01:21:25,
archive_legacy_physical_synchronizer 01:21:45-01:34:27, autoscaler_allow_scaledown 01:34:45-01:40:18; reset_cluster
did not run. The same SV preflight passed twice on the pre-reset SV (post_upgrade, post_cleanup); only the wait after
the redeploy failed. Unlike 11557, `archive_legacy_physical_synchronizer` overlapped the wait here and finished 8 s
after it ended.

The reset step of deploy_fresh_sv_runbook_after_upgrade (01:08:04-01:09:34) only destroys the four SV runbook stacks;
the operator redeploys them afterwards. The Helm values of the destroyed sv-runbook release list global-domain-1
mediator and sequencer as current and the pre-LSU global-domain-0 mediator and sequencer as legacy.

## 2. The AY precondition holds for the redeployed SV

The cluster configuration in effect during the redeploy (changed at 00:41:49, before the reset at 01:08) declares
migration 1 as active and migration 0 as legacy.

The SV app at the deployed commit, and on origin/main today, still builds a node config for every legacy node,
without the skip that successor nodes have (the AY mechanism, unchanged since 11379):
```
$ git log -1 --format='%H %ci %s' e5938144
e59381448e277f62af11f505e4e842b04fc05ba6 2026-11-18 21:57:06 +0000 Revert "implement participant admin API auth support in clusters (#8216)" (#8571)
$ git show e5938144:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala | grep -n -A3 'val legacyEntryFuture'
203:    val legacyEntryFuture =
204-      synchronizerNodes.legacy.traverse { legacyNode =>
205-        buildNodeConfig(legacyNode)
206-      }
$ git show e5938144:nix/canton-sources.json | grep -i '"version"'
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```
No fix for 11379 is on main (issue 11379 open, no closing PR; no commit to the two files since 2026-11-17):
```
$ gh issue view 11379 -R DACH-NY/cn-test-failures --json state,stateReason,title,closedByPullRequestsReferences
{"closedByPullRequestsReferences":[],"state":"OPEN","stateReason":"","title":"deploy_lsu_operator: SV re-onboarded after the LSU never initializes, SV app waits on the uninitialized pre-LSU (legacy) sequencer"}
$ git log --since=2026-11-17 --format='%h %ci %s' origin/main -- apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/LocalSynchronizerNode.scala
02b5a51214 2026-11-17 18:29:32 +0100 [static] Remove TODO references to the old canton-network-node repo from apps and frontend (#8543)
69b922dca8 2026-11-17 09:31:21 +0000 Download the sequencer onboarding state from the sponsor as a file (#7795)
```

## 3. Family AY signature in the sv-app log of namespace sv

Window 01:05-01:40. Three sv-app pods log in it: the pre-reset one ends at 01:08:31, the redeployed one logs
01:11:50-01:37:03, its replacement from 01:37:34.

- First part of the AY confirm: the config dump of each of the three starts of the redeployed pod (01:11:53,
  01:23:01, 01:31:39) has, under its local synchronizer nodes, a current entry with the global-domain-1 mediator and
  sequencer and a legacy entry with the global-domain-0 mediator and sequencer. The config dump of the replacement
  pod (01:37:38, after the archive job) has no legacy entry.
- Second part: from the start of the sequencer onboarding step (01:15:11) the sequencer status requests go to
  global-domain-0-sequencer and succeed at the gRPC level; the retries are on the status response having no
  synchronizer id.
- Counts over the window: 273 retries on the missing synchronizer id, 2 times the retry maximum (120) exceeded,
  5 lines of the sequencer onboarding step, 1 onboarding of the sequencer through the sponsoring SV.
- Two failed initializations inside the wait of the job: retries of the operation that sets the domain config
  exhausted and SV app initialization failed (join of the DSO with key) at 01:22:31 and 01:30:58, each followed by a
  restart (01:23:01, 01:31:39). The third attempt reaches the sequencer onboarding step at 01:32:23 and is cut short
  when the pod is replaced (last retry 01:33:57, join failed 01:37:03). The replacement pod reaches the sequencer
  onboarding step at 01:38:25 and onboards its sequencer through the sponsoring SV at 01:38:27.

## 4. Ruled out

- A regression of splice#7795 (onboarding snapshot download from the sponsor as a file): the download succeeds
  (received 01:14:00, imported 01:14:01) 70 s before the SV app reaches the sequencer onboarding.
- Family BE (pods stuck on an image pull): the sv-app pod runs and logs from 01:11:50 and reaches the sequencers of
  the namespace. The Kubernetes events were not queried for this run (see Verdict).

## Verdict

Duplicate of 11379 (family AY; parent open and not itself a duplicate). Real failure, not a test flake: an SV that is
redeployed after the LSU while the config still declares the old migration as legacy fails this way until
`archive_legacy_physical_synchronizer` removes the legacy block. This run shows the other half directly: the pod
started after the archive, without the legacy block, onboards its sequencer through the sponsor within 49 s of its
config dump. No test-side fix exists; the fix is in the SV app (skip uninitialized legacy nodes like successors) or in
the cluster config or workflow order (no legacy block for an SV joining after the LSU, or the redeploy preflight
after the archive job), as recorded for 11379. Not a small splice change with a local suite to verify it, so no fix
branch. With 11557 this is two nights in a row for a workflow that runs once a night: hard broken, not a rare flake.
Not verified: the Kubernetes events of namespace sv (BE ruled out from the container log only); whether the
replacement pod completes its initialization after 01:40 (outside the window read); why the nights between 11379 and
11557 passed this job.
