# 11258 - simtime (2): sv3/sv4 init hangs, the DSO participants' ledger APIs freeze behind a confirmation result that was never delivered (run 42253265180)

New (family B sibling: blacklisting during BFT sequencer onboarding). main d8537ea52b, after the DA BFT switch
(splice#8234). Two suites of the shard aborted in setup (`SvAppReference.waitForInitialization`, 5 min): sv3 and sv4
never finished `JoiningNodeInitializer joining Dso with key`. Mechanism: while sv3 and sv4 sequencers were onboarded,
the sv4 sequencer was blacklisted; sv4's mediator's verdict for a DSO transaction (request 05:15:10.583Z, submitted by
sv4's participant) was refused by that sequencer, sv1's mediator delayed its send by 3000 ms and never sent it, so
the verdict aggregation (threshold 2, effective from the next microsecond) never completed and no result was delivered. All four SV participants host the DSO party: each
kept that request pending until its decision time 05:16:10.583Z, which the sim clock never reached (it stayed below
05:15:14 for the rest of the log), and their record order publishers held back every later event. The indexers of
sv1-sv4 participants stopped at 17:02:57.99 and never resumed; sv3's own submission was committed in Canton at
17:03:17 but its completion never reached the ledger API (DEADLINE_EXCEEDED after 120 s), and the retries then failed
forever on the archived SvNodeState that the stale DSO store still showed. alice/bob/splitwell participants (not
informees) kept indexing.

- Run: https://github.com/canton-network/splice/actions/runs/42253265180, main d8537ea52b ("Shallow `split_tests`
  checkout and report git size per CI job (#7931)"), job 128014075815 `simtime (2)`, attempt 1.
- Runtime canton: 3.7.2 (`nix/canton-sources.json` at d8537ea52b).
- Component: Canton (mediator verdict send not retried after a refusal; blacklisting) exposed by sim time (no
  participant-side timeout); splice test config (splice#8356 disables blacklisting).

## 1. Failing suites

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11258/job.log | sed -E 's/^[^Z]*Z //' | grep -a -A6 'ABORTED' | cut -c1-200 | head -8
```
```
[info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
--
```
Same for WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest. Artifact: `gh run download 42253265180 --repo
canton-network/splice -n logs-simtime-2 -D log/11258/logs-simtime-2` (canton_network_test.clog.gz, canton-simtime.clog.gz).

## 2. Suite timeline

```
zcat canton_network_test.clog.gz | jq -r 'select(.message|test("^(Starting test suite|Test (succeeded|failed)|Failed creating|Finished creating environment: (Starting all nodes|Running setup))")?) | "\(.["@timestamp"]) \(.message[0:110])"' | tail -8
```
```
2026-11-06T16:58:14.966Z Starting test suite 'ScanWithGradualStartsTimeBasedIntegrationTest'...
2026-11-06T17:01:12.727Z Test succeeded: 'ScanWithGradualStartsTimeBasedIntegrationTest/initialize a scan app that joins late'
2026-11-06T17:01:12.736Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...
2026-11-06T17:01:49.230Z Finished creating environment: Starting all nodes
2026-11-06T17:06:49.336Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)
2026-11-06T17:06:49.549Z Starting test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'...
2026-11-06T17:07:06.424Z Finished creating environment: Starting all nodes
2026-11-06T17:12:06.510Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)
```
Earlier suites ran only sv1 on the global synchronizer (epochs logged only by globalSequencerSv1 before 16:59);
ScanWithGradualStarts onboarded sv2 (16:59), SvExpiredRewardsCollection onboarded sv3 and sv4 (17:03), section 6.

## 3. Which apps did not initialize

```
zcat canton_network_test.clog.gz | jq -r 'select(.["@timestamp"] >= "2026-11-06T17:01:13" and .["@timestamp"] < "2026-11-06T17:06:50") | select(.message|test("app initialization: .*(finished|failed)")?) | "\(.["@timestamp"]) \(.message[0:110])"' | grep -v 'create ledger client\|Wait for user' | tail -14
```
```
2026-11-06T17:02:21.510Z sv2 app initialization: Initialize app finished after PT48.63068665S
2026-11-06T17:02:24.993Z sv2Validator app initialization: Initialize app finished after PT51.395576656S
2026-11-06T17:02:49.445Z sv4Scan app initialization: Initialize app finished after PT1M16.252278981S
2026-11-06T17:02:53.650Z sv3Scan app initialization: Initialize app finished after PT1M19.652730014S
2026-11-06T17:06:49.366Z sv4Validator app initialization: Initialize app failed
2026-11-06T17:06:49.405Z sv3Validator app initialization: Initialize app failed
2026-11-06T17:06:49.484Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-06T17:06:49.492Z sv4 app initialization: JoiningNodeInitializer joining Dso with key failed
```
(the failures at 17:06:49 are the shutdown, "Ignoring initialization failure, we are actually shutting down").

## 4. sv3 and sv4: the last init step is `reconcileSynchronizerNodeConfigIfRequired(OnboardedAfterDelay)`

sv3 passed `SV nodes have been granted unlimited traffic` at 17:03:15.864, then submitted
`DsoRules_SetSynchronizerNodeConfig` (`SynchronizerNodeReconciler`, JoiningNodeInitializer.scala at d8537ea52b lines
530-552: `waitForSvToObtainUnlimitedTraffic`, then `reconcileSynchronizerNodeConfigIfRequired(..., OnboardedAfterDelay)`).

```
grep ' sv3 SynchronizerNodeReconciler ' /tmp/claude-1000/sv34.txt | head -6   # sv3/sv4 lines extracted from canton_network_test.clog.gz, 17:01:40-17:06:50
```
```
2026-11-06T17:03:15.964Z INFO sv3 SynchronizerNodeReconciler | Setting domain node config to Record(   fields = Seq(     Record(fields = Seq(GenMap(), List(), List())),     None(),     Some(Record(fields = "MED::sv3::12209c52abb1...
2026-11-06T17:05:15.966Z INFO sv3 SynchronizerNodeReconciler | The operation 'setting domain config for digital-asset-eng-3-6b07c8f8::122012dad2d0...' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999769803s. ...
2026-11-06T17:05:16.202Z DEBUG sv3 SynchronizerNodeReconciler | Retrying on same error kind transient error (request infinite retries) for StatusRuntimeException/FAILED_PRECONDITION: UNKNOWN_CONTRACT_SYNCHRONIZERS(9,d8f3d048): The following contracts have been archived: List(00a687cc93dce31ecc...
```
sv4: same, first submission 17:03:09.029, DEADLINE_EXCEEDED 17:05:09.031, then 31 UNKNOWN_CONTRACT_SYNCHRONIZERS
retries until 17:06:49.205 (sv3: 27 until 17:06:47.942):
```
zcat canton_network_test.clog.gz | jq -r 'select(.["@timestamp"] >= "2026-11-06T17:03:00" and .["@timestamp"] < "2026-11-06T17:06:50") | select(.logger_name|test("SynchronizerNodeReconciler.*SV=sv[34]$")?) | select(.level=="DEBUG" and (.message|test("UNKNOWN_CONTRACT"))) | "\(.["@timestamp"]) \(.logger_name|sub(".*SV=";""))"' | awk '{c[$2]++; l[$2]=$1} END{for(k in c) print k, c[k], l[k]}'
```
```
sv4 31 2026-11-06T17:06:49.205Z
sv3 27 2026-11-06T17:06:47.942Z
```
The archived contract is sv3's SvNodeState, created at 17:02:56; sv3's DSO store ingested its create and nothing
after:
```
zcat canton_network_test.clog.gz | jq -r 'select(.logger_name|test("SV=sv3/store=dsoParty$")?) | select(.message|contains("00a687cc93dce31ecc")) | ...'   # offset, template
zcat canton_network_test.clog.gz | jq -r 'select(.["@timestamp"] >= "2026-11-06T17:02:50" and .["@timestamp"] < "2026-11-06T17:07:00") | select(.logger_name|test("SV=sv3/store=dsoParty$")?) | select(.message|test("^Ingested")) | ...' | tail -2
```
```
2026-11-06T17:02:56.406Z offset = 189 ingestedCreatedEvents = CreatedEvent( contractId = 00a687cc93dc templateId = 0ff9141699e5...:Splice.DSO.SvState:SvNodeState ingestedArchivedEvents = ExercisedEvent( contractId = 000f4b2cc4e8 templateId = 0ff9141699e5...:Splice.DSO.SvState:SvNodeState ) )
2026-11-06T17:02:55.680Z 187 rt=1970-01-01T05:15:10.483Z
2026-11-06T17:02:56.406Z 189 rt=1970-01-01T05:15:10.485003Z
```
`setConfigIfRequired` re-reads `dsoStore.getDsoRulesWithSvNodeState` on every retry (SynchronizerNodeReconciler.scala
at d8537ea52b, lines 60-64 and 175-182), so the retries failed because the store itself was stale.

## 5. The sv3 submission was committed in Canton; the ledger API never saw it

```
zcat canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] >= "2026-11-06T17:03:15") | select(.message|contains("adf7660c-5ac5-46e8-86fd-b6b031858d02")) | ...' | head
```
```
17:03:16.030 DEBUG participant=sv3Participant | Sending message ID adf7660c-5ac5-46e8-86fd-b6b031858d02 to sequencer SEQ::sv1::1220544a03de...
17:03:16.371 DEBUG sequencer=globalSequencerSv2 | At block 2997, the submission request adf7660c-5ac5-46e8-86fd-b6b031858d02 at 1970-01-01T05:15:10.619001Z validated to: Deliver
```
```
17:03:16.823 INFO mediator=globalMediatorSv1 ConfirmationRequestAndResponseProcessor | Phase 6: Finalized request=RequestId(1970-01-01T05:15:10.619001Z) with verdict Approve at 1970-01-01T05:16:10.619001Z
17:03:17.195 DEBUG participant=sv3Participant TransactionProcessor | About to wrap up request RequestId(1970-01-01T05:15:10.619001Z) with event Some(   SequencedTransactionAccepted(     recordTime = 1970-01-01T05:15:10.619001Z, ...
17:03:17.198 DEBUG participant=sv3Participant RecordOrderPublisher | Publishing event for record time 1970-01-01T05:15:10.619001Z and sequencer counter 124. ...
```
No participant indexer stored anything after 17:02:57.99 (the SV participants) while alice/bob/splitwell kept going:
```
zcat canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] >= "2026-11-06T17:02:00" and .["@timestamp"] < "2026-11-06T17:13:00") | select(.logger_name|test("ParallelIndexerSubscription")?) | "\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<n>participant=[A-Za-z0-9]+)").n) | \(.message[0:120])"' | awk '{l[$2]=$0} END{for(k in l) print l[k]}' | sort
```
```
17:02:57.993 participant=sv1Participant | Post Processing end updated in IndexDB, updateOffset -> 2204.
17:02:57.994 participant=sv2Participant | Post Processing end updated in IndexDB, updateOffset -> 911.
17:02:57.996 participant=sv3Participant | Post Processing end updated in IndexDB, updateOffset -> 211.
17:02:57.999 participant=sv4Participant | Post Processing end updated in IndexDB, updateOffset -> 215.
17:07:08.048 participant=aliceParticipant | Post Processing end updated in IndexDB, updateOffset -> 1052.
17:07:08.048 participant=splitwellParticipant | Post Processing end updated in IndexDB, updateOffset -> 1029.
17:07:08.049 participant=bobParticipant | Post Processing end updated in IndexDB, updateOffset -> 905.
```
`Phase 7: Storing` per minute over the whole log (all participants) ends at 17:02:
```
zcat canton-simtime.clog.gz | jq -r 'select(.message|contains("Phase 7: Storing")?) | .["@timestamp"][11:16]' | uniq -c | awk '{printf "%s=%s ", $2, $1}'
```
```
16:46=16 16:47=44 ... 16:59=108 17:00=219 17:01=14 17:02=75
```

## 6. The blocking request: 05:15:10.583Z, the first event after a 12 s ordering gap

sv1's participant: the last published event before the gap is sc 2062 (05:15:10.501001Z, 17:02:57.978); the next
event, sc 2063 at 05:15:10.583Z, is a transaction request (request counter 561), submitted by sv4's participant at
17:03:09 (message 91080a10-...). Everything after it is "Publishing" but never stored:
```
zcat canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] >= "2026-11-06T17:02:57.9" and .["@timestamp"] < "2026-11-06T17:03:20") | select(.logger_name|test("participant=sv1Participant")?) | select(.message|test("sc=20(6[2-9])|counter 20(6[2-9])")) | ...'
```
```
17:02:57.978 RecordOrderPublisher | Publishing event for record time 1970-01-01T05:15:10.501001Z and sequencer counter 2062. ...
17:03:10.512 ParallelMessageDispatcher | Processing event at sc=2063, ts=1970-01-01T05:15:10.583Z, with contents=Seq(   RootHashMessage(...), EncryptedViewMessage(...
17:03:10.514 RequestCounterAllocatorImpl | Allocating request counter 561 to sequencer counter 2063
17:03:10.664 RecordOrderPublisher | Publishing event for record time 1970-01-01T05:15:10.583003Z and sequencer counter 2066. ...
17:03:11.607 RecordOrderPublisher | Publishing event for record time 1970-01-01T05:15:10.583001Z and sequencer counter 2064. (event:SequencedTransactionAccepted(...
```
The ordering gap 17:02:58-17:03:10 is the sv3/sv4 BFT onboarding (view changes; epoch 125 blacklists sv4):
```
zcat canton-simtime.clog.gz | jq -r '... sequencer=globalSequencerSv[14] ... ^New epoch|blacklisted, rejecting|^DB stored view change ...'
```
```
17:02:57.982 sequencer=globalSequencerSv1 New epoch 124 has started with leaders = List(SEQ::sv1::.., SEQ::sv2::..
17:03:03.000 sequencer=globalSequencerSv1 DB stored view change for view 1 and segment at block 2880
17:03:06.944 sequencer=globalSequencerSv4 DB stored view change for view 1 and segment at block 2883
2026-11-06T17:03:09.982Z New epoch 125 has started with leaders = List(SEQ::sv3::.., SEQ::sv1::.., SEQ::sv2::..)and blacklisted nodes = List(SEQ::sv4::..); ordering topology = OrderingTopology(   activationTime = 1970-01-01T05:15:10.501001Z,   size = 4,   weakQuorum = 2,   strongQuorum = 3, ...
```
globalSequencerSv4 rejected 75 client submissions as blacklisted, 17:03:10.918 to 17:03:50.551.

## 7. Why the result for 05:15:10.583Z was never delivered

All four mediators approved, but the verdict needed two mediator senders: the MediatorSynchronizerState with
threshold 2 (serial 4, sv3's mediator added) became effective at 05:15:10.583003Z, right after the request; before,
serial 3 had threshold 1 (sv1, sv2, sv4). On sv1's participant (proposals have `proposal` in the line):
```
zcat canton-simtime.clog.gz | jq -c '... 17:02:56-17:03:12 ... ^Persisted topology ... MediatorSynchronizerState ...' > log/11258/medstate.jsonl
jq -r 'select(.n=="participant=sv1Participant") | "\(.t[11:23]) \(.m|.[0:60]) ... \(.m|[scan("proposal|serial = [0-9]+|threshold = [0-9]+")]|join(","))"' log/11258/medstate.jsonl
```
```
17:02:57.285 Persisted topology transactions (SequencedTime(1970-01-01T05:15:10.497Z), ... threshold = 1,serial = 3
17:02:57.947 Persisted topology transactions (SequencedTime(1970-01-01T05:15:10.501Z), ... threshold = 2,serial = 4,proposal
17:03:10.632 Persisted topology transactions (SequencedTime(1970-01-01T05:15:10.583002Z), ... threshold = 2,serial = 4,proposal
17:03:10.661 Persisted topology transactions (SequencedTime(1970-01-01T05:15:10.583003Z), ... threshold = 2,serial = 4
```
sv2's verdict for the request was sequenced as a receipt only (aggregation incomplete, not delivered):
```
zcat canton-simtime.clog.gz | jq -r '... sequencer=globalSequencerSv2 ... contains("2d47e36b-30ee-4181-a53e-b8cbac580c44") ...'
```
```
17:03:10.929 mediator=globalMediatorSv2 Sending message ID 2d47e36b-30ee-4181-a53e-b8cbac580c44 to sequencer SEQ::sv2::12203fe09ccf...
17:03:11.206 At block 2968, the submission request 2d47e36b-30ee-4181-a53e-b8cbac580c44 at 1970-01-01T05:15:10.590Z validated to: DeliverReceipt
```
sv4's mediator's send was refused by its blacklisted sequencer (three attempts within 33 ms, then ERROR), sv1's was
delayed:
```
zcat canton-simtime.clog.gz | jq -r '... mediator=globalMediatorSv4 DefaultVerdictSender ERROR ...'
zcat canton-simtime.clog.gz | jq -r '... select(.message|test("Delaying sending")) ...'
zcat canton-simtime.clog.gz | jq -r '... mediator=globalMediatorSv[12] DefaultVerdictSender ...' | head -3
```
```
2026-11-06T17:03:10.914Z mediator=globalMediatorSv1 Phase 6: Finalized request=RequestId(1970-01-01T05:15:10.583Z) with verdict Approve at 1970-01-01T05:16:10.583Z
2026-11-06T17:03:10.916Z mediator=globalMediatorSv1 Delaying sending of verdict by 3000 ms
2026-11-06T17:03:10.967Z Failed to send result to sequencer for request 1970-01-01T05:15:10.583Z RequestRefused(   SendAsyncErrorGrpc(     Request failed for server-DefaultSequencer-0.   GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,a50f1769): Mempool received client request but this node is currently blacklisted, rejecting   Request: send-...
17:03:11.242 DEBUG mediator=globalMediatorSv2 | Sent result for request 1970-01-01T05:15:10.583Z
```
No "Sent result for request 1970-01-01T05:15:10.583Z" from sv1's mediator in the rest of the log (to 17:12:15).
No participant ever got the result, unlike the next request:
```
zcat canton-simtime.clog.gz | jq -r '... select(.message|test("Got result for Transaction request at RequestId\\(1970-01-01T05:15:10\\.583Z\\)")) ...' | wc -l
zcat canton-simtime.clog.gz | jq -r '... ...583001Z ...' | wc -l
zcat canton-simtime.clog.gz | jq -r '... select(.message|test("timeout-result has not completed")) ...'
```
```
0
4
17:03:20.849 participant=sv1Participant timeout-result has not completed after 10308 milliseconds
17:03:20.849 participant=sv3Participant timeout-result has not completed after 10320 milliseconds
17:03:20.849 participant=sv4Participant timeout-result has not completed after 10326 milliseconds
17:03:20.849 participant=sv2Participant timeout-result has not completed after 10339 milliseconds
```
The participants' fallback is the decision time 05:16:10.583Z (sim). The sim clock never got there (BFT block time on
globalSequencerSv1 from 17:03 to the end of the log):
```
zcat canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] >= "2026-11-06T17:03:00") | select(.logger_name|test("sequencer=globalSequencerSv1")?) | select(.message|test("^Delivering block")) | ...' | awk 'NR==1{print} {l=$0} END{print l}'
```
```
17:03:07 1970-01-01T05:15:10.502Z
17:12:15 1970-01-01T05:15:13.841Z
```
In a wall-clock test the participants would have timed the request out after 60 s and moved on.

## 8. Duplicate check

- known-families.md: B (BFT 1 -> N onboarding, blacklisting) is the context, but no entry has a verdict refused by
  a blacklisted sequencer freezing the participants. Grep for `verdict|aggregat|Failed to send result`: only Z
  (ScanPartyMonitorScript, unrelated) and 10495 (offboarded mediator's aggregated send, different).
- Search `"Failed to send result to sequencer"`: 8041 (closed 2026-05-20, "Mediator verdict gets rejected by
  sequencer due to recently onboarded sequencer being unknown", same class: other mediators' verdict refused during
  sequencer onboarding, participants wait for the timeout; closed after PV 35); 5577 (closed 2025-09, sender MED
  unknown); 10180, 10046, 10111, 7925, 7623, 7626 (other failures that mention the line).
- Search the two suite names: no open issue; 10473, 10184, 8106, 10140 ... are unrelated older failures.
- Run history: 4 of 7 post-merge main runs since splice#8234 failed (11241, 11242, 11248, 11255-11257, 11258);
  their mechanisms are triaged separately.

## Verdict

New, a consequence of blacklisting during the sv3/sv4 BFT sequencer onboarding (the once-per-Canton-instance
episode of splice#8361), made fatal by sim time. One verdict send refused by the blacklisted sv4 sequencer plus sv1's
mediator holding its send back left the result of a DSO transaction undelivered; in sim time the participants never
reach the decision-time timeout, so the ledger APIs of all four SV participants froze for the rest of the shard and
sv3/sv4 could not finish onboarding.

- splice#8356 (no blacklisting in tests) removes the refusal; with sv4's send accepted, sv2 + sv4 reach the threshold
  of 2. Likely fixes this instance.
- Canton (DA BFT / mediator team): a refused verdict send is logged as ERROR and not retried, and the delayed sender
  did not send; in sim time nothing recovers. Worth reporting with this log (8041 was the same class before PV 35).
- Not verified: why sv1's mediator never sent after its 3000 ms delay (the delay may run on the sim clock; not checked
  in the jar).
