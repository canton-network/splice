# 10170 and 9740 - TrafficBasedRewardsTimeBasedIntegrationTest "CIP-104 reward accounting pipeline works": round 11 not opened within 20 s after a 10-minute simulated time jump (runs 35326825932, 32257514623)

10170 is a DUPLICATE of 9740 (same test, same step, same message, same mechanism), and both belong to the family
fixed for the sibling helper by #5779 (cn-test-failures 8423, 2026-06-03): after a large simulated time jump the SV
app's domain time needs up to ~30 s wall clock to catch up, and `advanceTimeAndWaitForRoundOpening` only waits
the default 20 s.

| ref | run / job | branch, sha | canton | failure |
|-----|-----------|-------------|--------|---------|
| 9740 | 32257514623 / 96082925012 `simtime (1)` | main 306014bc81 (#6853), 2026-08-19 | 3.5.14-snapshot.20260815 | `Check waiting for open round automation (should create OpenMiningRound 11) for advancing time (8, 9, 10) was not equal to (9, 10, 11) (TimeTestUtil.scala:147)` |
| 10170 | 35326825932 / 105542036722 `simtime (0)` | release-line-0.8.x 19bfcbca6f (#7386), 2026-09-18 | 3.5.18 | identical message |

## 1. The step that fails (10170; 9740 identical at 13:41:02)

```
zcat log/10170/logs-simtime-0/canton_network_test.clog.gz | grep -a -E 'advancing time by|should create OpenMiningRound|Domain time delay is (currently|now)|successfully advanced the rounds' | grep -a 'T09:28:[2-5]'
```
```
09:28:30.420Z advancing time by PT4M10S to 1970-01-01T04:24:20Z
09:28:30.734Z Domain time delay is now -0.000024s ... below the configured max delay 2 minutes        (caught up in 0.3 s)
09:28:32.155Z Completed processing with outcome: successfully advanced the rounds and archived round 7
09:28:34.542Z advancing time by PT10M10S to 1970-01-01T04:34:30Z
09:28:34.555Z Running clue: (check) waiting for open round automation (should create OpenMiningRound 11)
09:28:35.215Z Domain time delay is currently 10m 9.999626s (04:34:30 - 04:24:20.000374), waiting until delay is below 2 minutes
09:28:54.564Z Failed clue: (check) waiting for open round automation (should create OpenMiningRound 11)   (20 s)
```
sv1's `DomainTimeIngestionTrigger` kept reading 04:24:20.000374 every second until the test was torn down at
09:29:04; the SV automation (including AdvanceOpenMiningRoundTrigger) stays paused while the delay is above the
2-minute `allowedDomainTimeDelay` (`DomainTimeStore.scala:68-118`).

## 2. Why domain time did not catch up: a command was in flight when the clock jumped

```
zcat log/10170/logs-simtime-0/canton-simtime.clog.gz | grep -a -E 'Advancing sim clock|MAX_SEQUENCING_TIME_EXCEEDED|Task scheduler waits for tick|Received TimeProof' | grep -a -E 'DelegatingSimClock|participant=sv1Participant|sequencer=globalSequencerSv1' | grep -a 'T09:28:3[0-9]'
```
```
09:28:30.453Z sv1Participant Received TimeProof(04:24:20.000005Z)                                  (previous step: proof arrives after the jump, fine)
09:28:34.297Z sv1Participant: SubmitAndWaitForTransaction from sv1's SV app (digital-asset-2 party), sequenced at 04:24:20.000343, max sequencing time 04:25:20
09:28:34.543Z Advancing sim clock to 1970-01-01T04:34:30Z                                          (test's PT10M10S jump)
09:28:34.550Z sv1Participant Task scheduler waits for tick of sc=1175. The tick with sc=1174 occurred at 04:24:20Z. Blocked trace ids: 6e473363...
09:28:34.550Z sv1Participant Sending time request
09:28:34.553Z globalSequencerSv1: SendAsync failed with ABORTED/MAX_SEQUENCING_TIME_EXCEEDED(2,6e473363): The sequencer time [04:34:30.000005Z] has exceeded by 549 seconds the max-sequencing-time of the send request [04:25:20.000343Z]
09:28:34.670Z sv1Participant Received TimeProof(1970-01-01T04:24:20.000374Z)                        (ordered before the jump took effect)
```
After that sv1Participant validated no further sequenced event until 09:29:20 (0 `Validating event` lines in
09:28:40-09:29:19, versus 23-56 per 10 s before and after). Its time tracker does not request another proof
(its schedule runs on the frozen sim clock), its task scheduler is blocked on the confirmation request whose
response the sequencer dropped, and the SV app's polling `FetchTime(freshnessBound = 1 s)` keeps returning the
stale 04:24:20.000374. Recovery comes from the participant's wall-clock timeout of the in-flight request
(confirmationResponseTimeout, 30 s, as documented in `TimeTestUtil.advanceTime`'s comment about
canton-network-node#3091), i.e. at ~09:29:04.5, 10 s after the 20 s check gave up. The earlier jumps in the
same test (09:28:00, 09:28:14, 09:28:30) caught up in under a second because nothing was in flight.

9740 (canton 3.5.14, main, 2026-08-19): identical shape at 13:41:02.837 (jump to 04:34:30), blocked scheduler
sc=1008, `MAX_SEQUENCING_TIME_EXCEEDED(2,39b26afe)` at 13:41:02.852, stale `TimeProof(04:24:20.000373Z)`,
domain time delay 10m 9.999685s, check failed at 13:41:22.

## 3. Prior fix for the same family

```
git log --format='%h %ad %s' --date=short -S'timeUntilSuccess = 90.seconds' -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/TimeTestUtil.scala
```
```
db3adfd49a 2026-06-03 Bumped timeout to let Domain Time catchup to trigger AdvanceOpenMiningRoundTrigger (#5779)
```
#5779 (fixes cn-test-failures 8423) gave `advanceTimeAndWaitForRoundAutomation` a 90 s `actAndCheck` budget for
exactly this reason; `advanceTimeAndWaitForRoundOpening` (`TimeTestUtil.scala:141`) was left on the 20 s default
and is what the CIP-104 test uses for its round steps.

## 4. Fix

Branch `ray/fix-round-opening-wait-budget` (9ac0f24ab3, off main): `advanceTimeAndWaitForRoundOpening` uses
`actAndCheck(timeUntilSuccess = 90.seconds)` like its sibling. One line, scalafmt clean, not compiled or run here.
The 1.5 s quiescence sleep in `advanceTime` cannot close the race with SV automation that submits on its own
schedule; the budget must cover the 30 s recovery. Backport to release-line-0.8.x as well (10170 ran there).
