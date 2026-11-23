# 11684 - simtime (2) exits without a report in SvTimeBasedAmuletPrice (run 44729952801)

DUPLICATE of 11255 (family B, rejected-send shape, same exit path as 11407, 11421 and 11643). Post-merge main
ae237a8ac9 (#8617), job `ci / scala_test_sim_time / simtime (2)`, Canton 3.7.4. The first suite of the shard,
GovernanceLockTimeBasedIntegrationTest, passed all 8 tests. In the second suite, SvTimeBasedAmuletPriceIntegrationTest,
test `amulet price votes and metrics` (initDso), the BFT ordering topology of the global synchronizer grew 1 -> 4 at
12:06:47.939 (epoch 69, no blacklisting). The MediatorSynchronizerState proposal with MED::sv4 in the active set (tx
03b1e21d6235) was pushed by sv3 at 12:06:47.720, before the switch; the pushes of sv4, sv1 and sv2 at 12:06:47.948-.950
were rejected by globalSequencerSv1 24-26 ms after the switch (`P2P connectivity is not ready (authenticated = 1 <
dissemination quorum = 2)`) and not retried. sv2 and sv3 got their mediator traffic grants; sv4 waited on its grant
from 12:06:53.384 until shutdown. The sv4 init wait timed out at 12:12:32.549, the teardown's
ResetDecentralizedNamespace did not succeed within 1 minute (ERROR 12:13:45.682) and ResetTopologyStatePlugin calls
sys.exit(1) there, so sbt exited with no test report and checkErrors was skipped.

- Run: https://github.com/canton-network/splice/actions/runs/44729952801, main ae237a8ac9 ("fix how validator common
  IaC handles disableAuth after the introduction of participant admin API auth (#8617)"), job 136267208750
  `ci / scala_test_sim_time / simtime (2)`.
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at ae237a8ac9).
- Component: Canton BFT ordering (sequencer onboarding window under sim time); exit path is the test harness.

Artifact: `log/11684/logs-simtime-2/` (`gh run download 44729952801 --repo canton-network/splice -n logs-simtime-2`),
job log `log/11684/job.log` (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/136267208750/logs`).
In the commands `A=log/11684/logs-simtime-2` and `fmt` is the generic formatter from recipes section 4.

The same run also has a failed `simtime (1)` (job 136267208631) and a cancelled `docker-compose (1)`; neither is part
of this ref and neither was looked at.

## 1. Job log: no report, sbt exit after 8 min of the test

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11684/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|Test still running|Attempt 1 failed|Exceeded maximum retries|##\[error\]' | grep -v 'echo "' | tail -6 | cut -c1-200
```
```
[info] *** Test still running after 7 minutes, 45 seconds: suite name: SvTimeBasedAmuletPriceIntegrationTest, test name: amulet price votes and metrics. 
[info] *** Test still running after 8 minutes, 15 seconds: suite name: SvTimeBasedAmuletPriceIntegrationTest, test name: amulet price votes and metrics. 
Attempt 1 failed with exit code 1, retrying
Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
##[error]Error: failed to run script step (id 23d39120-e4d9-11f1-a8b6-577e50ca784a): Error: step failed with return code 1
##[error]Process completed with exit code 1.
```
No `FAILED ***`, no `Tests:` summary, no `contains problems`: evidence loss class (family H). The "custom container"
annotation that follows is the runner's wording for a failed step, not an infra fault.

## 2. Test log: suite timeline, sv4 init timeout, reset failure

```
zcat $A/canton_network_test.clog.gz | grep -a -E "Starting test suite|Starting '|Test (succeeded|failed): " | fmt | tail -3
zcat $A/canton_network_test.clog.gz | grep -a '"level":"ERROR"' | fmt
```
```
2026-11-20T12:05:19.866Z Test succeeded: 'GovernanceLockTimeBasedIntegrationTest/lock transfer accept context fails if a source LockedAmulet is gone'",
2026-11-20T12:05:20.645Z Starting test suite 'SvTimeBasedAmuletPriceIntegrationTest'...",
2026-11-20T12:05:20.646Z Starting 'SvTimeBasedAmuletPriceIntegrationTest/amulet price votes and metrics'...",
```
```
2026-11-20T12:12:32.549Z Timeout while waiting for initialization of sv4",[o.l.s.c.SvAppBackendReference:SvTimeBasedAmuletPriceIntegrationTest/config=a6fffc74/app=sv4] ERROR
2026-11-20T12:13:45.682Z Failed to reset decentralized namespace",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] ERROR
```
The second ERROR is the last line of the file; its stack is `Condition never became true within 1 minute` from
`ResetDecentralizedNamespace.resetTopologyState` (ResetDecentralizedNamespace.scala:155) under
`ResetTopologyStatePlugin.attemptToResetTopologyState` (ResetTopologyStatePlugin.scala:110), which exits the JVM.
All 8 GovernanceLockTimeBased tests have a `Test succeeded` line.

## 3. What sv4 waited on

```
zcat $A/canton_network_test.clog.gz | grep -a -E 'Waiting until Mediator has been granted|Mediator has been granted unlimited traffic"|Gave up waiting until Mediator' | grep -a -v 'Check whether' | grep -a 'T12:0[6-9]\|T12:1' | sed -E 's/"logger_name":"[^"]*(SV=sv[0-9])".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-140
```
```
2026-11-20T12:06:52.724Z Waiting until Mediator has been granted unlimited traffic",[SV=sv3]
2026-11-20T12:06:53.384Z Waiting until Mediator has been granted unlimited traffic",[SV=sv4]
2026-11-20T12:06:53.714Z Waiting until Mediator has been granted unlimited traffic",[SV=sv2]
2026-11-20T12:06:58.219Z Success: Mediator has been granted unlimited traffic",[SV=sv2]
2026-11-20T12:07:05.152Z Success: Mediator has been granted unlimited traffic",[SV=sv3]
2026-11-20T12:12:45.387Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[SV=sv4]
```

## 4. Confirming grep of family B: 1 -> 4 switch, then rejected pushes

Ordering topology of the global synchronizer on globalSequencerSv1:
```
zcat $A/canton-simtime.clog.gz | grep -a 'New epoch [0-9]* has started with leaders' | grep -a globalSequencerSv1 | grep -a 'T12:06:[34]' | fmt 9999 | sed -E 's/New epoch ([0-9]+) .*blacklisted nodes = (List\([^)]*\)).*size = ([0-9]+).*/epoch \1 blacklisted \2 size \3/'
```
```
2026-11-20T12:06:33.427Z epoch 67 blacklisted List() size 1
2026-11-20T12:06:40.921Z epoch 68 blacklisted List() size 1
2026-11-20T12:06:47.939Z epoch 69 blacklisted List() size 4
```
Pushes of the proposal and the rejections:
```
zcat $A/canton-simtime.clog.gz | grep -a 03b1e21d6235 | grep -a 'Attempting to push' | fmt   (logger column only)
zcat $A/canton-simtime.clog.gz | grep -a 'Rejecting submission request by' | grep -a 'T12:0[5-9]\|T12:1' | fmt 520
```
```
2026-11-20T12:06:47.720Z Attempting to push 1 topology trans... QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::12206a660c86::37-0] DEBUG
2026-11-20T12:06:47.948Z Attempting to push 1 topology trans... QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::12206a660c86::37-0] DEBUG
2026-11-20T12:06:47.950Z Attempting to push 1 topology trans... QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::12206a660c86::37-0] DEBUG
2026-11-20T12:06:47.950Z Attempting to push 1 topology trans... QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::12206a660c86::37-0] DEBUG
```
```
2026-11-20T12:06:47.963Z Rejecting submission request by Some(PAR::sv4::122047c3f222...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-20T12:06:47.965Z Rejecting submission request by Some(PAR::sv2::1220aaf2e19b...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-20T12:06:47.965Z Rejecting submission request by Some(PAR::sv1::122011d157ed...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
```
These are the only three `Rejecting submission request by` lines in the window. The transaction is the mediator
state with MED::sv4 added:
```
zcat $A/canton-simtime.clog.gz | grep -a 03b1e21d6235 | grep -a -o -E 'MediatorSynchronizerState\([^)]{0,300}' | sed -E 's/1220[0-9a-f]{60}/../g' | head -1
```
```
MediatorSynchronizerState(\n        synchronizerId = global-domain::12206a660c86...,\n        group = 0,\n        threshold = 2,\n        active = Seq(MED::sv1::1220fa433b01..., MED::sv2::1220517cb4c8..., MED::sv3::122043672bfd..., MED::sv4::1220c5d424ff...
```
Ruling out the lost-verdict and blacklisting shapes, and the open outbox flushes:
```
zcat $A/canton-simtime.clog.gz | grep -a 'T12:0[5-9]\|T12:1[0-4]' | awk '/currently blacklisted/{b++} /Failed to send result/{f++} /Another flush cycle is currently ongoing/{a++} /authenticated = 1 < dissemination quorum = 2/{q++} END{print "blacklisted",b+0,"failedSendResult",f+0,"anotherFlush",a+0,"quorumText",q+0}'
```
```
blacklisted 0 failedSendResult 0 anotherFlush 26 quorumText 84
```

## Verdict

Duplicate of 11255 ("Timeouts due to changing bft ordering topology", open, assignee rautenrieth-da), family B
rejected-send shape with the family H exit path of 11407, 11421 and 11643. Flake, cause in Canton BFT ordering (the
new ordering topology is active before the newcomers are authenticated, and the participant does not retry the
rejected topology broadcast); the splice-side mitigation is splice#8363 ("Onboard SV sequencers one at a time in
tests"), still open on 2026-11-20. No fix branch: the mitigation already exists as an open PR and the cause is not in
splice. Not verified: which serial the proposal has and why sv3's own push before the switch did not suffice alone
(DSO threshold needs more signatures); the `simtime (1)` failure of the same run.
