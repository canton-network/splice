# 11687 - simtime (0): TrafficBasedRewardsSvAppTimeBased setup, splitwellValidator VettedPackages broadcast rejected at the 1 -> 4 BFT step, providerSplitwellBackend JVM exit (run 44814088068)

DUPLICATE of 11255 (family B, rejected-send shape; same end state as 11408, 11413, 11560, 11565 and 11686). Post-merge
run on main a0e7406501, job `simtime (0)`, Canton 3.7.4. No test report: the test JVM exited during the environment
setup of the first suite (TrafficBasedRewardsSvAppTimeBasedIntegrationTest). The global BFT ordering topology went from
1 to 4 sequencers at 20:59:44.736 with one authenticated P2P node (still 1 at 20:59:50.899); 4.78 s after the switch
(20:59:49.514) sv1's sequencer rejected splitwellParticipant's VettedPackages topology broadcast on global (`P2P
connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`). The send is not retried, the outbox flush
never closes, splitwellValidator stays in `Set up app instance splitwell` waiting for its DARs to be vetted on global,
never creates the user splitwell_provider, and providerSplitwellBackend runs out of `Wait for user` retries at
21:03:37.087 and exits the JVM.

The mapping of 11687 to `simtime (0)` is the one given with the triage request and the issue's `Job:` line. The run's
other failed job is `simtime (3)` (136558231592); it has no ref in this request and was not looked at.

- Run: https://github.com/canton-network/splice/actions/runs/44814088068, main a0e7406501 ("Deflake the onboarding
  snapshot resume step in SvOnboardingAddlIntegrationTest (#8632)"), job 136558231666
  `ci / scala_test_sim_time / simtime (0)`.
- Runtime canton: 3.7.4 (`git show a0e7406501:nix/canton-sources.json | grep -m1 version`).
- Component: Canton BFT ordering (topology activation regardless of P2P authentication); test-side mitigation is
  splice#8363 (open, not merged as of 2026-11-22).

Paths below are relative to `log/11687/` (job log `job.log`, artifact `logs-simtime-0/`).

## 1. Classification: no report, JVM exit

```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-200
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' job.log | grep -a -E 'still running after 7 minutes, 59|providerSplitwellBackend app initialization|Caused by|Attempt 1 failed' | cut -c1-260
```
```
##[error]Error: failed to run script step (id 9a4e6b90-e524-11f1-86fd-43c740c5c593): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.

[info] *** Test still running after 7 minutes, 59 seconds: suite name: TrafficBasedRewardsSvAppTimeBasedIntegrationTest, test name: Enable, disable of dryRunVersion/mintingVersion take effect at round closure. 
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
java.lang.RuntimeException: providerSplitwellBackend app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 275e23bff5b4e7a5df85521d4c99c2a1 with tid 275e23bff5b4e7a5df85521d4c99c2a1
Attempt 1 failed with exit code 1, retrying
```

No `Tests:` line, no `FAILED ***`, no checkErrors report.

## 2. Suite timeline

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite|Running setup|Creating environment failed|Aborted test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat logs-simtime-0/canton_network_test.clog.gz | tail -2 | cut -c1-110
```
```
2026-11-20T20:55:25.279Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-20T20:55:25.312Z Starting 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure'...",
2026-11-20T20:56:12.219Z Starting creating environment: Running setup",
{"@timestamp":"2026-11-20T21:03:37.087Z","message":"providerSplitwellBackend app initialization: Initialization failed","l
{"@timestamp":"2026-11-20T21:03:37.088Z","message":"Running queued action: schedule_after_unless_shutdown","logger_name"
```

The first suite never leaves `Running setup`; the log ends at the exit below.

## 3. Which node did not initialize, and the exit

14 apps complete; splitwellValidator stays in `Set up app instance splitwell` from 20:59:43.434 (its own `Vet
packages` step finished at 20:59:42.552, 2.2 s before the switch).

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E 'app initialization: ' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,200}).*/\1 \2/' > init.txt
grep -c 'Initialization complete' init.txt
grep -E 'splitwellValidator.*Vet packages' init.txt | cut -c1-200
grep -E 'splitwellValidator|providerSplitwell' init.txt | tail -4 | cut -c1-200
```
```
14
2026-11-20T20:58:59.164Z splitwellValidator app initialization: Vet packages started
2026-11-20T20:59:42.552Z splitwellValidator app initialization: Vet packages finished after PT43.388079561S
2026-11-20T20:59:43.434Z splitwellValidator app initialization: Set up app instance splitwell started
2026-11-20T21:03:37.087Z providerSplitwellBackend app initialization: Wait for user failed
2026-11-20T21:03:37.087Z providerSplitwellBackend app initialization: Initialize app failed
2026-11-20T21:03:37.087Z providerSplitwellBackend app initialization: Initialization failed
```

The exit stack's tid on the participant: the user does not exist.

```
C=logs-simtime-0/canton-simtime.clog.gz
zcat $C | grep -a 275e23bff5b4e7a5df85521d4c99c2a1 | grep -a AuthInterceptor | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/' | cut -c1-300
```
```
2026-11-20T21:03:37.084Z PERMISSION_DENIED(7,275e23bf): Could not resolve is_deactivated status for user 'splitwell_provider-a672d76a' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-a672d76a)' [c.d.c.a.AuthInterceptor:participant=splitwellParticipant] WARN
```

What splitwellValidator waits on until the end: its DARs being vetted on global.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -E "The operation '[^']+' failed with a retryable error" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/"logger_name".*//' | sed -E 's/List\([0-9a-f, ]+\)/List(..)/' | cut -c1-330 | sed -n '$p'
```
```
2026-11-20T21:03:34.606Z The operation 'check established dars List(..) are vetted on global-domain::1220d9ff1a77... from None' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: VettedPackages(\n  participantId = PAR::splitwellValidator::122013aba64c...,\n  pa
```

## 4. Family B confirming grep: the 1 -> 4 step and the rejected send

globalSequencerSv1 starts the first size-4 epoch at 20:59:44.736 (epoch 16, no blacklisting) while only itself is
authenticated (still 1 at 20:59:50.899, 2 at 20:59:50.940); the only `Rejecting submission request by` with `P2P
connectivity` of the run comes 4.78 s after the switch, for splitwellValidator's participant (a late rejection as in
11413, 3.08 s, and 11686, 3.25 s). Zero `currently blacklisted` and zero `Failed to send result` rule out the
lost-verdict shape.

```
zcat $C | grep -a -E 'New epoch [0-9]+ has started' | grep -a 'globalSequencerSv1' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(New epoch [0-9]+).*(size = [0-9]+).*/\1 \2 \3/' | grep -B1 -m1 'size = 4'
F='s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a -E 'New epoch [0-9]+ has started with leaders' | grep -a 'sequencer=globalSequencerSv1' | grep -a -v 'size = 1,' | head -1 | grep -o 'blacklisted nodes = List([^)]*)'
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'is currently below (weak|strong) quorum' | sed -E "$F" | cut -c1-160 | sed -n '1,3p;$p'
zcat $C | grep -a 'Rejecting submission request by' | grep -a 'P2P connectivity' | sed -E "$F" | sed -E 's/ with SequencerDeliverError.*"trace-id":"([0-9a-f]+)".*/ tid=\1/' | cut -c1-170
zcat $C | grep -a -c 'currently blacklisted'; zcat $C | grep -a -c 'Failed to send result'
```
```
2026-11-20T20:59:36.518Z New epoch 15 size = 1
2026-11-20T20:59:44.736Z New epoch 16 size = 4

blacklisted nodes = List()

2026-11-20T20:59:44.714Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-20T20:59:50.788Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-20T20:59:50.899Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-20T20:59:50.940Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed unti

2026-11-20T20:59:49.514Z Rejecting submission request by Some(PAR::splitwellValidator::122013aba64c...) tid=420cc109a60d4eae89ea020c801495ea

0
0
```

## 5. What was rejected, and that it is not retried

The trace id is splitwellParticipant's topology outbox pushing its VettedPackages transaction (fd9fe34955ef) to
global, with max sequencing time 00:00:20 on the sim clock. The gRPC connection gives up without a retry; a later
re-proposal gets `Another flush cycle is currently ongoing`.

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a 420cc109a60d4eae89ea020c801495ea | sed -E "$F" | cut -c1-300 | grep -E 'Attempting to push|sends request|max sequencing time|MempoolModule|Rejecting submission request by|Retry has not been configured'
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a 'Another flush cycle is currently ongoing' | sed -E "$F" | cut -c1-200
```
```
2026-11-20T20:59:49.442Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:fd9fe34955ef...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220d9ff1a77::37-0] DEBUG
2026-11-20T20:59:49.449Z 'PAR::splitwellValidator::122013aba64c...' sends request with id '4e832427-f0d1-4b1d-93e5-0b561fd71067' of size 4589 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-20T20:59:49.451Z Request to send submission with id 4e832427-f0d1-4b1d-93e5-0b561fd71067 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::122013aba64c... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220d9ff1a77::37-0]
2026-11-20T20:59:49.482Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::1220d9ff1a77::37-0] INFO
2026-11-20T20:59:49.514Z Rejecting submission request by Some(PAR::splitwellValidator::122013aba64c...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:seque
2026-11-20T20:59:49.518Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=splitwellParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=DefaultSequencer-0] DEBUG

2026-11-20T21:01:48.266Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220d9ff1a77::37-0] DEBUG
```

Not the breaker shape: `zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -c SUBMISSION_ALREADY_IN_FLIGHT` prints 0.

## 6. Verdict

- Duplicate of 11255 ("Timeouts due to changing bft ordering topology", open, assignee rautenrieth-da, not itself
  closed as a duplicate), rejected-send shape, VettedPackages variant of 11408, 11413, 11560, 11565 and 11686.
- Flake; cause in Canton BFT ordering at the 1 -> 4 topology step under simulated time. The test-side mitigation
  splice#8363 ("Onboard SV sequencers one at a time in tests") is open. No fix branch from this triage.
- Not verified: nothing was run locally; the failed `simtime (3)` job of the same run was not examined.
- Proposed, not run: close 11687 as a duplicate of 11255.
