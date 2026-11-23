# 11564 - frontend-wall-clock-time (2): onboardWalletUser HTTP 400 INVALID_PRESCRIBED_SYNCHRONIZER_ID while sv1's BFT sequencer is blacklisted (run 44301172116)

DUPLICATE of 11255 (family B, blacklisting in a wall-clock suite with an unchanged ordering topology, same shape as
11395). main ec059037fa, job 134792368662, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. 30 tests succeeded, 2
failed, both in TokenStandardWalletTransfersFrontendIntegrationTest ("allow accepting transfer offers", "allow
rejecting transfer offers"); checkErrors skipped. Both fail in the setup call `onboardWalletUser` with
`INVALID_PRESCRIBED_SYNCHRONIZER_ID ... Not all informees are on the specified synchronizer: global-domain ..., but on
Set(splitwell...)`: the PartyToParticipant of the new wallet user party reaches the splitwell synchronizer but not
the global one, because every participant submits to global through sv1's sequencer and sv1 is blacklisted by the
DA BFT leader selection for epochs 203-207 (12:50:20.400 to 12:51:04.908). The blacklisting follows BFT segment
timeouts (12:49:47.96) after the Canton JVM slowed down at 12:49:39; the ordering topology did not change (size 4
since 12:42:11). The cause of the slowdown is not identified, and the `Supervising futures itself takes longer` line
of 11395 is absent in both JVMs.

- Run: https://github.com/canton-network/splice/actions/runs/44301172116, main ec059037fa ("Resume interrupted
  onboarding snapshot downloads (#7796)"), job 134792368662 `ci / scala_test_frontend_wall_clock_time /
  frontend-wall-clock-time (2)`. The run's other failed job, docker-compose (1), is 11563.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: Canton DA BFT blacklisting, enabled in wall-clock test environments (splice#8356 disables it only in
  `EnvironmentDefinition.withSimTime`).

Paths below are relative to the triage checkout; `A=log/11564/logs-frontend-wall-clock-time-2`.
`F` is the generic formatter of recipes.md section 4.

## 1. Failing assertion

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134792368662/logs > log/11564/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11564/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|Failed tests' -A1 | sed -E 's/^[^Z]*Z //'
```
```
[info] - should allow accepting transfer offers *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
[info] - should allow rejecting transfer offers *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
[info] Run completed in 28 minutes, 5 seconds.
[info] Tests: succeeded 30, failed 2, canceled 0, ignored 0, pending 0
[error] Failed tests:
[error] 	org.lfdecentralizedtrust.splice.integration.tests.TokenStandardWalletTransfersFrontendIntegrationTest
```

## 2. Suite timeline and the failed command

```
zcat $A/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a TokenStandardWalletTransfers | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-260
```
```
2026-11-18T12:46:38.861Z Starting test suite 'TokenStandardWalletTransfersFrontendIntegrationTest'...
2026-11-18T12:47:59.039Z Starting '.../A wallet UI should create a p2p transfer'...
2026-11-18T12:48:44.239Z Test succeeded: '.../A wallet UI should create a p2p transfer'
2026-11-18T12:49:13.047Z Test succeeded: '.../A wallet UI should show a list of transfer offers'
2026-11-18T12:49:43.631Z Test succeeded: '.../A wallet UI should not show transfer offers I created'
2026-11-18T12:49:43.632Z Starting '.../A wallet UI should allow accepting transfer offers'...
2026-11-18T12:50:29.836Z Test failed: '.../A wallet UI should allow accepting transfer offers', message: Command execution failed., location: SeeStackDepthException
2026-11-18T12:50:29.841Z Starting '.../A wallet UI should allow rejecting transfer offers'...
2026-11-18T12:50:40.185Z Test failed: '.../A wallet UI should allow rejecting transfer offers', message: Command execution failed., location: SeeStackDepthException
```

```
zcat $A/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-18T12:(49:4[3-9]|49:5|50:[0-4])' > log/11564/win.jsonl
grep -a -E 'clue: Onboard|HttpCommandException' log/11564/win.jsonl | sed -E "$F" | cut -c1-420
```
```
2026-11-18T12:49:43.632Z Running clue: Onboard alice_wallet_user-fe6e8b4a_tc3 on aliceValidator
2026-11-18T12:50:04.728Z Finished clue: Onboard alice_wallet_user-fe6e8b4a_tc3 on aliceValidator
2026-11-18T12:50:04.754Z Running clue: Onboard bob_wallet_user-fe6e8b4a_tc3 on bobValidator
2026-11-18T12:50:29.790Z org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 400 Bad Request POST at '/api/validator/v0/admin/users' on 127.0.0.1:5603. Command failed, message: INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,e3a24b93): Not all informees are on the specified synchronizer: global-domain::1220099a9f4b...::37-0, but on Set(splitwell::12200e0c6de1...::36-0) ... [o.l.s.i.EnvironmentDefinition$$anon$43:.../config=fe6e8b4a] ERROR
2026-11-18T12:50:29.790Z Failed clue: Onboard bob_wallet_user-fe6e8b4a_tc3 on bobValidator
2026-11-18T12:50:40.161Z org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 400 Bad Request POST at '/api/validator/v0/admin/users' on 127.0.0.1:5503. Command failed, message: INVALID_PRESCRIBED_SYNCHRONIZER_ID(9,5b719436): Not all informees are on the specified synchronizer: global-domain::1220099a9f4b...::37-0, but on Set(splitwell::12200e0c6de1...::36-0) ...
2026-11-18T12:50:40.161Z Failed clue: Onboard alice_wallet_user-fe6e8b4a_tc4 on aliceValidator
```
The first onboard of the failing test (alice, tc3) already needed 21 s and 5 installWalletForUser submissions.

## 3. Duplicate check: epochs and blacklist on globalSequencerSv1, topology unchanged

```
zcat $A/canton.clog.gz | grep -a -E 'New epoch [0-9]+ has started|Supervising futures itself takes longer' | grep -a -E 'globalSequencerSv1|Supervising' > log/11564/epochs.jsonl
sed -E "$F" log/11564/epochs.jsonl | grep -a 'T12:49:[2-5]\|T12:5[01]:[01]' | sed -E 's/SEQ::(sv[0-9])::[^,) ]*/\1/g; s/; ordering topology = OrderingTopology\(\\n  activationTime = ([^,]+),\\n  size = ([0-9]).*/ activationTime=\1 size=\2/' | cut -c1-200
```
```
2026-11-18T12:49:33.252Z New epoch 200 has started with leaders = List(sv1, sv2, sv3, sv4)and blacklisted nodes = List() activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:49:37.944Z New epoch 201 has started with leaders = List(sv2, sv3, sv4, sv1)and blacklisted nodes = List() activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:49:53.877Z New epoch 202 has started with leaders = List(sv3, sv4, sv1)and blacklisted nodes = List(sv2) activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:50:20.400Z New epoch 203 has started with leaders = List(sv4, sv2, sv3)and blacklisted nodes = List(sv1) activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:50:36.956Z New epoch 204 has started with leaders = List(sv2, sv3, sv4)and blacklisted nodes = List(sv1) activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:50:43.726Z New epoch 205 has started with leaders = List(sv3, sv4, sv2)and blacklisted nodes = List(sv1) activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:50:50.667Z New epoch 206 has started with leaders = List(sv4, sv2, sv3)and blacklisted nodes = List(sv1) activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:50:57.927Z New epoch 207 has started with leaders = List(sv2, sv3, sv4)and blacklisted nodes = List(sv1) activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:51:04.908Z New epoch 208 has started with leaders = List(sv1, sv2, sv3, sv4)and blacklisted nodes = List() activationTime=2026-11-18T12:42:11.486535Z size=4
2026-11-18T12:51:09.954Z New epoch 209 has started with leaders = List(sv2, sv3, sv4, sv1)and blacklisted nodes = List() activationTime=2026-11-18T12:42:11.486535Z size=4
```
(Epochs 200 to 209 shown; the command also prints 198, 199, 210, 211.) Epochs 156-200 (12:45:04 to 12:49:33) all have `blacklisted nodes = List()` and last 4-9 s; epoch 201 lasts 15.9 s,
epoch 202 26.5 s.

Mempool rejections while blacklisted (the family's blacklisting grep):
```
zcat $A/canton.clog.gz | grep -a -E '"@timestamp":"2026-11-18T12:(49:[3-5]|50:|51:[0-2])' > log/11564/cwin.jsonl
grep -a 'currently blacklisted, rejecting' log/11564/cwin.jsonl | grep -a MempoolModule | grep -a -oE 'sequencer=[A-Za-z0-9]+' | sort | uniq -c
grep -a 'currently blacklisted, rejecting' log/11564/cwin.jsonl | grep -a MempoolModule | grep -a globalSequencerSv1 | sed -n '1p;$p' | cut -c1-60
```
```
    105 sequencer=globalSequencerSv1
     29 sequencer=globalSequencerSv2
{"@timestamp":"2026-11-18T12:50:23.773Z","message":"Mempool 
{"@timestamp":"2026-11-18T12:51:04.235Z","message":"Mempool 
```

The host-stall half of the 11395 confirm does NOT match:
```
grep -a -c 'Supervising futures itself' log/11564/epochs.jsonl; zcat $A/canton_network_test.clog.gz | grep -a -c 'Supervising futures itself takes longer'
```
```
0
0
```

## 4. Why bob's party (test 4) never reached global: batch held by the blacklisted sv1 past max sequencing time

```
grep -a 'participant=bobParticipant' log/11564/cwin.jsonl | grep -a StoreBasedSynchronizerOutbox | grep -a -E 'Attempting to push|responded' | grep -a global | sed -E "$F" | cut -c1-200
grep -a 'cba969df-4ff6-4fa5-acde-c852f0db0f97' log/11564/cwin.jsonl | sed -E "$F" | cut -c1-230
```
```
2026-11-18T12:50:04.799Z Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:0d18a1c2568d...))
2026-11-18T12:51:04.995Z Synchronizer 'global' responded the following for the given topology transactions: List(Failed)
2026-11-18T12:51:13.785Z Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:0d18a1c2568d...))
2026-11-18T12:51:14.349Z Synchronizer 'global' responded the following for the given topology transactions: List(Accepted)

2026-11-18T12:50:04.802Z Sending message ID cba969df-4ff6-4fa5-acde-c852f0db0f97 to sequencer SEQ::sv1::1220f8379d7e...
2026-11-18T12:50:04.803Z 'PAR::bobValidator::1220230114fa...' sends request with id 'cba969df-4ff6-4fa5-acde-c852f0db0f97' of size 682 bytes with 1 envelopes to ALL=1. [globalSequencerSv1]
2026-11-18T12:50:04.804Z Request to send submission with id cba969df-4ff6-4fa5-acde-c852f0db0f97 with max sequencing time 2026-11-18T12:50:24.799714Z from PAR::bobValidator::1220230114fa... to Set(All). [globalSequencerSv1]
2026-11-18T12:50:04.984Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..52ed) with reference epoch 202 for batch received from local mempool containing 2 requests ... [globalSequencerSv1]
2026-11-18T12:51:04.930Z BFT ordering request at index 1 in output block (epochNumber=208, blockNumber=14830) with message ID cba969df-4ff6-4fa5-acde-c852f0db0f97 may change ordering topology: false
2026-11-18T12:51:04.938Z At block 14830, the submission request cba969df-4ff6-4fa5-acde-c852f0db0f97 at 2026-11-18T12:51:04.891492Z validated to: Discard
```
The batch was created by sv1 in epoch 202, sv1 was not a leader in epochs 203-207, and the batch was ordered in the
first block of epoch 208, 40 s after its max sequencing time. On splitwell the same party was sequenced at
12:50:04.784524 (effective 12:50:05.034524), so `installWalletForUser` saw the party on splitwell only and the
validator gave up at 12:50:29.790.

## 5. Why alice's party (test 5) never reached global: direct mempool rejection

```
grep -a 'participant=aliceParticipant' log/11564/cwin.jsonl | grep -a StoreBasedSynchronizerOutbox | grep -a -E 'Attempting to push|responded' | grep -a 'T12:50' | sed -E "$F" | cut -c1-200
grep -a -E '"level":"(WARN|ERROR)"' log/11564/cwin.jsonl | grep -a 'T12:50:[34]' | grep -a 'outbox flusher' | sed -E "$F" | cut -c1-330
```
```
2026-11-18T12:50:30.092Z Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:628bede70d1d...))
2026-11-18T12:50:30.095Z Attempting to push 1 topology transactions to Synchronizer 'splitwell': List(TxHash(SHA-256:0ea9af1ddd19...))
2026-11-18T12:50:30.101Z Synchronizer 'global' responded the following for the given topology transactions: List(Failed)
2026-11-18T12:50:31.695Z Synchronizer 'splitwell' responded the following for the given topology transactions: List(Accepted)
2026-11-18T12:50:40.105Z Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:628bede70d1d...))
2026-11-18T12:50:40.112Z Synchronizer 'global' responded the following for the given topology transactions: List(Failed)
2026-11-18T12:50:50.122Z Attempting to push 1 topology transactions to Synchronizer 'global': List(TxHash(SHA-256:628bede70d1d...))
2026-11-18T12:50:50.129Z Synchronizer 'global' responded the following for the given topology transactions: List(Failed)

2026-11-18T12:50:30.102Z synchronizer outbox flusher failed The synchronizer Synchronizer 'global' failed the following topology transactions: List(SignedTopologyTransaction( TopologyTransaction( PartyToParticipant(partyId = alice__wallet__user-fe6e8b4a__tc4::122041fb81d4..., participants = PAR::aliceValidator::122041fb8
2026-11-18T12:50:40.112Z synchronizer outbox flusher failed The synchronizer Synchronizer 'global' failed the following topology transactions: List(SignedTopologyTransaction( TopologyTransaction( PartyToParticipant(partyId = alice__wallet__user-fe6e8b4a__tc4::122041fb81d4..., participants = PAR::aliceValidator::122041fb8
```
Other clients of sv1 in the same period (first lines of the WARN list of the window):
```
2026-11-18T12:49:57.583Z Failed to send result to sequencer for request 2026-11-18T12:49:55.132369Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,53674f3b): Mempool received client request but this node is currently blacklis
2026-11-18T12:50:24.743Z Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,26174575): Mempool received client request but this node is currently blacklisted, rejectin
2026-11-18T12:50:47.359Z The operation 'request current time' was not successful. Retrying after 3.617s. Result: Outcome(Left(RequestRefused(SendAsyncErrorDirect(Mempool received client request but this node is currently blacklisted, rejecting)))). [c.d.c.t.TimeProofRequestSubmitterImpl:sequencer=globalSequencerSv1] WARN
```

## 6. What preceded the blacklisting: Canton JVM slowdown from 12:49:39, segment timeouts 12:49:47.96

```
awk -F'"' '{print substr($4,12,8)}' log/11564/cwin.jsonl | awk '$1>="12:49:30" && $1<="12:50:12"{c[$1]++} END{for(k in c)print k, c[k]}' | sort | awk '{printf "%s=%s ", substr($1,4), $2}'
```
```
49:30=6407 49:31=9132 49:32=7425 49:33=7452 49:34=6902 49:35=6069 49:36=8518 49:37=7761 49:38=7390 49:39=4348 49:40=4478 49:41=853 49:42=2972 49:43=644 49:44=316 49:45=993 49:46=1032 49:47=431 49:48=386 49:49=2776 49:50=5007 49:51=1915 49:52=1514 49:53=3952 49:54=6161 49:55=3626 49:56=1344 49:57=4718 49:58=5062 49:59=1628 50:00=24 50:01=1060 50:02=359 50:03=3753 50:04=4127 50:05=630 50:06=346 50:07=299 50:08=312 50:09=1675 50:10=922 50:11=260 50:12=260
```
The test JVM log keeps its rate (same command on win.jsonl: 740-840 lines/s from 12:49:44 to 12:50:25, minimum 592
at 12:50:00).

```
grep -a 'after reaching local timeout' log/11564/cwin.jsonl | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,110}).*sequencer=(globalSequencerSv[0-9]).*/\1 \3 \2/' | cut -c1-150 | head -8
```
```
2026-11-18T12:49:47.963Z globalSequencerSv3 Segment 3 starting at block number 14273 moving from view number 0 to 1 after reaching local timeout. New lead
2026-11-18T12:49:47.965Z globalSequencerSv4 Segment 3 starting at block number 14273 moving from view number 0 to 1 after reaching local timeout. New lead
2026-11-18T12:49:47.999Z globalSequencerSv2 Segment 0 starting at block number 14270 moving from view number 0 to 1 after reaching local timeout. New lead
2026-11-18T12:49:48.003Z globalSequencerSv1 Segment 0 starting at block number 14270 moving from view number 0 to 1 after reaching local timeout. New lead
2026-11-18T12:49:48.004Z globalSequencerSv3 Segment 0 starting at block number 14270 moving from view number 0 to 1 after reaching local timeout. New lead
2026-11-18T12:49:48.004Z globalSequencerSv4 Segment 0 starting at block number 14270 moving from view number 0 to 1 after reaching local timeout. New lead
2026-11-18T12:49:48.009Z globalSequencerSv2 Segment 1 starting at block number 14271 moving from view number 0 to 1 after reaching local timeout. New lead
2026-11-18T12:50:09.515Z globalSequencerSv4 Segment 2 starting at block number 14352 moving from view number 0 to 1 after reaching local timeout. New lead
```
Segment 0 of epoch 201 is sv2's and segment 3 is sv1's (epoch 201 leaders List(sv2, sv3, sv4, sv1)); sv2 is
blacklisted in epoch 202, sv1 from epoch 203 after further timeouts at 12:50:09 to 12:50:16 in epoch 202.

Not the 11313 trigger (vetting DAR uploads): `grep -a -c -E 'UploadDar' log/11564/cwin.jsonl` prints `0`.
Only this suite is running: the previous suite's last test ended 12:46:23.735 and the next suite
(WalletTransactionHistoryFrontendIntegrationTest) starts 12:50:52.312. At 12:49:40.572 the test opens the wallet UI
in Firefox (`Running clue: (act) Browse to wallet UI at port 3000`).

## 7. Blacklisting is enabled in wall-clock environments

```
git grep -n 'disableBftBlacklisting' ec059037fa -- apps
```
```
ec059037fa:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala:704:  def disableBftBlacklisting(): ConfigTransform =
ec059037fa:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala:569:      .addConfigTransform((_, conf) => ConfigTransforms.disableBftBlacklisting()(conf))
```
Line 569 is inside `withSimTime`; origin/main is at ec059037fa as of the triage, same state.

## Verdict

Duplicate of 11255 (open, rautenrieth-da), family B, wall-clock blacklisting shape with an unchanged ordering
topology; second hit of this shape after 11395 (closed as a duplicate of 11255). Matched: non-empty blacklist on
`New epoch` lines with `size = 4` and an unchanged activationTime, 105 `currently blacklisted, rejecting` mempool
rejections on globalSequencerSv1, both failing commands traced to sv1 (one batch held until epoch 208 and
discarded after max sequencing time, one push rejected by the mempool). Not matched: the 11395 host-stall line
(`Supervising futures itself takes longer`), zero in both JVMs.

Flake. The test is not at fault: `onboardWalletUser` is plain setup. Fix location: test environment config
(apply `ConfigTransforms.disableBftBlacklisting()` to wall-clock environments too, the fix already named on 11395),
or Canton (a blacklisted sequencer refuses or holds client submissions while clients stay connected to it only). No
fix branch: the change affects every wall-clock suite and belongs to the open parent.

Not verified: what slowed the Canton JVM from 12:49:39 (no GC or CPU data in the artifact; no FutureSupervisor
warning; no DAR uploads; the browser page load at 12:49:40 is a coincidence in time only). Whether validator
onboarding should wait for the party on the global synchronizer before `installWalletForUser` (option noted on
11160) was not evaluated. No sbt or Canton run.
