# 11553 - Sv1BootstrapProtocolVersion36IntegrationTest: GetId polls on the sv1 participant keep failing after its admin API is up (run 44157951723)

DUPLICATE of 11228 (open, family BF). main d52fe77b48, `wall-clock-time (7)`, Canton
3.7.4-snapshot.20261112.20915.0.v9e248620, tests succeeded 17, failed 1. The suite's first clue polls `GetId` on
every standalone Canton node for up to 60 s over admin connections it built before the Canton process was spawned.
The sv1 participant's admin API came up 54.7 s into the budget (19:44:40.885); the two polls after that (19:44:44.458
and 19:44:46.199) still failed in the same millisecond they were sent, with a refused TCP connection, and the
participant logged no GetId request. Same suite, same clue, same line 98, same shape as 11228 (admin API up at 45 s
there). No fix written: duplicate.

- Run: https://github.com/canton-network/splice/actions/runs/44157951723, main d52fe77b48 ("Increase timeout for V2
  reward minting check in tests (#8550)"), job 134304234318 `ci / scala_test_wall_clock_time / wall-clock-time (7)`,
  push event, 2026-11-17 19:24:01-19:58:33.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`git show d52fe77b488d:nix/canton-sources.json`).
- Artifact logs-wall-clock-time-7 (14364632034) in `log/11553/logs-wall-clock-time-7/`.
- Component: test.

## 1. The failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11553/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/11553/job.log | grep -a -A1 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -2
```
```
##[error]Error: failed to run script step (id ac7d38a0-e2bd-11f1-bf6a-45881ef06769): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins *** FAILED ***
[info] Tests: succeeded 17, failed 1, canceled 0, ignored 0, pending 0

[info] - sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins *** FAILED ***
[info]   The future returned an exception of type: io.grpc.StatusRuntimeException, with message: UNAVAILABLE: io exception. (Sv1BootstrapProtocolVersion36IntegrationTest.scala:98)
```
The "custom container" annotation is the generic wrapper of the failed test step. Line 98 at the run's sha
(unchanged on origin/main) is the poll in clue "Canton nodes are running but have no identity":
```
git show d52fe77b488d:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/Sv1BootstrapProtocolVersion36IntegrationTest.scala | sed -n '77,81p;94,102p'
```
```
    val svs = Seq(sv1Backend, sv2Backend)
    val synchronizerNodeConnections: Seq[TopologyAdminConnection] =
      svs.flatMap(sv => Seq(sequencerConnection(sv), mediatorConnection(sv)))
    val topologyConnections: Seq[TopologyAdminConnection] =
      svs.map(participantConnection) ++ synchronizerNodeConnections
      clue("Canton nodes are running but have no identity") {
        topologyConnections.foreach { connection =>
          // Long timeout because Canton is slow to start up
          eventually(timeUntilSuccess = 60.seconds) {
            val idResult = connection.getIdOption().futureValue
            idResult.initialized shouldBe false
            idResult.uniqueIdentifier shouldBe None
          }
        }
      }
```
The connections are built before `withCantonSvNodes` spawns Canton; the first one polled is sv1's participant
(admin port 27102).

## 2. Test side (canton_network_test.clog)

```
zcat canton_network_test.clog.gz | grep -a -E "Starting 'Sv1Bootstrap|Running clue: (Starting external Canton process sv1-bootstrap|Canton nodes are running)|GetId to 127.0.0.1:27102: failed|Failed clue: Canton nodes|Test failed: 'Sv1Bootstrap" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | awk '$1<"2026-11-17T19:45"' | cut -c1-200
```
```
2026-11-17T19:43:45.205Z Starting 'Sv1BootstrapProtocolVersion36IntegrationTest/sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins'...
2026-11-17T19:43:46.176Z Running clue: Starting external Canton process sv1-bootstrap-pv36 with List(canton.features.enable-repair-commands=yes)
2026-11-17T19:43:46.197Z Running clue: Canton nodes are running but have no identity
2026-11-17T19:43:46.212Z Request (no caller tid) ...IdentityInitializationService/GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
...  (backing off: 46.216, 46.220, 46.226, 46.236, 46.254, 46.287, 46.353, 46.483, 46.741, 47.255, 48.281, 50.332, 54.431, then every 5 s)
2026-11-17T19:44:29.450Z ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
2026-11-17T19:44:34.453Z ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
2026-11-17T19:44:39.455Z ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
2026-11-17T19:44:44.458Z ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
2026-11-17T19:44:46.199Z ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
2026-11-17T19:44:46.201Z Failed clue: Canton nodes are running but have no identity
2026-11-17T19:44:48.204Z Test failed: 'Sv1BootstrapProtocolVersion36IntegrationTest/sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins', message: The future returned an excepti
```
The last two polls fail in the millisecond they are sent, and every failure in the suite carries the same cause:
```
zcat canton_network_test.clog.gz | grep -a -E "GetId to 127.0.0.1:27102" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | awk '$1>="2026-11-17T19:44:44" && $1<"2026-11-17T19:44:47"' | cut -c1-175
zcat canton_network_test.clog.gz | grep -a -o -E 'finishConnect[^\\"]{0,80}27102' | sort | uniq -c
```
```
2026-11-17T19:44:44.458Z Request (no caller tid) com.digitalasset.canton.topology.admin.v30.IdentityInitializationService/GetId to 127.0.0.1:27102: sending request GetIdReques
2026-11-17T19:44:44.458Z Request (no caller tid) com.digitalasset.canton.topology.admin.v30.IdentityInitializationService/GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io 
2026-11-17T19:44:46.199Z Request (no caller tid) com.digitalasset.canton.topology.admin.v30.IdentityInitializationService/GetId to 127.0.0.1:27102: sending request GetIdReques
2026-11-17T19:44:46.199Z Request (no caller tid) com.digitalasset.canton.topology.admin.v30.IdentityInitializationService/GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io 
     38 finishConnect(..) failed with error(-111): Connection refused: /127.0.0.1:27102
```
(The 38 include the GetId failures of a later suite on the same port from 19:50:12.)

## 3. Canton side (canton-standalone-sv1-bootstrap-pv36.clog), the confirming grep of 11228

```
zcat canton-standalone-sv1-bootstrap-pv36.clog.gz | grep -a -E 'Starting Canton version|Starting admin-api services|Succeeded startup stage: Stage Admin API|Applied [0-9]+ migrations|Setting up database schemas|Shutdown complete|"level":"(WARN|ERROR)"' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-150
zcat canton-standalone-sv1-bootstrap-pv36.clog.gz | grep -a -c 'GetId'
```
```
2026-11-17T19:43:49.636Z Starting Canton version 3.7.4-snapshot.20261112.20915.0.v9e248620",[c.d.canton.CantonCommunityApp$] INFO
2026-11-17T19:43:55.754Z Setting up database schemas",[c.d.c.e.CantonNodeBootstrapImpl$CheckDbMigrations:sequencer=sv1StandaloneSequencer] INFO   (sv2, sv3 same ms)
2026-11-17T19:44:07.498Z Applied 29 migrations successfully",[c.d.c.r.DbMigrations:sequencer=sv1StandaloneSequencer] INFO   (sv3 19:44:04.870, sv2 19:44:07.523)
2026-11-17T19:44:08.699Z Starting admin-api services on AdminServerConfig(127.0.0.1,Some(27109),...   (27209, 27309 same ms; sequencers)
2026-11-17T19:44:10.227Z Setting up database schemas",[c.d.c.e.CantonNodeBootstrapImpl$CheckDbMigrations:mediator=sv1StandaloneMediator] INFO
2026-11-17T19:44:18.674Z Applied 29 migrations successfully",[c.d.c.r.DbMigrations:mediator=sv1StandaloneMediator] INFO
2026-11-17T19:44:19.676Z Starting admin-api services on AdminServerConfig(127.0.0.1,Some(27107),...   (27207, 27307; mediators)
2026-11-17T19:44:21.010Z Setting up database schemas",[c.d.c.e.CantonNodeBootstrapImpl$CheckDbMigrations:participant=sv1StandaloneParticipant] INFO
2026-11-17T19:44:39.195Z Applied 29 migrations successfully",[c.d.c.r.DbMigrations:participant=sv1StandaloneParticipant] INFO
2026-11-17T19:44:40.885Z Starting admin-api services on AdminServerConfig(127.0.0.1,Some(27102),...   (27202, 27302 same ms; participants)
2026-11-17T19:44:41.041Z Succeeded startup stage: Stage Admin API",[c.d.c.e.CantonNodeBootstrapImpl$SetupAdminApi:participant=sv1StandaloneParticipant] DEBUG
2026-11-17T19:44:46.368Z Shutdown complete.",[c.d.canton.CantonCommunityApp$] INFO
0
```
(Output condensed to the sv1 lines; sv2 and sv3 are within 0.1 s of them.) No WARN or ERROR. Canton was healthy and
slow: 51 s from JVM start (3.4 s after the spawn) to the participant admin API, the three node types starting one
after the other with 9-18 s of Flyway migrations each. The admin API on 27102 was up 54.7 s into the 60 s budget
(19:43:46.197 to 19:44:46.2), the test polled twice after that, and the participant saw neither request.

## 4. Why the polls after 19:44:40.885 fail (inferred, as in 11228)

The channel of the reused ParticipantAdminConnection has failed to connect since 19:43:46.2. grpc-java reconnects
with exponential backoff (1 s, x1.6, jitter 0.2) and fails calls fast with the last connect error in between. From
the first attempt the connect attempts fall at about +1, +2.6, +5.2, +9.3, +15.9, +26.4, +43.2 s (about 19:44:29)
and then about +70 s (19:44:56), after the deadline at 19:44:46.2. Not verified: the channel's backoff settings and
its state (no channel-state logging at this level). The alternative from 11228 (port not yet accepting despite
`Starting admin-api services`) is not excluded by these logs either.

## 5. Duplicate check

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Sv1BootstrapProtocolVersion36"' --jq '.items[]|"\(.number) \(.state) \(.created_at) \(.title)"'
gh issue view 11228 -R DACH-NY/cn-test-failures --json state,stateReason,title,assignees --jq '{state,stateReason,title,assignees:[.assignees[].login]}'
gh api -X GET search/issues -f q='repo:canton-network/splice is:pr Sv1BootstrapProtocolVersion36IntegrationTest' --jq '.items[]|"\(.number) \(.state) \(.created_at) \(.title)"'
```
```
11228 open 2026-11-05T14:30:20Z Sv1BootstrapProtocolVersion36IntegrationTest: participant GetId polls keep failing after the admin API is up
{"assignees":[],"state":"OPEN","stateReason":"","title":"Sv1BootstrapProtocolVersion36IntegrationTest: participant GetId polls keep failing after the admin API is up"}
8150 closed 2026-10-30T11:17:33Z [static] Fix Sv1BootstrapProtocolVersion36IntegrationTest compile after participant admin API auth
```
- 11228 (packet `evidence/11228-pv36-bootstrap-canton-startup-timeout.md`): same suite, clue and line; admin API of
  the sv1 participant up 45 s into the budget, three later polls refused. It is open, not closed as a duplicate, has
  no assignee and no fix PR; the test file and StandaloneCanton.scala are unchanged between d52fe77b48 and
  origin/main.
- Not family AU (11346, closed by splice#8444): same root pattern (withCanton does not wait for the process), but
  that is WalletSurviveCantonRestartIntegrationTest with a Ledger API budget, and its fix touched only that suite.
- Second hit in 12 days (2026-11-05, 2026-11-17) of a suite that runs in every main and PR run: rare flake.

## Verdict

Duplicate of 11228, test flake. A full standalone Canton (3 sequencers, 3 mediators, 3 participants, migrations per
node type in sequence) reached the participant admin API 54.7 s into a 60 s budget, and the admin connection created
before the process existed did not reconnect in the remaining 5.3 s. Fix location: test (as proposed on 11228: build
the connections after Canton is up or use a fresh one per poll, and a budget that covers the startup, as #8444 did
for AU). No fix written here because the ref is a duplicate. Not verified: the gRPC backoff explanation (section 4).
