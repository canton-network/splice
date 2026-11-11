# 11357 - ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock steps trip the sv2 medium-priority circuit breaker (run 43012738610)

DUPLICATE of 11147 (family AF), fourth hit after 11291 and 11348. main 2d54198 ("SvFrontendIntegrationTest: tag the
Featured App Right test (#8427)"), job docker-canton-simtime (0), attempt 1 (attempt 2 passed), Canton
3.7.3-snapshot.20261109.20894.0.ve6e6c252. All 5 tests passed; checkErrors flagged exactly one line: the WARN
`Circuit breaker medium-priority-connection tripped after 20 failures` from sv2 at 11:56:40.155, stack
`ABORTED: LOCAL_VERDICT_TIMEOUT(2,0)`, during the hourly sim-clock loop of the suite. The confirming check holds: after
the last medium success at 11:56:30.488, sv2 logged 21 medium-trigger LOCAL_VERDICT_TIMEOUT failures over the 5 jumps
from 11:56:31.362 to 11:56:39.606, no medium success in between. Fix PR splice#8420 (open) covers it.

- Run: https://github.com/canton-network/splice/actions/runs/43012738610, main 2d54198 ("SvFrontendIntegrationTest:
  tag the Featured App Right test (#8427)"), job 130496469862
  `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (sim-clock steps vs SV circuit breaker config).

## 0. Issue and job resolution

```
gh api repos/DACH-NY/cn-test-failures/issues/11357 --jq '"\(.state) \(.title)\n\(.body)"'
```
```
open GHA Run 43012738610 : job docker-canton-simtime (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43012738610).
Job: docker-canton-simtime (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [2d54198](https://github.com/canton-network/splice/commit/2d54198a475f5ad3f818a9fcda403422fdea4fdc) SvFrontendIntegrationTest: tag the Featured App Right test (#8427)
Actor: rautenrieth-da
```
Jobs of that name in all attempts (pages of 40):
```
RUN=43012738610; export NAME='docker-canton-simtime (0)'; for p in 1 2 3 4; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select(.name == env.NAME or (.name | endswith(" / " + env.NAME))) | "\(.id) attempt=\(.run_attempt) \(.conclusion) \(.name) | started=\(.started_at) done=\(.completed_at) runner=\(.runner_name) | failedsteps=\([.steps[]|select(.conclusion=="failure" or .conclusion=="cancelled")|.name]|join(","))"'
git show 2d54198a475f5ad3f818a9fcda403422fdea4fdc:nix/canton-sources.json | grep -m1 version
```
```
130496469862 attempt=1 failure ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0) | started=2026-11-11T11:33:09Z done=2026-11-11T11:58:07Z runner=self-hosted-docker-large-runner-gvshd | failedsteps=Run Tests
130710910442 attempt=2 success ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0) | started=2026-11-11T18:19:39Z done=2026-11-11T18:47:33Z runner=self-hosted-docker-large-runner-rjjjh | failedsteps=
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

## 1. Flagged lines

Job log (ANSI stripped) at `log/inbox-1111b/130496469862.log`.
```
L=log/inbox-1111b/130496469862.log
grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' $L | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/^[^Z]*Z //' $L | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-700
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' $L | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
##[error]Process completed with exit code 1.
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 9 minutes, 40 seconds.
[info] Tests: succeeded 5, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
{"@timestamp":"2026-11-11T11:56:40.155Z","message":"Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:ScanNewNetworkBackfillingTimeBasedIntegrationTest/config=d96884d2/SV=sv2","thread_name":"ScanNewNetworkBackfillingTimeBasedIntegrationTest-d96884d2-env-ec-2657","level":"WARN","stack_trace":"io.grpc.StatusRuntimeException: ABORTED: LOCAL_VERDICT_TIMEOUT(2,0): Rejected transaction due to a participant determined timeout \n\tat io.grpc.Status.asRuntimeException(Status.java:532)\n\tat io.grpc.stub.ClientCalls$UnaryStreamToFuture.onClose(ClientCalls.java:638)\n\tat org.lfdecentralizedtrust.splice.admin.api.cl
Total: 1 lines with problems.
ScanNewNetworkBackfillingTimeBasedIntegrationTest
ScanTimeBasedIntegrationTest
```

## 2. Which test was running

Artifact `logs-docker-canton-simtime-0` (id 13852348872, created 2026-11-11T11:57:50Z, so attempt 1; attempt 2 ended
18:47) in `log/11357/logs-docker-canton-simtime-0/`:
```
gh api "repos/canton-network/splice/actions/runs/43012738610/artifacts?per_page=100" --jq '.artifacts[] | select(.name|test("simtime-0")) | "\(.id) \(.size_in_bytes) \(.name) created=\(.created_at) expired=\(.expired)"'
gh run download 43012738610 --repo canton-network/splice -n logs-docker-canton-simtime-0 -D log/11357/logs-docker-canton-simtime-0
```
```
13852348872 81226204 logs-docker-canton-simtime-0 created=2026-11-11T11:57:50Z expired=false
```
```
T=log/11357/logs-docker-canton-simtime-0/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | tail -3
zcat $T | grep -a 'ScanNewNetworkBackfilling' | grep -a -c 'advancing time by PT1H'
```
```
2026-11-11T11:54:04.597Z Starting test suite 'ScanNewNetworkBackfillingTimeBasedIntegrationTest'...",
2026-11-11T11:54:09.396Z Starting 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'...",
2026-11-11T11:57:08.190Z Test succeeded: 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'",
42
```
The WARN at 11:56:40.155 is inside the only test of the suite, during its hourly `advanceTime` loop.

## 3. Family AF confirming check

Helpers `log/tmp/spans.py` and `log/tmp/fmt.py` as in the 11147 and 11348 packets (`spans.py` takes the SV as third
argument). Medium-trigger failures of sv2, numbered, with sim-clock jumps and sv2 breaker lines:
```
zcat $T | python3 -I log/tmp/spans.py 11:56:00.000 11:56:40.155 sv2 | grep -E 'JUMP|CB|FAIL (SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger) ' | awk '/FAIL/{n++; print $0"   #"n; next} {print}'
```
```
11:56:00.357 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
11:56:00.853 JUMP 1970-01-03T04:10:32.998Z
11:56:01.441 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #1
11:56:01.444 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #2
...
11:56:29.574 JUMP 1970-01-03T16:10:32.998Z
11:56:29.587 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
11:56:31.137 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
11:56:31.362 JUMP 1970-01-03T17:10:32.998Z
11:56:31.923 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #16
11:56:31.932 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #17
11:56:31.932 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #18
11:56:31.943 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #19
11:56:33.255 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
11:56:33.397 JUMP 1970-01-03T18:10:32.998Z
11:56:34.017 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #20
11:56:34.019 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #21
11:56:34.022 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #22
11:56:34.024 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #23
11:56:34.024 FAIL ExpireIssuingMiningRoundTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT   #24
11:56:35.120 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
11:56:35.500 JUMP 1970-01-03T19:10:32.998Z
11:56:36.108 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #25
11:56:36.112 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #26
11:56:36.113 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #27
11:56:36.114 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #28
11:56:37.114 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
11:56:37.569 JUMP 1970-01-03T20:10:32.998Z
11:56:38.140 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #29
11:56:38.144 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #30
11:56:38.146 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #31
11:56:38.148 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #32
11:56:39.606 JUMP 1970-01-03T21:10:32.998Z
11:56:40.151 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #33
11:56:40.153 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #34
11:56:40.154 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #35
11:56:40.154 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #36
11:56:40.155 CB   Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
```
(Rows #3 to #15, 11:56:06 to 11:56:28, elided with `...`; full output is the command above.)

Last medium successes before the trip (a success zeroes the consecutive-failure count, 11147 packet section 4):
```
zcat $T | awk '/T11:56:/' | grep -a 'SV=sv2' | grep -a 'Completed processing with outcome' | grep -a -E '"span-name":"(SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger)"' | python3 -I -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'][11:23], j['span-name'], '|', j['message'][:110])" | awk '$1<="11:56:40.155"' | tail -4
```
```
11:56:21.696 SummarizingMiningRoundTrigger | Completed processing with outcome: created confirmation for summarizing mining round 9
11:56:25.691 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 10, processingDelay=PT2H
11:56:29.882 SummarizingMiningRoundTrigger | Completed processing with outcome: created confirmation for summarizing mining round 10
11:56:30.488 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 11, processingDelay=PT1H
```
So after 11:56:30.488 sv2 logged 21 medium-trigger LOCAL_VERDICT_TIMEOUT failures (#16 to #36) over the 5 jumps
11:56:31.362 to 11:56:39.606, with no medium success in between, and the breaker tripped at the 20th counted. The
last four failures (#33 to #36) are within 4 ms of the trip, so which of them was the 20th counted is not decidable
from the log order. As in 11147 and 11348, the unnamed `Resetting circuit breaker` lines in between (11:56:33.255,
11:56:35.120, 11:56:37.114) belong to other sv2 breakers (inference: had any been the medium one, the count at the
trip would be at most 17).

Recovery, and the WARN is the only trip in the log:
```
zcat $T | grep -a 'SpliceCircuitBreaker' | grep -a 'SV=sv2' | grep -a -E 'half-open|closed state|tripped' | python3 -I log/tmp/fmt.py
zcat $T | grep -a -c 'tripped after'
```
```
11:56:40.155 WARN SpliceCircuitBreaker SV=sv2 | Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
1
```
(No half-open or closed line for sv2 after the trip: the test ended at 11:57:08, before the breaker's reset timeout
elapsed in wall time; not relevant to the verdict.)

## 4. Duplicate check

```
for n in 11147 11291 11348; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"\(.number) \(.state) \(.state_reason) assignees=\([.assignees[].login]|join(",")) | \(.title)"'; done
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "medium-priority-connection"' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:100])"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "docker-canton-simtime" created:>=2026-11-09' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:100])"'
gh api repos/canton-network/splice/pulls/8420 --jq '"\(.title) | \(.state) merged=\(.merged) \(.user.login) updated=\(.updated_at)"'
zcat log/11357/logs-docker-canton-simtime-0/canton_network_test.clog.gz | grep -a -c 'Not all objects are known'
```
```
11147 open null assignees=stephencompall-DA | ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock steps trip the sv2 medium-priority circuit breaker
11291 closed duplicate assignees= | GHA Run 42623223930 : job docker-canton-simtime (0) in scala_test Failed :fire:
11348 closed duplicate assignees= | GHA Run 42923829975 : job docker-canton-simtime (0) in scala_test Failed :fire:
11147 open 2026-11-02 ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock steps trip the sv2 medium-priority circ
9487 closed 2026-08-05 Circuit breaker medium-priority-connection tripped after 20 failures
11357 open 2026-11-11 GHA Run 43012738610 : job docker-canton-simtime (0) in scala_test Failed :fire:
11348 closed 2026-11-11 GHA Run 42923829975 : job docker-canton-simtime (0) in scala_test Failed :fire:
11291 closed 2026-11-09 GHA Run 42623223930 : job docker-canton-simtime (0) in scala_test Failed :fire:
Disable the SV circuit breakers in ScanNewNetworkBackfillingTimeBasedIntegrationTest | open merged=false stephencompall-DA updated=2026-11-11T18:00:42Z
0
```
Per hit: 11147 parent (same WARN, suite, shard, sv2); 11291 and 11348 earlier duplicates (11348 on sv1); 9487
different test and shard (2026-08, closed); 11357 this issue. 11182 (same test, `Not all objects are known`) ruled
out by the 0 count. PR splice#8420 is open, so 2d54198 does not contain it.

## Verdict

Duplicate of 11147 (family AF), fourth hit (11147, 11291, 11348, 11357). Test flake: sim-clock steps time out the
in-flight medium-priority SV submissions and the consecutive-failure count reaches the test's max-failures 20 within
5 steps. Fix: splice#8420 (open, stephencompall-DA) disables the circuit breakers of all SV apps in this test; no
further action. Not verified: nothing beyond the family check.
