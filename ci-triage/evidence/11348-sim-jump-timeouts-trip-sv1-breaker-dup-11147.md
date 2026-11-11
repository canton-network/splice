# 11348 - ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock steps trip the sv1 medium-priority circuit breaker (run 42923829975)

DUPLICATE of 11147 (family AF). main 2ef0423 ("Disable BFT sequencer blacklisting in simtime integration tests
(#8356)"), job docker-canton-simtime (0), Canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252. All 5 tests passed;
checkErrors flagged exactly one line in canton_network_test.clog: the WARN `Circuit breaker medium-priority-connection
tripped after 20 failures` from sv1 at 00:33:49.472, stack `ABORTED: LOCAL_VERDICT_TIMEOUT(2,0)`, during the hourly
sim-clock loop of ScanNewNetworkBackfillingTimeBasedIntegrationTest. Same mechanism as 11147 and 11291; the only
difference is the SV (sv1 here, sv2 in both earlier hits). The open fix PR splice#8420 disables the circuit breakers
of all SV apps in this test, so it covers sv1 too. Not 11182 (same test, different signature, absent here).

- Run: https://github.com/canton-network/splice/actions/runs/42923829975, main 2ef0423 ("Disable BFT sequencer
  blacklisting in simtime integration tests (#8356)"), job 130205972060
  `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (sim-clock steps vs SV circuit breaker config).

## 0. Issue

```
gh api repos/DACH-NY/cn-test-failures/issues/11348 --jq .body
```
```
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42923829975).
Job: docker-canton-simtime (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [2ef0423](https://github.com/canton-network/splice/commit/2ef042397984ed873ab5e815fd747a87d0c62258) Disable BFT sequencer blacklisting in simtime integration tests (#8356)
Actor: rautenrieth-da
```
Job resolution (the only failed job of that name in the run; the run has a second failed job, key-download (0),
which is 11347):
```
gh api "repos/canton-network/splice/actions/runs/42923829975/jobs?filter=latest&per_page=100" --jq '.jobs[]|select(.conclusion=="failure")|"\(.id) \(.name) | \([.steps[]|select(.conclusion=="failure")|.name]|join(","))"'
git show 2ef042397984ed873ab5e815fd747a87d0c62258:nix/canton-sources.json | grep -m1 version
```
```
130205489387 ci / scala_test_key_download / key-download (0) | Run Tests
130205972060 ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0) | Run Tests
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

## 1. Flagged lines

Job log saved as `log/11348/job.log` (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/130205972060/logs`).
```
REF=11348; sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$REF/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-700
```
```
##[error]Process completed with exit code 1.
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 10 minutes, 1 second.
[info] Tests: succeeded 5, failed 0, canceled 0, ignored 0, pending 0
```
```
Found problems in log/canton_network_test.clog:
{"@timestamp":"2026-11-11T00:33:49.472Z","message":"Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:ScanNewNetworkBackfillingTimeBasedIntegrationTest/config=4f799cb1/SV=sv1","thread_name":"ScanNewNetworkBackfillingTimeBasedIntegrationTest-4f799cb1-env-ec-2795","level":"WARN","stack_trace":"io.grpc.StatusRuntimeException: ABORTED: LOCAL_VERDICT_TIMEOUT(2,0): Rejected transaction due to a participant determined timeout \n\tat io.grpc.Status.asRuntimeException(Status.java:532)\n\tat io.grpc.stub.ClientCalls$UnaryStreamToFuture.onClose(ClientCalls.java:638)\n\tat org.lfdecentralizedtrust.splice.admin.api.cl
Total: 1 lines with problems.
```
Suites in the shard:
```
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/11348/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
ScanNewNetworkBackfillingTimeBasedIntegrationTest
ScanTimeBasedIntegrationTest
```

## 2. Which test was running

Artifact `logs-docker-canton-simtime-0` (id 13815504986, created 2026-11-11T00:35:25Z, attempt 1) in
`log/11348/logs-docker-canton-simtime-0/`
(`gh run download 42923829975 --repo canton-network/splice -n logs-docker-canton-simtime-0 -D log/11348/logs-docker-canton-simtime-0`).
```
T=log/11348/logs-docker-canton-simtime-0/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | tail -3
zcat $T | grep -a 'ScanNewNetworkBackfilling' | grep -a -c 'advancing time by PT1H'
```
```
2026-11-11T00:31:18.397Z Starting test suite 'ScanNewNetworkBackfillingTimeBasedIntegrationTest'...",
2026-11-11T00:31:23.358Z Starting 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'...",
2026-11-11T00:34:38.195Z Test succeeded: 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'",
42
```
The WARN at 00:33:49.472 falls inside the only test of the suite, during its hourly `advanceTime` loop (42 one-hour
steps; loop at `ScanNewNetworkBackfillingTimeBasedIntegrationTest.scala:140`, see the 11147 packet).

## 3. The confirming check of family AF: which 20 failures tripped the sv1 medium breaker

Helpers: `log/tmp/fmt.py` and `log/tmp/spans.py` are the helpers of the 11147 packet (sections 3 and 6), with
`spans.py` taking the SV as a third argument (`SV = sys.argv[3] if len(sys.argv) > 3 else 'sv2'`, and
`lg.endswith('/SV='+SV)` in place of `lg.endswith('/SV=sv2')`).

Medium-priority failures of sv1 (the Medium triggers listed in the 11147 packet, section 6), numbered, with the
sim-clock jumps and the sv1 breaker lines:
```
T=log/11348/logs-docker-canton-simtime-0/canton_network_test.clog.gz
zcat $T | python3 -I log/tmp/spans.py 00:33:20.000 00:33:49.472 sv1 | grep -E 'JUMP|CB|FAIL (SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger) ' | awk '/FAIL/{n++; print $0"   #"n; next} {print}'
```
```
00:33:20.125 CB   Resetting circuit breaker as last failure was PT3H ago which is more than 15 minutes
00:33:20.251 JUMP 1970-01-03T01:10:32.998Z
00:33:22.317 JUMP 1970-01-03T02:10:32.998Z
00:33:24.021 JUMP 1970-01-03T03:10:32.998Z
00:33:24.419 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #1
00:33:24.424 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #2
00:33:25.441 CB   Resetting circuit breaker as last failure was PT4H ago which is more than 15 minutes
00:33:25.855 JUMP 1970-01-03T04:10:32.998Z
00:33:26.571 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #3
00:33:27.357 CB   Resetting circuit breaker as last failure was PT3H ago which is more than 15 minutes
00:33:36.570 JUMP 1970-01-03T05:10:32.998Z
00:33:37.567 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
00:33:38.646 JUMP 1970-01-03T06:10:32.998Z
00:33:39.034 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
00:33:39.784 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
00:33:40.529 JUMP 1970-01-03T07:10:32.998Z
00:33:41.218 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #4
00:33:42.623 JUMP 1970-01-03T08:10:32.998Z
00:33:44.245 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
00:33:44.713 JUMP 1970-01-03T09:10:32.998Z
00:33:45.360 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #5
00:33:45.362 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #6
00:33:45.362 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #7
00:33:45.363 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #8
00:33:45.367 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #9
00:33:45.368 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #10
00:33:45.374 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #11
00:33:46.599 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
00:33:46.804 JUMP 1970-01-03T10:10:32.998Z
00:33:47.422 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #12
00:33:47.423 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #13
00:33:47.425 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #14
00:33:47.426 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #15
00:33:47.426 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #16
00:33:47.429 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #17
00:33:47.430 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #18
00:33:48.761 JUMP 1970-01-03T11:10:32.998Z
00:33:49.045 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
00:33:49.457 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #19
00:33:49.460 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #20
00:33:49.464 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #21
00:33:49.465 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #22
00:33:49.467 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #23
00:33:49.471 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #24
00:33:49.472 CB   Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
```
The last successful medium submissions before the trip (a success zeroes the consecutive-failure count, 11147
packet section 4):
```
zcat $T | awk '/T00:33:(2[0-9]|3[0-9]|4[0-9])/' | grep -a 'SV=sv1' | grep -a 'Completed processing with outcome' | grep -a -E '"span-name":"(SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger)"' | python3 -I -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'][11:23], j['span-name'], '|', j['message'][:110])" | tail -3
```
```
00:33:40.842 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 7, processingDelay=PT2H
00:33:42.950 SummarizingMiningRoundTrigger | Completed processing with outcome: created confirmation for summarizing mining round 7
00:33:42.988 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT2H
```
So the medium count was 0 after 00:33:42.988, and #5 to #24 are exactly the 20 failures that tripped it, all
LOCAL_VERDICT_TIMEOUT from the two medium triggers, spread over the jumps at 00:33:44.713, 00:33:46.804 and
00:33:48.761, with no medium success in between. Inference as in 11147: the unnamed `Resetting circuit breaker`
lines at 00:33:46.599 and 00:33:49.045 belong to other sv1 breakers (had either been the medium one, the count at
the trip would be at most 13).

All sv1 ledger-call failures in the window by trigger (the medium triggers dominate; the others use high or low
connections per the 11147 table):
```
zcat $T | python3 -I log/tmp/spans.py 00:33:20.000 00:33:49.472 sv1 | awk '$2=="FAIL"{print $3}' | sort | uniq -c | sort -rn
```
```
     14 CalculateRewardsTrigger
     10 SummarizingMiningRoundTrigger
      5 ExpireStaleConfirmationsTrigger
      2 update
      2 ExecuteConfirmedActionTrigger
      1 UpdateExternalPartyConfigStateTrigger
      1 AdvanceOpenMiningRoundTrigger
```
Recovery, and the WARN is the only trip in the log:
```
zcat $T | grep -a 'SpliceCircuitBreaker' | grep -a 'SV=sv1' | grep -a -E 'half-open|closed state|tripped' | python3 -I log/tmp/fmt.py
zcat $T | grep -a -c 'tripped after'
```
```
00:33:49.472 WARN SpliceCircuitBreaker SV=sv1 | Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
00:34:19.484 INFO SpliceCircuitBreaker SV=sv1 | Circuit breaker medium-priority-connection moving to half-open state
00:34:21.521 INFO SpliceCircuitBreaker SV=sv1 | Circuit breaker medium-priority-connection moving to closed state
1
```

## 4. Duplicate check

Family AF in `known-families.md` (parent 11147, dup 11291): same WARN text, same stack, same suite, same shard,
same medium triggers and the same shape (20 consecutive LOCAL_VERDICT_TIMEOUT over consecutive `advancing time by
PT1H` steps, no medium success in between). Only the SV differs (sv1 vs sv2); the mechanism applies to every SV
app of the test.

Tracker state and search for the WARN text:
```
for n in 11147 11291 11182; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"\(.number) \(.state) \(.state_reason) assignees=\([.assignees[].login]|join(",")) | \(.title)"'; done
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "medium-priority-connection"' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:100])"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "docker-canton-simtime" created:>=2026-11-01' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:100])"'
```
```
11147 open null assignees=stephencompall-DA | ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock steps trip the sv2 medium-priority circuit breaker
11291 closed duplicate assignees= | GHA Run 42623223930 : job docker-canton-simtime (0) in scala_test Failed :fire:
11182 open null assignees=isegall-da | Scan bulk storage: BFT check runs once more after success and logs a false Not all objects are known ERROR
11147 open 2026-11-02 ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock steps trip the sv2 medium-priority circ
9487 closed 2026-08-05 Circuit breaker medium-priority-connection tripped after 20 failures
---
11348 open 2026-11-11 GHA Run 42923829975 : job docker-canton-simtime (0) in scala_test Failed :fire:
11147 open 2026-11-02 ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock steps trip the sv2 medium-priority circ
11182 open 2026-11-04 Scan bulk storage: BFT check runs once more after success and logs a false Not all objects are known
11291 closed 2026-11-09 GHA Run 42623223930 : job docker-canton-simtime (0) in scala_test Failed :fire:
```
Per hit:
- 11147: parent, same failure (sv2).
- 11291: earlier duplicate of 11147, same failure (sv2).
- 11182: same test, different failure (bulk storage `Not all objects are known` ERROR); not present here (below).
- 9487: different test and shard (logical-sync-upgrade (0), 2026-08-05, closed completed); not the same failure.

11182 ruled out:
```
zcat log/11348/logs-docker-canton-simtime-0/canton_network_test.clog.gz | grep -a -c 'Not all objects are known'
```
```
0
```

Rate: hits on post-merge main since 2026-11-04 are 11291 and 11348 (11147 is from 2026-11-02); runs of the
post-merge workflow on main in the same window:
```
gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&created=>=2026-11-04&per_page=100" --jq '[.workflow_runs[]|select(.status=="completed")]|length, (group_by(.conclusion)|map("\(.[0].conclusion)=\(length)")|join(" "))'
```
```
82
cancelled=3 failure=45 success=34
```
2 of 82 completed post-merge main runs in a week.

## 5. The fix: splice#8420 covers this, not merged

```
gh api repos/canton-network/splice/pulls/8420 --jq '.title, .state, .merged, .head.ref, .user.login'
gh api repos/canton-network/splice/pulls/8420/files --jq '.[]|.filename, .patch' | sed -n '1p;14,22p'
```
```
Disable the SV circuit breakers in ScanNewNetworkBackfillingTimeBasedIntegrationTest
open
false
s11/ctf-11147-backfill-sv-circuit-breaker
stephencompall-DA
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanNewNetworkBackfillingTimeBasedIntegrationTest.scala
       .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
+      // The sim-clock steps time out in-flight submissions of the SVs' medium-priority triggers,
+      // which can trip their circuit breakers; that is not what this test is about.
+      .addConfigTransforms((_, config) =>
+        ConfigTransforms.updateAllSvAppConfigs_(c =>
+          c.copy(parameters = c.parameters.copy(circuitBreakers = CircuitBreakersConfig.never))
+        )(config)
+      )
       .addConfigTransforms((_, config) =>
```
`updateAllSvAppConfigs_` applies to every SV app, so sv1 is covered as well as sv2. The PR is open, so the run's
sha 2ef0423 does not contain it.

## Verdict

Duplicate of 11147 (family AF), third hit and the first on sv1. Flake in the test: the sim-clock steps time out
in-flight medium-priority SV submissions and the count reaches the test's max-failures 20 within a few steps. Fix:
splice#8420 (open, stephencompall-DA), which disables the circuit breakers of all SV apps in this test; no further
change needed. Not verified: why sv1 and not sv2 this time (any SV can accumulate the count; not investigated).
