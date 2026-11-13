# 11415 - simtime (5): MED::sv3 MediatorSynchronizerState pushes of all four SV participants rejected at the 1 -> 4 switch in the SvExpiredRewardsCollectionTimeBased setup; the reported ExternallySignedTxs failure is the setup blast radius (run 43463352087)

DUPLICATE of 11255 (rejected-send shape, family B). Main 2c5cd9a752, the merge of the Canton bump to
3.7.4-snapshot.20261112.20915.0.v9e248620 (#8479), job simtime (5). This is the first 11255 hit on that Canton
snapshot; the shape is the same as 11386 (same suite, same MED::sv3 add, Canton 3.7.3), so the bump neither caused nor
fixed it. In the setup of SvExpiredRewardsCollectionTimeBasedIntegrationTest the global ordering topology goes from 1 to
4 nodes at 16:57:02.471. sv1's sequencer rejects the pushes of the MediatorSynchronizerState proposal that adds MED::sv3
(tx e2b097c017a7) from sv4 (16:57:02.533), sv2 (.548), sv3 (.870) and sv1 (16:57:03.021) with `P2P connectivity is not
ready (authenticated = 1 < dissemination quorum = 2)`. sv3's SV app waits on `Mediator has been granted unlimited
traffic` from 16:57:06.601 until the 5 min init wait aborts the suite (not in the test summary as a test, only as
`*** ABORTED ***`). The setup failure skips the namespace reset, so the next two suites fail sv1 init with
TOPOLOGY_UNAUTHORIZED_TRANSACTION: ExternallySignedTxsTimeBasedIntegrationTest (the test named in the summary; its
external-signing logic never ran) and SvTimeBasedBootstrappingRoundIntegrationTest. No blacklisting, no lost verdicts.

- Run: https://github.com/canton-network/splice/actions/runs/43463352087, main 2c5cd9a752 ("Upgrade canton to
  3.7.4-snapshot.20261112.20915.0.v9e248620 (#8479)"), job 132016350741 `ci / scala_test_sim_time / simtime (5)`,
  attempt 1.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 2c5cd9a752).
- Component: Canton BFT ordering under simulated time (parent 11255, assignee rautenrieth-da).

All commands run from the repo root; `T=log/11415/logs-simtime-5/canton_network_test.clog.gz`,
`C=log/11415/logs-simtime-5/canton-simtime.clog.gz`, and
`F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/(SEQ|PAR|MED)::([a-z0-9]+)::[0-9a-f.]+/\1::\2/g'`.

## 0. Job resolution and Canton pin

```
export NAME='simtime (5)'; gh api --paginate "repos/canton-network/splice/actions/runs/43463352087/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
git show 2c5cd9a752ad507c46a7c6b227ccd9f03ab97f51:nix/canton-sources.json | grep -m1 version
git show 09c8608e46ea296de76da6d86fff03ba3b2b2e7b:nix/canton-sources.json | grep -m1 version
```
```
132016350741 attempt=1 ci / scala_test_sim_time / simtime (5)
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
  "version": "3.7.3",
```
(09c8608e46 is the previous post-merge run, 43457213815, on 3.7.3.)

## 1. Failing assertion: environment setup, not the test body

The summary names one test of ExternallySignedTxsTimeBased, but its failure (and both aborted suites) is the 5 min
`waitForInitialization` of an SV app in `Running setup`.
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11415/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|ABORTED' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/11415/job.log | grep -a -m1 -A6 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //'
```
```
[info]  *** ABORTED ***
[info] *** 1 TEST FAILED ***
[info] *** 2 SUITES ABORTED ***
[info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
[info] Run completed in 26 minutes, 44 seconds.
[info] Tests: succeeded 4, failed 1, canceled 0, ignored 1, pending 0
[info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedBootstrappingRoundIntegrationTest *** ABORTED ***
[info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
```

## 2. Suite timeline

TimeBasedTreasury passes with a reset after each test; SvExpiredRewardsCollectionTimeBased starts at 16:55:10 and
never resets or reports a test; the two following suites start at 17:00:43 and 17:05:57.
```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |has been reset" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | tail -7
zcat $T | grep -a 'has been reset' | grep -a -c -E '"@timestamp":"2026-11-13T(16:5[6-9]|17:)'
```
```
2026-11-13T16:55:10.139Z decentralized namespace has been reset",
2026-11-13T16:55:10.152Z sequencer domain state threshold has been reset",
2026-11-13T16:55:10.178Z Test succeeded: 'TimeBasedTreasuryIntegrationTest/merge also amulet that should be expired'",
2026-11-13T16:55:10.185Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...",
2026-11-13T17:00:43.996Z Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...",
2026-11-13T17:05:57.846Z Test failed: 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance',
2026-11-13T17:05:57.857Z Starting test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'...",
0
```

## 3. Ordering topology 1 -> 4 at 16:57:02.471, no blacklisting

```
zcat $C | grep -a 'New epoch 8[23] has started' | grep -a 'globalSequencerSv1' | sed -E "$F" | sed -E 's/ordering topology = OrderingTopology\(\\n  activationTime = [^,]*,\\n  (size = [0-9]+).*/\1/' | cut -c1-200
```
```
2026-11-13T16:56:55.390Z New epoch 82 has started with leaders = List(SEQ::sv1)and blacklisted nodes = List(); size = 1
2026-11-13T16:57:02.471Z New epoch 83 has started with leaders = List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3)and blacklisted nodes = List(); size = 4
```

## 4. The MED::sv3 MediatorSynchronizerState pushes of all four SVs are rejected 62-550 ms after the switch

Each SV participant's outbox pushes tx e2b097c017a7 7-9 ms before sv1's sequencer rejects a submission from that
participant with `P2P connectivity is not ready`.
```
zcat $C | grep -a -E '"@timestamp":"2026-11-13T16:57:0(2\.[3-9]|3\.0)' | grep -a -E 'Attempting to push|Rejecting submission request by' | sed -E "$F" | cut -c1-300
```
```
2026-11-13T16:57:02.518Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:e2b097c017a7...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220ddcc6259::37-0] DEBUG
2026-11-13T16:57:02.533Z Rejecting submission request by Some(PAR::sv4) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T16:57:02.539Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:e2b097c017a7...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220ddcc6259::37-0] DEBUG
2026-11-13T16:57:02.548Z Rejecting submission request by Some(PAR::sv2) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T16:57:02.862Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:e2b097c017a7...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220ddcc6259::37-0] DEBUG
2026-11-13T16:57:02.870Z Rejecting submission request by Some(PAR::sv3) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T16:57:03.013Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:e2b097c017a7...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220ddcc6259::37-0] DEBUG
2026-11-13T16:57:03.021Z Rejecting submission request by Some(PAR::sv1) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
```
Tx e2b097c017a7 is the MediatorSynchronizerState that adds MED::sv3 (last in `active`):
```
zcat $C | grep -a -m3 -E 'e2b097c017a7.*MediatorSynchronizerState|MediatorSynchronizerState.*e2b097c017a7' | grep -a -oE 'MediatorSynchronizerState\([^)]{0,300}' | sed -E 's/1220[0-9a-f]{60}/../g; s/::[0-9a-f]{20,}/::../g' | head -1
```
```
MediatorSynchronizerState(\n        synchronizerId = global-domain::1220ddcc6259...,\n        group = 0,\n        threshold = 2,\n        active = Seq(MED::sv1::1220495f042d..., MED::sv2::1220d426b670..., MED::sv4::1220b69dfdb4..., MED::sv3::1220c8165f41...
```

## 5. Not the lost-verdict or breaker shape

```
for p in 'currently blacklisted' 'Failed to send result' 'P2P connectivity is not ready'; do echo "$p: $(zcat $C | grep -a -c "$p")"; done
```
```
currently blacklisted: 0
Failed to send result: 0
P2P connectivity is not ready: 72
```

## 6. sv3's SV app waits on the mediator's traffic grant until the init wait ends

```
zcat $T | grep -a 'SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=dced6c95/SV=sv3' | grep -a -E 'Waiting until Mediator|unlimited traffic' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | head -3 | cut -c1-200
```
```
2026-11-13T16:57:06.601Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=dced6c95/SV=sv3] INFO
2026-11-13T16:57:06.605Z The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable error (full stack trace omitted): NOT_FOUND: No traffic state found for membe
2026-11-13T16:57:06.605Z The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying
```

## 7. Blast radius: the next suite (the reported test) fails sv1 init on the unreset DSO namespace

sv1 in ExternallySignedTxsTimeBased cannot re-establish the DSO PartyToParticipant because the decentralized namespace
was never reset after the aborted setup (section 2: zero resets after 16:55:10).
```
zcat $T | grep -a -E '"@timestamp":"2026-11-13T17:0[1-5]' | grep -a 'ExternallySignedTxsTimeBasedIntegrationTest/config=59e0251b/SV=sv1' | grep -a -E '"level":"(WARN|ERROR)"|retryable error' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | head -1 | cut -c1-330
zcat $C | grep -a 'be39d455ae346b13e89c407b57355be9' | grep -a -m1 'TOPOLOGY_UNAUTHORIZED_TRANSACTION' | grep -a -oE '"@timestamp":"[^"]+","message":"[^"]{0,160}' | sed -E 's/1220[0-9a-f]{60}/../g'
```
```
2026-11-13T17:01:00.742Z The operation 'Check whether Party DSO-59e0251b-59e0251b::1220ddcc6259... is allocated on PAR::sv1::122010f3b4f3... and establish it if needed' failed with a retryable error (full stack trace omitted): INVALID_ARGUMENT: An error occurred. Please contact the operator and inquire about the request be39d455ae346b13e89c407b57355be9 with
"@timestamp":"2026-11-13T17:01:00.741Z","message":"TOPOLOGY_UNAUTHORIZED_TRANSACTION(5,be39d455): Topology transaction is missing authorizations by namespaces=Set(1220ddcc6259...) and keys=Set()
```

## 8. Duplicate check

Same suite and same MED::sv3 add as 11386 (Canton 3.7.3) and 11391; parent 11255 open, assigned.
```
awk -F'\t' '$7 ~ /SvExpiredRewardsCollection/ {print $1"\t"$5"\t"substr($7,1,120)"\t"$8}' ci-triage/evidence/INDEX.tsv
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) \(.title)"'
gh api repos/DACH-NY/cn-test-failures/issues/11386 --jq '"\(.number) \(.state) \(.state_reason)"'
```
```
11341	129941967998 simtime (3)	SvExpiredRewardsCollectionTimeBasedIntegrationTest setup (aborted, not in the summary): ordering 1 -> 2 (16:20:04) -> 4 (16:	11255
11386	131160106132 `simtime (5)`, attempt 1	SvExpiredRewardsCollectionTimeBasedIntegrationTest setup: ordering 1 -> 4 at epoch 84 12:09:50.296 (no blacklisting); MED::	11255 (B, rejected-send)
11391	131219123664 `simtime (4)`, attempt 1	SvExpiredRewardsCollectionTimeBasedIntegrationTest setup: ordering 1 -> 4 at epoch 140 14:09:19.993; MED::sv3 add: only sv2 si	11255 (B, rejected-send)
11255 open assignees=rautenrieth-da Timeouts due to changing bft ordering topology
11386 closed duplicate
```
Post-merge main runs on 2026-11-13 since 12:00 (this run is the first on Canton 3.7.4-snapshot; the two after it were
still running at triage time):
```
gh run list --repo canton-network/splice --branch main --event push --created '>=2026-11-13T12:00:00Z' --limit 30 --json databaseId,headSha,conclusion,createdAt,workflowName,status --jq '.[] | select(.workflowName|test("post-merge")) | "\(.databaseId) \(.headSha[0:10]) \(.createdAt) \(.status) \(.conclusion)"'
```
```
43467250981 a6ac3bde8d 2026-11-13T16:53:32Z in_progress 
43465999013 09f5cc4b78 2026-11-13T16:45:57Z in_progress 
43463352087 2c5cd9a752 2026-11-13T16:30:16Z completed failure
43457213815 09c8608e46 2026-11-13T15:53:58Z completed failure
43444026219 87d584aa16 2026-11-13T14:34:38Z completed failure
43433803626 ec67414f5d 2026-11-13T13:27:31Z completed failure
```

## Verdict

Duplicate of 11255, rejected-send shape (family B), with the setup-failure blast radius; ExternallySignedTxsTimeBased is a
victim, not a culprit. Flake in Canton BFT ordering under simulated time, fix with the parent's assignee. Not a
regression from the Canton 3.7.4-snapshot bump: identical shape on 3.7.3 (11386, same suite and MED::sv3). Not
verified: whether 3.7.4-snapshot changes the rate (one run so far).
