# 10180 - logical-sync-upgrade (0) cancelled at the 60 min job timeout: bobValidatorLocal's init read the synchronizer topology store 56 ms before its participant finished the automatic LSU, got TOPOLOGY_STORE_NOT_FOUND, NodeBase called sys.exit(1), and the shard went silent for 45 minutes (run 35587381756)

Two known families stacked. (1) Evidence loss, family H bullet 2 (10088-A, #7289): a splice app started by the test
fails initialization, `NodeBase` logs `Initialization failed` and calls `sys.exit(1)` inside the sbt test JVM; the
test log and sbt output stop at 10:29:54, the canton processes keep running, nothing is reported, and GitHub cancels
the job at its 60 min limit (SIGINT at 11:14:41, exit 130). (2) The init failure itself is a third variant of family
H2 (validator restarted while its participant is mid-LSU; 10088-B and 10174 were the other two): in the "bob
validator local upgrades after upgrade and can tap" step the participant is restored from its pre-upgrade state and
performs the automatic upgrade 36-0 -> 36-2 while the validator app starts. `NodeInitializer`'s OTK rotation check
reads `ListAllV2` on the logical synchronizer store at 10:29:54.018; at that instant 36-0 is already deactivated
(10:29:53.631) and 36-2 is not yet Ok (10:29:54.104), so the participant answers `TOPOLOGY_STORE_NOT_FOUND: No active
synchronizer found`. That call is the one topology read in the init path not wrapped in `retryProvider.retry`,
although NOT_FOUND is in `retryableStatusCodes`. Fix is app-side (wrap the read, like its sibling); the evidence
loss is #7289.

- Run: https://github.com/canton-network/splice/actions/runs/35587381756, main 8a83eb63b5 ("[ci] Keep alice's merge
  trigger paused across both blocks ...", the 10175 fix), job 106294069033
  `ci / scala_test_logical_sync_upgrade / logical-sync-upgrade (0)`, GH conclusion cancelled. Only non-success job.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824 (`git show 8a83eb63b5:nix/canton-sources.json`).
- Component: splice app (validator init, `apps/common` NodeInitializer) plus test harness (NodeBase sys.exit, #7289).
- The 10174 ignore-pattern fix (18f490ae5a) is an ancestor of this sha; this is not that WARN.

## 1. Classification: no report at all, job cancelled at 60 min

```
gh api repos/canton-network/splice/actions/jobs/106294069033 --jq '{started:.started_at,completed:.completed_at}, (.steps[]|select(.conclusion!="success" and .conclusion!="skipped")|{n:.name,c:.conclusion,s:.started_at,e:.completed_at})'
gh api repos/canton-network/splice/actions/jobs/106294069033/logs > log/10180/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10180/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|contains problems' | wc -l
sed -E 's/\x1b\[[0-9;]*m//g' log/10180/job.log | grep -a -E '^\S+Z \[(info|warn|error)\]' | tail -1 | cut -c1-160
sed -E 's/\x1b\[[0-9;]*m//g' log/10180/job.log | grep -a -E '^2026-09-21T11:14:4' | head -4 | cut -c1-160
```
```
{"started":"2026-09-21T10:14:41Z","completed":"2026-09-21T11:15:34Z"}
{"n":"Run Tests","c":"failure","s":"2026-09-21T10:15:06Z","e":"2026-09-21T11:15:30Z"}
0
2026-09-21T10:29:36.9306154Z [info] *** Test still running after 5 minutes, 24 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime.
2026-09-21T11:14:41.4433200Z ##[warning]Received SIGINT, terminating
2026-09-21T11:14:41.5386384Z Caught signal, killing all pgroup processes
2026-09-21T11:14:41.5418825Z ##[error]Error: failed to run script step (id c0b90be0-b5a5-11f1-86b6-4772ce13bdb6): Error: step failed with return code 130
```
No ScalaTest summary, no `Killed SBT after timeout 40m` line from the sbt wrapper either
(`.github/actions/sbt/execute_sbt_command/action.yml:71-76`, the message is printed only after the `| tee` pipeline
ends). The job log has zero lines between 10:30 and 11:14. Shard suite: LsuIntegrationTest only.

## 2. Test log: stops at 10:29:54.049 with bobValidatorLocal's init failure

```
T=log/10180/logs-logical-sync-upgrade-0/canton_network_test.clog.gz
zcat $T | grep -a -E "Running clue" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | tail -4 | cut -c1-120
zcat $T | tail -1 | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,120}).*"logger_name":"([^"]*)".*/\1 [\3] \2/'
zcat $T | grep -a -oE '^\{"@timestamp":"2026-09-21T1[01]:[0-9]{2}' | cut -c16- | sort | uniq -c | tail -3
```
```
2026-09-21T10:29:40.894Z Running clue: sv and scan app can be restarted
2026-09-21T10:29:41.931Z Running clue: bob validator local upgrades after upgrade and can tap
2026-09-21T10:29:41.931Z Running clue: Starting external Canton process lsu-bob-validator-after-upgrade with List()
2026-09-21T10:29:41.951Z Running clue: Using external Canton process lsu-bob-validator-after-upgrade
2026-09-21T10:29:54.049Z [o.l.s.v.ValidatorApp:LsuIntegrationTest/config=c25fc408/validator=bobValidatorLocal] bobValidatorLocal app initialization: Initialization failed
  49969 2026-09-21T10:27   (per-minute counts; 10:29 is the last minute with any line)
  48116 2026-09-21T10:28
  50273 2026-09-21T10:29
```
The main canton log (`canton.clog.gz`) and the standalone logs keep going at a steady rate until 11:14:5x, so the
processes were alive; only the test JVM's logging stopped.

The code that stops it (main at the run sha):
```
git show 8a83eb63b5:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/NodeBase.scala | sed -n '284,290p'
```
```
        case Failure(err) =>
          val msg = s"$appInitMessage: Initialization failed"
          logger.error(msg, err)
          System.err.println(s"$msg, so exiting; check the application logs for details")
          err.printStackTrace()
          sys.exit(1)
      }
```
Same terminal shape as 10088-A (all four sv*Local apps failed init, log ended, no summary, job cancelled). Issue #7289
is open; branch `ray/fix-fail-fast-init` (2832e6da66, 2026-09-14, uncompiled) carries the halt guard and a test-side
fast failure. What exactly the JVM does after `sys.exit(1)` for 45 minutes is not visible in the logs; the outcome is.

## 3. Why bobValidatorLocal's init failed: NOT_FOUND from the topology store, 30 ms into "Initialize node"

```
zcat $T | grep -a 'validator=bobValidatorLocal' | grep -a -E '"@timestamp":"2026-09-21T10:29:54' | grep -a -v '"level":"DEBUG"' | grep -a -E 'NodeInitializer|ListAllV2|Initialize node|Initialize app|Initialization failed' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,230}).*"level":"([A-Z]+)".*/\1 \3 \2/; s/1220[0-9a-f]{60}/../g'
zcat $T | grep -a 'validator=bobValidatorLocal' | grep -a 'Initialization failed' | tail -1 | grep -a -oE '"stack_trace":"(.{0,330})' | sed -E 's/\\n\\t/\n  /g; s/\\n/\n/g' | head -3
```
```
2026-09-21T10:29:54.018Z INFO Node is initialized with identity bobValidator::1220374955eb..., checking if OTK rotation is needed
2026-09-21T10:29:54.018Z DEBUG(ApiClientRequestLogger) Request (tid:2a9953173563e90ef95e29f7962931b5) com.digitalasset.canton.topology.admin.v30.TopologyManagerReadService/ListAllV2 to 127.0.0.1:27502: sending request ListAllV2Request( BaseQuery( StoreId(Synchronizer(Synchronizer(Id(global-domain::..82e1)))), ...
2026-09-21T10:29:54.048Z INFO Request (tid:2a9953173563e90ef95e29f7962931b5) com.digitalasset.canton.topology.admin.v30.TopologyManagerReadService/ListAllV2 to 127.0.0.1:27502: failed with NOT_FOUND/TOPOLOGY_STORE_NOT_FOUND(11,2a995317): No active synchronizer found for global-domain::122043d435a1...
2026-09-21T10:29:54.048Z INFO bobValidatorLocal app initialization: Initialize node failed
2026-09-21T10:29:54.048Z INFO bobValidatorLocal app initialization: Initialize app failed
2026-09-21T10:29:54.049Z ERROR bobValidatorLocal app initialization: Initialization failed
java.lang.RuntimeException: bobValidatorLocal app initialization: Initialize app failed
  at org.lfdecentralizedtrust.splice.environment.NodeBase.$anonfun$appInitStep$3(NodeBase.scala:225)
Caused by: java.lang.RuntimeException: bobValidatorLocal app initialization: Initialize node failed
```
The read is issued with the logical synchronizer id as store id; Canton resolves that to the active physical
synchronizer, and there was none.

## 4. What the participant was doing: automatic LSU 36-0 -> 36-2, 36-2 became Ok 56 ms after the read

Standalone participant `extraStandaloneParticipant` (the `lsu-bob-validator-after-upgrade` process, started 10:29:42.582):
```
B=log/10180/logs-logical-sync-upgrade-0/canton-standalone-lsu-bob-validator-after-upgrade.clog.gz
zcat $B | grep -a -E '"@timestamp":"2026-09-21T10:29:5[3-4]' | grep -a -E '"level":"(INFO|WARN|ERROR)"' | grep -a -E 'SynchronizerConnectionsManager|ConnectedSynchronizer:|AutomaticLogicalSynchronizerUpgrade|DbSynchronizerConnectionConfigStore' | grep -a -vE 'Attempting online|Now retrying' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,120}).*/\1 \2/; s/global-domain::[0-9a-f.]*::/gd::/g' | awk '!seen[substr($0,25)]++'
```
```
2026-09-21T10:29:53.166Z 'connected-synchronizer' is now in state Ok(). Previous state was Not Initialized.            (psid 36-0)
2026-09-21T10:29:53.470Z Successfully re-connected to synchronizers List(Synchronizer 'global')
2026-09-21T10:29:53.631Z Deactivating the synchronizer connection configs subsumed by the new LSU target gd::36-2:
2026-09-21T10:29:53.635Z Inserting connection for (Synchronizer 'global', gd::36-2) into the DB
2026-09-21T10:29:53.694Z Starting upgrade from gd::36-0 to gd::36-2
2026-09-21T10:29:53.715Z Running automatic upgrade from gd::36-0 to gd::36-2
2026-09-21T10:29:53.743Z LSU_TRANSIENT_ERROR: Synchronizer index is not yet at upgrade time: should be at 2026-09-21T10:27:42.322207Z ...
2026-09-21T10:29:53.776Z Disconnecting from Synchronizer 'global'
2026-09-21T10:29:53.821Z Disconnected from Synchronizer 'global'
2026-09-21T10:29:53.896Z Connected to synchronizer and starting synchronisation: Synchronizer 'global'
2026-09-21T10:29:53.930Z LSU_TRANSIENT_ERROR: Failed to connect to Synchronizer 'global' to perform upgradability check.
2026-09-21T10:29:54.104Z 'connected-synchronizer' is now in state Ok(). Previous state was Not Initialized.            (psid 36-2)
2026-09-21T10:29:54.132Z Upgrade from gd::36-0 to gd::36-2 already done.
```
So between 10:29:53.631 (36-0 deactivated as the subsumed config) and 10:29:54.104 (36-2 Ok) the participant had no
active physical synchronizer for `global`. The validator's read at 10:29:54.018 to .048 fell inside that 470 ms
window. The test itself expects this transition and waits for it (`LsuIntegrationTest.scala:826-832`, `eventually(60 s)`
until the connected synchronizer's serial is the new one); the validator app does not.

## 5. The one unretried topology read in the init path

```
git show 8a83eb63b5:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/setup/NodeInitializer.scala | sed -n '197,201p;218,224p;390,402p'
git show 8a83eb63b5:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala | sed -n '533,538p'
```
```
      status <- connection.getStatus
      _ <- nodeId.uniqueIdentifier match {
        case Some(id) if status.isInitialized =>
          logger.info(s"Node is initialized with identity $id, checking if OTK rotation is needed")
          rotateOwnerToKeyMappingNotSignedByKeys(id, nodeIdentity, synchronizerId)
      // Canton nodes enable their endpoints one at a time, and return NOT_IMPLEMENTED while an endpoint is not yet enabled.
      // (Hence the retry here.)
      ownerToKeyMappings <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "list_owner_to_key_mapping",
        s"${connection.serviceName} answers the listOwnerToKeyMapping request",
        connection.listOwnerToKeyMapping(nodeIdentity(id)),
  private def rotateOwnerToKeyMappingNotSignedByKeys(
      ...
    for {
      nsTxHistory <- connection.listAllTransactions(
        store = TopologyStoreId.Synchronizer(synchronizerId),
        timeQuery = TimeQuery.Range(None, None),
        includeMappings = Set(OwnerToKeyMapping.code),
        filterNamespace = Some(member.namespace),
      )
    private val retryableStatusCodes = Seq(
      Status.Code.UNIMPLEMENTED,
      Status.Code.UNAVAILABLE,
      Status.Code.NOT_FOUND,
```
`findOwnerToKeyMappingThatUsesNamespaceSigningKey` retries its topology read with `WaitingOnInitDependency` (120
tries, 200 ms to 5 s); `rotateOwnerToKeyMappingNotSignedByKeys` does not, and NOT_FOUND is already in the retryable
set, so one wrapper would have carried the validator across the 470 ms window.

## 6. The 371 MB standalone upgrade log is not a signal

```
zcat log/10180/logs-logical-sync-upgrade-0/canton-standalone-global-synchronizer-upgrade.clog.gz | awk ... (levels, per-minute counts, loggers)   # log/10180/standalone-hist.txt
```
```
16471698 DEBUG, 258576 INFO, 288 WARN, 2 ERROR; 340k-360k lines per minute, flat, from 10:30 to 11:14
top loggers: PekkoP2PGrpcNetworkManager 2.68M, IssConsensusModule 0.76M per sequencer, P2PGrpcConnectionManager 0.65M per sequencer
```
It is the four standalone BFT sequencers' DEBUG output for the 45 minutes nobody stopped them. The ERROR and WARN
lines are unrelated to the failure and predate it:
```
zcat <standalone log> | grep -a '"level":"ERROR"' | ...; zcat <standalone log> | grep -a '"level":"WARN"' | grep -oE '"logger_name":"[^"]{0,70}' | awk '{c[$0]++} END {for(k in c) print c[k], k}'   # log/10180/standalone-errs.txt
```
```
2026-09-21T10:29:12.000Z [DefaultVerdictSender:mediator=sv4StandaloneMediator/psid=...::36-2] Failed to send result to sequencer for request 2026-09-21T10:29:11.394816Z RequestRefused(SendAsyncErrorGrpc(Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABOR...
2026-09-21T10:29:12.875Z [DefaultVerdictSender:mediator=sv4StandaloneMediator/psid=...::36-2] Failed to send result to sequencer for request 2026-09-21T10:29:12.308741Z RequestRefused(...)
288 WARN, all TimeProofRequestSubmitterImpl on the four standalone sequencers and mediators (16-44 each)
```
The two ERRORs are sv4's standalone mediator during the "sv4 upgrades" step (10:28:22 to 10:29:40), 40 s before the
bob step; the WARNs are time-proof request retries spread over the idle 45 minutes.

## Verdict

- Cancelled job = evidence loss by `NodeBase.sys.exit(1)` on an app init failure (family H, second recorded occurrence
  after 10088-A). Fix is #7289 / `ray/fix-fail-fast-init` (halt guard plus a fast test failure), still open.
- The init failure = family H2 variant C: validator restarted while its participant is mid-LSU, and the OTK rotation
  check's `listAllTransactions` on the logical synchronizer store is the one unretried call in the init path. Flake in
  CI terms (470 ms window per restart), and a real robustness gap in production: a validator restarted during its
  participant's automatic LSU exits and relies on the orchestrator to restart it.
- Fix location (app, described, not written): wrap the `listAllTransactions` call in `rotateOwnerToKeyMappingNotSignedByKeys`
  in `retryProvider.retry(RetryFor.WaitingOnInitDependency, ...)` as its sibling does; NOT_FOUND is already retryable.
  Alternatively resolve the active physical synchronizer id first and read that store. No test-side change is right
  here: the test deliberately starts the validator during the participant's upgrade.
- Not verified: how often this shard hits the window (10174 and 10088-B were the same restart with different failing
  calls); what the sbt JVM does between `sys.exit(1)` and the cancel.
