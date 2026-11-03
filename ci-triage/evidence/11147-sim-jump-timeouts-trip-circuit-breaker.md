# 11147 - ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock jump timeouts trip the sv2 medium-priority circuit breaker (run 41510362200)

new (family AF, provisional letter). main 136e2e3160, job `docker-canton-simtime (0)`, Canton 3.7.1. All 5 tests
passed; checkErrors flags exactly one WARN in `log/canton_network_test.clog`: `Circuit breaker
medium-priority-connection tripped after 20 failures` on SV=sv2 at 19:54:38.531, during "copy the founder's first
segments to an SV that joins inside the first segment", which advances the sim clock in 1 h steps (42 steps). Every
step drops the SV automation commands that are in flight (LOCAL_VERDICT_TIMEOUT, accepted by design at a sim-time
jump; same mechanism as 10475). Those timeouts count as breaker failures. The breaker only forgets failures on the next
call made 15 min (sim time) after the last failure, i.e. after the next jump, so one jump that drops 20 or more
medium-priority submissions trips it. Here sv2 logged 47 failed ledger submissions between the jump at 19:54:35.970 and
the trip. The breaker went half-open at 19:55:08.544 and closed at 19:55:09.390; the test passed.

- Run: https://github.com/canton-network/splice/actions/runs/41510362200, main 136e2e3160 ("Fix script to start
  backends for local frontend testing. (#8193)"), job 125500702596
  `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)`, attempt 1.
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at 136e2e3160).
- Component: test (sim-time jumps in ScanNewNetworkBackfillingTimeBasedIntegrationTest vs SpliceCircuitBreaker).

## 1. Flagged line

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11147/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in check-sbt-output' | cut -c1-700 | awk '!s[$0]++'
```
```
Found problems in log/canton_network_test.clog:
{"@timestamp":"2026-11-02T19:54:38.531Z","message":"Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:ScanNewNetworkBackfillingTimeBasedIntegrationTest/config=4b0ea7db/SV=sv2","thread_name":"ScanNewNetworkBackfillingTimeBasedIntegrationTest-4b0ea7db-env-ec-3293","level":"WARN","stack_trace":"io.grpc.StatusRuntimeException: ABORTED: LOCAL_VERDICT_TIMEOUT(2,0): Rejected transaction due to a participant determined timeout \n\tat io.grpc.Status.asRuntimeException(Status.java:532)\n\tat io.grpc.stub.ClientCalls$UnaryStreamToFuture.onClose(ClientCalls.java:638)\n\tat org.lfdecentralizedtrust.splice.admin.api.cl
Total: 1 lines with problems.
```
Canton pin:
```
git show 136e2e3160:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.1",
```

## 2. Which test was running

```
T=log/11147/logs-docker-canton-simtime-0/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Finished '" | grep -a 'T19:5[2-6]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat $T | grep -a -c 'advancing time by PT1H'
```
```
2026-11-02T19:52:42.159Z Test succeeded: 'ScanTimeBasedIntegrationTest/snapshotting'",
2026-11-02T19:52:53.792Z Starting test suite 'ScanNewNetworkBackfillingTimeBasedIntegrationTest'...",
2026-11-02T19:53:01.906Z Starting 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'...",
2026-11-02T19:56:02.166Z Test succeeded: 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'",
42
```
The test loop (`ScanNewNetworkBackfillingTimeBasedIntegrationTest.scala:140` at 136e2e3160):
```
git show 136e2e3160:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanNewNetworkBackfillingTimeBasedIntegrationTest.scala | grep -n -E 'advanceTime|advanceHourlyTo' | head -3
```
```
140:        (1L to hours).foreach(_ => advanceTime(Duration.ofHours(1)))
144:        advanceHourlyTo(firstOwnSegmentStart.plus(Duration.ofHours(4)))
182:        advanceHourlyTo(firstOwnSegmentEnd.plus(Duration.ofHours(1)))
```

## 3. The jump, the timeouts, the trip

Formatter used below: `log/tmp/fmt.py` (prints time, level, short logger, message head; JSON per line on stdin):
```
import sys, json, re
for l in sys.stdin:
    try:
        j = json.loads(l)
    except Exception:
        continue
    m = j['message']; lg = j['logger_name']
    who = re.sub(r'.*?([A-Za-z0-9]+)(?::.*?/(SV=\w+|validator=\w+|scan=\w+))?$', r'\1 \2', lg.split('/config=')[0].split('.')[-1] + ('/' + lg.split('/')[-1] if '/' in lg else ''))
    m = re.sub(r'Request \(tid:[0-9a-f]+\) com.daml.ledger.api.v2.CommandService/', '', m)
    m = re.sub(r' to 127.0.0.1:\d+: failed with', ' ->', m)
    m = m.split(': Rejected')[0].split(': Transaction was')[0].split('\n')[0]
    print(j['@timestamp'][11:23], j.get('level', '')[:4], who, '|', m[:140])
```
```
zcat $T | awk '/T19:54:(3[4-9])/' | grep -a -E 'advancing time by|(SubmitAndWait.*failed with|Resetting circuit|tripped|moving to).*SV=sv2' | python3 -I log/tmp/fmt.py
```
```
19:54:34.391 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.430 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.434 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.436 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.437 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.438 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.439 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.440 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:34.646 INFO SpliceCircuitBreaker SV=sv2 | Resetting circuit breaker as last failure was PT5H ago which is more than 15 minutes
19:54:35.970 INFO ScanNewNetworkBackfillingTimeBasedIntegrationTest  | advancing time by PT1H to 1970-01-02T22:10:32.998Z
19:54:38.461 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWaitForTransaction -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.479 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.506 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.516 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.522 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.523 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWaitForTransaction -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.523 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.525 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.529 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.529 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWait -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.529 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWaitForTransaction -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.530 INFO ApiClientRequestLogger SV=sv2 | SubmitAndWaitForTransaction -> ABORTED/LOCAL_VERDICT_TIMEOUT(2,0)
19:54:38.531 WARN SpliceCircuitBreaker SV=sv2 | Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
19:54:38.811 INFO SpliceCircuitBreaker SV=sv2 | Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
19:54:39.885 INFO ScanNewNetworkBackfillingTimeBasedIntegrationTest  | advancing time by PT1H to 1970-01-02T23:10:32.998Z
```
All sv2 ledger-call failures from the jump to the trip (all priorities; sv2 has one breaker per priority, the request
log does not say which breaker a call went through):
```
zcat $T | awk '/T19:54:3[5-8]/' | grep -a 'failed with' | grep -a 'SV=sv2' | python3 -I log/tmp/fmt.py | awk '$1>="19:54:35.970" && $1<="19:54:38.531"' | wc -l
zcat $T | grep -a -c 'tripped after'
```
```
47
1
```
Recovery (the breaker re-closes 31 s later):
```
zcat $T | grep -a 'SpliceCircuitBreaker' | grep -a 'SV=sv2' | grep -a -E 'half-open|closed state' | python3 -I log/tmp/fmt.py
```
```
19:55:08.544 INFO SpliceCircuitBreaker SV=sv2 | Circuit breaker medium-priority-connection moving to half-open state
19:55:09.390 INFO SpliceCircuitBreaker SV=sv2 | Circuit breaker medium-priority-connection moving to closed state
```

## 4. Why timeouts count and when the count resets

`SpliceCircuitBreaker` at 136e2e3160: every failure not in the ignore list calls `underlying.fail()` (pekko counts
consecutive failures); the count is only reset by a success or, on the next call while closed, when the last failure
is at least `resetFailuresAfter` (15 min) old by `clock.now`, which is the sim clock here. LOCAL_VERDICT_TIMEOUT has
category 2 (ContentionOnSharedResources), not in `errorCategoriesToIgnore`.
```
git show 136e2e3160:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/util/SpliceCircuitBreaker.scala | sed -n '47,57p;92,119p'
```
```
  private val errorCategoriesToIgnore: Set[ErrorCategory] = Set(
    InvalidIndependentOfSystemState,
    InvalidGivenCurrentSystemStateOther,
    InvalidGivenCurrentSystemStateResourceExists,
    InvalidGivenCurrentSystemStateResourceMissing,
    InvalidGivenCurrentSystemStateSeekAfterEnd,
  )

  private val errorCodesToIgnore: Set[LocalRejectErrorCode] = Set(
    LockedContracts
  )
  private def callAndMark[T](body: => Future[T])(implicit tc: TraceContext) = {
    // Only run this when the circuit breaker is closed, otherwise rely on the pekko circuit breaker to handle reopening.
    if (underlying.isClosed) {
      lastFailure.updateAndGet(_.filter { lastFailureTime =>
        val elapsed = clock.now - lastFailureTime
        if (elapsed.compareTo(config.resetFailuresAfter.asJava) >= 0) {
          logger.info(
            s"Resetting circuit breaker as last failure was $elapsed ago which is more than ${config.resetFailuresAfter}"
          )
          underlying.succeed()
          false
        } else {
          true
        }
      })
    }

    body.andThen {
      case Failure(exception) =>
        if (!isFailureIgnored(exception)) {
          underlying.fail()
          lastFailure.set(Some(clock.now))
          lastException.set(Some(exception))
        }
      case Success(_) =>
        underlying.succeed()
        lastException.set(None)
    }
  }
```
The trigger logs confirm the category:
```
zcat $T | grep -a 'T19:54:1[0-3]' | grep -a 'SV=sv2' | grep -a -m1 'category=Some' | python3 -I -c "import sys,json; print(json.loads(sys.stdin.readline())['message'][:200])"
```
```
The operation 'processTaskWithRetry' failed with a retryable error (full stack trace omitted):
category=Some(ContentionOnSharedResources)
ErrorInfoDetail(LOCAL_VERDICT_TIMEOUT,Map(definite_answer -> f
```

Test config raises the threshold to 20 (default medium is 10):
```
git grep -n -i 'max-failures' 136e2e3160 -- apps/app/src/test/resources/include/svs/_sv.conf
```
```
136e2e3160:apps/app/src/test/resources/include/svs/_sv.conf:55:        max-failures = 20
136e2e3160:apps/app/src/test/resources/include/svs/_sv.conf:58:        max-failures = 20
```

## 5. Related: 10475 (same suite, same jumps, other symptom)

10475 (closed, completed 2026-10-22) is the same jump mechanism in the same test case: the in-flight command drop at a
jump is accepted by design, and its remaining symptom in the canton-simtime log (`Time validation has failed`) got a
simtime ignore pattern.
```
grep -rn 'Time validation has failed' project/ignore-patterns/ | cut -c1-200
grep -rn -i 'circuit breaker' project/ignore-patterns/ | cut -c1-200
```
```
project/ignore-patterns/canton_log_simtime_extra.ignore.txt:51:Time validation has failed: The delta of the ledger time .* and the record time .* exceeds the max of
project/ignore-patterns/canton_network_test_log.ignore.txt:127:Circuit breaker .* tripped after .* failures.*(Command|Splice)CircuitBreakerTest
```
No earlier hit of this WARN in the issues export (2026-10-20 to 2026-11-03, title, body, comments).

## Verdict

New, family AF (provisional): sim-clock jumps in a test with busy SV automation drop more in-flight commands than the
test-config breaker threshold (20) between two failure-count resets. Flake, test-side, no functional effect (the
breaker re-closed after 31 s and the test passed).

Fix (test-side, not written): in `ScanNewNetworkBackfillingTimeBasedIntegrationTest.environmentDefinition`, add a
config transform that sets `parameters.circuitBreakers = CircuitBreakersConfig.never` for the SV apps (and their
validators if they show the same), as `LsuIntegrationTest.scala:114-116` does for sv4. An ignore pattern is not
proposed: on wall-clock shards a breaker trip is real signal, and the pattern would have to be scoped to this suite
anyway. Not verified: which of the 47 failures went through the medium-priority breaker (the request log does not
name it); whether other simtime suites with hourly jumps can trip it the same way.
