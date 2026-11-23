# 11565 - simtime (0): TrafficBasedRewardsSvAppTimeBased setup, splitwellValidator VettedPackages broadcast rejected at the 1 -> 4 BFT step, providerSplitwellBackend JVM exit (run 44322981802)

DUPLICATE of 11255 (family B, rejected-send shape; same end state as 11408, 11413 and 11560). Post-merge run on main
465b29cc47, job `simtime (0)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. No test report: the test JVM exited
during the environment setup of the first suite (TrafficBasedRewardsSvAppTimeBasedIntegrationTest). The global BFT
ordering topology went from 1 to 4 sequencers at 14:33:11.774 with one authenticated P2P node; 288 ms later sv1's
sequencer rejected splitwellParticipant's VettedPackages topology broadcast on global (`P2P connectivity is not ready
(authenticated = 1 < dissemination quorum = 2)`). The send is not retried, the outbox flush never closes,
splitwellValidator stays in `Vet packages`, never creates the user splitwell_provider, and providerSplitwellBackend
runs out of `Wait for user` retries at 14:37:08 and exits the JVM.

The mapping of 11565 to `simtime (0)` is the one given with the triage request and the issue's `Job:` line; it was the
only failed job of the run when triaged (the other jobs were still running).

- Run: https://github.com/canton-network/splice/actions/runs/44322981802, main 465b29cc47 ("AGENT.md: refer to new
  DAML_STYLE.md and add instructions for TODO notes (#8551)"), job 134867152260 `ci / scala_test_sim_time / simtime (0)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`git show 465b29cc47:nix/canton-sources.json | grep -m1 version`).
- Component: Canton BFT ordering (topology activation regardless of P2P authentication); test-side mitigation is
  splice#8363 (open, not merged as of 2026-11-18).

Paths below are relative to `log/11565/` (job log `job.log`, artifact `logs-simtime-0/`).

## 1. Classification: no report, JVM exit

```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-200
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' job.log | grep -a -E 'still running after 7 minutes|providerSplitwellBackend app initialization|Caused by|Attempt 1 failed' | cut -c1-260
```
```
##[error]Error: failed to run script step (id 678dd200-e35c-11f1-987d-6169fbd6a415): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.

[info] *** Test still running after 7 minutes, 29 seconds: suite name: TrafficBasedRewardsSvAppTimeBasedIntegrationTest, test name: Enable, disable of dryRunVersion/mintingVersion take effect at round closure. 
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
java.lang.RuntimeException: providerSplitwellBackend app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 48c8a7d71e3585df79d228f3bced7e82 with tid 48c8a7d71e3585df79d228f3bced7e82
Attempt 1 failed with exit code 1, retrying
```

No `Tests:` line, no `FAILED ***`, `Check logs for errors` skipped.

## 2. Suite timeline

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished test suite|Running setup|Creating environment failed|Aborted test suite" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat logs-simtime-0/canton_network_test.clog.gz | tail -1 | cut -c1-60
```
```
2026-11-18T14:29:35.501Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-18T14:29:35.524Z Starting 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure'...",
2026-11-18T14:30:20.487Z Starting creating environment: Running setup",
{"@timestamp":"2026-11-18T14:37:08.100Z","message":"Sending 
```

The first suite never leaves `Running setup`; the log ends 2 ms after the exit below.

## 3. Which node did not initialize, and the exit

14 apps complete; splitwellValidator stays in `Vet packages` from 14:32:39.140.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -E 'app initialization: ' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]{0,200}).*/\1 \2/' > init.txt
grep -c 'Initialization complete' init.txt
grep -E 'splitwellValidator|providerSplitwell' init.txt | tail -4 | cut -c1-200
```
```
14
2026-11-18T14:32:39.140Z splitwellValidator app initialization: Vet packages started
2026-11-18T14:37:08.097Z providerSplitwellBackend app initialization: Wait for user failed
2026-11-18T14:37:08.097Z providerSplitwellBackend app initialization: Initialize app failed
2026-11-18T14:37:08.098Z providerSplitwellBackend app initialization: Initialization failed
```

The exit stack's tid on the participant: the user does not exist.

```
C=logs-simtime-0/canton-simtime.clog.gz
zcat $C | grep -a 48c8a7d71e3585df79d228f3bced7e82 | grep -a AuthInterceptor | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/' | cut -c1-300
```
```
2026-11-18T14:37:08.095Z PERMISSION_DENIED(7,48c8a7d7): Could not resolve is_deactivated status for user 'splitwell_provider-147cf5e8' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-147cf5e8)' [c.d.c.a.AuthInterceptor:participant=splitwellParticipant] WARN
```

What splitwellValidator waits on until the end: its DARs being vetted on global.

```
zcat logs-simtime-0/canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -E "The operation '[^']+' failed with a retryable error" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/"logger_name".*//' | sed -E 's/List\([0-9a-f, ]+\)/List(..)/' | cut -c1-330 | sed -n '$p'
```
```
2026-11-18T14:37:05.719Z The operation 'check established dars List(..) are vetted on global-domain::1220b72cfe80... from None' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: VettedPackages(\n  participantId = PAR::splitwellValidator::122070dfbe04...,\n  pa
```

## 4. Family B confirming grep: the 1 -> 4 step and the rejected send

globalSequencerSv1 starts the first size-4 epoch at 14:33:11.774 (no blacklisting) while only itself is
authenticated; the only `Rejecting submission request by` of the run comes 288 ms later, for splitwellValidator's
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
2026-11-18T14:33:11.774Z New epoch 13 has started size = 4 strongQuorum = 3
2026-11-18T14:33:33.217Z New epoch 14 has started size = 4 strongQuorum = 3

blacklisted nodes = List()

2026-11-18T14:33:11.762Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-18T14:33:16.069Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able
2026-11-18T14:33:16.097Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed unti

2026-11-18T14:33:12.062Z Rejecting submission request by Some(PAR::splitwellValidator::122070dfbe04...) tid=bfd01498e9b9570c9aa2cf23f712bc9f

0
0
```

## 5. What was rejected, and that it is not retried

The trace id is splitwellParticipant's topology outbox pushing its VettedPackages transaction (ad6cce181186) to
global, with max sequencing time 00:00:20 on the sim clock. The gRPC connection gives up without a retry. The message
id never appears again; later re-proposals get `Another flush cycle is currently ongoing`.

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $C | grep -a bfd01498e9b9570c9aa2cf23f712bc9f | sed -E "$F" | cut -c1-300 | grep -E 'Attempting to push|sends request|max sequencing time|MempoolModule|Rejecting submission request by|Retry has not been configured'
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a 'ad6cce181186' | grep -a 'Authorization details' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-80 | head -1
zcat $C | grep -a e7b50031-575c-4893-8d9c-a9212d577a15 | awk -F'"' '$4>"2026-11-18T14:33:13"' | wc -l
zcat $C | grep -a 'participant=splitwellParticipant' | grep -a 'Another flush cycle is currently ongoing' | sed -E "$F" | cut -c1-200
```
```
2026-11-18T14:33:12.044Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:ad6cce181186...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220b72cfe80::37-0] DEBUG
2026-11-18T14:33:12.052Z 'PAR::splitwellValidator::122070dfbe04...' sends request with id 'e7b50031-575c-4893-8d9c-a9212d577a15' of size 4402 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-18T14:33:12.053Z Request to send submission with id e7b50031-575c-4893-8d9c-a9212d577a15 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::122070dfbe04... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220b72cfe80::37-0]
2026-11-18T14:33:12.057Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::1220b72cfe80::37-0] INFO
2026-11-18T14:33:12.062Z Rejecting submission request by Some(PAR::splitwellValidator::122070dfbe04...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:seque
2026-11-18T14:33:12.067Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=splitwellParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=DefaultSequencer-0] DEBUG

2026-11-18T14:33:12.040Z Authorization details for VettedPackages=TxHash(SHA-256

0

2026-11-18T14:35:08.973Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220b72cfe80::37-0] DEBUG
2026-11-18T14:37:03.630Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220b72cfe80::37-0] DEBUG
```

Not the breaker shape: `zcat logs-simtime-0/canton_network_test.clog.gz | grep -a -c SUBMISSION_ALREADY_IN_FLIGHT` prints 0.

## 6. Verdict

- Duplicate of 11255 ("Timeouts due to changing bft ordering topology", open, assignee rautenrieth-da, not itself
  closed as a duplicate), rejected-send shape, VettedPackages variant of 11408, 11413 and 11560.
- Flake; cause in Canton BFT ordering at the 1 -> 4 topology step under simulated time. The test-side mitigation
  splice#8363 ("Onboard SV sequencers one at a time in tests") is open. No fix branch from this triage.
- Not verified: nothing was run locally; the other jobs of the run were still in progress at triage time.
- Proposed, not run: close 11565 as a duplicate of 11255.
