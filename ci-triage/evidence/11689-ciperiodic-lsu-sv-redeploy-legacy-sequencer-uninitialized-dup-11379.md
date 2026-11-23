# 11689 - ciperiodic deploy_lsu_operator preflight_sv_check_post_redeploy: validator-app in namespace sv never created after the SV runbook redeploy

DUPLICATE of 11379 (family AY), confirmed by the family's signature in the sv-app log. Fourth night in this shape
(11379 2026-11-12, 11557 2026-11-18, 11642 2026-11-19, this one 2026-11-21, job window 01:35:41-02:00:39 UTC): same
workflow, job, step and step output. After the LSU the SV runbook stacks are destroyed and redeployed by the
operator, and the job's wait for a ready validator-app pod in namespace sv finds no such pod for 16 min 55 s (201
attempts). The SV app of the redeployed SV has a legacy block with the global-domain-0 nodes in its config on all
three of its starts, reaches the sequencer onboarding step at 01:40:54, polls the status of global-domain-0-sequencer
(whose status response carries no synchronizer id), gives up after 120 retries and fails its initialization at
01:48:05 and 01:56:36. As in 11642, `archive_legacy_physical_synchronizer` ran during the wait (01:46:37-01:58:57)
and the pod rolled out after it (config dump 02:02:16 without a legacy block) reached the onboarding of its
sequencer through the sponsoring SV at 02:03:03, 3 minutes after the wait had ended. No test ran, no artifact, no
fix branch.

- Cluster version: `0.10.4-snapshot.20261120.4442.0.va0e74065` (splice a0e7406501, 2026-11-20 20:44 UTC, the tip of
  origin/main at triage time).
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at splice a0e7406501).
- Component: splice SV app or cluster config (as 11379).
- Parent: 11379 (open, not closed as a duplicate, no assignee, no closing PR; the ref the family AY entry is filed
  under). 11557 and 11642 are the earlier duplicates of it.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Failing step and workflow

Of the 50 steps of the job, the only one that did not succeed is the wait for the sv-runbook deployment to finish,
01:43:20-02:00:15; the job has no artifacts. The step sees the cluster report the expected version and the namespace
sv become active, then retries the wait for a ready validator-app pod 201 times without a match and gives up. This
is the step output of 11379, 11557 and 11642 with a different cluster version.

Order of the workflow (UTC, 2026-11-20 to 2026-11-21): reset_before_deploy 23:08:53-23:16:43,
deploy_cluster_base_version 23:17:01-23:53:17, prepare_cluster_for_logical_synchronizer_upgrade 23:53:38-00:17:22,
upgrade_logical_synchronizer 00:17:43-00:31:47, the three post-upgrade preflights 00:32:10-00:49:57,
retire_old_physical_synchronizer 00:50:17-01:06:07, post_upgrade_verify_identities 01:06:35-01:11:09, the three
post-cleanup preflights 01:06:29-01:26:43, backup_cluster_after_upgrade 01:27:05-01:38:50,
deploy_fresh_sv_runbook_after_upgrade 01:27:05-01:35:27 (its reset step 01:33:55-01:35:17),
preflight_sv_check_post_redeploy 01:35:41-02:00:39 (failed), restore_node 01:39:08-01:46:11,
archive_legacy_physical_synchronizer 01:46:37-01:58:57, autoscaler_allow_scaledown 01:59:16-02:05:49; reset_cluster
did not run. All jobs other than the failed one succeeded.

The same SV preflight passed twice on the pre-reset SV (post_upgrade, post_cleanup); only the wait after the redeploy
failed. The deploy at the start of the workflow passed, so this night is not the 11670 shape (family for the missing
`audiences.participantAdminApi`). `archive_legacy_physical_synchronizer` overlapped the wait and finished 78 s before
it ended. The output of the reset step was not read for this run (11642 section 1 describes the Helm values with the
legacy nodes from that step).

## 2. The AY precondition holds for the redeployed SV

The cluster configuration in effect during the redeploy (changed at 00:56:59, before the reset at 01:33:55) declares
migration 1 as active and migration 0 as legacy.

The SV app at the deployed commit, which is origin/main at triage time, still builds a node config for every legacy
node, without the skip that successor nodes have (the AY mechanism, unchanged since 11379):
```
$ git log -1 --format='%H %ci %s' a0e74065
a0e7406501e06520024cf3ad04fd8a395f0a3807 2026-11-20 21:44:30 +0100 Deflake the onboarding snapshot resume step in SvOnboardingAddlIntegrationTest (#8632)
$ git log -1 --format='%h %ci' origin/main
a0e7406501 2026-11-20 21:44:30 +0100
$ git show a0e74065:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala | grep -n -A3 'val legacyEntryFuture'
203:    val legacyEntryFuture =
204-      synchronizerNodes.legacy.traverse { legacyNode =>
205-        buildNodeConfig(legacyNode)
206-      }
$ git show a0e74065:nix/canton-sources.json | grep -i '"version"'
  "version": "3.7.4",
$ gh issue view 11379 -R DACH-NY/cn-test-failures --json state,stateReason,title,closedByPullRequestsReferences,assignees
{"assignees":[],"closedByPullRequestsReferences":[],"state":"OPEN","stateReason":"","title":"deploy_lsu_operator: SV re-onboarded after the LSU never initializes, SV app waits on the uninitialized pre-LSU (legacy) sequencer"}
```

## 3. Family AY signature in the sv-app log of namespace sv

Window 01:25-02:10. Three sv-app pods log in it: the pre-reset one ends at 01:34:20, the redeployed one logs
01:36:33-02:01:43, its replacement from 02:02:13.

- First part of the AY confirm: the config dump of each of the three starts of the redeployed pod (01:36:36,
  01:48:35, 01:57:17) has, under its local synchronizer nodes, a current entry with the global-domain-1 mediator and
  sequencer and a legacy entry with the global-domain-0 mediator and sequencer. The config dump of the replacement
  pod (02:02:16, after the archive job) has no legacy entry.
- Second part: from the start of the sequencer onboarding step (01:40:54) the sequencer status requests go to
  global-domain-0-sequencer and succeed at the gRPC level; the operation that sets the domain config then fails with
  a retryable UNAVAILABLE error because the status response has no synchronizer id.
- Counts over the window: 264 retries on the missing synchronizer id, 3 times a retry maximum exceeded, 5 lines of
  the sequencer onboarding step, 1 onboarding of the sequencer through the sponsoring SV. Of the 3 exceeded retry
  maxima, 2 are the domain config operation on the redeployed pod; the third (01:34:18, a package version lookup) is
  the pre-reset pod while it is being torn down.
- Timeline of the redeployed pod: ACS snapshot from the sponsor received 01:39:39 and imported 01:39:40; sequencer
  onboarding step 01:40:54; retries (120) exhausted and SV app initialization failed (join of the DSO with key) at
  01:48:05; restart 01:48:35, sequencer onboarding step 01:49:21, failed again 01:56:36; restart 01:57:17, sequencer
  onboarding step 01:58:03, third attempt cut short when the pod is replaced (last retry 01:59:09, join failed
  02:01:43). The two failures are inside the wait of the job (01:43:20-02:00:15). The replacement pod reaches the
  sequencer onboarding step at 02:03:02 and onboards its sequencer through the sponsoring SV at 02:03:03.

## 4. Ruled out

- A regression of the onboarding snapshot download (splice#7795): the import succeeds 01:39:40, 74 s before the SV
  app reaches the sequencer onboarding (timeline above).
- Family BE (pods stuck on an image pull) and the missing-secret shapes of 11638/11640: the sv-app pod runs and logs
  from 01:36:33 and reaches the sequencers of the namespace. The Kubernetes events were not queried for this run.

## Verdict

Duplicate of 11379 (family AY; parent open and not itself a duplicate). Real failure, not a test flake: an SV that is
redeployed after the LSU while the config still declares the old migration as legacy fails this way until
`archive_legacy_physical_synchronizer` removes the legacy block; the pod started after the archive onboards its
sequencer through the sponsor within 47 s of its config dump. No fix for 11379 is on main (the deployed commit is
the tip of origin/main and has the unguarded `buildNodeConfig(legacyNode)`). The fix is in the SV app (skip
uninitialized legacy nodes like successors) or in the cluster config or workflow order (no legacy block for an SV
joining after the LSU, or the redeploy preflight after the archive job), as recorded for 11379; nothing was changed
here. Third failure of this job in four nights (11-18, 11-19, 11-21; 11-20 failed earlier, at the base deploy,
11670) for a workflow that runs once a night: hard broken. Not verified: the Kubernetes events of namespace sv;
whether the replacement pod completes its initialization after 02:10 (outside the window read).
