# 10179 - MemberTrafficIntegrationTest "serve a member's traffic status as reported by the sequencer": Scan's sequencer-side read (298932) ran 27 ms after the participant-side read (295144), and alice's validator automation had a submission sequenced in between (run 35490579821)

New, test-side read-ordering race; no prior packet or family entry. The test compares two snapshots of alice's
traffic state taken from two different sources at two different instants: first `getTrafficState` on
aliceParticipant's admin API (the participant's own view, which only advances when it processes its sequenced
events), then Scan, which asks globalSequencerSv1's admin API (the sequencer's view, current at sequencing). At
05:30:04.790 aliceValidator's automation submitted `ValidatorLicense_RecordValidatorLivenessActivity`; the
sequencers consumed 3947 for it at sequencing time 05:30:04.806010 (extraTrafficConsumed 295144 -> 298932). The
participant answered the test at 05:30:04.866 with its view as of 05:30:04.326851 (295144); it received and
processed the receipt for the .806 event at 05:30:04.885-.890; Scan's sequencer query at 05:30:04.893 returned
298932. Both numbers are correct for their instant. Fix: compare inside `eventually()`.

- Run: https://github.com/canton-network/splice/actions/runs/35490579821 ("Wall Clock Tests with Postgres 14",
  2026-09-20, main 18f490ae5a), job 106024968667 `scala_test_wall_clock_time / wall-clock-time (4)`.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824 (`git show 18f490ae5a:nix/canton-sources.json`).
- Component: test (MemberTrafficIntegrationTest). 33 tests passed, 1 failed, no checkErrors involvement.
- Only failed job of the run, so the ref maps to it without inference.

## 1. Failing assertion

```
gh api repos/canton-network/splice/actions/jobs/106024968667/logs > log/10179/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10179/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/10179/job.log | grep -a -m1 -A6 "traffic status as reported by the sequencer \*\*\* FAILED" | sed -E 's/^[^Z]*Z //' | grep -a -E 'was not equal|MemberTrafficIntegrationTest.scala'
```
```
[info] *** 1 TEST FAILED ***
[info] - should serve a member's traffic status as reported by the sequencer *** FAILED ***
[info] Tests: succeeded 33, failed 1, canceled 0, ignored 0, pending 0
[info]   298932 was not equal to 295144 (MemberTrafficIntegrationTest.scala:134)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.MemberTrafficIntegrationTest.$anonfun$new$20(MemberTrafficIntegrationTest.scala:134)
```
Left side is Scan's answer, right side the participant's:
```
git show 18f490ae5a:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/MemberTrafficIntegrationTest.scala | sed -n '125,137p'
git show 18f490ae5a:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/SynchronizerFeesTestUtil.scala | sed -n '243,249p'
```
```
    "serve a member's traffic status as reported by the sequencer" in { implicit env =>
      val memberId = aliceValidatorBackend.participantClient.id

      val actualStateAsPerSequencer = getTrafficState(aliceValidatorBackend, activeSynchronizerId)
      val actualTotalPurchasedAsPerDso =
        listMemberTrafficContracts(memberId).map(_.data.totalPurchased.toLong).sum

      val statusAsPerScan = sv1ScanBackend.getMemberTrafficStatus(activeSynchronizerId, memberId)

      statusAsPerScan.actual.totalConsumed shouldBe actualStateAsPerSequencer.extraTrafficConsumed.value
      statusAsPerScan.actual.totalLimit shouldBe actualStateAsPerSequencer.extraTrafficPurchased.value
      statusAsPerScan.target.totalPurchased shouldBe actualTotalPurchasedAsPerDso
    }
  def getTrafficState(
      validatorApp: ValidatorAppBackendReference,
      synchronizerId: SynchronizerId,
  ): TrafficState = {
    validatorApp.participantClientWithAdminToken.traffic_control
      .traffic_state(synchronizerId)
  }
```
Despite its name, `actualStateAsPerSequencer` is the participant admin API's `TrafficControlService/TrafficControlState`.
Scan asks the sequencer:
```
git show 18f490ae5a:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/http/HttpScanHandler.scala | sed -n '2489,2493p'
```
```
        actual <- synchronizerNodeService
          .sequencerAdminConnection()
          .flatMap(_.getSequencerTrafficControlState(member))
        actualConsumed = actual.extraTrafficConsumed.value
        actualLimit = actual.extraTrafficLimit.value
```

## 2. Suite timeline: the test ran for 48 ms

```
T=log/10179/logs-wall-clock-time-4/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite 'MemberTraffic|Test (succeeded|failed): 'MemberTraffic|Starting 'MemberTraffic" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-160
```
```
2026-09-20T05:29:29.345Z Starting test suite 'MemberTrafficIntegrationTest'...
2026-09-20T05:29:57.284Z Starting 'MemberTrafficIntegrationTest/SV automation should handle contracts with an invalid member id'...
2026-09-20T05:30:01.476Z Test succeeded: 'MemberTrafficIntegrationTest/SV automation should handle contracts with an invalid member id'
2026-09-20T05:30:01.476Z Starting 'MemberTrafficIntegrationTest/SV automation should merge duplicate member traffic contracts'...
2026-09-20T05:30:04.856Z Test succeeded: 'MemberTrafficIntegrationTest/SV automation should merge duplicate member traffic contracts'
2026-09-20T05:30:04.857Z Starting 'MemberTrafficIntegrationTest/Scan should serve a member's traffic status as reported by the sequencer'...
2026-09-20T05:30:04.905Z Test failed: 'MemberTrafficIntegrationTest/Scan should serve a member's traffic status as reported by the sequencer', message: 298932 was not equal to 295144
```

## 3. The two reads and the three answers

Participant-side read (test), sequencer-side read (Scan), and an unrelated sequencer-side read by sv1's own
automation 68 ms earlier that brackets the change:
```
zcat $T | grep -a -E '"@timestamp":"2026-09-20T05:30:04\.(798|893)' | grep -a 'TrafficControlStateResponse' | sed -E 's/^\{"@timestamp":"([^"]+)".*(SV=sv1|scan=sv1Scan).*received a message (.{0,140}).*/\1 \2 \3/; s/1220[0-9a-f]{60}/../g'
C=log/10179/logs-wall-clock-time-4/canton.clog.gz
zcat $C | grep -a -E '"@timestamp":"2026-09-20T05:30:04\.86' | grep -a 'participant=aliceParticipant' | grep -a 'TrafficControlService/TrafficControlState' | grep -a 'sending response' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,190}).*/\1 participant \2/'
```
```
2026-09-20T05:30:04.798Z SV=sv1       TrafficControlStateResponse(Map(PAR::aliceValidator::..7de8 -> TrafficState(12000000L, 295144L, 111L, 0L, 1789882204661244L, 2)))
2026-09-20T05:30:04.866Z participant  ... sending response TrafficControlStateResponse(TrafficState(12000000L, 295144L, 0L, 3952L, 1789882204326851L, 2))
2026-09-20T05:30:04.893Z scan=sv1Scan TrafficControlStateResponse(Map(PAR::aliceValidator::..7de8 -> TrafficState(12000000L, 298932L, 0L, 0L, 1789882204806012L, 2)))
```
`TrafficState(extraTrafficPurchased, extraTrafficConsumed, baseTrafficRemainder, lastConsumedCost, timestamp micros, serial)`.
The participant's answer is stamped 05:30:04.326851 (its last processed traffic update); the sequencer's answer at
.893 is stamped 05:30:04.806012.

## 4. What changed between .866 and .893: alice's validator automation

The sequencers' consumption ledger for alice around the test (globalSequencerSv1; all four sequencers log the same):
```
zcat $C | grep -a 'TrafficConsumedManager:sequencer=globalSequencerSv1' | grep -a 'PAR::aliceValidator' | grep -a -E '"@timestamp":"2026-09-20T05:30:0[0-4]' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"Consumed ([0-9]+) for [^ ]+ at ([^Z]+Z): new state TrafficConsumed\(member = [^,]+, extraTrafficConsumed = ([0-9]+).*/\1 consumed=\2 seqTs=\3 extraConsumed=\4/' | tail -3
```
```
2026-09-20T05:30:03.917Z consumed=9203 seqTs=2026-09-20T05:30:03.781600Z extraConsumed=291373
2026-09-20T05:30:04.396Z consumed=3952 seqTs=2026-09-20T05:30:04.326851Z extraConsumed=295144
2026-09-20T05:30:04.879Z consumed=3947 seqTs=2026-09-20T05:30:04.806010Z extraConsumed=298932
```
The .806010 event is a submission by aliceValidator's own automation, not by the test:
```
zcat $T | grep -a -E '"@timestamp":"2026-09-20T05:30:04\.790' | grep -a 'SubmitAndWaitRequest' | sed -E 's/.*logger_name":"([^"]*)".*/\1/'
zcat $T | grep -a -E '"@timestamp":"2026-09-20T05:30:04\.790' | grep -a 'SubmitAndWaitRequest' | sed -E 's/.*ExerciseCommand\((.{0,160}).*/\1/; s/\\n/ /g; s/  +/ /g; s/1220[0-9a-f]{60}/../g'
```
```
o.l.s.a.a.c.ApiClientRequestLogger:MemberTrafficIntegrationTest/config=59a6fa3e/validator=aliceValidator
 Identifier(#splice-amulet, Splice.ValidatorLicense, ValidatorLicense), 00035557715ea1b2968df802198d23bdf49a7c1362fb1977b8cd0f8019e85eedbcca12..200f, ValidatorLicense_RecordValidatorLivenessActivity, ...
```
And the participant only learned about it after the test had already read it:
```
zcat $C | grep -a -E '"@timestamp":"2026-09-20T05:30:04\.8[89]' | grep -a 'participant=aliceParticipant' | grep -a -E 'Received the receipt|Traffic control handler observed' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,110}).*"logger_name":"([^"]*)".*/\1 [\3] \2/; s/\\n/ /g; s/  +/ /g' | cut -c1-230
```
```
2026-09-20T05:30:04.890Z [c.d.c.p.p.ParallelMessageDispatcher:participant=aliceParticipant/psid=global-domain::12200a6c8203::36-0] Received the receipt for a previously sent batch: Deliver( previous timestamp = Some(2026-09-20T05:30:04.661240Z), timestamp = 2026-09-20T05:30:04.806010Z, ...
2026-09-20T05:30:04.890Z [c.d.c.p.t.ParticipantTrafficControlSubscriber:participant=aliceParticipant/psid=global-domain::12200a6c8203::36-0] Traffic control handler observed timestamp: 2026-09-20T05:30:04.806010Z
```
So: test reads participant at .866 (view as of .326, 295144); receipt for the .806 submission reaches the participant
at .890; Scan reads the sequencer at .893 (298932). The 3788 difference is the 3947 cost minus 159 of base traffic
allowance the participant had regrown (the participant's own view still showed baseTrafficRemainder 0 at .326, the
sequencer showed 111 at .661).

How often alice submits while the suite runs (consumption events per second on globalSequencerSv1, cost > 0):
```
zcat $C | grep -a 'TrafficConsumedManager:sequencer=globalSequencerSv1' | grep -a 'PAR::aliceValidator' | grep -a -vE 'Consumed 0 for' | grep -a -E '"@timestamp":"2026-09-20T05:(29:[3-5]|30:0)' | grep -a -oE '"@timestamp":"2026-09-20T05:[0-9:]{5}' | cut -c15- | sort | uniq -c | awk '{printf "%s:%s ",$2,$1} END {print ""}'
```
```
2026-09-20T05:29:50:1 2026-09-20T05:29:51:2 2026-09-20T05:29:57:1 2026-09-20T05:29:58:3 2026-09-20T05:29:59:2 2026-09-20T05:30:00:2 2026-09-20T05:30:01:2 2026-09-20T05:30:02:2 2026-09-20T05:30:03:3 2026-09-20T05:30:04:2
```
Two to three submissions per second (the preceding test's traffic purchases plus validator automation), against a
27 ms window between the two reads and an 80 ms participant receipt latency: the test fails whenever a submission is
sequenced in the roughly 100 ms before the participant read.

## 5. Fix

Branch `ray/fix-10179-member-traffic-status-consistent-read` (f877bd3b79, off main 18f490ae5a): both reads and the
two `actual` comparisons move inside `eventually()`, so the test retries until it catches a consistent pair; the
`target.totalPurchased` comparison stays outside (contract state, top-ups disabled). One file, +9/-5. The test's
name and intent are unchanged; the participant view converges within one receipt latency. Not compiled or run here;
`apps-app/Test/scalafmtCheck` result in the README row.

## Verdict

- New test-side race, no duplicate; family I (test races). Flake, not a product problem: both values were correct
  for the instant each source was asked.
- Not verified: how often this hits (first occurrence I have seen; the test has been unchanged since 2024-10).
