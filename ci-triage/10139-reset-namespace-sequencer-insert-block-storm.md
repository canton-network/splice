# 10139 - decentralized namespace reset times out behind a reference-sequencer insert-block serialization storm (run 35057829498)

Workflow "Wall Clock Tests with Postgres 14", scheduled nightly on main, sha b5d5645c56, 2026-09-16T05:00Z.
Failed job 104671796734 `scala_test_wall_clock_time / wall-clock-time (2)`. Canton runtime
3.6.0-snapshot.20260910.20260.0.v90621933. Commands verified against the downloaded artifacts.

Categorization
- Tests failed: none asserted. All 5 SvStateManagementIntegrationTest tests that ran passed; the suite
  was killed in the teardown of test 5 ("archive duplicated and non-sv AmuletPriceVote contracts") by
  `sys.exit(1)` in the ResetDecentralizedNamespace plugin. Tests 6-10 of the suite never ran.
- Failure type: timeout (plugin `retry_until_true` 1 minute) -> process exit 1; no ScalaTest report,
  checkErrors step skipped.
- Component: canton (reference block sequencer driver: `insert block` PostgreSQL serialization
  failures with exponential backoff, then DB pool exhaustion). The splice test plugin is only the
  messenger.
- Flake vs real: flake (CI infra / reference-driver contention under a shared Postgres), not a
  regression in the code under test. Recurrence likely on busy wall-clock shards.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35057829498 --repo canton-network/splice -n logs-wall-clock-time-2 -D dl
cd dl
# canton.clog.gz = node log; canton_network_test.clog.gz = test harness + apps; job-104671796734.log = GHA console
# (this packet ran the commands from log/10139/ which holds the same files)
```

## 1. Failed job: sbt exited 1 with no test report

```
sed -n '11044,11049p;11054,11055p' job-104671796734.log | cut -c1-200
```
```
2026-09-16T05:14:56.6489809Z [info] *** Test still running after 1 minute, 8 seconds: suite name: SvStateManagementIntegrationTest, test name: archive duplicated and non-sv AmuletPriceVote contracts. 
2026-09-16T05:15:38.3046697Z [info] *** Test still running after 1 minute, 38 seconds: suite name: SvStateManagementIntegrationTest, test name: archive duplicated and non-sv AmuletPriceVote contracts.
2026-09-16T05:15:39.3154078Z Attempt 1 failed with exit code 1, retrying
2026-09-16T05:15:39.3155132Z Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
2026-09-16T05:15:39.3217902Z ##[error]Error: failed to run script step (id 51987600-b18c-11f1-80d1-797f21892dfc): Error: step failed with return code 1
2026-09-16T05:15:39.3275659Z ##[error]Process completed with exit code 1.
2026-09-16T05:15:39.3351555Z ##[start-action display=Check logs for errors;id=__self.__self_8]
2026-09-16T05:15:39.3356091Z ##[end-action id=__self.__self_8;outcome=skipped;conclusion=skipped;duration_ms=0]
```
```
grep -cE 'FAILED \*\*\*|Failed tests:|Test failed:|RUN ABORTED|Found problems in|Tests: succeeded' job-104671796734.log
```
```
0
```
The sbt process died one second after the last "Test still running" line, with no ScalaTest summary,
no `Failed tests:` and no checkErrors output (the "Check logs for errors" step was skipped).

## 2. The exception: ResetDecentralizedNamespace gave up after 1 minute and called sys.exit(1)

```
zcat canton_network_test.clog.gz | grep -a '"level":"ERROR"' | python3 -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'], j['logger_name'], j['thread_name']); print(j['message']); print(j['stack_trace'])" \
  | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | head -14
```
```
2026-09-16T05:15:35.856Z o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace pool-101-thread-4-ScalaTest-running-SvStateManagementIntegrationTest
Failed to reset decentralized namespace
java.lang.IllegalStateException: Condition never became true within 1 minute
	at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:150)
	at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:119)
	at org.lfdecentralizedtrust.splice.integration.plugins.ResetDecentralizedNamespace.$anonfun$resetTopologyState$2(ResetDecentralizedNamespace.scala:127)
	at org.lfdecentralizedtrust.splice.integration.plugins.ResetDecentralizedNamespace.resetTopologyState(ResetDecentralizedNamespace.scala:42)
	at org.lfdecentralizedtrust.splice.integration.plugins.ResetTopologyStatePlugin.resetTopologyStateRetries$1(ResetTopologyStatePlugin.scala:91)
	at org.lfdecentralizedtrust.splice.integration.plugins.ResetTopologyStatePlugin.attemptToResetTopologyState(ResetTopologyStatePlugin.scala:110)
	at org.lfdecentralizedtrust.splice.integration.plugins.ResetTopologyStatePlugin.beforeEnvironmentDestroyed(ResetTopologyStatePlugin.scala:33)
	at com.digitalasset.canton.integration.EnvironmentSetup.manualDestroyEnvironment(EnvironmentSetup.scala:282)
	at org.lfdecentralizedtrust.splice.integration.tests.SvStateManagementIntegrationTest.manualDestroyEnvironment(SvStateManagementIntegrationTest.scala:49)
	at com.digitalasset.canton.integration.BaseIsolatedEnvironments.testFinished(EnvironmentSetup.scala:362)
```
This is the last line of canton_network_test.clog (the harness log stops here). The catch-all in the
plugin exits the JVM, which is why section 1 shows no report:

```
git show b5d5645c56:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala | sed -n '105,108p'
```
```
        case e: Throwable =>
          logger.error(s"Failed to reset $topologyType", e)
          sys.exit(1)
```
```
zcat canton_network_test.clog.gz | grep -aoE "(Starting|Test succeeded:) 'SvStateManagementIntegrationTest/[^']*'" | sed -E 's/^Starting/S/;s/^Test succeeded:/OK/' | sort | uniq -c
```
```
      1 OK 'SvStateManagementIntegrationTest/SVs can create a VoteRequest, vote on it and list them.'
      1 OK 'SvStateManagementIntegrationTest/SVs can update their AmuletPriceVote contracts'
      1 OK 'SvStateManagementIntegrationTest/VoteRequest expires with a definitive outcome.'
      1 OK 'SvStateManagementIntegrationTest/VoteRequest expires with no definitive outcome.'
      1 S 'SvStateManagementIntegrationTest/SVs can create a VoteRequest, vote on it and list them.'
      1 S 'SvStateManagementIntegrationTest/SVs can update their AmuletPriceVote contracts'
      1 S 'SvStateManagementIntegrationTest/VoteRequest expires with a definitive outcome.'
      1 S 'SvStateManagementIntegrationTest/VoteRequest expires with no definitive outcome.'
      1 S 'SvStateManagementIntegrationTest/archive duplicated and non-sv AmuletPriceVote contracts'
```
Four tests passed; the fifth ran to its teardown (no "Test succeeded"/"Test failed" line, the JVM
exited first). The suite has 10 tests (`grep -c '" in {' SvStateManagementIntegrationTest.scala`
at b5d5645c56 = 10); tests 6-10 never started.

## 3. Canton runtime version

```
zcat canton.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1; cat ../canton/VERSION
```
```
Canton version 3.6.0-snapshot.20260910.20260.0.v90621933
3.5.7-SNAPSHOT
```
The vendored `canton/` tree is 3.5.7-SNAPSHOT, not what ran; no Canton source line numbers are cited
below. The ordering layer in this shard is the reference block sequencer driver (test-only), not BFT:

```
zcat canton.clog.gz | grep -ac 'bftordering'; zcat canton.clog.gz | grep -ac 'ReferenceSequencerDriver'
```
```
0
18101
```

## 4. What the plugin was waiting for

Test 5 offboards sv3 and stops its SV app (SvStateManagementIntegrationTest.scala:304-369 at
b5d5645c56), so at teardown the DSO decentralized namespace has 3 owners (sv1, sv2, sv4); the plugin
must bring it back to {sv1}:

```
zcat canton_network_test.clog.gz | grep -a 'ResetDecentralizedNamespace' | grep -av '"level":"DEBUG"' | python3 -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'], j['level'], j['message'][:150])" | awk '$1>="2026-09-16T05:14:00"' | sed -n '1,5p;$p'
```
```
2026-09-16T05:14:35.804Z INFO Resetting decentralized namespace to contain only sv1
2026-09-16T05:14:35.804Z INFO The following namespaces need to be removed from the decentralized namespace: Set(1220345699b7..., 12207e9683e2...)
2026-09-16T05:14:35.852Z INFO All required proposals to reset SV namespace submitted, waiting for it to be effective
2026-09-16T05:14:35.855Z INFO Decentralized namespace still contains Set(1220004c6bc1..., 1220345699b7..., 12207e9683e2...), waiting for it to be reset
2026-09-16T05:14:35.858Z INFO Decentralized namespace still contains Set(1220004c6bc1..., 1220345699b7..., 12207e9683e2...), waiting for it to be reset
2026-09-16T05:15:35.856Z ERROR Failed to reset decentralized namespace
```
```
zcat canton.clog.gz | grep -aoE 'PAR::sv[0-9]::1220[0-9a-f]{8}' | sort -u
```
```
PAR::sv1::1220004c6bc1
PAR::sv2::1220345699b7
PAR::sv3::12202d01765c
PAR::sv4::12207e9683e2
```
The owner set never changed during the 60 s poll (every "still contains" line is the same set), and
the serial never moved either (the plugin throws INVALID_ARGUMENT and restarts when the serial changes,
ResetDecentralizedNamespace.scala:113-118; no "Restarting" line was logged).

## 5. All three proposals were accepted locally and sent to globalSequencerSv1 at 05:14:35.8

```
zcat canton.clog.gz | grep -a 'DecentralizedNamespaceDefinition' | grep -aE 'SynchronizerTopologyManager|SequencerBasedRegisterTopologyTransactionHandle' \
  | python3 -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); t=j['@timestamp']
    if '2026-09-16T05:14:34'<=t<='2026-09-16T05:15:40': print(t, j['level'], j['logger_name'][:75], '|', j['message'][:170].replace('\n',' '))" | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
```
```
2026-09-16T05:14:35.809Z INFO c.d.c.t.SynchronizerTopologyManager:participant=sv1Participant/psid=global-domain::12205673851e | Attempting to build, sign, and Replace DecentralizedNamespaceDefinition(namespace = 12205673851e..., threshold = 1, owners = 1220004c6bc1...) with serial Some(22)
2026-09-16T05:14:35.824Z INFO c.d.c.t.SynchronizerTopologyManager:participant=sv2Participant/psid=global-domain::12205673851e | Attempting to build, sign, and Replace DecentralizedNamespaceDefinition(namespace = 12205673851e..., threshold = 1, owners = 1220004c6bc1...) with serial Some(22)
2026-09-16T05:14:35.839Z INFO c.d.c.t.SynchronizerTopologyManager:participant=sv4Participant/psid=global-domain::12205673851e | Attempting to build, sign, and Replace DecentralizedNamespaceDefinition(namespace = 12205673851e..., threshold = 1, owners = 1220004c6bc1...) with serial Some(22)
2026-09-16T05:15:04.577Z INFO c.d.c.c.s.SequencerBasedRegisterTopologyTransactionHandle:participant=sv2Participant/psid=globa | The submitted topology transactions were not sequenced. Error=[Timeout(2026-09-16T05:15:01.682807Z)]. Transactions=SignedTopologyTransaction(   TopologyTransaction(DecentralizedNamespaceDefinition(nam
2026-09-16T05:15:04.598Z INFO c.d.c.c.s.SequencerBasedRegisterTopologyTransactionHandle:participant=sv4Participant/psid=globa | The submitted topology transactions were not sequenced. Error=[Timeout(2026-09-16T05:15:01.682809Z)]. Transactions=SignedTopologyTransaction(   TopologyTransaction(DecentralizedNamespaceDefinition(nam
2026-09-16T05:15:04.600Z INFO c.d.c.c.s.SequencerBasedRegisterTopologyTransactionHandle:participant=sv1Participant/psid=globa | The submitted topology transactions were not sequenced. Error=[Timeout(2026-09-16T05:15:01.682808Z)]. Transactions=SignedTopologyTransaction(   TopologyTransaction(DecentralizedNamespaceDefinition(nam
```
Sequencer side, the three sends were accepted with a 20 s max sequencing time:

```
zcat canton.clog.gz | grep -a 'GrpcSequencerService:sequencer=globalSequencerSv1' | grep -aE '"@timestamp":"2026-09-16T05:14:35\.8' \
  | grep -aoE "'PAR::sv[0-9]::1220[0-9a-f]{8}[^']*' sends request with id '[0-9a-f-]+'" | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
```
```
'PAR::sv1::1220004c6bc1...' sends request with id '63d451fa-bc18-4c83-8b1a-cd2e5b6aafb5'
'PAR::sv2::1220345699b7...' sends request with id 'e9fbb208-0ed1-45a5-8055-0f3685e522bf'
'PAR::sv4::12207e9683e2...' sends request with id 'ecf20081-0868-49de-b2a6-a49f5ca87732'
```
```
zcat canton.clog.gz | grep -a '63d451fa-bc18-4c83-8b1a-cd2e5b6aafb5' | grep -a 'sequencer=globalSequencerSv1' | grep -aE 'max sequencing time|Processing block' \
  | python3 -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'], j['logger_name'][:55], '|', j['message'][:210].replace('\n',' '))" | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
```
```
2026-09-16T05:14:35.827Z c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1 | Request to send submission with id 63d451fa-bc18-4c83-8b1a-cd2e5b6aafb5 with max sequencing time 2026-09-16T05:14:55.822774Z from PAR::sv1::1220004c6bc1... to Set(All). 
2026-09-16T05:15:03.188Z c.d.c.s.b.u.BlockChunkProcessor:sequencer=globalSequenc | Processing block 3672, data chunk 0. Last chunk ts=2026-09-16T05:15:01.682806Z, last seq event ts=Some(2026-09-16T05:14:30.344632Z). 1 events   Send of '63d451fa-bc18-4c83-8b1a-cd2e5b6aafb5' at 2026-09-16T05:15
```
(The SendAsync itself returned `succeeded(OK)` at 05:14:35.828 on globalSequencerSv1's ApiRequestLogger.)
Submitted 05:14:35.827, max sequencing time 05:14:55.823, actually ordered at 05:15:01.683 in block
3672 (26 s later, 6 s past the deadline) -> rejected as `Timeout`. Same for e9fbb208 (sv2) and
ecf20081 (sv4), which sit in block 3672 chunks 1 and 2 with the same 05:15:01.682807Z timestamp.

## 6. The outbox retried once, 15 s later than scheduled, and that retry was never ordered

```
zcat canton.clog.gz | grep -a 'QueueBasedSynchronizerOutbox:participant=sv1Participant' | grep -aE 'Requeuing|delayed flush|Attempting to push' \
  | python3 -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); t=j['@timestamp']
    if t>='2026-09-16T05:15:00': print(t, j['message'][:110])" | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
```
```
2026-09-16T05:15:04.600Z Requeuing and backing off due to error The synchronizer Synchronizer 'global' failed the following topology tr
2026-09-16T05:15:04.601Z Kick off a new delayed flush in 10s
2026-09-16T05:15:19.585Z About to kick off a delayed flush scheduled 10s ago
2026-09-16T05:15:19.593Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:6d5afeb
```
```
zcat canton.clog.gz | grep -a 'GrpcSequencerService:sequencer=' | grep -aE '"@timestamp":"2026-09-16T05:15:(19|2[0-9]|3[0-6])' \
  | grep -aoE "'PAR::sv[124]::1220[0-9a-f]{8}[^']*' sends request with id '[0-9a-f-]+'" | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
zcat canton.clog.gz | grep -acE "Send of '(d74b3140|6f3e9a61|8ed20f7e)"
zcat canton.clog.gz | tail -1 | grep -aoE '"@timestamp":"[^"]*"'
```
```
'PAR::sv1::1220004c6bc1...' sends request with id 'd74b3140-ae47-4e52-a92b-5914b0bf089d'
'PAR::sv2::1220345699b7...' sends request with id '6f3e9a61-f22e-4f83-b07d-a67e0020ce86'
'PAR::sv4::12207e9683e2...' sends request with id '8ed20f7e-e996-4153-880e-9097f28d89b7'
0
"@timestamp":"2026-09-16T05:15:45.229Z"
```
The retry (max sequencing time 05:15:39.6, see `grep d74b3140 | grep 'max sequencing'`) was never
ordered before the node log ends at 05:15:45; the plugin's 60 s budget expired at 05:15:35.856.

## 7. Why the sequencer ordered nothing for 27 s: `insert block` serialization failures with exponential backoff

globalSequencerSv1 (the node that accepted the three sends) enqueued them into its reference-driver
batch at 05:14:35.83-.86, but could not write the batch until 05:15:02.937:

```
zcat canton.clog.gz | grep -a 'ReferenceSequencerDriver:sequencer=globalSequencerSv1' | grep -a 'tag send' \
  | grep -aE '"@timestamp":"2026-09-16T05:14:35\.8' | python3 -c "
import sys,json
for l in sys.stdin: j=json.loads(l); print(j['@timestamp'], j['message'][:70])"
zcat canton.clog.gz | grep -a 'ReferenceSequencerDriver:sequencer=globalSequencerSv1' | grep -a 'Stored batch of requests' \
  | grep -aE '"@timestamp":"2026-09-16T05:1(4:(3[5-9]|[45][0-9])|5:0[0-2])' | grep -aoE '"@timestamp":"[^"]*"'
```
```
2026-09-16T05:14:35.827Z enqueued reference sequencer store request with tag send and sequencin
2026-09-16T05:14:35.842Z enqueued reference sequencer store request with tag send and sequencin
2026-09-16T05:14:35.856Z enqueued reference sequencer store request with tag send and sequencin
"@timestamp":"2026-09-16T05:15:02.937Z"
```
No batch was stored by globalSequencerSv1 between 05:14:35 and 05:15:02.937.

The reason is its `insert block` DB write, which failed on PostgreSQL SQLSTATE 40001 over and over
while DbStorage's retry backoff grew from 50 ms to 7.9 s:

```
zcat canton.clog.gz | grep -a 'DbStorageSingle:sequencer=globalSequencerSv1' | grep -aE '"@timestamp":"2026-09-16T05:1(4:(3[5-9]|[45][0-9])|5:0[0-2])' \
  | grep -aE 'Retrying after|Now retrying' | python3 -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'], j['message'][:95].replace('\n',' '))"
```
```
2026-09-16T05:14:35.284Z The operation 'insert block' has failed with an exception. New kind of error: transient error (
2026-09-16T05:14:35.334Z Now retrying operation 'insert block'. 
2026-09-16T05:14:35.365Z The operation 'insert block' has failed with an exception. Retrying after 0.084s. 
2026-09-16T05:14:35.449Z Now retrying operation 'insert block'. 
2026-09-16T05:14:35.486Z The operation 'insert block' has failed with an exception. Retrying after 0.17s. 
2026-09-16T05:14:35.656Z Now retrying operation 'insert block'. 
2026-09-16T05:14:36.220Z The operation 'insert block' has failed with an exception. Retrying after 0.256s. 
2026-09-16T05:14:36.477Z Now retrying operation 'insert block'. 
2026-09-16T05:14:37.471Z The operation 'insert block' has failed with an exception. Retrying after 0.7s. 
2026-09-16T05:14:38.172Z Now retrying operation 'insert block'. 
2026-09-16T05:14:38.237Z The operation 'insert block' has failed with an exception. Retrying after 0.844s. 
2026-09-16T05:14:39.082Z Now retrying operation 'insert block'. 
2026-09-16T05:14:39.323Z The operation 'insert block' has failed with an exception. Retrying after 2.608s. 
2026-09-16T05:14:41.931Z Now retrying operation 'insert block'. 
2026-09-16T05:14:42.131Z The operation 'insert block' has failed with an exception. Retrying after 4.02s. 
2026-09-16T05:14:46.152Z Now retrying operation 'insert block'. 
2026-09-16T05:14:48.525Z The operation 'insert block' has failed with an exception. Retrying after 6.246s. 
2026-09-16T05:14:54.771Z Now retrying operation 'insert block'. 
2026-09-16T05:14:55.002Z The operation 'insert block' has failed with an exception. Retrying after 7.927s. 
2026-09-16T05:15:02.929Z Now retrying operation 'insert block'. 
```
The retry at 05:15:02.929 succeeded ("Stored batch of requests" 05:15:02.937), 27 s after the sends
were enqueued and 7 s after their max sequencing time. The failing statement:

```
zcat canton.clog.gz | grep -aE '"@timestamp":"2026-09-16T05:1(4:[3-5]|5:[0-4])' | grep -aoE 'PSQLException: [^"\\]{0,90}' | sort | uniq -c
```
```
     36 PSQLException: ERROR: could not serialize access due to read/write dependencies among transactions
```
Effect on ordering, seen from the block stream: no event was sequenced between 05:14:31.24 and
05:14:41.12, between 05:14:41.30 and 05:14:51.33, or between 05:14:51.54 and 05:15:00.37, and the
first block after the stall carries a 28-event backlog:

```
zcat canton.clog.gz | grep -a 'BlockChunkProcessor:sequencer=globalSequencerSv1' | grep -a 'Processing block' \
  | grep -aE '"@timestamp":"2026-09-16T05:1(4:(3[1-9]|[45][0-9])|5:0[0-3])' \
  | grep -aoE '"@timestamp":"[^"]*"|Processing block [0-9]+, data chunk [0-9]+|Last chunk ts=[^,]*|[0-9]+ events' | paste - - - - | sed -E 's/"@timestamp"://' | sed -n '6,21p'
```
```
"2026-09-16T05:14:31.876Z"	Processing block 3586, data chunk 0	Last chunk ts=2026-09-16T05:14:31.237724Z	6 events
"2026-09-16T05:14:41.227Z"	Processing block 3606, data chunk 0	Last chunk ts=2026-09-16T05:14:31.237730Z	1 events
"2026-09-16T05:14:41.228Z"	Processing block 3608, data chunk 0	Last chunk ts=2026-09-16T05:14:41.123760Z	1 events
"2026-09-16T05:14:41.346Z"	Processing block 3609, data chunk 0	Last chunk ts=2026-09-16T05:14:41.123761Z	1 events
"2026-09-16T05:14:50.347Z"	Processing block 3631, data chunk 0	Last chunk ts=2026-09-16T05:14:41.295920Z	1 events
"2026-09-16T05:14:51.426Z"	Processing block 3637, data chunk 0	Last chunk ts=2026-09-16T05:14:45.492293Z	1 events
"2026-09-16T05:14:51.427Z"	Processing block 3638, data chunk 0	Last chunk ts=2026-09-16T05:14:51.333815Z	1 events
"2026-09-16T05:14:51.666Z"	Processing block 3639, data chunk 0	Last chunk ts=2026-09-16T05:14:51.333816Z	1 events
"2026-09-16T05:15:00.667Z"	Processing block 3659, data chunk 0	Last chunk ts=2026-09-16T05:14:51.537826Z	1 events
"2026-09-16T05:15:01.627Z"	Processing block 3662, data chunk 0	Last chunk ts=2026-09-16T05:15:00.372637Z	1 events
"2026-09-16T05:15:01.629Z"	Processing block 3664, data chunk 0	Last chunk ts=2026-09-16T05:15:01.437781Z	1 events
"2026-09-16T05:15:01.746Z"	Processing block 3667, data chunk 0	Last chunk ts=2026-09-16T05:15:01.439122Z	1 events
"2026-09-16T05:15:03.188Z"	Processing block 3672, data chunk 0	Last chunk ts=2026-09-16T05:15:01.682806Z	1 events
"2026-09-16T05:15:03.189Z"	Processing block 3672, data chunk 1	Last chunk ts=2026-09-16T05:15:01.682806Z	1 events
"2026-09-16T05:15:03.191Z"	Processing block 3672, data chunk 2	Last chunk ts=2026-09-16T05:15:01.682806Z	1 events
"2026-09-16T05:15:03.192Z"	Processing block 3672, data chunk 3	Last chunk ts=2026-09-16T05:15:01.682806Z	25 events
```
(The few events that did land at :41 and :51 were written by the other three sequencer nodes, whose own
insert retries happened to win; globalSequencerSv1's writer stored nothing in 05:14:40-05:14:59.)

```
zcat canton.clog.gz | grep -a 'ReferenceSequencerDriver:sequencer=globalSequencerSv1' | grep -a 'Stored batch of requests' \
  | grep -aoE '"@timestamp":"2026-09-16T05:1[45]:[0-9]' | cut -c16- | sort | uniq -c
```
```
     89 026-09-16T05:14:0
     39 026-09-16T05:14:1
     26 026-09-16T05:14:2
      6 026-09-16T05:14:3
     11 026-09-16T05:15:0
```

## 8. The contention is structural: four sequencer writers on one reference-driver DB, getting worse through the run

```
zcat canton.clog.gz | grep -a "operation 'insert block' has failed" | grep -aoE '"@timestamp":"[^"]{16}' | cut -c15- | sort | uniq -c
zcat canton.clog.gz | grep -a 'has failed with an exception' | grep -aE '"@timestamp":"2026-09-16T05:1(4:[3-5]|5:[0-4])' | grep -aoE '(participant|sequencer|mediator)=[A-Za-z0-9]+' | sort | uniq -c
zcat canton.clog.gz | grep -a 'has failed with an exception' | grep -aoE "operation '[^']*'" | sort | uniq -c
```
```
     93 2026-09-16T05:10
    134 2026-09-16T05:11
    212 2026-09-16T05:12
    346 2026-09-16T05:13
    210 2026-09-16T05:14
     38 2026-09-16T05:15
     24 sequencer=globalSequencerSv1
     24 sequencer=globalSequencerSv2
     22 sequencer=globalSequencerSv3
     20 sequencer=globalSequencerSv4
   1033 operation 'insert block'
```
Every DbStorage retry in the run is the reference driver's `insert block`, only on the four global
sequencer nodes (globalSequencerSv1-4 share the driver's Postgres tables; participants and mediators
report none). The failure rate climbs monotonically 05:10 -> 05:13 as the shared DB fills up.

Node inventory of this single Canton JVM against one Postgres 14:

```
zcat canton.clog.gz | grep -aoE '"logger_name":"[^"]*:(participant|sequencer|mediator)=[A-Za-z0-9]+' | grep -oE '(participant|sequencer|mediator)=[A-Za-z0-9]+' | sort -u | tr '\n' ' '
```
```
mediator=globalMediatorSv1 mediator=globalMediatorSv2 mediator=globalMediatorSv3 mediator=globalMediatorSv4 mediator=splitwellMediator mediator=splitwellUpgradeMediator participant=aliceParticipant participant=bobParticipant participant=splitwellParticipant participant=sv1Participant participant=sv2Participant participant=sv3Participant participant=sv4Participant sequencer=globalSequencerSv1 sequencer=globalSequencerSv2 sequencer=globalSequencerSv3 sequencer=globalSequencerSv4 sequencer=splitwellSequencer sequencer=splitwellUpgradeSequencer
```

## 9. Second stall: DB pool exhaustion on all four sequencers while the retry was pending

```
zcat canton.clog.gz | grep -aE '"level":"(WARN|ERROR)"' | python3 -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); t=j['@timestamp']
    if '2026-09-16T05:14:30'<=t<='2026-09-16T05:15:46': print(t, j['level'], j['logger_name'][:60], '|', j['message'][:175])" | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
```
```
2026-09-16T05:15:34.589Z WARN c.d.c.r.DbStorageSingle:sequencer=globalSequencerSv3 | DB_CONNECTION_LOST(13,c57ae348): Database health check failed to establish a valid connection: slick-globalSequencerSv3-12 - Connection is not available, request timed out after 5000ms (total=8, active=8, idle=0, waiting
2026-09-16T05:15:34.590Z WARN c.d.c.r.DbStorageSingle:sequencer=globalSequencerSv2 | DB_CONNECTION_LOST(13,2d11ec79): Database health check failed to establish a valid connection: slick-globalSequencerSv2-8 - Connection is not available, request timed out after 5000ms (total=8, active=8, idle=0, waiting=
2026-09-16T05:15:34.590Z WARN c.d.c.r.DbStorageSingle:sequencer=globalSequencerSv4 | DB_CONNECTION_LOST(13,f3cda368): Database health check failed to establish a valid connection: slick-globalSequencerSv4-9 - Connection is not available, request timed out after 5000ms (total=8, active=8, idle=0, waiting=
2026-09-16T05:15:34.599Z WARN c.d.c.r.DbStorageSingle:sequencer=globalSequencerSv1 | DB_CONNECTION_LOST(13,8168eb56): Database health check failed to establish a valid connection: slick-globalSequencerSv1-7 - Connection is not available, request timed out after 5000ms (total=8, active=8, idle=0, waiting=
2026-09-16T05:15:39.584Z WARN c.d.c.s.s.SequencerRuntime:sequencer=globalSequencerSv3/psid=global-domain::12205673851e:: | Sequencer is unhealthy, so disconnecting all members. Can't connect to database
2026-09-16T05:15:39.589Z WARN c.d.c.s.s.SequencerRuntime:sequencer=globalSequencerSv4/psid=global-domain::12205673851e:: | Sequencer is unhealthy, so disconnecting all members. Can't connect to database
2026-09-16T05:15:39.590Z WARN c.d.c.s.s.SequencerRuntime:sequencer=globalSequencerSv2/psid=global-domain::12205673851e:: | Sequencer is unhealthy, so disconnecting all members. Can't connect to database
2026-09-16T05:15:39.598Z WARN c.d.c.s.s.SequencerRuntime:sequencer=globalSequencerSv1/psid=global-domain::12205673851e:: | Sequencer is unhealthy, so disconnecting all members. Can't connect to database
2026-09-16T05:15:41.698Z WARN c.d.c.s.m.Mediator:mediator=splitwellMediator/psid=splitwell::12206496 | Detected late processing (or clock skew) of batch with timestamp = 2026-09-16T05:15:10.622196Z; delta = PT31.076385S after sequencing (> threshold = PT20S)
2026-09-16T05:15:42.577Z WARN c.d.c.s.m.Mediator:mediator=splitwellUpgradeMediator/psid=splitwellUpg | Detected late processing (or clock skew) of batch with timestamp = 2026-09-16T05:15:10.622282Z; delta = PT31.954975S after sequencing (> threshold = PT20S)
```
At 05:15:29-34 every global sequencer had all 8 pool connections busy (`total=8, active=8, idle=0`)
and its 5 s health check could not get one; at 05:15:39 all four declared themselves unhealthy and
dropped every member. Even the unrelated splitwell mediators (separate synchronizer, same Postgres)
processed a 05:15:10 batch 31 s late. The participant admin API was also slow: sv1's
ListDecentralizedNamespaceDefinition took 7.3 s in the middle of the plugin's poll loop:

```
zcat canton.clog.gz | grep -a 'ApiRequestLogger:participant=sv1Participant' | grep -a 'ListDecentralizedNamespaceDefinition' \
  | grep -aE '"@timestamp":"2026-09-16T05:15:(0[0-9]|1[0-9]|20)' | grep -aE 'received a message|: completed' \
  | grep -aoE '"@timestamp":"[^"]*"|grpc:/127.0.0.1:[0-9]+: (received a message|completed)' | paste - - | sed -E 's/"@timestamp"://'
```
```
"2026-09-16T05:15:02.286Z"	grpc:/127.0.0.1:50806: received a message
"2026-09-16T05:15:02.288Z"	grpc:/127.0.0.1:50806: completed
"2026-09-16T05:15:12.291Z"	grpc:/127.0.0.1:50806: received a message
"2026-09-16T05:15:19.593Z"	grpc:/127.0.0.1:50806: completed
```
```
zcat canton.clog.gz | grep -aoE '^\{"@timestamp":"2026-09-16T05:1[45]:[0-9]' | cut -c16-33 | sort | uniq -c
```
```
  96826 2026-09-16T05:14:0
  26410 2026-09-16T05:14:1
  22559 2026-09-16T05:14:2
  14952 2026-09-16T05:14:3
   2628 2026-09-16T05:14:4
   2518 2026-09-16T05:14:5
   5727 2026-09-16T05:15:0
    724 2026-09-16T05:15:1
   1466 2026-09-16T05:15:2
   1085 2026-09-16T05:15:3
   6396 2026-09-16T05:15:4
```
The whole Canton JVM went nearly silent in 05:15:10-05:15:19 (724 lines vs tens of thousands
minutes earlier), consistent with a DB-wide stall rather than one hot component.

## 10. Contrast: the same reset one test earlier took 1.1 s

```
zcat canton_network_test.clog.gz | grep -a 'ResetDecentralizedNamespace' | grep -aE 'All required proposals|has been reset' \
  | python3 -c "
import sys,json
for l in sys.stdin: j=json.loads(l); print(j['@timestamp'], j['message'][:60])" | tail -3
```
```
2026-09-16T05:13:45.075Z All required proposals to reset SV namespace submitted, waiting for it t
2026-09-16T05:13:46.134Z decentralized namespace has been reset
2026-09-16T05:14:35.852Z All required proposals to reset SV namespace submitted, waiting for it t
```
Four previous teardowns in this suite reset the namespace in 0.6-1.1 s each (05:10:29, 05:11:49,
05:12:32, 05:13:45). The plugin logic did not change between them and the failure; the sequencer did.

## Root cause / hypothesis

Proven by the logs above:
- The job failed because ResetDecentralizedNamespace's 60 s `retry_until_true` expired and the
  plugin's catch-all called `sys.exit(1)` (ResetTopologyStatePlugin.scala:105-107 at b5d5645c56),
  killing sbt with no test report and skipping checkErrors. No test assertion failed.
- The three reset proposals (sv1, sv2, sv4; serial 22) were signed, accepted by each participant's
  topology manager and accepted by globalSequencerSv1's SendAsync at 05:14:35.83-.86 with max
  sequencing time 05:14:55.8, but were only ordered at 05:15:01.68 (block 3672) and therefore
  rejected as `Timeout`; the one outbox retry at 05:15:19.6 was never ordered before the log ends.
- The 27 s ordering gap is globalSequencerSv1's reference-driver `insert block` write failing
  repeatedly on PostgreSQL SQLSTATE 40001 ("could not serialize access due to read/write dependencies
  among transactions") with exponential backoff reaching 7.9 s; its batch was stored only at
  05:15:02.937. All four global sequencers were in the same retry loop; failures rose from 93/min at
  05:10 to 346/min at 05:13.
- A second stall followed: all four sequencers' 8-connection pools were fully busy at 05:15:29-34,
  the DB health check failed, and at 05:15:39 they disconnected all members.

Inferred, not proven:
- The sequencer nodes and the shared Postgres 14 on the k8s runner were resource-starved (the
  reference driver's serializable inserts from four writers conflict more as the tables grow and
  as the runner slows; even the splitwell mediators on a different synchronizer processed a batch
  31 s late, and sv1's participant admin API took 7.3 s for a trivial topology read). There is no
  postgres log or host metrics in the artifacts to show what held the 8 connections.
- The 20 s max sequencing time on topology broadcasts (Canton default for
  `topologyTransactionRegistrationTimeout`; not verified against the 3.6.0 snapshot) plus the outbox's
  10 s requeue backoff means a single ordering stall of more than ~20 s costs at least ~45 s of the
  plugin's 60 s budget, so this failure mode needs only one bad half-minute at teardown.

## Duplicates / related

- No packet in ci-triage/ mentions ResetDecentralizedNamespace, `insert block`, SQLSTATE 40001 or
  pool exhaustion (`grep -li 'decentralized namespace\|insert block\|40001\|active=8' ci-triage/*.md`
  -> none).
- 10094 / 10091 (sequencer acknowledge-signed stall, wall-clock shard, canton 3.5.15): a different
  symptom (checkErrors WARN, AcknowledgeSigned DEADLINE_EXCEEDED after 120 s on globalSequencerSv1)
  but the same shape - the DB-backed sequencer in a wall-clock shard going unresponsive for tens of
  seconds mid-run, root cause left open there. Worth re-checking that run's canton log for
  `insert block` 40001 retries and `active=8` pool exhaustion; if present, 10139 explains 10094.
- 10048 (BFT deadlock) is not related: this shard runs the reference driver, not BFT (section 3).
- Not the same as the 10088 LSU failures or the 10084 indexer reconnect WARNs.

## Suggested next step / owner

1. Test-infra (splice): stop letting a teardown plugin kill the sbt JVM. `sys.exit(1)` in
   ResetTopologyStatePlugin turns a slow sequencer into a report-less job failure that also skips
   checkErrors; failing the suite (throw) or logging and letting the next env creation fail would keep
   the ScalaTest report and the remaining 5 tests. Also consider a longer wait than 60 s (or an
   explicit resubmit after a `not sequenced` Timeout) since one 20 s stall already consumes 45 s.
2. Canton / test infra: the reference block sequencer driver with four writers on one Postgres
   produces a monotonically rising SQLSTATE 40001 retry storm in this shard (section 8). Options are
   fewer sequencer nodes in `simpleTopology4Svs` for wall-clock shards, a larger sequencer DB pool than
   8 connections, or a Canton-side cap on the `insert block` backoff (7.9 s between attempts is what
   pushed the sends past their 20 s deadline). Owner: whoever owns the reference driver's
   DbReferenceBlockOrderingStore; confirm against 3.6.0-snapshot.20260910 sources, not `canton/`.
3. Re-run to confirm it is a flake (expected to pass); file under cn-test-failures as
   "SvStateManagementIntegrationTest teardown: ResetDecentralizedNamespace 60s timeout, reference
   sequencer insert-block 40001 storm + DB pool exhaustion".

## Summary

wall-clock-time(2), job 104671796734, main nightly at b5d5645c56, canton 3.6.0-snapshot.20260910.
Four SvStateManagementIntegrationTest tests passed; during the teardown of the fifth the
ResetDecentralizedNamespace plugin waited 60 s for its serial-22 proposals (from sv1, sv2, sv4) to
become effective, gave up at 05:15:35.856 and called sys.exit(1), so sbt exited 1 with no report and
checkErrors skipped. The proposals were accepted by globalSequencerSv1 at 05:14:35.8 with a 20 s max
sequencing time but only ordered at 05:15:01.7, because the sequencer's reference-driver `insert
block` kept failing on PostgreSQL serialization conflicts (SQLSTATE 40001) with exponential backoff up
to 7.9 s; the outbox's retry at 05:15:19.6 then ran into all four sequencers exhausting their 8-connection
DB pools (05:15:34) and disconnecting all members (05:15:39). Infra/flake, not a code regression;
the plugin's sys.exit turns it into a report-less job failure.
