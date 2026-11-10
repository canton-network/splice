# 11147, 11291 - ScanNewNetworkBackfillingTimeBasedIntegrationTest: sim-clock jump timeouts trip the sv2 medium-priority circuit breaker (runs 41510362200, 42623223930)

new (family AF, provisional letter). main 136e2e3160, job `docker-canton-simtime (0)`, Canton 3.7.1. All 5 tests
passed; checkErrors flags exactly one WARN in `log/canton_network_test.clog`: `Circuit breaker
medium-priority-connection tripped after 20 failures` on SV=sv2 at 19:54:38.531, during "copy the founder's first
segments to an SV that joins inside the first segment", which advances the sim clock in 1 h steps (42 steps). Every
step drops the SV automation commands that are in flight (LOCAL_VERDICT_TIMEOUT, accepted by design at a sim-time
jump; same mechanism as 10475). Those timeouts count as breaker failures. The breaker only forgets failures on a
call that starts 15 min (sim time) or more after the last failure. Corrected 2026-11-09 (section 6): the 20 counted
failures are not from one jump. sv2's medium-priority triggers (SummarizingMiningRoundTrigger, CalculateRewardsTrigger)
resubmit within about a second after each timeout, before the next jump, so no call ever starts 15 min (sim) after a
failure and the count grows across consecutive jumps: 20 consecutive medium failures over 3 jumps (19:54:32.486 to
19:54:38.529), only 8 of them after the jump at 19:54:35.970. The "47" below counts all sv2 `failed with` lines of all
priorities and is not the breaker count. The breaker went half-open at 19:55:08.544 and closed at 19:55:09.390; the
test passed.

Second hit 11291 (2026-11-09, main ab36803c8a, Canton 3.7.2, same job, same suite and test case, sv2 again):
sections 7 to 10. Same cycle over 4 jumps, trip at 13:49:00.423.

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

## 6. Correction (2026-11-09): which failures the medium breaker counted (11147)

Added during the 11291 triage. The 11147 artifact was re-downloaded to `log/11291/run11147-logs-docker-canton-simtime-0/`
(`gh run download 41510362200 --repo canton-network/splice -n logs-docker-canton-simtime-0 -D <dir>`), and the
sv2 ledger-call failures were attributed by `span-name` (the trigger) instead of counting all `failed with` lines.
Helper `log/tmp/spans.py` (prints sim-clock jumps, sv2 `failed with` lines of ApiClientRequestLogger with span-name, rpc
and error, and sv2 SpliceCircuitBreaker lines between two wall-clock times):
```
import sys, json, re
# Usage: zcat clog.gz | python3 -I spans.py FROM TO
# Prints sv2 ledger-call failures, sv2 breaker lines and sim-clock jumps between FROM and TO (HH:MM:SS.mmm),
# one per line: time, kind, span-name, short message.
lo, hi = sys.argv[1], sys.argv[2]
for l in sys.stdin:
    if '"@timestamp"' not in l:
        continue
    try:
        j = json.loads(l)
    except Exception:
        continue
    t = j['@timestamp'][11:23]
    if t < lo or t > hi:
        continue
    m = j['message']; lg = j['logger_name']
    if 'advancing time by' in m:
        print(t, 'JUMP', m.split(' to ')[-1][:24])
    elif lg.endswith('/SV=sv2') and 'ApiClientRequestLogger' in lg and 'failed with' in m:
        rpc = re.search(r'CommandService/(\w+)', m)
        err = re.search(r'failed with (\S+?)[:(]', m)
        print(t, 'FAIL', j.get('span-name', '-'), rpc.group(1) if rpc else '-', err.group(1) if err else '-')
    elif lg.endswith('/SV=sv2') and 'SpliceCircuitBreaker' in lg:
        print(t, 'CB  ', m[:110])
```
Which connection each failing trigger uses (at the 11291 sha; the trigger either picks the priority itself or gets it at
registration in `SvDsoAutomationService`; the delegate-based triggers use the same SvDsoAutomationService connections
through `svTaskContext`):
```
for t in SummarizingMiningRoundTrigger CalculateRewardsTrigger AdvanceOpenMiningRoundTrigger ExpireStaleConfirmationsTrigger ArchiveClosedMiningRoundsTrigger UpdateExternalPartyConfigStateTrigger ExecuteConfirmedActionTrigger ProcessRewardsTrigger ExpireIssuingMiningRoundTrigger ExpireRewardCouponsTrigger; do f=$(git ls-tree -r --name-only ab36803c8a apps/sv/src/main | grep "/$t.scala$"); p=$(git show ab36803c8a:$f | grep -o -E 'SpliceLedgerConnectionPriority\.\w+' | sort -u | tr '\n' ' '); r=$(git show ab36803c8a:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/SvDsoAutomationService.scala | grep -n -A4 "new $t(" | grep -o -E 'Priority\.\w+' | tr '\n' ' '); echo "$t | in trigger: $p| at registration: $r"; done
```
```
SummarizingMiningRoundTrigger | in trigger: | at registration: Priority.Medium 
CalculateRewardsTrigger | in trigger: | at registration: Priority.Medium 
AdvanceOpenMiningRoundTrigger | in trigger: SpliceLedgerConnectionPriority.High | at registration: 
ExpireStaleConfirmationsTrigger | in trigger: SpliceLedgerConnectionPriority.Low | at registration: 
ArchiveClosedMiningRoundsTrigger | in trigger: | at registration: Priority.Low 
UpdateExternalPartyConfigStateTrigger | in trigger: SpliceLedgerConnectionPriority.High | at registration: 
ExecuteConfirmedActionTrigger | in trigger: SpliceLedgerConnectionPriority.High | at registration: 
ProcessRewardsTrigger | in trigger: SpliceLedgerConnectionPriority.Low | at registration: 
ExpireIssuingMiningRoundTrigger | in trigger: SpliceLedgerConnectionPriority.Medium | at registration: 
ExpireRewardCouponsTrigger | in trigger: SpliceLedgerConnectionPriority.Low | at registration: 
```
The delegate-based triggers get SvDsoAutomationService's `connection` function through the task context:
```
git grep -n -A2 'SvTaskBasedTrigger.Context(' ab36803c8a -- apps/sv/src/main | cut -d: -f2- | head -3
```
```
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/SvDsoAutomationService.scala:272:      SvTaskBasedTrigger.Context(
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/SvDsoAutomationService.scala-273-        dsoStore,
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/SvDsoAutomationService.scala-274-        connection,
```
Each `SpliceAppAutomationService` builds one breaker per priority, named `<priority>-priority-connection`; the
`Resetting circuit breaker` line does not name the breaker:
```
git show ab36803c8a:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/SpliceAppAutomationService.scala | sed -n 64,86p
```
```
  private def createConnectionWithPriority(priority: SpliceLedgerConnectionPriority) = {
    val (name, config) = priority match {
      case SpliceLedgerConnectionPriority.High =>
        "high" -> parametersConfig.circuitBreakers.highPriority
      case SpliceLedgerConnectionPriority.Medium =>
        "medium" -> parametersConfig.circuitBreakers.mediumPriority
      case SpliceLedgerConnectionPriority.Low =>
        "low" -> parametersConfig.circuitBreakers.lowPriority
      case SpliceLedgerConnectionPriority.AmuletExpiry =>
        "amulet-expiry" -> parametersConfig.circuitBreakers.amuletExpiry
    }
    ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory.append("connectionPriority", name),
      SpliceCircuitBreaker(
        s"$name-priority-connection",
        config,
        clock,
        store.dsoPartyId,
        loggerFactory,
      ),
      completionOffsetCallback,
    )
  }
```
Medium-priority failures of sv2 before the 11147 trip, numbered (filter: the medium triggers from the table above plus
the other Medium registrations CalculateRewardsDryRunTrigger, AnsSubscriptionInitialPaymentTrigger,
SubmitSvStatusReportTrigger):
```
T=log/11291/run11147-logs-docker-canton-simtime-0/canton_network_test.clog.gz; zcat $T | python3 -I log/tmp/spans.py 19:54:20.000 19:54:38.531 | grep -E 'FAIL (SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger) |JUMP|CB' | awk '{ if ($2=="FAIL") n++; print $0 (($2=="FAIL") ? "   #" n : "") }'
zcat $T | python3 -I log/tmp/spans.py 19:54:35.970 19:54:38.531 | awk '$2=="FAIL"{print $3}' | sort | uniq -c | sort -rn
```
```
19:54:21.031 JUMP 1970-01-02T14:10:32.998Z
19:54:21.966 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #1
19:54:21.969 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #2
19:54:23.343 JUMP 1970-01-02T15:10:32.998Z
19:54:23.643 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #3
19:54:23.648 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #4
19:54:24.801 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
19:54:25.042 JUMP 1970-01-02T16:10:32.998Z
19:54:26.416 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
19:54:26.840 JUMP 1970-01-02T17:10:32.998Z
19:54:27.379 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #5
19:54:27.430 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #6
19:54:28.689 JUMP 1970-01-02T18:10:32.998Z
19:54:30.388 JUMP 1970-01-02T19:10:32.998Z
19:54:31.418 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
19:54:32.061 JUMP 1970-01-02T20:10:32.998Z
19:54:32.486 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #7
19:54:32.487 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #8
19:54:32.488 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #9
19:54:32.489 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #10
19:54:33.866 JUMP 1970-01-02T21:10:32.998Z
19:54:34.391 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #11
19:54:34.430 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #12
19:54:34.434 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #13
19:54:34.436 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #14
19:54:34.437 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #15
19:54:34.438 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #16
19:54:34.439 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #17
19:54:34.440 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #18
19:54:34.646 CB   Resetting circuit breaker as last failure was PT5H ago which is more than 15 minutes
19:54:35.970 JUMP 1970-01-02T22:10:32.998Z
19:54:38.479 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #19
19:54:38.506 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #20
19:54:38.516 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #21
19:54:38.522 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #22
19:54:38.523 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #23
19:54:38.525 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #24
19:54:38.529 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #25
19:54:38.529 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #26
19:54:38.531 CB   Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
---
      4 SummarizingMiningRoundTrigger
      4 ExpireStaleConfirmationsTrigger
      4 CalculateRewardsTrigger
```
The last successful medium submissions before the trip (a success calls `underlying.succeed()`, which zeroes the
consecutive-failure count):
```
zcat $T | awk '/T19:54:(2[7-9]|3[0-8])/' | grep -a 'SV=sv2' | grep -a 'Completed processing with outcome' | grep -a -E '"span-name":"(SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger)"' | python3 -I -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'][11:23], j['span-name'], '|', j['message'][:110])"
```
```
19:54:31.964 SummarizingMiningRoundTrigger | Completed processing with outcome: created confirmation for summarizing mining round 1
19:54:32.334 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 1, processingDelay=PT10H
```
So the medium count was 0 after 19:54:32.334 and #7 to #26 are exactly the 20 that tripped it, spread over the jumps at
19:54:32.061, 19:54:33.866 and 19:54:35.970 (8 after the last one). This also shows that the reset at 19:54:34.646 was
another sv2 breaker, not the medium one (inference: if it had been the medium one, the count at the trip would be 8).

## 7. 11291: run, job, flagged line

- Run: https://github.com/canton-network/splice/actions/runs/42623223930, main ab36803c8a ("Tag the concrete
  TrafficBasedRewards test classes (#8362)"), job 129180735524
  `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)`, attempt 1, the only failed job.
- Runtime canton: 3.7.2.

Issue body (verbatim):
```
gh api repos/DACH-NY/cn-test-failures/issues/11291 --jq '.title, .state, .created_at, .body'
```
```
GHA Run 42623223930 : job docker-canton-simtime (0) in scala_test Failed :fire:
open
2026-11-09T13:50:29Z

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42623223930).
Job: docker-canton-simtime (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [ab36803](https://github.com/canton-network/splice/commit/ab36803c8a442194eb1929f27c50d9f1c58bc54c) Tag the concrete TrafficBasedRewards test classes (#8362)
Actor: rautenrieth-da
```
```
gh api repos/canton-network/splice/actions/jobs/129180735524 --jq '.name, .run_attempt, .conclusion, .started_at, .completed_at'
git show ab36803c8a:nix/canton-sources.json | grep -m1 version
```
```
ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)
1
failure
2026-11-09T13:22:20Z
2026-11-09T13:50:37Z
  "version": "3.7.2",
```
Classification and the flagged line (job log at `log/11291/job.log`):
```
REF=11291; sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$REF/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in check-sbt-output' | cut -c1-700 | awk '!s[$0]++'
```
```
##[error]Process completed with exit code 1.
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 11 minutes, 4 seconds.
[info] Tests: succeeded 5, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
{"@timestamp":"2026-11-09T13:49:00.423Z","message":"Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:ScanNewNetworkBackfillingTimeBasedIntegrationTest/config=6a0a9104/SV=sv2","thread_name":"ScanNewNetworkBackfillingTimeBasedIntegrationTest-6a0a9104-env-ec-2802","level":"WARN","stack_trace":"io.grpc.StatusRuntimeException: ABORTED: LOCAL_VERDICT_TIMEOUT(2,0): Rejected transaction due to a participant determined timeout \n\tat io.grpc.Status.asRuntimeException(Status.java:532)\n\tat io.grpc.stub.ClientCalls$UnaryStreamToFuture.onClose(ClientCalls.java:638)\n\tat org.lfdecentralizedtrust.splice.admin.api.cl
Total: 1 lines with problems.
```
Artifact (attempt 1, created 2026-11-09T13:50:20Z), downloaded to `log/11291/logs-docker-canton-simtime-0/`:
```
gh api "repos/canton-network/splice/actions/runs/42623223930/artifacts?per_page=100" --jq '.artifacts[] | "\(.id) \(.size_in_bytes) \(.name) \(.created_at) expired=\(.expired)"' | grep -i simtime
```
```
13680102030 80969598 logs-docker-canton-simtime-0 2026-11-09T13:50:20Z expired=false
```

## 8. 11291: which test, which step

Same shard suites as 11147, same test case; the trip falls in the hourly loop (jump 39 of 42; in 11147 jump 15 of 42):
```
cat log/11291/logs-docker-canton-simtime-0/test-full-class-names-sim-time-docker.log
T=log/11291/logs-docker-canton-simtime-0/canton_network_test.clog.gz; zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
for T in log/11291/logs-docker-canton-simtime-0/canton_network_test.clog.gz log/11291/run11147-logs-docker-canton-simtime-0/canton_network_test.clog.gz; do echo "$T"; zcat $T | grep -a -E 'advancing time by PT1H|tripped after' | python3 -I log/tmp/fmt.py | awk '/advancing/{n++; last=$0} /tripped/{print "trip after jump #" n " of the suite: " last; print $0} END{print "jumps:", n}'; done
```
```
org.lfdecentralizedtrust.splice.integration.tests.ScanNewNetworkBackfillingTimeBasedIntegrationTest
org.lfdecentralizedtrust.splice.integration.tests.ScanTimeBasedIntegrationTest
2026-11-09T13:38:24.302Z Starting test suite 'ScanTimeBasedIntegrationTest'...",
2026-11-09T13:38:34.779Z Starting 'ScanTimeBasedIntegrationTest/report correct reference data'...",
2026-11-09T13:42:08.420Z Test succeeded: 'ScanTimeBasedIntegrationTest/report correct reference data'",
2026-11-09T13:42:08.459Z Starting 'ScanTimeBasedIntegrationTest/return correct amulet configs'...",
2026-11-09T13:43:22.855Z Test succeeded: 'ScanTimeBasedIntegrationTest/return correct amulet configs'",
2026-11-09T13:43:22.889Z Starting 'ScanTimeBasedIntegrationTest/return AnsRules contract and config'...",
2026-11-09T13:44:12.345Z Test succeeded: 'ScanTimeBasedIntegrationTest/return AnsRules contract and config'",
2026-11-09T13:44:12.369Z Starting 'ScanTimeBasedIntegrationTest/snapshotting'...",
2026-11-09T13:45:56.817Z Test succeeded: 'ScanTimeBasedIntegrationTest/snapshotting'",
2026-11-09T13:45:57.270Z Starting test suite 'ScanNewNetworkBackfillingTimeBasedIntegrationTest'...",
2026-11-09T13:46:02.235Z Starting 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'...",
2026-11-09T13:49:26.550Z Test succeeded: 'ScanNewNetworkBackfillingTimeBasedIntegrationTest/copy the founder's first segments to an SV that joins inside the first segment'",
log/11291/logs-docker-canton-simtime-0/canton_network_test.clog.gz
trip after jump #39 of the suite: 13:48:59.804 INFO ScanNewNetworkBackfillingTimeBasedIntegrationTest  | advancing time by PT1H to 1970-01-03T22:10:32.998Z
13:49:00.423 WARN SpliceCircuitBreaker SV=sv2 | Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
jumps: 42
log/11291/run11147-logs-docker-canton-simtime-0/canton_network_test.clog.gz
trip after jump #15 of the suite: 19:54:35.970 INFO ScanNewNetworkBackfillingTimeBasedIntegrationTest  | advancing time by PT1H to 1970-01-02T22:10:32.998Z
19:54:38.531 WARN SpliceCircuitBreaker SV=sv2 | Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
jumps: 42
```

## 9. 11291: the 20 failures, the cycle that prevents the reset

All sv2 failures (all priorities), breaker lines and jumps from the first jump of the test case to the trip. The
attached last failure (13:49:00.422, `span-name` CalculateRewardsTrigger, tid 06c7c425...) is #22 below:
```
T=log/11291/logs-docker-canton-simtime-0/canton_network_test.clog.gz; zcat $T | python3 -I log/tmp/spans.py 13:47:30.000 13:49:00.423
```
```
13:47:30.943 JUMP 1970-01-02T08:10:32.998Z
13:47:32.827 JUMP 1970-01-02T09:10:32.998Z
13:47:33.547 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:47:35.011 JUMP 1970-01-02T10:10:32.998Z
13:47:37.207 JUMP 1970-01-02T11:10:32.998Z
13:47:38.755 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
13:47:39.089 JUMP 1970-01-02T12:10:32.998Z
13:47:39.759 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:47:41.239 JUMP 1970-01-02T13:10:32.998Z
13:47:43.402 JUMP 1970-01-02T14:10:32.998Z
13:47:45.726 JUMP 1970-01-02T15:10:32.998Z
13:47:47.910 JUMP 1970-01-02T16:10:32.998Z
13:47:49.197 CB   Resetting circuit breaker as last failure was PT4H ago which is more than 15 minutes
13:47:50.155 JUMP 1970-01-02T17:10:32.998Z
13:47:50.565 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait NOT_FOUND/LOCAL_VERDICT_INACTIVE_CONTRACTS
13:47:52.401 JUMP 1970-01-02T18:10:32.998Z
13:47:53.270 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:47:53.282 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:47:54.705 JUMP 1970-01-02T19:10:32.998Z
13:47:55.352 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:47:56.828 JUMP 1970-01-02T20:10:32.998Z
13:47:57.461 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:47:57.471 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:47:58.871 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
13:47:58.933 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction NOT_FOUND/LOCAL_VERDICT_INACTIVE_CONTRACTS
13:47:58.937 JUMP 1970-01-02T21:10:32.998Z
13:47:59.712 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:00.370 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:00.722 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:01.190 JUMP 1970-01-02T22:10:32.998Z
13:48:01.896 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:03.361 JUMP 1970-01-02T23:10:32.998Z
13:48:05.515 JUMP 1970-01-03T00:10:32.998Z
13:48:07.568 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
13:48:07.638 JUMP 1970-01-03T01:10:32.998Z
13:48:07.887 CB   Resetting circuit breaker as last failure was PT4H ago which is more than 15 minutes
13:48:08.261 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:09.715 JUMP 1970-01-03T02:10:32.998Z
13:48:10.572 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:12.052 JUMP 1970-01-03T03:10:32.998Z
13:48:12.770 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:12.779 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:12.789 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/NOT_SEQUENCED_TIMEOUT
13:48:14.209 JUMP 1970-01-03T04:10:32.998Z
13:48:15.228 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:15.234 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:15.688 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
13:48:18.268 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:19.305 FAIL ExecuteConfirmedActionTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
13:48:19.590 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
13:48:20.272 FAIL ExecuteConfirmedActionTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
13:48:21.498 FAIL ExecuteConfirmedActionTrigger SubmitAndWaitForTransaction NOT_FOUND/CONTRACT_NOT_FOUND
13:48:21.754 FAIL ProcessRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
13:48:22.196 JUMP 1970-01-03T05:10:32.998Z
13:48:22.799 FAIL ProcessRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:23.650 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:24.253 JUMP 1970-01-03T06:10:32.998Z
13:48:24.925 FAIL ExpireIssuingMiningRoundTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:26.204 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:26.629 JUMP 1970-01-03T07:10:32.998Z
13:48:26.708 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:27.585 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:28.112 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/NOT_SEQUENCED_TIMEOUT
13:48:28.119 FAIL ExpireRewardCouponsTrigger SubmitAndWaitForTransaction ABORTED/NOT_SEQUENCED_TIMEOUT
13:48:28.126 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/NOT_SEQUENCED_TIMEOUT
13:48:28.946 JUMP 1970-01-03T08:10:32.998Z
13:48:29.807 FAIL ArchiveClosedMiningRoundsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:29.841 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:31.233 JUMP 1970-01-03T09:10:32.998Z
13:48:31.942 FAIL ArchiveClosedMiningRoundsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:31.942 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:31.950 FAIL UpdateExternalPartyConfigStateTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:33.385 JUMP 1970-01-03T10:10:32.998Z
13:48:33.390 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:33.494 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:34.111 FAIL ArchiveClosedMiningRoundsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:34.115 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:35.502 JUMP 1970-01-03T11:10:32.998Z
13:48:35.819 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:36.188 FAIL ArchiveClosedMiningRoundsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:36.195 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:36.196 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:36.199 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:36.202 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:37.646 JUMP 1970-01-03T12:10:32.998Z
13:48:38.280 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:38.289 FAIL UpdateExternalPartyConfigStateTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:38.294 FAIL ArchiveClosedMiningRoundsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:39.698 JUMP 1970-01-03T13:10:32.998Z
13:48:40.383 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:41.335 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:41.847 JUMP 1970-01-03T14:10:32.998Z
13:48:42.303 FAIL ArchiveClosedMiningRoundsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:42.314 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:42.331 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:42.341 FAIL ExpireStaleConfirmationsTrigger SubmitAndWaitForTransaction ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:42.354 CB   Resetting circuit breaker as last failure was PT2H ago which is more than 15 minutes
13:48:43.998 JUMP 1970-01-03T15:10:32.998Z
13:48:44.815 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:45.435 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:46.280 JUMP 1970-01-03T16:10:32.998Z
13:48:46.916 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:47.899 CB   Resetting circuit breaker as last failure was PT1H ago which is more than 15 minutes
13:48:48.610 JUMP 1970-01-03T17:10:32.998Z
13:48:49.288 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:49.291 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:50.959 JUMP 1970-01-03T18:10:32.998Z
13:48:51.911 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:51.923 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:51.929 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:51.934 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:53.384 JUMP 1970-01-03T19:10:32.998Z
13:48:53.628 CB   Resetting circuit breaker as last failure was PT3H ago which is more than 15 minutes
13:48:54.131 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:54.147 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:54.151 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:54.182 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:54.183 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:55.540 JUMP 1970-01-03T20:10:32.998Z
13:48:56.254 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:56.258 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:56.261 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:56.264 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:56.264 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:57.634 FAIL update ingestion - -
13:48:57.707 JUMP 1970-01-03T21:10:32.998Z
13:48:58.369 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:58.380 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:58.385 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:58.386 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:58.391 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:58.396 FAIL AdvanceOpenMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:48:59.666 FAIL update ingestion - -
13:48:59.804 JUMP 1970-01-03T22:10:32.998Z
13:49:00.422 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT
13:49:00.423 CB   Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
```
Only the medium-priority triggers, numbered:
```
zcat $T | python3 -I log/tmp/spans.py 13:48:49.000 13:49:00.423 | grep -E 'FAIL (SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger) |JUMP|CB' | awk '{ if ($2=="FAIL") n++; print $0 (($2=="FAIL") ? "   #" n : "") }'
```
```
13:48:49.288 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #1
13:48:49.291 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #2
13:48:50.959 JUMP 1970-01-03T18:10:32.998Z
13:48:51.911 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #3
13:48:51.923 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #4
13:48:51.929 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #5
13:48:51.934 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #6
13:48:53.384 JUMP 1970-01-03T19:10:32.998Z
13:48:53.628 CB   Resetting circuit breaker as last failure was PT3H ago which is more than 15 minutes
13:48:54.131 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #7
13:48:54.147 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #8
13:48:54.151 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #9
13:48:54.182 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #10
13:48:54.183 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #11
13:48:55.540 JUMP 1970-01-03T20:10:32.998Z
13:48:56.254 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #12
13:48:56.258 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #13
13:48:56.261 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #14
13:48:56.264 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #15
13:48:56.264 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #16
13:48:57.707 JUMP 1970-01-03T21:10:32.998Z
13:48:58.369 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #17
13:48:58.380 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #18
13:48:58.385 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #19
13:48:58.386 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #20
13:48:58.391 FAIL SummarizingMiningRoundTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #21
13:48:59.804 JUMP 1970-01-03T22:10:32.998Z
13:49:00.422 FAIL CalculateRewardsTrigger SubmitAndWait ABORTED/LOCAL_VERDICT_TIMEOUT   #22
13:49:00.423 CB   Circuit breaker medium-priority-connection tripped after 20 failures. Attaching last failure
```
Last medium successes before the trip (none after 13:48:51.622):
```
zcat $T | awk '/T13:48:(4[5-9]|5[0-9])|T13:49:00/' | grep -a 'SV=sv2' | grep -a 'Completed processing with outcome' | grep -a -E '"span-name":"(SummarizingMiningRoundTrigger|CalculateRewardsTrigger|ExpireIssuingMiningRoundTrigger|CalculateRewardsDryRunTrigger|AnsSubscriptionInitialPaymentTrigger|SubmitSvStatusReportTrigger)"' | python3 -I -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'][11:23], j['span-name'], '|', j['message'][:110])"
```
```
13:48:46.851 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 3, processingDelay=PT10H
13:48:49.196 SummarizingMiningRoundTrigger | Completed processing with outcome: created confirmation for summarizing mining round 4
13:48:49.253 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 2, processingDelay=PT13H
13:48:49.300 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 5, processingDelay=PT7H
13:48:51.514 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 6, processingDelay=PT6H
13:48:51.597 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 4, processingDelay=PT10H
13:48:51.622 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 7, processingDelay=PT4H
```
So the medium count was 0 after 13:48:51.622, and #3 to #22 are exactly the 20 counted, over the 4 jumps 13:48:50.959
(its burst), 13:48:53.384, 13:48:55.540, 13:48:57.707 and 13:48:59.804 (1 failure after the last one). The reset at
13:48:53.628 is another sv2 breaker (inference, as in section 6).

The cycle, by request: each medium submission is sent 0.1 to 0.7 s before a jump, fails 0.6 to 1.0 s after it, the
trigger then waits for the domain time to catch up and sends the next one before the following jump (SEND = `sending
request`, FAIL = `failed with`, OK = completed, WAIT = DomainTimeStore `Domain time delay is currently ...`):
```
zcat $T | awk '/T13:48:(5[0-9])|T13:49:00/' | grep -a -E '"span-name":"(SummarizingMiningRoundTrigger|CalculateRewardsTrigger)"|advancing time by' | grep -a -E 'CommandService/SubmitAndWait|advancing time by|Domain time delay' | grep -a -E 'SV=sv2|advancing' | python3 -I -c "
import sys,json,re
for l in sys.stdin:
    j=json.loads(l); m=j['message']; t=re.search(r'tid:(\w{8})',m)
    k='JUMP' if 'advancing' in m else ('SEND' if 'sending request' in m else ('FAIL' if 'failed with' in m else ('OK  ' if 'succeeded' in m or 'received response' in m else ('WAIT' if 'Domain time' in m else 'OTHR'))))
    print(j['@timestamp'][11:23], k, j.get('span-name','')[:14], t.group(1) if t else '', m.split(chr(10))[0][-60:] if k in ('OTHR','JUMP') else '')" | grep -v OTHR | awk '!s[$0]++'
```
```
13:48:50.113 SEND CalculateRewar 3c005c26 
13:48:50.113 SEND CalculateRewar 563ef69d 
13:48:50.123 SEND CalculateRewar f7170a1a 
13:48:50.192 SEND SummarizingMin aa924d8a 
13:48:50.193 SEND SummarizingMin 8ddcb286 
13:48:50.194 SEND SummarizingMin 6edf1204 
13:48:50.223 SEND SummarizingMin c2d32fd2 
13:48:50.959 JUMP   advancing time by PT1H to 1970-01-03T18:10:32.998Z
13:48:51.475 OK   CalculateRewar 3c005c26 
13:48:51.542 OK   CalculateRewar 563ef69d 
13:48:51.598 OK   CalculateRewar f7170a1a 
13:48:51.682 WAIT CalculateRewar  
13:48:51.911 FAIL SummarizingMin 8ddcb286 
13:48:51.923 FAIL SummarizingMin aa924d8a 
13:48:51.929 FAIL SummarizingMin 6edf1204 
13:48:51.934 FAIL SummarizingMin c2d32fd2 
13:48:52.122 WAIT SummarizingMin  
13:48:52.132 WAIT SummarizingMin  
13:48:52.138 WAIT SummarizingMin  
13:48:52.142 WAIT SummarizingMin  
13:48:52.782 SEND SummarizingMin 6fe9d72a 
13:48:52.783 SEND SummarizingMin f6abc32f 
13:48:52.784 SEND SummarizingMin d1c04996 
13:48:52.792 SEND SummarizingMin bf06dbac 
13:48:52.794 SEND CalculateRewar 40cd3db8 
13:48:53.384 JUMP   advancing time by PT1H to 1970-01-03T19:10:32.998Z
13:48:54.131 FAIL SummarizingMin 6fe9d72a 
13:48:54.147 FAIL SummarizingMin f6abc32f 
13:48:54.151 FAIL SummarizingMin d1c04996 
13:48:54.182 FAIL SummarizingMin bf06dbac 
13:48:54.183 FAIL CalculateRewar 40cd3db8 
13:48:54.392 WAIT CalculateRewar  
13:48:54.444 WAIT SummarizingMin  
13:48:54.457 WAIT SummarizingMin  
13:48:54.518 WAIT SummarizingMin  
13:48:54.585 WAIT SummarizingMin  
13:48:54.873 SEND CalculateRewar aae5446d 
13:48:54.894 SEND SummarizingMin c2ce6463 
13:48:54.904 SEND SummarizingMin 283b9ee7 
13:48:54.905 SEND SummarizingMin 46910331 
13:48:54.908 SEND SummarizingMin d4338240 
13:48:55.540 JUMP   advancing time by PT1H to 1970-01-03T20:10:32.998Z
13:48:56.254 FAIL CalculateRewar aae5446d 
13:48:56.258 FAIL SummarizingMin c2ce6463 
13:48:56.261 FAIL SummarizingMin 46910331 
13:48:56.264 FAIL SummarizingMin 283b9ee7 
13:48:56.264 FAIL SummarizingMin d4338240 
13:48:56.650 WAIT CalculateRewar  
13:48:56.843 WAIT SummarizingMin  
13:48:56.868 WAIT SummarizingMin  
13:48:57.008 SEND CalculateRewar 7112a09d 
13:48:57.037 SEND SummarizingMin 09537bfa 
13:48:57.046 SEND SummarizingMin 1ee66b8d 
13:48:57.086 SEND SummarizingMin b53095ee 
13:48:57.087 SEND SummarizingMin c2ef3591 
13:48:57.707 JUMP   advancing time by PT1H to 1970-01-03T21:10:32.998Z
13:48:58.369 FAIL CalculateRewar 7112a09d 
13:48:58.380 FAIL SummarizingMin 09537bfa 
13:48:58.385 FAIL SummarizingMin 1ee66b8d 
13:48:58.386 FAIL SummarizingMin c2ef3591 
13:48:58.391 FAIL SummarizingMin b53095ee 
13:48:59.065 WAIT CalculateRewar  
13:48:59.231 SEND CalculateRewar 06c7c425 
13:48:59.504 SEND SummarizingMin a4d27484 
13:48:59.721 SEND SummarizingMin 154dd50f 
13:48:59.723 SEND SummarizingMin 92ca8eaa 
13:48:59.804 JUMP   advancing time by PT1H to 1970-01-03T22:10:32.998Z
13:48:59.910 WAIT SummarizingMin  
13:49:00.422 FAIL CalculateRewar 06c7c425 
13:49:00.429 FAIL SummarizingMin a4d27484 
13:49:00.430 FAIL SummarizingMin 92ca8eaa 
13:49:00.431 FAIL SummarizingMin 154dd50f 
```
The WAIT line in full:
```
zcat $T | awk '/T13:48:5[2-4]/' | grep -a 'SV=sv2' | grep -a 'Domain time delay is currently' | python3 -I -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'][11:23], j.get('span-name'), '|', j['message'][:230])" | head -2
```
```
13:48:52.122 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.991983s (1970-01-03T18:10:32.998Z - 1970-01-03T17:10:33.006017Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
13:48:52.132 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.991983s (1970-01-03T18:10:32.998Z - 1970-01-03T17:10:33.006017Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
```

Two consecutive SummarizingMiningRoundTrigger requests across two steps, raw message heads (the lines quoted in the
issue comment):
```
zcat $T | awk '/T13:48:5[4-8]/' | grep -a -E 'tid:(46910331|1ee66b8d)|advancing time by|Domain time delay' | grep -a -E 'SV=sv2|advancing' | grep -a -E 'sending request|failed with|advancing|"span-name":"SummarizingMiningRoundTrigger"' | python3 -I -c "
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j['@timestamp'][11:23], j.get('span-name','-'), '|', j['message'].split(chr(10))[0][:200])"
```
```
13:48:54.444 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.989988s (1970-01-03T19:10:32.998Z - 1970-01-03T18:10:33.008012Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
13:48:54.457 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.989988s (1970-01-03T19:10:32.998Z - 1970-01-03T18:10:33.008012Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
13:48:54.518 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.989988s (1970-01-03T19:10:32.998Z - 1970-01-03T18:10:33.008012Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
13:48:54.585 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.989988s (1970-01-03T19:10:32.998Z - 1970-01-03T18:10:33.008012Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
13:48:54.905 SummarizingMiningRoundTrigger | Request (tid:46910331d8be3671ac27990c7ac90a0b) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15201: sending request SubmitAndWaitRequest(
13:48:55.540 - | advancing time by PT1H to 1970-01-03T20:10:32.998Z
13:48:56.261 SummarizingMiningRoundTrigger | Request (tid:46910331d8be3671ac27990c7ac90a0b) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15201: failed with ABORTED/LOCAL_VERDICT_TIMEOUT(2,0): Rejected transaction due to a par
13:48:56.843 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.989s (1970-01-03T20:10:32.998Z - 1970-01-03T19:10:33.009Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
13:48:56.868 SummarizingMiningRoundTrigger | Domain time delay is currently 59m 59.989s (1970-01-03T20:10:32.998Z - 1970-01-03T19:10:33.009Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
13:48:57.046 SummarizingMiningRoundTrigger | Request (tid:1ee66b8da8ee91d31aa4b0689f3ab1b7) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15201: sending request SubmitAndWaitRequest(
13:48:57.707 - | advancing time by PT1H to 1970-01-03T21:10:32.998Z
13:48:58.385 SummarizingMiningRoundTrigger | Request (tid:1ee66b8da8ee91d31aa4b0689f3ab1b7) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15201: failed with ABORTED/LOCAL_VERDICT_TIMEOUT(2,0): Rejected transaction due to a par
```
Every SEND above starts within about 1 s wall clock (no sim-clock step in between) after the previous medium failure,
so `callAndMark` sees `clock.now - lastFailure` well below 15 min and does not reset; the next sim step then adds the
next burst. The 15 min reset can only fire for a call that starts after a jump and before that jump's failures arrive,
which these two triggers never do in this window.

No recovery line: the test case ended (sv2 stopped) 25 s after the trip, before the 30 s reset timeout:
```
zcat $T | grep -a 'SpliceCircuitBreaker' | grep -a -E 'half-open|closed state|is open, calls' | python3 -I log/tmp/fmt.py | awk '!s[$0]++' | head
zcat $T | grep -a -E 'Stopping|shutting down|Closing' | grep -a 'SV=sv2' | head -2 | python3 -I log/tmp/fmt.py
```
```
13:46:14.538 DEBU 1036997668  | Closing DB-locked connection
13:49:25.666 INFO SvApp SV=sv2 | Stopping sv2 node
```
(The first command printed nothing; both lines come from the second.)

## 10. Nothing on main changed this since 11147; other hits

Fix status on main (fetched 2026-11-09, head 885cd84668): no commit since 136e2e3160 touches the suite, the test
breaker config, `SpliceCircuitBreaker`, the breaker defaults or the breaker wiring except 9f88d4ce64 (one unrelated
line, `enableKeyDownload`); the suite has no breaker override; ignore patterns unchanged for breakers:
```
git fetch -q origin main; git log -1 --format='%h %cI %s' origin/main
git merge-base --is-ancestor 136e2e3160 ab36803c8a && echo "136e2e3160 ancestor of ab36803c8a"
git log --format='%h %cs %s' 136e2e3160..origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanNewNetworkBackfillingTimeBasedIntegrationTest.scala apps/app/src/test/resources/include/svs/_sv.conf apps/common/src/main/scala/org/lfdecentralizedtrust/splice/util/SpliceCircuitBreaker.scala apps/common/src/main/scala/org/lfdecentralizedtrust/splice/config/SpliceConfig.scala apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/SpliceAppAutomationService.scala
git show 9f88d4ce64 -- apps/common/src/main/scala/org/lfdecentralizedtrust/splice/config/SpliceConfig.scala | grep -E '^[-+]' | grep -v -E '^(\+\+\+|---)'
git log --format='%h %cs %s' -S'circuitBreakers' 136e2e3160..origin/main -- apps/
git grep -n -i 'circuit' origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanNewNetworkBackfillingTimeBasedIntegrationTest.scala; echo "exit=$?"
git show origin/main:apps/app/src/test/resources/include/svs/_sv.conf | sed -n 51,60p
git grep -n -i 'circuit breaker' origin/main -- project/ignore-patterns/ | cut -c1-200
```
```
885cd84668 2026-11-09T19:55:07Z Upgrade Canton to 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (#8381)
136e2e3160 ancestor of ab36803c8a
9f88d4ce64 2026-11-06 [ci] Bump the Canton fork to 3.7.1 for grpc 1.85 (#8212)
+  override def enableKeyDownload: Boolean = false
exit=1
  parameters {
    circuit-breakers {
      # increase the max-failures in tests as things can be flaky
      medium-priority {
        max-failures = 20
      }
      low-priority {
        max-failures = 20
      }
    }
origin/main:project/ignore-patterns/canton_network_test_log.ignore.txt:127:Circuit breaker .* tripped after .* failures.*(Command|Splice)CircuitBreakerTest
```
(`git log -S'circuitBreakers'` printed nothing.)

The helper for the fix exists on main:
```
git grep -n -E 'def updateAllSvAppConfigs' origin/main -- apps/ | cut -d: -f2-4 | cut -c1-200
```
```
apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala:315:  def updateAllSvAppConfigs(
apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala:325:  def updateAllSvAppConfigs_(
```

Other hits (closed issues included). The issue bodies carry no log lines, so the search by message finds only
hand-written titles:
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "medium-priority-connection tripped"' --jq '.total_count, (.items[] | "\(.number) \(.state) \(.created_at[:10]) \(.title)")'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "ScanNewNetworkBackfillingTimeBasedIntegrationTest"' --jq '.total_count, (.items[] | "\(.number) \(.state) \(.created_at[:10]) \(.title)")'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "docker-canton-simtime" created:>=2026-10-26' --jq '.total_count, (.items[] | "\(.number) \(.state) \(.created_at[:16]) \(.title)")'
```
```
1
9487 closed 2026-08-05 Circuit breaker medium-priority-connection tripped after 20 failures
3
11182 open 2026-11-04 Scan bulk storage: BFT check runs once more after success and logs a false Not all objects are known ERROR
10336 closed 2026-10-12 Found problems in log/canton_network_test.clog: Skipped <n> SV rewards from last claimed round <m> to current round <k>. This is expected in case of SV inactivity
10475 closed 2026-10-20 ScanNewNetworkBackfillingTimeBasedIntegrationTest: checkErrors on canton-simtime log, ledger time from the post-jump sim clock vs record time from a pre-jump BFT block
15
11291 open 2026-11-09T13:50 GHA Run 42623223930 : job docker-canton-simtime (0) in scala_test Failed :fire:
11147 open 2026-11-02T19:56 GHA Run 41510362200 : job docker-canton-simtime (0) in scala_test Failed :fire:
11182 open 2026-11-04T07:32 Scan bulk storage: BFT check runs once more after success and logs a false Not all objects are known ERROR
11071 closed 2026-10-30T13:02 GHA Run 41047059702 : job docker-canton-simtime (0) in scala_test Failed :fire:
11061 closed 2026-10-30T11:42 GHA Run 41047059702 : job docker-canton-simtime (0) in scala_test Failed :fire:
11031 closed 2026-10-30T11:12 GHA Run 41044776115 : job docker-canton-simtime (0) in scala_test Failed :fire:
11003 closed 2026-10-30T11:07 GHA Run 41044455724 : job docker-canton-simtime (0) in scala_test Failed :fire:
10963 closed 2026-10-30T10:49 GHA Run 41042191156 : job docker-canton-simtime (0) in scala_test Failed :fire:
10879 closed 2026-10-29T05:31 GHA Run 40821274875 : job docker-canton-simtime (0) in scala_test Failed :fire:
10838 closed 2026-10-28T22:32 GHA Run 40775321053 : job docker-canton-simtime (0) in scala_test Failed :fire:
10804 closed 2026-10-28T22:05 GHA Run 40755669766 : job docker-canton-simtime (0) in scala_test Failed :fire:
10797 closed 2026-10-28T22:02 GHA Run 40749783712 : job docker-canton-simtime (0) in scala_test Failed :fire:
10713 closed 2026-10-28T05:40 GHA Run 40635877843 : job docker-canton-simtime (0) in scala_test Failed :fire:
10662 closed 2026-10-27T05:37 GHA Run 40453428305 : job docker-canton-simtime (0) in scala_test Failed :fire:
10581 closed 2026-10-26T05:39 GHA Run 40272593413 : job docker-canton-simtime (0) in scala_test Failed :fire:
```
Per hit: 9487 is LsuIntegrationTest on a validator (the origin of the sv4 `CircuitBreakersConfig.never` override), not
this test. 11182, 10336, 10475 are other failures of this suite (10475 is the related jump symptom, section 5). Of the
docker-canton-simtime issues, 11291 and 11147 are this failure; the closed ones carry other resolutions in their
comments (dup of 10939, fixed by canton-network/splice#8109 for the test-compile break, dup of 10520, 10581 no comment):
```
for r in $(gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "docker-canton-simtime" created:>=2026-10-26' --jq '.items[].number'); do echo "$r: $(gh api repos/DACH-NY/cn-test-failures/issues/$r/comments --jq '[.[].body[:160]] | join(" || ")' | tr '\n' ' ' | cut -c1-300)"; done
```
```
11291:  
11147:  
11182: The BFT check in BulkStorageCommitFromStaging runs once more after it has already succeeded, and that extra check fails when a peer has meanwhile committed the  || @isegall-da Can you please have a look? 
11071: Duplicate of #10939: re-run of run 41047059702 on 5adf8c654f, which predates the fix canton-network/splice#8150 
11061: Duplicate of #10939 (fixed by canton-network/splice#8150) 
11031: Duplicate of #10939 
11003: Duplicate of #10939 
10963: Duplicate of #10939 
10879: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 until #8109 (GovernanceLockTimeBasedIntegrationTest used truncateInsta 
10838: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 until #8109 (GovernanceLockTimeBasedIntegrationTest used truncateInsta 
10804: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 until #8109 (GovernanceLockTimeBasedIntegrationTest used truncateInsta 
10797: Fixed by canton-network/splice#8109: main did not compile its tests from 2026-10-28 18:56 until #8109 (GovernanceLockTimeBasedIntegrationTest used truncateInsta 
10713: Duplicate of #10520 
10662: Duplicate of #10520 
10581: 
```
10581 has no comment; it was closed as a duplicate and its run (mainnet Daml version workflow) failed in 20 jobs, so
it is not counted here (not opened further):
```
gh api repos/DACH-NY/cn-test-failures/issues/10581 --jq '.state_reason, .closed_at'
gh api "repos/canton-network/splice/actions/runs/40272593413/jobs?filter=all&per_page=100" --jq '.jobs[] | select(.conclusion=="failure") | "\(.id) attempt=\(.run_attempt) \(.name)"' | wc -l
```
```
duplicate
2026-10-26T10:00:22Z
20
```

Frequency: 2 hits (11147 on 2026-11-02, 11291 on 2026-11-09) against 97 post-merge main runs of the workflow from
2026-11-02 to 2026-11-09 (PR runs not counted):
```
gh api -X GET "repos/canton-network/splice/actions/workflows/163795441/runs" -f branch=main -f created='2026-11-02..2026-11-09' -f per_page=1 --jq '.total_count'
```
```
97
```
Suite authors (for the assignee suggestion):
```
git log --format='%h %cs %ae %s' origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanNewNetworkBackfillingTimeBasedIntegrationTest.scala | head -8
gh api repos/canton-network/splice/commits/3b7c8685c7 --jq '.author.login'
```
```
7934197d26 2026-10-14 98821776+ray-roestenburg-da@users.noreply.github.com Scan: an SV that joins after genesis writes its own bulk storage update segments (#7725)
14f89947c0 2026-10-12 pawel.perek@digitalasset.com Pause ReceiveSvRewardCouponTrigger in ScanNewNetworkBackfillingTimeBasedIntegrationTest (#7722)
3b7c8685c7 2026-10-10 98821776+ray-roestenburg-da@users.noreply.github.com Scan: integration test for bulk storage backfilling on a new network (#7658)
ray-roestenburg-da
```

## Verdict

11147: new, family AF. 11291: duplicate of 11147 (AF), confirmed by the family grep (a run of
LOCAL_VERDICT_TIMEOUT failures right after `advancing time by PT1H` steps, no medium reset, then the WARN). Corrected
mechanism (sections 6, 9): the sim-clock steps (about 2 s apart) time out the in-flight submissions of sv2's
medium-priority triggers SummarizingMiningRoundTrigger and CalculateRewardsTrigger; each trigger waits for domain time
to catch up and resubmits before the next step, so every call starts less than 15 min (sim) after the last failure,
`resetFailuresAfter` never fires, and 20 consecutive failures accumulate over 3 (11147) or 4 (11291) consecutive steps.
Flake, test-side, no functional effect (tests passed both times; the breaker re-closed after 31 s in 11147). Rare: 2 of
97 post-merge main runs in a week.

Fix (test-side, not written, nothing on main yet): in
`ScanNewNetworkBackfillingTimeBasedIntegrationTest.environmentDefinition`, add
`.addConfigTransforms((_, config) => ConfigTransforms.updateAllSvAppConfigs_(c => c.copy(parameters = c.parameters.copy(circuitBreakers = CircuitBreakersConfig.never)))(config))`,
as `LsuIntegrationTest` does for sv4. No ignore pattern (on wall-clock shards a breaker trip is real signal). Suggested
assignee: ray-roestenburg-da (author of the suite), optional.

Not verified: why a submission sent 0.6 s before a step is not completed by the step (submission latency in this
shard was not measured; some CalculateRewards submissions sent 0.85 s before a step did complete); whether other
simtime suites with hourly loops can trip the same way (none seen in the searches above); the exact compile of the
fix snippet (no sbt in triage).
