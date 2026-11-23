# 11665 - WalletTimeBasedIntegrationTest "list multiple subscriptions": 20 s wait for the second subscription vs a 35 s DSO round trip in a slow BFT epoch (run 44572202679)

new (family BO), test timing. main dacc71079a ("support participant admin API auth in the participant console
(#8585)"), job `ci / scala_test_sim_time / simtime (5)`, attempt 1, Canton 3.7.4. 8 tests passed, 1 failed:
`A wallet should allow a user to list multiple subscriptions in different states` fails in the second loop iteration
with `had length 1 instead of expected length 2 (WalletTimeBasedIntegrationTest.scala:105)`. The check waits the
default 20 s (17:24:20.933 to 17:24:40.948) for the Subscription that sv1 creates in two sequential ledger round trips
after alice accepts the request: the confirmation by AnsSubscriptionInitialPaymentTrigger (8.2 s, done 17:24:29.021)
and `DsoRules_ExecuteConfirmedAction` by ExecuteConfirmedActionTrigger (35.5 s, 17:24:29.484 to 17:25:04.991). The
subscription existed 24 s after the wait gave up. The second round trip was slow in the BFT ordering layer (4 nodes
since the previous suite): the two confirmation responses were batched at 17:24:32.097, too late for sv1's last slot
of epoch 79, and epoch 80 needed 25.5 s instead of 7-8 s (first pre-prepare stored after 3.7 s on all four
sequencers, PBFT 5 s timeout, view change in all four segments, first block ordered after 20.2 s), so they were
ordered only at 17:25:02.4 in epoch 81. Why the sequencers' pre-prepare writes stalled is not visible in the
artifacts (the scan app's writes to Postgres stayed at 1-3 ms in the same seconds). Same slow-window shape as 11532
(simtime, suite after TimeBasedTestNetPreview, 4-node ordering topology) and 11375 (wall-clock), cause open in both.

- Run: https://github.com/canton-network/splice/actions/runs/44572202679, main dacc71079a ("support participant admin
  API auth in the participant console (#8585)"), job 135730552502 `ci / scala_test_sim_time / simtime (5)`, attempt 1,
  runner self-hosted-k8s-large-runner-lfrpz, 17:00:21 to 17:36:06 UTC.
- Runtime canton: 3.7.4.
- Component: test (wait budget); the slow ordering window is environment or Canton BFT, cause open.
- Tuple as given: run 44572202679, simtime (5), ref 11665. The run has one failed job and the issue body names it.

## 0. Issue and job

```
gh run view 44572202679 --repo canton-network/splice --json headBranch,headSha,displayTitle,createdAt,event,jobs --jq '{b:.headBranch,sha:.headSha,t:.displayTitle,c:.createdAt,e:.event}, (.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{id:.databaseId,name:.name,c:.conclusion,s:.startedAt,e:.completedAt})'
git show dacc71079a:nix/canton-sources.json | grep -m1 version
```
```
{"b":"main","c":"2026-11-19T16:59:19Z","e":"push","sha":"dacc71079a469d89b8059d12e9995324197f02ae","t":"support participant admin API auth in the participant console (#8585)"}
{"c":"failure","e":"2026-11-19T17:36:06Z","id":135730552502,"name":"ci / scala_test_sim_time / simtime (5)","s":"2026-11-19T17:00:21Z"}
  "version": "3.7.4",
```

Setup (all later commands run from the triage checkout):

```
mkdir -p log/11665; export TMPDIR=$PWD/log/ghtmp; mkdir -p $TMPDIR
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135730552502/logs > log/11665/job.log
gh run download 44572202679 --repo canton-network/splice -n logs-simtime-5 -D log/11665/logs-simtime-5
T=log/11665/logs-simtime-5/canton_network_test.clog.gz; C=log/11665/logs-simtime-5/canton-simtime.clog.gz
```

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11665/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-200
sed -E 's/\x1b\[[0-9;]*m//g' log/11665/job.log | sed -E 's/^[^Z]*Z //' | grep -a -m3 -E 'had length|WalletTimeBasedIntegrationTest.\$anonfun\$new|actAndCheck\$1'
```
```
##[error]Error: failed to run script step (id 23b1c6a0-e43c-11f1-9fab-839a6533ad4b): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - allow a user to list multiple subscriptions in different states *** FAILED ***
[info] - should allow a user to list multiple subscriptions in different states *** FAILED ***
[info] Tests: succeeded 8, failed 1, canceled 0, ignored 0, pending 0
[info]   )))) had length 1 instead of expected length 2 (WalletTimeBasedIntegrationTest.scala:105)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.WalletTimeBasedIntegrationTest.$anonfun$new$12(WalletTimeBasedIntegrationTest.scala:105)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$actAndCheck$1(SpliceTests.scala:374)
```

The check is the default-budget `actAndCheck` at WalletTimeBasedIntegrationTest.scala:97-107 (20 s, SpliceTests.scala:352-378).

## 2. Suite and test timeline

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /' | cut -c1-150 | sed -n 4,11p
```
```
17:18:26.235 Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
17:18:26.235 Starting 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly'...",
17:22:51.894 Test succeeded: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly'",
17:22:51.910 Starting test suite 'WalletTimeBasedIntegrationTest'...",
17:23:32.506 Starting 'WalletTimeBasedIntegrationTest/A wallet should allow a user to list multiple subscriptions in different states'...",
17:24:40.955 Test failed: 'WalletTimeBasedIntegrationTest/A wallet should allow a user to list multiple subscriptions in different states', message: C
17:24:40.966 Starting 'WalletTimeBasedIntegrationTest/A wallet should auto-expire locked amulet'...",
17:27:19.801 Test succeeded: 'WalletTimeBasedIntegrationTest/A wallet should auto-expire locked amulet'",
```

First iteration: accept to subscription listed in 5.3 s. Second iteration (after the 10 d and 10 min advances): the
accept alone takes 4.5 s and the check times out after 20 s.

```
zcat $T | grep -a '"@timestamp":"2026-11-19T17:2[345]' | grep -a -E '"logger_name":"o.l.s.i.t.WalletTimeBased[^"]*"' | grep -a -E 'Accept subscription request|subscription is created|advancing time by|Starting test case' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /' | cut -c1-110
```
```
17:23:32.505 Starting test case 0",
17:23:37.503 Running clue: (act) Accept subscription request",
17:23:38.537 Finished clue: (act) Accept subscription request",
17:23:38.537 Running clue: (check) subscription is created",
17:23:42.796 Finished clue: (check) subscription is created",
17:23:44.324 advancing time by PT240H to 1970-01-11T02:22:20Z",
17:24:00.565 advancing time by PT10M to 1970-01-11T02:32:20Z",
17:24:16.417 Running clue: (act) Accept subscription request",
17:24:20.933 Finished clue: (act) Accept subscription request",
17:24:20.933 Running clue: (check) subscription is created",
17:24:40.948 Failed clue: (check) subscription is created",
17:24:40.948 Failed clue: Creating 3 subscriptions, 10 days apart",
17:24:40.965 Starting test case 1",
17:25:14.670 advancing time by PT10M10S to 1970-01-11T02:42:30Z",
17:25:43.047 advancing time by PT10M10S to 1970-01-11T02:52:40Z",
17:25:58.752 advancing time by PT10M10S to 1970-01-11T03:02:50Z",
```

## 3. What the check waits on: two sv1 round trips, 8.2 s and 35.5 s

The Subscription is created by sv1: AnsSubscriptionInitialPaymentTrigger confirms the collection, then
ExecuteConfirmedActionTrigger executes it. The execution lock was free (not family BA).

```
zcat $T | grep -a '"@timestamp":"2026-11-19T17:2[45]' | grep -a -E '0c9a07dc023582034023117e9cc01b34|a21bb10ad153d616477bfa0db17d7567' | grep -a -E 'SubmitAndWait(ForTransaction)? to 127.0.0.1:15101: (sending request|succeeded)' | sed -E 's/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /' | cut -c1-150
zcat $T | grep -a '"@timestamp":"2026-11-19T17:2[45]' | grep -a -E 'AnsSubscriptionInitialPaymentTrigger|ExecuteConfirmedActionTrigger' | grep -a -E 'Completed processing with outcome|Acquired lock' | grep -a 'SV=sv1' | sed -E 's/"logger_name":"o.l.s.s.a.[a-z.]*([A-Za-z]+):.*/[\1]/; s/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /; s/ContractId\([0-9a-f]{10}[0-9a-f.]*\)/ContractId(..)/g' | awk '$1>="17:24:20" && $1<="17:25:06"' | cut -c1-150
```
```
17:24:20.792 Request (tid:0c9a07dc023582034023117e9cc01b34) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15101: sending request Su
17:24:29.021 Request (tid:0c9a07dc023582034023117e9cc01b34) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15101: succeeded(OK)","lo
17:24:29.484 Request (tid:a21bb10ad153d616477bfa0db17d7567) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15101: send
17:25:04.991 Request (tid:a21bb10ad153d616477bfa0db17d7567) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15101: succ
17:24:29.164 Completed processing with outcome: confirmed to create ans entry alice2_tc0.unverified.ans by collecting payment ContractId(..)",[AnsSubs
17:24:29.476 Acquired lock after PT0.000046S",[ExecuteConfirmedActionTrigger]
17:25:05.118 Completed processing with outcome: executed action ARC_AnsEntryContext(\n  Record(\n    fields = Seq(\n      ContractId(..),\n      ANSRA
```

Command ids: `org.lfdecentralizedtrust.splice.sv.createAnsCollectInitialEntryPaymentConfirmation_c2ceac...` (tid 0c9a07dc)
and `8825ff69-50bf-43c7-bb7d-bc6a1d65e6ea`, `DsoRules_ExecuteConfirmedAction` (tid a21bb10a).

## 4. The 35.5 s round trip: request ordered in 2 s, confirmation responses in 31 s

```
zcat $C | grep -a '"@timestamp":"2026-11-19T17:2[45]' | grep -a a21bb10ad153d616477bfa0db17d7567 | grep -a -E '"message":"Phase [1-7][: ]|sends request with id' | grep -a -v -E 'TAPS|Initial state|Responses for' | sed -E 's/"logger_name":"[^:"]*:([^"/]*).*/[\1]/; s/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-175 | awk '{k=substr($0,14,24)} !(k in s){s[k]=1; print}'
```
```
17:24:29.489 Phase 1 started: Submitting 1 command(s) for interpretation on behalf of digital-asset-2-e1e587e7::1220ab4df269....",[participant=sv1Participant]
17:24:29.632 Phase 1 completed: Submitting 4 envelopes for Transaction request, submitters digital-asset-2-e1e587e7::1220ab4df269..., command-id 8825ff69-50bf-43c7-bb7d-bc6a1d
17:24:29.637 'PAR::sv1::1220ab4df269...' sends request with id 'dcb7ac32-b9c6-4c3b-9c23-59cf338d3a74' of size 9773 bytes with 4 envelopes to MGR=1, PAR=2.",[sequencer=globalSe
17:24:31.774 Phase 2: Registered request=1970-01-11T02:32:20.173Z from submittingParticipant=PAR::sv1::1220ab4df269... with 3 view(s).",[mediator=globalMediatorSv2]
17:24:31.777 Phase 3: Validating Transaction request=1970-01-11T02:32:20.173Z with 1 envelope(s)",[participant=aliceParticipant]
17:24:31.807 Phase 4: Sending for request=1970-01-11T02:32:20.173Z with msgId=128a17c8-3d49-4bcd-b6cb-19148a0c18d4 approved=2, rejected=0, abstained=0",[participant=aliceParti
17:24:31.814 'PAR::aliceValidator::12206a7c348a...' sends request with id '128a17c8-3d49-4bcd-b6cb-19148a0c18d4' of size 771 bytes with 1 envelopes to MGR=1.",[sequencer=globa
17:25:02.915 Phase 5: Received 2 response(s) for request=1970-01-11T02:32:20.173Z.",[mediator=globalMediatorSv2]
17:25:02.954 Phase 5: Received 3 response(s) for request=1970-01-11T02:32:20.173Z.",[mediator=globalMediatorSv2]
17:25:02.978 Phase 6: Finalized request=RequestId(1970-01-11T02:32:20.173Z) with verdict Approve at 1970-01-11T02:33:20.173Z",[mediator=globalMediatorSv2]
17:25:02.988 'MED::sv2::122014a9a786...' sends request with id 'e1723c0a-c115-4585-9a3b-5c9cd3832384' of size 773 bytes with 1 envelopes to PAR=2.",[sequencer=globalSequencerS
17:25:03.008 'MED::sv1::12207c28dead...' sends request with id 'bdf72592-8cd3-4287-b054-3e8a350fd2ea' of size 773 bytes with 1 envelopes to PAR=2.",[sequencer=globalSequencerS
17:25:03.128 'MED::sv3::122031b8b01d...' sends request with id '51ccc838-8cb0-47d4-ae89-0dea0dc14c24' of size 773 bytes with 1 envelopes to PAR=2.",[sequencer=globalSequencerS
17:25:04.562 Phase 7: Storing at offset=826 SequencedTransactionAccepted(\n  recordTime = 1970-01-11T02:32:20.173Z,\n  updateId = ..b23f,\n  transactionMeta = TransactionMeta(
17:25:14.694 'MED::sv4::12209dabde8c...' sends request with id '28864a35-6d46-4581-994a-45bb1123144b' of size 773 bytes with 1 envelopes to PAR=2.",[sequencer=globalSequencerS
```

(The awk keeps the first line per message prefix; the same Phase 2/5/6 lines exist for the other three mediators, and
sv1's own response 354dceba-d804-468b-85ed-225d536d62bb is sent at 17:24:31.870.)
The two responses sit in the ordering layer from 17:24:31.8 to 17:25:02.4. The sim clock does not move in between
(the next advance is at 17:25:14.670, section 2), so nothing times out in sim time.

## 5. Ordering layer: batch misses epoch 79, epoch 80 takes 25.5 s with a view change

The response batch is created on globalSequencerSv1 at 17:24:32.097; sv1's segment of epoch 79 has no slot left, the
batch is proposed as the first block of epoch 80 (2790), lost to the view change, and ordered in epoch 81 (block
2873).

```
zcat $C | grep -a '"@timestamp":"2026-11-19T17:2[45]' | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E '00a5\)|message ID 128a17c8' | grep -a -E 'LocalBatchCreated|Ignoring proposal|DelayedProposalResponse: providing|Starting consensus process for block 2790|DB stored pre-prepare|BFT ordering request at index 0' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-250
```
```
17:24:32.097 Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..00a5) with reference epoch 79 for batch received from local mempool containing 2 requests (tag=send, messageId=128a17c8-3d49-4bcd-b6cb-19148a0c18d4, payloadSiz
17:24:35.424 ConsensusSegment.ConsensusMessage.LocalAvailability: received proposal for block 2787 from local availability with batch IDs: List(BatchId(..00a5)). Ignoring proposal because we cannot assign more slots at the moment (reason: End of segm
17:24:36.478 Availability.DelayedProposalResponse: providing proposal with batch IDs List(BatchId(..00a5)) to local consensus",
17:24:36.481 ConsensusSegment.ConsensusMessage.LocalAvailability: received proposal for block 2790 from local availability with batch IDs: List(BatchId(..00a5)). Starting consensus process for block 2790 in epoch 80 with original leader SEQ::sv1::..1
17:24:39.762 DB stored pre-prepare w/ (epochNumber=80, blockNumber=2790) and batches List(BatchId(..00a5))",
17:25:01.866 Availability.DelayedProposalResponse: providing proposal with batch IDs List(BatchId(..00a5), BatchId(..bef5)) to local consensus",
17:25:01.897 DB stored pre-prepare w/ (epochNumber=81, blockNumber=2873) and batches List(BatchId(..00a5), BatchId(..bef5))",
17:25:02.406 BFT ordering request at index 0 in output block (epochNumber=81, blockNumber=2873) with message ID 128a17c8-3d49-4bcd-b6cb-19148a0c18d4 may change ordering topology: false",
```

Per-epoch timing on globalSequencerSv1 (seconds after the epoch start; 80 blocks per epoch on 4 nodes since epoch 59,
17:20:23, in the previous suite):

```
zcat $C | grep -a -E 'sequencer=globalSequencerSv1[/"]' | grep -a -o -E '^\{"@timestamp":"[^"]+","message":"(New epoch [0-9]+ has started|DB stored pre-prepare w/ \(epochNumber=[0-9]+, blockNumber=[0-9]+|Ordered block [0-9]+ in segment|Segment [0-9] starting at block number [0-9]+ moving from view number 0 to 1)' | sed -E 's/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /' > log/11665/sv1-epoch-events.txt
awk '{split($1,a,":"); s=a[1]*3600+a[2]*60+a[3]} /New epoch/{e=$4; st[e]=s; t0[e]=$1; fp[e]=""; fo[e]=""; vc[e]=0; next} /DB stored pre-prepare/{ if(fp[e]=="") fp[e]=s-st[e]; next} /Ordered block/{ if(fo[e]=="") fo[e]=s-st[e]; last[e]=s-st[e]; next} /moving from view/{vc[e]++} END{for(i=73;i<=84;i++) printf "%s e%d firstPrePrepareStored=%.2f firstOrdered=%.2f lastOrdered=%.2f viewChanges=%d\n", t0[i], i, fp[i], fo[i], last[i], vc[i]}' log/11665/sv1-epoch-events.txt | column -t
```
```
17:23:18.423  e73  firstPrePrepareStored=0.33  firstOrdered=0.35   lastOrdered=7.94   viewChanges=0
17:23:26.383  e74  firstPrePrepareStored=0.56  firstOrdered=0.59   lastOrdered=9.15   viewChanges=0
17:23:35.562  e75  firstPrePrepareStored=0.32  firstOrdered=0.44   lastOrdered=8.20   viewChanges=0
17:23:43.781  e76  firstPrePrepareStored=0.55  firstOrdered=0.66   lastOrdered=10.37  viewChanges=0
17:23:54.185  e77  firstPrePrepareStored=0.33  firstOrdered=0.44   lastOrdered=10.29  viewChanges=0
17:24:04.583  e78  firstPrePrepareStored=0.46  firstOrdered=0.71   lastOrdered=7.21   viewChanges=4
17:24:12.419  e79  firstPrePrepareStored=0.33  firstOrdered=2.19   lastOrdered=23.59  viewChanges=0
17:24:36.079  e80  firstPrePrepareStored=3.68  firstOrdered=20.17  lastOrdered=24.88  viewChanges=4
17:25:01.552  e81  firstPrePrepareStored=0.34  firstOrdered=0.58   lastOrdered=14.01  viewChanges=0
17:25:15.599  e82  firstPrePrepareStored=0.35  firstOrdered=0.40   lastOrdered=14.50  viewChanges=0
17:25:30.234  e83  firstPrePrepareStored=0.34  firstOrdered=0.62   lastOrdered=11.34  viewChanges=0
17:25:41.683  e84  firstPrePrepareStored=0.57  firstOrdered=0.71   lastOrdered=9.84   viewChanges=0
```

(The view-change lines are counted against the epoch that is current when they are logged; the four under e78 are
logged between the starts of epochs 78 and 79.)

Epoch 80, first block of each of the four segments: the leaders start at 17:24:36.48-36.62, the pre-prepares are
stored only at 39.76-39.80 on the leaders and 41.23-41.88 on the followers, the 5 s PBFT timer fires at 41.09 and
all segments change view.

```
zcat $C | grep -a '"@timestamp":"2026-11-19T17:24:[34]' | grep -a 'sequencer=globalSequencerSv' | grep -a -E 'Starting consensus process for block 279[0-3] |DB stored pre-prepare w/ \(epochNumber=80, blockNumber=279[0-3]\)|moving from view number 0 to 1' | sed -E 's/"logger_name":"[^:"]*:sequencer=global([A-Za-z0-9]+).*/[\1]/; s/\{"@timestamp":"2026-11-19T([^"]+)Z","message":"/\1 /' | sed -E 's/^([0-9:.]+) .*(Starting consensus process for block [0-9]+).*(\[Sequencer.*)/\1 \2 \3/; s/^([0-9:.]+) (DB stored pre-prepare) w\/ \(epochNumber=80, (blockNumber=[0-9]+)\).*(\[Sequencer.*)/\1 \2 \3 \4/; s/^([0-9:.]+) (Segment [0-9]) starting at block number ([0-9]+) moving from view number 0 to 1.*(\[Sequencer.*)/\1 \2 (\3) view 0 to 1 \4/' | sort | awk '$1<"17:24:42"'
```
```
17:24:36.481 Starting consensus process for block 2790 [SequencerSv1]
17:24:36.616 Starting consensus process for block 2791 [SequencerSv2]
17:24:36.617 Starting consensus process for block 2792 [SequencerSv3]
17:24:36.621 Starting consensus process for block 2793 [SequencerSv4]
17:24:39.762 DB stored pre-prepare blockNumber=2790 [SequencerSv1]
17:24:39.802 DB stored pre-prepare blockNumber=2791 [SequencerSv2]
17:24:39.802 DB stored pre-prepare blockNumber=2792 [SequencerSv3]
17:24:39.802 DB stored pre-prepare blockNumber=2793 [SequencerSv4]
17:24:41.091 Segment 0 (2790) view 0 to 1 [SequencerSv4]
17:24:41.091 Segment 1 (2791) view 0 to 1 [SequencerSv4]
17:24:41.091 Segment 2 (2792) view 0 to 1 [SequencerSv4]
17:24:41.091 Segment 3 (2793) view 0 to 1 [SequencerSv4]
17:24:41.096 Segment 0 (2790) view 0 to 1 [SequencerSv2]
17:24:41.096 Segment 0 (2790) view 0 to 1 [SequencerSv3]
17:24:41.096 Segment 1 (2791) view 0 to 1 [SequencerSv2]
17:24:41.096 Segment 1 (2791) view 0 to 1 [SequencerSv3]
17:24:41.096 Segment 2 (2792) view 0 to 1 [SequencerSv2]
17:24:41.096 Segment 2 (2792) view 0 to 1 [SequencerSv3]
17:24:41.096 Segment 3 (2793) view 0 to 1 [SequencerSv2]
17:24:41.096 Segment 3 (2793) view 0 to 1 [SequencerSv3]
17:24:41.097 Segment 0 (2790) view 0 to 1 [SequencerSv1]
17:24:41.097 Segment 1 (2791) view 0 to 1 [SequencerSv1]
17:24:41.097 Segment 2 (2792) view 0 to 1 [SequencerSv1]
17:24:41.097 Segment 3 (2793) view 0 to 1 [SequencerSv1]
17:24:41.228 DB stored pre-prepare blockNumber=2790 [SequencerSv2]
17:24:41.228 DB stored pre-prepare blockNumber=2790 [SequencerSv3]
17:24:41.228 DB stored pre-prepare blockNumber=2790 [SequencerSv4]
17:24:41.836 DB stored pre-prepare blockNumber=2791 [SequencerSv3]
17:24:41.836 DB stored pre-prepare blockNumber=2792 [SequencerSv1]
17:24:41.836 DB stored pre-prepare blockNumber=2792 [SequencerSv2]
17:24:41.836 DB stored pre-prepare blockNumber=2792 [SequencerSv4]
17:24:41.836 DB stored pre-prepare blockNumber=2793 [SequencerSv1]
17:24:41.856 DB stored pre-prepare blockNumber=2791 [SequencerSv4]
17:24:41.856 DB stored pre-prepare blockNumber=2793 [SequencerSv2]
17:24:41.856 DB stored pre-prepare blockNumber=2793 [SequencerSv3]
17:24:41.878 DB stored pre-prepare blockNumber=2791 [SequencerSv1]
```

The view change drops block 2790 of view 0 with the batch; sv1 proposes the batch again in its first slot of epoch 81
(block 2873, 17:25:01.866).

## 6. What was ruled out for the stalled pre-prepare writes

- Not a JVM pause: the Canton log keeps about 1700 lines/s (CurrentTime polls) in the silent seconds.
- Not Postgres as a whole: sv1Scan's incremental ACS snapshot updates (one small transaction each) stay at 1-3 ms.

```
zcat $T | grep -a '"@timestamp":"2026-11-19T17:2[345]' | grep -a -E '"logger_name":"o.l.s.s.s.AcsSnapshotStore:[^"]*scan=sv1Scan' | sed -E 's/^\{"@timestamp":"2026-11-19T([^"]+)Z","message":"(Updating|Updated) incremental snapshot.*/\1 \2/' | grep -E '^[0-9:.]+ Updat' | awk '{split($1,a,":"); s=a[1]*3600+a[2]*60+a[3]; if($2=="Updating"){st=s} else {d=s-st; b=substr($1,1,7) "0"; if(!(b in n)){ord[++k]=b}; n[b]++; sum[b]+=d; if(d>mx[b])mx[b]=d}} END{for(i=1;i<=k;i++){b=ord[i]; printf "%s n=%d avg=%.3fs max=%.3fs\n", b, n[b], sum[b]/n[b], mx[b]}}' | paste - - | column -t | sed -n 2,5p
```
```
17:23:50  n=503  avg=0.001s  max=0.005s  17:24:00  n=101  avg=0.001s  max=0.004s
17:24:10  n=112  avg=0.001s  max=0.002s  17:24:20  n=110  avg=0.001s  max=0.002s
17:24:30  n=45   avg=0.001s  max=0.003s  17:24:40  n=5    avg=0.001s  max=0.002s
17:24:50  n=8    avg=0.017s  max=0.126s  17:25:00  n=140  avg=0.001s  max=0.003s
```

- Not family BA: `Acquired lock after PT0.000046S` (section 3). Not family D or AF: no sim-clock advance while the
  command is in flight, no `LOCAL_VERDICT_TIMEOUT`, no `MAX_SEQUENCING_TIME_EXCEEDED` in the window. Not family BB:
  `Request fetch remote time has succeeded` continues on every app.
- No WARN or ERROR in either log between 17:24:02 and 17:24:40 other than the test's own `Failed clue` and
  `Test failed` lines (counted with `grep -c '"level":"(WARN|ERROR)"'` over that window: 0 in canton-simtime.clog).
- Not visible: Postgres server logs, runner node load. The four sequencers finish their writes in the same
  millisecond (17:24:39.802, 17:24:41.228), which points at something they share (their Postgres server or a shared
  executor in the one Canton JVM), not at one node.

## Verdict

- New, family BO. First hit of this test in the tracker
  (`gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "list multiple subscriptions"'` and
  `... WalletTimeBasedIntegrationTest created:>=2026-08-01` return nothing relevant). No family signature matches:
  BC (11532) is another test and clue with the same slow-window background, BA needs a held execution lock, 11375 is a
  wall-clock infra one-off. No duplicate parent.
- Flake, test timing. The default 20 s covers alice's ingestion plus two sequential sv1 round trips on the 4-node
  BFT topology the previous suite leaves behind; one round trip that lands on an epoch boundary with a view change
  takes 35 s. Needed here: 44 s from the start of the wait (17:24:20.933 to 17:25:04.99).
- Fix (test): `actAndCheck(timeUntilSuccess = 60.seconds)` on the `Accept subscription request` check, see the Fix
  section.
- Not explained: why the BFT pre-prepare writes of all four sequencers stall for 1.4-3.3 s at the start of epoch 80
  (and why epoch 79 needed 23.6 s without a view change). Third recorded slow ordering window (11375, 11532); in both
  sim-time hits the suite runs right after TimeBasedTestNetPreviewIntegrationTest on its 4-node ordering topology.
  The fix does not address it.
- Not verified: the failure was not reproduced locally (see Fix).

## Fix

Branch `pperek/fix-11665-wallet-subscription-created-wait` off origin/main 1e80ae1dea, commit 155cd7a595, worktree
`~/Projects/fix-11665`, local only, no PR. Two lines in
`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletTimeBasedIntegrationTest.scala`:

```
git -C ~/Projects/fix-11665 show 155cd7a595 --format= -U0 | grep -E '^[-+][^-+]'
```
```
+import scala.concurrent.duration.DurationInt
-            actAndCheck(
+            actAndCheck(timeUntilSuccess = 60.seconds)(
```

Run on the fix worktree (macOS, no Docker: Postgres 18.6 from the nix store, `./start-canton.sh -d -s -p external`,
Canton 3.7.4):

```
direnv exec . sbt -batch "apps-app/Test/compile"
direnv exec . sbt -batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.WalletTimeBasedIntegrationTest"
direnv exec . sbt -batch "apps-app/Test/scalafmtCheck"
```
```
[success] Total time: 387 s (0:06:27.0), completed 20 Nov 2026, 10:25:13
[info] Tests: succeeded 3, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
[success] Total time: 356 s (0:05:56.0), completed 20 Nov 2026, 10:32:53
[success] Total time: 9 s, completed 20 Nov 2026, 10:34:10
```

(One summary line per command, from the three sbt outputs.) The local run shows that the change compiles and the
suite passes; it does not exercise the slow epoch, so the 60 s budget is argued from the CI timeline (44 s needed),
not tested against it. `sbt checkErrors` was not run. Canton and Postgres were stopped afterwards.
