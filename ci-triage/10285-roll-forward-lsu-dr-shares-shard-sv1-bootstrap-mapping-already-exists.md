# 10285 - roll-forward-lsu (0): RollForwardLsuDRIntegrationTest ran in the same shard (same Canton) after RollForwardLsuIntegrationTest; sv1's bootstrap re-proposes SynchronizerParametersState serial 1 on sv1Participant, gets TOPOLOGY_MAPPING_ALREADY_EXISTS forever, no app initializes (run 37434381333)

New. Not family H/H2 (no LSU-time restart, no sys.exit, the job ended normally with a ScalaTest report) and not 10269.
main 08e28cbf6a (#7638 itself), canton 3.6.1. The roll-forward-lsu test split put BOTH suites into shard 0 and
nothing into shard 1, because the cached JUnit reports gave both suites a time of 0 (`bucket 0: 2 tests, total time:
0`); in the 13 previous main runs since 10-05 the split was always one suite per shard. RollForwardLsuIntegrationTest
ran first and passed; its sv1 bootstrapped a synchronizer from sv1Participant at 08:23:49.809. The synchronizer id is
deterministic (`global-domain::<namespace of sv1Participant>`), so when RollForwardLsuDRIntegrationTest's sv1 ran the
same bootstrap against the same long-running sv1Participant, the participant already held
`SynchronizerParametersState` serial 1 for `global-domain::1220ef5200eb...` in its authorized store. The re-proposal
(content identical) is rejected with `TOPOLOGY_MAPPING_ALREADY_EXISTS` from 08:27:04.871, which the init step retries
as transient, 75 times until shutdown. sv1 never initializes, every other app waits on it, and `startAllSync` times
out on aliceValidator (the first node waited on) at 08:31:44.690. 1 test passed, 1 failed.

- Run: https://github.com/canton-network/splice/actions/runs/37434381333, main 08e28cbf6a ("Retry
  getPhysicalSynchronizerId in JoiningNodeInitializer (#7638)"), job 112173680724
  `ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)`. Only failed job (run still in progress when checked;
  `roll-forward-lsu (1)` had no tests).
- Runtime canton: 3.6.1 (`git show 08e28cbf6a:nix/canton-sources.json | grep -m1 '"version"'`).
- Component: CI test split (`.github/actions/tests/split_tests`) plus test isolation between the two roll-forward
  LSU suites; the rejected call is `SV1Initializer.bootstrapDomain` (splice SV app).
- Artifact: `logs-roll-forward-lsu-0` in `log/10285/logs-roll-forward-lsu-0/`.

## 1. Failing assertion

```
$ gh api repos/canton-network/splice/actions/jobs/112173680724/logs > log/10285/job.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10285/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
[info] - roll forward LSU DR *** FAILED ***
[info] Run completed in 8 minutes, 31 seconds.
[info] Tests: succeeded 1, failed 1, canceled 0, ignored 0, pending 0

$ sed -E 's/\x1b\[[0-9;]*m//g' log/10285/job.log | grep -a -m1 -A12 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | sed -n '2,5p;10,13p'
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:189)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.startAllSync(SpliceTests.scala:443)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.startAllSync$(SpliceTests.scala:441)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.startAllSync(RollForwardLsuDRIntegrationTest.scala:44)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.$anonfun$new$3(RollForwardLsuDRIntegrationTest.scala:223)
```

`RollForwardLsuDRIntegrationTest.scala:222-223` at 08e28cbf6a is the first step of the test,
`clue("Start nodes before DR") { startAllSync((aliceValidatorBackend +: allNodes)*) }`. `startAllSync`
(`SpliceTests.scala:441-444`) starts all nodes, then waits on each in order; aliceValidator is first.

## 2. Two suites in one shard: the split

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10285/job.log | grep -a -m1 'test_names:' | cut -c1-200
  test_names: [["org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuIntegrationTest","org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest"],[]]

$ gh api repos/canton-network/splice/actions/jobs/112172491071/logs > log/10285/split.log    # "Split the tests into parallel runs"
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10285/split.log | grep -a -E 'RollForward|added |^bucket [0-9]'
Parsing xml report TEST-org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest.xml
Parsing xml report TEST-org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuIntegrationTest.xml
added org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuIntegrationTest to bucket 0, total time: 0
bucket 0 has 1 tests
added org.lfdecentralizedtrust.splice.integration.tests.RollForwardLsuDRIntegrationTest to bucket 0, total time: 0
bucket 0 has 2 tests
bucket 0: 2 tests, total time: 0
bucket 1: 0 tests, total time: 0
```

Every earlier main run since 10-05 split one suite per shard (script loops over the workflow's runs, finds the split
job, prints its bucket lines and the two shard conclusions; output in `log/10285/split-history-recent.txt`):

```
$ cat log/10285/split-history-recent.txt | cut -c1-150
37434381333 2026-10-06T08:10:45Z 08e28cbf6a | bucket 0: 2 tests, total time: 0 bucket 1: 0 tests, total time: 0  | 0=failure,1=null
37385323284 2026-10-05T22:53:52Z 4870bbe980 | bucket 0: 1 tests, total time: 258.767 bucket 1: 1 tests, total time: 185.005  | 0=success,1=success
37381236181 2026-10-05T22:15:06Z 116d003064 | bucket 0: 1 tests, total time: 258.581 bucket 1: 1 tests, total time: 196.424  | 0=success,1=success
37359998986 2026-10-05T18:58:32Z fc30189983 | bucket 0: 1 tests, total time: 257.855 bucket 1: 1 tests, total time: 220.56  | 0=success,1=success
37351458092 2026-10-05T17:50:40Z 073e1872f0 | bucket 0: 1 tests, total time: 255.496 bucket 1: 1 tests, total time: 190.036  | 1=success,0=success
37334129283 2026-10-05T15:36:00Z 220f54c3f1 | bucket 0: 1 tests, total time: 259.998 bucket 1: 1 tests, total time: 183.377  | 0=success,1=success
...
37307002868 2026-10-05T12:04:10Z 1bdfc57343 | bucket 0: 1 tests, total time: 259.852 bucket 1: 1 tests, total time: 204.99  | 0=success,1=success
```

The split (`split_tests.ts:37-51,53-68` at 08e28cbf6a) is greedy over estimated times read from
`/cache/test-reports/TEST-*.xml` (`post_sbt/action.yml:53-62` copies every job's reports there). With both estimates 0,
`bucketTimes.indexOf(Math.min(...))` returns 0 for both suites. Until #7591 (567cd541b6, 2026-10-01) a 0 time fell back
to the maximum known time (`testTimes[testName] || maxTestTime`); since then it is kept (`?? maxTestTime`, then
`Math.max(known, 0.0)`), so a zero or negative cached time now collapses the split.

## 3. Same Canton, same sv1Participant, deterministic synchronizer id

The first suite's sv1 bootstraps `global-domain::1220ef5200eb...` at 08:23:49; the DR suite's sv1 hits the same id at
08:27:04:

```
$ T=log/10285/logs-roll-forward-lsu-0/canton_network_test.clog.gz
$ zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Failed clue: Start nodes" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-180
2026-10-06T08:23:16.908Z Starting test suite 'RollForwardLsuIntegrationTest'...",
2026-10-06T08:23:22.457Z Starting 'RollForwardLsuIntegrationTest/roll forward LSU'...",
2026-10-06T08:26:33.001Z Test succeeded: 'RollForwardLsuIntegrationTest/roll forward LSU'",
2026-10-06T08:26:38.699Z Starting test suite 'RollForwardLsuDRIntegrationTest'...",
2026-10-06T08:26:42.127Z Starting 'RollForwardLsuDRIntegrationTest/roll forward LSU DR'...",
2026-10-06T08:31:44.690Z Failed clue: Start nodes before DR",
2026-10-06T08:31:45.178Z Test failed: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR', message: Condition never became true within 5 minutes, location: SeeStackDepthException

$ zcat $T | grep -a -E "global-domain::1220ef5200eb" | grep -a -E "Proposing initial mapping|Check whether sequencer is initialized" | jq -r '"\(."@timestamp") \(.logger_name|sub(".*:";"")) \(.message|.[0:110]|gsub("\n";" "))"' | sed -E 's/1220[0-9a-f]{60}/../g' | awk '!s[substr($0,26)]++' | head -8
2026-10-06T08:23:49.806Z RollForwardLsuIntegrationTest/config=d21b6bff/SV=sv1 Proposing initial mapping for SequencerSynchronizerState with serial 1: SequencerSynchronizerState(synchronize
2026-10-06T08:23:49.806Z RollForwardLsuIntegrationTest/config=d21b6bff/SV=sv1 Proposing initial mapping for MediatorSynchronizerState with serial 1: MediatorSynchronizerState(synchronizerI
2026-10-06T08:27:04.893Z RollForwardLsuDRIntegrationTest/config=76ad3e6c/SV=sv1 The operation 'Check whether sequencer is initialized and establish it if needed' failed with a retryable erro
```

Both suites use the participants of the job's long-running Canton (`canton.clog.gz`, 08:19:25-08:31:52); the DR suite
brings its own standalone sequencers and mediators (`canton-standalone-roll-forward-lsu-before-dr.clog.gz`, from
08:26:42.744). The synchronizer id is computed from the participant's namespace only
(`SV1Initializer.scala:449-456`), so it is the same for both suites.

## 4. The rejected re-proposal on sv1Participant

```
$ C=log/10285/logs-roll-forward-lsu-0/canton.clog.gz
$ zcat $C | grep -a "participant=sv1Participant" | grep -a "Replace SynchronizerParametersState" | jq -r '."@timestamp"' | sed -n '1,3p;$p'; zcat $C | grep -a "participant=sv1Participant" | grep -a -c "Replace SynchronizerParametersState"
2026-10-06T08:23:49.809Z
2026-10-06T08:27:04.863Z
2026-10-06T08:27:05.104Z
2026-10-06T08:31:41.222Z
76

$ zcat $C | grep -a "participant=sv1Participant" | grep -a "Replace SynchronizerParametersState" | jq -c .message | sed -n 1p > first.txt
$ zcat $C | grep -a "participant=sv1Participant" | grep -a "Replace SynchronizerParametersState" | jq -c .message | sed -n 2p > second.txt
$ diff first.txt second.txt && echo IDENTICAL
IDENTICAL

$ zcat $C | grep -a "participant=sv1Participant" | grep -a TOPOLOGY_MAPPING_ALREADY_EXISTS | jq -r '"\(."@timestamp") \(.level) \(.logger_name|sub(":.*";"")|sub(".*\\.";"")) \(.message|.[0:200]|gsub("\n";" "))"' | sed -E 's/1220[0-9a-f]{60}/../g' | head -2
2026-10-06T08:27:04.871Z INFO ParticipantNodeBootstrap$$anon$2 TOPOLOGY_MAPPING_ALREADY_EXISTS(10,df02d526): A matching topology mapping authorized with the same keys already exists in this state
2026-10-06T08:27:04.872Z INFO ApiRequestLogger Request com.digitalasset.canton.topology.admin.v30.TopologyManagerWriteService/Authorize by grpc:/127.0.0.1:53726: failed with ALREADY_EXISTS/TOPOLOGY_MAPPING_ALREADY_EXISTS(10,df02d526): A matching t
```

One proposal by the first suite (accepted), then 75 by the DR suite, each rejected although the content is identical.
On the app side the error arrives as a retryable error and the step retries until shutdown:

```
$ zcat $T | grep -a 'config=76ad3e6c/SV=sv1"' | grep -a -c "Check whether sequencer is initialized and establish it if needed' failed"
75
$ zcat $T | grep -a 'config=76ad3e6c/SV=sv1"' | grep -a "Check whether sequencer is initialized" | jq -r '"\(."@timestamp") \(.message|.[0:230]|gsub("\n";" | "))"' | sed -n '1p;$p'
2026-10-06T08:27:04.893Z The operation 'Check whether sequencer is initialized and establish it if needed' failed with a retryable error (full stack trace omitted): | category=Some(InvalidGivenCurrentSystemStateResourceExists) | ErrorInfoDetail(TOPOLOGY_MAPPI
2026-10-06T08:31:47.380Z Giving up on retrying the operation 'Check whether sequencer is initialized and establish it if needed' due to shutdown. Last attempt was Some(transient error (request infinite retries)) with exception: UNAVAILABLE: io exception
```

Call site at 08e28cbf6a: `SV1Initializer.scala:476-481` (`retryProvider.ensureThatO(RetryFor.WaitingOnInitDependency,
"init_sequencer", ...)`; the sequencer status check fails because the new standalone sequencer is not initialized, so
the establish block runs) -> `SV1Initializer.scala:529-532` `participantAdminConnection.proposeInitialDomainParameters`
-> `TopologyAdminConnection.scala:1384-1395` `proposeMapping(TopologyStoreId.Authorized,
SynchronizerParametersState(...), serial = PositiveInt.one, isProposal = false)`. The step before it,
`proposeInitialDecentralizedNamespaceDefinition` (`SV1Initializer.scala:484-489`), tolerates the leftover mapping
(`Existing mapping found for DecentralizedNamespaceDefinition`, 75 times); the parameters proposal does not.

## 5. Everything else waits on sv1

```
$ zcat $T | grep -a 'RollForwardLsuDR' | grep -a -E 'app initialization: .* started' | jq -r '"\(.logger_name|sub(".*/";"")) \(."@timestamp") \(.message)"' | awk '{last[$1]=$0} END{for(k in last) print last[k]}' | sort -k2 | cut -c1-150
validator=aliceValidator 2026-10-06T08:26:42.510Z aliceValidator app initialization: Getting BFT scan connection started
scan=sv1Scan 2026-10-06T08:26:42.756Z sv1Scan app initialization: Get primary party started
scan=sv2Scan 2026-10-06T08:26:43.048Z sv2Scan app initialization: Get primary party started
scan=sv3Scan 2026-10-06T08:26:43.324Z sv3Scan app initialization: Get primary party started
scan=sv4Scan 2026-10-06T08:26:43.594Z sv4Scan app initialization: Get primary party started
SV=sv1 2026-10-06T08:26:43.880Z sv1 app initialization: SV1Initializer bootstrapping Dso started
SV=sv2 2026-10-06T08:26:44.146Z sv2 app initialization: JoiningNodeInitializer joining Dso with key started
SV=sv3 2026-10-06T08:26:44.416Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started
SV=sv4 2026-10-06T08:26:44.707Z sv4 app initialization: JoiningNodeInitializer joining Dso with key started
```

No app of the DR suite gets past its first DSO-dependent step; aliceValidator is only the node the test waited on first.

## Verdict

- New ref. Not family H/H2 and not 10269: the failure is at the first step of the DR test, before any LSU, with a
  normal ScalaTest report (no `sys.exit`, no 60 min cancel). Closest in spirit to family M (state leaking between
  suites of one shard), here through the participant's topology store instead of a Postgres table.
- Trigger: CI split. Both roll-forward suites were put into shard 0 because their cached report times were 0; since
  #7591 a zero time is no longer replaced by the maximum known time. Deterministic once the split co-locates the two
  suites in that order: the DR suite cannot bootstrap a second synchronizer from the same sv1Participant.
- Fix options (none written; CI and production code, left to owners):
  1. CI: in `split_tests.ts` treat a non-positive time as unknown (`known > 0 ? known : maxTestTime`) and rebuild
     `dist/index.js`. Restores one suite per shard here, but only while parallelism >= number of suites.
  2. CI, more robust: run the two roll-forward suites in separate jobs (or one suite per test_names file) so they
     never share a Canton, independent of the timing cache.
  3. App (`SV1Initializer.bootstrapDomain`): reuse an existing identical `SynchronizerParametersState` (and the
     sequencer/mediator states) in the participant's authorized store instead of re-proposing serial 1; useful for an
     sv1 that restarts mid-bootstrap too.
- Resolution for this run: rerun (the next split reads fresh report times).
- Not verified: why both cached reports had time 0 (the `/cache/test-reports` volume is not visible from here; the
  previous main run 37385323284 split normally 9 h earlier); whether Canton rejects an identical re-proposal by design
  in 3.6.1 (no jar inspected); whether the SequencerSynchronizerState / MediatorSynchronizerState re-proposals would
  have been rejected too (the parallel `tupled` fails on the first error); that the DR suite passes when it runs after
  RollForwardLsuIntegrationTest with this fixed (not run).
