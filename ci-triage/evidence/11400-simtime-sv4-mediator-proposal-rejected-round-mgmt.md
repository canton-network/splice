# 11400 - simtime (3): sv4 MediatorSynchronizerState push rejected at the 1 -> 4 switch, sv4Validator exits (run 43295852356)

DUPLICATE of 11255 (rejected-send shape, inside a test body). Main 1fb5a7382f, job simtime (3), Canton 3.7.3. In
SvTimeBasedRoundMgmtIntegrationTest "round management" the global ordering topology goes to 4 nodes at 20:42:51.459;
sv1's sequencer rejects the next outbox pushes of sv3 (0.35 s later) and sv4 (0.54 s later), both the
MediatorSynchronizerState proposal, with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum =
2)`. The SV participants' outbox flushes then keep logging `Another flush cycle is currently ongoing`,
sv4_validator_user is never created, and sv4Validator exits the JVM at 20:47:47 (`Wait for user failed`,
`PERMISSION_DENIED`), so the job has no ScalaTest report (family H). Not caused by #8421 (the run's commit changes
only ValidatorLicenseMetadataTimeBasedIntegrationTest).

- Run: https://github.com/canton-network/splice/actions/runs/43295852356, main 1fb5a7382f ("Claim the faucet coupons
  first, wait 60s in ValidatorLicenseMetadata test (#8421)"), job 131454349982 `ci / scala_test_sim_time / simtime
  (3)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at 1fb5a7382f, unchanged since c9c875cfc5).
- Component: Canton BFT ordering (P2P connectivity below dissemination quorum right after a topology change) under
  simulated time.

## 1. Job: JVM exit in SvTimeBasedRoundMgmtIntegrationTest, no report

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11400/job.txt | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //'
sed -n 13630,13650p log/11400/job.txt | sed -E 's/^[^Z]*Z //' | grep -v '^\s*at ' | cut -c1-200
```
```
##[error]Error: failed to run script step (id 1fcb6950-ded9-11f1-9da7-05774a53779f): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
[info] *** Test still running after 7 minutes, 26 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management.
sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
java.lang.RuntimeException: sv4Validator app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 756556992bfc2267523a73ceea3bd835 with tid 756556992bfc2267523a73ceea3bd835
Attempt 1 failed with exit code 1, retrying
```

## 2. The 1 -> 4 switch and the rejected pushes

Artifact `logs-simtime-3` in `log/11400/logs-simtime-3/`.
```
zcat canton-simtime.clog.gz | grep -a -E "New epoch [0-9]+ .*size = |Rejecting submission request by .*P2P connectivity" | grep -a 'T20:4[0-3]' | <first line per distinct message>
```
```
2026-11-12T20:41:33.874Z New epoch 8 has started with leaders = List(SEQ::sv1::...) ... size = 1
2026-11-12T20:42:51.459Z New epoch 14 has started with leaders = List(SEQ::sv3::..., SEQ::sv4::..., SEQ::sv1::..., SEQ::sv2::...
2026-11-12T20:42:51.806Z Rejecting submission request by Some(PAR::sv3::122063ffce85...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting) | GrpcSequencerService:sequencer=globalSequencerSv1
2026-11-12T20:42:51.997Z Rejecting submission request by Some(PAR::sv4::12206dfbbe31...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting) | GrpcSequencerService:sequencer=globalSequencerSv1
```
The rejected requests are the outbox pushes (1 envelope to ALL):
```
zcat canton-simtime.clog.gz | grep -a 00980891-d7fd-4c28-b010-ef9117a7b6ff | head -5
zcat canton-simtime.clog.gz | grep -a -E 'T20:42:51.986Z' | grep -a 'participant=sv4Participant' | grep -a 'Attempting to push'
zcat canton-simtime.clog.gz | grep -a 'participant=sv4Participant' | grep -a '4799e52a56cb' | grep -a -oE 'TopologyTransaction\(\\n *[A-Za-z]+\(' | sort -u
```
```
2026-11-12T20:42:51.991Z Sending message ID 00980891-d7fd-4c28-b010-ef9117a7b6ff to sequencer SEQ::sv1::12209ea37433... | participant=sv4Participant
2026-11-12T20:42:51.993Z 'PAR::sv4::12206dfbbe31...' sends request with id '00980891-d7fd-4c28-b010-ef9117a7b6ff' of size 909 bytes with 1 envelopes to ALL=1. | sequencer=globalSequencerSv1
{"@timestamp":"2026-11-12T20:42:51.986Z","message":"Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:4799e52a56cb...))", ... QueueBasedSynchronizerOutbox:participant=sv4Participant
TopologyTransaction(\n        MediatorSynchronizerState(
TopologyTransaction(\n      MediatorSynchronizerState(
```

## 3. Stuck outboxes, no lost-verdict shape, sv4 user never created

```
zcat canton-simtime.clog.gz | grep -a 'T20:4[2-7]' | grep -a 'Another flush cycle' | sed -E 's/.*participant=([^/]*).*/\1/' | sort | uniq -c
zcat canton-simtime.clog.gz | grep -a -c 'Failed to send result'
zcat canton_network_test.clog.gz | grep -a -E "sv4_validator_user" | <first line per distinct message> | head
```
```
      2 sv1Participant
      7 sv2Participant
      8 sv3Participant
      6 sv4Participant
0
2026-11-12T20:40:12.286Z Waiting for user sv4_validator_user-aa486638 | ValidatorApp:.../validator=sv4Validator
2026-11-12T20:40:12.460Z The operation 'Get user sv4_validator_user-aa486638' failed with a retryable error (full stack trace omitted): PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 17fb5098461b3d03719e49b11e44...
```
No `Got 'user sv4_validator_user-aa486638'` line until the exit at 20:47:47.

## Verdict

Duplicate of 11255, rejected-send shape (MediatorSynchronizerState push of a joining SV rejected within 0.6 s of the
1 -> 4 switch, zero `Failed to send result`), here inside a test body with a JVM exit instead of a setup failure.
