# 11643 - simtime (4) exits without a report in SvTimeBasedRoundMgmt round management (run 44444328126)

DUPLICATE of 11255 (family B, rejected-send shape, same exit path as 11407 and 11421). Post-merge main 1eafba3113
(#8521), job `ci / scala_test_sim_time / simtime (4)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. The shard
got only as far as its first suite, SvTimeBasedRoundMgmtIntegrationTest. In the test `round management` (initDso,
isolated environment) the BFT ordering topology grew 1 -> 4 at 02:46:54.333 (epoch 12). The MediatorSynchronizerState
proposal adding MED::sv3 (tx 001c8bf60f98) was pushed by sv3 at 02:46:54.048, before the switch; the pushes of sv4,
sv1 and sv2 were rejected by globalSequencerSv1 24 ms, 154 ms and 926 ms after the switch (`P2P connectivity is not
ready (authenticated = 1 < dissemination quorum = 2)`) and not retried. sv3 waited on its mediator traffic grant from
02:46:58.058 until the init wait timed out at 02:49:48.476; the teardown's ResetDecentralizedNamespace did not
succeed within 1 minute (ERROR 02:51:00.497) and ResetTopologyStatePlugin calls sys.exit(1) there, so sbt exited at
02:51:01 with no test report and checkErrors was skipped.

- Run: https://github.com/canton-network/splice/actions/runs/44444328126, main 1eafba3113 ("add-column migration for
  BigQuery staging->production (#8521)"), job 135293050650 `ci / scala_test_sim_time / simtime (4)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 1eafba3113).
- Component: Canton BFT ordering (sequencer onboarding window under sim time); exit path is the test harness.

Artifact: `log/11643/logs-simtime-4/` (`gh run download 44444328126 --repo canton-network/splice -n logs-simtime-4`),
job log `log/11643/job.log` (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135293050650/logs`).
In the commands `A=log/11643/logs-simtime-4` and `fmt` is the generic formatter from recipes section 4.

## 1. Job log: no report, sbt exit after 6 min of the only test

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11643/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|Test still running|Attempt 1 failed|Exceeded maximum retries|##\[error\]' | grep -v 'echo "' | tail -7 | cut -c1-200
```
```
2026-11-19T02:50:05.1801843Z [info] *** Test still running after 5 minutes, 27 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management. 
2026-11-19T02:50:35.3238637Z [info] *** Test still running after 5 minutes, 57 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management. 
2026-11-19T02:51:01.4584178Z Attempt 1 failed with exit code 1, retrying
2026-11-19T02:51:01.4587344Z Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
2026-11-19T02:51:01.4639063Z ##[error]Error: failed to run script step (id 1243b0f0-e3c3-11f1-9e3c-599a6e30140e): Error: step failed with return code 1
2026-11-19T02:51:01.4727860Z ##[error]Process completed with exit code 1.
2026-11-19T02:51:01.4753860Z ##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```
No
`FAILED ***`, no `Tests:` summary, no `contains problems`: evidence loss class. The "custom container" annotation is
the runner's wording for a failed step, not an infra fault.

## 2. Test log: the only suite, sv3 init timeout, reset failure

```
zcat $A/canton_network_test.clog.gz | grep -a -E "Starting test suite|Starting '" | fmt
zcat $A/canton_network_test.clog.gz | grep -a -E '"level":"(WARN|ERROR)"' | fmt 420
```
```
2026-11-19T02:44:37.683Z Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-19T02:44:37.710Z Starting 'SvTimeBasedRoundMgmtIntegrationTest/round management'...",[c.d.c.LogReporter:reporter=scala-test] INFO
```
```
2026-11-19T02:49:48.476Z Timeout while waiting for initialization of sv3",[o.l.s.c.SvAppBackendReference:SvTimeBasedRoundMgmtIntegrationTest/config=643df84c/app=sv3] ERROR
2026-11-19T02:51:00.497Z Failed to reset decentralized namespace",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] ERROR
```
The ERROR at 02:51:00.497 is followed by the sbt exit at 02:51:01.458 (section 1). The plugin exits the JVM right
after that log line:
```
git grep -n -E 'sys\.exit|Failed to reset' 1eafba3113 -- 'apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/Reset*'
```
```
1eafba3113:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala:39:        sys.exit(1)
1eafba3113:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala:85:        sys.exit(1)
1eafba3113:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala:106:          logger.error(s"Failed to reset $topologyType", e)
1eafba3113:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala:107:          sys.exit(1)
```

## 3. What sv3 waited on

```
zcat $A/canton_network_test.clog.gz | grep -a -c 'unlimited traffic'
zcat $A/canton_network_test.clog.gz | grep -a 'unlimited traffic' | fmt 260 | sed -n '1p;$p'
```
```
208
2026-11-19T02:46:58.058Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=643df84c/SV=sv3] INFO
2026-11-19T02:50:00.272Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=643df84c/SV=sv3] INFO
```

## 4. Family B confirming grep: 1 -> 4 switch, then rejected sends

```
zcat $A/canton-simtime.clog.gz | grep -a -E 'New epoch 1[12] has started with leaders' | fmt 330 | awk '!s[substr($0,26,60)]++'
```
```
2026-11-19T02:46:47.636Z New epoch 11 has started with leaders = List(SEQ::sv1::..32db)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T00:00:00.150002Z,\n  size = 1,\n  weakQuorum = 1,\n  strongQuorum = 1,\n  nodesTopologyInfo = \"SEQ::sv1::..32db\" -> NodeTopologyInfo(keyIds = \"..3188\"),\n  sequencingPa
2026-11-19T02:46:54.333Z New epoch 12 has started with leaders = List(SEQ::sv1::..32db, SEQ::sv2::..59fb, SEQ::sv3::..225d, SEQ::sv4::..d87b)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T00:00:00.170002Z,\n  size = 4,\n  weakQuorum = 2,\n  strongQuorum = 3,\n  nodesTopologyInfo = Map(\n    \"SEQ::sv1::..32
```
(The same epoch 12 line is logged again by the three newcomers at 02:47:02.342, 02:47:04.851 and 02:47:07.783.)

```
zcat $A/canton-simtime.clog.gz | grep -a 'Rejecting submission request by' | grep -a 'P2P connectivity is not ready' | fmt 420
```
```
2026-11-19T02:46:54.357Z Rejecting submission request by Some(PAR::sv4::12203ccd4eff...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-19T02:46:54.487Z Rejecting submission request by Some(PAR::sv1::1220bf4b836e...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-19T02:46:55.259Z Rejecting submission request by Some(PAR::sv2::12209b05fe65...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
```

Lost-verdict shape ruled out, no blacklisting, flush cycles stuck:
```
zcat $A/canton-simtime.clog.gz | awk '/Rejecting submission request by/&&/P2P connectivity is not ready/{r++} /Failed to send result/{f++} /Another flush cycle is currently ongoing/{a++} /below weak quorum/{w++} /blacklisted nodes = List\(SEQ/{b++} /currently blacklisted/{c++} END{print "rejected",r+0,"failedSendResult",f+0,"anotherFlush",a+0,"belowWeakQuorum",w+0,"blacklisted",b+0,"currentlyBlacklisted",c+0}'
```
```
rejected 3 failedSendResult 0 anotherFlush 22 belowWeakQuorum 2 blacklisted 0 currentlyBlacklisted 0
```

## 5. The rejected sends carry the MED::sv3 MediatorSynchronizerState proposal

Each rejection follows the outbox push of tx 001c8bf60f98 of the same participant by 9-16 ms; sv3's own push is
285 ms before the switch and has no rejection.
```
zcat $A/canton-simtime.clog.gz | grep -a -E 'T02:46:5[3-5]' | grep -a 'QueueBasedSynchronizerOutbox' | grep -a 'Attempting to push' | grep -a 001c8bf60f98 | fmt 520
```
```
2026-11-19T02:46:54.048Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:001c8bf60f98...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220095c8c2f::37-0] DEBUG
2026-11-19T02:46:54.341Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:001c8bf60f98...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220095c8c2f::37-0] DEBUG
2026-11-19T02:46:54.478Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:001c8bf60f98...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220095c8c2f::37-0] DEBUG
2026-11-19T02:46:55.249Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:001c8bf60f98...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220095c8c2f::37-0] DEBUG
```
The transaction (first mention, sv3Participant, 02:46:54.046):
```
zcat $A/canton-simtime.clog.gz | grep -a -m3 001c8bf60f98 | grep -a -o -E 'MediatorSynchronizerState\([^)]{0,260}' | sed -E 's/1220[0-9a-f]{60}/../g' | head -1
```
```
MediatorSynchronizerState(\n        synchronizerId = global-domain::1220095c8c2f...,\n        group = 0,\n        threshold = 2,\n        active = Seq(MED::sv1::1220d99fc8f1..., MED::sv2::12206191c4d6..., MED::sv4::122071fbc8e4..., MED::sv3::1220c17a3da3...
```
The rejected send is not retried and stays "possibly sequenced" (sv4Participant, same for the others):
```
zcat $A/canton-simtime.clog.gz | grep -a -E 'T02:46:54\.3[5-9]' | grep -a -E 'Retry has not been configured|may still be possibly sequenced' | fmt 230
```
```
2026-11-19T02:46:54.363Z Retry has not been configured for GrpcServiceUnavailable, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=sv4Participant/psid=global-domain::1220095c8c2f::37-0/pool=main/connection=DefaultSequencer-0] DEBUG
2026-11-19T02:46:54.383Z Send [56fc77a1-9c1b-461c-bced-6d2dff349767] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-DefaultSequencer-0. Is the server running? Did you configure
```
Flush cycles never close until shutdown (count, first, last per participant):
```
zcat $A/canton-simtime.clog.gz | grep -a 'Another flush cycle is currently ongoing' | grep -a -o -E '"@timestamp":"[^"]+"|participant=[a-zA-Z0-9]+' | paste - - | awk '{c[$2]++; if(!($2 in f))f[$2]=$1; l[$2]=$1} END{for(k in c) print k, c[k], f[k], l[k]}'
```
```
participant=sv3Participant 7 "@timestamp":"2026-11-19T02:46:48.182Z" "@timestamp":"2026-11-19T02:50:00.429Z"
participant=sv1Participant 3 "@timestamp":"2026-11-19T02:46:48.054Z" "@timestamp":"2026-11-19T02:50:00.490Z"
participant=sv4Participant 7 "@timestamp":"2026-11-19T02:46:46.572Z" "@timestamp":"2026-11-19T02:50:00.450Z"
participant=sv2Participant 5 "@timestamp":"2026-11-19T02:46:44.903Z" "@timestamp":"2026-11-19T02:50:00.471Z"
```

## 6. Parent and fix state

```
gh issue view 11255 -R DACH-NY/cn-test-failures --json title,state,assignees --jq '{title,state,a:[.assignees[].login]}'
gh pr view 8363 -R canton-network/splice --json title,state,mergedAt --jq '{title,state,mergedAt}'
gh pr view 8356 -R canton-network/splice --json title,state,mergedAt --jq '{title,state,mergedAt}'
```
```
{"a":["rautenrieth-da"],"state":"OPEN","title":"Timeouts due to changing bft ordering topology"}
{"mergedAt":null,"state":"OPEN","title":"Onboard SV sequencers one at a time in tests"}
{"mergedAt":"2026-11-10T23:29:46Z","state":"MERGED","title":"Disable BFT sequencer blacklisting in integration tests"}
```

## Verdict

Duplicate of 11255 (family B, rejected-send shape under simulated time; same exit path as 11407 and 11421, MED::sv3
variant). Flake, not caused by #8521. Cause is in Canton BFT ordering (the new ordering topology is activated at the
epoch boundary before the newcomers are P2P-authenticated, and sends rejected in that window are not retried); the
splice-side mitigation is the open splice#8363 (onboard SV sequencers one at a time in tests). No fix branch.

Not verified: that sv3's own push at 02:46:54.048 was sequenced (inferred from the absence of a rejection for
PAR::sv3; the proposal in any case stayed below the signatures it needs, since sv3 never saw the mediator onboarded);
the sys.exit itself leaves no log line and is inferred from the ERROR at 02:51:00.497, the plugin source and the sbt
exit 0.96 s later. Canton jar not inspected (mechanism documented under family B).
