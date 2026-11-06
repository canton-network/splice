# 11238 - SvInitializationIntegrationTest "connect to all domains during initialization": list_connected hits sv4Validator's sequencer-connection reconnect (run 42205124002)

New (test race, family I). main 04c342f6fd, job `wall-clock-time (5)`, canton 3.7.2, 16 tests passed, 1 failed.
The test restarts the sv4 SV app after disconnecting sv4's participant and then reads
`sv4Backend.participantClient.synchronizers.list_connected()` once, without `eventually`. The SV app did reconnect
the participant (11:01:03.842), but at 11:01:36.957 sv4Validator's ReconcileSequencerConnectionsTrigger added sv2's
sequencer (scan `availableAfter` 11:01:36.484607, 60 s default sequencer-availability-delay) and bounced the
connection with disconnect + reconnect. The test's single `list_connected()` at 11:01:37.009-.010 fell into the
disconnect window (participant disconnected 11:01:36.967, connected again 11:01:37.023) and got `List()`.
All links are in the logs. Fix in the test: wrap the check in `eventually`.

- Run: https://github.com/canton-network/splice/actions/runs/42205124002, main 04c342f6fd ("Fix race in test for
  traffic purchase via TS transfer (#8347)"), job 127850216470 attempt 1 `ci / scala_test_wall_clock_time / wall-clock-time (5)`.
- Runtime canton: 3.7.2 (nix/canton-sources.json at the sha).
- Component: test (`apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvInitializationIntegrationTest.scala`).

## 0. Issue and job resolution

Issue body, verbatim:

```
gh api /repos/DACH-NY/cn-test-failures/issues/11238 | jq -r '"#\(.number) [\(.state)] \(.title) \(.created_at)\n\(.body)"'
```
```
#11238 [open] GHA Run 42205124002 : job wall-clock-time (5) in scala_test Failed :fire: 2026-11-06T11:21:38Z

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42205124002).
Job: wall-clock-time (5) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [04c342f](https://github.com/canton-network/splice/commit/04c342f6fd0393eb0eb5c01ea88eb92119c0673e) Fix race in test for traffic purchase via TS transfer (#8347)
Actor: meiersi-da
```
No comments on the issue (comments call returned nothing).

Job id (recipe 1, exact-name match):
```
RUN=42205124002; export NAME='wall-clock-time (5)'; gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name) \(.started_at) \(.completed_at)"'
gh api repos/canton-network/splice/actions/runs/$RUN --jq '"\(.name) \(.head_branch) \(.head_sha) \(.run_attempt) \(.created_at) \(.conclusion)"'
```
```
127850216470 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (5) 2026-11-06T10:47:12Z 2026-11-06T11:21:47Z
CI post-merge to main or release-line branches main 04c342f6fd0393eb0eb5c01ea88eb92119c0673e 1 2026-11-06T10:45:43Z failure
```

Canton pin:
```
git show 04c342f6fd0393eb0eb5c01ea88eb92119c0673e:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.2",
```

## 1. Failing assertion

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/127850216470/logs > log/11238/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11238/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id 5e3bfa50-d9d0-11f1-aa16-e5e98e941999): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - connect to all domains during initialization *** FAILED ***
[info] Run completed in 25 minutes, 58 seconds.
[info] Tests: succeeded 16, failed 1, canceled 0, ignored 0, pending 0
```

Clue and stack head (first occurrence):
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11238/job.log | grep -a -A10 'FAILED \*\*\*' | grep -v 'still running' | head -11 | cut -c1-260
```
```
2026-11-06T11:02:00.0538320Z [info] - connect to all domains during initialization *** FAILED ***
2026-11-06T11:02:00.0539770Z [info]   The partial function passed as the second parameter to inside was not defined at the value passed as the first parameter to inside, which was: List() (SvInitializationIntegrationTest.scala:82)
2026-11-06T11:02:00.0540879Z [info]   org.scalatest.exceptions.TestFailedException:
2026-11-06T11:02:00.0541545Z [info]   at org.scalatest.Inside$.insideWithPos(Inside.scala:181)
2026-11-06T11:02:00.0542364Z [info]   at org.scalatest.Inside.inside(Inside.scala:105)
2026-11-06T11:02:00.0543201Z [info]   at org.scalatest.Inside.inside$(Inside.scala:104)
2026-11-06T11:02:00.0544565Z [info]   at org.lfdecentralizedtrust.splice.integration.tests.SvInitializationIntegrationTest.inside(SvInitializationIntegrationTest.scala:24)
2026-11-06T11:02:00.0546091Z [info]   at org.lfdecentralizedtrust.splice.integration.tests.SvInitializationIntegrationTest.$anonfun$new$12(SvInitializationIntegrationTest.scala:82)
2026-11-06T11:02:00.0547404Z [info]   at scala.util.Try$.apply(Try.scala:217)
2026-11-06T11:02:00.0548203Z [info]   at com.digitalasset.canton.BaseTest.clue(BaseTest.scala:404)
2026-11-06T11:02:00.0549087Z [info]   at com.digitalasset.canton.BaseTest.clue$(BaseTest.scala:402)
```

Suites in the shard:
```
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/11238/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
BootstrapPackageConfigDarUploadWithUnsupportedVersionsIntegrationTest
DistributedDomainIntegrationTest
DowngradeSvPackagesIntegrationTest
ExpiryWithIgnoredAmuletVersionIntegrationTest
ScanEventHistoryIntegrationTest
SvDevNetReonboardingIntegrationTest
SvInitializationIntegrationTest
TokenStandardFetchFallbackIntegrationTest
```

## 2. The test code at the sha

```
git show 04c342f6fd0393eb0eb5c01ea88eb92119c0673e:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvInitializationIntegrationTest.scala | cat -n | sed -n 65,87p
```
```
    65	  "connect to all domains during initialization" in { implicit env =>
    66	    initDso()
    67	    sv4Backend.stop()
    68
    69	    val decentralizedSynchronizerId =
    70	      inside(sv4Backend.participantClient.synchronizers.list_connected()) { case Seq(domain) =>
    71	        domain.synchronizerId
    72	      }
    73
    74	    clue("simulate the domain was left disconnected when error occur during party migration.") {
    75	      sv4Backend.participantClient.synchronizers.disconnect_all()
    76	      sv4Backend.participantClient.synchronizers
    77	        .list_connected() shouldBe empty withClue "synchronizers"
    78	    }
    79
    80	    clue("sv will connect to all domains during initialization.") {
    81	      sv4Backend.startSync()
    82	      inside(sv4Backend.participantClient.synchronizers.list_connected()) {
    83	        case Seq(listConnectedDomainsResult) =>
    84	          listConnectedDomainsResult.synchronizerId shouldBe decentralizedSynchronizerId
    85	      }
    86	    }
    87	  }
```
Line 82 is a single read, no `eventually`. Only sv4's SV app is stopped; sv4Validator keeps running against the
same participant (sv4Participant, admin port 5402).

## 3. Suite timeline: the SV app reconnected the participant at 11:01:03.842

```
export T=log/11238/logs-wall-clock-time-5/canton_network_test.clog.gz C=log/11238/logs-wall-clock-time-5/canton.clog.gz
F() { sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/SvInitializationIntegrationTest\/config=83c95814/T/g'; }
zcat $T | grep -a -E 'Running clue: (simulate|sv will)|Finished clue: simulate|Failed clue|Starting node sv4"|Reconnecting all domains|Waiting for domain Synchronizer .global. to be connected|sv4 app initialization: Initialize app finished|Test failed: .SvInit' | grep -a '2026-11-06T11:01' | F | cut -c1-200
```
```
2026-11-06T11:01:03.158Z Running clue: simulate the domain was left disconnected when error occur during party migration.",[o.l.s.i.t.SvInitializationIntegrationTest:SvInitializationIntegrationTest] D
2026-11-06T11:01:03.262Z Finished clue: simulate the domain was left disconnected when error occur during party migration.",[o.l.s.i.t.SvInitializationIntegrationTest:SvInitializationIntegrationTest] 
2026-11-06T11:01:03.262Z Running clue: sv will connect to all domains during initialization.",[o.l.s.i.t.SvInitializationIntegrationTest:SvInitializationIntegrationTest] DEBUG
2026-11-06T11:01:03.262Z Starting node sv4",[o.l.s.e.SvApps:T] INFO
2026-11-06T11:01:03.763Z Reconnecting all domains.",[o.l.s.s.o.j.JoiningNodeInitializer:T/SV=sv4] INFO
2026-11-06T11:01:03.843Z Waiting for domain Synchronizer 'global' to be connected",[o.l.s.s.InMemorySynchronizerStore:T/SV=sv4/store=svParty] INFO
2026-11-06T11:01:09.450Z Waiting for domain Synchronizer 'global' to be connected has not completed after 5605 milliseconds",[o.l.s.s.InMemorySynchronizerStore:T/SV=sv4/store=svParty] WARN
2026-11-06T11:01:33.423Z Waiting for domain Synchronizer 'global' to be connected succeed successfully but slow after 29578 milliseconds",[o.l.s.s.InMemorySynchronizerStore:T/SV=sv4/store=svParty] INF
2026-11-06T11:01:33.840Z sv4 app initialization: Initialize app finished after PT30.153505719S",[o.l.s.s.SvApp:T/SV=sv4] INFO
2026-11-06T11:01:37.012Z Failed clue: sv will connect to all domains during initialization.",[o.l.s.i.t.SvInitializationIntegrationTest:SvInitializationIntegrationTest] ERROR
2026-11-06T11:01:59.031Z Test failed: 'SvInitializationIntegrationTest/connect to all domains during initialization', message: The partial function passed as the second parameter to inside was not def
```

Participant side (canton log): the SV app's reconnect succeeded at 11:01:03.842.
```
zcat $C | grep -a 'participant=sv4Participant' | grep -a -E 'Disconnected from Synchronizer|Reconnecting to synchronizers|Successfully re-connected' | grep -a '2026-11-06T11:01:0' | F | cut -c1-160
```
```
2026-11-06T11:01:03.252Z Disconnected from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Participant] INFO
2026-11-06T11:01:03.764Z Reconnecting to synchronizers List(global). Already connected: Set()",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Particip
2026-11-06T11:01:03.842Z Successfully re-connected to synchronizers List(Synchronizer 'global')",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Participant] INFO
```

## 4. Why startSync returned only at about 11:01:37: the SV app's domain store polls every 30 s

The SV app's DomainIngestionService polled connected synchronizers at 11:01:03.748, 94 ms before the participant
reconnected, saw none, and polled again only at 11:01:33 (30 s interval). The init step waiting for 'global'
completed at 11:01:33.423; startSync's health polling (backoff up to 5 s) saw the app initialized at 11:01:37.004.

```
zcat $T | grep -a 'SV=sv4' | grep -a -E 'GetConnectedSynchronizersResponse|Starting trigger polling loop.*30 seconds' | grep -a '2026-11-06T11:01:[03]' | F | sed -E 's/Request \(tid:[0-9a-f]+\) com.daml.ledger.api.v2.StateService\/GetConnectedSynchronizers to 127.0.0.1:5401: received a message //' | cut -c1-200
```
```
2026-11-06T11:01:03.746Z Starting trigger polling loop, ConfigSummary(pollingInterval = 30 seconds, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)",[o.l.s.a.DomainIngestionService:T/SV=sv4] INFO
2026-11-06T11:01:03.748Z GetConnectedSynchronizersResponse(Vector())",[o.l.s.a.a.c.ApiClientRequestLogger:T/SV=sv4] DEBUG
2026-11-06T11:01:03.754Z Starting trigger polling loop, ConfigSummary(pollingInterval = 30 seconds, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)",[o.l.s.a.DomainIngestionService:T/SV=sv4] INFO
2026-11-06T11:01:03.757Z GetConnectedSynchronizersResponse(Vector())",[o.l.s.a.a.c.ApiClientRequestLogger:T/SV=sv4] DEBUG
2026-11-06T11:01:33.091Z GetConnectedSynchronizersResponse(ConnectedSynchronizer(global, global-domain::..2607, PARTICIPANT_PERMISSION_SUBMISSION))",[o.l.s.a.a.c.ApiClientRequestLogger:T/SV=sv4] DEBUG
2026-11-06T11:01:33.421Z GetConnectedSynchronizersResponse(ConnectedSynchronizer(global, global-domain::..2607, PARTICIPANT_PERMISSION_SUBMISSION))",[o.l.s.a.a.c.ApiClientRequestLogger:T/SV=sv4] DEBUG
```

```
zcat $T | awk '/"@timestamp":"2026-11-06T11:01:(0[3-9]|[1-3][0-9])/' | grep -a 'Running on sv4 command GetHealthStatus' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-90
```
```
2026-11-06T11:01:03.687Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:03.692Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:03.699Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:03.708Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:03.720Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:03.740Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:03.776Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:03.845Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:04.004Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:04.274Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:04.791Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:05.820Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:07.874Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:11.975Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:16.980Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:21.987Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:26.991Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:31.997Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
2026-11-06T11:01:37.004Z Running on sv4 command GetHealthStatus(/api/sv,org.lfdecentralize
```

## 5. What disconnected the participant again: sv4Validator's ReconcileSequencerConnectionsTrigger

At 11:01:36.924 sv4Validator read the DSO sequencer list from scan, found sv2's sequencer available (domain time
11:01:36.698694 is after its `availableAfter` 11:01:36.484607), modified the synchronizer config to add it, and
reconnected the participant to apply it.

```
zcat $T | grep -a '2026-11-06T11:01:36.9' | grep -a 'validator=sv4Validator' | grep -a -E 'For synchronizer|new sequencer configuration|"logger_name":"o.l.s.v.a.ReconcileSequencerConnectionsTrigger' | F | cut -c1-330
zcat $T | grep -a '2026-11-06T11:01:36.93' | grep -a -E 'modifying sequencers connections' | sed -E 's/.*"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/\1 \2/'
```
```
2026-11-06T11:01:36.924Z For synchronizer Synchronizer 'global' at time 2026-11-06T11:01:36.698694Z using Vector(GrpcSequencerConnection(sequencerAlias = Sequencer 'SEQ::sv1::..1d90', endpoints = http://localhost:5108), GrpcSequencerConnection(sequencerAlias = Sequencer 'SEQ::sv2::..bece', endpoints = http://localhost:5208)) fro
2026-11-06T11:01:36.930Z modifying sequencers connections to SequencerConnections(\n  connections = Map(\n    Sequencer 'SEQ::sv1::..1d90' -> GrpcSequencerConnection(\n      sequencerAlias = Sequencer 'SEQ::sv1::..1d90',\n      endpoints = http://localhost:5108\n    ),\n    Sequencer 'SEQ::sv2::..bece' -> GrpcSequencerConnection
2026-11-06T11:01:36.956Z reconnect to the synchronizer Synchronizer 'global' for new sequencer configuration to take effect",[o.l.s.e.ParticipantAdminConnection:T/validator=sv4Validator] INFO
o.l.s.v.a.ReconcileSequencerConnectionsTrigger:SvInitializationIntegrationTest/config=83c95814/validator=sv4Validator INFO
```

Scan's sequencer list in that same response (sv2 available after 11:01:36.484607; sv4 and sv3 follow at 11:01:37.9
and 11:01:54.9):
```
zcat $T | awk '/"@timestamp":"2026-11-06T11:01:36\.9[12]/' | grep -a 'validator=sv4Validator' | grep -a -o 'dso-sequencers): Received response with entity data: .*' | grep -a -o -E 'SEQ::sv[0-9]::[.0-9a-f]+\\",\\"url\\":\\"[^\\]+\\",\\"svName\\":\\"[^\\]+\\",\\"availableAfter\\":\\"[^\\]+'
```
```
SEQ::sv1::1220256d5acc62223af38512b6344fceec93a91743d9f4429aed4a931043bb831d90\",\"url\":\"http://localhost:5108\",\"svName\":\"Digital-Asset-2\",\"availableAfter\":\"2026-11-06T11:00:21.456724Z
SEQ::sv2::1220f2332a7985784d3b51ee480377a6fb85d50e19b31827cc0ec0e68ac83fddbece\",\"url\":\"http://localhost:5208\",\"svName\":\"Digital-Asset-Eng-2\",\"availableAfter\":\"2026-11-06T11:01:36.484607Z
SEQ::sv3::12206264d63dbf64a209dbc481e51ae61305b3e52ccc24fa6da3bddd216d3881c839\",\"url\":\"http://localhost:5308\",\"svName\":\"Digital-Asset-Eng-3\",\"availableAfter\":\"2026-11-06T11:01:54.948845Z
SEQ::sv4::122016ba74e3cd865863488958d4e1ff644d33fa27b7a6c42855d33ee17e284d982a\",\"url\":\"http://localhost:5408\",\"svName\":\"Digital-Asset-Eng-4\",\"availableAfter\":\"2026-11-06T11:01:37.934612Z
```

`availableAfter` is the SV's publish time plus `sequencerAvailabilityDelay`, default 60 s; integration tests override
it only in four LSU/distributed-domain suites, not here:
```
S=04c342f6fd0393eb0eb5c01ea88eb92119c0673e
git show $S:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/SynchronizerNodeReconciler.scala | sed -n 123,133p
git grep -n -E 'sequencerAvailabilityDelay: ' $S -- apps/sv/src/main | cut -c42-
git grep -n -E 'sequencer-availability-delay|unsafeWithSequencerAvailabilityDelay' $S -- apps/app/src/test | cut -c42-260
```
```
          val sequencerAvailableAfter: Option[Instant] = localSequencerConfig.flatMap { _ =>
            val sequencerAvailabilityDelay =
              currentNode.sequencerAvailabilityDelay
            state match {
              case SynchronizerNodeState.OnboardedAfterDelay =>
                Some(clock.now.toInstant.plus(sequencerAvailabilityDelay))
              case SynchronizerNodeState.OnboardedImmediately =>
                Some(clock.now.toInstant)
              case SynchronizerNodeState.Onboarding(_) =>
                None
            }
apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala:659:    sequencerAvailabilityDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(60),
apps/app/src/test/resources/local-sv-node/sv-app/app.conf:51:          sequencer-availability-delay = "60 seconds"
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala:401:  def unsafeWithSequencerAvailabilityDelay(
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/DistributedDomainIntegrationTest.scala:32:      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala:92:      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala:96:      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuIntegrationTest.scala:66:      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
```
(sv2 published its sequencer during this test's `initDso()`, which started 11:00:18.4; 11:01:36.48 - 60 s = 11:00:36.48.)

## 6. The race: the test's list_connected lands in the 56 ms disconnect window

App/test log, sv4 participant admin calls (port 5402) and the test's read:
```
zcat $T | grep -a '2026-11-06T11:01:3[67]' | grep -a -E '(DisconnectSynchronizer|ReconnectSynchronizer) to 127.0.0.1:5402: (sending|received)|ListConnectedSynchronizers\(\) (to|has succeeded for) remote participant for .sv4|Failed clue' | F | sed -E 's/Request \(tid:[0-9a-f]+\) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService\///' | cut -c1-230
```
```
2026-11-06T11:01:36.957Z DisconnectSynchronizer to 127.0.0.1:5402: sending request DisconnectSynchronizerRequest(global)",[o.l.s.a.a.c.ApiClientRequestLogger:T/validator=sv4Validator] DEBUG
2026-11-06T11:01:36.968Z DisconnectSynchronizer to 127.0.0.1:5402: received a message DisconnectSynchronizerResponse()",[o.l.s.a.a.c.ApiClientRequestLogger:T/validator=sv4Validator] DEBUG
2026-11-06T11:01:36.968Z ReconnectSynchronizer to 127.0.0.1:5402: sending request ReconnectSynchronizerRequest(global, false)",[o.l.s.a.a.c.ApiClientRequestLogger:T/validator=sv4Validator] DEBUG
2026-11-06T11:01:37.009Z Sending request ListConnectedSynchronizers() to remote participant for `sv4``.",[c.d.c.a.a.c.GrpcCtlRunner:T] DEBUG
2026-11-06T11:01:37.010Z Request ListConnectedSynchronizers() has succeeded for remote participant for `sv4``.",[c.d.c.a.a.c.GrpcCtlRunner:T] DEBUG
2026-11-06T11:01:37.012Z Failed clue: sv will connect to all domains during initialization.",[o.l.s.i.t.SvInitializationIntegrationTest:SvInitializationIntegrationTest] ERROR
2026-11-06T11:01:37.040Z ReconnectSynchronizer to 127.0.0.1:5402: received a message ReconnectSynchronizerResponse(true)",[o.l.s.a.a.c.ApiClientRequestLogger:T/validator=sv4Validator] DEBUG
```

Participant side (canton log):
```
zcat $C | grep -a '2026-11-06T11:01:3[67]' | grep -a 'SynchronizerConnectionsManager:participant=sv4Participant' | grep -a '"level":"INFO"' | F | cut -c1-170
```
```
2026-11-06T11:01:36.957Z Disconnecting from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Participant] INFO
2026-11-06T11:01:36.958Z Disconnecting connected synchronizer global-domain::12208ab56272...::37-0",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Participant] 
2026-11-06T11:01:36.967Z Disconnected from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Participant] INFO
2026-11-06T11:01:37.023Z Connected to synchronizer and starting synchronisation: Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Participant] INFO
```
Participant disconnected from 11:01:36.967 to 11:01:37.023; the test read at 11:01:37.009-.010 and got `List()`.

## 7. Duplicate check

Board export and families (no hit for the test or the assertion):
```
grep -n -i -E 'SvInitialization|connect to all domains' ci-triage/known-families.md ci-triage/evidence/INDEX.tsv | cut -c1-120
X=/run/sandbox/source/ci-triage/export; for f in inbox triaged in-progress; do jq -r --arg p 'SvInitialization|connect to all domains' 'select((.title + " " + (.body//"") + " " + ([.comments[]?.body] | join(" "))) | test($p)) | "\(input_filename|split("/")|last) #\(.number) \(.createdAt[0:10]) \(.title[0:120])"' $X/$f.jsonl; done
```
```
ci-triage/evidence/INDEX.tsv:46:10176	2026-09-17	35379257952	main 18f490ae5a (the 10174 fix)	105711633730 `wall-clock-ti
```
(the export loop printed nothing). 10176 is a different test of the suite ("SV apps can start one by one", teardown
trigger pause timeout): not the same failure.

All issues, open and closed (search API):
```
for q in '"connect to all domains during initialization"' 'SvInitializationIntegrationTest'; do echo "== $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q" -f per_page=50 --jq '.total_count, (.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])")'; done
```
```
== "connect to all domains during initialization"
1
#5051 closed/not_planned 2025-07-18 SvInitializationIntegrationTest fails on connect to all domains during initialization
== SvInitializationIntegrationTest
14
#10054 closed/duplicate 2026-09-07 SvInitializationIntegrationTest: sv3 fails to initialized because Mediator has No traffic state
#10176 closed/completed 2026-09-18 SvInitializationIntegrationTest teardown: pausing sv2Scan's AcsSnapshotTrigger times out after 5 s on a 10.7 s
#7916 closed/duplicate 2026-04-07 wall-clock-time - duplicate key value violates unique constraint "scan_verdict_hi_ui" & artifact upload issue
#8141 closed/completed 2026-04-22 Unexpected shutdown log on MediatorVerdictIngestionService
#7901 closed/completed 2026-04-07 GHA Run 24066084292 : wall-clock-time - SV apps can start one by one - empty option in physicalSynchronizers
#7749 closed/duplicate 2026-03-26 SvInitializationIntegrationTest org.postgresql.util.PSQLException: ERROR: tuple concurrently updated
#7509 closed/completed 2026-03-12 sv3Participant UnrecoverableError: Mediator approved a request that we have locally rejected
#3093 closed/completed 2025-03-13 sequencer seems to get stuck in time awaiter shortly after initialization
#5355 closed/not_planned 2025-08-06 validator app shutdown failed - command failure
#5051 closed/not_planned 2025-07-18 SvInitializationIntegrationTest fails on connect to all domains during initialization
#5060 closed/completed 2025-07-18 SvInitializationIntegrationTest fails on warnings in log.
#5130 closed/duplicate 2025-07-23 GHA Run 16469808610 : job scala_test Failed :fire:
#4961 closed/completed 2025-07-10 SvInitializationIntegrationTest: Resetting decentralized namespace failed : Failed to reset environment as SV1
#3437 closed/not_planned 2025-04-02 scala_test just stops, no idea why
```
Per hit:
- 5051: same test, same assertion (see below). Same failure signature; mechanism not analysed there (closed not planned, 2025-08-06).
- 10054: sv3 init fails, Mediator has no traffic state (title). Different failure.
- 10176: "SV apps can start one by one" teardown, trigger pause timeout (title). Different test case.
- 7916: duplicate key `scan_verdict_hi_ui` and artifact upload (title). Different.
- 8141: MediatorVerdictIngestionService shutdown log (title). Different.
- 7901: "SV apps can start one by one", empty option in physicalSynchronizers (title). Different test case.
- 7749: PSQLException tuple concurrently updated (title). Different.
- 7509: sv3Participant UnrecoverableError, mediator approved a locally rejected request (title). Different.
- 3093: sequencer stuck in time awaiter (title). Different.
- 5355: validator app shutdown failed (title). Different.
- 5060: warnings in log (title, checkErrors). Different.
- 5130: generic scala_test title; listed by the search for the suite name only. Not the same assertion (the exact-phrase search above returns only 5051).
- 4961: resetting decentralized namespace failed (title). Different.
- 3437: scala_test just stops (title). Different.

5051, the only earlier hit of the assertion:
```
gh api /repos/DACH-NY/cn-test-failures/issues/5051 | jq -r '"#\(.number) [\(.state)] \(.title) \(.created_at) \(.closed_at)"'
gh api "/repos/DACH-NY/cn-test-failures/issues/5051/comments?per_page=100" | jq -r '.[] | "-- \(.user.login) \(.created_at)\n\(.body)"'
```
```
#5051 [closed] SvInitializationIntegrationTest fails on connect to all domains during initialization 2025-07-18T00:29:18Z 2025-08-06T13:29:14Z
-- ray-roestenburg-da 2025-07-18T08:41:28Z
```
```
 connect to all domains during initialization *** FAILED ***
[info]   The partial function passed as the second parameter to inside was not defined at the value passed as the first parameter to inside, which was: List() (SvInitializationIntegrationTest.scala:65)
```
Same assertion (line 65 then), one hit in 2025-07; its logs were not checked here, so the mechanism there is not
confirmed. Not a duplicate target (closed, no analysis); 11238 gets its own Triaged entry.

## 8. Code history of the assertion

```
git log --format='%h %ad %s' --date=short -5 04c342f6fd0393eb0eb5c01ea88eb92119c0673e -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvInitializationIntegrationTest.scala
git blame -L 80,86 --date=short 04c342f6fd0393eb0eb5c01ea88eb92119c0673e -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvInitializationIntegrationTest.scala
```
```
9eea7b2cab 2026-09-17 No longer set the deprecated sequencer connections in the dso rules (#5932)
51dcc13301 2026-04-08 Set min daml versions for tests that use physical sync configs (#4911)
38665fc8ce 2026-04-07 Fix race between SvInitializationIntegrationTest and ResetDecentralizedNamespace (#4826)
1e5eddee92 2026-03-26 Merge branch 'main' into nicuda/canton-3.5/merge_main
1f70cb6fc7 2026-03-26 Add wart to prevent unbounded parTraverse (fixes #1353) (#4569)
b6086ad6030 apps/app/src/test/scala/com/daml/network/integration/tests/SvInitializationIntegrationTest.scala                (canton-network-da 2024-09-18 80)     clue("sv will connect to all domains during initialization.") {
b6086ad6030 apps/app/src/test/scala/com/daml/network/integration/tests/SvInitializationIntegrationTest.scala                (canton-network-da 2024-09-18 81)       sv4Backend.startSync()
afcce7cff9d apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvInitializationIntegrationTest.scala (canton-network-da 2025-04-22 82)       inside(sv4Backend.participantClient.synchronizers.list_connected()) {
b6086ad6030 apps/app/src/test/scala/com/daml/network/integration/tests/SvInitializationIntegrationTest.scala                (canton-network-da 2024-09-18 83)         case Seq(listConnectedDomainsResult) =>
afcce7cff9d apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvInitializationIntegrationTest.scala (canton-network-da 2025-04-22 84)           listConnectedDomainsResult.synchronizerId shouldBe decentralizedSynchronizerId
b6086ad6030 apps/app/src/test/scala/com/daml/network/integration/tests/SvInitializationIntegrationTest.scala                (canton-network-da 2024-09-18 85)       }
b6086ad6030 apps/app/src/test/scala/com/daml/network/integration/tests/SvInitializationIntegrationTest.scala                (canton-network-da 2024-09-18 86)     }
```
The single-read check is old (2024-09, author `canton-network-da` = bulk import); no single owner from blame.

## Verdict

New, test flake (race), family I. Not a duplicate; 5051 (2025-07) had the same assertion but was never analysed.
The SV app did what the test checks (reconnected sv4's participant at 11:01:03.842). The test then read the
connection state once, at a moment when sv4Validator's ReconcileSequencerConnectionsTrigger was bouncing the
connection to add sv2's sequencer (disconnected 11:01:36.967 to 11:01:37.023). The validator's reconnect is
expected behaviour: sequencers published during `initDso()` become available 60 s later, and with the SV app's
30 s domain-store poll plus startSync's 5 s health backoff the check lands about 75 s after `initDso()` started,
inside the 11:01:36 to 11:01:55 window in which the validators add the sv2, sv4 and sv3 sequencers. The window
is open only about 60 ms per reconnect, hence rare.

Fix (test only): in `SvInitializationIntegrationTest.scala` line 82, wrap the `inside(list_connected()) { case Seq(..) => ... }`
in `eventually() { ... }` (default 20 s is ample; the reconnects complete in under 100 ms). No owner from blame;
no assignee suggested.

Not verified: the mechanism of 5051 (logs not fetched); how often the check lands in the reconnect window on
passing runs.
