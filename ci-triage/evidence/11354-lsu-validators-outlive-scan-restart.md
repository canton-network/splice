# 11354 - LsuIntegrationTest: validator BFT scan calls exhaust their retries while test 2 restarts all scans (run 42975175376)

New (no family yet; proposed AU). main b350273 (#8369), job `logical-sync-upgrade (0)`, Canton
3.7.3-snapshot.20261109.20894.0.ve6e6c252. Both tests of LsuIntegrationTest passed (`Tests: succeeded 2, failed
0`); checkErrors flagged 3 `Consensus not reached.` WARNs from BftScanConnection of aliceValidator and
splitwellValidator at 07:12:10.605 to 07:12:11.010, 7 s into the second test. The second test starts by stopping
all SV, scan and SV-validator nodes "to clear any caches" and re-running `initDso`, but aliceValidator and
splitwellValidator, started by the first test, are not in that list and keep running. Their triggers
(ValidatorPackageVettingTrigger, RollForwardLsuTrigger) make BFT calls (2 of 3 matching) against the scans; the
client-call retry policy gives up after 12 retries (07:12:04.088 to 07:12:11.010, about 7 s) and then logs WARN.
The scans were stopped at 07:12:03.060 and the second healthy scan finished initialization at 07:12:10.925; sv1Scan
alone took 5.03 s to close its HTTP binding (the others closed by 07:12:03.071), and the parallel stop waits for the slowest node before
`initDso` starts at 07:12:08.125. The race exists since #6503 (2026-07-23); this run lost it.

- Run: https://github.com/canton-network/splice/actions/runs/42975175376, main b3502738ae ("Support different
  featured app right kinds in load tester (#8369)"), job 130372803034 `ci / scala_test_logical_sync_upgrade /
  logical-sync-upgrade (0)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (LsuIntegrationTest).

## 1. Job classification and flagged lines

Tests passed; only checkErrors failed, with three lines.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11354/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id 2e6a72b0-dda0-11f1-acd4-bd766d62a009): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 12 minutes, 39 seconds.
[info] Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0
```

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11354/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-300
```
```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-11T07:12:10.605Z","message":"Consensus not reached.","logger_name":"o.l.s.s.a.a.c.BftScanConnection:LsuIntegrationTest/config=1e9a52d1/validator=aliceValidator","thread_name":"LsuIntegrationTest-1e9a52d1-env-ec-405","level":"WARN","stack_trace":"org.lfdecentralizedtrust.spli
***"@timestamp":"2026-11-11T07:12:10.987Z","message":"Consensus not reached.","logger_name":"o.l.s.s.a.a.c.BftScanConnection:LsuIntegrationTest/config=1e9a52d1/validator=splitwellValidator","thread_name":"LsuIntegrationTest-1e9a52d1-env-ec-1189","level":"WARN","stack_trace":"org.lfdecentralizedtrust
***"@timestamp":"2026-11-11T07:12:11.010Z","message":"Consensus not reached.","logger_name":"o.l.s.s.a.a.c.BftScanConnection:LsuIntegrationTest/config=1e9a52d1/validator=splitwellValidator","thread_name":"LsuIntegrationTest-1e9a52d1-env-ec-271","level":"WARN","stack_trace":"org.lfdecentralizedtrust.
Total: 3 lines with problems.
```

Per WARN, the responses per scan (artifact `logs-logical-sync-upgrade-0`, `T=canton_network_test.clog.gz`), and the
port mapping:

```
zcat $T | grep -a '"message":"Consensus not reached."' | grep -a 'T07:12:1' | while read -r l; do ts=$(echo "$l" | grep -oE '"@timestamp":"[^"]+"' | cut -d'"' -f4); v=$(echo "$l" | grep -oE 'validator=[a-zA-Z]+' | head -1); r=$(echo "$l" | grep -oE '\(List\(http://localhost:[0-9]+\),(HttpFailureResponse\(404 Not Found|ExceptionFailureResponse\([a-zA-Z.]+|SuccessfulResponse\([^)]*\))' | sed -E 's/\(List\(http:\/\/localhost:([0-9]+)\),/\1=/; s/org.apache.pekko.stream.//' | tr '\n' ' '); echo "$ts $v $r"; done
zcat $T | grep -a -oE 'Running on sv[1-4]Scan command [A-Za-z]+\([^)]*\) against NetworkAppClientConfig\(http://127.0.0.1:[0-9]+' | sed -E 's/command [A-Za-z]+\([^)]*\) against NetworkAppClientConfig\(//' | sort -u
```
```
2026-11-11T07:12:10.605Z validator=aliceValidator 5212=HttpFailureResponse(404 Not Found 5312=ExceptionFailureResponse(StreamTcpException 5012=ExceptionFailureResponse(StreamTcpException 
2026-11-11T07:12:10.987Z validator=splitwellValidator 5312=ExceptionFailureResponse(StreamTcpException 5012=HttpFailureResponse(404 Not Found 5112=SuccessfulResponse(Vector() 
2026-11-11T07:12:11.010Z validator=splitwellValidator 5012=HttpFailureResponse(404 Not Found 5112=SuccessfulResponse(None) 5312=ExceptionFailureResponse(StreamTcpException 
Running on sv1Scan http://127.0.0.1:5012
Running on sv2Scan http://127.0.0.1:5112
Running on sv3Scan http://127.0.0.1:5212
Running on sv4Scan http://127.0.0.1:5312
```

The `StreamTcpException`s are `java.net.ConnectException: Connection refused`, and the 404 bodies are `The requested
resource could not be found: http://localhost:5212/api/scan/v0/admin/sv/voterequests` (first WARN, decoded):

```
zcat $T | grep -a '"message":"Consensus not reached."' | grep -a 'T07:12:1' | sed -E 's/\\n/\n    /g; s/\\"/"/g' | cut -c1-400 | grep -a -E 'could not be found|Connection refused' | head -3 | cut -c1-120
```
```
      "error" : "The requested resource could not be found: http://localhost:5212/api/scan/v0/admin/sv/voterequests"
    })), (List(http://localhost:5312),ExceptionFailureResponse(org.apache.pekko.stream.StreamTcpException: Tcp command [
      "error" : "The requested resource could not be found: http://localhost:5012/api/scan/v0/admin/sv/voterequests"
```

## 2. Suite timeline

The WARNs fall 7 s into the second test case.

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-11T07:06:25.503Z Starting test suite 'LsuIntegrationTest'...",
2026-11-11T07:06:32.963Z Starting 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade'...",
2026-11-11T07:11:54.421Z Test succeeded: 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade'",
2026-11-11T07:12:03.031Z Starting 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'...",
2026-11-11T07:18:59.548Z Test succeeded: 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'",
```

## 3. The test restarts all SV and scan nodes but not the two validators started by test 1

Test 1 starts aliceValidator and splitwellValidator; test 2 stops `allNodes` (scans, SVs, SV validators), which does
not include them, then calls `startAllSync` for them. The end of test 2 does stop the two validators first.

```
git show b350273:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala | sed -n '226,228p;354,390p;897,903p'
```
```
  "cancel a scheduled logical synchronizer upgrade" in { implicit env =>
    initDso(includeLocal = false)
    startAllSync(aliceValidatorBackend, splitwellValidatorBackend)
  "upgrade synchronizer to new physical synchronizer without downtime" in { implicit env =>
    // start the nodes early so that the sv app can remove the existing successor physical synchronizer state
    withCantonSvNodes(
...
      val allNodes = Seq[AppBackendReference](
        sv1ScanBackend,
        sv2ScanBackend,
        sv3ScanBackend,
        sv4ScanBackend,
        sv1Backend,
        sv1LocalBackend,
        sv1NoLegacyLocalBackend,
        sv2Backend,
        sv3Backend,
        sv4Backend,
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
      )
      // restart to clear any caches
      allNodes.par.foreach(_.stop())
      initDso(includeLocal = false)
      startAllSync(aliceValidatorBackend, splitwellValidatorBackend)
      clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
        // manually stop stuff as we destroy the new synchronizer as it runs in process
        val validators = Seq(aliceValidatorBackend, splitwellValidatorBackend)
        stopAllAsync(validators*).futureValue
        validators.par.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
        stopAllAsync(allNodes*).futureValue
        allBackends.par.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
```

The two validators run without a stop from test 1 until the end of test 2 (the `startAllSync` at 07:12:13 logs
`Starting node` again, but no stop precedes it):

```
zcat $T | grep -a -E '"message":"(Starting|Stopping) node (aliceValidator|splitwellValidator)"' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]+)".*/\1 \2/'
```
```
2026-11-11T07:09:49.593Z Starting node aliceValidator
2026-11-11T07:09:50.353Z Starting node splitwellValidator
2026-11-11T07:12:13.495Z Starting node aliceValidator
2026-11-11T07:12:13.495Z Starting node splitwellValidator
2026-11-11T07:18:53.310Z Stopping node splitwellValidator
2026-11-11T07:18:53.310Z Stopping node aliceValidator
```

Origin (git blame and PR authors): test 1 got its validators in #4716, the restart and the test-2 `startAllSync`
came with #6503, both by nicu-da.

```
git blame -L 226,228 -L 386,390 b350273 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala | cut -c1-12
for c in 1d2425e3361 b062e3d3c19; do git log -1 --format='%h %ad %an %s' --date=short $c; done
for p in 6503 4716; do gh api repos/canton-network/splice/pulls/$p --jq '"\(.number) \(.user.login) \(.merged_at) \(.title)"'; done
```
```
6330f2363f8
897d0ec3ba9
1d2425e3361
b062e3d3c19
b062e3d3c19
b062e3d3c19
b062e3d3c19
b062e3d3c19
1d2425e336 2026-04-01 Nicu Reut lsu test - Add validator that is offline during the LSU (#4716)
b062e3d3c1 2026-07-23 Nicu Reut Extend LSU cancellation test to mimic actual cancellations (#6503)
6503 nicu-da 2026-07-23T14:22:27Z Extend LSU cancellation test to mimic actual cancellations
4716 nicu-da 2026-04-01T11:01:37Z lsu test - Add validator that is offline during the LSU
```

## 4. Scan downtime: stop at 07:12:03, sv1Scan unbind 5 s, scans healthy again from 07:12:10.5

```
zcat $T | grep -a -E '"@timestamp":"2026-11-11T07:12:0[3-8]' | grep -a 'scan=sv1Scan' | grep -a -E 'http binding|Successfully closed .AsyncCloseable|still not' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /;s/LsuIntegrationTest\/config=1e9a52d1\///' | cut -c1-200 | head -3
zcat $T | grep -a -E '"@timestamp":"2026-11-11T07:12:0[3-9]' | grep -a 'scan=sv[2-4]Scan' | grep -a "Successfully closed 'AsyncCloseable(name=http binding admin service)'" | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /;s/LsuIntegrationTest\/config=1e9a52d1\///' | cut -c1-200
```
```
2026-11-11T07:12:03.063Z Attempting to close 'AsyncCloseable(name=http binding admin service)'...",[o.l.s.a.h.HttpAdminService$HttpAdminServiceImpl:scan=sv1Scan] DEBUG
2026-11-11T07:12:07.565Z Task closing http binding admin service still not completed after 4500 milliseconds. Continue waiting...",[o.l.s.s.ScanAppBootstrap:scan=sv1Scan] INFO
2026-11-11T07:12:08.091Z Successfully closed 'AsyncCloseable(name=http binding admin service)'.",[o.l.s.a.h.HttpAdminService$HttpAdminServiceImpl:scan=sv1Scan] DEBUG
2026-11-11T07:12:03.071Z Successfully closed 'AsyncCloseable(name=http binding admin service)'.",[o.l.s.a.h.HttpAdminService$HttpAdminServiceImpl:scan=sv2Scan] DEBUG
2026-11-11T07:12:03.071Z Successfully closed 'AsyncCloseable(name=http binding admin service)'.",[o.l.s.a.h.HttpAdminService$HttpAdminServiceImpl:scan=sv3Scan] DEBUG
2026-11-11T07:12:03.071Z Successfully closed 'AsyncCloseable(name=http binding admin service)'.",[o.l.s.a.h.HttpAdminService$HttpAdminServiceImpl:scan=sv4Scan] DEBUG
```

Stop and restart order (the parallel stop returns after the slowest node, then `initDso` starts sv1..sv4 and the
scans one after the other):

```
zcat $T | grep -a -E '"@timestamp":"2026-11-11T07:1(2:[0-5]|3:)' | grep -a -E '"message":"(Starting node sv[1-4](Scan)?"|[^"]*initialization: (Starting initialization|Initialize app finished))' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/LsuIntegrationTest\/config=1e9a52d1\///' | grep -a -E 'Scan|Starting node' | cut -c1-140
zcat $T | grep -a '"message":"Stopping node sv1Scan"' | grep -a T07:12 | cut -c1-70
```
```
2026-11-11T07:12:08.125Z Starting node sv1",[o.l.s.e.SvApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:08.911Z Starting node sv3",[o.l.s.e.SvApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:09.225Z Starting node sv2",[o.l.s.e.SvApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:09.545Z Starting node sv4",[o.l.s.e.SvApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:09.923Z Starting node sv2Scan",[o.l.s.e.ScanApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:10.329Z sv2Scan app initialization: Starting initialization",[o.l.s.s.ScanApp:scan=sv2Scan] INFO
2026-11-11T07:12:10.329Z Starting node sv3Scan",[o.l.s.e.ScanApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:10.540Z sv2Scan app initialization: Initialize app finished after PT0.21111424S",[o.l.s.s.ScanApp:scan=sv2Scan] INFO
2026-11-11T07:12:10.727Z sv3Scan app initialization: Starting initialization",[o.l.s.s.ScanApp:scan=sv3Scan] INFO
2026-11-11T07:12:10.729Z Starting node sv1Scan",[o.l.s.e.ScanApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:10.925Z sv3Scan app initialization: Initialize app finished after PT0.19746691S",[o.l.s.s.ScanApp:scan=sv3Scan] INFO
2026-11-11T07:12:11.129Z sv1Scan app initialization: Starting initialization",[o.l.s.s.ScanApp:scan=sv1Scan] INFO
2026-11-11T07:12:11.129Z Starting node sv4Scan",[o.l.s.e.ScanApps:LsuIntegrationTest/config=1e9a52d1] INFO
2026-11-11T07:12:11.400Z sv1Scan app initialization: Initialize app finished after PT0.270998108S",[o.l.s.s.ScanApp:scan=sv1Scan] INFO
2026-11-11T07:12:11.596Z sv4Scan app initialization: Starting initialization",[o.l.s.s.ScanApp:scan=sv4Scan] INFO
2026-11-11T07:12:11.938Z sv4Scan app initialization: Initialize app finished after PT0.342269501S",[o.l.s.s.ScanApp:scan=sv4Scan] INFO
{"@timestamp":"2026-11-11T07:12:03.060Z","message":"Stopping node sv1S
```

While a scan initializes, its HTTP server is bound but answers every API route with 404 (sv3Scan, before its init
finished at 07:12:10.925):

```
zcat $T | grep -a -E '"@timestamp":"2026-11-11T07:12:1[01]' | grep -a 'scan=sv3Scan' | grep -a 'Responding with status code: 404' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /;s/LsuIntegrationTest\/config=1e9a52d1\///' | cut -c1-140
```
```
2026-11-11T07:12:10.343Z HTTP GET /api/scan/v0/sv-bft-sequencers from (127.0.0.1:33906): Responding with status code: 404 Not Found, took 0 
2026-11-11T07:12:10.392Z HTTP GET /api/scan/v0/admin/sv/voterequests from (127.0.0.1:33910): Responding with status code: 404 Not Found, too
2026-11-11T07:12:10.602Z HTTP GET /api/scan/v0/admin/sv/voterequests from (127.0.0.1:33912): Responding with status code: 404 Not Found, too
2026-11-11T07:12:10.734Z HTTP GET /api/scan/v0/sv-bft-sequencers from (127.0.0.1:33926): Responding with status code: 404 Not Found, took 0 
2026-11-11T07:12:10.754Z HTTP GET /api/scan/v0/dso-sequencers from (127.0.0.1:33940): Responding with status code: 404 Not Found, took 0 ms"
```

## 5. The validators' BFT call retries 12 times (about 7 s), then logs WARN

`Consensus not reached. Will be retried.` is INFO inside the retry loop; the WARN `Consensus not reached.` is logged
at `consensusFailureLogLevel` only after `retryForClientCalls` gives up:

```
git show b350273:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/api/client/BftCallExecutor.scala | sed -n '228,251p;574,577p'
```
```
    retryProvider
      .retryForClientCalls(
        "bft_call",
        s"Bft call with $nTargetSuccess out of ${requestFrom.size} matching responses",
        executeCall(
          call,
          requestFrom,
          nTargetSuccess,
          logger,
          shortenResponsesForLog,
          disagreementLogLevel,
          connectionMetrics,
        ),
        logger,
        (_: String) => ConsensusNotReachedRetryable,
      )
      .transform {
        case Success((data, peers)) =>
          markBftCall("ok", connectionMetrics)
          Success(BftOutcome.Agreed(data, peers))
        case Failure(c: ConsensusNotReached[T @unchecked]) =>
          LoggerUtil.logThrowableAtLevel(consensusFailureLogLevel, "Consensus not reached.", c)
          markBftCall("consensus_not_reached", connectionMetrics)
          Success(BftOutcome.Disagreement(c.numRequests, nTargetSuccess, c.responses))
        case c: ConsensusNotReached[?] =>
          logger.info("Consensus not reached. Will be retried.", c)
          ErrorKind.TransientErrorKind()
        case _ => ErrorKind.FatalErrorKind
```

Levels and spans of all `Consensus not reached` lines in the run (only the three give-ups are WARN):

```
zcat $T | grep -a 'Consensus not reached' | sed -E 's/\{"@timestamp":"([^"]{19})[^"]*","message":"([^"]{0,40}).*"logger_name":"([^"]*)".*"level":"([A-Z]+)".*span-name":"([^"]*)".*/\1 \4 \3 \5/; s/LsuIntegrationTest\/config=1e9a52d1\///' | awk '{print substr($1,1,18), $2, $3, $4}' | sort | uniq -c
```
```
     33 2026-11-11T07:12:0 INFO o.l.s.s.a.a.c.BftScanConnection:validator=aliceValidator bft_call
     32 2026-11-11T07:12:0 INFO o.l.s.s.a.a.c.BftScanConnection:validator=splitwellValidator bft_call
      3 2026-11-11T07:12:1 INFO o.l.s.s.a.a.c.BftScanConnection:validator=aliceValidator bft_call
      4 2026-11-11T07:12:1 INFO o.l.s.s.a.a.c.BftScanConnection:validator=splitwellValidator bft_call
      1 2026-11-11T07:12:1 WARN o.l.s.s.a.a.c.BftScanConnection:validator=aliceValidator ValidatorPackageVettingTrigger-work
      1 2026-11-11T07:12:1 WARN o.l.s.s.a.a.c.BftScanConnection:validator=splitwellValidator RollForwardLsuTrigger-work
      1 2026-11-11T07:12:1 WARN o.l.s.s.a.a.c.BftScanConnection:validator=splitwellValidator ValidatorPackageVettingTrigger-work
```

One call end to end (trace of the 07:12:11.010 WARN, RollForwardLsuTrigger of splitwellValidator): first failure
1 s after the stop, give-up after 12 retries.

```
zcat $T | grep -a 0f75b465ea8bf26c3e88c72f4e7599dc | grep -a -E 'BftScanConnection' | grep -a -v 'Consensus not reached. Will' | grep -a -E 'New kind of error|Giving up|"Consensus not reached."' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /;s/LsuIntegrationTest\/config=1e9a52d1\///' | cut -c1-230
```
```
2026-11-11T07:12:04.088Z The operation 'Bft call with 2 out of 3 matching responses' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a number of 0 failures, and after 100
2026-11-11T07:12:11.010Z The operation 'Bft call with 2 out of 3 matching responses' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. ",[o.l.s.s.a.a.c.BftScanConnection:validator=splitwellValidator] INFO
2026-11-11T07:12:11.010Z Consensus not reached.",[o.l.s.s.a.a.c.BftScanConnection:validator=splitwellValidator] WARN
```

## 6. Duplicate check

Not in `known-families.md` or `evidence/INDEX.tsv` (only 11289 mentions the string, a sim-time BFT verdict case).
Tracker search, per hit:

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Consensus not reached"' --jq '.total_count, (.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:110])")'
```
```
8
6668 open 2025-12-15 Mainnet: Consensus not reached in `listVoteRequests`
9795 closed 2026-08-31 simtime (0) checkErrors: 41x "Consensus not reached" from SvTimeBasedAmuletPriceIntegrationTest: Pinged @adeto
9836 closed 2026-09-01 SvTimeBasedRoundMgmtIntegrationTest: "message":"Consensus not reached." log/canton_network_test.clog contains 
10342 closed 2026-10-12 0.9.0 validator fails to start against pre-0.9.0 scans
10067 closed 2026-09-08 deploy_lsu_operator: The create-onboarding-secret POST was 429'd by the SV app's global per-client-IP limiter 
8879 closed 2026-06-19 GHA Run 27824925886 : job without-canton (1) in scala_test Failed :fire:
6629 closed 2025-12-06 Mockito fail in periodically refresh the list of scans
4946 closed 2025-07-09 ValidatorPreflightIntegrationTest: NetworkError - transfer does not appear in transactions log 
```

- 6668: Mainnet listVoteRequests, production, not a test restart.
- 9795, 9836: sim-time suites (SvTimeBased*), not LsuIntegrationTest.
- 10342: version skew between validator and scans.
- 10067, 8879, 6629, 4946: different failures (rate limiter, without-canton shard, Mockito unit test, preflight).

None is the same failure. The previous logical-sync-upgrade issue, 11256 (closed), is a different shape (dup of
10729 per the 2026-11-07 round). Family AB (11140, BftScanConnection refresh WARN about a stopped sv2Scan after a
suppression ended) is a related mechanism (validator BFT client vs a deliberately stopped scan) but a different
logger message, test and trigger. Not family AA/H2 (no LSU trigger or psid involved; the WARNs precede the LSU
schedule).

Existing ignore patterns cover this message only for two BftScanConnection test suites:

```
grep -n -i 'consensus not reached' project/ignore-patterns/*
```
```
project/ignore-patterns/canton_network_test_log.ignore.txt:89:Consensus not reached.*BftScanConnection:BftScanConnectionTest.*
project/ignore-patterns/canton_network_test_log.ignore.txt:90:Consensus not reached.*BftScanConnection:ConfigurationProvidedBftScanConnectionIntegrationTest.*
```

## Verdict

New, test flake (race in LsuIntegrationTest, in the test since #6503, 2026-07-23). The second test restarts all
SV and scan nodes but leaves aliceValidator and splitwellValidator running from the first test; their triggers'
BFT scan calls give up after 12 retries (about 7 s) and log WARN when the scans take longer than that to come back.
Here the scans were unavailable from 07:12:03.060 until 07:12:10.540 / 10.925 (first two healthy scans), mostly
because sv1Scan's HTTP binding took 5.03 s to close and the parallel stop waits for it.

Fix (test, LsuIntegrationTest.scala, second test): stop the two validators before the restart and let the existing
`startAllSync(aliceValidatorBackend, splitwellValidatorBackend)` start them again, the same order the end of the
test already uses, e.g. `stopAllAsync(aliceValidatorBackend, splitwellValidatorBackend).futureValue` before
`allNodes.par.foreach(_.stop())`. Alternative: wrap the restart in a suppression of BftScanConnection WARNs; worse,
because it hides the signal for the rest of the window. No ignore pattern. Suggested assignee nicu-da (author of the
restart, #6503, and of the validators in test 1, #4716).

Not verified: why sv1Scan's HTTP unbind took 5 s (likely in-flight requests from the validators held the binding;
not checked); how often the restart window exceeds 7 s in passing runs (no passing artifacts examined), so the hit
rate is unknown beyond this single occurrence.
