# 10146 - ACS_COMMITMENT_MISMATCH sv1Participant vs aliceValidator after alice was multi-hosted on sv1 without ACS import (run 35077158925)

Post-merge CI on main, sha 8f931e71c0 "Backport PR #7325 to main (#7329)", 2026-09-16T09:02Z.
Canton runtime 3.6.0-snapshot.20260910.20260.0.v90621933. One failed job. Commands verified against
the artifacts in log/35077158925-wall-clock-4/.

Third occurrence of the recurring ACS_COMMITMENT_MISMATCH flake (earlier: run 34523566111 on
2026-09-10, ref 10129 = run 34838594625 on 2026-09-14). This packet adds the mechanism that the earlier
two could not name: the suite that ran right before the period multi-hosts alice's party on sv1Participant
without an ACS import, and Canton 3.6's new commitment processor re-buckets alice's pre-existing
contracts at that hosting change while sv1 has nothing to re-bucket.

## Categorization

- Test(s) affected: none asserted-failed. All 16 tests in the shard passed. The WARN was emitted at
  09:31:04 during the environment start of WalletPaymentIntegrationTest, for a commitment period that
  covers the last seconds of AmuletExpiryV1FallbackIntegrationTest and all of DistributedDomainIntegrationTest.
- Failure type: checkErrors WARN (un-allowlisted line in canton_before_shutdown.clog).
- Component: splice test design (party multi-hosting without party replication in
  ExpiryWithMinimalVettedPackagesIntegrationTest.scala and AutoIgnoreUnresponsivePartiesIntegrationTest)
  observed by Canton 3.6's new ACS commitment processor (ReceivedAcsCommitmentMatcher).
- Flake vs real: flake in the CI sense (rare because commitment sending is randomly delayed by up to
  0.9 x 30 min), but the digest divergence it reports is real and deliberately created by the test.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35077158925 --repo canton-network/splice -n logs-wall-clock-time-4 -D dl
cd dl
# canton_before_shutdown.clog.gz = shared Canton node log DURING the run; canton_network_test.clog.gz = harness + apps
# job-104732535931.log = full GHA job console (gh api repos/canton-network/splice/actions/jobs/104732535931/logs)
```

## 1. Failed job

```
sed -n '35p' job-104732535931.log
```
```
2026-09-16T09:06:25.1744288Z Complete job name: ci / scala_test_wall_clock_time / wall-clock-time (4)
```

## 2. checkErrors problem line as flagged; all tests passed

```
grep -anE 'lines with ignored|Found problems|lines with problems|\(checkErrors\) log|Process completed with exit code|Tests: succeeded|All tests passed' \
  job-104732535931.log | cut -c1-140
sed -n '11955p' job-104732535931.log | cut -c1-120
```
```
11067:2026-09-16T09:33:15.4194217Z [info] Tests: succeeded 16, failed 0, canceled 0, ignored 0, pending 0
11068:2026-09-16T09:33:15.4194971Z [info] All tests passed.
11952:2026-09-16T09:33:37.1464830Z Total: 327 lines with ignored entries.
11954:2026-09-16T09:33:40.0166737Z Found problems in log/canton_before_shutdown.clog:
11956:2026-09-16T09:33:40.0175339Z Total: 1 lines with problems.
11978:2026-09-16T09:33:40.0188191Z [error] (checkErrors) log/canton_before_shutdown.clog contains problems.
11983:2026-09-16T09:33:41.0335098Z ##[error]Process completed with exit code 1.
2026-09-16T09:33:40.0170859Z ***"@timestamp":"2026-09-16T09:31:04.138Z","message":"ACS_COMMITMENT_MISMATCH(5,e37a6
```

The shard's suites, in run order:

```
sed -n '9717p' job-104732535931.log | grep -oE 'tests\.[A-Za-z0-9]+' | sed 's/tests\.//' | paste -sd' '
zcat canton_network_test.clog.gz | grep -ac 'Test succeeded:'; zcat canton_network_test.clog.gz | grep -ac 'Test failed:'
```
```
SvOnboardingViaNonFoundingSvIntegrationTest TestTokenV2SettlementIntegrationTest BootstrapPackageConfigDarUploadIntegrationTest WalletSurviveCantonRestartIntegrationTest AmuletExpiryV1FallbackIntegrationTest DistributedDomainIntegrationTest WalletPaymentIntegrationTest UnsupportedPackageVettingIntegrationTest WalletTxLogAcsIntegrationTest
16
0
```

## 3. Canton runtime version; the NEW commitment processor is what runs

```
zcat canton_before_shutdown.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
cd <splice>; git show 8f931e71c0:nix/canton-sources.json | grep -m1 -oE '"version": *"[^"]+"'; cat canton/VERSION
grep -rlE 'ReceivedAcsCommitmentMatcher|RunningDigestProcessor|AcsCommitmentSender' canton/ | head -3
```
```
Canton version 3.6.0-snapshot.20260910.20260.0.v90621933
"version": "3.6.0-snapshot.20260910.20260.0.v90621933"
3.5.7-SNAPSHOT
(no matches: the classes that produced the WARN do not exist in the vendored canton/ tree)
```

The vendored `canton/` (3.5.7-SNAPSHOT) cannot be used to reason about this WARN at all: the runtime
disables the old `AcsCommitmentProcessor` on the global synchronizer and runs a new pipeline
(`AcsCommitmentProcessorManager`, `RunningDigestProcessorImpl`, `AcsCommitmentSender`,
`ReceivedAcsCommitmentMatcher`). Only the splitwell synchronizer still runs the old processor.

```
zcat canton_before_shutdown.clog.gz | grep -a '(Old) ACS commitment processor is disabled' | grep -aoE 'participant=[A-Za-z0-9]+/psid=[a-z-]+' | sort | uniq -c
zcat canton_before_shutdown.clog.gz | grep -a 'Initialized the ACS commitment processor DB queue' | grep -aoE 'participant=[A-Za-z0-9]+/psid=[a-z-]+' | sort | uniq -c
```
```
      5 participant=aliceParticipant/psid=global-domain
      4 participant=bobParticipant/psid=global-domain
      4 participant=splitwellParticipant/psid=global-domain
      8 participant=sv1Participant/psid=global-domain
      3 participant=sv2Participant/psid=global-domain
      4 participant=sv3Participant/psid=global-domain
      5 participant=sv4Participant/psid=global-domain
      3 participant=aliceParticipant/psid=splitwell
      1 participant=bobParticipant/psid=splitwell
      2 participant=splitwellParticipant/psid=splitwell
```

## 4. The full WARN: sv1Participant receives aliceValidator's commitment for (09:28:07.504068, 09:30:00]

```
zcat canton_before_shutdown.clog.gz | grep -a 'ACS_COMMITMENT_MISMATCH' | grep -a '"level":"WARN"' \
  | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | cut -c1-3000
zcat canton_before_shutdown.clog.gz | grep -a 'ACS_COMMITMENT_MISMATCH' | grep -a '"level":"WARN"' | grep -aoE 'digest = [^\)"]{0,40}'
zcat canton_before_shutdown.clog.gz | grep -ac 'ACS_COMMITMENT_MISMATCH'
```
```
{"@timestamp":"2026-09-16T09:31:04.138Z","message":"ACS_COMMITMENT_MISMATCH(5,e37a6a5b): The local commitment does not match the remote commitment","logger_name":"c.d.c.p.c.ReceivedAcsCommitmentMatcher:participant=sv1Participant/synchronizer=global-domain::12203f4eb526","thread_name":"canton-env-ec-72","level":"WARN","synchronizerId":"global-domain::12203f4eb526...","trace-id":"<HASH>","synchronizer":"global-domain::12203f4eb526...","location":"ReceivedAcsCommitmentMatcher.scala:202","span-id":"<HASH>","remote":"RemoteAcsCommitmentData(\n  sender = aliceValidator::122048d430a7...,\n  counterparticipant = sv1::1220e959c972...,\n  period = CommitmentPeriod(fromExclusive = 2026-09-16T09:28:07.504068Z, toInclusive = 2026-09-16T09:30:00Z),\n  digest = <HASH>\n)","error-code":"ACS_COMMITMENT_MISMATCH(5,e37a6a5b)","span-parent-id":"<HASH>","participant":"sv1Participant","local":"List(LocalDigest(period = CommitmentPeriod(fromExclusive = 2026-09-16T09:28:07.504068Z, toInclusive = 2026-09-16T09:30:00Z), digest = <HASH>))","span-name":"MessageDispatcher.handle"}
digest = 12205fb80c3fe260c8b26947441f3e4a44f863fa
digest = 12201ac23760d1859017c85e6eed62aab61e296f
1
```

- Observer (logger): `participant=sv1Participant`. Sender: `aliceValidator::122048d430a7...` (alice's
  participant id). Counter-participant: `sv1::1220e959c972...`.
- Period: fromExclusive 2026-09-16T09:28:07.504068Z, toInclusive 2026-09-16T09:30:00Z. Length 112.495932 s
  (`python3 -c "from datetime import datetime as d; print((d.fromisoformat('2026-09-16T09:30:00')-d.fromisoformat('2026-09-16T09:28:07.504068')).total_seconds())"`).
  toInclusive is again exactly the 30-minute reconciliation tick (09:30:00).
- Both sides agree on the period and disagree on the digest: remote (alice) `12201ac23760...` vs local (sv1)
  `12205fb80c3f...`. The digest is the digest of the ACS shared by the pair at toInclusive, so the period
  start is irrelevant to the disagreement.
- Exactly one WARN in the run; alice's side logged no mismatch (it only sees its own message, section 7).

## 5. The period start is the predecessor of alice's last request before the tick

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:(2[3-9]|30:[01])' \
  | grep -a 'TransactionProcessor:participant=aliceParticipant/psid=global-domain' | grep -a 'Phase 3: Validating' \
  | grep -aoE 'request=[0-9T:.Z-]+' | sort -u | tail -4 | paste -sd' '
zcat canton_before_shutdown.clog.gz | grep -a 'cleanReplayTs -> 2026-09-16T09:28:07.504068Z' | head -1 \
  | grep -aoE '"@timestamp":"[^"]*"|cleanReplayTs -> [^,]+|"logger_name":"[^"]{0,70}' | paste -sd' '
```
```
request=2026-09-16T09:27:46.174275Z request=2026-09-16T09:27:46.624613Z request=2026-09-16T09:28:07.104618Z request=2026-09-16T09:28:07.504069Z
"@timestamp":"2026-09-16T09:30:22.210Z" cleanReplayTs -> 2026-09-16T09:28:07.504068Z "logger_name":"c.d.c.p.p.PruningProcessor$:participant=aliceParticipant/psid=global-d
```

Alice's last request before 09:30:00 was sequenced at 09:28:07.504069 (it is also the last Phase 3 line
of alice on the global synchronizer until after the tick); the period starts 1 us before it
(`.immediatePredecessor`), and the participant's own PruningProcessor reports the same value as its
`cleanReplayTs` after the reconnect at 09:30:18. Alice was idle on the global synchronizer between
09:28:07.5 and its reconnect, whose replay starting point is the first event after the tick:

```
zcat canton_before_shutdown.clog.gz | grep -a '"@timestamp":"2026-09-16T09:30:18.371Z"' | grep -a 'Computed starting points' | grep -aoE 'clean replay = [^)]+\)'
```
```
clean replay = MessageCleanReplayStartingPoint(next request counter = 34, next sequencer counter = 508, prenext timestamp = 2026-09-16T09:30:17.946697Z)
```

So the period is the tail of the previous suite plus the idle stretch. The same rule explains the odd starts of the two earlier occurrences (20:29:33.037 and
11:59:59.523): they are alice's last request before the respective tick, not a topology event.

That last request was sv1 (as DSO) expiring alice's locked amulets at the end of AmuletExpiryV1FallbackIntegrationTest:

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:28:07\.(4[89]|5)' | grep -a 'Phase 1 completed' \
  | grep -a 'participant=sv1Participant' | grep -aoE '"message":"[^"]{0,200}'
zcat canton_network_test.clog.gz | grep -a '"@timestamp":"2026-09-16T09:28:07.482Z"' | grep -a 'SubmitAndWait' | grep -a 'SV=sv1' \
  | grep -aoE '[A-Z][A-Za-z]+_[A-Z][A-Za-z0-9_]+' | sort | uniq -c
```
```
"message":"Phase 1 completed: Submitting 4 envelopes for Transaction request, submitters digital-asset-2-45dc8c49::1220e959c972..., command-id 06e6729c-380e-48a8-9528-6088c063151c
      2 DsoRules_LockedAmulet_ExpireAmuletV2
```

## 6. Which suites were running

```
zcat canton_network_test.clog.gz | grep -aE "Starting test suite|Test succeeded|Finished creating environment: Starting all nodes" \
  | grep -aE '"@timestamp":"2026-09-16T09:(2[7-9]|3[01])' | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,110}' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -v '^$' | grep -v 'WalletPaymentIntegrationTest/' | cut -c1-200
```
```
"@timestamp":"2026-09-16T09:27:06.851Z" "message":"Test succeeded: 'WalletSurviveCantonRestartIntegrationTest/Wallet should survive Canton restarts'
"@timestamp":"2026-09-16T09:27:07.557Z" "message":"Starting test suite 'AmuletExpiryV1FallbackIntegrationTest'...
"@timestamp":"2026-09-16T09:27:25.515Z" "message":"Finished creating environment: Starting all nodes
"@timestamp":"2026-09-16T09:28:08.415Z" "message":"Test succeeded: 'AmuletExpiryV1FallbackIntegrationTest/Amulet expiry falls back to V1 choices when alice's val
"@timestamp":"2026-09-16T09:28:08.419Z" "message":"Starting test suite 'DistributedDomainIntegrationTest'...
"@timestamp":"2026-09-16T09:30:50.581Z" "message":"Test succeeded: 'DistributedDomainIntegrationTest/SV onboarding on distributed domain'
"@timestamp":"2026-09-16T09:30:50.587Z" "message":"Starting test suite 'WalletPaymentIntegrationTest'...
"@timestamp":"2026-09-16T09:31:04.189Z" "message":"Finished creating environment: Starting all nodes
```

The period (09:28:07.5, 09:30:00] starts 0.9 s before AmuletExpiryV1FallbackIntegrationTest finished and
runs through most of DistributedDomainIntegrationTest. Participants (sv1Participant, aliceParticipant) are
shared across the whole shard; each suite allocates new parties on them but the ACS of earlier suites
stays. DistributedDomainIntegrationTest only starts alice's validator app at 09:30:16 (after the tick):

```
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:(29|30:[01])' | grep -a 'Starting node aliceValidator' \
  | grep -aoE '"@timestamp":"[^"]*"' | head -1
cd <splice>; grep -n 'aliceValidatorBackend.startSync' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/DistributedDomainIntegrationTest.scala
```
```
"@timestamp":"2026-09-16T09:30:16.694Z"
116:    aliceValidatorBackend.startSync()
```

## 7. Only alice's commitment mismatched; sv3's and bob's for the same tick matched on sv1

```
zcat canton_before_shutdown.clog.gz | grep -a 'toInclusive = 2026-09-16T09:30:00Z' | grep -a 'participant=sv1Participant' \
  | grep -aE 'Checking commitment signature|Matching received|ACS_COMMITMENT_MISMATCH' | sed -E 's/[0-9a-f]{16,}/<HASH>/g' \
  | grep -aoE '"@timestamp":"[^"]*"|purportedly by\) [A-Za-z0-9]+|from [A-Za-z0-9]+::<HASH>|fromExclusive = [^,]+|ACS_COMMITMENT_MISMATCH' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -v '^$'
zcat canton_before_shutdown.clog.gz | grep -a 'Sending commitments for checkpoint' | grep -av 'was successful' | grep -aoE 'participant=[A-Za-z0-9]+' | sort | uniq -c
zcat canton_before_shutdown.clog.gz | grep -ac 'purportedly by) sv1::'
```
```
"@timestamp":"2026-09-16T09:30:44.665Z" purportedly by) sv3 fromExclusive = 2026-09-16T09:29:00.784783Z
"@timestamp":"2026-09-16T09:30:54.040Z" purportedly by) aliceValidator fromExclusive = 2026-09-16T09:28:07.504068Z
"@timestamp":"2026-09-16T09:30:54.058Z" from sv3::<HASH> fromExclusive = 2026-09-16T09:29:00.784783Z
"@timestamp":"2026-09-16T09:31:04.131Z" from aliceValidator::<HASH> fromExclusive = 2026-09-16T09:28:07.504068Z
"@timestamp":"2026-09-16T09:31:04.138Z" ACS_COMMITMENT_MISMATCH fromExclusive = 2026-09-16T09:28:07.504068Z ACS_COMMITMENT_MISMATCH fromExclusive = 2026-09-16T09:28:07.504068Z
"@timestamp":"2026-09-16T09:32:30.540Z" purportedly by) bobValidator fromExclusive = 2026-09-16T09:27:33.303927Z
"@timestamp":"2026-09-16T09:32:31.148Z" from bobValidator::<HASH> fromExclusive = 2026-09-16T09:27:33.303927Z
      1 participant=aliceParticipant
      1 participant=bobParticipant
      1 participant=sv3Participant
0
```

Three participants sent a commitment in the whole run (alice, bob, sv3), all for the 09:30:00 tick. sv1
matched sv3's (09:30:54) and bob's (09:32:31) without complaint and mismatched only alice's. sv1 itself
never sent one (section 8). Alice's message was received by sv1 and by alice itself (the sender is a
recipient of its own broadcast); alice's own matcher matched trivially:

```
zcat canton_before_shutdown.clog.gz | grep -a 'ReceivedCommitmentCheckpoint was written at recordTime=2026-09-16T09:30:53.826789Z' | grep -aoE 'participant=[A-Za-z0-9]+' | sort | uniq -c
```
```
      1 participant=aliceParticipant
      1 participant=sv1Participant
```

## 8. Why a mismatch is only seen once in a while: randomized send delay and constant pipeline restarts

The new sender delays each tick's send by a uniform random fraction of the 30-minute interval (max 0.9,
i.e. up to 27 min). Every participant (re)connect restarts the pipeline and re-draws the delay. Splice
apps reconnect their participants at every suite start and whenever sequencer connections change, so in a
2-minute suite a commitment only leaves a participant when it draws a delay of about a minute or less.

```
zcat canton_before_shutdown.clog.gz | grep -a 'Delaying sending commitment by' \
  | grep -aoE 'by [0-9]+ (seconds|milliseconds)|participant=[A-Za-z0-9]+' | paste -sd' ' | sed 's/participant/\nparticipant/g' | grep -v '^$' | sort | uniq -c | sort -k2
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:30:(0[7-9]|1[0-9])' \
  | grep -aE 'participant=(alice|sv1)Participant/psid=global-domain|participant=(alice|sv1)Participant/synchronizer=global-domain|AcsCommitmentProcessorManager:participant=(alice|sv1)Participant' \
  | grep -aE 'ReconciliationIntervalBoundary was written|send-loop terminated|Starting commitment processor pipeline for synchronizer global|Delaying sending commitment' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,120}|participant=[A-Za-z0-9]+' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -v '^$' | cut -c1-230
```
```
      1 participant=aliceParticipant by 216 seconds
      1 participant=aliceParticipant by 512 seconds
      1 participant=aliceParticipant by 53872 milliseconds
      1 participant=bobParticipant by 1454 seconds
      1 participant=bobParticipant by 103 seconds
      1 participant=bobParticipant by 636 seconds
      1 participant=bobParticipant by 834 seconds
      1 participant=bobParticipant by 886 seconds
      1 participant=splitwellParticipant by 103 seconds
      1 participant=splitwellParticipant by 636 seconds
      1 participant=splitwellParticipant by 919 seconds
      1 participant=sv1Participant by 1220 seconds
      1 participant=sv1Participant by 251 seconds
      1 participant=sv1Participant by 497 seconds
      1 participant=sv2Participant by 1120 seconds
      1 participant=sv3Participant by 264 seconds
      1 participant=sv3Participant by 292 seconds
      1 participant=sv3Participant by 44177 milliseconds
      1 participant=sv4Participant by 1389 seconds
      1 participant=sv4Participant by 453 seconds
"@timestamp":"2026-09-16T09:30:08.775Z" "message":"An ACS digest checkpoint ReconciliationIntervalBoundary was written at recordTime=2026-09-16T09:30:00Z, offset=Offset(16 participant=sv1Participant
"@timestamp":"2026-09-16T09:30:08.809Z" "message":"Delaying sending commitment by 251 seconds (interval = 30m, min = 0.0, max = 0.9) participant=sv1Participant
"@timestamp":"2026-09-16T09:30:09.308Z" "message":"The send-loop terminated due to an orderly shutdown. participant=sv1Participant
"@timestamp":"2026-09-16T09:30:09.591Z" "message":"Starting commitment processor pipeline for synchronizer global-domain::12203f4eb526... participant=sv1Participant
"@timestamp":"2026-09-16T09:30:09.597Z" "message":"Delaying sending commitment by 497 seconds (interval = 30m, min = 0.0, max = 0.9) participant=sv1Participant
"@timestamp":"2026-09-16T09:30:10.677Z" "message":"The send-loop terminated due to an orderly shutdown. participant=sv1Participant
"@timestamp":"2026-09-16T09:30:10.726Z" "message":"Starting commitment processor pipeline for synchronizer global-domain::12203f4eb526... participant=sv1Participant
"@timestamp":"2026-09-16T09:30:10.728Z" "message":"Delaying sending commitment by 1220 seconds (interval = 30m, min = 0.0, max = 0.9) participant=sv1Participant
"@timestamp":"2026-09-16T09:30:17.435Z" "message":"An ACS digest checkpoint ReconciliationIntervalBoundary was written at recordTime=2026-09-16T09:30:00Z, offset=Offset(81 participant=aliceParticipant
"@timestamp":"2026-09-16T09:30:17.436Z" "message":"Delaying sending commitment by 512 seconds (interval = 30m, min = 0.0, max = 0.9) participant=aliceParticipant
"@timestamp":"2026-09-16T09:30:18.323Z" "message":"The send-loop terminated due to an orderly shutdown. participant=aliceParticipant
"@timestamp":"2026-09-16T09:30:18.389Z" "message":"Starting commitment processor pipeline for synchronizer global-domain::12203f4eb526... participant=aliceParticipant
"@timestamp":"2026-09-16T09:30:18.391Z" "message":"Delaying sending commitment by 53872 milliseconds (interval = 30m, min = 0.0, max = 0.9) participant=aliceParticipant
```

Alice's first draw (512 s) would never have fired inside this shard; the reconnect at 09:30:18 (alice's
validator app starting in DistributedDomainIntegrationTest) re-drew 53.9 s and the commitment went out at
09:30:53. The 09:30:00 tick itself was only detected by alice at 09:30:17 because alice saw no event
between 09:28:07.5 and 09:30:17.9. sv1 restarted its pipeline 0.5 s and 1.9 s after writing its own tick
checkpoint and drew 251/497/1220 s, so it never sent. A restart between tick checkpoint and send does not
by itself corrupt anything: sv3 (restart 4 s after its checkpoint, sent 09:30:44) and bob (restarts at
09:31:08 and 09:32:30) both matched on sv1.

## 9. Topology, party and ACS changes inside the period

No reassignments and no repair on sv1 or alice. The only ACS imports in the window are the DSO party
being onboarded to sv2, sv3 and sv4 (DistributedDomainIntegrationTest), which is normal SV onboarding and
does not touch the (sv1, alice) pair:

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:(28:0[7-9]|28:[1-5]|29|30|31:0[0-5])' | grep -av '"level":"DEBUG"' \
  | grep -aE 'RepairService|ReassignmentProcessor|UnassignmentProcessor|AssignmentProcessor|reassignment request|ImportAcs' \
  | grep -aE 'Adding contracts|finished' | grep -aoE '"@timestamp":"[^"]*"|Persisted: [0-9]+ new active contracts|participant=[A-Za-z0-9]+' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -v '^$'
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:(28:[1-5]|29)' | grep -a 'participant=sv1Participant' \
  | grep -a 'Attempting to build, sign, and Replace PartyToParticipant(partyId = DSO' | sed -E 's/[0-9a-f]{16,}/<HASH>/g' \
  | grep -aoE '"@timestamp":"[^"]*"|PAR::sv[0-9]::[0-9a-f]{12}[^ ,)]* -> [A-Za-z()]+' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -v '^$' | cut -c1-260
```
```
"@timestamp":"2026-09-16T09:28:51.085Z" participant=sv2Participant
"@timestamp":"2026-09-16T09:28:51.252Z" Persisted: 18 new active contracts participant=sv2Participant
"@timestamp":"2026-09-16T09:28:52.323Z" participant=sv3Participant
"@timestamp":"2026-09-16T09:28:52.453Z" Persisted: 21 new active contracts participant=sv3Participant
"@timestamp":"2026-09-16T09:28:56.506Z" participant=sv4Participant
"@timestamp":"2026-09-16T09:28:56.602Z" Persisted: 22 new active contracts participant=sv4Participant
"@timestamp":"2026-09-16T09:28:11.777Z" PAR::sv1::1220e959c972... -> Submission)
"@timestamp":"2026-09-16T09:28:50.669Z" PAR::sv1::1220e959c972... -> Submission PAR::sv2::122068b0a2bf... -> Submission(onboarding)))
```

(The sv3 and sv4 additions at 09:28:51.8 and 09:28:53+ are multi-line `PartyToParticipant(\n partyId` messages
that this one-line regex does not catch; their effective times appear as PartyHostingChange checkpoints below.)

The new processor explicitly tracks party hosting changes: it writes a `PartyHostingChange` digest
checkpoint on every participant at the effective time of every PartyToParticipant change (13 of them
inside the period, each written by both sv1 and alice at identical record times):

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:(28:0[7-9]|28:[1-5]|29|30:0)' \
  | grep -aE 'participant=(alice|sv1)Participant/synchronizer=global-domain' | grep -a 'ACS digest checkpoint PartyHostingChange' \
  | grep -aoE 'recordTime=[^,]+|participant=[A-Za-z0-9]+' | paste -sd' ' | sed 's/recordTime/\nrecordTime/g' | grep -v '^$' | awk '{print $1}' | sort | uniq -c
```
```
      2 recordTime=2026-09-16T09:28:11.834038Z
      2 recordTime=2026-09-16T09:28:11.834039Z
      2 recordTime=2026-09-16T09:28:13.534568Z
      2 recordTime=2026-09-16T09:28:13.974651Z
      2 recordTime=2026-09-16T09:28:14.933352Z
      2 recordTime=2026-09-16T09:28:15.484142Z
      2 recordTime=2026-09-16T09:28:15.484143Z
      2 recordTime=2026-09-16T09:28:50.735863Z
      2 recordTime=2026-09-16T09:28:52.095815Z
      2 recordTime=2026-09-16T09:28:53.335018Z
      2 recordTime=2026-09-16T09:28:53.617394Z
      2 recordTime=2026-09-16T09:28:56.114669Z
      2 recordTime=2026-09-16T09:28:58.447451Z
```

## 10. The mechanism: alice's party was multi-hosted on sv1Participant 2.5 min earlier, without ACS import

AmuletExpiryV1FallbackIntegrationTest (and its two siblings in the same file) multi-host alice's wallet
user party on sv1Participant so the test can create bare Amulet/LockedAmulet contracts for alice from sv1:

```
cd <splice>
F=apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ExpiryWithMinimalVettedPackagesIntegrationTest.scala
grep -nE '^class |^abstract class ' $F
sed -n '170,178p' $F
grep -n 'onboardWalletUser(aliceWalletClient' $F
git log --format='%h %ad %s' --date=short -S 'Multi-host alice on sv1Participant' -- $F
```
```
43:abstract class ExpiryWithMinimalVettedPackagesIntegrationTestBase
238:class AmuletExpiryV1FallbackIntegrationTest
267:class ExpiryWithIgnoredAmuletVersionIntegrationTest
400:class ExpiryWithNoVettedAmuletVersionIntegrationTest
    // Multi-host alice on sv1Participant to be able to create bare Amulet and LockedAmulet contracts
    actAndCheck(
      "Multi-host alice on sv1Participant (alice keeps her old host)",
      eventuallySucceeds() {
        aliceParticipant.topology.party_to_participant_mappings.propose_delta(
          party = aliceParty,
          adds = Seq((sv1ParticipantId, ParticipantPermission.Submission)),
          store = synchronizerId,
        )
157:    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
3f4ad9e9c1 2026-08-06 Add bad counterparties auto-ignore mechanism in Ans and Transfer pre-approval expiry triggers (#6680)
```

The party is onboarded first (line 157, which creates contracts for it), then the hosting is extended
with a plain `propose_delta` (Submission permission, no `onboarding` flag) and no party replication / ACS
import onto sv1. In the log:

```
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:27' | grep -a 'participant=sv1Participant' \
  | grep -a 'Attempting to build, sign, and Replace PartyToParticipant(partyId = alice' | sed -E 's/[0-9a-f]{16,}/<HASH>/g' \
  | grep -aoE '"@timestamp":"[^"]*"|partyId = [^,]+|PAR::[A-Za-z0-9]+::[0-9a-f]{12}[^ ,)]* -> [A-Za-z()]+' | paste -sd' '
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:27:4[4-6]' | grep -a 'participant=sv1Participant' | grep -a 'Persisted topology transactions' \
  | grep -a 'alice__wallet__user-45dc8c49' | grep -a 'PAR::sv1' | grep -aoE 'SequencedTime\([^)]+\), EffectiveTime\([^)]+\)' | sort -u | tail -1
zcat canton_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:2(7|8:0[0-7])' | grep -a 'participant=sv1Participant' | grep -ac 'RepairServiceContractsImporter'
```
```
"@timestamp":"2026-09-16T09:27:44.742Z" partyId = alice__wallet__user-45dc8c49::122048d430a7... PAR::aliceValidator::122048d430a7... -> Submission PAR::sv1::1220e959c972... -> Submission))
SequencedTime(2026-09-16T09:27:44.724481Z), EffectiveTime(2026-09-16T09:27:44.974481Z)
0
```

Contracts of that party that have no DSO/sv1-hosted stakeholder already existed on aliceParticipant before
the hosting change took effect at 09:27:44.974481 (alice's requests at 09:27:36.245 .. 09:27:43.624,
section 5), and sv1 never received them:

```
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T09:27:(3[5-9]|4[0-4])' | grep -a 'alice__wallet__user-45dc8c49' \
  | grep -aoE '"@timestamp":"[^"]*"|Splice\.Wallet\.Install:WalletAppInstall|Splice\.Amulet:ValidatorRight' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | grep -E 'Install|Right' | awk '!seen[$2]++'
cd <splice>; grep -n -A7 'template WalletAppInstall' daml/splice-wallet/daml/Splice/Wallet/Install.daml | grep -E 'signatory'
grep -n -A6 'template ValidatorRight' daml/splice-amulet/daml/Splice/Amulet.daml | grep -E 'signatory'
```
```
"@timestamp":"2026-09-16T09:27:43.657Z" Splice.Wallet.Install:WalletAppInstall
"@timestamp":"2026-09-16T09:27:44.078Z" Splice.Amulet:ValidatorRight
    signatory endUserParty, validatorParty
    signatory user, validator
```

Both templates have only alice's user party and alice's validator operator party as stakeholders, i.e.
before 09:27:44.97 they were hosted on aliceParticipant only and were not part of any (sv1, alice)
commitment. After the hosting change alice's user party is hosted on sv1 as well, so alice's side counts
them as shared with counter-participant sv1, while sv1 has never seen them. From that point on the
(sv1, alice) digests differ at every tick for as long as both participants live; the 09:30:00 tick was the
first one after the change, and the mismatch was reported by the side that received a commitment (sv1).

The same construct exists in one more suite; MultiHostValidatorOperatorIntegrationTest does the same on
bobParticipant (Observation), which is the pair the existing ignore line 145 happens to cover:

```
cd <splice>; grep -rlniE 'multi-?host' apps/app/src/test/scala --include=*.scala
grep -n 'Multi-host alice on sv1Participant' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/AutoIgnoreUnresponsivePartiesIntegrationTest.scala
```
```
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/AutoIgnoreUnresponsivePartiesIntegrationTest.scala
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ExpiryWithMinimalVettedPackagesIntegrationTest.scala
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/MultiHostValidatorOperatorIntegrationTest.scala
89:        "Multi-host alice on sv1Participant",
```

## 11. No ignore pattern covers this pair

```
cd <splice>; grep -nE 'ACS_COMMITMENT|ACS_MISMATCH' project/ignore-patterns/canton_log.ignore.txt | cut -c1-120
```
```
66:.*Received a commitment where we have no shared contract.*ACS_MISMATCH_NO_SHARED_CONTRACTS.*
90:# In ValidatorReonboardingIntegrationTest, importing the ACS on the new participant may lead to temporary ACS_COMMITM
92:ACS_COMMITMENT_MISMATCH.*aliceValidatorLocalNewForValidatorReonboardingIT
145:ACS_COMMITMENT_MISMATCH.*(bob|alice)Participant
155:ACS_COMMITMENT_DEGRADATION
```

The WARN line names `participant=sv1Participant` and `sender = aliceValidator::...`; neither
`aliceParticipant` nor `bobParticipant` appears in it, so line 145 does not match. Line 92 is for a
different participant name.

## 12. Not a regression of the failing sha

```
cd <splice>; git log --oneline -6 8f931e71c0 | cat
git log --format='%h %cd %s' --date=short -8 8f931e71c0 -- nix/canton-sources.json | tail -1
```
```
8f931e71c0 Backport PR #7325 to main (#7329)
f1ee318e39 Don't wait forever on a non-active psid in `ensureSynchronizerRegisteredAndConnected` (#7311)
75c22422b7 Fix inverted ACS commitment component health condition (#7327)
de254044f1 Fix commitment pv 36 dashboard variables (#7326)
0c43730f70 Fix flake in WalletMintingDelegationTimeBasedIntegrationTest caused by time advancing too far (#7261)
8c20340d0d Fix commitment health alerting (#7322)
a7adcc5c0d 2026-08-21 Upgrade Canton to 3.6.0-snapshot.20260818.20026.0.v41046c3b (#6859)
```

The multi-hosting has been in the tests since 2026-08-06 (#6680); Canton 3.6 (new commitment processor)
has been on main since 2026-08-21 (#6859). The commits at the failing sha only touch commitment health
metrics/alerting, not the processor or the tests.

## Root cause / hypothesis

Proven (from logs and source):
- Single flagged line: sv1Participant's ReceivedAcsCommitmentMatcher WARN at 09:31:04.138 for
  aliceValidator's commitment over (09:28:07.504068, 09:30:00]; same period on both sides, digests differ.
  The runtime is Canton 3.6.0-snapshot.20260910 with the NEW commitment pipeline; the vendored canton/
  tree has none of the classes involved.
- fromExclusive is the immediate predecessor of alice's last request before the tick (09:28:07.504069,
  DsoRules_LockedAmulet_ExpireAmuletV2 by sv1 at the end of AmuletExpiryV1FallbackIntegrationTest), not a
  topology event. The period ends on the first 30-min tick after that suite.
- Alice's wallet user party `alice__wallet__user-45dc8c49` was multi-hosted on sv1Participant at
  09:27:44.97 with a plain `propose_delta` (Submission, no onboarding flag) and no ACS import onto sv1
  (0 RepairServiceContractsImporter lines on sv1). Contracts with only alice-hosted stakeholders
  (WalletAppInstall 09:27:43.66, ValidatorRight 09:27:44.08) existed before the change.
- The new processor writes PartyHostingChange checkpoints on every PartyToParticipant change on both
  participants; sv3's and bob's commitments for the same tick matched on sv1; alice's did not.
- Commitment sending is randomized over up to 27 min and re-drawn at every participant reconnect, so
  only 3 commitments were sent in the whole run. The WARN needs a sender to draw a sub-minute delay.
- No reassignment, repair, purge or unvetting on sv1/alice in the window; the DSO ACS import onto sv2-4
  is ordinary SV onboarding and the pairs involving sv3 matched.

Inferred:
- The digest divergence is alice-side contracts of the multi-hosted party that sv1 never received.
  Alice's processor re-buckets them under counter-participant sv1 at the PartyHostingChange; sv1 has
  nothing to re-bucket. The contract-level content of the digests is not logged, so this is the only
  candidate consistent with all of the above rather than a direct observation.
- Why only since 2026-09-10 although the test construct dates from 2026-08-06: the old processor (Canton
  3.5) fixed counter-participant sets at ACS-change time and did not re-evaluate them at hosting
  changes; the new one does. Not verified against Canton source (vendored tree is too old).
- The "first partial period" pattern of the earlier notes is a by-product of two things, not a cause:
  the period start is the sender's last request before the tick, and CI shards rarely survive to a
  second tick. There is no evidence that a second period would re-converge; with this mechanism it would
  mismatch again as long as both participants are up.

Comparison of the three occurrences:

| run | date | period (fromExclusive, toInclusive] | length | observer | sender | multi-host suite in shard |
|-----|------|--------------------------------------|--------|----------|--------|---------------------------|
| 34523566111 | 2026-09-10 | (20:29:33.037, 20:30:00] | 27 s | sv1Participant | aliceValidator | ExpiryWithNoVettedAmuletVersionIntegrationTest (same base class, same multi-host, per earlier notes) |
| 34838594625 (10129) | 2026-09-14 | (11:59:59.523, 12:00:00] | 0.477 s | sv1Participant | aliceValidator | not recorded in the notes; check the shard for AmuletExpiryV1Fallback / ExpiryWithIgnoredAmuletVersion / ExpiryWithNoVettedAmuletVersion / AutoIgnoreUnresponsiveParties |
| 35077158925 (10146) | 2026-09-16 | (09:28:07.504068, 09:30:00] | 112.5 s | sv1Participant | aliceValidator | AmuletExpiryV1FallbackIntegrationTest, 09:27:07-09:28:08, multi-host at 09:27:44.97 (this packet) |

## Duplicates / related

- Duplicate of ref 10129 (run 34838594625) and of run 34523566111: same observer, same sender, same
  synchronizer, same shape. Two of the three shards are now known to contain a suite from
  ExpiryWithMinimalVettedPackagesIntegrationTest.scala; the 10129 shard needs the same check.
- Related ignore entries: canton_log.ignore.txt:90-92 (ValidatorReonboarding ACS import) and :145
  (party migration in RecoverExternalPartyIntegrationTest) exist for the same class of cause (a party's
  hosting changes without both hosts having the same ACS), with other participant names.
- Related upstream: DACH-NY/canton-network-internal#2050 (participants.conf:25,
  `do-not-await-on-checking-incoming-commitments`) is about commitment processing blocking, not this.

## Suggested next step / owner

1. Close the open investigation as "test creates a real ACS divergence": the mismatch is not a Canton
   commitment bug and not a boundary artifact. The `ray/test-acs-reconciliation-interval` learning branch
   (30 s interval) is no longer needed to decide benign-vs-real; if run anyway it would show the
   (sv1, alice) pair mismatching at every tick after a multi-host suite, which is what this packet predicts.
2. Fix in the tests, not in the ignore file (owner: whoever owns #6680 / the expiry tests, plus
   AutoIgnoreUnresponsivePartiesIntegrationTest). Options, in order of preference:
   a. Multi-host alice on sv1Participant BEFORE `onboardWalletUser` creates any contract for the party
      (allocate the party, extend hosting, then onboard), so both hosts see every contract of the party.
   b. Or use proper party replication (`onboarding` flag + ACS export/import onto sv1), the same way
      ValidatorReonboardingIntegrationTest does.
   c. Or, if the test intentionally leaves sv1 without the party's older contracts, remove the extra host
      again (`propose_delta(removes = Seq(sv1ParticipantId))`) at the end of the suite so later suites in
      the shard do not carry the divergence into the next tick.
3. Do NOT widen ignore line 145 to `sender = aliceValidator`: alice is the counterparty of most test
   traffic and this occurrence shows the detector reporting a genuine divergence that the tests create.
   If a stop-gap is needed before 2 lands, scope it to the offending suites via the party name suffix
   pattern in the WARN's sender/counterparticipant fields and give it the real comment (party multi-hosted
   without ACS import in Expiry*/AutoIgnoreUnresponsiveParties suites).
4. Verify on the 10129 artifacts (run 34838594625) that a multi-host suite ran before 12:00:00 in that
   shard; if none did, there is a second mechanism and this packet's inference is incomplete.

## Summary

wall-clock-time (4), job 104732535931, canton 3.6.0-snapshot.20260910.20260.0.v90621933. All 16 tests
passed; checkErrors flags one WARN: sv1Participant's new-pipeline ReceivedAcsCommitmentMatcher rejects
aliceValidator's commitment for (09:28:07.504068, 09:30:00] (first tick after AmuletExpiryV1FallbackIntegrationTest).
That suite multi-hosted alice's wallet party on sv1Participant at 09:27:44.97 with no ACS import after the
party already owned alice-only contracts, so the (sv1, alice) shared-ACS digests diverge from then on.
sv3's and bob's commitments for the same tick matched. The WARN is rare only because commitment sends are
randomly delayed by up to 27 min and re-drawn at every reconnect. Duplicate of 10129 / run 34523566111.
Fix the tests (host before onboarding, or replicate the party properly); do not widen the ignore.
