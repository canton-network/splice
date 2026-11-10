# 11341 - simtime (3) on main: SvExpiredRewardsCollectionTimeBasedIntegrationTest setup hits the 11255 lost-verdict shape; DisabledWalletTimeBasedIntegrationTest fails after it (run 42848234115)

DUPLICATE of 11255 (lost-verdict shape, family B), same as 11332. Post-merge main run of 5a5335450d ("[static] Add
pulumi reset command (#8350)"), Canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252. The reported failure, `Disabled
wallet` with `The app state of sv1Scan is currently not accessible`, is a follow-on. The suite before it,
SvExpiredRewardsCollectionTimeBasedIntegrationTest, timed out in `Running setup` after 5 min (`Timeout while waiting
for initialization of sv3`) and was aborted. It has no line in the sbt test summary, which shows 4 succeeded, 1
failed. In that setup the ordering topology went 1 -> 2 (sv4) at 16:20:04 and 2 -> 4 at 16:20:20, and sv4 was
blacklisted for epochs 107-109. Request 06:27:01.296Z (sim time), the first one sequenced at the 2 -> 4 switch,
then got only one mediator result: sv1 sent, sv4 was refused while blacklisted, and sv3 delayed its verdict by 3000 ms
on a sim clock that does not advance. From 16:20:33.891 the mediator group has 4 members and threshold 2. No
participant ever got the result, and all four SV participants' ledger ends stop at 16:20:20.69. sv3's
`DsoRules_SetSynchronizerNodeConfig` resubmits then hung (120 s `DEADLINE_EXCEEDED`, then shutdown), so sv3 init timed out.
Because the failure happened in `Running setup`, the topology reset plugins never ran (the last `has been reset` is at
16:18:12, before this suite). The next suite, DisabledWalletTimeBasedIntegrationTest, could not authorize the new DSO party
on sv1 (`TOPOLOGY_UNAUTHORIZED_TRANSACTION ... missing authorizations by namespaces`) and failed after 5 min. Its
destroy then failed in UpdateHistorySanityCheckPlugin on `sv1Scan` with no app state, which is the reported message.

- Run: https://github.com/canton-network/splice/actions/runs/42848234115, main 5a5335450d ("[static] Add pulumi
  reset command (#8350)"), job 129941967998 `ci / scala_test_sim_time / simtime (3)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: Canton BFT ordering / mediator verdict sending under sim time (11255); test framework (setup-failure
  path skips the topology reset plugins, as in 11332).

## 0. Issue (verbatim)

```
gh api repos/DACH-NY/cn-test-failures/issues/11341 --jq '"\(.title)\n\(.state) \(.created_at) assignees=\([.assignees[].login]) comments=\(.comments)\n\(.body)"'
```
```
GHA Run 42848234115 : job simtime (3) in scala_test Failed :fire:
open 2026-11-10T16:29:36Z assignees=[] comments=0

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42848234115).
Job: simtime (3) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [5a53354](https://github.com/canton-network/splice/commit/5a5335450d7bc61e8c662599a54a275f6b1c7f16) [static] Add pulumi reset command (#8350)
Actor: nicu-da
```

## 1. Job, canton pin, failing test

The run has one failed job, attempt 1. It matches `simtime (3)` exactly.
```
RUN=42848234115; gh run view $RUN --repo canton-network/splice --json headBranch,headSha,displayTitle,createdAt,jobs --jq '{b:.headBranch,sha:.headSha[0:10],t:.displayTitle,c:.createdAt}, (.jobs[]|select(.conclusion=="failure")|{id:.databaseId,name:.name})'; gh api repos/canton-network/splice/actions/runs/$RUN --jq '.run_attempt'
git show 5a5335450d:nix/canton-sources.json | grep -m1 version
```
```
{"b":"main","c":"2026-11-10T15:48:37Z","sha":"5a5335450d","t":"[static] Add pulumi reset command (#8350)"}
{"id":129941967998,"name":"ci / scala_test_sim_time / simtime (3)"}
1
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```
Classification: one assertion failure. Its stack is the destroy of DisabledWalletTimeBasedIntegrationTest.
```
gh api repos/canton-network/splice/actions/jobs/129941967998/logs > log/11341/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11341/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/11341/job.log | grep -a -A12 'Disabled wallet \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | head -13
```
```
##[error]Error: failed to run script step (id 6ddcb9b0-dd20-11f1-813e-7d712f71ecb1): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - Disabled wallet *** FAILED ***
[info] Run completed in 23 minutes, 44 seconds.
[info] Tests: succeeded 4, failed 1, canceled 0, ignored 1, pending 0
```
```
[info] - Disabled wallet *** FAILED ***
[info]   java.lang.RuntimeException: The app state of sv1Scan is currently not accessible. Node doesn't have any app state.
[info]   at org.lfdecentralizedtrust.splice.console.AppBackendReference.$anonfun$_appState$8(SpliceInstanceReference.scala:244)
...
[info]   at org.lfdecentralizedtrust.splice.console.ScanAppBackendReference.automation(ScanAppReference.scala:970)
[info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.$anonfun$beforeEnvironmentDestroyed$4(UpdateHistorySanityCheckPlugin.scala:58)
...
[info]   at com.digitalasset.canton.integration.EnvironmentSetup.manualDestroyEnvironment(EnvironmentSetup.scala:296)
```
Suites in the shard:
```
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/11341/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
DisabledWalletTimeBasedIntegrationTest
SvExpiredRewardsCollectionTimeBasedIntegrationTest
UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest
WalletAppRewardsTimeBasedIntegrationTest
WalletTimeBasedIntegrationTest
```

## 2. Suite timeline: the setup of SvExpiredRewardsCollection fails first, and nothing resets afterwards

Artifact `logs-simtime-3` (id 13782387246, created 2026-11-10T16:29:21Z), downloaded to `log/11341/logs-simtime-3/`.
```
T=log/11341/logs-simtime-3/canton_network_test.clog.gz; zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Running setup|has been reset|Test suite .* (finished|completed)" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-10T16:05:15.413Z Starting test suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'...",
...
2026-11-10T16:18:12.333Z decentralized namespace has been reset",
2026-11-10T16:18:12.344Z sequencer domain state threshold has been reset",
2026-11-10T16:18:12.380Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...",
2026-11-10T16:18:45.787Z Starting creating environment: Running setup",
2026-11-10T16:23:45.900Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",
2026-11-10T16:23:56.138Z Aborted test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'.",
2026-11-10T16:23:56.156Z Starting test suite 'DisabledWalletTimeBasedIntegrationTest'...",
2026-11-10T16:23:56.157Z Starting 'DisabledWalletTimeBasedIntegrationTest/Disabled wallet'...",
2026-11-10T16:23:56.655Z Starting creating environment: Running setup",
2026-11-10T16:23:56.727Z Finished creating environment: Running setup",
2026-11-10T16:28:58.069Z Test failed: 'DisabledWalletTimeBasedIntegrationTest/Disabled wallet', message: The app state of sv1Scan is currently not accessible. Node doesn't have any app state., locatio
```
Errors at the setup failure:
```
zcat $T | grep -a 'T16:2[3-4]' | grep -a -E '"level":"(ERROR|WARN)"' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-400 | head -3
```
```
2026-11-10T16:23:18.591Z Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure",[o.l.s.u.SpliceCircuitBreaker:SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=d34e6706/validator=sv4Validator] WARN
2026-11-10T16:23:45.899Z Timeout while waiting for initialization of sv3",[o.l.s.c.SvAppBackendReference:SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=d34e6706/app=sv3] ERROR
2026-11-10T16:23:45.900Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",[o.l.s.i.t.SvExpiredRewardsCollectionTimeBasedIntegrationTest:SvExpiredRewardsCollectionTimeBasedIntegrationTest] ERROR
```
`has been reset` occurs 4 times in the whole log. The last occurrence is at 16:18:12, before SvExpiredRewardsCollection started:
```
zcat $T | grep -a -c 'has been reset'; zcat $T | grep -a 'has been reset' | tail -1 | cut -c1-60
```
```
4
{"@timestamp":"2026-11-10T16:18:12.344Z","message":"sequence
```

## 3. What sv3 init waited on: DsoRules_SetSynchronizerNodeConfig resubmits that never complete

sv3's SynchronizerNodeReconciler is the active reader at shutdown. Its 16:20:03 submission succeeded at 16:20:14. The
16:20:31 resubmit (tid 8c03cdbb) hit the 120 s deadline, and the 16:22:31 one (tid d25d529d) was still open at shutdown.
```
zcat $T | grep -a -E 'T16:(19|2[0-3])' | grep -a 'SV=sv3' | grep -a -E 'SubmitAndWaitForTransaction to 127.0.0.1:15301: (sending request|received|succeeded|failed)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
zcat $T | grep -a 'SV=sv3' | grep -a -E 'setting domain config' | grep -a -E 'failed with a retryable|Giving up' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-260
```
```
2026-11-10T16:20:01.712Z Request (tid:8c8a3f94c8d66c608d1ebdbb285d28f1) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: sending requ
2026-11-10T16:20:02.889Z Request (tid:8c8a3f94c8d66c608d1ebdbb285d28f1) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: failed with 
2026-11-10T16:20:03.095Z Request (tid:d0dfafe16b797dfebbac67be110c9a31) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: sending requ
2026-11-10T16:20:14.008Z Request (tid:d0dfafe16b797dfebbac67be110c9a31) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: received a m
2026-11-10T16:20:14.009Z Request (tid:d0dfafe16b797dfebbac67be110c9a31) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: succeeded(OK
2026-11-10T16:20:31.318Z Request (tid:8c03cdbb817214a67aa57f32411a60b5) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: sending requ
2026-11-10T16:22:31.317Z Request (tid:8c03cdbb817214a67aa57f32411a60b5) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: failed with 
2026-11-10T16:22:31.529Z Request (tid:d25d529d01fc0ba036000b00d3fa2f63) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: sending requ
2026-11-10T16:23:56.051Z Request (tid:d25d529d01fc0ba036000b00d3fa2f63) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15301: failed with 
```
```
2026-11-10T16:20:02.890Z The operation 'setting domain config for digital-asset-eng-3-d34e6706::122087071711...' failed with a retryable error (full stack trace omitted):\ncategory=Some(ContentionOnSharedResources)\nErrorInfoDetail(LOCAL_VERDICT_LOCKED_CONTRACTS,...
2026-11-10T16:22:31.319Z The operation 'setting domain config for digital-asset-eng-3-d34e6706::122087071711...' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999748302s. ...
2026-11-10T16:23:56.053Z Giving up on retrying the operation 'setting domain config for digital-asset-eng-3-d34e6706::122087071711...' due to shutdown. Last attempt was Some(transient error (request infinite retries)) with exception: UNAVAILABLE: Channel shutdownNow invoked",...
```

## 4. Ordering topology: 1 -> 2 (sv4) -> 4, sv4 blacklisted for 3 epochs

```
C=log/11341/logs-simtime-3/canton-simtime.clog.gz; zcat $C | grep -a -E 'T16:(1[89]|2[0-3])' | grep -a -E 'New epoch [0-9]+ has started' | grep -a globalSequencerSv1 | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name".*//; s/1220[0-9a-f]{60}/../g; s/::[0-9a-f]{8,}/::./g' | cut -c1-330 | awk '{m=$0; sub(/New epoch [0-9]+/,"",m); sub(/^[^ ]+ /,"",m); if(m!=last){print; last=m}}'
```
```
2026-11-10T16:19:57.390Z New epoch 103 has started with leaders = List(SEQ::sv1::..5c86)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-03-04T06:27:01.196005Z,\n  size = 1, ...
2026-11-10T16:20:04.127Z New epoch 104 has started with leaders = List(SEQ::sv1::..5c86, SEQ::sv4::..b66a)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-03-04T06:27:01.216001Z,\n  size = 2, ...
2026-11-10T16:20:20.819Z New epoch 106 has started with leaders = List(SEQ::sv3::..a13e, SEQ::sv4::..b66a, SEQ::sv1::..5c86, SEQ::sv2::..9ceb)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-03-04T06:27:01.296001Z,\n  size = 4, ...
2026-11-10T16:20:33.454Z New epoch 107 has started with leaders = List(SEQ::sv3::..a13e, SEQ::sv1::..5c86, SEQ::sv2::..9ceb)and blacklisted nodes = List(SEQ::sv4::..b66a); ...
2026-11-10T16:20:46.788Z New epoch 108 has started with leaders = List(SEQ::sv1::..5c86, SEQ::sv2::..9ceb, SEQ::sv3::..a13e)and blacklisted nodes = List(SEQ::sv4::..b66a); ...
2026-11-10T16:21:01.716Z New epoch 109 has started with leaders = List(SEQ::sv2::..9ceb, SEQ::sv3::..a13e, SEQ::sv1::..5c86)and blacklisted nodes = List(SEQ::sv4::..b66a); ...
2026-11-10T16:21:16.612Z New epoch 110 has started with leaders = List(SEQ::sv3::..a13e, SEQ::sv4::..b66a, SEQ::sv1::..5c86, SEQ::sv2::..9ceb)and blacklisted nodes = List(); ...
```
Mediator group on globalSequencerSv1. It has 4 members and threshold 2 from 16:20:33.891, so a verdict needs two senders:
```
zcat $C | grep -a 'MediatorSynchronizerState' | jq -r 'select(.["@timestamp"] >= "2026-11-10T16:18:45") | select(.logger_name|test("sequencer=globalSequencerSv1")) | (.message|gsub("\\s+";" ")) as $m | ($m|[scan("MediatorSynchronizerState\\([^)]*active = Seq\\([^)]*\\)[^)]*")]|.[0]) as $s | select($s!=null) | "\(.["@timestamp"][11:23]) \($s|gsub("1220[0-9a-f]+(\\.\\.\\.)?";"")|.[0:200])"' | awk '{k=substr($0,14); if(!(k in seen)){seen[k]=1; print}}'
```
```
16:20:00.720 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv4::)
16:20:15.866 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv4::, MED::sv3::) 
16:20:15.869 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv4::, MED::sv3::)
16:20:33.891 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 2, active = Seq(MED::sv1::, MED::sv4::, MED::sv3::, MED::sv2::) 
```

## 5. The first stuck request: 06:27:01.296Z gets one result, and the participants stop publishing

Request 06:27:01.296Z was submitted by sv4 at the 2 -> 4 activation time (06:27:01.296001Z). All three mediators
approve it. sv4's result is refused by its blacklisted sequencer, sv3 delays on the stopped sim clock, and only sv1's
result is sent. No participant ever gets a result for it.
```
zcat $C | grep -a -E 'T16:2[0-3]' | grep -a -E 'request=?1970-03-04T06:27:01.296Z|request 1970-03-04T06:27:01.296Z|RequestId\(1970-03-04T06:27:01.296Z\)' | grep -a -E 'Phase (2: Registered|6)|Sent result|Failed to send result|Got result|Phase 7' | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<m>(mediator|participant)=[A-Za-z0-9]+)").m) | \(.message|gsub("\\s+";" ")|.[0:150])"'
zcat $C | grep -a -E 'T16:20:(1[5-9]|2[0-9]|3[0-4])' | grep -a 'Delaying sending of verdict' | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<m>mediator=[A-Za-z0-9]+)").m)"'
zcat $C | grep -a -E 'T16:2[0-3]' | grep -a -c 'Sent result for request 1970-03-04T06:27:01.296Z'; zcat $C | grep -a -E 'T16:2[0-3]' | grep -a 'Got result for' | grep -a -c '06:27:01.296Z'
```
```
16:20:20.812 mediator=globalMediatorSv4 | Phase 2: Registered request=1970-03-04T06:27:01.296Z from submittingParticipant=PAR::sv4::12209f911f6b... with 1 view(s).
16:20:20.812 mediator=globalMediatorSv1 | Phase 2: Registered request=1970-03-04T06:27:01.296Z from submittingParticipant=PAR::sv4::12209f911f6b... with 1 view(s).
16:20:31.385 mediator=globalMediatorSv3 | Phase 2: Registered request=1970-03-04T06:27:01.296Z from submittingParticipant=PAR::sv4::12209f911f6b... with 1 view(s).
16:20:33.866 mediator=globalMediatorSv3 | Phase 6: Finalized request=RequestId(1970-03-04T06:27:01.296Z) with verdict Approve at 1970-03-04T06:28:01.296Z
16:20:33.881 mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-03-04T06:27:01.296Z) with verdict Approve at 1970-03-04T06:28:01.296Z
16:20:33.882 mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-03-04T06:27:01.296Z) with verdict Approve at 1970-03-04T06:28:01.296Z
16:20:34.052 mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-03-04T06:27:01.296Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer
16:20:34.270 mediator=globalMediatorSv1 | Sent result for request 1970-03-04T06:27:01.296Z
```
```
16:20:17.835 mediator=globalMediatorSv4
16:20:17.847 mediator=globalMediatorSv1
16:20:18.802 mediator=globalMediatorSv4
16:20:31.324 mediator=globalMediatorSv3
16:20:33.867 mediator=globalMediatorSv3
16:20:34.330 mediator=globalMediatorSv1
16:20:34.343 mediator=globalMediatorSv3
```
```
1
0
```
Ledger end of every SV participant, last update. All four stop at 16:20:20.69, just before that request is
registered (16:20:20.812):
```
for p in 1 2 3 4; do zcat $C | grep -a "participant=sv${p}Participant" | grep -a 'Updated ledger end properties' | grep -a -E 'T16:(19|2[0-3])' | tail -1 | jq -r '"sv'$p'Participant \(.["@timestamp"][11:23]) \(.message[0:60])"'; done
```
```
sv1Participant 16:20:20.693 Updated ledger end properties: LedgerEnd(lastOffset = 2314, 
sv2Participant 16:20:20.695 Updated ledger end properties: LedgerEnd(lastOffset = 171, l
sv3Participant 16:20:20.685 Updated ledger end properties: LedgerEnd(lastOffset = 191, l
sv4Participant 16:20:20.696 Updated ledger end properties: LedgerEnd(lastOffset = 201, l
```

## 6. sv3's resubmit (tid 8c03cdbb) gets the same treatment

The request is sequenced at 06:27:01.378002Z in epoch 107 and all three mediators approve it. sv4 is refused while
blacklisted, sv3 delays, and sv1's result is ordered as `DeliverReceipt` (not delivered to the participants), so
the participants' result timers never complete:
```
zcat $C | grep -a -E '8c03cdbb817214a67aa57f32411a60b5|d25d529d01fc0ba036000b00d3fa2f63' > log/11341/tid-8c03-d25d.jsonl
grep -a 8c03cdbb log/11341/tid-8c03-d25d.jsonl | grep -a -E 'Phase 6|Failed to send|Rejecting submission request by|Delaying sending|validated to: DeliverReceipt|timeout-result has not' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/SvExpiredRewardsCollectionTimeBasedIntegrationTest\/?//g; s/psid=global-domain::1220d9bdc256::37-0\/?//g' | cut -c1-250 | awk '{m=substr($0,26,90); if(m!=last){print; last=m}}'
```
```
2026-11-10T16:20:34.342Z Phase 6: Finalized request=RequestId(1970-03-04T06:27:01.378002Z) with verdict Approve at 1970-03-04T06:28:01.378002Z",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:mediator=globalMediatorSv3/] INFO
2026-11-10T16:20:34.343Z Delaying sending of verdict by 3000 ms",[c.d.c.s.m.DefaultVerdictSender:mediator=globalMediatorSv3/] DEBUG
2026-11-10T16:20:34.350Z Phase 6: Finalized request=RequestId(1970-03-04T06:27:01.378002Z) with verdict Approve at 1970-03-04T06:28:01.378002Z",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:mediator=globalMediatorSv4/] INFO
2026-11-10T16:20:34.357Z Phase 6: Finalized request=RequestId(1970-03-04T06:27:01.378002Z) with verdict Approve at 1970-03-04T06:28:01.378002Z",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:mediator=globalMediatorSv1/] INFO
2026-11-10T16:20:34.364Z Rejecting submission request by Some(MED::sv4::12202f571822...) with SequencerDeliverError(code = SEQUENCER_OVERLOADED, message = Mempool received client request but this node is currently blacklisted, rejecting)",...
2026-11-10T16:20:34.391Z Failed to send result to sequencer for request 1970-03-04T06:27:01.378002Z RequestRefused(\n  SendAsyncErrorGrpc(\n    Request failed for server-DefaultSequencer-0.\n  GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,8c03cdbb): Mempool received client request but this node is currently blacklis...
2026-11-10T16:20:34.953Z At block 2170, the submission request 1265f200-2a9a-48a0-b75c-f84caf107cf4 at 1970-03-04T06:27:01.387Z validated to: DeliverReceipt",...
2026-11-10T16:20:44.836Z timeout-result has not completed after 10889 milliseconds",[c.d.c.p.p.c.NaiveRequestTracker:participant=sv4Participant/] DEBUG
```
(1265f200 is the result of MED::sv1: `'MED::sv1::1220b2d29812...' sends request with id '1265f200-2a9a-48a0-b75c-f84caf107cf4' of size 844 bytes with 1 envelopes to PAR=4.` at 16:20:34.367.)

## 7. The following suite: sv1 cannot authorize the new DSO party; the destroy then fails on sv1Scan

```
zcat $T | grep -a -E 'T16:2[4-8]' | grep -a 'DisabledWallet' | grep -a -E '"level":"(ERROR|WARN)"' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | head -2
zcat $C | grep -a 689ccead4467c0c58edf0fdbfaddcdaf | grep -a 'TOPOLOGY_UNAUTHORIZED_TRANSACTION' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-200
```
```
2026-11-10T16:28:58.062Z Failed clue: Start apps, wallet automations should not start up",[o.l.s.i.t.:] ERROR
2026-11-10T16:28:58.069Z Test failed: 'Disabled wallet', message: The app state of sv1Scan is currently not accessible. Node doesn't have any app state., location: SeeStackDepthException",[c.d.c.LogReporter:reporter=scala-test] ERROR
```
```
2026-11-10T16:24:00.259Z TOPOLOGY_UNAUTHORIZED_TRANSACTION(5,689ccead): Topology transaction is missing authorizations by namespaces=Set(1220d9bdc256...) and keys=Set()",[c.d.c.t.SynchronizerTopologyManager:participant=sv1Participant/psid=global-domain::1220d9bdc256::37-0] WARN
```
(sv1 retries `Check whether Party DSO-6087a5d5-6087a5d5::1220d9bdc256... is allocated on PAR::sv1::...` with that
error every 2 to 5 s from 16:24:00. The DSO namespace 1220d9bdc256 is still the 4-owner namespace of the aborted suite,
because the reset of section 2 never ran.)

## 8. Duplicate check

Family B text in `known-families.md`, "11255 on Canton 3.7.3-snapshot.20261109" (lost verdict and setup-failure
blast radius). The confirming greps from there are: the 1 -> 2 -> 4 steps with blacklisting (section 4), `Failed to
send result ... currently blacklisted` plus a delayed verdict (sections 5 and 6), and zero `has been reset` after the
failed setup (section 2). 11255 itself is open, assigned to rautenrieth-da, and splice#8356 is open:
```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"\(.state) assignees=\([.assignees[].login]) comments=\(.comments) title=\(.title)"'
gh pr view 8356 --repo canton-network/splice --json state,title,mergedAt --jq '"\(.state) \(.mergedAt) \(.title)"'
```
```
open assignees=["rautenrieth-da"] comments=3 title=Timeouts due to changing bft ordering topology
OPEN null Disable BFT sequencer blacklisting in integration tests
```
Post-merge main runs since the previous round (11:45 UTC). All three failed, and this is the only new simtime issue:
```
gh api "repos/canton-network/splice/actions/runs?branch=main&event=push&created=>=2026-11-10T11:45:00Z&per_page=100" --jq '.workflow_runs[] | select(.name|test("post-merge")) | "\(.id) \(.created_at[0:16]) \(.head_sha[0:10]) \(.status) \(.conclusion)"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "simtime" created:>=2026-11-10T14:00:00Z' --jq '.items[] | "\(.number) \(.state) \(.created_at[0:16]) \(.title[0:80])"'
```
```
42848234115 2026-11-10T15:48 5a5335450d completed failure
42823122392 2026-11-10T13:17 1844b95301 completed failure
42810067070 2026-11-10T11:45 88491ce56e completed failure
11341 open 2026-11-10T16:29 GHA Run 42848234115 : job simtime (3) in scala_test Failed :fire:
```

## Verdict

Duplicate of 11255, lost-verdict shape with the setup-failure blast radius, the same as 11332. This is a flake in
Canton BFT ordering and mediator verdict sending under sim time. Its fix candidates are on 11255: splice#8356, which
turns blacklisting off and covers this shape, and running the topology reset plugins on the setup-failure path, which
would have kept DisabledWalletTimeBasedIntegrationTest green. No new fix. Not verified: why the sequencer orders
sv1's lone result as `DeliverReceipt`. The packet infers that from the threshold-2 group in section 4 and has not
checked the aggregation rule in the jar.
