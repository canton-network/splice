# 11686 - simtime (0): TrafficBasedRewardsSvAppTimeBased setup, splitwellValidator VettedPackages broadcast rejected at the 1 -> 4 BFT step, providerSplitwellBackend JVM exit (run 44762471901)

DUPLICATE of 11255 (family B, rejected-send shape; same end state as 11408, 11413, 11560 and 11565). Post-merge run on
main d3f9006367, job `simtime (0)`, Canton 3.7.4. No test report: the test JVM exited during the environment setup of
the first suite (TrafficBasedRewardsSvAppTimeBasedIntegrationTest). The global BFT ordering topology went from 1 to 4
sequencers at 15:34:10.993 with one authenticated P2P node; 3.25 s later (15:34:14.241) sv1's sequencer rejected
splitwellParticipant's VettedPackages topology broadcast on global (`P2P connectivity is not ready (authenticated = 1 <
dissemination quorum = 2)`). The send is not retried, the outbox flush never closes, splitwellValidator stays in
`Set up app instance splitwell` waiting for its DARs to be vetted on global, never creates the user
splitwell_provider, and providerSplitwellBackend runs out of `Wait for user` retries at 15:38:13.899 and exits the JVM.

The mapping of 11686 to `simtime (0)` is the one given with the triage request and the issue's `Job:` line. The run's
only other non-successful job is `docker-compose (1)` (cancelled, 136378748189); it has no ref in this request and was
not looked at.

- Run: https://github.com/canton-network/splice/actions/runs/44762471901, main d3f9006367 ("move perf dashboars from
  splice-stores to testing (#8622)"), job 136378743626 `ci / scala_test_sim_time / simtime (0)`.
- Runtime canton: 3.7.4 (`git show d3f9006367:nix/canton-sources.json | grep -m1 version`).
- Component: Canton BFT ordering (topology activation regardless of P2P authentication); test-side mitigation is
  splice#8363 (open, not merged as of 2026-11-22).

Paths below are relative to `log/11686/` (job log `job.log`, artifact `logs-simtime-0/`).

## 1. Classification: no report, JVM exit

```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-200
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' job.log | grep -a -E 'still running after 7 minutes|providerSplitwellBackend app initialization|Caused by|Attempt 1 failed' | cut -c1-260
```
```
##[error]Error: failed to run script step (id 34a1f3c0-e4f7-11f1-933d-0733ad275c79): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.

[info] *** Test still running after 7 minutes, 29 seconds: suite name: TrafficBasedRewardsSvAppTimeBasedIntegrationTest, test name: Enable, disable of dryRunVersion/mintingVersion take effect at round closure. 
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
java.lang.RuntimeException: providerSplitwellBackend app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 3088d99626d46bfcafd64c7773ba92fb with tid 3088d99626d46bfcafd64c7773ba92fb
Attempt 1 failed with exit code 1, retrying
```

No `Tests:` line, no `FAILED ***`, no checkErrors report.

## 2. Suite timeline

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite|Running setup|Creating environment failed|Aborted test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat logs-simtime-0/canton_network_test.clog.gz | tail -1 | cut -c1-60
```
```
2026-11-20T15:30:21.829Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-20T15:30:21.867Z Starting 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure'...",
2026-11-20T15:31:08.592Z Starting creating environment: Running setup",
{"@timestamp":"2026-11-20T15:38:13.899Z","message":"provider
```

The first suite never leaves `Running setup`; the log ends with the exit below.

## 3. Which node did not initialize, and the exit

14 apps complete; splitwellValidator stays in `Set up app instance splitwell` from 15:34:10.291 (its own `Vet
packages` step finished at 15:34:09.477, before the switch).

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E 'app initialization: ' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,200}).*/\1 \2/' > init.txt
grep -c 'Initialization complete' init.txt
grep -E 'splitwellValidator|providerSplitwell' init.txt | tail -4 | cut -c1-200
```
```
14
2026-11-20T15:34:10.291Z splitwellValidator app initialization: Set up app instance splitwell started
2026-11-20T15:38:13.898Z providerSplitwellBackend app initialization: Wait for user failed
2026-11-20T15:38:13.899Z providerSplitwellBackend app initialization: Initialize app failed
2026-11-20T15:38:13.899Z providerSplitwellBackend app initialization: Initialization failed
```

The exit stack's tid on the participant: the user does not exist.

```
C=logs-simtime-0/canton-simtime.clog.gz
zcat $C | grep -a 3088d99626d46bfcafd64c7773ba92fb | grep -a AuthInterceptor | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/' | cut -c1-300
```
```
2026-11-20T15:38:13.896Z PERMISSION_DENIED(7,3088d996): Could not resolve is_deactivated status for user 'splitwell_provider-9ad17df2' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-9ad17df2)' [c.d.c.a.AuthInterceptor:participant=splitwellParticipant] WARN
```

What splitwellValidator waits on until the end: its DARs being vetted on global.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -E "The operation '[^']+' failed with a retryable error" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/"logger_name".*//' | sed -E 's/List\([0-9a-f, ]+\)/List(..)/' | cut -c1-330 | sed -n '$p'
```
```
2026-11-20T15:38:13.472Z The operation 'check established dars List(..) are vetted on global-domain::12204f1080ed... from None' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: VettedPackages(\n  participantId = PAR::splitwellValidator::1220a0e9ffcb...,\n  pa
```

## 4. Family B confirming grep: the 1 -> 4 step and the rejected send

globalSequencerSv1 starts the first size-4 epoch at 15:34:10.993 (epoch 15, no blacklisting) while only itself is
authenticated (still 1 at 15:34:16.563); the only `Rejecting submission request by` with `P2P connectivity` of the run
comes 3.25 s after the switch, for splitwellValidator's participant (a late rejection as in 11413, 3.08 s). Zero
`currently blacklisted` and zero `Failed to send result` rule out the lost-verdict shape.

```
zcat $C | grep -a -E 'New epoch [0-9]+ has started' | grep -a 'globalSequencerSv1' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(New epoch [0-9]+).*(size = [0-9]+).*/\1 \2 \3/' | awk '$1<"2026-11-20T15:34:35"' | tail -3
F='s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a -E 'New epoch [0-9]+ has started with leaders' | grep -a 'sequencer=globalSequencerSv1' | grep -a -v 'size = 1,' | head -1 | grep -o 'blacklisted nodes = List([^)]*)'
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'is currently below (weak|strong) quorum' | sed -E "$F" | cut -c1-160 | head -3
zcat $C | grep -a 'Rejecting submission request by' | grep -a 'P2P connectivity' | sed -E "$F" | sed -E 's/ with SequencerDeliverError.*"trace-id":"([0-9a-f]+)".*/ tid=\1/' | cut -c1-170
zcat $C | grep -a -c 'currently blacklisted'; zcat $C | grep -a -c 'Failed to send result'
```
```
2026-11-20T15:34:02.974Z New epoch 14 size = 1
2026-11-20T15:34:10.993Z New epoch 15 size = 4
2026-11-20T15:34:32.146Z New epoch 16 size = 4

blacklisted nodes = List()

2026-11-20T15:34:10.977Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-20T15:34:16.562Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-20T15:34:16.563Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able

2026-11-20T15:34:14.241Z Rejecting submission request by Some(PAR::splitwellValidator::1220a0e9ffcb...) tid=ea2af244ae4c043a50b5c8c93ba2e9d6

0
0
```

## 5. What was rejected, and that it is not retried

The trace id is splitwellParticipant's topology outbox pushing its VettedPackages transaction (750821a413ee, serial 3)
to global, with max sequencing time 00:00:20 on the sim clock. The gRPC connection gives up without a retry. The
message id never appears again after the connection restart; later re-proposals get `Another flush cycle is currently
ongoing`.

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a ea2af244ae4c043a50b5c8c93ba2e9d6 | sed -E "$F" | cut -c1-300 | grep -E 'Attempting to push|sends request|max sequencing time|MempoolModule|Rejecting submission request by|Retry has not been configured'
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a '750821a413ee' | grep -a 'Authorization details' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-80 | head -1
zcat $C | grep -a 7e0aa216-2983-4e91-ad7f-15eff03f1c84 | awk -F'"' '$4>"2026-11-20T15:34:15"' | wc -l
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a 'Another flush cycle is currently ongoing' | sed -E "$F" | cut -c1-200
```
```
2026-11-20T15:34:14.188Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:750821a413ee...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::12204f1080ed::37-0] DEBUG
2026-11-20T15:34:14.196Z 'PAR::splitwellValidator::1220a0e9ffcb...' sends request with id '7e0aa216-2983-4e91-ad7f-15eff03f1c84' of size 4586 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-20T15:34:14.197Z Request to send submission with id 7e0aa216-2983-4e91-ad7f-15eff03f1c84 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::1220a0e9ffcb... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::12204f1080ed::37-0]
2026-11-20T15:34:14.235Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12204f1080ed::37-0] INFO
2026-11-20T15:34:14.241Z Rejecting submission request by Some(PAR::splitwellValidator::1220a0e9ffcb...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:seque
2026-11-20T15:34:14.245Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=splitwellParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=DefaultSequencer-0] DEBUG

2026-11-20T15:34:14.184Z Authorization details for VettedPackages=TxHash(SHA-256

0

2026-11-20T15:36:11.065Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::12204f1080ed::37-0] DEBUG
2026-11-20T15:38:10.871Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::12204f1080ed::37-0] DEBUG
```

Not the breaker shape: `zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -c SUBMISSION_ALREADY_IN_FLIGHT` prints 0.

## 6. Verdict

- Duplicate of 11255 ("Timeouts due to changing bft ordering topology", open, assignee rautenrieth-da, not itself
  closed as a duplicate), rejected-send shape, VettedPackages variant of 11408, 11413, 11560 and 11565.
- Flake; cause in Canton BFT ordering at the 1 -> 4 topology step under simulated time. The test-side mitigation
  splice#8363 ("Onboard SV sequencers one at a time in tests") is open. No fix branch from this triage.
- Not verified: nothing was run locally; the cancelled `docker-compose (1)` job of the same run was not examined.
- Proposed, not run: close 11686 as a duplicate of 11255.
