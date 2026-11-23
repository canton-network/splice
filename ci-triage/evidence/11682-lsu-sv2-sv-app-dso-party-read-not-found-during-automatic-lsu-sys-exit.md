# 11682 - LsuIntegrationTest: sv2 SV app init reads the DSO party mapping while sv2Participant runs the automatic LSU, NOT_FOUND, sys.exit, job cancelled at 60 min (run 44722471331)

New occurrence of family H2 variant C (10180 closed not_planned, 11404 open) at a third call site, in the SV app;
not a duplicate of an open issue by the confirming grep of 11404 (that one is the validator's `ListVettedPackages`
read during a manual LSU, and its proposed fix does not touch this path). Post-merge run on main 7b71d35779, job
`logical-sync-upgrade (0)`, Canton 3.7.4. Test 1 passed. Test 2 ("upgrade synchronizer to new physical synchronizer
without downtime") sleeps until the upgrade time 11:10:36.330 and then restarts sv2. sv2Participant runs its
automatic LSU at the same moment: it sets 37-0 to "LSU source" at 11:10:36.776 and 37-2 to Active at 11:10:36.789.
The restarted sv2 SV app sends `ListPartyToParticipant` on the logical synchronizer store at 11:10:36.788
(`DsoPartyHosting.isDsoPartyAuthorizedOn`, not retried), the participant answers `TOPOLOGY_STORE_NOT_FOUND: No
active synchronizer found` at 11:10:36.797, the SV app init fails, `NodeBase` calls sys.exit in the sbt test JVM
(11:10:36.803), and the job prints nothing until GitHub cancels it at the 60 min limit (SIGINT 11:50:50). No test
report.

- Run: https://github.com/canton-network/splice/actions/runs/44722471331, main 7b71d35779 ("Remove package version
  guards made redundant by the minimum Daml versions (#8600)"), job 136241914295
  `ci / scala_test_logical_sync_upgrade / logical-sync-upgrade (0)`, attempt 1. Conclusion cancelled, not failure
  (`gh run view --json jobs` with `conclusion=="failure"` lists nothing); it is the only job of that name in the run
  and the issue body names it.
- Runtime canton: 3.7.4 (`git show 7b71d35779:nix/canton-sources.json | grep -m1 version`).
- Component: splice app (SV app init, `JoiningNodeInitializer.joinDsoAndOnboardNodes`) plus test harness (sys.exit
  in the test JVM loses the report, family H).
- The commit under test (#8600) plays no part: the failing call is a topology read in the SV app init.

## 1. Classification: no report, job cancelled at the 60 min limit

Job and failed step.

```
gh api repos/canton-network/splice/actions/jobs/136241914295 --jq '{conclusion,started:.started_at,completed:.completed_at}, (.steps[]|select(.conclusion!="success" and .conclusion!="skipped")|{n:.name,c:.conclusion,s:.started_at,e:.completed_at})'
```
```
{"completed":"2026-11-20T11:52:00Z","conclusion":"cancelled","started":"2026-11-20T10:50:50Z"}
{"c":"failure","e":"2026-11-20T11:51:54Z","n":"Run Tests","s":"2026-11-20T10:51:08Z"}
```

No scalatest summary in the job log (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/136241914295/logs > log/11682/job.log`).

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11682/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|contains problems' | wc -l
```
```
0
```

The last console output before the cancel is the sv2 SV app init failure; nothing is printed between 11:10:36 and
the SIGINT (second command: count of lines in between).

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11682/job.log | grep -a -E 'T11:10:(16|36)|T11:50:5' | grep -a -E 'still running|app initialization|Caused by|SIGINT|killing|return code' | sed -E 's/^[^ ]*T([0-9:]+)\.[0-9]+Z /\1 /' | cut -c1-250
sed -E 's/\x1b\[[0-9;]*m//g' log/11682/job.log | grep -a -c 'T11:1[1-9]\|T11:[234]\|T11:50:[0-4]'
```
```
11:10:16 [info] *** Test still running after 3 minutes, 51 seconds: suite name: LsuIntegrationTest, test name: upgrade synchronizer to new physical synchronizer without downtime. 
11:10:36 sv2 app initialization: Initialization failed, so exiting; check the application logs for details
11:10:36 java.lang.RuntimeException: sv2 app initialization: Initialize app failed
11:10:36 Caused by: java.lang.RuntimeException: sv2 app initialization: Initialize node failed
11:10:36 Caused by: java.lang.RuntimeException: sv2 app initialization: JoiningNodeInitializer joining Dso with key failed
11:10:36 Caused by: io.grpc.StatusRuntimeException: NOT_FOUND: TOPOLOGY_STORE_NOT_FOUND(11,c4c41483): No active synchronizer found for global-domain::12204f082a29....
11:50:50 ##[warning]Received SIGINT, terminating
11:50:51 Caught signal, killing all pgroup processes
11:50:51 ##[error]Error: failed to run script step (id 8ec24470-e4d1-11f1-91c7-ed8a3bc77005): Error: step failed with return code 130
0
```

## 2. Which test was running

`D=log/11682/logs-logical-sync-upgrade-0` (artifact `logs-logical-sync-upgrade-0`), `T=$D/canton_network_test.clog.gz`.
The shard runs one suite (`cat $D/test-full-class-names-lsu.log`:
`org.lfdecentralizedtrust.splice.integration.tests.LsuIntegrationTest`). Test 1 passed, test 2 started at 11:06:24.

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-11-20T11:00:46.501Z Starting test suite 'LsuIntegrationTest'...",
2026-11-20T11:00:53.854Z Starting 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade'...",
2026-11-20T11:06:13.708Z Test succeeded: 'LsuIntegrationTest/cancel a scheduled logical synchronizer upgrade'",
2026-11-20T11:06:24.415Z Starting 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'...",
```

The test log ends at the exit (family H confirming grep).

```
zcat $T | tail -n 1 | cut -c1-60
```
```
{"@timestamp":"2026-11-20T11:10:36.806Z","message":"Running 
```

The last test step is "Restart sv2 and resume traffic transfer trigger", entered at the upgrade time to the
millisecond. `F` is the line formatter of recipe 4.

```
zcat $T | grep -a '"logger_name":"o.l.s.i.t.LsuIntegrationTest' | grep -a -E 'clue: (wait for upgrade time|Restart sv2)' | sed -E "$F" | cut -c1-200
```
```
2026-11-20T11:08:27.620Z Running clue: wait for upgrade time 2026-11-20T11:10:36.330413Z",[o.l.s.i.t.LsuIntegrationTest:LsuIntegrationTest] DEBUG
2026-11-20T11:10:36.330Z Finished clue: wait for upgrade time 2026-11-20T11:10:36.330413Z",[o.l.s.i.t.LsuIntegrationTest:LsuIntegrationTest] DEBUG
2026-11-20T11:10:36.330Z Running clue: Restart sv2 and resume traffic transfer trigger",[o.l.s.i.t.LsuIntegrationTest:LsuIntegrationTest] DEBUG
```

The test sleeps until the upgrade time and restarts sv2 right after, so the restart and the participants'
automatic LSU start together by construction.

```
git show 7b71d35779:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala | sed -n '574,580p'
```
```
      clue(s"wait for upgrade time $upgradeTime") {
        Threading.sleep(Duration.between(Instant.now(), upgradeTime.toInstant).toMillis.abs)
      }

      clue("Restart sv2 and resume traffic transfer trigger") {
        sv2Backend.stop()
        sv2Backend.startSync()
```

## 3. sv2 SV app: the DSO party read fails 47 ms into "joining Dso with key"

Window file: `zcat $T | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] >= "2026-11-20T11:10:20" && a[1] <= "2026-11-20T11:10:37") print}' > log/11682/window.jsonl`.
The synchronizer is registered with `manualConnect = false` (sv2 is fully onboarded), so the tolerance for an
uninitialized store in `isDsoPartyAuthorizedOn` does not apply. The read goes to the logical synchronizer store
(`Id(...)`, not `PhysicalId(...)`).

```
grep -a 'SV=sv2' log/11682/window.jsonl | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] >= "2026-11-20T11:10:36.33") print}' | grep -a -E 'app initialization: (Starting|JoiningNode|Initializ)|Success: participant registered|ListPartyToParticipant.*(sending|failed)' | sed -E "$F" | sed -E 's/(Success: participant registered Synchronizer .global.).*(manualConnect = [a-z]+).*(\[o\.l)/\1 ... \2 ... \3/; s/\\n  Trailers:.*(\[o\.l)/ \1/; s/(sending request ListPartyToParticipantRequest).*(StoreId[^,]*\)\)\)\)).*(\[o\.l)/\1(... \2 ...) \3/' | cut -c1-330
```
```
2026-11-20T11:10:36.680Z sv2 app initialization: Starting initialization",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] INFO
2026-11-20T11:10:36.684Z sv2 app initialization: Initialize app started",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] DEBUG
2026-11-20T11:10:36.734Z sv2 app initialization: Initialize node started",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] DEBUG
2026-11-20T11:10:36.752Z sv2 app initialization: JoiningNodeInitializer joining Dso with key started",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] DEBUG
2026-11-20T11:10:36.764Z Success: participant registered Synchronizer 'global' ... manualConnect = false ... [o.l.s.e.ParticipantAdminConnection:LsuIntegrationTest/config=e21a4d55/SV=sv2] INFO
2026-11-20T11:10:36.788Z Request (tid:c4c41483778845f8ba167863c1ee31c4) com.digitalasset.canton.topology.admin.v30.TopologyManagerReadService/ListPartyToParticipant to 127.0.0.1:5202: sending request ListPartyToParticipantRequest(... StoreId(Synchronizer(Synchronizer(Id(global-domain::..9202)))) ...) [o.l.s.a.a.c.ApiClientReques
2026-11-20T11:10:36.799Z Request (tid:c4c41483778845f8ba167863c1ee31c4) com.digitalasset.canton.topology.admin.v30.TopologyManagerReadService/ListPartyToParticipant to 127.0.0.1:5202: failed with NOT_FOUND/TOPOLOGY_STORE_NOT_FOUND(11,c4c41483): No active synchronizer found for global-domain::12204f082a29.... [o.l.s.a.a.c.ApiClie
2026-11-20T11:10:36.799Z sv2 app initialization: JoiningNodeInitializer joining Dso with key failed",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] INFO
2026-11-20T11:10:36.802Z sv2 app initialization: Initialize node failed",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] INFO
2026-11-20T11:10:36.802Z sv2 app initialization: Initialize app failed",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] INFO
2026-11-20T11:10:36.803Z sv2 app initialization: Initialization failed",[o.l.s.s.SvApp:LsuIntegrationTest/config=e21a4d55/SV=sv2] ERROR
```

## 4. sv2Participant: no active psid for 13 ms during the automatic LSU

`zcat $D/canton.clog.gz | grep -a 'participant=sv2Participant' | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] >= "2026-11-20T11:10:30" && a[1] <= "2026-11-20T11:10:45") print}' > log/11682/sv2p.jsonl`.
The automatic upgrade deactivates 37-0 at 11:10:36.776 and activates 37-2 at 11:10:36.789. The SV app's read (error
id c4c41483, sent 11:10:36.788) is answered at 11:10:36.797 from a lookup made inside that gap; 2 ms later the
upgrade is complete.

```
grep -a -E 'AutomaticLogicalSynchronizerUpgrade|TOPOLOGY_STORE_NOT_FOUND|Setting status of|Disconnected from Synchronizer' log/11682/sv2p.jsonl | grep -a -v ApiRequestLogger | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] >= "2026-11-20T11:10:36.68") print}' | sed -E "$F" | cut -c1-230
```
```
2026-11-20T11:10:36.680Z Upgrade from global-domain::12204f082a29...::37-0 to global-domain::12204f082a29...::37-2 is possible, starting internal upgrade",[c.d.c.p.s.AutomaticLogicalSynchronizerUpgrade:participant=sv2Participant/l
2026-11-20T11:10:36.682Z Running automatic upgrade from global-domain::12204f082a29...::37-0 to global-domain::12204f082a29...::37-2",[c.d.c.p.s.AutomaticLogicalSynchronizerUpgrade:participant=sv2Participant/lsu=37-2] INFO
2026-11-20T11:10:36.774Z Disconnected from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO
2026-11-20T11:10:36.776Z Scheduling automatic-lsu-upgrade",[c.d.c.p.s.AutomaticLogicalSynchronizerUpgrade:participant=sv2Participant/lsu=37-2] INFO
2026-11-20T11:10:36.776Z Marking synchronizer connection global-domain::12204f082a29...::37-0 as inactive",[c.d.c.p.s.AutomaticLogicalSynchronizerUpgrade:participant=sv2Participant/lsu=37-2] INFO
2026-11-20T11:10:36.776Z Setting status of (Synchronizer 'global', global-domain::..9202::37-0) to LSU source",[c.d.c.p.s.d.DbSynchronizerConnectionConfigStore:participant=sv2Participant] INFO
2026-11-20T11:10:36.788Z Marking synchronizer connection global-domain::12204f082a29...::37-2 as active",[c.d.c.p.s.AutomaticLogicalSynchronizerUpgrade:participant=sv2Participant/lsu=37-2] INFO
2026-11-20T11:10:36.789Z Setting status of (Synchronizer 'global', global-domain::..9202::37-2) to Active",[c.d.c.p.s.d.DbSynchronizerConnectionConfigStore:participant=sv2Participant] INFO
2026-11-20T11:10:36.797Z TOPOLOGY_STORE_NOT_FOUND(11,c4c41483): No active synchronizer found for global-domain::12204f082a29....",[c.d.c.t.a.g.GrpcTopologyManagerReadService:participant=sv2Participant] INFO
2026-11-20T11:10:36.799Z Automatic upgrade was successful",[c.d.c.p.s.AutomaticLogicalSynchronizerUpgrade:participant=sv2Participant/lsu=37-2] INFO
```

## 5. Code: the read is not retried, the step before it is

`joinDsoAndOnboardNodes` retries `getPhysicalSynchronizerId` with `RetryFor.WaitingOnInitDependency` and then calls
`isDsoPartyAuthorizedOn` bare. Unchanged between the run sha and origin/main ae237a8ac9
(`git diff --stat 7b71d35779 origin/main -- <both files>` is empty).

```
git show 7b71d35779:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/joining/JoiningNodeInitializer.scala | sed -n '194,207p'
```
```
      psid <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "getPhysicalSynchronizerId",
        "Get the physical synchronizer id in joinDsoAndOnboardNodes",
        participantAdminConnection
          .getPhysicalSynchronizerId(config.domains.global.alias),
        logger,
      )
      decentralizedSynchronizerId = psid.logical
      dsoPartyHosting = newDsoPartyHosting(dsoPartyId)
      dsoPartyIsAuthorized <- dsoPartyHosting.isDsoPartyAuthorizedOn(
        decentralizedSynchronizerId,
        registeredGlobalSync,
        participantId,
```

`isDsoPartyAuthorizedOn` (DsoPartyHosting.scala:42-73) is a plain `listPartyToParticipant` on
`TopologyStoreId.Synchronizer(synchronizerId)`; it only recovers `TopologyStoreNotInitialized` /
`TopologyStoreUnknown` when the synchronizer is registered with `manualConnect=true` (a not yet onboarded SV), which
is false here (section 3). NOT_FOUND is in `RetryProvider.retryableStatusCodes`, so a `retryProvider.retry` around
the call retries it. The other caller, `SV1Initializer` (line 302), already sits inside `retryProvider.waitUntil`.

## 6. Duplicate check

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue TOPOLOGY_STORE_NOT_FOUND' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:150])"'
```
```
#11404 open/- 2026-11-13 LsuIntegrationTest: sv4Validator init reads vetted packages before the participant has the topology store of the new physical synchronizer, exits the 
#10180 closed/not_planned 2026-09-21 logical-sync-upgrade (0) cancelled at the 60 min job timeout: bobValidatorLocal's init read the synchronizer topology store 56 ms before its participa
#8131 closed/completed 2026-04-21 Delete Permissioned synchronizer test from main branch
#8178 closed/duplicate 2026-04-24 UpdateHistoryIntegrationTest: Topology store `global-domain::1220c5c63a57...::34-0` is not known
#8009 closed/duplicate 2026-04-09 PermissionedSynchronizerIntegrationTest: Request failed for remote participant for `sv1Validator`
#8132 closed/completed 2026-04-21 participant disconnects from synchronizer without any indication why
```

- 11404 (open, H2 variant C): same class (an app init topology read on the logical synchronizer store while its
  participant switches psid, NOT_FOUND, sys.exit, cancelled at 60 min). Its confirming grep (the `ListVettedPackages`
  failure between `Setting status of ... 37-2) to Active` and `LSU: About to copy topology`) does not match: here
  the call is `ListPartyToParticipant` from the SV app, the LSU is the automatic one, the error text is `No active
  synchronizer found`, and the step is the sv2 restart, not "sv4 upgrades". Its fix (retry the vetting read in
  `vetDars`) does not cover this call. Not closed as a duplicate of it.
- 10180 (closed not_planned): same error text and the automatic LSU, but bobValidatorLocal's OTK rotation read in
  `NodeInitializer`; closed, so not a parent.
- Not AY (11379: SV init waits on the legacy sequencer, `Total maximum number of retries 120 exceeded`; here a single
  unretried read), not BQ (11673: DomainTimeIngestionTrigger ERROR, tests pass), not AV / AA (log check only).
- No PR on splice touches these reads:
  `gh pr list -R canton-network/splice --state all --search '11404 OR TOPOLOGY_STORE_NOT_FOUND OR "lookupVettingState" OR "isDsoPartyAuthorizedOn"'` prints nothing.

## Verdict

Family H2, variant C, third occurrence (10180 on 2026-09-17, 11404 on 2026-11-13, this on 2026-11-20); new call
site in the SV app. Flake: a 13 ms window, but the test lines the sv2 restart up with the upgrade time, so the SV
app init and the participant's automatic LSU always overlap within a few hundred ms. Real product race: an SV app
that (re)starts while its participant performs an LSU reads the logical synchronizer topology store while no
physical synchronizer is active, and the unretried read ends the app. Not a duplicate of an open issue (section 6);
related to 11404 (same family, other call site).

Fix location: splice app, small. Fix branch `pperek/fix-11682-sv-init-dso-party-read-retry` (off origin/main
ae237a8ac9), commit 6189f34745: `joinDsoAndOnboardNodes` wraps `dsoPartyHosting.isDsoPartyAuthorizedOn` in
`retryProvider.retry(RetryFor.WaitingOnInitDependency, ...)`, like the `getPhysicalSynchronizerId` step above it.
The tolerated manualConnect=true case still returns false at once, because the recover sits inside
`isDsoPartyAuthorizedOn`. `SV1Initializer` is unchanged (already inside `waitUntil`). It is a production change, not
a test-side one.

Verified locally (macOS, no Docker, Postgres 18.4 from the nix store, `./start-canton.sh -d -w -k -p external`):
- `sbt -batch apps-app/Test/compile`: success.
- `sbt -batch "apps-app/testOnly org.lfdecentralizedtrust.splice.integration.tests.LsuIntegrationTest"`, three runs:
  1. Canton started without `-k`: both tests failed in their setup with `KeyDownloadDisabledException` from the
     LsuTrigger (environment error of the run, the suite needs key download).
  2. With `-k`: test 1 passed, test 2 ended in the same step "Restart sv2" with `sv2 app initialization:
     JoiningNodeInitializer joining Dso with key failed`, cause `DEADLINE_EXCEEDED ... after 59.978616917s` on
     `UserManagementService/GrantUserRights` to 127.0.0.1:5201. The fixed read had succeeded before
     (`ListPartyToParticipant ... succeeded(OK)` 12:41:05.457, `DSO party mappings to our participant` 12:41:05.527);
     sv2Participant logged nothing between 12:41 and 12:42:24, load average 42 to 52, 12.9 GB swap used. Read as
     starvation of the 24 GB machine, not as an effect of the change; not proven beyond that.
  3. With `-k`, fresh Postgres and Canton: `Tests: succeeded 2, failed 0`, `All tests passed.`, run 13 min 0 s.

Not verified: no run hit the 13 ms window, so the retry path itself was not seen in a log; the static checks
(`sbt lint`) were not run, only `scalafmtOnly` on the changed file and the pre-commit hooks; the 11404 and 10180
reads are still unwrapped (not in this branch); the Canton side (the participant reports no active psid between
"LSU source" and "Active" instead of answering from either store) was not checked against the 3.7.4 jar; why the
step does not end when the sbt JVM exits in CI was not investigated (family H).
