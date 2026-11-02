# 2026-11-02 splice main singles (fork B): 11104 11105 11107 11128 11136

All five runs are canton-network/splice `main` (push or schedule), Canton 3.7.0 (`nix/canton-sources.json` at each
sha). None shares a cause with fork A's mainnet-Daml nightlies (`nightly-a.md`).

```
gh run view $RUN --repo canton-network/splice --json headBranch,headSha,displayTitle,createdAt,event,jobs \
  --jq '{b:.headBranch,sha:.headSha[0:10],t:.displayTitle,c:.createdAt,e:.event}, (.jobs[]|select(.conclusion=="failure")|{id:.databaseId,name:.name})'
```
```
== 41079252359
{"b":"main","c":"2026-10-30T15:16:59Z","e":"push","sha":"7e354bbf46","t":"Build offline root key delegations for the correct protocol version (..."}
{"id":124144368461,"name":"ci / scala_test_logical_sync_upgrade / logical-sync-upgrade (0)"}
== 41079307859
{"b":"main","c":"2026-10-30T15:17:21Z","e":"push","sha":"d3d7807893","t":"Use withBlockingTransactionalLock in indexSnapshotStakeholdersTable (..."}
{"id":124149916455,"name":"ci / scala_test_wall_clock_time / wall-clock-time (3)"}
== 41132609705
{"b":"main","c":"2026-10-30T21:39:05Z","e":"push","sha":"3525c637db","t":"scan-party-monitor: use events stream to report on private transactio..."}
{"id":124327589434,"name":"ci / scala_test_sim_time / simtime (2)"}
== 41391559249
{"b":"main","c":"2026-11-02T05:00:52Z","e":"schedule","sha":"3525c637db","t":"Wall Clock Tests with Postgres 14"}
{"id":125104220178,"name":"scala_test_wall_clock_time / wall-clock-time (1)"}
== 41408167263
{"b":"main","c":"2026-11-02T07:43:48Z","e":"push","sha":"e4825eacda","t":"make perf test db instance similar to prod spec (#8156)"}
{"id":125155379899,"name":"ci / scala_test_wall_clock_time / wall-clock-time (2)"}
```

Classification (`references/recipes.md` section 2) per job log:
```
== 11104   [error] (checkErrors) log/canton_network_test.clog contains problems.   Tests: succeeded 2, failed 0
== 11105   [error] (checkErrors) log/canton_before_shutdown.clog contains problems. Tests: succeeded 41, failed 0
== 11107   no test summary (JVM exit)
== 11128   - scan_party_monitor.py reports transfers to unexpected recipients and confirmations *** FAILED ***   Tests: succeeded 60, failed 1
== 11136   - scan_party_monitor.py reports transfers to unexpected recipients and confirmations *** FAILED ***   Tests: succeeded 16, failed 1
```

## 11128 (parent) + 11136 - ScanPartyMonitorScriptIntegrationTest: private verdict held back by the /v0/events cap

New family Z. Flake in the test added by #8107 (3525c637db, Stephen Compall, merged 10-30 21:39). Passed on its
own push run 41132609705 (all wall-clock shards `success`) and on the PG14 nightlies of 10-31 and 11-01; failed
11-02 05:00 (PG14 nightly) and 11-02 07:43 (push).

### 1. Failing assertion (identical in both)
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11128/job.log | grep -a -A3 'confirmations \*\*\* FAILED' | sed -E 's/^[^Z]*Z //'
```
```
[info] - scan_party_monitor.py reports transfers to unexpected recipients and confirmations *** FAILED ***
[info]   Check The script reports alice confirming the private transaction for Alice offers bob a transfer, which the DSO party does not see List() did not contain element "confirming" (ScanPartyMonitorScriptIntegrationTest.scala:273)
```
The last step creates a TransferOffer alice -> bob (no DSO stakeholder) and re-runs the script inside `eventually`
until it reports a `"private": true` match with reason `confirming` for alice.

### 2. The script's cursor never moves past the last DSO-visible update
Script stderr from the test's debug line (`<script> exited with 0:`), act at 05:32:02.940, failure 05:32:23.753:
```
zcat log/11128/logs-wall-clock-time-1/canton_network_test.clog.gz | grep -a -E '"logger_name":"[^"]*ScanPartyMonitorScriptIntegrationTest' | grep -a 'exited with' | sed -n '6,8p;20,24p' | python3 -I -c '...print stderr...'
```
```
2026-11-02T05:32:03.776Z exited with 0:
INFO Streaming events after 2026-11-02T05:32:02.202697Z (migration id 0), tracking 3 parties
INFO At record time 2026-11-02T05:32:02.771245Z (migration id 0, 0s behind); this run: 1 transactions, 0 matching
---
2026-11-02T05:32:04.093Z exited with 0:
INFO Streaming events after 2026-11-02T05:32:02.771245Z (migration id 0), tracking 3 parties
INFO At record time 2026-11-02T05:32:02.771245Z (migration id 0, 1s behind); this run: 0 transactions, 0 matching
---
2026-11-02T05:32:23.744Z exited with 0:
INFO Streaming events after 2026-11-02T05:32:02.771245Z (migration id 0), tracking 3 parties
INFO At record time 2026-11-02T05:32:02.771245Z (migration id 0, 20s behind); this run: 0 transactions, 0 matching
```

### 3. Scan ingested the private verdict, but its update history did not advance
```
zcat $T | grep -a 'sv1Scan' | grep -a -E 'History 17 migration 0 ingesting|Last ingested verdict record_time' | ...
```
```
2026-11-02T05:32:03.035Z Inserted 1 verdicts ... Last ingested verdict record_time is now Some(2026-11-02T05:32:02.771245Z)
2026-11-02T05:32:03.168Z History 17 migration 0 ingesting 2627 => 2630 @ Some(2026-11-02T05:32:02.771245Z)
2026-11-02T05:32:03.267Z Inserted 1 verdicts ... Last ingested verdict record_time is now Some(2026-11-02T05:32:03.021818Z)
2026-11-02T05:32:08.049Z History 17 migration 0 ingesting 2630 => 2638 @ None
2026-11-02T05:32:22.931Z History 17 migration 0 ingesting 2638 => 2662 @ None
```
The verdict at 05:32:03.021818 (the private offer) is stored at 03.267. The DSO update stream gets nothing with a
record time afterwards (only `@ None` offset moves) until the test gives up 20 s later.

### 4. Why /v0/events hides it: cap = min(last verdict, last update) record time
`ScanEventStore.getEvents` (3525c637db, apps/scan/.../store/ScanEventStore.scala:69-98):
```
currentCap <- resolveCurrentMigrationCap(verdictStore.lastIngestedRecordTime, updateHistory.lastIngestedRecordTime, currentMigrationId)
...
else rt > afterRt && rt <= currentMigrationCap
```
`UpdateHistory.advanceLastIngestedRecordTime` runs only for ingestions with `recordTime` set (UpdateHistory.scala:307),
so the `@ None` steps keep the cap at 05:32:02.771245. A verdict for a transaction the DSO party does not see is served
only after the next DSO-visible update. On a busy network that is seconds; in the 1-SV test the next one can be
20+ s away (round automation), longer than the `eventually`.

### 5. 11136 is the same
```
grep -E '^2026-11-02T08:(25:49|25:5|26:)' log/11136/ingest.txt
```
```
2026-11-02T08:25:49.458Z Inserted 1 verdicts ... Last ingested verdict record_time is now Some(2026-11-02T08:25:48.708589Z)
2026-11-02T08:25:49.465Z Inserted 1 verdicts ... Last ingested verdict record_time is now Some(2026-11-02T08:25:48.708590Z)
2026-11-02T08:25:49.747Z History 24 migration 0 ingesting 3223 => 3226 @ Some(2026-11-02T08:25:48.708589Z)
2026-11-02T08:25:52.693Z History 24 migration 0 ingesting 3226 => 3230 @ None
2026-11-02T08:26:07.154Z History 24 migration 0 ingesting 3230 => 3250 @ None
```
```
08:26:10,096 INFO At record time 2026-11-02T08:25:48.708589Z (migration id 0, 21s behind); this run: 0 transactions, 0 matching
```

### Verdict
11128 new family Z (test flake; of the 5 runs checked since #8107, 2 failed); 11136 dup of 11128. Fix belongs in the test: after
`createTransferOffer`, cause one DSO-visible transaction (e.g. a `tap` by any wallet) inside the act so the cap moves
past the offer, or widen the `eventually`. Scan-side question for the owner: whether offset checkpoints could
carry a record time and advance the cap. Owner: stephencompall-DA (#8107). Not verified: no fix written, no run.

## 11107 - dup of 10907 (family U)

simtime (2), main 3525c637db. sv4 exits during SV join:
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11107/job.log | sed -E 's/^[^Z]*Z //' | sed -n '12020,12040p'
```
```
sv4 app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv4 app initialization: JoiningNodeInitializer joining Dso with key failed
Caused by: io.grpc.StatusRuntimeException: INVALID_ARGUMENT: IMPORT_ACS_ERROR(8,84057eb5): Cannot apply a repair command as the last event is a topology offset. Please reconnect to the synchronizer
```
Same signature as family U. 10907 is open, assigned julientinguely-da. Second hit (first 10-29).

## 11104 - LsuIntegrationTest: sv4Validator faucet trigger ERROR while the late sv4 successor sequencer is blacklisted

New family AA. Test passed (`Tests: succeeded 2`); checkErrors flagged one ERROR.

### 1. Flagged line
```
grep -a -B600 'contains problems' log/11104/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' | ...
```
```
"@timestamp":"2026-10-30T15:37:53.534Z" "logger_name":"o.l.s.v.a.ReceiveFaucetCouponTrigger:LsuIntegrationTest/config=38cc22d/validator=sv4Validator" "level":"ERROR"
Skipping processing of Task(license = ... ValidatorLicense ...) due to unexpected failure
io.grpc.StatusRuntimeException: NOT_FOUND: LOCAL_VERDICT_INACTIVE_CONTRACTS(11,a3244410): Rejected transaction is referring to inactive contracts
```

### 2. 35 retries all refused with backpressure, only the last one hit an inactive round
```
zcat $T | grep -a 'sv4Validator' | grep -a 'SubmitAndWait to 127.0.0.1:5401: failed with' | grep -a -E 'T15:3[5-7]' | ... | sort | uniq -c
```
```
     35 ABORTED/SEQUENCER_BACKPRESSURE
      1 NOT_FOUND/LOCAL_VERDICT_INACTIVE_CONTRACTS
"@timestamp":"2026-10-30T15:35:44.099Z"	failed with ABORTED/SEQUENCER_BACKPRESSURE
"@timestamp":"2026-10-30T15:37:53.529Z"	failed with NOT_FOUND/LOCAL_VERDICT_INACTIVE_CONTRACTS
```
```
2026-10-30T15:37:53.531Z The operation 'processTaskWithRetry' has failed with an exception. Total maximum number of retries 35 exceeded. Giving up.
2026-10-30T15:37:54.529Z Validator never received faucet coupons, it will start now at round 1.
2026-10-30T15:37:55.847Z Completed processing with outcome: Received faucet coupon for Round 1
```
Only sv4 nodes see backpressure (175 sv4Validator, rest sv4 SV triggers), 15:35:44 to 15:37:50.

### 3. The sv4 successor sequencer starts after the upgrade time and is blacklisted for ~2 min
Upgrade time 15:35:11.008943. sv1..3 successors initialised 15:32:56; sv4's (intentional late joiner, test
comment at LsuIntegrationTest.scala:108) initialises at 15:35:38:
```
zcat $S | grep -a 'sequencer=sv4StandaloneSequencer' | ...
```
```
2026-10-30T15:35:38.737Z ... SequencerInitializationService/InitializeSequencerFromLsuPredecessor
2026-10-30T15:35:38.755Z Assigning sequencer to synchronizer from beginning
2026-10-30T15:35:44.079Z Rejecting submission request (...) synchronously: SequencerDeliverError(code = SEQUENCER_OVERLOADED, message = Sequencer can't take requests because it is behind on processing events)
2026-10-30T15:37:50.246Z Rejecting submission request (tick-...) synchronously: SequencerDeliverError(code = SEQUENCER_OVERLOADED, message = Mempool received client request but this node is currently blacklisted, rejecting)
```
On sv1's successor:
```
2026-10-30T15:35:12.954Z New epoch 9 has started with leaders = List(sv1, sv2, sv3)and blacklisted nodes = List(sv4)
2026-10-30T15:35:33.992Z New epoch 10 has started with leaders = List(sv3, sv4, sv1, sv2)and blacklisted nodes = List()
2026-10-30T15:35:44.290Z New epoch 11 has started with leaders = List(sv3, sv1, sv2)and blacklisted nodes = List(sv4)
...
2026-10-30T15:37:35.945Z New epoch 19 has started with leaders = List(sv2, sv3, sv1)and blacklisted nodes = List(sv4)
2026-10-30T15:37:50.447Z New epoch 20 has started with leaders = List(sv1, sv2, sv3, sv4)and blacklisted nodes = List()
```
sv4's participant only connects to sv4's sequencer, so every sv4 submission is refused while it is blacklisted. The
faucet trigger's 35 retries end at 15:37:53, 3 s after the blacklist lifts; the last attempt uses round 0 from
the rounds cache (valid until 15:45:37), which is archived by then, and the trigger logs ERROR.

### Verdict
New, test flake (first hit). The test already disables sv4's circuit breakers for this catch-up (#5587, Martin
Florian); the faucet trigger's retry budget is the same kind of catch-up noise. Fix options: pause
ReceiveFaucetCouponTrigger on sv4Validator until "sv4 upgrades", or suppress that ERROR for sv4Validator in that
window. Owner suggestion: Martin Florian (author of the sv4 late-joiner workaround). Not verified: frequency, fix.

## 11105 - sv1Participant WARN `Internal stream [Internal ACS Updates Stream] completed with a failure` (Canton 3.7.0)

Tests passed (`succeeded 41`); checkErrors flagged one WARN in canton_before_shutdown.clog, during the environment
start of GovernanceLockIntegrationTest (suite started 15:34:55).

### 1. Flagged line
```
"@timestamp":"2026-10-30T15:36:04.827Z","message":"Internal stream [Internal ACS Updates Stream] completed with a failure","logger_name":"c.d.c.l.p.s.InternalIndexServiceImpl:participant=sv1Participant","level":"WARN","stack_trace":"org.apache.pekko.stream.AbruptTerminationException: Processor actor [Actor[pekko://canton-actor-system/system/Materializers/StreamSupervisor-0/flow-280-1-fanoutPublisherSink#-1518798469]] terminated abruptly\n"
```

### 2. The commitment matcher fails 250 ms after it became healthy, on a fresh synchronizer connection
```
zcat $C | grep -a -E "'matcher-global-domain|Internal ACS Updates Stream" | ...
```
```
2026-10-30T15:35:58.911Z Starting internal stream [Internal ACS Updates Stream] with new tid: tid:1a09da94...  [sv1Participant] INFO
2026-10-30T15:35:58.911Z Starting internal stream [Internal ACS Updates Stream] with original tid: tid:e0d7a526... synchronizerId: global-domain::....., fromExclusive: None
2026-10-30T15:35:58.911Z Starting internal stream [Internal ACS Updates Stream] with new tid: tid:69636b79...  [sv1Participant] INFO
2026-10-30T15:35:58.911Z Starting internal stream [Internal ACS Updates Stream] with original tid: tid:e0d7a526... synchronizerId: global-domain::....., fromExclusive: None
2026-10-30T15:36:04.574Z 'matcher-global-domain::.....' is now in state Ok(). Previous state was Not Initialized.
2026-10-30T15:36:04.827Z 'matcher-global-domain::.....' is now in state Failed(Processor actor [...fanoutPublisherSink...] terminated abruptly)
2026-10-30T15:36:04.827Z Internal stream [Internal ACS Updates Stream] completed with a failure  WARN
```
Two internal ACS streams start with the same original tid; the failing one is tid 69636b79 (the WARN's
trace-id). sv1Participant had just connected to `global` for the first time (`SYNC_SERVICE_UNKNOWN_SYNCHRONIZER`
until 15:35:47, ledger offset 4 at 15:36:04). The matcher stays Failed for sv1Participant (no later `Ok` line).
Only one occurrence in the shard.

### Verdict
New, Canton 3.7.0 participant (ACS commitment matcher / internal index stream), first hit. Not a splice
change (d3d7807893 touches the scan snapshot lock). Owner suggestion: whoever owns the Canton 3.7 bump (raise
with the Canton team if it repeats). No ignore pattern proposed: the matcher stays Failed, so the line carries signal.
Not verified: what stopped the fanout publisher actor (jar not inspected).
