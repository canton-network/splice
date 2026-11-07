# 11255 - simtime (0): sv4Validator gives up waiting for its user; topology outboxes of sv1-sv4 stuck after the BFT 1 -> 4 switch (run 42248944117)

New (family B variant, simtime-specific). main 6f7a5f83bc (after #8234 moved all tests to DA BFT), job `simtime (0)`,
canton 3.7.2. The first suite of the shard, TrafficBasedRewardsSvAppTimeBasedIntegrationTest, never finished its
environment setup: sv4 did not finish joining the DSO (sv2 and sv3 did, at 16:26:10 and 16:26:14), so the sv4 SV app never created
`sv4_validator_user`, and sv4Validator gave up after 7.5 min (`Gave up getting 'user sv4_validator_user-...'`),
logged `Initialization failed, so exiting` and took the sbt JVM down (no test report). Cause: at 16:25:50.759 the
global ordering topology switched from 1 to 4 nodes (epoch 19) while the newcomers were not yet P2P-authenticated.
The MediatorSynchronizerState proposal adding MED::sv4 (tx fd952213751b) was broadcast by sv1, sv2 and sv3 within
the next second and all three sends were rejected synchronously by globalSequencerSv1 with
`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`; the participants log `may still be
possibly sequenced so we are ignoring the error`. sv4Participant's previous broadcast (632aaa22, accepted at
16:25:50.442, batched in epoch 18) was never ordered. None of the four sends got a receipt. Their max sequencing
time is `1970-01-01T00:00:20Z` on the simulated clock, which stood at `00:00:01.739` at 16:29:24 (no test advanced
it during setup), so each participant's topology outbox kept its flush cycle open: no participant broadcast a
topology transaction after 16:25:51.658, and the SV apps' re-proposals at 16:27:50 only got `Another flush cycle is
currently ongoing`. Blacklisting is not involved (epoch 19 `blacklisted nodes = List()`); sv4's sequencer was
blacklisted from epoch 20 (16:26:08) only after the outboxes were already stuck, so splice#8356 would not have
prevented it. Same mechanism as the simtime (0) failure on splice#8262 (sv2 mediator broadcast rejected, never
resent).

- Run: https://github.com/canton-network/splice/actions/runs/42248944117, main 6f7a5f83bc ("Benchmarking JSON serialization in getUpdateById (#8351)"), job 127999437319 `simtime (0)`, attempt 1.
- Runtime canton: 3.7.2 (`nix/canton-sources.json` at 6f7a5f83bc).
- Component: Canton (BFT ordering topology switch + participant topology outbox under simulated time); test setup exposes it.
- Artifact: `log/11255/logs-simtime-0/` (`canton_network_test.clog.gz`, `canton-simtime.clog.gz`).

## 1. Failure in the job log

sv4Validator init fails, the JVM exits, no test report.

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11255/job.log | grep -a -E 'Initialization failed, so exiting|Caused by|Test still running after 7 minutes, 58' | cut -c1-260
```

```
[info] *** Test still running after 7 minutes, 58 seconds: suite name: TrafficBasedRewardsSvAppTimeBasedIntegrationTest, test name: Enable, disable of dryRunVersion/mintingVersion take effect at round closure. 
sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 8e3861021d0f576c967cfc3121009c61 with tid 8e3861021d0f576c967cfc3121009c61
```

## 2. Suite timeline

The failing suite is the first and only one started on this shard's Canton instance.

```
git show 6f7a5f83bc:nix/canton-sources.json | grep version; zcat log/11255/logs-simtime-0/canton_network_test.clog.gz | jq -r 'select(.message|test("^Starting test suite|^Test (succeeded|failed)")) | "\(.["@timestamp"]) | \(.message|.[0:120])"'
```

```
  "version": "3.7.2",
2026-11-06T16:21:04.175Z | Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
```

## 3. sv4Validator waits for its user, gives up at 16:29:27

The user is created by the sv4 SV app only after sv4 joined the DSO.

```
zcat log/11255/logs-simtime-0/canton_network_test.clog.gz | grep -a 'validator=sv4Validator"' | jq -r 'select(.message|test("Waiting for user|Gave up|Wait for user (started|failed)|Initialization failed")) | "\(.["@timestamp"]) \(.level) | \(.message|.[0:120])"'
```

```
2026-11-06T16:21:48.062Z DEBUG | sv4Validator app initialization: Wait for user started
2026-11-06T16:21:48.062Z INFO | Waiting for user sv4_validator_user-d433f1ac
2026-11-06T16:29:27.924Z ERROR | Gave up getting 'user sv4_validator_user-d433f1ac'
2026-11-06T16:29:27.924Z INFO | sv4Validator app initialization: Wait for user failed
2026-11-06T16:29:27.925Z ERROR | sv4Validator app initialization: Initialization failed
```

## 4. sv2 and sv3 joined, sv4 never finished joining

App init completions of the SV apps; sv4 has no 'joining Dso ... finished'.

```
zcat log/11255/logs-simtime-0/canton_network_test.clog.gz | jq -r 'select(.message|test("^sv[1-4] app initialization: (JoiningNodeInitializer joining Dso with key|Initialize app) (started|finished)")) | "\(.["@timestamp"]) | \(.message|.[0:120])"'
```

```
2026-11-06T16:21:22.063Z | sv1 app initialization: Initialize app started
2026-11-06T16:21:34.345Z | sv2 app initialization: Initialize app started
2026-11-06T16:21:40.903Z | sv4 app initialization: Initialize app started
2026-11-06T16:21:50.998Z | sv3 app initialization: Initialize app started
2026-11-06T16:22:40.396Z | sv3 app initialization: JoiningNodeInitializer joining Dso with key started
2026-11-06T16:22:40.396Z | sv4 app initialization: JoiningNodeInitializer joining Dso with key started
2026-11-06T16:22:40.396Z | sv2 app initialization: JoiningNodeInitializer joining Dso with key started
2026-11-06T16:23:31.078Z | sv1 app initialization: Initialize app finished after PT2M9.015028427S
2026-11-06T16:26:10.194Z | sv2 app initialization: JoiningNodeInitializer joining Dso with key finished after PT3M29.797386749S
2026-11-06T16:26:11.869Z | sv2 app initialization: Initialize app finished after PT4M37.524483974S
2026-11-06T16:26:14.007Z | sv3 app initialization: JoiningNodeInitializer joining Dso with key finished after PT3M33.610551483S
2026-11-06T16:26:15.581Z | sv3 app initialization: Initialize app finished after PT4M24.58323575S
```

## 5. sv4 waits for its mediator to be added

The MediatorSynchronizerState proposal adding MED::sv4 is not observed for 2 min, then re-proposed.

```
zcat log/11255/logs-simtime-0/canton_network_test.clog.gz | grep -a 'SV=sv4"' | jq -r 'select(.message|test("Add mediator MED::sv4")) | select(.level!="DEBUG") | "\(.["@timestamp"]) | \(.message|gsub("\n";" ")|.[0:150])"' | awk '{k=substr($0,26,60); c[k]++; if (c[k]<=1) print}'
```

```
2026-11-06T16:25:50.931Z | Ensuring that Add mediator MED::sv4::12205c30098d...
2026-11-06T16:25:50.951Z | No proposal found for Add mediator MED::sv4::12205c30098d..., submitting new one.
2026-11-06T16:25:50.993Z | Submitted proposal MediatorSynchronizerState(   synchronizerId = global-domain::122032b9fc1d...,   group = 0,   threshold = 2,   active = Seq(MED::sv1
2026-11-06T16:25:50.999Z | The operation 'check established Add mediator MED::sv4::12205c30098d...' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION
2026-11-06T16:25:51.200Z | Now retrying operation 'check established Add mediator MED::sv4::12205c30098d...'. 
2026-11-06T16:27:50.493Z | The operation 'Add mediator MED::sv4::12205c30098d...' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not
2026-11-06T16:27:50.493Z | The operation 'Add mediator MED::sv4::12205c30098d...' has failed with an exception. New kind of error: transient error (request infinite retries). Re
2026-11-06T16:27:50.694Z | Now retrying operation 'Add mediator MED::sv4::12205c30098d...'. 
```

## 6. Ordering topology switches 1 -> 4 at 16:25:50.759 (epoch 19)

globalSequencerSv1 epochs around the switch; sv4 blacklisted only from epoch 20.

```
zcat log/11255/logs-simtime-0/canton-simtime.clog.gz | grep -a 'New epoch' | jq -r 'select(.["@timestamp"] > "2026-11-06T16:25:40" and .["@timestamp"] < "2026-11-06T16:26:50") | select(.logger_name|test("globalSequencerSv1")) | "\(.["@timestamp"]) | \(.message|gsub("\n";" ")|gsub("::1220[0-9a-f]{60}";"")|.[0:150])"'
```

```
2026-11-06T16:25:43.371Z | New epoch 18 has started with leaders = List(SEQ::sv1a06f)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activationTime = 197
2026-11-06T16:25:50.759Z | New epoch 19 has started with leaders = List(SEQ::sv43725, SEQ::sv1a06f, SEQ::sv26085, SEQ::sv3dc59)and blacklisted nodes = List(); ordering topology 
2026-11-06T16:26:08.093Z | New epoch 20 has started with leaders = List(SEQ::sv3dc59, SEQ::sv1a06f, SEQ::sv26085)and blacklisted nodes = List(SEQ::sv43725); ordering topology = 
2026-11-06T16:26:19.439Z | New epoch 21 has started with leaders = List(SEQ::sv1a06f, SEQ::sv26085, SEQ::sv3dc59)and blacklisted nodes = List(SEQ::sv43725); ordering topology = 
2026-11-06T16:26:33.087Z | New epoch 22 has started with leaders = List(SEQ::sv26085, SEQ::sv3dc59, SEQ::sv1a06f)and blacklisted nodes = List(SEQ::sv43725); ordering topology = 
2026-11-06T16:26:48.014Z | New epoch 23 has started with leaders = List(SEQ::sv43725, SEQ::sv1a06f, SEQ::sv26085, SEQ::sv3dc59)and blacklisted nodes = List(); ordering topology 
```

## 7. sv1, sv2, sv3 broadcasts of fd952213751b rejected; sv4's earlier send accepted but never ordered

All rejections of the shard, then the max sequencing time of these sends.

```
zcat log/11255/logs-simtime-0/canton-simtime.clog.gz | jq -r 'select(.message|test("Rejecting submission request by|returned an error however|Request to send submission with id (632aaa22|f2b5d037|90722ac6|ea380669)")) | "\(.["@timestamp"]) \(.logger_name|split(":")[1]|.[0:36]) | \(.message|gsub("\n";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:200])"'
```

```
2026-11-06T16:25:50.443Z sequencer=globalSequencerSv1/psid=gl | Request to send submission with id 632aaa22-b4f2-4c78-97f4-f6f6c7451250 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv4::122012e13e7f... to Set(All). 
2026-11-06T16:25:50.939Z sequencer=globalSequencerSv1/psid=gl | Request to send submission with id 90722ac6-7c15-4212-b77d-c96380e209df with max sequencing time 1970-01-01T00:00:20Z from PAR::sv1::12202b9dfdf2... to Set(All). 
2026-11-06T16:25:50.961Z sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv1::12202b9dfdf2...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quor
2026-11-06T16:25:50.997Z participant=sv1Participant/psid=glob | Send [90722ac6-7c15-4212-b77d-c96380e209df] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-DefaultSequencer-0. Is the 
2026-11-06T16:25:51.323Z sequencer=globalSequencerSv1/psid=gl | Request to send submission with id f2b5d037-b061-479c-bfca-cf5edccdf49c with max sequencing time 1970-01-01T00:00:20Z from PAR::sv2::1220b4a19a88... to Set(All). 
2026-11-06T16:25:51.325Z sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv2::1220b4a19a88...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quor
2026-11-06T16:25:51.336Z participant=sv2Participant/psid=glob | Send [f2b5d037-b061-479c-bfca-cf5edccdf49c] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-DefaultSequencer-0. Is the 
2026-11-06T16:25:51.666Z sequencer=globalSequencerSv1/psid=gl | Request to send submission with id ea380669-8b8c-4dae-a96c-d56bcb10b234 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv3::122012d62ebd... to Set(All). 
2026-11-06T16:25:51.668Z sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv3::122012d62ebd...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quor
2026-11-06T16:25:51.677Z participant=sv3Participant/psid=glob | Send [ea380669-8b8c-4dae-a96c-d56bcb10b234] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-DefaultSequencer-0. Is the 
2026-11-06T16:26:12.476Z sequencer=globalSequencerSv3 | Rejecting submission request by Some(MED::sv3::1220ac1960e9...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-
2026-11-06T16:26:12.525Z sequencer=globalSequencerSv3 | Rejecting submission request by Some(MED::sv3::1220ac1960e9...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-
```

## 8. None of the four sends is ever sequenced

No receipt or deliver for any of the four message ids.

```
for id in 632aaa22-b4f2-4c78-97f4-f6f6c7451250 90722ac6-7c15-4212-b77d-c96380e209df f2b5d037-b061-479c-bfca-cf5edccdf49c ea380669-8b8c-4dae-a96c-d56bcb10b234; do echo "$id receipts=$(zcat log/11255/logs-simtime-0/canton-simtime.clog.gz | grep -a "$id" | grep -a -c -E 'Received the receipt|message id = Some')"; done; zcat log/11255/logs-simtime-0/canton-simtime.clog.gz | grep -a '122018a4953a60ccf237' | jq -r '"\(.["@timestamp"]) | \(.message|gsub("\n";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:150])"' | tail -2
```

```
632aaa22-b4f2-4c78-97f4-f6f6c7451250 receipts=0
90722ac6-7c15-4212-b77d-c96380e209df receipts=0
f2b5d037-b061-479c-bfca-cf5edccdf49c receipts=0
ea380669-8b8c-4dae-a96c-d56bcb10b234 receipts=0
2026-11-06T16:25:50.762Z | Availability.LocalDissemination.LocalBatchesStoredSigned: local batches List(Traced(BatchId(..76d0))(TraceContext(trace id=e8cbb19e218b101cbd5c5e0b1a8
2026-11-06T16:25:50.762Z | Availability.LocalDissemination.LocalBatchesStoredSigned: disseminating BatchId(..76d0) to SEQ::sv3::..dc59, SEQ::sv2::..6085, SEQ::sv4::..3725
```

## 9. The simulated clock stays far below the max sequencing time

Last sequenced event on sv1Participant before the failure: sim time 00:00:01.739 (max sequencing time 00:00:20).

```
zcat log/11255/logs-simtime-0/canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-06T16:29:20" and .["@timestamp"] < "2026-11-06T16:29:28") | select(.logger_name|test("participant=sv1Participant")) | select(.message|test("Processing event at sc=")) | "\(.["@timestamp"]) | \(.message|.[0:80])"' | tail -1
```

```
2026-11-06T16:29:24.603Z | Processing event at sc=288, ts=1970-01-01T00:00:01.739Z, with contents=SignedPro
```

## 10. Outboxes stuck: no topology broadcast after 16:25:51.658

Last broadcasts of sv1-sv4 participants, then the re-proposals that find the flush cycle still open.

```
zcat log/11255/logs-simtime-0/canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-06T16:25:50") | select(.logger_name|test("participant=sv[1-4]Participant")) | select(.message|test("Broadcasting topology|completeCycle|responded the following|Another flush cycle")) | "\(.["@timestamp"]) \(.logger_name|split(":")[1]|.[12:26]) | \(.message|gsub("\n";" ")|.[0:120])"'
```

```
2026-11-06T16:25:50.359Z sv1Participant | Broadcasting topology transaction: List(TxHash(SHA-256:09ff38d4b751...))
2026-11-06T16:25:50.433Z sv4Participant | Broadcasting topology transaction: List(TxHash(SHA-256:09ff38d4b751...))
2026-11-06T16:25:50.696Z sv1Participant | Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:09ff38d
2026-11-06T16:25:50.700Z sv1Participant | completeCycle List(Traced(TxHash(SHA-256:09ff38d4b751...), tid:e85936e1890b499f623b60c2fe9c91a6))
2026-11-06T16:25:50.926Z sv1Participant | Broadcasting topology transaction: List(TxHash(SHA-256:fd952213751b...))
2026-11-06T16:25:50.989Z sv4Participant | Another flush cycle is currently ongoing
2026-11-06T16:25:51.314Z sv2Participant | Broadcasting topology transaction: List(TxHash(SHA-256:fd952213751b...))
2026-11-06T16:25:51.658Z sv3Participant | Broadcasting topology transaction: List(TxHash(SHA-256:fd952213751b...))
2026-11-06T16:27:50.740Z sv4Participant | Another flush cycle is currently ongoing
2026-11-06T16:27:50.859Z sv3Participant | Another flush cycle is currently ongoing
2026-11-06T16:27:51.126Z sv2Participant | Another flush cycle is currently ongoing
2026-11-06T16:27:53.445Z sv1Participant | Another flush cycle is currently ongoing
```

## Verdict

New; BFT onboarding family B (umbrella 10165), simtime variant: a topology broadcast that is rejected
(`P2P connectivity is not ready`) or dropped while the ordering topology grows from 1 to 4 nodes is never resent,
and the participant's topology outbox waits for it until its max sequencing time, which on the simulated clock never
arrives during environment setup. On a wall clock that deadline passes in real time, so the outbox would recover
(inference, not verified against the 3.7.2 source). Not blacklisting: splice#8356 does not prevent it. In
terms of splice#8361 this is the once-per-instance sv2-sv4 onboarding episode, but the rejection kind is the
connectivity check, not the blacklist.

Fix: Canton (DA BFT team), either the outbox/send tracker should retry a synchronously rejected send, or the ordering
layer should not activate a topology whose new members are not authenticated (family B). Splice mitigation for
tests: onboard sv2-sv4 sequencers one at a time, or wait for P2P authentication before the next onboarding step.

Not verified: why sv4's accepted batch (632aaa22, epoch 18) was not ordered after the switch (re-disseminated to
sv2-sv4 at 16:25:50.762, nothing after); the Canton code path that keeps the outbox waiting.
