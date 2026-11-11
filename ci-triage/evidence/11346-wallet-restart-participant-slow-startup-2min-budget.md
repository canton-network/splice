# 11346 - WalletSurviveCantonRestartIntegrationTest: restarted participant needs 106 s to serve the Ledger API, the 2 min tap budget runs out (run 42914678423)

new. main 5ccef7f ("Give onboardUser in BFT scan tests a budget above the 38s request timeout (#8410)"), job
`wall-clock-time (5)`, canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252. 19 tests passed, 1 failed:
`WalletSurviveCantonRestartIntegrationTest / Wallet should survive Canton restarts`, in the
`eventuallySucceeds(2.minutes)` around `aliceWalletClient.tap(2)` after the second start of the standalone participant.
The test starts the tap loop right after spawning the Canton process (`withCanton` does not wait for readiness), so
the 2 min budget includes JVM start and participant startup. The second start was slow at every stage (JVM start
9 s vs 1.3 s, first log line to Ledger API listening 97 s vs 24 s); the restart also re-decodes 104 stored packages
(40 s). The Ledger API listened 106 s into the budget, the participant reconnected to the synchronizer at 120 s, and
the only tap that got past authorization submitted at 00:06:28 (122 s) and hit the 30 s gRPC deadline, then the 38 s
HTTP timeout; the budget had expired, so `eventuallySucceeds` failed. Flake, test-side budget.

- Run: https://github.com/canton-network/splice/actions/runs/42914678423, main 5ccef7f, job 130205231981 `ci / scala_test_wall_clock_time / wall-clock-time (5)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (WalletSurviveCantonRestartIntegrationTest).

Logs: job log `log/inbox-1111/130205231981.log`, artifact `log/11346/logs-wall-clock-time-5/` (gitignored).
Below `$T` = `canton_network_test.clog.gz`, `$C1`/`$C2` = `canton-standalone-wallet-survive-canton-restarts-{1,2}.clog.gz`,
`FMT` = the generic formatter of recipe 4:
`sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'`.

## 1. Job resolution and failing assertion

```
gh api "repos/canton-network/splice/actions/runs/42914678423/jobs?filter=latest&per_page=100" --jq '.jobs[]|select(.conclusion=="failure")|"\(.id) \(.name) | \([.steps[]|select(.conclusion=="failure")|.name]|join(","))"'
```
```
130205231981 ci / scala_test_wall_clock_time / wall-clock-time (5) | Run Tests
```

```
sed -E 's/\x1b\[[0-9;]*m//g' log/inbox-1111/130205231981.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/inbox-1111/130205231981.log | grep -a -A25 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -E 'TestFailed|eventuallySucceeds|WalletSurvive' | head -6
```
```
[info] - should survive Canton restarts *** FAILED ***
[info] - survive Canton restarts *** FAILED ***
[info] Run completed in 25 minutes, 31 seconds.
[info] Tests: succeeded 19, failed 1, canceled 0, ignored 4, pending 0

[info]   org.scalatest.exceptions.TestFailedException was thrown. (BaseTestExtensions.scala:37)
[info]   at org.lfdecentralizedtrust.splice.BaseTestExtensions.$anonfun$eventuallySucceeds$1(BaseTestExtensions.scala:37)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.WalletSurviveCantonRestartIntegrationTest.eventually(WalletSurviveCantonRestartIntegrationTest.scala:12)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.WalletSurviveCantonRestartIntegrationTest.eventuallySucceeds(WalletSurviveCantonRestartIntegrationTest.scala:12)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.WalletSurviveCantonRestartIntegrationTest.$anonfun$new$10(WalletSurviveCantonRestartIntegrationTest.scala:74)
```

The job log also ends with `##[error]Executing the custom container implementation failed. Please contact your self
hosted runner administrator.`; that is the runner's post-step after the test failure, not a cause.

## 2. The test code at the run's sha

```
git show 5ccef7f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletSurviveCantonRestartIntegrationTest.scala | sed -n '58,77p'
```
```
      clue("Second run of Canton participant") {
        withCanton(
          Seq(
            testResourcesPath / "standalone-participant-extra.conf"
          ),
          Seq.empty,
          "wallet-survive-canton-restarts-2",
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> dbName,
        ) {
          clue("We can tap and list after Canton restart and domain reconnection") {
            loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
              {
                // Due to the circuit breaker kicking in, this might take a bit longer to succeed after some failures due to the disconnect
                // Disable suppressErrors to avoid nested loggerFactory calls
                eventuallySucceeds(2.minutes, suppressErrors = false) {
                  aliceWalletClient.tap(2)
                }
                aliceWalletClient.list()
```

`withCanton` only spawns the process and runs the body; it does not wait for the node:

```
git show 5ccef7f:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/ProcessTestUtil.scala | sed -n '61,85p'
```
```
  protected def withCanton[A](
      configs: Seq[File],
      extraConfigs: Seq[String],
      logSuffix: String,
      extraEnv: (String, String)*
  )(test: => A): A = {
    Using.resource(
      clue(s"Starting external Canton process $logSuffix with ${extraConfigs}")(
        startCantonInternal(
          configs.flatMap(config => Seq("-c", config.toString)) ++ extraConfigs.flatMap(
            Seq("-C", _)
          ),
          logSuffix,
          extraEnv*
        )
      )
    )(_ =>
      clue(s"Using external Canton process $logSuffix") {
        test
      }
    )((resource: Process) => {
      clue(s"Destroying external Canton process $logSuffix") {
        resource.destroyAndWait()
      }
    })
  }
```

In the first run `aliceValidatorLocalBackend.startSync()` (line 45) waits for the participant; the second run has no such wait.

## 3. Suite timeline

```
zcat $T | grep -a 'WalletSurviveCantonRestart' | grep -a -E "Starting test suite|Test (succeeded|failed)|Running clue|Finished clue|Failed clue" | FMT | grep -E 'Second run|restarts-2|tap and list after|Test failed|Starting test suite' | cut -c1-170
```
```
2026-11-11T00:02:44.101Z Starting test suite 'WalletSurviveCantonRestartIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-11T00:04:25.432Z Running clue: Second run of Canton participant",...
2026-11-11T00:04:25.433Z Running clue: Starting external Canton process wallet-survive-canton-restarts-2 with List()",...
2026-11-11T00:04:25.555Z Finished clue: Starting external Canton process wallet-survive-canton-restarts-2 with List()",...
2026-11-11T00:04:25.555Z Running clue: Using external Canton process wallet-survive-canton-restarts-2",...
2026-11-11T00:04:25.581Z Running clue: We can tap and list after Canton restart and domain reconnection",...
2026-11-11T00:06:59.184Z Failed clue: We can tap and list after Canton restart and domain reconnection",... ERROR
2026-11-11T00:07:17.184Z Test failed: 'WalletSurviveCantonRestartIntegrationTest/Wallet should survive Canton restarts', message: org.scalatest.exceptions.TestFailedException was thrown., ...
```

Suites run one at a time in this shard (the reporter shows `ExpiryWithNoVettedAmuletVersionIntegrationTest` completing at
00:02:44.090 and `AmuletExpiryV1FallbackIntegrationTest` starting at 00:08:06.690). The budget started at 00:04:25.58 and
expired at about 00:06:25.6.

## 4. Taps 00:04:25 to 00:06:16: 403 because the Ledger API was down

```
zcat $T | grep -a -E 'T00:04:25\.(6[0-9])' | grep -a 'aliceValidatorLocal' | grep -a -E 'ListUserRights|GetUser|Authorization|403' | FMT | cut -c1-230
```
```
2026-11-11T00:04:25.643Z Request (tid:c4255f9152ca32339d5c989fe559c937) com.daml.ledger.api.v2.admin.UserManagementService/ListUserRights to 127.0.0.1:27501: failed with UNAVAILABLE/io exception",...
2026-11-11T00:04:25.643Z Request (tid:6142ff3597ce9977f2fd0d60c333e2d3) com.daml.ledger.api.v2.admin.UserManagementService/GetUser to 127.0.0.1:27501: failed with UNAVAILABLE/io exception",...
2026-11-11T00:04:25.643Z Suppressed WARN: Authorization Failed for alice_wallet_user-b35d8974 for operation 'tap'. Reason: UNAVAILABLE: io exception",[o.l.s.w.a.h.UserWalletAuthExtractor:...validator=aliceValidatorLocal] INFO
2026-11-11T00:04:25.659Z HTTP POST /api/validator/v0/wallet/tap from (127.0.0.1:33120): Responding with status code: 403 Forbidden, took 45 ms",...
```

All authorization failures in the clue have the same reason; first and last:

```
zcat $T | grep -a -E 'T00:0[4-7]' | grep -a "Authorization Failed for alice_wallet_user-b35d8974 for operation 'tap'" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",.*//' | awk '{t=$1; $1=""; if($0!=last){print t, "first:", $0; last=$0} lt=t} END{print lt, "last"}'
zcat $T | grep -a -E 'T00:0[4-7]' | grep -a -c "Authorization Failed for alice_wallet_user-b35d8974 for operation 'tap'"
```
```
2026-11-11T00:04:25.643Z first:  Suppressed WARN: Authorization Failed for alice_wallet_user-b35d8974 for operation 'tap'. Reason: UNAVAILABLE: io exception
2026-11-11T00:06:16.104Z last
34
```

## 5. Second participant start: slower at every stage than the first, Ledger API at 00:06:11.8

```
for i in 1 2; do zcat canton-standalone-wallet-survive-canton-restarts-$i.clog.gz | grep -a -E 'Starting Canton version|Starting up with resolved|Starting node extra|Succeeded startup stage|Listening on|Connected to synchronizer|Successfully re-connected' | FMT | cut -c1-150; done
```
```
== 1
2026-11-11T00:02:58.801Z Starting Canton version 3.7.3-snapshot.20261109.20894.0.ve6e6c252",[c.d.canton.CantonCommunityApp$] INFO
2026-11-11T00:03:01.008Z Starting up with resolved config ...
2026-11-11T00:03:01.941Z Starting node extraStandaloneParticipant",[c.d.c.e.ParticipantNodes] INFO
2026-11-11T00:03:03.471Z Succeeded startup stage: Report liveness and readiness over gRPC and HTTP during startup",...
2026-11-11T00:03:06.233Z Succeeded startup stage: Validate schema and run database migrations if necessary",...
2026-11-11T00:03:07.304Z Succeeded startup stage: Initialise storage",...
2026-11-11T00:03:07.647Z Succeeded startup stage: Init crypto module",...
2026-11-11T00:03:08.181Z Succeeded startup stage: Stage Admin API",...
2026-11-11T00:03:22.564Z Listening on 127.0.0.1:27501 over plain text with LedgerApiKeepAliveServerConfig(10m,20s,10s,false).",...
2026-11-11T00:03:25.435Z Succeeded startup stage: Startup participant node",...
2026-11-11T00:03:34.624Z Connected to synchronizer and starting synchronisation: Synchronizer 'global'",...
== 2
2026-11-11T00:04:34.461Z Starting Canton version 3.7.3-snapshot.20261109.20894.0.ve6e6c252",[c.d.canton.CantonCommunityApp$] INFO
2026-11-11T00:04:45.202Z Starting up with resolved config ...
2026-11-11T00:04:48.797Z Starting node extraStandaloneParticipant",[c.d.c.e.ParticipantNodes] INFO
2026-11-11T00:04:56.844Z Succeeded startup stage: Report liveness and readiness over gRPC and HTTP during startup",...
2026-11-11T00:05:03.801Z Succeeded startup stage: Validate schema and run database migrations if necessary",...
2026-11-11T00:05:05.868Z Succeeded startup stage: Initialise storage",...
2026-11-11T00:05:09.473Z Succeeded startup stage: Init crypto module",...
2026-11-11T00:05:12.013Z Succeeded startup stage: Stage Admin API",...
2026-11-11T00:05:12.803Z Succeeded startup stage: Init node id",...
2026-11-11T00:05:57.504Z Succeeded startup stage: generate-or-await-node-topology-tx",...
2026-11-11T00:06:11.845Z Listening on 127.0.0.1:27501 over plain text with LedgerApiKeepAliveServerConfig(10m,20s,10s,false).",...
2026-11-11T00:06:19.374Z Succeeded startup stage: Startup participant node",...
2026-11-11T00:06:26.151Z Connected to synchronizer: Synchronizer 'global', without starting synchronisation",...
2026-11-11T00:06:32.843Z Successfully re-connected to synchronizers List(Synchronizer 'global')",...
```

Process spawn to first log line: run 1 00:02:57.506 (clue `Finished clue: Starting external Canton process ...-1`)
to 00:02:58.801 = 1.3 s; run 2 00:04:25.555 to 00:04:34.461 = 8.9 s. First log line to Ledger API listening: run 1
23.8 s, run 2 97.4 s. Spawn to Ledger API listening in run 2: 106.3 s of the 120 s budget.

## 6. The 45 s `generate-or-await-node-topology-tx` stage on restart: package metadata view rebuilt from 104 stored packages

```
zcat $C2 | grep -a 'Fetching package' | sed -E 's/\{"@timestamp":"([^"]+)".*/\1/' | awk 'NR==1{print "first",$0} {l=$0;n++} END{print "last",l,"count",n}'
zcat $C2 | grep -a 'refresh Package Metadata View has not completed' | FMT | cut -c1-200
```
```
first 2026-11-11T00:05:13.402Z
last 2026-11-11T00:05:53.722Z count 104
2026-11-11T00:05:23.391Z refresh Package Metadata View has not completed after 10558 milliseconds",[c.d.c.p.s.m.MutablePackageMetadataViewImpl:participant=extraStandaloneParticipant] DEBUG
```

This step only exists on a restart (the first start has an empty package store), and it ran on a host that was
slow overall (section 5). Why the host was slow (runner load) was not established.

## 7. The only tap after the Ledger API came up: submitted at 00:06:28, 30 s gRPC deadline, then 38 s HTTP timeout

The tap at 00:06:21 passed authorization; the wallet's server-side retry loop failed with
NOT_CONNECTED_TO_SYNCHRONIZER until 00:06:27, then submitted at 00:06:28 (the participant had just started its
synchronizer connection and was replaying):

```
zcat $T | grep -a -E 'T00:06:(2[1-9]|[3-5][0-9])' | grep -a 'aliceValidatorLocal' | grep -a "The operation 'Tap' failed with a retryable error" | sed -E 's/\{"@timestamp":"([^"]+)".*ErrorInfoDetail\(([A-Z_]+).*/\1 \2/' | cut -c1-80
zcat $T | grep -a 'tid:610ea3645bc6aea9fe86c70555c269a9' | FMT | cut -c1-230
```
```
2026-11-11T00:06:21.565Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:21.793Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:22.072Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:22.375Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:23.122Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:23.781Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:24.473Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:25.431Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:26.436Z NOT_CONNECTED_TO_SYNCHRONIZER
2026-11-11T00:06:27.262Z NOT_CONNECTED_TO_SYNCHRONIZER
{"@timestamp":"2026-11-11T00:06:58.100Z","message":"The operation 'Tap' failed w   (cut by the sed: the 00:06:58 failure is the DEADLINE_EXCEEDED below)

2026-11-11T00:06:28.074Z Request (tid:610ea3645bc6aea9fe86c70555c269a9) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:27501: sending request SubmitAndWaitForTransactionRequest(...
2026-11-11T00:06:58.089Z Request (tid:610ea3645bc6aea9fe86c70555c269a9) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:27501: failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 29.999775444s. Name resolution delay ...
```

The client gave up first:

```
zcat $T | grep -a -E 'T00:06:59' | grep -a -E 'resulted in a timeout|HTTP 503' | FMT | cut -c1-230
zcat $T | grep -a 'wallet/tap from (127.0.0.1:45214): Responding' | FMT | cut -c1-200
```
```
2026-11-11T00:06:59.164Z Suppressed WARN: Request to http://127.0.0.1:5503/api/validator/v0/wallet/tap (POST) resulted in a timeout after 38 seconds.",...
2026-11-11T00:06:59.183Z Suppressed ERROR: org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 503 Service Unavailable POST at '/api/validator/v0/wallet/tap' on 127.0.0.1:5503. Command failed, message: The server is taking too long to respond to the (POST) request at ...
2026-11-11T00:07:01.128Z HTTP POST /api/validator/v0/wallet/tap from (127.0.0.1:45214): Responding with status code: 409 Conflict, took 39978 ms",...
```

The attempt started before the deadline (00:06:21.1 < 00:06:25.6) and returned after it, so `eventuallySucceeds`
did not retry. Why the submission at 00:06:28 did not complete in 30 s was not established (the participant logged
`Caught up with sequencer` at 00:06:32.665; the host was slow throughout, section 5).

## 8. Duplicate check

```
grep -i -n 'SurviveCantonRestart\|survive Canton' ci-triage/known-families.md ci-triage/evidence/INDEX.tsv
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue WalletSurviveCantonRestart' --jq '.total_count'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "survive Canton restarts"' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:110])"'
```
```
(no output)
0
6612 closed 2025-12-05 FAILED_PRECONDITION/OUTDATED_TRAFFIC_COST: Submission was refused because traffic cost was outdated
6390 closed 2025-11-07 `canton-standalone-wallet-survives-canton-restarts` logs warnings about sequencer (oh no! /s)
5366 closed 2025-08-07 WalletSurviveCantonRestartIntegrationTest: Wallet should survive Canton restarts
6379 closed 2025-11-07 wall-clock-time: Validator app fails because of participant init race which leads to `UNIMPLEMENTED/Method not
6394 closed 2025-11-07 WalletSurviveCantonRestartIntegrationTest: participant races with validator app on synchronizer reconnection d
5417 closed 2025-08-18 aliceValidator can't connect to participant when setting up first canton in WalletSurviveCantonRestartIntegrat
1895 closed 2025-01-22 WalletSurviveCantonRestartIntegrationTest fails waiting for first participant
```

Per hit: all closed, all a year old or more. 6612 traffic cost refusal, 6390 sequencer WARNs in the standalone log,
6379 and 5417 and 1895 the first start / validator init, 6394 shared participant DB between two tests (comment by
martinflorian-da), 5366 circuit breaker during the tap loop (the comments discuss the breaker and SEQUENCER_SENDER_UNKNOWN). None is
a slow second start against the 2 min budget. Families: not B, L or the BFT topology timeouts (11255): the participant
connects to the synchronizer fine once up; not H (the test reported cleanly). No other hit in the last week.

## 9. Owner of the retry block

```
git blame -L 69,85 --date=short 5ccef7f -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletSurviveCantonRestartIntegrationTest.scala | cut -c1-80 | awk '{print $1,$2,$3,$4}' | sort | uniq -c
git show -s --format='%h %ad %an %s' --date=short 4ba9f0d2c98
```
```
     17 4ba9f0d2c98 (Julien Tinguely 2025-09-22
4ba9f0d2c9 2025-09-22 Julien Tinguely Add ignore for WalletSurviveCantonRestartIntegrationTest circuit breaker (#2345)
```

## Verdict

New, first hit. Test flake: the 2 min `eventuallySucceeds` budget for the first tap after the restart also covers JVM
start and participant startup, because `withCanton` does not wait for the node. On this run the second start took
106 s to the Ledger API (vs 25 s for the first start a minute earlier; every stage slower, plus 40 s of package
metadata rebuild that only happens on restart), and the one attempt after that rode the server-side retry loop into
the 30 s submission deadline and the 38 s HTTP timeout.

Fix (test, `WalletSurviveCantonRestartIntegrationTest.scala` second `withCanton` block): wait for the restarted participant
before the tap loop, e.g. `eventually(5.minutes)` until `aliceValidatorLocalBackend.participantClientWithAdminToken`
lists the global synchronizer as connected, then keep the 2 min tap budget; or simply raise the tap budget to 5 min.
No assignee: the block was last changed by #2345 (2025-09-22, an ignore rule), not a clear owner.

Not verified: why the runner was slow, and why the 00:06:28 submission did not complete within 30 s.
