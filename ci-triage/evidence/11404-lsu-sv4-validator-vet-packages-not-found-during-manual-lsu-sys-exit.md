# 11404 - LsuIntegrationTest: sv4Validator init reads vetted packages 10 ms after sv4's manual LSU activates 37-2, NOT_FOUND, sys.exit, job cancelled at 60 min (run 43369569777)

New occurrence of family H2 variant C (10180, closed not_planned on 2026-11-09 as "2w no repro") at a different call
site; not a duplicate of an open issue. Scheduled "Integration tests against mainnet Daml version" on main
9f97b5cf4e, job `logical-sync-upgrade (0)`, Canton 3.7.3. Test 1 passed. In test 2 ("upgrade synchronizer to new
physical synchronizer without downtime"), step "sv4 upgrades" restarts the sv4 apps after the upgrade time. The sv4
SV app runs the manual LSU of sv4Participant (36-0 to 37-2); the participant marks 37-2 Active at 05:32:37.154 and
creates its topology store at 05:32:37.204-.226. The sv4Validator init step "Vet packages" sends
`ListVettedPackages` on the logical synchronizer store at 05:32:37.162, gets `TOPOLOGY_STORE_NOT_FOUND ... 37-2 is
not known`, the unretried read fails the init, `NodeBase` calls sys.exit in the sbt test JVM (05:32:37.167), and the
job sits without output until GitHub cancels it at the 60 min limit (SIGINT 06:08:16). No test report.

- Run: https://github.com/canton-network/splice/actions/runs/43369569777, main 9f97b5cf4e ("Re-find and re-click the
  ANS search button until its result shows in AnsFrontendIntegrationTest (#8418)"), job 131702801310
  `build / scala_test_logical_sync_upgrade / logical-sync-upgrade (0)`, attempt 1. Conclusion cancelled, not failure;
  it is the only job of that name in the run and the only non-successful one, so the issue maps to it.
- Runtime canton: 3.7.3 (`git show 9f97b5cf4e:nix/canton-sources.json | grep -m1 '"version"'`).
- Component: splice app (validator init, `ParticipantAdminDarsConnection.vetDars`) plus test harness (sys.exit in
  the test JVM loses the report).
- The mainnet Daml version plays no part: the failing call is a topology read that never reaches a package check.

## 1. Classification: no report, job cancelled at the 60 min limit

Job and failed step.

```
gh api repos/canton-network/splice/actions/jobs/131702801310 --jq '{conclusion,started:.started_at,completed:.completed_at}, (.steps[]|select(.conclusion!="success" and .conclusion!="skipped")|{n:.name,c:.conclusion,s:.started_at,e:.completed_at})'
```
```
{"completed":"2026-11-13T06:09:34Z","conclusion":"cancelled","started":"2026-11-13T05:08:16Z"}
{"c":"failure","e":"2026-11-13T06:09:29Z","n":"Run Tests","s":"2026-11-13T05:08:45Z"}
```

No scalatest summary in the job log.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11404/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|contains problems' | wc -l
```
```
0
```

The last console output before the cancel is the sv4Validator init failure; nothing is printed between 05:32:37 and
the SIGINT.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11404/job.log | grep -a -E 'T05:32:(19|37)|T06:08:16' | grep -a -E 'still running|app initialization|Caused by|SIGINT|killing|return code' | sed -E 's/^[^ ]*T([0-9:]+)\.[0-9]+Z /\1 /' | cut -c1-250
sed -E 's/\x1b\[[0-9;]*m//g' log/11404/job.log | grep -a -c 'T05:3[3-9]\|T05:[45]\|T06:0[0-7]'
```
```
05:32:19 [info] *** Test still running after 4 minutes, 26 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime. 
05:32:37 sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
05:32:37 java.lang.RuntimeException: sv4Validator app initialization: Initialize app failed
05:32:37 Caused by: java.lang.RuntimeException: sv4Validator app initialization: Initialize node failed
05:32:37 Caused by: java.lang.RuntimeException: sv4Validator app initialization: Vet packages failed
05:32:37 Caused by: io.grpc.StatusRuntimeException: NOT_FOUND: TOPOLOGY_STORE_NOT_FOUND(11,b71635d5): Topology store 'global-domain::1220c98d14c9...::37-2' is not known.
06:08:16 ##[warning]Received SIGINT, terminating
06:08:16 Caught signal, killing all pgroup processes
06:08:16 ##[error]Error: failed to run script step (id e7784610-df21-11f1-b2f5-1b328b36edb3): Error: step failed with return code 130
0
```

## 2. Which test was running

`T=log/11404/logs-logical-sync-upgrade-0/canton_network_test.clog.gz` (artifact `logs-logical-sync-upgrade-0`,
`gzip -t` ok). Test 1 passed, test 2 started at 05:27:51.

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-11-13T05:20:49.280Z Starting test suite 'LsuIntegrationTest'...",
2026-11-13T05:20:56.858Z Starting 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade'...",
2026-11-13T05:27:41.986Z Test succeeded: 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade'",
2026-11-13T05:27:51.611Z Starting 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'...",
```

The test log ends at the exit.

```
zcat $T | tail -n 1 | cut -c1-60
```
```
{"@timestamp":"2026-11-13T05:32:37.170Z","message":"Request 
```

The last test step is "sv4 upgrades" (LsuIntegrationTest.scala:794), 4.7 s before the exit. `F` is the line
formatter of recipe 4 plus `s/LsuIntegrationTest\/config=c372b0f1\///`.

```
zcat $T | grep -a '"logger_name":"o.l.s.i.t.LsuIntegrationTest' | grep -a -E 'Running clue: (Alice can purchase traffic|sv4 upgrades|sv4 connects)|Finished clue: (Alice can purchase traffic|sv4)' | sed -E "$F" | cut -c1-200
```
```
2026-11-13T05:32:29.110Z Running clue: Alice can purchase traffic",[o.l.s.i.t.LsuIntegrationTest:LsuIntegrationTest] DEBUG
2026-11-13T05:32:32.486Z Finished clue: Alice can purchase traffic",[o.l.s.i.t.LsuIntegrationTest:LsuIntegrationTest] DEBUG
2026-11-13T05:32:32.486Z Running clue: sv4 upgrades",[o.l.s.i.t.LsuIntegrationTest:LsuIntegrationTest] DEBUG
```

The step starts all sv4 apps in parallel and then waits for sv4 to connect to the new synchronizer.

```
git show 9f97b5cf4e:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala | sed -n '794,802p'
```
```
      clue("sv4 upgrades") {
        loggerFactory.suppress(
          SuppressionRule.forLogger[LsuTrigger] && SuppressionRule.Level(
            org.slf4j.event.Level.WARN
          )
        ) {
          lateJoiningNode.par.foreach(_.startSync())
          clue("sv4 connects to the new sync") {
            eventually(120.seconds) {
```

## 3. sv4Validator: "Vet packages" waits on sv4Scan, then reads the vetting state with no synchronizer connected

Window file: `zcat $T | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] >= "2026-11-13T05:32:32.4" && a[1] <= "2026-11-13T05:32:37.2") print}' > log/11404/window.jsonl`.
At 05:32:33.648 the participant is still connected to 36-0. The step retries `amulet-rules` against sv4Scan (404
five times) until 05:32:37.152; then the participant reports no connected synchronizer (05:32:37.155) and the
`ListVettedPackages` read on the logical store id fails at 05:32:37.165.

```
grep -a 'validator=sv4Validator' log/11404/window.jsonl | grep -a -E 'ListConnectedSynchronizers.*received a message|amulet-rules.*status code|Vetting packages:|ListDars.*succeeded|ListVettedPackages|app initialization: (Vet|Initializ)' | sed -E "$F" | cut -c1-300
```
```
2026-11-13T05:32:33.606Z sv4Validator app initialization: Initialize app started",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:33.620Z sv4Validator app initialization: Initialize node started",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:33.648Z Request (tid:b9db0d1ae3d551971aafe079a31082fd) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ListConnectedSynchronizers to 127.0.0.1:5402: received a message ListConnectedSynchronizersResponse(\n  Result(global, global-domain::..edda, global-d
2026-11-13T05:32:33.649Z sv4Validator app initialization: Vet packages started",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:33.651Z HTTP client (POST /api/scan/v0/amulet-rules): HTTP request took 1 ms to complete. Received response with status code: 404 Not Found",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:33.855Z HTTP client (POST /api/scan/v0/amulet-rules): HTTP request took 2 ms to complete. Received response with status code: 404 Not Found",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:34.061Z HTTP client (POST /api/scan/v0/amulet-rules): HTTP request took 2 ms to complete. Received response with status code: 404 Not Found",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:34.534Z HTTP client (POST /api/scan/v0/amulet-rules): HTTP request took 2 ms to complete. Received response with status code: 404 Not Found",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:35.479Z HTTP client (POST /api/scan/v0/amulet-rules): HTTP request took 1 ms to complete. Received response with status code: 404 Not Found",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:37.152Z HTTP client (POST /api/scan/v0/amulet-rules): HTTP request took 4 ms to complete. Received response with status code: 200 OK",[o.l.s.v.ValidatorApp:validator=sv4Validator] DEBUG
2026-11-13T05:32:37.155Z Request (tid:e194a4ab9f856b181ee947691e324ebb) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ListConnectedSynchronizers to 127.0.0.1:5402: received a message ListConnectedSynchronizersResponse(Vector())",[o.l.s.a.a.c.ApiClientRequestLogger:val
2026-11-13T05:32:37.156Z Vetting packages: (splice-validator-lifecycle,0.1.5), (splice-validator-lifecycle,0.1.6), (splice-validator-lifecycle,0.1.7), (splice-validator-lifecycle,0.1.8), (splice-validator-lifecycle,0.1.9), (splice-wallet-payments,0.1.19), (splice-wallet-payments,0.1.20), (splice-wal
2026-11-13T05:32:37.161Z Request (tid:86881670f59756f28ea3970cd7ad89c5) com.digitalasset.canton.admin.participant.v30.PackageService/ListDars to 127.0.0.1:5402: succeeded(OK)",[o.l.s.a.a.c.ApiClientRequestLogger:validator=sv4Validator] DEBUG
2026-11-13T05:32:37.162Z Request (tid:b71635d528ef3d71963d79902a0b2ee4) com.digitalasset.canton.topology.admin.v30.TopologyManagerReadService/ListVettedPackages to 127.0.0.1:5402: sending request ListVettedPackagesRequest(\n  BaseQuery(\n    StoreId(Synchronizer(Synchronizer(Id(global-domain::..edda
2026-11-13T05:32:37.165Z Request (tid:b71635d528ef3d71963d79902a0b2ee4) com.digitalasset.canton.topology.admin.v30.TopologyManagerReadService/ListVettedPackages to 127.0.0.1:5402: failed with NOT_FOUND/TOPOLOGY_STORE_NOT_FOUND(11,b71635d5): Topology store 'global-domain::1220c98d14c9...::37-2' is no
2026-11-13T05:32:37.165Z sv4Validator app initialization: Vet packages failed",[o.l.s.v.ValidatorApp:validator=sv4Validator] INFO
2026-11-13T05:32:37.166Z sv4Validator app initialization: Initialize node failed",[o.l.s.v.ValidatorApp:validator=sv4Validator] INFO
2026-11-13T05:32:37.166Z sv4Validator app initialization: Initialize app failed",[o.l.s.v.ValidatorApp:validator=sv4Validator] INFO
2026-11-13T05:32:37.167Z sv4Validator app initialization: Initialization failed",[o.l.s.v.ValidatorApp:validator=sv4Validator] ERROR
```

## 4. sv4Participant: 37-2 is Active 50 ms before its topology store exists

`zcat log/11404/logs-logical-sync-upgrade-0/canton.clog.gz | grep -a 'participant=sv4Participant' | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] >= "2026-11-13T05:32:33.3" && a[1] <= "2026-11-13T05:32:50") print}' > log/11404/sv4p.jsonl`.
The manual LSU deactivates 36-0 at 05:32:37.149 and activates 37-2 at 05:32:37.154; the topology store of 37-2 is
filled at 05:32:37.204-.226. The validator's read (error id b71635d5) lands at 05:32:37.164, inside that gap.

```
grep -a -E 'OnlineManualLogicalSynchronizerUpgrade|TOPOLOGY_STORE_NOT_FOUND|LSU: (About|Done)|Transferred [0-9]+ topology|Setting status of|Disconnected from Synchronizer' log/11404/sv4p.jsonl | grep -a -v ApiRequestLogger | sed -E "$F" | cut -c1-250
```
```
2026-11-13T05:32:37.121Z Manual upgrade from global-domain::1220c98d14c9...::36-0 to global-domain::1220c98d14c9...::37-2 with upgrade time 2026-11-13T05:31:49.779899Z",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=
2026-11-13T05:32:37.122Z Running manual upgrade from global-domain::1220c98d14c9...::36-0 to global-domain::1220c98d14c9...::37-2",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.122Z Upgrade from global-domain::1220c98d14c9...::36-0 to global-domain::1220c98d14c9...::37-2",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.122Z Running manual-online upgrade from global-domain::1220c98d14c9...::36-0 to global-domain::1220c98d14c9...::37-2",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.122Z Attempting online upgradability check",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.122Z Scheduling lsu-online-upgradability-check",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.124Z Trying to register successor config. Known sequencers are Set(Sequencer 'DefaultSequencer') with threshold 1",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.124Z Sequencers without known id: List(). Sequencers without known successor: List(). Successors known for: List(Sequencer 'DefaultSequencer')",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2]
2026-11-13T05:32:37.136Z Upgrade from global-domain::1220c98d14c9...::36-0 to global-domain::1220c98d14c9...::37-2 is possible, starting internal upgrade",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.136Z Running manual-online upgrade from global-domain::1220c98d14c9...::36-0 to global-domain::1220c98d14c9...::37-2",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.149Z Disconnected from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv4Participant] INFO
2026-11-13T05:32:37.149Z Scheduling manual-online-lsu-upgrade",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.149Z Marking synchronizer connection global-domain::1220c98d14c9...::36-0 as inactive",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.149Z Setting status of (Synchronizer 'global', global-domain::..edda::36-0) to LSU source",[c.d.c.p.s.d.DbSynchronizerConnectionConfigStore:participant=sv4Participant] INFO
2026-11-13T05:32:37.154Z Marking synchronizer connection global-domain::1220c98d14c9...::37-2 as active",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.154Z Setting status of (Synchronizer 'global', global-domain::..edda::37-2) to Active",[c.d.c.p.s.d.DbSynchronizerConnectionConfigStore:participant=sv4Participant] INFO
2026-11-13T05:32:37.160Z Manual-online upgrade was successful",[c.d.c.p.s.OnlineManualLogicalSynchronizerUpgrade:participant=sv4Participant/lsu=37-2] INFO
2026-11-13T05:32:37.164Z TOPOLOGY_STORE_NOT_FOUND(11,b71635d5): Topology store 'global-domain::1220c98d14c9...::37-2' is not known.",[c.d.c.t.a.g.GrpcTopologyManagerReadService:participant=sv4Participant] INFO
2026-11-13T05:32:37.204Z LSU: About to copy topology from 36-0",[c.d.c.p.s.g.GrpcSynchronizerRegistry:participant=sv4Participant] INFO
2026-11-13T05:32:37.226Z Transferred 94 topology transactions from global-domain::1220c98d14c9...::36-0 to global-domain::1220c98d14c9...::37-2",[c.d.c.t.s.d.DbTopologyStore:participant=sv4Participant/psid=global-domain::1220c98d14c9::37-2] INFO
2026-11-13T05:32:37.226Z LSU: Done copying topology from 36-0",[c.d.c.p.s.g.GrpcSynchronizerRegistry:participant=sv4Participant] INFO
```

## 5. Who started the manual LSU, and why the two meet

The sv4 SV app (restarted in the same step) initializes its new sequencer and mediator and then sends
`PerformManualLsu` at 05:32:37.082.

```
grep -a -E 'SV=sv4' log/11404/window.jsonl | grep -a -E 'PerformManualLsu.*(sending|succeeded)|Initializing mediator|InitializeMediator.*succeeded|InitializeSequencerFromLsuPredecessor.*succeeded' | sed -E "$F" | cut -c1-230
```
```
2026-11-13T05:32:36.182Z Request (tid:0c7a0270a553f6c480a6d0a2a5c569a2) com.digitalasset.canton.sequencer.admin.v30.SequencerInitializationService/InitializeSequencerFromLsuPredecessor to 127.0.0.1:27409: succeeded(OK)",[o.l.s.a.a
2026-11-13T05:32:36.189Z Initializing mediator",[o.l.s.s.l.LsuNodeInitializer:SV=sv4] INFO
2026-11-13T05:32:37.056Z Request (tid:ccfc927d0bee2dc6ab85d5705f950b61) com.digitalasset.canton.mediator.admin.v30.MediatorInitializationService/InitializeMediator to 127.0.0.1:27407: succeeded(OK)",[o.l.s.a.a.c.ApiClientRequestLo
2026-11-13T05:32:37.082Z Request (tid:f8f56ab980fd5d706d873d3558dab35c) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/PerformManualLsu to 127.0.0.1:5402: sending request PerformManualLsuRequest(\n  
```

sv4Scan, which the validator's step waits on, finishes init at 05:32:36.881; the validator's next retry is at
05:32:37.147. Both paths take about 3.5 to 4 s from the same start, so the read can land in the 50 ms gap.

```
grep -a 'scan=sv4Scan' log/11404/window.jsonl | grep -a 'Initialization complete' | sed -E "$F" | cut -c1-200
```
```
2026-11-13T05:32:36.881Z sv4Scan app initialization: Initialization complete, running on version 0.10.3-ci-snapshot.20261112.1.0.v9f97b5cf-dirty",[o.l.s.s.ScanApp:scan=sv4Scan] INFO
```

## 6. Code: the first read of vetDars is not retried

"Vet packages" (ValidatorApp.scala:230) calls `PackageVetting.vetCurrentPackages` -> `vetPackages` ->
`uploadDarsAndVet` -> `participantAdminConnection.vetDars`. `vetDars` starts with `lookupVettingState`, a plain
`listVettedPackages` call outside `ensureTopologyMapping` and outside any `retryProvider.retry`.

```
git show 9f97b5cf4e:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/ParticipantAdminDarsConnection.scala | sed -n '69,81p;290,297p'
```
```
  def vetDars(
      synchronizerId: SynchronizerId,
      dars: Seq[DarResource],
      fromDate: Option[Instant],
      maxVettingDelay: Option[(Clock, NonNegativeFiniteDuration)],
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val cantonFromDate = fromDate.map(CantonTimestamp.assertFromInstant)
    lookupVettingState(
      Some(synchronizerId),
      TopologyAdminConnection.TopologyTransactionType.AuthorizedState,
    ).flatMap {
  def lookupVettingState(
      domain: Option[SynchronizerId],
      topologyTransactionType: TopologyTransactionType,
  )(implicit tc: TraceContext): Future[Option[TopologyResult[VettedPackages]]] = {
    for {
      participantId <- getParticipantId()
      vettedState <- listVettedPackages(participantId, domain, topologyTransactionType)
    } yield {
```

NOT_FOUND is a retryable status code, so wrapping the read would have retried it.

```
git show 9f97b5cf4e:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala | sed -n '534,540p'
```
```
    private val retryableStatusCodes = Seq(
      // Canton registers its services after starting the top-level gRPC server, which means we get UNIMPLEMENTED errors
      // for a short period of during the startup of Canton.
      Status.Code.UNIMPLEMENTED,
      Status.Code.UNAVAILABLE,
      Status.Code.NOT_FOUND,
      Status.Code.ALREADY_EXISTS,
```

The read that failed in 10180 (`NodeInitializer.rotateOwnerToKeyMappingNotSignedByKeys`) is still unwrapped at this
sha.

```
git show 9f97b5cf4e:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/setup/NodeInitializer.scala | sed -n '409,415p'
```
```
    for {
      nsTxHistory <- connection.listAllTransactions(
        store = TopologyStoreId.Synchronizer(synchronizerId),
        timeQuery = TimeQuery.Range(None, None),
        includeMappings = Set(OwnerToKeyMapping.code),
        filterNamespace = Some(member.namespace),
      )
```

## 7. Duplicate check

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue TOPOLOGY_STORE_NOT_FOUND' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:150])"'
```
```
#10180 closed/not_planned 2026-09-21 logical-sync-upgrade (0) cancelled at the 60 min job timeout: bobValidatorLocal's init read the synchronizer topology store 56 ms before its participa
#8131 closed/completed 2026-04-21 Delete Permissioned synchronizer test from main branch
#8178 closed/duplicate 2026-04-24 UpdateHistoryIntegrationTest: Topology store `global-domain::1220c5c63a57...::34-0` is not known
#8009 closed/duplicate 2026-04-09 PermissionedSynchronizerIntegrationTest: Request failed for remote participant for `sv1Validator`
#8132 closed/completed 2026-04-21 participant disconnects from synchronizer without any indication why
```

- 10180: same failure class (validator init topology read on the logical synchronizer store while its participant
  switches psid during an LSU, NOT_FOUND, sys.exit, job cancelled at 60 min). Different call site (OTK rotation
  check, `No active synchronizer found`), different step (bobValidatorLocal after the upgrade) and Canton 3.6.
  Closed not_planned on 2026-11-09 after "2w no repro" (`gh api repos/DACH-NY/cn-test-failures/issues/10180 --jq
  '.state_reason, .closed_at'` prints `not_planned`, `2026-11-09T22:30:00Z`).
- 8178, 8131, 8009, 8132 (2026-04): other tests, before LSU; not the same failure.
- Not AV (11354: scans restarted, BFT call retries run out; here the scan call succeeds), not AA (11104: sv4 faucet
  trigger ERROR, tests pass), not 11401 / 11255 (onboarding in test 1; here test 1 passed), not AY (11379: SV app
  waits on the legacy sequencer; here the SV app completes the manual LSU).

## Verdict

Family H2, variant C, second occurrence (10180 on 2026-09-17, this on 2026-11-13); new call site. Flake: a 50 ms
window per sv4 restart. Real product race in the validator app: a validator starting while its participant performs
an LSU can read the logical synchronizer topology store between the activation of the successor psid and the
creation of its store, and the unretried read ends the app.

Fix location: splice app. In `ParticipantAdminDarsConnection.vetDars` wrap the initial `lookupVettingState` in
`retryProvider.retry` (NOT_FOUND is retryable), or wrap the "Vet packages" vetting call in ValidatorApp.scala the
way the `getAmuletRules` call above it is wrapped; do the same for the 10180 read in
`NodeInitializer.rotateOwnerToKeyMappingNotSignedByKeys`. Evidence loss (sys.exit in the test JVM, 36 min of idle
job) is the known family H problem.

Not verified: no repro; the Canton side (whether a psid should be reported Active before its topology store is
registered) was not checked against the 3.7.3 jar; why the step does not end when the sbt JVM exits (the `timeout
40m` wrapper started at 05:20:13 did not end the step by 06:00:13 either) was not investigated.
