# 11539 - cimain deploy_cluster: joining SV sequencer snapshot queued behind a blocked ACS export, synchronizer frozen at the 1 -> 2 step

New, not a duplicate. A cimain deploy (splice 08a89e8dcc, 0.10.3-snapshot.20261117.4397.0.v08a89e8d) fails the
pulumi update of the sv stacks: the Helm releases sv-app of sv-2, sv-3 and sv-da-1 (and scan of sv-3) do not become
available within 600 s. Cause is a deadlock in the sponsor SV app (sv-1), new with splice#7795 (69b922dca8, merged
2026-11-17 09:31 UTC, first cimain deploy with it that reached SV onboarding): the sequencer onboarding state of
the first joining sequencer (sv-da-1) is exported through `SvOnboardingSnapshotService`, whose single queue runs
one export at a time (`parallelism = 1`), and it sits behind an ACS export for sv-3 that cannot finish while the
synchronizer is frozen. The synchronizer is frozen because the ordering topology went 1 -> 2 and the second node
(sv-da-1) cannot start without that onboarding state.

- Deployed splice: 08a89e8dcc (#8525), contains 69b922dca8 (#7795).
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: splice SV app (sponsor side of SV onboarding).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

SV roles: sv-1 is the founding SV and the sponsor; sv-da-1, sv-2 and sv-3 join through it.

## 1. What the deploy job and the cluster logs showed (2026-11-17, times UTC)

- Failing step: from 12:34:52 on, the Helm releases sv-app of sv-2, sv-3 and sv-da-1 and scan of sv-3 time out;
  everything after that is a cancelled update or a stack lock from the retries. sv-1's sv-app is not among them:
  the founding SV initialized at 12:28:31. The sv-canton stacks and validator1 succeeded before 12:31:35.
- The synchronizer stops at the 1 -> 2 ordering topology step. sv-1's sequencer starts an epoch with ordering
  topology size 1 at 12:30:35.766, delivers its last block at 12:30:40.547, and at 12:30:40.574 starts the next
  epoch with sv-da-1's sequencer as second node (strong quorum 2). It never starts another epoch, reports that the
  number of authenticated P2P nodes (1) is below the strong quorum, and drops every P2P message to the newcomer as
  unknown (189 times, from 12:30:40.596 to the end of the observed window at 12:38:28).
- The newcomer never gets past its init stage: the sv-da-1 sequencer reaches the stage where it waits for the
  sequencer-to-synchronizer initialization at 12:30:00.696, persists its identity transactions at 12:30:27, and
  logs nothing above DEBUG after that except periodic query statistics.
- Its onboarding state stays queued on the sponsor: sv-1 queues and starts an ACS export (for sv-3, activation time
  12:30:39.720) at 12:30:39.950, and queues the sequencer onboarding state export for sv-da-1 at 12:30:41.144,
  1.2 s later. The ACS export never finishes, the sequencer export never starts. The two earlier ACS exports of the
  same deploy (12:29:51.071 and 12:29:53.299, before the step) finished in 0.34 s and 0.16 s. sv-da-1 polls the
  download of its snapshot from the sponsor 127 times (12:30:41.157 to 12:38:27.890) and is always told that the
  snapshot is queued.
- What the ACS export waits on: sv-1's participant answers each `ExportPartyAcs` call after exactly 120 s with
  `EFFECTIVE_PARTY_TO_PARTICIPANT_MAPPING_NOT_FOUND` for the DSO party on sv-3's participant (12:32:39.983,
  12:34:40.121, 12:36:40.341); `DsoPartyMigration.exportSnapshotToFile` retries it inside the export job, so the
  job keeps the only slot. Inferred, not shown from participant internals: the participant does not publish the
  sv-3 PartyToParticipant change on its ledger because the synchronizer delivered nothing after 12:30:40.5 (the
  export started 0.6 s before the last block).
- Consequences only: between 12:32:32 and 12:33:00 the SvOnboardingMediatorProposalTrigger of sv-da-1, sv-2 and
  sv-1 gives up waiting for the sequencer of sv-2 to be added to the topology state, sv-3's
  DomainTimeIngestionTrigger skips a periodic task at 12:32:44, and at 12:37:46 sv-1's HttpSvPublicHandler gives up
  waiting to observe the new sequencer through the existing one (that sequencer prepare request ends with 404 after
  428 s). The add of sv-2's sequencer never becomes effective because nothing is ordered after 12:30:40.5.

## 2. Source: one queue, one slot, both kinds of export (splice 08a89e8dcc)

```
git merge-base --is-ancestor 69b922dca8 08a89e8dcc && echo "#7795 in deployed commit"
git log -1 --format='%h %ad %s' --date=iso 69b922dca8
git grep -n -A5 'final case class SvOnboardingSnapshotsConfig' 08a89e8dcc -- apps/sv/src/main | cut -d: -f2-
git grep -n -A3 'private val (queue, queueTerminated)' 08a89e8dcc -- apps/sv/src/main | cut -d: -f2-
```
```
#7795 in deployed commit
69b922dca8 2026-11-17 13:31:21 +0400 Download the sequencer onboarding state from the sponsor as a file (#7795)
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala:769:final case class SvOnboardingSnapshotsConfig(
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala-770-    directory: Path,
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala-771-    retention: PositiveFiniteDuration = PositiveFiniteDuration.ofHours(24),
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala-772-    parallelism: PositiveInt = PositiveInt.one,
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala-773-    queueSize: PositiveInt = PositiveInt.tryCreate(4),
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala-774-)
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotService.scala:197:  private val (queue, queueTerminated) = Source
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotService.scala-198-    .queue[QueuedExport](config.queueSize.value)
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotService.scala-199-    .mapAsyncUnordered(config.parallelism.value)(runExport)
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/sponsor/SvOnboardingSnapshotService.scala-200-    .toMat(Sink.ignore)(Keep.both)
```
`HttpSvPublicHandler.onboardSvSequencerPrepare` (sequencer state, since #7795) and
`onboardSvPartyMigrationPrepareResponse` (DSO party ACS, since #7794 of 2026-11-12) both call
`onboardingSnapshotService.prepare`. Before #7795 the sequencer onboarding state was returned by the handler
directly, so it could not wait behind an ACS export.

## 3. Not family B (11255)

Same trigger (ordering topology grows while the newcomer is not yet up), different mechanism: no rejected send, no
blacklisting, and the newcomer is not slow but never initialized. The family B confirming strings have 0 matches in
the sequencer logs of the window, and no epoch starts with a non-empty list of blacklisted nodes.

## Verdict

New, real, splice SV app (sponsor side). Deadlock cycle on sv-1:
ACS export for sv-3 (holds the only export slot) waits on synchronizer progress -> synchronizer waits on the
sv-da-1 sequencer (strong quorum 2 since 12:30:40.574) -> sv-da-1 sequencer waits on its onboarding state -> the
onboarding state export is queued behind the ACS export. It needs two SVs joining through the same sponsor with an
ACS export in flight at the moment another joiner's sequencer enters the ordering topology, so it is a race: the
next cimain deploy passed. Any environment that onboards several SVs at once through one sponsor can hit it
(cluster deploys, 4-SV integration test setups).

Fix: branch `pperek/fix-11539-sequencer-snapshot-export-lane`, commit 86631f9ed5 off origin/main d27fa80744, local
only. `SvOnboardingSnapshotService` gets one queue per kind of export (same `parallelism` and `queueSize` each), so
a sequencer onboarding state never waits behind ACS exports; the ACS limit is unchanged. Production code of the SV
onboarding path: needs review by the #7795 author. It changes the meaning of the two config values from a shared
limit to a limit per kind, and the existing unit test `limit concurrent ACS and sequencer exports and reject a full
queue` was narrowed to one kind. The alternative, a larger default `parallelism`, only moves the bound (N joiners
can block N-1 slots).

Verified on the branch:
```
direnv exec . sbt -batch "apps-sv/Test/compile" "apps-sv/testOnly org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotServiceTest" "scalafmtCheckAll"
```
```
[info] Tests: succeeded 23, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
```
The new test `export a sequencer onboarding state while all ACS exports are blocked` fails against the service of
origin/main (same command, service file reverted): `Tests: succeeded 22, failed 1`. No integration suite and no
Canton were run: the deadlock needs the multi-SV onboarding race, which no local suite reproduces on demand.

Not verified: that the blocked ACS export would have finished had the synchronizer resumed (an inference, see
section 1); a cluster redeploy with the fix. Not the same failure: 11548 (17:22, splice 408f7ed9) fails on
`sv-1/global-domain-0` and has no `SvOnboardingSnapshotService` line in the window.
