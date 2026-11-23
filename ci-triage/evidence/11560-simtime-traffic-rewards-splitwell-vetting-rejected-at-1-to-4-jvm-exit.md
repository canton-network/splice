# 11560 - simtime (0): TrafficBasedRewardsSvAppTimeBased setup, splitwellValidator VettedPackages broadcast rejected at the 1 -> 4 BFT step, providerSplitwellBackend JVM exit (run 44273112018)

DUPLICATE of 11255 (family B, rejected-send shape; same end state as 11408 and 11413). Post-merge run on main
e74e9bf844, job `simtime (0)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. No test report: the test JVM exited
during the environment setup of the first suite (TrafficBasedRewardsSvAppTimeBasedIntegrationTest). The global BFT
ordering topology went from 1 to 4 sequencers at 09:20:22.362 with one authenticated P2P node; 256 ms later sv1's
sequencer rejected splitwellParticipant's VettedPackages topology broadcast on global (`P2P connectivity is not ready
(authenticated = 1 < dissemination quorum = 2)`). The send is not retried, the outbox flush never closes,
splitwellValidator stays in `Set up app instance splitwell` (waiting for its DARs to be vetted), never creates the
user splitwell_provider, and providerSplitwellBackend runs out of `Wait for user` retries at 09:24:26 and exits the JVM.

The same run's `simtime (4)` is 11559 (same family, MED::sv4 add). The mapping of 11560 to `simtime (0)` is the one
given with the triage request and the issue's `Job:` line.

- Run: https://github.com/canton-network/splice/actions/runs/44273112018, main e74e9bf844 ("Disable base64 intrisics
  (#8563)"), job 134694800934 `ci / scala_test_sim_time / simtime (0)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`git show e74e9bf844:nix/canton-sources.json | grep -m1 version`).
- Component: Canton BFT ordering (topology activation regardless of P2P authentication); test-side mitigation is
  splice#8363 (open, not merged as of 2026-11-18).

Paths below are relative to `log/11560/` (job log `job.log`, artifact `logs-simtime-0/`).

## 1. Classification: no report, JVM exit

```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-200
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' job.log | grep -a -E 'still running after 7 minutes|providerSplitwellBackend app initialization|Caused by|Attempt 1 failed' | cut -c1-260
```
```
##[error]Error: failed to run script step (id afe100d0-e330-11f1-8540-15cb0f32716a): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.

[info] *** Test still running after 7 minutes, 29 seconds: suite name: TrafficBasedRewardsSvAppTimeBasedIntegrationTest, test name: Enable, disable of dryRunVersion/mintingVersion take effect at round closure. 
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
java.lang.RuntimeException: providerSplitwellBackend app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 4ad7b04f7abe11a69738d7cf68e0c0d2 with tid 4ad7b04f7abe11a69738d7cf68e0c0d2
Attempt 1 failed with exit code 1, retrying
```

No `Tests:` line, no `FAILED ***`, `Check logs for errors` skipped.

## 2. Suite timeline

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite|Running setup|Creating environment failed|Aborted test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat logs-simtime-0/canton_network_test.clog.gz | tail -1 | cut -c1-60
```
```
2026-11-18T09:16:45.191Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-18T09:16:45.232Z Starting 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure'...",
2026-11-18T09:17:31.874Z Starting creating environment: Running setup",

{"@timestamp":"2026-11-18T09:24:26.072Z","message":"Request 
```

The first suite never leaves `Running setup`; the log ends 2 ms after the exit below.

## 3. Which node did not initialize, and the exit

14 apps complete; splitwellValidator stays in `Set up app instance splitwell` from 09:20:18.478.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E 'app initialization: ' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,200}).*/\1 \2/' > init.txt
grep -c 'Initialization complete' init.txt
grep -E 'splitwellValidator|providerSplitwell' init.txt | tail -4 | cut -c1-200
```
```
14
2026-11-18T09:20:18.478Z splitwellValidator app initialization: Set up app instance splitwell started
2026-11-18T09:24:26.070Z providerSplitwellBackend app initialization: Wait for user failed
2026-11-18T09:24:26.070Z providerSplitwellBackend app initialization: Initialize app failed
2026-11-18T09:24:26.070Z providerSplitwellBackend app initialization: Initialization failed
```

The exit stack's tid on the participant: the user does not exist.

```
C=logs-simtime-0/canton-simtime.clog.gz
zcat $C | grep -a 4ad7b04f7abe11a69738d7cf68e0c0d2 | grep -a AuthInterceptor | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/' | cut -c1-300
```
```
2026-11-18T09:24:26.068Z PERMISSION_DENIED(7,4ad7b04f): Could not resolve is_deactivated status for user 'splitwell_provider-8bae37b8' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-8bae37b8)' [c.d.c.a.AuthInterceptor:participant=splitwellParticipant] WARN
```

What splitwellValidator waits on until the end: its DARs being vetted on global.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -E "The operation '[^']+' failed with a retryable error" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/"logger_name".*//' | cut -c1-330 | sed -n '$p'
```
```
2026-11-18T09:24:25.596Z The operation 'check established dars List(4c380e09c99b2c5cb938f6cce2a92c03f8941ca6f7af7df7c7370b2d421346da) are vetted on global-domain::122042499db5... from None' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: VettedPackages(\n  participantId = PAR::splitwellValidator::122051e6f3fd...,\n  packa
```

## 4. Family B confirming grep: the 1 -> 4 step and the rejected send

globalSequencerSv1 starts the first size-4 epoch at 09:20:22.362 (no blacklisting) while only itself is
authenticated; the only `Rejecting submission request by` of the run comes 256 ms later, for splitwellValidator's
participant. Zero `currently blacklisted` and zero `Failed to send result` rule out the lost-verdict shape.

```
F='s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a -E 'New epoch [0-9]+ has started with leaders' | grep -a 'sequencer=globalSequencerSv1' | grep -a -v 'size = 1,' | sed -E "$F" | sed -E 's/(New epoch [0-9]+ has started).*(size = [0-9]+).*(strongQuorum = [0-9]+).*/\1 \2 \3/' | cut -c1-120 | head -2
zcat $C | grep -a -E 'New epoch [0-9]+ has started with leaders' | grep -a 'sequencer=globalSequencerSv1' | grep -a -v 'size = 1,' | head -1 | grep -o 'blacklisted nodes = List([^)]*)'
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'is currently below (weak|strong) quorum' | sed -E "$F" | cut -c1-160 | head -3
zcat $C | grep -a 'Rejecting submission request by' | grep -a 'P2P connectivity' | sed -E "$F" | sed -E 's/ with SequencerDeliverError.*"trace-id":"([0-9a-f]+)".*/ tid=\1/' | cut -c1-170
zcat $C | grep -a -c 'currently blacklisted'; zcat $C | grep -a -c 'Failed to send result'
```
```
2026-11-18T09:20:22.362Z New epoch 13 has started size = 4 strongQuorum = 3
2026-11-18T09:20:39.196Z New epoch 14 has started size = 4 strongQuorum = 3

blacklisted nodes = List()

2026-11-18T09:20:22.347Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-18T09:20:29.449Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-18T09:20:29.480Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed unti

2026-11-18T09:20:22.618Z Rejecting submission request by Some(PAR::splitwellValidator::122051e6f3fd...) tid=0b96b5cd396f83d3c5d684ccdd844e1f

0
0
```

## 5. What was rejected, and that it is not retried

The trace id is splitwellParticipant's topology outbox pushing its VettedPackages transaction (1a8b8caf0eac) to
global, with max sequencing time 00:00:20 on a sim clock at about 00:00:00.19. The gRPC connection gives up without
a retry. The message id never appears again; the hash only reappears when the validator re-proposes the same
vetting state (09:22:16, 09:24:21) and the outbox answers `Another flush cycle is currently ongoing`.

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a 0b96b5cd396f83d3c5d684ccdd844e1f | sed -E "$F" | cut -c1-300 | grep -E 'Attempting to push|sends request|max sequencing time|MempoolModule|Rejecting submission request by|Retry has not been configured'
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a '1a8b8caf0eac' | grep -a 'Authorization details' | sed -E "$F" | cut -c1-80 | head -1
zcat $C | grep -a 8f3d47f6-26eb-4784-9ac1-b3da022f4183 | awk -F'"' '$4>"2026-11-18T09:20:23"' | wc -l
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a 'Another flush cycle is currently ongoing' | sed -E "$F" | cut -c1-200
```
```
2026-11-18T09:20:22.576Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:1a8b8caf0eac...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::122042499db5::37-0] DEBUG
2026-11-18T09:20:22.583Z 'PAR::splitwellValidator::122051e6f3fd...' sends request with id '8f3d47f6-26eb-4784-9ac1-b3da022f4183' of size 4438 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-18T09:20:22.584Z Request to send submission with id 8f3d47f6-26eb-4784-9ac1-b3da022f4183 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::122051e6f3fd... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::122042499db5::37-0] DEBUG
2026-11-18T09:20:22.587Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::122042499db5::37-0] INFO
2026-11-18T09:20:22.618Z Rejecting submission request by Some(PAR::splitwellValidator::122051e6f3fd...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-18T09:20:22.644Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=splitwellParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=DefaultSequencer-0] DEBUG

2026-11-18T09:20:22.573Z Authorization details for VettedPackages=TxHash(SHA-256:1a8b8caf0eac...)\n  Req

0

2026-11-18T09:22:16.073Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::122042499db5::37-0] DEBUG
2026-11-18T09:24:21.065Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::122042499db5::37-0] DEBUG
```

Not the breaker shape: `zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -c SUBMISSION_ALREADY_IN_FLIGHT` prints 0.

## 6. Verdict

- Duplicate of 11255 ("Timeouts due to changing bft ordering topology", open, assignee rautenrieth-da), rejected-send
  shape, VettedPackages variant of 11408 and 11413.
- Flake; cause in Canton BFT ordering at the 1 -> 4 topology step under simulated time. The test-side mitigation
  splice#8363 ("Onboard SV sequencers one at a time in tests") is open. No fix branch from this triage.
- Second failed simtime shard of this family in run 44273112018 (11559 is `simtime (4)`).
- Proposed, not run: close 11560 as a duplicate of 11255.
