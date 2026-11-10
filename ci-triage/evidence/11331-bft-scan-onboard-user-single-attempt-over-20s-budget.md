# 11331 - ConfigurationProvidedBftScanConnectionIntegrationTest: onboardUser gets one 38 s attempt in a 20 s budget (run 42790576154)

new (test budget). Post-merge main e59889cad0 (#8061), job `wall-clock-time (3)`, 20 of 21 tests passed. The test
`simple threshold normal case validator onboarding succeeds` wraps `aliceValidatorBackend.onboardUser(...)` in
`eventuallySucceeds()`, whose default budget is 20 s. The one call it made (10:00:08.333) was answered by
aliceValidator's 38 s server request timeout at 10:00:46.380; by then the 20 s budget was spent, so `eventually`
rethrew without a second attempt. The handler itself kept running and finished with 200 OK at 10:01:03.557 (55.2 s).
It was slow because alice's validator had just started right after the four-SV BFT onboarding (sv3 entered the
ordering topology at 09:59:42, blacklisted until 10:00:05): each of its two ledger transactions took three sequencing
hops of 2.1 to 4.2 s (16 s and 29 s), and the second one was additionally replayed after alice's
ReconcileSequencerConnectionsTrigger reconnected the participant at 10:00:48 to add sv4's sequencer. Every other
onboardUser call in the shard took at most 6.5 s, including the same call in the next test case of this suite (2.6 s).
No product error: the system completed the onboarding; the test's wait is shorter than one attempt can legitimately take.

- Run: https://github.com/canton-network/splice/actions/runs/42790576154, main e59889cad0 ("SV UI: Add support for new
  vote creation to update featured app config (#8061)"), job 129743155795 `ci / scala_test_wall_clock_time /
  wall-clock-time (3)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (`git show e59889cad0:nix/canton-sources.json`).
- Component: test (ConfigurationProvidedBftScanConnectionIntegrationTest).

Artifact: `logs-wall-clock-time-3` (id 13753563025), downloaded with
`gh run download 42790576154 --repo canton-network/splice -n logs-wall-clock-time-3 -D log/11331/logs-wall-clock-time-3`.

## 1. Failing assertion

```
$ gh api repos/canton-network/splice/actions/jobs/129743155795/logs > log/11331/job.log
$ sed 's/\x1b\[[0-9;]*m//g' log/11331/job.log | grep -a -A1 -E 'simple threshold normal case validator onboarding succeeds \*\*\* FAILED|Tests: succeeded' | cut -c30-200 | head -5
```
```
[info] - simple threshold normal case validator onboarding succeeds *** FAILED ***
[info]   Alice's validator should be able to onboard a user after establishing connections. (BaseTestExtensions.scala:37)

[info] Tests: succeeded 20, failed 1, canceled 0, ignored 0, pending 0
[info] *** 1 TEST FAILED ***
```
Stack head (same command with `-A20`): `BaseTestExtensions.eventuallySucceeds$1(BaseTestExtensions.scala:37)`,
`BaseTest$.go$1(BaseTest.scala:614)`, `BaseTest$.eventually(BaseTest.scala:635)`,
`ConfigurationProvidedBftScanConnectionIntegrationTest.$anonfun$new$13(ConfigurationProvidedBftScanConnectionIntegrationTest.scala:129)`.

## 2. The wait and its budget (source at the run's sha)

The test (lines 127-131, unchanged since e932fdbd55, 2025-10-29):
```
$ git show e59889cad0:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ConfigurationProvidedBftScanConnectionIntegrationTest.scala | sed -n 127,131p
```
```
    withClue("Alice's validator should be able to onboard a user after establishing connections.") {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }
```
`eventuallySucceeds` turns any non-test exception into `fail(e)` and passes its budget to `eventually`:
```
$ git show e59889cad0:canton-fork/testing/src/main/scala/org/lfdecentralizedtrust/splice/BaseTestExtensions.scala | sed -n 27,39p
```
```
  def eventuallySucceeds[T](
      timeUntilSuccess: FiniteDuration = BaseTest.DefaultEventuallyTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
      suppressErrors: Boolean = true,
  )(testCode: => T): T = {
    eventually(timeUntilSuccess, maxPollInterval) {
      try {
        if (suppressErrors) loggerFactory.suppressErrors(testCode) else testCode
      } catch {
        case e: TestFailedException => throw e
        case NonFatal(e) => fail(e)
      }
    }
```
The default is 20 s, and `eventually` retries only while time is left after a failed attempt (BaseTest is compiled
from the tree at this sha; lines 614 and 635 match the stack):
```
$ git show e59889cad0:canton/community/testing/src/main/scala/com/digitalasset/canton/BaseTest.scala | sed -n '537p;614p;622,626p;635p'
```
```
  val DefaultEventuallyTimeUntilSuccess: FiniteDuration = 20.seconds
      val result = Try(testCode)
          val retry = !retryOnTestFailuresOnly || ex.isInstanceOf[TestFailedException]
          if (retry && hasTimeLeft) {
            val nextSleepMs = sleep(sleepMs)
            go(nextSleepMs)
          } else {
    go(sleepMs = 1L)
```
The app server answers any request still running after 38 s with a timeout:
```
$ git show e59889cad0:apps/app/src/main/resources/application.conf | sed -n 1,2p
```
```
# This is deliberately lower than ConsoleCommandTimeout.defaultRequestTimeout
pekko.http.server.request-timeout = 38 seconds
```
So a single slow attempt (up to 38 s) always exhausts the 20 s budget, and there is no retry.

## 3. One attempt, answered by the 38 s timeout; the handler finished 17 s later

```
$ zcat log/11331/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a -E "Starting .ConfigurationProvidedBftScanConnectionIntegrationTest/simple|Running on aliceValidator command OnboardUser|admin/users \(POST\) resulted in a timeout|Checking participant Ledger API Store integrity after tests|HTTP POST /api/validator/v0/admin/users from \(127.0.0.1:45618\): (received request|Responding with status)|Test failed: .ConfigurationProvidedBftScanConnectionIntegrationTest/simple" | jq -rR 'fromjson? | "\(."@timestamp"[11:23]) \(.level) \(.logger_name|sub(":.*";"")) | \(.message[0:200]|gsub("\n";" "))"'
```
(output lines from 09:58:11 to 10:03:46; the integrity-check lines of other test cases trimmed)
```
09:58:11.674 INFO c.d.c.LogReporter | Starting 'ConfigurationProvidedBftScanConnectionIntegrationTest/simple threshold normal case validator onboarding succeeds'...
10:00:08.333 DEBUG o.l.s.c.ConsoleHttpCommandRunner | Running on aliceValidator command OnboardUser(alice_wallet_user-ea089c8e,None,None) against NetworkAppClientConfig(http://127.0.0.1:5503,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 
10:00:08.357 DEBUG o.l.s.a.a.HttpRequestLogger | HTTP POST /api/validator/v0/admin/users from (127.0.0.1:45618): received request.
10:00:18.355 DEBUG o.l.s.c.ConsoleHttpCommandRunner | Task Running on aliceValidator command OnboardUser(alice_wallet_user-ea089c8e,None,None) against NetworkAppClientConfig(http://127.0.0.1:5503,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,fals
10:00:28.356 DEBUG o.l.s.c.ConsoleHttpCommandRunner | Task Running on aliceValidator command OnboardUser(alice_wallet_user-ea089c8e,None,None) against NetworkAppClientConfig(http://127.0.0.1:5503,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,fals
10:00:38.357 DEBUG o.l.s.c.ConsoleHttpCommandRunner | Task Running on aliceValidator command OnboardUser(alice_wallet_user-ea089c8e,None,None) against NetworkAppClientConfig(http://127.0.0.1:5503,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,fals
10:00:46.380 WARN o.l.s.a.h.HttpErrorHandler | Request to http://127.0.0.1:5503/api/validator/v0/admin/users (POST) resulted in a timeout after 38 seconds.
10:00:46.456 INFO c.d.c.i.LedgerApiStoreIntegrityChecker | Checking participant Ledger API Store integrity after tests...
10:01:03.557 DEBUG o.l.s.a.a.HttpRequestLogger | HTTP POST /api/validator/v0/admin/users from (127.0.0.1:45618): Responding with status code: 200 OK
10:02:23.780 ERROR c.d.c.LogReporter | Test failed: 'ConfigurationProvidedBftScanConnectionIntegrationTest/simple threshold normal case validator onboarding succeeds', message: Alice's validator should be able to onboard a user after estab
10:03:46.425 DEBUG o.l.s.c.ConsoleHttpCommandRunner | Running on aliceValidator command OnboardUser(alice_wallet_user-5af34467,None,None) against NetworkAppClientConfig(http://127.0.0.1:5503,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 
```
There is exactly one OnboardUser for `alice_wallet_user-ea089c8e`; the test went to teardown (integrity checks)
76 ms after the timeout. The timeout WARN is logged by aliceValidator's server (logger
`o.l.s.a.h.HttpErrorHandler:ConfigurationProvidedBftScanConnectionIntegrationTest/config=ea089c8e/validator=aliceValidator`,
`HttpErrorHandler.timeoutHandler`), so the client got a timeout response while the handler continued.

## 4. Where the 55 s went on the server (trace a1a55e01)

```
$ zcat log/11331/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a '"trace-id":"a1a55e0106a79dce40f51e49cd20fa40"' | grep -a -E 'Ensuring that Party|Success: Party .* is observed on ledger API|Installing wallet|CommandService/SubmitAndWait to 127.0.0.1:5501: (sending request|received a message)' | jq -rR 'fromjson? | "\(."@timestamp"[11:23]) \(.logger_name|sub(":.*";"")) | \(.message[0:150]|gsub("\n";" ")|gsub(" +";" "))"'
```
```
10:00:08.579 o.l.s.e.ParticipantAdminConnection | Ensuring that Party alice__wallet__user-ea089c8e::1220cdc1724e... is allocated on PAR::aliceValidator::1220cdc1724e...
10:00:15.378 o.l.s.e.SpliceLedgerConnection | Success: Party alice__wallet__user-ea089c8e::1220cdc1724e... is observed on ledger API
10:00:16.156 o.l.s.v.a.h.HttpValidatorAdminHandler | Installing wallet for endUserName:alice_wallet_user-ea089c8e, endUserParty=alice__wallet__user-ea089c8e::1220cdc1724e..., validatorServiceParty=alice-
10:00:16.205 o.l.s.a.a.c.ApiClientRequestLogger | Request (tid:b7914f5bde0e0e02cfd52073cb8d1eb4) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitReq
10:00:32.357 o.l.s.a.a.c.ApiClientRequestLogger | Request (tid:b7914f5bde0e0e02cfd52073cb8d1eb4) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: received a message SubmitAndWait
10:00:33.606 o.l.s.a.a.c.ApiClientRequestLogger | Request (tid:cd258054a0398e6f2bc4ae657c06f54b) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: sending request SubmitAndWaitReq
10:01:02.956 o.l.s.a.a.c.ApiClientRequestLogger | Request (tid:cd258054a0398e6f2bc4ae657c06f54b) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5501: received a message SubmitAndWait
```
Party allocation 7 s, `installWalletForUser` (WalletAppInstall create) 16.2 s, `createValidatorRight` 29.4 s. The
command ids are `org.lfdecentralizedtrust.splice.validator.installWalletForUser_12775553...` and
`org.lfdecentralizedtrust.splice.validator.createValidatorRight_2e097326...` (from the same request lines).

## 5. Each transaction needed three sequencing hops of 2 to 4 s

Participant and sequencer side of both submissions (canton log carries the gRPC tids as trace ids):
```
$ zcat log/11331/logs-wall-clock-time-3/canton.clog.gz | grep -a -E '"trace-id":"(b7914f5bde0e0e02cfd52073cb8d1eb4|cd258054a0398e6f2bc4ae657c06f54b)"' | grep -a -E "Phase 1 completed|sends request with id '(4d5c41da|5cb1f563|39927b79|91701585|705d2d7e|dbabee54)|Phase 3: Validating|Phase 4: Sending|Phase 7: Storing" | jq -rR 'fromjson? | "\(."@timestamp"[11:23]) \(."trace-id"[0:8]) \(.logger_name|sub("/psid.*";"")|sub(".*\\.";"")) | \(.message[0:150]|gsub("\n";" ")|gsub(" +";" "))"'
```
```
10:00:17.205 b7914f5b TransactionProcessor:participant=aliceParticipant | Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters Seq(alice-validatorea089c8e-1::1220cdc1724e..., alice__wallet__user-ea08
10:00:17.256 b7914f5b GrpcSequencerService:sequencer=globalSequencerSv2 | 'PAR::aliceValidator::1220cdc1724e...' sends request with id '4d5c41da-a21e-4f55-8d06-9728c2131345' of size 3082 bytes with 3 envelopes to MGR=1, PAR=
10:00:22.635 b7914f5b TransactionProcessor:participant=aliceParticipant | Phase 3: Validating Transaction request=2026-11-10T10:00:18.860277Z with 1 envelope(s)
10:00:22.931 b7914f5b TransactionProcessor:participant=aliceParticipant | Phase 4: Sending for request=2026-11-10T10:00:18.860277Z with msgId=5cb1f563-7eb2-4fda-a6ac-f7014007d423 approved=1, rejected=0, abstained=0
10:00:22.983 b7914f5b GrpcSequencerService:sequencer=globalSequencerSv1 | 'PAR::aliceValidator::1220cdc1724e...' sends request with id '5cb1f563-7eb2-4fda-a6ac-f7014007d423' of size 784 bytes with 1 envelopes to MGR=1.
10:00:26.513 b7914f5b GrpcSequencerService:sequencer=globalSequencerSv1 | 'MED::sv1::1220be4ef093...' sends request with id '39927b79-f6ad-4e93-903d-e4eca9335d5c' of size 722 bytes with 1 envelopes to PAR=1.
10:00:31.029 b7914f5b ParallelIndexerSubscription:participant=aliceParticipant | Phase 7: Storing at offset=1662 SequencedTransactionAccepted( recordTime = 2026-11-10T10:00:18.860277Z, updateId = 122023b8637f2646a5102734316c2d5
10:00:34.932 cd258054 TransactionProcessor:participant=aliceParticipant | Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters Seq(alice-validatorea089c8e-1::1220cdc1724e..., alice__wallet__user-ea08
10:00:35.132 cd258054 GrpcSequencerService:sequencer=globalSequencerSv2 | 'PAR::aliceValidator::1220cdc1724e...' sends request with id '91701585-8a2f-4755-b6cf-2de27fbb41ec' of size 3045 bytes with 3 envelopes to MGR=1, PAR=
10:00:39.382 cd258054 TransactionProcessor:participant=aliceParticipant | Phase 3: Validating Transaction request=2026-11-10T10:00:36.632650Z with 1 envelope(s)
10:00:39.883 cd258054 TransactionProcessor:participant=aliceParticipant | Phase 4: Sending for request=2026-11-10T10:00:36.632650Z with msgId=705d2d7e-5840-48ae-970b-4566f47ffd98 approved=1, rejected=0, abstained=0
10:00:40.183 cd258054 GrpcSequencerService:sequencer=globalSequencerSv1 | 'PAR::aliceValidator::1220cdc1724e...' sends request with id '705d2d7e-5840-48ae-970b-4566f47ffd98' of size 776 bytes with 1 envelopes to MGR=1.
10:00:44.278 cd258054 GrpcSequencerService:sequencer=globalSequencerSv4 | 'MED::sv4::1220b82353c0...' sends request with id 'dbabee54-4814-46e1-9474-232b0b38be32' of size 722 bytes with 1 envelopes to PAR=1.
10:00:57.650 cd258054 TransactionProcessor:participant=aliceParticipant | Phase 3: Validating Transaction request=2026-11-10T10:00:36.632650Z with 1 envelope(s)
10:01:00.316 cd258054 TransactionProcessor:participant=aliceParticipant | Phase 4: Sending for request=2026-11-10T10:00:36.632650Z with msgId=7c5dab96-addb-4616-beeb-8af09298a6d2 approved=1, rejected=0, abstained=0
10:01:01.205 cd258054 ParallelIndexerSubscription:participant=aliceParticipant | Phase 7: Storing at offset=1675 SequencedTransactionAccepted( recordTime = 2026-11-10T10:00:36.632650Z, updateId = 1220d311abee0e01b653c7be0d7cdc3
```

When each of those sends came out of BFT ordering on globalSequencerSv1:
```
$ zcat log/11331/logs-wall-clock-time-3/canton.clog.gz | grep -a 'OutputModule:sequencer=globalSequencerSv1/' | grep -a -E 'BFT ordering request at index [0-9]+ in output block .* message ID (4d5c41da|5cb1f563|39927b79|91701585|705d2d7e|dbabee54)' | jq -rR 'fromjson? | "\(."@timestamp"[11:23]) | \(.message[0:150])"'
```
```
10:00:20.614 | BFT ordering request at index 1 in output block (epochNumber=219, blockNumber=5928) with message ID 4d5c41da-a21e-4f55-8d06-9728c2131345 may change or
10:00:25.064 | BFT ordering request at index 0 in output block (epochNumber=220, blockNumber=5938) with message ID 5cb1f563-7eb2-4fda-a6ac-f7014007d423 may change or
10:00:29.281 | BFT ordering request at index 0 in output block (epochNumber=220, blockNumber=5950) with message ID 39927b79-f6ad-4e93-903d-e4eca9335d5c may change or
10:00:38.381 | BFT ordering request at index 0 in output block (epochNumber=220, blockNumber=5979) with message ID 91701585-8a2f-4755-b6cf-2de27fbb41ec may change or
10:00:42.369 | BFT ordering request at index 0 in output block (epochNumber=220, blockNumber=5994) with message ID 705d2d7e-5840-48ae-970b-4566f47ffd98 may change or
10:00:48.488 | BFT ordering request at index 0 in output block (epochNumber=220, blockNumber=6009) with message ID dbabee54-4814-46e1-9474-232b0b38be32 may change or
```
Send to ordered output: request 3.4 s, response 2.1 s, verdict 2.8 s (first transaction); request 3.2 s, response
2.2 s, verdict 4.2 s (second). Each event then reached the participant 1.5 to 2 s later (Phase 3 at 22.635 for an
output at 20.614). No hop was lost: the first transaction completed in 16 s with three hops.

## 6. The second transaction was replayed after a participant reconnect

alice's validator started with sv1 and sv2 only, and its ReconcileSequencerConnectionsTrigger added sv4 at 10:00:48 and
sv3 at 10:01:09:
```
$ zcat log/11331/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a 'validator=aliceValidator' | grep -a -E "Got 'non-empty sequencer connections from scan'|modifying sequencers connections to" | jq -rR 'fromjson? | "\(."@timestamp"[11:23]) " + ([.message|scan("Sequencer .SEQ::sv[0-9]")]|unique|join(","))'
```
```
09:59:59.926 Sequencer 'SEQ::sv1,Sequencer 'SEQ::sv2
10:00:48.483 Sequencer 'SEQ::sv1,Sequencer 'SEQ::sv2,Sequencer 'SEQ::sv4
10:01:09.006 Sequencer 'SEQ::sv1,Sequencer 'SEQ::sv2,Sequencer 'SEQ::sv3,Sequencer 'SEQ::sv4
```
The ModifySynchronizer call at 10:00:48 made aliceParticipant process the in-flight request (sequencer counter 931,
`createValidatorRight`) a second time, 18 s after the first time, and send a second confirmation response at 10:01:00.316:
```
$ zcat log/11331/logs-wall-clock-time-3/canton.clog.gz | grep -a 'participant=aliceParticipant' | grep -a -E 'SynchronizerConnectivityService/ModifySynchronizer by .*: received a message|Processing event at sc=931, ts=2026-11-10T10:00:36.632650Z' | jq -rR 'fromjson? | "\(."@timestamp"[11:23]) \(.logger_name|sub("/.*";"")|sub(".*\\.";"")) | \(.message[0:140]|gsub("\n";" ")|gsub(" +";" "))"'
```
```
09:45:53.589 ApiRequestLogger:participant=aliceParticipant | Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:33702: received 
09:59:59.937 ApiRequestLogger:participant=aliceParticipant | Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:59170: received 
10:00:39.279 ParallelMessageDispatcher:participant=aliceParticipant | Processing event at sc=931, ts=2026-11-10T10:00:36.632650Z, messageId=91701585-8a2f-4755-b6cf-2de27fbb41ec, with contents=Seq( RootHashMes
10:00:48.766 ApiRequestLogger:participant=aliceParticipant | Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:59172: received 
10:00:57.624 ParallelMessageDispatcher:participant=aliceParticipant | Processing event at sc=931, ts=2026-11-10T10:00:36.632650Z, messageId=91701585-8a2f-4755-b6cf-2de27fbb41ec, with contents=Seq( RootHashMes
10:01:09.080 ApiRequestLogger:participant=aliceParticipant | Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:59172: received 
10:03:17.273 ApiRequestLogger:participant=aliceParticipant | Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:35468: received 
```
The verdict for that request had been ordered at 10:00:48.488 (section 5); the replay moved the completion to
10:01:02.956. This only lengthened the handler after the 38 s timeout had already fired (10:00:46.380); it did not
cause the test failure, which was decided by section 3.

## 7. Context: alice started right after the four-SV BFT onboarding

Epochs on globalSequencerSv1 (sv4 joined at epoch 213, sv3 at 215, sv3 blacklisted 216-218):
```
$ zcat log/11331/logs-wall-clock-time-3/canton.clog.gz | grep -a 'IssConsensusModule:sequencer=globalSequencerSv1/' | grep -a -E 'New epoch 2(1[2-9]|2[0-4]) has started' | jq -rR 'fromjson? | "\(."@timestamp"[11:23]) \(.message|gsub("\n";" ")|gsub("::1220[0-9a-f]+";"")|capture("(?<e>New epoch [0-9]+) has started with leaders = (?<l>List\\([^)]*\\))and blacklisted nodes = (?<b>List\\([^)]*\\))")|"\(.e) leaders=\(.l) blacklisted=\(.b)")"'
```
```
09:59:24.597 New epoch 212 leaders=List(SEQ::sv1, SEQ::sv2) blacklisted=List()
09:59:29.140 New epoch 213 leaders=List(SEQ::sv1, SEQ::sv2, SEQ::sv4) blacklisted=List()
09:59:38.164 New epoch 214 leaders=List(SEQ::sv2, SEQ::sv4, SEQ::sv1) blacklisted=List()
09:59:42.677 New epoch 215 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List()
09:59:48.139 New epoch 216 leaders=List(SEQ::sv1, SEQ::sv2, SEQ::sv4) blacklisted=List(SEQ::sv3)
09:59:53.912 New epoch 217 leaders=List(SEQ::sv2, SEQ::sv4, SEQ::sv1) blacklisted=List(SEQ::sv3)
09:59:59.536 New epoch 218 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv2) blacklisted=List(SEQ::sv3)
10:00:05.525 New epoch 219 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List()
10:00:22.080 New epoch 220 leaders=List(SEQ::sv1, SEQ::sv2, SEQ::sv3, SEQ::sv4) blacklisted=List()
10:00:49.379 New epoch 221 leaders=List(SEQ::sv2, SEQ::sv3, SEQ::sv4, SEQ::sv1) blacklisted=List()
10:01:25.855 New epoch 222 leaders=List(SEQ::sv3, SEQ::sv4, SEQ::sv1, SEQ::sv2) blacklisted=List()
10:02:01.282 New epoch 223 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List()
10:02:23.681 New epoch 224 leaders=List(SEQ::sv1, SEQ::sv2, SEQ::sv3, SEQ::sv4) blacklisted=List()
```
No view change was logged on globalSequencerSv1 between 10:00:00 and 10:02:30:
```
$ zcat log/11331/logs-wall-clock-time-3/canton.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="10:00:00" && t<="10:02:30"' | grep -a -c -E 'moving from view number|View change completed'
0
``` Why the hops took 2 to 4 s is not established; this packet only
shows that they did, and that every hop arrived.

## 8. Baseline: every other onboardUser in this shard took at most 6.5 s

```
$ zcat log/11331/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a 'POST /api/validator/v0/admin/users' | grep -a -E 'received request|Responding with status code' | jq -rR 'fromjson? | "\(.["@timestamp"]) \(.["trace-id"]) \(.logger_name|capture("(?<s>[A-Za-z]+IntegrationTest)")?.s // "-") \(.message|capture("(?<m>received request|Responding with status code: [0-9]+)").m)"' | awk '{if ($4=="received") {st[$2]=$1; su[$2]=$3} else if ($2 in st) {split(st[$2],a,"T"); split($1,b,"T"); split(substr(a[2],1,12),x,":"); split(substr(b[2],1,12),y,":"); d=(y[1]*3600+y[2]*60+y[3])-(x[1]*3600+x[2]*60+x[3]); printf "%s %s %.1fs\n", substr(st[$2],12,12), su[$2], d}}' | sort -k3 -rn | head -11
```
```
10:00:08.357 ConfigurationProvidedBftScanConnectionIntegrationTest 55.2s
10:13:39.806 ProvisionalFeaturedAppLockConversionIntegrationTest 6.5s
10:13:35.570 ProvisionalFeaturedAppLockConversionIntegrationTest 4.2s
09:55:38.081 SequencerPruningIntegrationTest 3.7s
10:12:15.939 ScanPartyMonitorScriptIntegrationTest 3.2s
10:12:19.143 ScanPartyMonitorScriptIntegrationTest 3.0s
10:06:21.450 AutomationControlIntegrationTest 2.9s
10:06:24.313 AutomationControlIntegrationTest 2.8s
10:13:32.922 ProvisionalFeaturedAppLockConversionIntegrationTest 2.6s
10:12:22.184 ScanPartyMonitorScriptIntegrationTest 2.6s
10:03:46.427 ConfigurationProvidedBftScanConnectionIntegrationTest 2.6s
```
(11 onboard requests in the shard in total; the last line is the next test case of the same suite.)

## 9. A retry is safe

The wallet-install step looks up an existing install first and deduplicates the command, so a second onboardUser
attempt after a timeout does not conflict with the first:
```
$ git show e59889cad0:apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/util/ValidatorUtil.scala | sed -n '62,63p;77,85p;88,90p'
```
```
        store.lookupWalletInstallByNameWithOffset(endUserName).flatMap {
          case QueryResult(offset, None) =>
              .withDedup(
                commandId = SpliceLedgerConnection
                  .CommandId(
                    "org.lfdecentralizedtrust.splice.validator.installWalletForUser",
                    Seq(validatorServiceParty),
                    BaseLedgerConnection.sanitizeUserIdToPartyString(endUserName),
                  ),
                deduplicationOffset = offset,
              )
          case QueryResult(_, Some(_)) =>
            logger.info(s"WalletAppInstall for $endUserName already exists, skipping")
            Future.successful(())
```

## 10. Duplicate check

```
$ grep -c 'ConfigurationProvidedBftScan' ci-triage/evidence/INDEX.tsv ci-triage/known-families.md
ci-triage/evidence/INDEX.tsv:0
ci-triage/known-families.md:0
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "ConfigurationProvidedBftScanConnectionIntegrationTest"' --jq '.total_count, (.items[] | "#\(.number) \(.state)/\(.state_reason) \(.created_at[0:10]) \(.title[0:100])")'
1
#8982 closed/not_planned 2026-06-29 Creating environment failed at step Creating fixture: Cause: java.io.UncheckedIOException: Could not
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "onboard a user after establishing connections"' --jq '.total_count'
0
```
#8982 is a different failure (fixture creation, Prometheus HTTP server), not this one. First hit of this failure in
the tracker.

The same default-budget pattern around onboardUser exists in three more places:
```
$ git grep -n -A1 'eventuallySucceeds() {' e59889cad0 -- apps/app/src/test | grep 'onboardUser' | cut -c1-140
```
```
e59889cad0:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BftScanConnectionIntegrationTest.scala-244-        aliceValidatorBackend.on
e59889cad0:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ConfigurationProvidedBftScanConnectionIntegrationTest.scala-129-        ali
e59889cad0:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ConfigurationProvidedBftScanConnectionIntegrationTest.scala-182-        ali
e59889cad0:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorSequencerConnectionIntegrationTest.scala-137-          aliceValida
```

Not family B or 11255: every sequencing hop of both transactions arrived and all mediators finalized with Approve
(no rejected send, no lost verdict); the failure is the test's budget, not a stall. Related to the 2026-11-09
cross-cutting note (fixed waits that lost their margin under BFT: 11299, 11301, 11303).

## Verdict

New, test budget; first hit. Flake: the onboard call took 55 s because alice's validator started 23 s after the
four-SV BFT ordering topology became active (2 to 4 s per sequencing hop, plus a replay after the
sequencer-connection change at 10:00:48), but the test allowed 20 s and the server's 38 s request timeout ended the
only attempt. Fix (test): give the onboardUser waits a budget above the 38 s server timeout so a timed-out attempt is
retried, e.g. `eventuallySucceeds(timeUntilSuccess = 2.minutes)` at
`ConfigurationProvidedBftScanConnectionIntegrationTest.scala:128` and `:181` (same pattern in
`BftScanConnectionIntegrationTest.scala:243` and `ValidatorSequencerConnectionIntegrationTest.scala:136`). No assignee:
the test lines are unchanged since 2025-10-29 (e932fdbd55), so no recent change introduced it.

Not verified: why the sequencing hops took 2 to 4 s in this window (no view change on sv1; the blacklisting of sv3 had
ended at 10:00:05); whether the test passed with a shorter onboard time on earlier runs since the BFT switch (only
this shard's other onboard calls are compared).
