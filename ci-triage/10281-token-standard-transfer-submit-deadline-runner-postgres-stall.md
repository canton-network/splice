# 10281 - TokenStandardTransferIntegrationTest "support create, list, accept, reject and withdraw": third createTokenStandardTransfer times out after 10 s on aliceParticipant's SubmitAndWaitForTransaction during a server-wide Postgres write stall (run 37325889948)

New ref; same class as 10276 (runner Postgres stall, server-wide), here surfacing as a hard test failure instead of a
checkErrors WARN. main 9e1c06863e, job 111816892945 `ci / scala_test_wall_clock_time / wall-clock-time (0)`, canton
3.6.0-snapshot.20261001.20345.0.v85a9270a (the run predates the 3.6.1 bump #7635). 34 tests passed, 1 failed;
checkErrors was skipped because the test step failed. The test creates four token-standard transfer offers in a row;
#1 and #2 took 3.3 s and 2.1 s, #3 (sent 14:59:59.025) hit the 10 s client deadline on its
`SubmitAndWaitForTransaction` to aliceParticipant at 15:00:09.034 and the wallet returned HTTP 500. That deadline is
test-only: the integration-test config sets the wallet treasury's `grpcDeadline` to 10 s, production has none
(section 10). The transaction
itself was approved by the mediators at 15:00:05.219 and committed on aliceParticipant at 15:00:09.231, 197 ms after
the deadline. Every hop of the confirmation round trip was slow because Postgres writes stalled for 1.2-1.4 s at a
time across all canton nodes: seven nodes with their own connection pools finished one sequenced-event write within
4 ms of each other at 15:00:09.212-09.216 (and again at 15:00:05.128-05.168 and 15:00:11.186-11.189). Not family B
(no ordering-topology change, no blacklisting, no mempool rejections) and not family L (no `insert block` retries).
Flake, infra cause, failing a test-only deadline; no fix branch.

- Run: https://github.com/canton-network/splice/actions/runs/37325889948, main 9e1c06863e ("make cache actions
  compatible w/ custom runners (#7273)"), job 111816892945 `ci / scala_test_wall_clock_time / wall-clock-time (0)`.
  Only failed job in the run.
- Runtime canton: 3.6.0-snapshot.20261001.20345.0.v85a9270a (`git show 9e1c06863e:nix/canton-sources.json`).
- Component: infra (Postgres service shared by all canton nodes); surfaced through the wallet treasury's test-only
  10 s submission deadline (`treasury.grpcDeadline`, set by `ConfigTransforms.setDefaultGrpcDeadlineForTreasuryService`
  in `ConfigTransforms.defaults`; default `None` in production; observed 9.999877156 s). See section 10.
- Artifact: `logs-wall-clock-time-0` in `log/10281/logs-wall-clock-time-0/`. In the commands below
  `T=log/10281/logs-wall-clock-time-0/canton_network_test.clog.gz`, `C=log/10281/logs-wall-clock-time-0/canton.clog.gz`,
  and `F` is the generic line formatter from the skill recipes (section 4).

## 1. Classification: one FAILED test, canton pin

```
$ gh api repos/canton-network/splice/actions/jobs/111816892945 --jq '"\(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)"'
failure 2026-10-05T14:37:23Z 2026-10-05T15:03:55Z self-hosted-k8s-large-4p78d-runner-gthlx

$ sed -E 's/\x1b\[[0-9;]*m//g' log/10281/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|contains problems|Check logs for errors;id' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-120
##[start-action display=Check logs for errors;id=__self.__self_8]
[info] *** 1 TEST FAILED ***
[info] - should support create, list, accept, reject and withdraw *** FAILED ***
[info] - support create, list, accept, reject and withdraw *** FAILED ***
[info] Tests: succeeded 34, failed 1, canceled 0, ignored 0, pending 0

$ git show 9e1c06863e:nix/canton-sources.json | grep -m1 '"version"'; git merge-base --is-ancestor 8bf7f8c034 9e1c06863e && echo 'has #7635 (3.6.1)' || echo 'without #7635 (3.6.1)'
  "version": "3.6.0-snapshot.20261001.20345.0.v85a9270a",
without #7635 (3.6.1)
```

## 2. The failing step: the third "(act) Alice creates transfer offer" takes 10.011 s and returns 500

`TokenStandardTransferIntegrationTest.scala:77-95` at 9e1c06863e creates four offers in a row with `actAndCheck`; the
call has no retry.

```
$ zcat $T | grep -a -E "Starting '|Test (succeeded|failed): " | grep -a 'T1[45]:[05][09]' | grep -a 'support create' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-230
2026-10-05T14:59:36.968Z Starting 'TokenStandardTransferIntegrationTest/Token Standard Transfers should should support create, list, accept, reject and withdraw'...",
2026-10-05T15:00:09.042Z Test failed: 'TokenStandardTransferIntegrationTest/Token Standard Transfers should should support create, list, accept, reject and withdraw', message: Command execution failed., location: SeeStackDepthExce

$ zcat $T | grep -a -E 'clue: .*(Alice creates transfer offer|Alice and Bob see it)' | grep -a 'T14:59\|T15:00:0' | sed -E "$F" | cut -c1-110
2026-10-05T14:59:53.571Z Running clue: (act) Alice creates transfer offer",[o.l.s.i.t.TokenStandardTransferInt
2026-10-05T14:59:56.885Z Finished clue: (act) Alice creates transfer offer",[o.l.s.i.t.TokenStandardTransferIn
2026-10-05T14:59:56.886Z Running clue: (check) Alice and Bob see it",[o.l.s.i.t.TokenStandardTransferIntegrati
2026-10-05T14:59:56.903Z Finished clue: (check) Alice and Bob see it",[o.l.s.i.t.TokenStandardTransferIntegrat
2026-10-05T14:59:56.903Z Running clue: (act) Alice creates transfer offer",[o.l.s.i.t.TokenStandardTransferInt
2026-10-05T14:59:59.010Z Finished clue: (act) Alice creates transfer offer",[o.l.s.i.t.TokenStandardTransferIn
2026-10-05T14:59:59.011Z Running clue: (check) Alice and Bob see it",[o.l.s.i.t.TokenStandardTransferIntegrati
2026-10-05T14:59:59.023Z Finished clue: (check) Alice and Bob see it",[o.l.s.i.t.TokenStandardTransferIntegrat
2026-10-05T14:59:59.023Z Running clue: (act) Alice creates transfer offer",[o.l.s.i.t.TokenStandardTransferInt
2026-10-05T15:00:09.036Z Failed clue: (act) Alice creates transfer offer",[o.l.s.i.t.TokenStandardTransferInte

$ zcat $T | grep -a 'db587c0f9824b36c01cc7365abb8c8cc' | grep -a -E 'received request|Executing token standard|Responding with status|took [0-9]+ ms' | sed -E "$F" | cut -c1-200
2026-10-05T14:59:59.025Z HTTP POST /api/validator/v0/wallet/token-standard/transfers from (127.0.0.1:46690): received request.",[o.l.s.a.a.HttpRequestLogger:TokenStandardTransferIntegrationTest/config
2026-10-05T14:59:59.027Z Executing token standard operation TokenStandardTransferOperationV1(\n  from = tid:db587c0f9824b36c01cc7365abb8c8cc,\n  receiver = bob__wallet__user-1cc452b__tc0::1220bf835877
2026-10-05T15:00:09.035Z HTTP POST /api/validator/v0/wallet/token-standard/transfers from (127.0.0.1:46690): Responding with status code: 500 Internal Server Error",[o.l.s.a.a.HttpRequestLogger:TokenS
2026-10-05T15:00:09.035Z HTTP client (POST /api/validator/v0/wallet/token...): HTTP request took 10011 ms to complete. Received response with status code: 500 Internal Server Error",[o.l.s.i.Environme
```

## 3. The ledger command: sequenced at once, approved at 15:00:05.219, committed 197 ms after the deadline

The validator submits `SubmitAndWaitForTransaction` (trace 255bf9cf...) to aliceParticipant (:5501) at 14:59:59.035.
The confirmation request is sequenced in block 15293 at 14:59:59.078079, alice's confirmation response at
15:00:02.918840 (block 15305), the mediator verdict at 15:00:05.242372 (block 15318). aliceParticipant only gets to
process the verdict at 15:00:09.221, after the ledger API call was cancelled at 15:00:09.034.

```
$ zcat $T | grep -a -E 'T14:59:59\.03[45]' | grep -a 'SubmitAndWaitForTransaction to 127.0.0.1:5501: sending' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(Request \(tid:[0-9a-f]+\) [^ ]+ to [0-9.:]+: sending request).*/\1 \2/'
2026-10-05T14:59:59.035Z Request (tid:255bf9cfa4760f36245c07cf1d905dbb) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5501: sending request

$ zcat $C | grep -a 255bf9cfa4760f36245c07cf1d905dbb > log/10281/tid-submit.jsonl; sed -E "$F" log/10281/tid-submit.jsonl | grep -a -E 'participant=aliceParticipant' | grep -a -E 'Phase 1 completed|Processing event at sc=|Phase 4: Sending|Got result|Finalizing Transaction|REQUEST_TIME_OUT|SubmitAndWaitForTransaction by .*: cancelled' | cut -c1-150
2026-10-05T14:59:59.059Z Phase 1 completed: Submitting 4 envelopes for Transaction request, submitters alice__wallet__user-1cc452b__tc0::12208608ad0e.
2026-10-05T15:00:00.720Z Processing event at sc=1410, ts=2026-10-05T14:59:59.078079Z, messageId=12a50ec3-03da-42d4-834f-1ff26a3eb958, with contents=Se
2026-10-05T15:00:00.792Z Phase 4: Sending for request=2026-10-05T14:59:59.078079Z with msgId=d5b16403-1e2b-43be-bdd1-fc717352cf0e approved=2, rejected
2026-10-05T15:00:05.219Z Processing event at sc=1413, ts=2026-10-05T15:00:02.918840Z, messageId=d5b16403-1e2b-43be-bdd1-fc717352cf0e, with contents=Se
2026-10-05T15:00:09.034Z REQUEST_TIME_OUT(3,afd3ba73): Timed out while awaiting for a completion corresponding to a command submission with command-id
2026-10-05T15:00:09.034Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:41984: cancelled",[c.d.c.l.a.Api
2026-10-05T15:00:09.034Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:41984: failed with DEADLINE_EXCE
2026-10-05T15:00:09.221Z Processing event at sc=1416, ts=2026-10-05T15:00:05.242372Z, with contents=SignedProtocolMessage(\n  ConfirmationResultMessag
2026-10-05T15:00:09.221Z Got result for Transaction request at RequestId(2026-10-05T14:59:59.078079Z): OpenEnvelope(\n  SignedProtocolMessage(\n    Co
2026-10-05T15:00:09.231Z Finalizing Transaction request=2026-10-05T14:59:59.078079Z.",[c.d.c.p.p.TransactionProcessor:participant=aliceParticipant/psi

$ sed -E "$F" log/10281/tid-submit.jsonl | grep -a -E 'At block [0-9]+, the submission request (12a50ec3|d5b16403|65171ed1)|Phase 6: Finalized' | awk '!s[substr($0,25,80)]++' | cut -c1-175
2026-10-05T14:59:59.628Z At block 15293, the submission request 12a50ec3-03da-42d4-834f-1ff26a3eb958 at 2026-10-05T14:59:59.078079Z validated to: Deliver",[c.d.c.s.b.u.Sequenc
2026-10-05T15:00:03.902Z At block 15305, the submission request d5b16403-1e2b-43be-bdd1-fc717352cf0e at 2026-10-05T15:00:02.918840Z validated to: Deliver",[c.d.c.s.b.u.Sequenc
2026-10-05T15:00:05.219Z Phase 6: Finalized request=RequestId(2026-10-05T14:59:59.078079Z) with verdict Approve at 2026-10-05T15:00:59.078079Z",[c.d.c.s.m.ConfirmationRequestA
2026-10-05T15:00:06.777Z At block 15318, the submission request 65171ed1-f30d-478a-b236-1b45765496be at 2026-10-05T15:00:05.242372Z validated to: Deliver",[c.d.c.s.b.u.Sequenc
```

Per hop: request sent 14:59:59.060, ordered 14:59:59.627 (0.6 s); alice's processing of sc=1410 starts
15:00:00.720 (1.06 s after the event was stored, see section 4); response sent 15:00:00.792, sequenced 15:00:02.918
(2.1 s); verdict 15:00:05.219, sequenced 15:00:05.242, processed on alice 15:00:09.221 (4.0 s, see section 6).

## 4. A second request from aliceParticipant in the same block, processed first, was slow in phases 1 and 3

The validator's traffic top-up (`CO_BuyMemberTraffic`, a `WalletAppInstall_ExecuteBatch`, trace e46ba22b...) was
submitted 1.07 s before the transfer and sequenced 1 us before it (sc=1409). Its phase 1 (encryption) took 1.015 s
and its phase 3 1.06 s; aliceParticipant processes sc=1410 after it. It also timed out after 10 s.

```
$ zcat $C | grep -a e46ba22bcd731cc8723a8737470e28a8 | grep -a 'participant=aliceParticipant' | sed -E "$F" | grep -a -E 'Submitted commands are|Generated requestUuid|Phase 1 completed|Phase 3: Validating|Added to the request tracker|Phase 4: Sending|REQUEST_TIME_OUT|Task scheduler waits' | cut -c1-200
2026-10-05T14:59:57.972Z Submitted commands are: 'exercise @Splice.Wallet.Install:WalletAppInstall WalletAppInstall_ExecuteBatch'",[c.d.c.p.a.s.c.CommandSubmissionServiceImpl:participant=aliceParticip
2026-10-05T14:59:57.995Z Generated requestUuid=426b5529-b4e0-4bf2-a003-3003079bc2b4",[c.d.c.p.p.TransactionProcessingSteps:participant=aliceParticipant/psid=global-domain::1220729bafde::36-0] DEBUG
2026-10-05T14:59:59.010Z Phase 1 completed: Submitting 4 envelopes for Transaction request, submitters alice-validator1cc452b-1::12208608ad0e..., command-id b4288910-02f3-46b4-8d6a-a47f89d9c755",[c.d.
2026-10-05T14:59:59.660Z Phase 3: Validating Transaction request=2026-10-05T14:59:59.078078Z with 2 envelope(s)",[c.d.c.p.p.TransactionProcessor:participant=aliceParticipant/psid=global-domain::122072
2026-10-05T15:00:00.720Z Request 234: Added to the request tracker as a new request with timestamp 2026-10-05T14:59:59.078078Z",[c.d.c.p.p.c.NaiveRequestTracker:participant=aliceParticipant/psid=globa
2026-10-05T15:00:00.790Z Phase 4: Sending for request=2026-10-05T14:59:59.078078Z with msgId=6a835222-cd02-4c6f-941f-55c785337ed7 approved=5, rejected=0, abstained=0",[c.d.c.p.p.TransactionProcessor:p
2026-10-05T15:00:07.972Z REQUEST_TIME_OUT(3,4100540c): Timed out while awaiting for a completion corresponding to a command submission with command-id=b4288910-02f3-46b4-8d6a-a47f89d9c755 and submissi
2026-10-05T15:00:07.972Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:41984: failed with DEADLINE_EXCEEDED/REQUEST_TIME_OUT(3,4100540c): Timed out while
2026-10-05T15:00:07.972Z Task scheduler waits for tick of sc=1409. The tick with sc=1408 occurred at 2026-10-05T14:59:57.970587Z. Blocked trace ids: 50f930fa582f5267cc3d569f405c2378, 11ae2bb1003a3686b

$ zcat $T | grep -a e46ba22bcd731cc8723a8737470e28a8 | grep -a -E 'CommandService/SubmitAndWait' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Request \(tid:[0-9a-f]+\) [^ ]+ to ([0-9.:]+): (sending request|failed with [A-Z_]+).*/\1 \2 \3/'; zcat $T | grep -a 'T14:59:57.964Z' | grep -a -m1 'Received operation (queue size before adding this: 0): AmuletOperation' | grep -aoE 'CO_[A-Za-z]+'
2026-10-05T14:59:57.968Z 127.0.0.1:5501 sending request
2026-10-05T15:00:07.968Z 127.0.0.1:5501 failed with DEADLINE_EXCEEDED
CO_BuyMemberTraffic
```

Over the whole shard, 4 of 720 `SubmitAndWait*` calls from the apps took over 5 s; three are in this 12 s window
(section 8).

## 5. The cause: sequenced-event store writes stall for 1.2-1.4 s, server-wide

`DbSequencedEventStore` writes ("Storing delivery events" to "Successfully stored") take 1-2 ms on average for the
whole shard; in 14:59-15:00 they go up to 1.39 s. Nodes with their own HikariCP pools (`max-connections = 8` each,
`storage-postgres.conf:21`) on the one Postgres server (`storage-postgres.conf:9`, port 5432) finish the same write in the same few
milliseconds, which a per-node problem would not produce.

```
$ zcat $C | grep -a -E 'Storing delivery events from|Successfully stored [0-9]+ events' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(Storing|Successfully).*"logger_name":"[^:]*:([a-z]+=[A-Za-z0-9]+)\/psid=([a-z-]+).*/\1 \2 \3\/\4/' | grep -a -v '^{' > log/10281/store-events.txt; awk '{split($1,a,"T"); split(a[2],b,":"); t=b[1]*3600+b[2]*60+substr(b[3],1,length(b[3])-1); if($2=="Storing") s[$3]=t; else if($3 in s){d=t-s[$3]; m=substr($1,12,5); if(d>mx[m]) mx[m]=d; c[m]++; tot[m]+=d; if(d>0.5) slow[m]++; delete s[$3]}} END{for(m in mx) printf "%s n=%d mean=%.3f max=%.3f over0.5s=%d\n", m, c[m], tot[m]/c[m], mx[m], slow[m]+0}' log/10281/store-events.txt | sort | sed -n '/^14:5[5-9]/p;/^15:0[0-3]/p'
14:55 n=758 mean=0.001 max=0.053 over0.5s=0
14:56 n=1830 mean=0.001 max=0.017 over0.5s=0
14:57 n=2389 mean=0.002 max=0.074 over0.5s=0
14:58 n=1271 mean=0.002 max=0.050 over0.5s=0
14:59 n=748 mean=0.013 max=1.141 over0.5s=2
15:00 n=544 mean=0.049 max=1.391 over0.5s=16
15:01 n=873 mean=0.024 max=0.533 over0.5s=1
15:02 n=106 mean=0.028 max=0.518 over0.5s=1
15:03 n=32 mean=0.019 max=0.175 over0.5s=0

$ awk '{split($1,a,"T"); split(a[2],b,":"); t=b[1]*3600+b[2]*60+substr(b[3],1,length(b[3])-1); if($2=="Storing") {s[$3]=t; st[$3]=$1} else if($3 in s){d=t-s[$3]; if(d>0.3 && $1>"2026-10-05T14:59:5" && $1<"2026-10-05T15:00:12") printf "%s -> %s %.3f s %s\n", substr(st[$3],12), substr($1,12), d, $3; delete s[$3]}}' log/10281/store-events.txt
14:59:51.191Z -> 14:59:51.630Z 0.439 s mediator=globalMediatorSv1/global-domain
14:59:51.191Z -> 14:59:51.630Z 0.439 s mediator=globalMediatorSv2/global-domain
14:59:51.191Z -> 14:59:51.630Z 0.439 s mediator=globalMediatorSv3/global-domain
14:59:51.191Z -> 14:59:51.630Z 0.439 s mediator=globalMediatorSv4/global-domain
14:59:51.191Z -> 14:59:51.630Z 0.439 s participant=bobParticipant/global-domain
15:00:03.918Z -> 15:00:05.128Z 1.210 s mediator=globalMediatorSv2/global-domain
15:00:03.918Z -> 15:00:05.128Z 1.210 s mediator=globalMediatorSv4/global-domain
15:00:03.935Z -> 15:00:05.168Z 1.233 s participant=aliceParticipant/global-domain
15:00:03.935Z -> 15:00:05.168Z 1.233 s mediator=globalMediatorSv3/global-domain
15:00:03.935Z -> 15:00:05.168Z 1.233 s mediator=globalMediatorSv1/global-domain
15:00:07.435Z -> 15:00:07.813Z 0.378 s mediator=globalMediatorSv4/global-domain
15:00:07.821Z -> 15:00:09.212Z 1.391 s mediator=globalMediatorSv2/global-domain
15:00:07.839Z -> 15:00:09.214Z 1.375 s mediator=globalMediatorSv1/global-domain
15:00:07.839Z -> 15:00:09.215Z 1.376 s participant=sv1Participant/global-domain
15:00:07.839Z -> 15:00:09.215Z 1.376 s mediator=globalMediatorSv3/global-domain
15:00:07.839Z -> 15:00:09.215Z 1.376 s participant=aliceParticipant/global-domain
15:00:07.839Z -> 15:00:09.216Z 1.377 s participant=bobParticipant/global-domain
15:00:09.995Z -> 15:00:11.186Z 1.191 s participant=aliceParticipant/splitwell
15:00:09.995Z -> 15:00:11.186Z 1.191 s mediator=splitwellMediator/splitwell
15:00:09.995Z -> 15:00:11.189Z 1.194 s participant=bobParticipant/splitwell
15:00:09.995Z -> 15:00:11.189Z 1.194 s participant=splitwellParticipant/splitwell
```

## 6. The verdict is held on aliceParticipant behind that write

The verdict (sc=1416) needs topology known up to 15:00:05.242372; the participant only knows 15:00:04.094855 until the
previous event (sc=1415) is stored, which takes 15:00:07.839 to 15:00:09.215. The task scheduler reports the request
tracker stuck behind sc=1409 for 10 s, with the transfer's trace (255bf9cf...) among the blocked ones.

```
$ zcat $C | grep -a 'participant=aliceParticipant/psid=global' | grep -a -E 'T15:00:0(7\.8[34]|9\.21[5-7])' | grep -a -E 'Storing delivery events|Successfully stored|Starting time awaiter|Completing time awaiter' | sed -E "$F" | cut -c1-175
2026-10-05T15:00:07.839Z Storing delivery events from 2026-10-05T15:00:05.241371Z / 1415 to 2026-10-05T15:00:05.241371Z / 1415.",[c.d.c.s.d.DbSequencedEventStore:participant=a
2026-10-05T15:00:07.839Z Starting time awaiter for timestamp 2026-10-05T15:00:05.242372Z. Current known time is 2026-10-05T15:00:04.094855Z.",[c.d.c.t.TimeAwaiter:participant=
2026-10-05T15:00:09.215Z Successfully stored 1 events and updated the lower bound from CounterAndTimestamp(1414,2026-10-05T15:00:03.844854Z) to CounterAndTimestamp(1415,2026-1
2026-10-05T15:00:09.216Z Completing time awaiter for timestamp 2026-10-05T15:00:05.242372Z",[c.d.c.t.TimeAwaiter:participant=aliceParticipant/psid=global-domain::1220729bafde:
2026-10-05T15:00:09.217Z Storing delivery events from 2026-10-05T15:00:05.242372Z / 1416 to 2026-10-05T15:00:05.242372Z / 1416.",[c.d.c.s.d.DbSequencedEventStore:participant=a

$ zcat $C | grep -a 'Task scheduler waits for tick' | grep -a 'participant=aliceParticipant' | grep -a 'T15:00:0' | sed -E "$F" | cut -c1-260
2026-10-05T15:00:02.971Z Task scheduler waits for tick of sc=1409. The tick with sc=1408 occurred at 2026-10-05T14:59:57.970587Z. Blocked trace ids: 50f930fa582f5267cc3d569f405c2378",[c.d.c.d.TaskScheduler:participant=aliceParticipant/psid=global-domain::12207
2026-10-05T15:00:07.972Z Task scheduler waits for tick of sc=1409. The tick with sc=1408 occurred at 2026-10-05T14:59:57.970587Z. Blocked trace ids: 50f930fa582f5267cc3d569f405c2378, 11ae2bb1003a3686b24d7d264314fffd, e46ba22bcd731cc8723a8737470e28a8, 255bf9cfa
```

## 7. Corroboration: DB lock health checks rejected across all nodes from 14:59

`DbLock.runLockCheck` logs `LockCheckRejected` (DEBUG) when the check's DB call fails with
`RejectedExecutionException` or `DbStorage$NoConnectionAvailable` (`DbLockPostgres.$anonfun$lockCheck$3`, checked in the
3.6.0-snapshot.20261001.20345.0.v85a9270a jar), i.e. the previous check has not returned. It is 0-14 per minute before
14:59, then 27, 49, 37, 20, on participants, mediators and sequencers alike (aliceParticipant at 14:59:58.373, inside
the traffic top-up's 1 s phase 1).

```
$ zcat $C | grep -a "DbLock.runLockCheck' was not successful" | grep -aoE '^\{"@timestamp":"2026-10-05T[0-9:]{5}' | cut -c27-31 | uniq -c | tr '\n' ' '; echo; zcat $C | grep -a -m1 "DbLock.runLockCheck' was not successful" | grep -aoE 'Result: Outcome\([^)]*\)\)'
      1 14:40       1 14:42       1 14:44       1 14:45      14 14:46       2 14:47       1 14:49       5 14:50       7 14:51       5 14:52       8 14:53       1 14:56       3 14:57       4 14:58      27 14:59      49 15:00      37 15:01      20 15:02       5 15:03 
Result: Outcome(Left(LockCheckRejected(lockId = 813112907))

$ javap -p -c -cp log/canton-jars/canton-open-source-3.6.0-snapshot.20261001.20345.0.v85a9270a/lib/canton-open-source-3.6.0-snapshot.20261001.20345.0.v85a9270a.jar com.digitalasset.canton.resource.DbLockPostgres 2>/dev/null | sed -n '/lockCheck$3(/,/areturn/p' | grep -E 'lockCheck|instanceof|LockCheckRejected."<init>"'
  public static final com.digitalasset.canton.resource.DbLockError $anonfun$lockCheck$3(com.digitalasset.canton.resource.DbLockPostgres, java.lang.Throwable);
         5: instanceof    #937                // class java/util/concurrent/RejectedExecutionException
        20: instanceof    #104                // class com/digitalasset/canton/resource/DbStorage$NoConnectionAvailable
        48: invokespecial #940                // Method com/digitalasset/canton/resource/DbLockError$LockCheckRejected."<init>":(Lcom/digitalasset/canton/resource/DbLockId;)V
```

The shard also had a shorter stall at 14:46:37-38 (store writes 0.5-1.3 s, 14 lock-check rejections that minute) that
did not fail a test.

## 8. Not family B, not family L; the window is an outlier

```
$ W=log/10281/window.jsonl; zcat $C | grep -a -E '"@timestamp":"2026-10-05T(14:59:[3-5]|15:00:[01])' > $W; echo "insert block retries: $(grep -a -c "operation 'insert block'" $W)"; echo "below weak quorum: $(grep -a -c 'below weak quorum' $W)"; echo "mempool rejections: $(grep -a -c 'P2P connectivity is not ready' $W)"; grep -a 'globalSequencerSv1' $W | grep -a -E 'New epoch [0-9]+ has started' | sed -E 's/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\).*/\1 epoch=\2 blacklisted=[\4]/'
insert block retries: 0
below weak quorum: 0
mempool rejections: 0
2026-10-05T14:59:34.646Z epoch=114 blacklisted=[]
2026-10-05T14:59:51.001Z epoch=115 blacklisted=[]
2026-10-05T15:00:11.775Z epoch=116 blacklisted=[]

$ zcat $T | grep -a 'CommandService/SubmitAndWait' | grep -a -E 'sending request|succeeded\(OK\)|failed with' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Request \(tid:([0-9a-f]+)\) [^ ]+ to ([0-9.:]+): (sending request|succeeded|failed with [A-Z_]+).*/\1 \2 \3 \4/' | awk '{split($1,a,"T"); split(a[2],b,":"); t=b[1]*3600+b[2]*60+substr(b[3],1,length(b[3])-1); if($4=="sending"){s[$2]=t; st[$2]=$1} else if($2 in s){d=t-s[$2]; n++; if(d>5) {slow++; printf "%s %s %.2f %s\n", st[$2], $3, d, $4}; delete s[$2]}} END{print "total",n,"over5s",slow+0}'
2026-10-05T14:47:19.158Z 127.0.0.1:5301 10.89 succeeded
2026-10-05T14:59:57.968Z 127.0.0.1:5501 10.00 failed
2026-10-05T14:59:59.035Z 127.0.0.1:5501 10.00 failed
2026-10-05T15:00:08.174Z 127.0.0.1:5501 6.11 failed
total 720 over5s 4
```

The global synchronizer runs the BFT orderer with an unchanged ordering topology (epochs 114-116, nobody blacklisted)
and no `insert block` retries: neither family B nor family L.

## 9. Not a JVM pause, and not the Postgres checkpoint

All nodes of section 5 run in one canton JVM, so a stop-the-world pause or a starved runner would also make them finish
together. Both JVMs (canton and the sbt test JVM) kept logging in every 200 ms window across the 15:00:07.8-09.2 stall;
the canton burst at 09.2 is the backlog draining when the writes return. Only the DB writes stopped.

```
$ for f in canton.clog.gz canton_network_test.clog.gz; do echo "== $f"; zcat log/10281/logs-wall-clock-time-0/$f | grep -a -oE '^\{"@timestamp":"2026-10-05T15:00:0[789]\.[0-9]' | grep -oE '0[789]\.[0-9]$' | awk '{s=substr($1,1,2); d=substr($1,4,1); print s "." int(d/2)*2}' | sort | uniq -c | awk '{printf "%s:%s ", $2, $1} END{print ""}'; done
== canton.clog.gz
07.0:55 07.2:24 07.4:636 07.6:36 07.8:859 08.0:69 08.2:59 08.4:49 08.6:26 08.8:74 09.0:201 09.2:2589 09.4:40 09.6:29 09.8:3244
== canton_network_test.clog.gz
07.0:37 07.2:62 07.4:48 07.6:65 07.8:87 08.0:47 08.2:72 08.4:48 08.6:53 08.8:96 09.0:65 09.2:156 09.4:49 09.6:62 09.8:217
```

The Postgres server log is not in the artifact. A Cloud Logging export for the runner pod's `postgres` container was
supplied by the user (container stderr, so Cloud Logging tags it severity ERROR; Postgres level is LOG). The pod is
this job's runner:

```
$ gh api repos/canton-network/splice/actions/jobs/111816892945 --jq '"\(.runner_name) \(.started_at) \(.completed_at)"'
self-hosted-k8s-large-4p78d-runner-gthlx 2026-10-05T14:37:23Z 2026-10-05T15:03:55Z

[user-supplied, resource.labels.pod_name self-hosted-k8s-large-4p78d-runner-gthlx-workflow, container postgres,
 node gke-cn-splicenet-cn-apps-node-pool-2--f261a6dd-9nkn]
2026-10-05 14:57:33.425 UTC [70] LOG:  checkpoint starting: time
2026-10-05 15:02:07.657 UTC [70] LOG:  checkpoint complete: wrote 2541 buffers (15.5%), wrote 19 SLRU buffers; 0 WAL file(s) added, 0 removed, 27 recycled; write=269.666 s, sync=0.601 s, total=274.232 s; sync files=2300, longest=0.132 s, average=0.001 s; distance=429727 kB, estimate=464145 kB; lsn=0/69CD51A0, redo lsn=0/5A327010
```

The only checkpoint around the stalls is a timed one, and it is unremarkable. Its write phase (14:57:33-~15:02:03)
covers all stall windows, but it is paced: 269.7 s is 0.9 x the default 300 s `checkpoint_timeout`, i.e. the default
`checkpoint_completion_target` spreading 2541 buffers (about 20 MB) over the interval. Its sync phase, where checkpoint
fsync storms stall commits, took 0.601 s in total (longest file 0.132 s) and ran at ~15:02:03-07, after the last stall.
So the checkpoint is ruled out as the cause. The supplied export contains only these two lines; whether the container
logged anything else in 14:59-15:01 was not checked. Slow statements and lock waits are not logged with the CI
Postgres settings. The WAL volume, 430 MB over the checkpoint
interval (about 1.4 MB/s), shows the shard's write load.

## 10. Where the 10 s deadline comes from: the test config's treasury `grpcDeadline`

The wallet handler does not submit the transfer itself; it enqueues it on the user's treasury, which submits with
`treasuryConfig.grpcDeadline` (line 839; line 673 is the batch path, which the `CO_BuyMemberTraffic` top-up of
section 4 takes, hence its 10 s failure too). `LedgerClient.submitAndWait` builds the stub with `timeouts.unbounded`
and applies only that deadline. The config field defaults to `None` and is documented as test-only; every integration
test sets it to 10 s through `ConfigTransforms.defaults`.

```
$ git show 9e1c06863e:apps/wallet/src/main/scala/org/lfdecentralizedtrust/splice/wallet/admin/http/HttpWalletHandler.scala | grep -n "treasury.enqueueTokenStandardTransferOperationV1"
897:        result <- userWallet.treasury.enqueueTokenStandardTransferOperationV1(
$ git show 9e1c06863e:apps/wallet/src/main/scala/org/lfdecentralizedtrust/splice/wallet/treasury/TreasuryService.scala | grep -n "treasuryConfig.grpcDeadline"
673:        deadline = treasuryConfig.grpcDeadline,
839:          treasuryConfig.grpcDeadline,
$ git show 9e1c06863e:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/ledger/api/LedgerClient.scala | sed -n "395,401p"
      stubWithCredsAndTraceContext <- withGrpcContext(commandServiceStub, Some(timeouts.unbounded))
      stub = deadline
        .map(duration =>
          stubWithCredsAndTraceContext
            .withDeadlineAfter(duration.asJava.toMillis(), TimeUnit.MILLISECONDS)
        )
        .getOrElse(stubWithCredsAndTraceContext)
$ git show 9e1c06863e:apps/wallet/src/main/scala/org/lfdecentralizedtrust/splice/wallet/config/TreasuryConfig.scala | sed -n "28,33p"
      * This is used to set the deadline for grpc calls to the participant.
      * If the call takes longer than this, it will be cancelled and retried.
      * This is only intended for testing purposes.
      * TODO(DACH-NY/canton-network-node#11501) block and unblock submissions on domain reconnect
      */
    grpcDeadline: Option[NonNegativeFiniteDuration] = None,
$ git show 9e1c06863e:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala | sed -n "195p;204p;300,306p"
  def defaults(testId: Option[String] = None): Seq[ConfigTransform] = {
      setDefaultGrpcDeadlineForTreasuryService(),
  def setDefaultGrpcDeadlineForTreasuryService(): ConfigTransform =
    ConfigTransforms.updateAllValidatorAppConfigs_(c =>
      c.copy(treasury =
        c.treasury.copy(
          grpcDeadline = Some(NonNegativeFiniteDuration.ofSeconds(10))
        )
      )
```

An earlier version of this packet attributed the deadline to `LedgerClient.scala:130` (`timeouts.default`); that is
`withGrpcContext`'s default parameter, which `submitAndWait` overrides with `timeouts.unbounded` (line 395).

## Verdict

- New ref, not a duplicate of a catalogued family. Same class as 10276 (and 10176's Postgres commit-latency outlier):
  the runner's Postgres server stalls for over a second at a time, here 15:00:03.9-05.2 and 15:00:07.8-09.2, and every
  canton node waits on it at once. In 10276 it cost a checkErrors WARN; here it stretched one confirmation round trip
  (four sequencing hops plus two participant writes) to 10.2 s, just past the 10 s treasury deadline that the
  integration-test config sets (section 10). In production, with no deadline, the transfer would have completed at
  15:00:09.231.
- Flake, infra cause; resolution: rerun. Test-side option, left to the owners: raise
  `setDefaultGrpcDeadlineForTreasuryService` above 10 s. The deadline exists so that tests cancel and retry submissions
  around synchronizer reconnects (TODO canton-network-node#11501), so a larger value trades that off; no branch
  written. The stall itself (10 s ledger latency on the runner) remains an infra problem either way.
- Follow-up, the same as 10276 and left to the owners: the Postgres server log exists in Cloud Logging (section 9) but
  checkpoints are the only thing it records here; enable `log_min_duration_statement`, `log_lock_waits` and
  `track_io_timing` in the CI Postgres so the next stall shows which statements waited and on what. Next check for this
  run: GKE node metrics (disk write latency / IOPS, CPU steal) for gke-cn-splicenet-cn-apps-node-pool-2--f261a6dd-9nkn
  at 14:59-15:01.
- Ruled out (section 9): a canton JVM pause, and the 14:57:33-15:02:07 checkpoint (paced writes, 0.6 s sync after the
  stalls).
- Not verified: what stalled Postgres (node disk I/O latency or a lock wait remain; no node metrics, no statement or
  lock-wait logging); whether the splice apps' own database
  (`splice_apps`, same server) stalled in the same windows; why phase 1 of the traffic top-up took 1.015 s (section 7
  only shows a lock check rejected inside that second, not which DB call phase 1 waited on); whether canton 3.6.1 (main
  since #7635) changes any of this.
