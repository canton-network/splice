# 11690 - simtime (2): MED::sv4 MediatorSynchronizerState push rejected at the 1 -> 4 switch, sv4Validator exits (run 44987759917)

DUPLICATE of 11255 (family B, rejected-send shape, setup step). Scheduled workflow "Integration tests against mainnet
Daml version", main a0e7406501, job simtime (2), Canton 3.7.4. In the `Running setup` step of
TimeBasedTestNetPreviewIntegrationTest (second suite of the shard) the global ordering topology goes from 1 to 4
nodes at 05:30:14.440; 8 ms later sv1's sequencer rejects sv2Participant's push of the serial-4
MediatorSynchronizerState proposal that adds MED::sv4 (8e46a81a2664) with `P2P connectivity is not ready
(authenticated = 1 < dissemination quorum = 2)`. The send is not retried, the outbox flush cycles stay open, the sv4
SV app waits on `Mediator has been granted unlimited traffic` until the end, sv4_validator_user is never created, and
sv4Validator exits the JVM at 05:34:52.952 (`Wait for user failed`, `PERMISSION_DENIED` = `UserNotFound`), so the job
has no ScalaTest report (family H) and checkErrors is skipped. Same shape as 11400, 11410, 11540, 11559. Not caused
by #8632 (the run's commit touches only SvOnboardingAddlIntegrationTest; 11687 and 11688 hit the same sha).

- Run: https://github.com/canton-network/splice/actions/runs/44987759917, main a0e7406501 ("Deflake the onboarding
  snapshot resume step in SvOnboardingAddlIntegrationTest (#8632)"), job 137102500483
  `build / scala_test_sim_time / simtime (2)`, 2026-11-22 05:19:35-05:35:31 UTC. The run's other non-green job,
  137102494130 `docker-compose (1)` (cancelled), is not part of this ref.
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at a0e7406501).
- Component: Canton BFT ordering (topology switch activates before the newcomers are P2P-authenticated) under
  simulated time.

## 1. Job: JVM exit in TimeBasedTestNetPreviewIntegrationTest, no report

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/137102500483/logs > log/11690/job.log
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11690/job.log > log/11690/job.clean.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|Run completed|##\[error\]' log/11690/job.clean.log | sort -u
sed -n '13415,13428p' log/11690/job.clean.log | grep -v '^\s*at ' | cut -c1-260
```
```
##[error]Error: failed to run script step (id 3c0239b0-e635-11f1-b4d7-df82183e7e2c): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** Test still running after 7 minutes, 59 seconds: suite name: TimeBasedTestNetPreviewIntegrationTest, test name: TestNet initializes correctly. 
sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
java.lang.RuntimeException: sv4Validator app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
	... 8 more
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 6b62441b89f3c8260362ef46f408d0e5 with tid 6b62441b89f3c8260362ef46f408d0e5
```
No `*** FAILED ***`, no `Tests:` line: evidence loss by JVM exit. The step `Check logs for errors` is skipped.

## 2. Suite timeline

Artifact `logs-simtime-2` (id 14740559981) in `log/11690/logs-simtime-2/`; `T=canton_network_test.clog.gz`,
`C=canton-simtime.clog.gz`.
```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Running setup" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170 | tail -20
zcat $T | grep -a -c 'has been reset'
```
```
2026-11-22T05:26:50.135Z Starting test suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'...",
2026-11-22T05:26:50.209Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
2026-11-22T05:26:50.216Z Starting 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly'...",
2026-11-22T05:27:35.975Z Starting creating environment: Running setup",
0
```
The first suite ran no test (0.07 s; its tests are tagged out on the mainnet Daml version). The hit is in the
environment setup of the second suite. The last line of the file is at 05:34:52.952 (the exit).

## 3. Ordering topology 1 -> 4 at 05:30:14.440, no blacklisting

```
zcat $C | grep -a -E 'New epoch [0-9]+ has started' | grep -a -v 'size = 1,' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/ordering topology = OrderingTopology\(\\n  activationTime = ([^,]+),\\n  size = ([0-9]+).*"logger_name":"([^"]*)".*/activationTime=\1 size=\2 [\3]/' | cut -c1-330 | head -4
```
```
2026-11-22T05:30:14.440Z New epoch 13 has started with leaders = List(SEQ::sv2::..790e, SEQ::sv3::..12fb, SEQ::sv4::..fd8a, SEQ::sv1::..88c6)and blacklisted nodes = List(); activationTime=1970-01-01T00:00:00.190001Z size=4 [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv1/psid=global-domain::1220bf8b047f::36
2026-11-22T05:30:22.197Z New epoch 13 has started with leaders = List(SEQ::sv2::..790e, SEQ::sv3::..12fb, SEQ::sv4::..fd8a, SEQ::sv1::..88c6)and blacklisted nodes = List(); activationTime=1970-01-01T00:00:00.190001Z size=4 [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv4/psid=global-domain::1220bf8b047f::36
2026-11-22T05:30:24.259Z New epoch 13 has started with leaders = List(SEQ::sv2::..790e, SEQ::sv3::..12fb, SEQ::sv4::..fd8a, SEQ::sv1::..88c6)and blacklisted nodes = List(); activationTime=1970-01-01T00:00:00.190001Z size=4 [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv3/psid=global-domain::1220bf8b047f::36
2026-11-22T05:30:26.016Z New epoch 13 has started with leaders = List(SEQ::sv2::..790e, SEQ::sv3::..12fb, SEQ::sv4::..fd8a, SEQ::sv1::..88c6)and blacklisted nodes = List(); activationTime=1970-01-01T00:00:00.190001Z size=4 [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv2/psid=global-domain::1220bf8b047f::36
```
No size-2 or size-3 epoch on the global synchronizer: sv1 switches from 1 node straight to 4, and the three newcomers
enter the epoch 7.8 to 11.6 s later.

## 4. Confirming grep of family B: rejections right after the switch, zero blacklisting, zero lost verdicts

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a 'Rejecting submission request by' | sed -E "$F" | cut -c1-420
for p in 'P2P connectivity is not ready' 'currently blacklisted' 'Failed to send result' 'Another flush cycle is currently ongoing' 'Retry has not been configured for GrpcServiceUnavailable'; do printf '%s: ' "$p"; zcat $C | grep -a -c "$p"; done
```
```
2026-11-22T05:30:14.448Z Rejecting submission request by Some(PAR::sv2::1220946a76ca...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-22T05:30:15.290Z Rejecting submission request by Some(PAR::splitwellValidator::1220ceb6b675...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-22T05:30:27.115Z Rejecting submission request by Some(MED::sv2::12209771508d...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:50ddd25420aa... is already marked as delivered)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv2] INFO
P2P connectivity is not ready: 36
currently blacklisted: 0
Failed to send result: 0
Another flush cycle is currently ongoing: 26
Retry has not been configured for GrpcServiceUnavailable: 26
```
sv1 rejects PAR::sv2 8 ms and PAR::splitwellValidator 850 ms after the switch. (The MED::sv2 line at 05:30:27 is an
ordinary aggregation dedup, unrelated.)

## 5. The rejected PAR::sv2 send is the MediatorSynchronizerState proposal adding MED::sv4

```
zcat $C | grep -a 'Attempting to push' | grep -a -E 'T05:30:1[34]' | grep -a -E 'participant=sv[0-9]' | grep -a 8e46a81a2664 | sed -E "$F" | cut -c1-260
zcat $C | grep -a 'participant=sv2Participant' | grep -a -E 'T05:30:14\.4' | grep -a -E 'UNAVAILABLE|giving up' | sed -E "$F" | cut -c1-330 | head -2
zcat $C | grep -a '8e46a81a2664' | grep -a -v 'Attempting to push' | head -400 | grep -a -m1 'MED::' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*/ [\1]/' | cut -c1-760
```
```
2026-11-22T05:30:13.881Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8e46a81a2664...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220bf8b047f::36-0] DEBUG
2026-11-22T05:30:14.072Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8e46a81a2664...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220bf8b047f::36-0] DEBUG
2026-11-22T05:30:14.244Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8e46a81a2664...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220bf8b047f::36-0] DEBUG
2026-11-22T05:30:14.413Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:8e46a81a2664...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220bf8b047f::36-0] DEBUG
2026-11-22T05:30:14.451Z Request failed for server-DefaultSequencer-0. Is the server running? Did you configure the server address as 0.0.0.0? Are you using the right TLS settings? (details logged as DEBUG)\n  GrpcServiceUnavailable: UNAVAILABLE/SEQUENCER_UNAVAILABLE(1,88c100eb): P2P connectivity is not ready (authenticated = 1 
2026-11-22T05:30:14.452Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=sv2Participant/psid=global-domain::1220bf8b047f::36-0/pool=main/connection=DefaultSequencer-0] DEBUG
2026-11-22T05:30:13.808Z Adding StoredTopologyTransaction(\n  SignedTopologyTransaction(\n    TopologyTransaction(\n      MediatorSynchronizerState(\n        synchronizerId = global-domain::1220bf8b047f...,\n        group = 0,\n        threshold = 2,\n        active = Seq(MED::sv1::12204b3f4548..., MED::sv2::12209771508d..., MED::sv3::1220442815be..., MED::sv4::1220f8c160cd...)\n      ),\n      serial = 4,\n      operation = Replace,\n      hash = SHA-256:8e46a81a2664...\n    ),\n    signatures = 1220eb3eb30d...,\n    proposal\n  ),\n  sequenced = 1970-01-01T00:00:00.188001Z,\n  validFrom = 1970-01-01T00:00:00.188001Z\n) [c.d.c.t.c.TopologyStateWriteThroughCache:participant=sv1Participant/psid=global-domain::1220bf8b047f::36-0/purpose=global-domain::1220bf8b047f::36-0]
```
The serial-4 proposal (mediators sv1-sv4) was pushed by sv4, sv1, sv3 and sv2 between 559 and 27 ms before the
switch; sv2's push, the last one, is answered with the rejection 35 ms after it was attempted and is not retried.
Not traced here: which of the three earlier pushes were sequenced (the proposal existed with one signature at
05:30:13.808) and whether any of them sat in a batch that was never ordered, as in the mixed hits 11409 and 11543.

## 6. sv4 waits on mediator traffic until the exit; sv4_validator_user never created

```
for s in sv2 sv3 sv4; do zcat $T | grep -a 'Mediator has been granted unlimited traffic' | grep -a "SV=$s" | tail -1 | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//' | cut -c1-200; done
zcat $C | grep -a 6b62441b89f3c8260362ef46f408d0e5 | sed -E "$F" | cut -c1-300 | head -1
zcat $T | grep -a -E 'Initialization failed|Wait for user failed' | sed -E "$F" | cut -c1-260
```
```
2026-11-22T05:30:26.365Z Success: Mediator has been granted unlimited traffic",
2026-11-22T05:30:24.156Z Success: Mediator has been granted unlimited traffic",
2026-11-22T05:34:50.066Z The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an exception. Retrying after a number of 78 failures, and after 4230 milliseconds. ",
2026-11-22T05:34:52.950Z PERMISSION_DENIED(7,6b62441b): Could not resolve is_deactivated status for user 'sv4_validator_user-8e214f92' and identity_provider_id 'Default' due to 'UserNotFound(sv4_validator_user-8e214f92)'",[c.d.c.a.AuthInterceptor:participant=sv4Participant] WARN
2026-11-22T05:34:52.952Z sv4Validator app initialization: Wait for user failed",[o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=8e214f92/validator=sv4Validator] INFO
2026-11-22T05:34:52.952Z sv4Validator app initialization: Initialization failed",[o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=8e214f92/validator=sv4Validator] ERROR
```
sv2 and sv3 got their mediator traffic; sv4 waits from 05:30:17.871 (first `Waiting until Mediator has been granted
unlimited traffic`) to the exit. The tid of the console stack (section 1) is the `UserNotFound(sv4_validator_user)`
GetUser on sv4Participant.

## Verdict

Duplicate of 11255 "Timeouts due to changing bft ordering topology" (open, assignee rautenrieth-da, not itself a
duplicate), family B, rejected-send shape at the 1 -> 4 step, JVM-exit end state (as 11400, 11410, 11540, 11559).
Flake; cause in Canton BFT (new ordering topology active before the newcomers are P2P-authenticated, rejected sends
not retried under a sim clock that never reaches max sequencing time). The test-side mitigation is splice#8363
("Onboard SV sequencers one at a time in tests"), still open on 2026-11-22. No fix branch: nothing small to add
beside #8363. Suggested action for the user: close 11690 as a duplicate of 11255 (not run).

Not verified: the fate of the three pushes before the switch (section 5); the Canton source (runtime log lines only).
