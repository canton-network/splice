# 10269 - logical-sync-upgrade (0) cancelled at the 60 min job limit: sv2, restarted at the LSU upgrade time, asks its participant for the `global` psid 13 ms after the participant disconnected 36-0 for the upgrade, gets NOT_FOUND, and NodeBase calls sys.exit(1) (run 36840538827)

Two known families stacked, as in 10180. (1) Evidence loss, family H (`NodeBase` `sys.exit(1)` inside the sbt test
JVM, #7289): the test log ends at 09:20:22.145, there is no ScalaTest report, and GitHub cancels the job at its 60 min
limit (SIGINT at 10:08:47, exit 130). (2) The init failure is a fourth variant of family H2 (app restarted while its
participant is mid-LSU), and the first in the SV app. In "upgrade synchronizer to new physical synchronizer without
downtime", the test waits for the upgrade time 09:20:21.788529 and then restarts sv2 (`LsuIntegrationTest.scala:568-570`).
sv2's participant starts its automatic upgrade 36-0 -> 36-2 at 09:20:21.916, disconnects 36-0 at 09:20:22.128, and
starts connecting to 36-2 at 09:20:22.141. In that window sv2's `JoiningNodeInitializer`
(`JoiningNodeInitializer.scala:466`) calls `participantAdminConnection.getPhysicalSynchronizerId(global alias)`
without a retry wrapper. The call throws `NOT_FOUND: No synchronizer registered and handshaked for Synchronizer
'global'` (`ParticipantAdminSynchronizerConnection.scala:104-114`), the init fails at 09:20:22.141 and the app exits.
NOT_FOUND is in `RetryProvider.retryableStatusCodes`, so a `retryProvider.getValueWithRetries` around the call would
have ridden out the 13 ms gap.

- Run: https://github.com/canton-network/splice/actions/runs/36840538827, main f2b8c468be ("Add note on planned
  grafana dashboard migration (#7566)"), job 110298744185 `ci / scala_test_logical_sync_upgrade / logical-sync-upgrade (0)`,
  GH conclusion cancelled. Only non-success job in the run (one attempt).
- Runtime canton: 3.6.0-snapshot.20260929.20331.0.v07b3f95b (`git show f2b8c468be:nix/canton-sources.json | grep -m1 '"version"'`).
- Component: splice SV app (`apps/sv` JoiningNodeInitializer) plus test harness (`NodeBase` sys.exit, #7289).
- Artifact: `logs-logical-sync-upgrade-0` in `log/10269/logs-logical-sync-upgrade-0/`.

## 1. Classification: cancelled at 60 min, no ScalaTest summary, sv2 init exit at 09:20:22

```
$ gh api repos/canton-network/splice/actions/jobs/110298744185 --jq '"\(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)"'; gh api repos/canton-network/splice/check-runs/110298744185/annotations --jq '.[]|select(.annotation_level=="failure")|.message'; sed 's/\x1b\[[0-9;]*[mJK]//g' log/10269/job.log | grep -a -E 'Tests: |Run completed|still running after|so exiting|^\S+ Caused by|Received SIGINT' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++' | cut -c1-200

cancelled 2026-10-01T09:08:43Z 2026-10-01T10:09:26Z self-hosted-k8s-x-large-qmch4-runner-brm22
The job has exceeded the maximum execution time of 1h0m0s
Executing the custom container implementation failed. Please contact your self hosted runner administrator.
Error: failed to run script step (id 38d7d4b0-bd78-11f1-891e-c1f73ad20533): Error: step failed with return code 130
[info] *** Test still running after 1 minute, 24 seconds: suite name: LsuIntegrationTest, test name: cancel a scheduled logical synchronizer upgrade. 
[info] *** Test still running after 1 minute, 54 seconds: suite name: LsuIntegrationTest, test name: cancel a scheduled logical synchronizer upgrade. 
[info] *** Test still running after 2 minutes, 24 seconds: suite name: LsuIntegrationTest, test name: cancel a scheduled logical synchronizer upgrade. 
[info] *** Test still running after 2 minutes, 54 seconds: suite name: LsuIntegrationTest, test name: cancel a scheduled logical synchronizer upgrade. 
[info] *** Test still running after 1 minute, 17 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime. 
[info] *** Test still running after 1 minute, 47 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime. 
[info] *** Test still running after 2 minutes, 17 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime. 
[info] *** Test still running after 2 minutes, 47 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime. 
[info] *** Test still running after 3 minutes, 17 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime. 
sv2 app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv2 app initialization: Initialize node failed
Caused by: java.lang.RuntimeException: sv2 app initialization: JoiningNodeInitializer joining Dso with key failed
Caused by: io.grpc.StatusRuntimeException: NOT_FOUND: No synchronizer registered and handshaked for Synchronizer 'global'
##[warning]Received SIGINT, terminating
```

There is no `Tests:` or `Run completed` line. Both test logs stop at the exit, while the canton processes keep logging until the job is killed:

```
$ zcat log/10269/logs-logical-sync-upgrade-0/canton_network_test.clog.gz | tail -1 | jq -r '."@timestamp"'; zcat log/10269/logs-logical-sync-upgrade-0/canton.clog.gz | tail -1 | jq -r '."@timestamp"'
2026-10-01T09:20:22.145Z
2026-10-01T10:08:51.329Z
```

## 2. The test step: wait for the upgrade time, then restart sv2

```
$ zcat log/10269/logs-logical-sync-upgrade-0/canton_network_test.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-01T09:17:50" and (.logger_name|test("LsuIntegrationTest(/config=[0-9a-f]+)?$")) and (.message|test("^(Running|Finished) clue: (Schedule logical|new nodes are|wait for upgrade|Restart sv2)|^(Starting|Stopping) node sv2$"))) | "\(."@timestamp") \(.message)"'
2026-10-01T09:17:51.788Z Running clue: Schedule logical synchronizer upgrade at 2026-10-01T09:20:21.788529Z
2026-10-01T09:17:55.342Z Finished clue: Schedule logical synchronizer upgrade at 2026-10-01T09:20:21.788529Z
2026-10-01T09:17:59.547Z Running clue: new nodes are initialized
2026-10-01T09:18:08.349Z Finished clue: new nodes are initialized
2026-10-01T09:18:08.349Z Running clue: wait for upgrade time 2026-10-01T09:20:21.788529Z
2026-10-01T09:20:21.787Z Finished clue: wait for upgrade time 2026-10-01T09:20:21.788529Z
2026-10-01T09:20:21.788Z Running clue: Restart sv2 and resume traffic transfer trigger
2026-10-01T09:20:21.788Z Stopping node sv2
2026-10-01T09:20:21.799Z Starting node sv2
```

The restart begins 0.5 ms before the upgrade time. The step comes from #5265 (2f1db590db, 2026-04-27,
`git log -L568,571:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala f2b8c468be`).

## 3. sv2's init: everything up to the psid lookup succeeds, then "joining Dso with key failed"

```
$ zcat log/10269/logs-logical-sync-upgrade-0/canton_network_test.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-01T09:20:21" and (.logger_name|test("(SvApp|JoiningNodeInitializer):LsuIntegrationTest/config=ce4daa34/SV=sv2$")) and .level != "DEBUG") | "\(."@timestamp") \(.level) \(.logger_name|sub(":.*";"")|sub(".*\\.";"")) \(.message|.[0:150])"'
2026-10-01T09:20:21.788Z INFO SvApp Stopping sv2 node
2026-10-01T09:20:22.074Z INFO SvApp sv2 app initialization: Starting initialization
2026-10-01T09:20:22.075Z INFO SvApp Ensuring participant is initialized
2026-10-01T09:20:22.083Z INFO SvApp sv2 app initialization: Ensure participant is initialized with expected id finished after PT0.007882415S
2026-10-01T09:20:22.083Z INFO SvApp Creating ledger API auth token source
2026-10-01T09:20:22.084Z INFO SvApp sv2 app initialization: create ledger client finished after PT0.000709403S
2026-10-01T09:20:22.084Z INFO SvApp Waiting for user sv2-ce4daa34
2026-10-01T09:20:22.084Z INFO SvApp Attempting to get 'user sv2-ce4daa34'
2026-10-01T09:20:22.087Z INFO SvApp Got 'user sv2-ce4daa34': ()
2026-10-01T09:20:22.087Z INFO SvApp sv2 app initialization: Wait for user finished after PT0.003263252S
2026-10-01T09:20:22.090Z INFO SvApp Attempting to get 'Participant ID'
2026-10-01T09:20:22.092Z INFO SvApp Got 'Participant ID': PAR::sv2::1220295fbf5e...
2026-10-01T09:20:22.092Z INFO SvApp sv2 app initialization: Get participant ID finished after PT0.001354965S
2026-10-01T09:20:22.100Z INFO JoiningNodeInitializer Resolved domain migration id 0 from the local store offsets
2026-10-01T09:20:22.107Z INFO JoiningNodeInitializer No CometBFT node found, so not waiting on CometBFT sync.
2026-10-01T09:20:22.107Z INFO JoiningNodeInitializer DSO party is authorized to our participant.
2026-10-01T09:20:22.114Z INFO JoiningNodeInitializer Reconnecting all domains.
2026-10-01T09:20:22.115Z INFO JoiningNodeInitializer Participant hosts dsoParty: true and has proposals to host dsoParty false
2026-10-01T09:20:22.115Z INFO JoiningNodeInitializer Ensuring that the DsoRules list the SV party digital-asset-eng-2-ce4daa34::1220295fbf5e...
2026-10-01T09:20:22.116Z INFO JoiningNodeInitializer Success: the DsoRules list the SV party digital-asset-eng-2-ce4daa34::1220295fbf5e..., result is ()
2026-10-01T09:20:22.117Z INFO JoiningNodeInitializer Initial round 0 is already set in user's metadata.
2026-10-01T09:20:22.135Z INFO JoiningNodeInitializer Waiting until the DsoRules and AmuletRules are visible
2026-10-01T09:20:22.135Z INFO JoiningNodeInitializer Success: the DsoRules and AmuletRules are visible
2026-10-01T09:20:22.141Z INFO SvApp sv2 app initialization: JoiningNodeInitializer joining Dso with key failed
2026-10-01T09:20:22.143Z INFO SvApp sv2 app initialization: Initialize node failed
2026-10-01T09:20:22.143Z INFO SvApp sv2 app initialization: Initialize app failed
2026-10-01T09:20:22.144Z ERROR SvApp sv2 app initialization: Initialization failed
```

The last success is "the DsoRules and AmuletRules are visible" at 22.135, and the next statement in the code is the
psid lookup (section 5). sv2's two earlier starts in this suite (09:13:44, and 09:16:49 for this test) ran the same
step without error, because the participant was connected to 36-0 at the time.

## 4. sv2's participant: 36-0 disconnected at 22.128, 36-2 connecting from 22.141

```
$ zcat log/10269/logs-logical-sync-upgrade-0/canton.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-01T09:20:21.9" and ."@timestamp" <= "2026-10-01T09:20:22.16" and (.logger_name|test("(SynchronizerConnectionsManager|AutomaticLogicalSynchronizerUpgrade|DbSynchronizerConnectionConfigStore|SequencerConnectionPoolImpl):participant=sv2Participant")) and (.message|test("Starting upgrade|not yet at upgrade time|Reconnecting to|Deactivating|is possible|Disconnecting connected|Connecting to synchronizer|now in state Ok"))) | "\(."@timestamp") \(.logger_name|sub(":.*";"")|sub(".*\\.";"")) \(.message|gsub("\n";" ")|.[0:150])"'
2026-10-01T09:20:21.916Z SynchronizerConnectionsManager Starting upgrade from global-domain::1220211a9490...::36-0 to global-domain::1220211a9490...::36-2
2026-10-01T09:20:21.923Z AutomaticLogicalSynchronizerUpgrade LSU_TRANSIENT_ERROR(2,765e5d64): LSU transient failure: Synchronizer index is not yet at upgrade time: should be at 2026-10-01T09:20:21.788529Z time b
2026-10-01T09:20:21.924Z AutomaticLogicalSynchronizerUpgrade The operation 'lsu' was not successful. New kind of error: no success error (request infinite retries). Retrying after 0.2s. Result: Outcome(Left(LSU_
2026-10-01T09:20:22.115Z SynchronizerConnectionsManager Reconnecting to synchronizers List(). Already connected: Set(global-domain::1220211a9490...::36-0)
2026-10-01T09:20:22.126Z DbSynchronizerConnectionConfigStore Deactivating the synchronizer connection configs subsumed by the new LSU target global-domain::1220211a9490...::36-2: 
2026-10-01T09:20:22.128Z AutomaticLogicalSynchronizerUpgrade Upgrade from global-domain::1220211a9490...::36-0 to global-domain::1220211a9490...::36-2 is possible, starting internal upgrade
2026-10-01T09:20:22.128Z SynchronizerConnectionsManager Disconnecting connected synchronizer global-domain::1220211a9490...::36-0
2026-10-01T09:20:22.141Z SynchronizerConnectionsManager Connecting to synchronizer with id global-domain::1220211a9490c8d7e7207ec81b7d9d6d32e20478cb694663c08cb6f8cbf0f248ab9e::36-2 config: SynchronizerConne
2026-10-01T09:20:22.151Z SequencerConnectionPoolImpl 'sequencer-connection-pool' is now in state Ok(). Previous state was Not Initialized.
```

The failure at 22.141 (section 3) falls between `Disconnecting connected synchronizer ...::36-0` (22.128) and
`Connecting to synchronizer with id ...::36-2` (22.141). During that interval no physical synchronizer for alias
`global` is registered and handshaked.

## 5. The code: an unretried lookup, a retryable status code, and sys.exit

```
$ S=f2b8c468be; git show $S:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/joining/JoiningNodeInitializer.scala | sed -n '457,469p'; echo ---; git show $S:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/ParticipantAdminSynchronizerConnection.scala | sed -n '104,114p'; echo ---; git show $S:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/NodeBase.scala | sed -n '284,290p'
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependencyLong,
        "dso_rules_visible",
        show"the DsoRules and AmuletRules are visible",
        dsoStore.getDsoRules().map(_ => ()),
        logger,
      )
      // Register triggers once the DsoRules are visible and have been ingested
      _ = dsoAutomationService.registerPostOnboardingTriggers()
      participantReportedPSid <- participantAdminConnection.getPhysicalSynchronizerId(
        config.domains.global.alias
      )
      currentNode <- synchronizerNodeService.activeSynchronizerNode()
---
  def getPhysicalSynchronizerId(synchronizerAlias: SynchronizerAlias)(implicit
      traceContext: TraceContext
  ): Future[PhysicalSynchronizerId] = lookupPhysicalSynchronizerId(synchronizerAlias).map(
    _.getOrElse(
      throw Status.NOT_FOUND
        .withDescription(
          s"No synchronizer registered and handshaked for $synchronizerAlias"
        )
        .asRuntimeException()
    )
  )
---
        case Failure(err) =>
          val msg = s"$appInitMessage: Initialization failed"
          logger.error(msg, err)
          System.err.println(s"$msg, so exiting; check the application logs for details")
          err.printStackTrace()
          sys.exit(1)
      }
```

The lines above are, in order: `JoiningNodeInitializer.scala:457-469`, `ParticipantAdminSynchronizerConnection.scala:104-114`
and `NodeBase.scala:284-290`. Neither the fail-fast fix nor any retry of this call is on main:

```
$ git merge-base --is-ancestor origin/ray/fix-fail-fast-init origin/main && echo "fail-fast-init in main" || echo "fail-fast-init not in main"; gh issue view 7289 --repo canton-network/splice --json number,state,title --jq '"#\(.number) \(.state) \(.title)"'; git grep -n 'retryableStatusCodes\|Status.Code.NOT_FOUND' f2b8c468be -- apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala | cut -d: -f2- | head -4
fail-fast-init not in main
#7289 OPEN Fail fast on integration test when the environment fails to start.
apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala:534:    private val retryableStatusCodes = Seq(
apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala:539:      Status.Code.NOT_FOUND,
apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryProvider.scala:630:                  if retryableStatusCodes.contains(statusCode) ||
```

## 6. Recurrence: 3 of 162 LSU jobs since 09-27, all at this sv2 restart

Producing the job list (CI workflow runs created since 2026-09-27, all attempts):

```
$ gh api "repos/canton-network/splice/actions/runs?created=>=2026-09-27T00:00:00Z&per_page=100" --paginate --jq '.workflow_runs[]|select(.name|test("^CI (on PRs|post-merge)"))|.id' > log/10269/runs.txt
$ cat log/10269/runs.txt | xargs -P8 -I{} gh api "repos/canton-network/splice/actions/runs/{}/jobs?filter=all&per_page=100" --jq '.jobs[]|select(.name|test("logical-sync-upgrade"))|"\(.id)\t\(.run_id)\t\(.run_attempt)\t\(.conclusion)\t\(.started_at)\t\(.completed_at)\t\(.head_branch)"' > log/10269/lsu-jobs.tsv
```

Every LSU job cancelled after 50 min or more, then the first gRPC cause in each job log (the other two logs were fetched with
`gh api repos/canton-network/splice/actions/jobs/<id>/logs > log/10269/other-<id>.log`):

```
$ python3 -c 'import csv,datetime as d; p=lambda s: d.datetime.fromisoformat(s.replace("Z","+00:00")); [print(r[0],r[3],r[4],round((p(r[5])-p(r[4])).total_seconds()/60),r[6]) for r in csv.reader(open("log/10269/lsu-jobs.tsv"),delimiter="\t") if len(r)==7 and r[3]=="cancelled" and r[5] not in ("null","") and (p(r[5])-p(r[4])).total_seconds()>=3000]' | sort -k3
109539611384 cancelled 2026-09-29T17:47:03Z 60 pawel/bump-apps-transitive
109544383763 cancelled 2026-09-29T18:00:51Z 60 isegall/two-phase-bulk
110298744185 cancelled 2026-10-01T09:08:43Z 61 main
```

```
$ for j in 109539611384 109544383763 110298744185; do f=log/10269/other-$j.log; [ $j = 110298744185 ] && f=log/10269/job.log; printf '%s %s ' $j "$(gh api repos/canton-network/splice/actions/jobs/$j --jq '"\(.started_at) \(.conclusion) \(.head_branch) \(.head_sha[0:10])"')"; sed 's/\x1b\[[0-9;]*[mJK]//g' $f | grep -a -m1 -E '^\S+ Caused by: io.grpc' | sed -E 's/^[^Z]*Z Caused by: io.grpc.StatusRuntimeException: //' | cut -c1-110; done
109539611384 2026-09-29T17:47:03Z cancelled pawel/bump-apps-transitive 3411d8514b NOT_FOUND: No synchronizer registered and handshaked for Synchronizer 'global'
109544383763 2026-09-29T18:00:51Z cancelled isegall/two-phase-bulk 23c3ae97cb NOT_FOUND: TOPOLOGY_STORE_NOT_FOUND(11,6bf51712): No active synchronizer found for global-domain::122033705f34
110298744185 2026-10-01T09:08:43Z cancelled main f2b8c468be NOT_FOUND: No synchronizer registered and handshaked for Synchronizer 'global'
```

The 09-29 isegall/two-phase-bulk job (23c3ae97cb) is the same restart through a different topology read
(`TOPOLOGY_STORE_NOT_FOUND: No active synchronizer found`, the 10180 error); its log does not name the call site.

## Verdict

- Family H (evidence loss, `NodeBase` sys.exit, #7289) plus a new variant D of family H2: the SV app is restarted
  at the LSU upgrade time. Flake, timing dependent: 3 of 162 LSU jobs since 09-27. Before these, 10088-B, 10174
  and 10180 were variants in the validator app.
- Fix, app side (`apps/sv`): wrap `participantAdminConnection.getPhysicalSynchronizerId(config.domains.global.alias)`
  at `JoiningNodeInitializer.scala:466` in `retryProvider.getValueWithRetries`, as the neighbouring steps are
  (NOT_FOUND is retryable, `RetryProvider.scala:534-539`). The TOPOLOGY_STORE_NOT_FOUND hit on 09-29 shows at least
  one more unretried topology read in the same init path, so the durable fix is the one the README cross-cutting
  note already names: resolve the active psid first, or retry NOT_FOUND across SV and validator init while the
  participant is mid-LSU. Test-side alternative: restart sv2 only after its participant reports 36-2 connected.
  That would avoid the window, but it would stop testing a restart during the upgrade, so not proposed.
- Evidence-loss half: `ray/fix-fail-fast-init` (#7289) is still not on main; with it this would be one failed test,
  not a 60 min cancel.
- No fix branch: production code, left to the SV app owner as described.
- Not verified: the call site of the 09-29 TOPOLOGY_STORE_NOT_FOUND variant (its artifact was not downloaded).
