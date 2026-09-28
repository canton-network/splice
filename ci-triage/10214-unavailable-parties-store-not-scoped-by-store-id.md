# 10214 - AutoIgnoreUnresponsiveParties sees the previous suite's ignored party because listParties is not scoped by store_id (run 35876878745)

New, not a duplicate. `AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest` / "A party that became
available again is removed from the store" asserts `ignoredParties shouldBe empty` and fails with the alice
party of the suite that ran immediately before it in the same shard,
`ExpiryWithNoVettedAmuletVersionIntegrationTest`. The feature under test worked correctly: the suite's own
party was recovered and deleted 28 s before the deadline. The failure is entirely a stale row.
`DbUnavailablePartiesStore` writes a `store_id` on every insert and offers `removePartiesUpToStoreId`, but
`listPartiesAt` selects from `dso_unavailable_parties` with no `store_id` predicate, so an SV app sees rows
written by every other store that shares the database. The previous suite writes its row with the default
10-minute base ignore duration, so it stays visible for the whole of the next suite.

This is not a flake. The shard's suite order is fixed by the split, the two suites are adjacent in it, and the
assertion lands 3 min 14 s into a 10-minute window, so it fails every time these two suites are co-located.

- Run: https://github.com/canton-network/splice/actions/runs/35876878745, `main` 95dc17d3d9
  ("Automatic unavailable parties unignore (#7439)"), job 107236070917
  `ci / scala_test_wall_clock_time / wall-clock-time (7)`.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824.
- Component: splice app (`apps/common` store read path). The test is correct as written.

All commands are run from the repo root with the artifact in `log/10214/logs-wall-clock-time-7/`; long
hashes are trimmed by a sed baked into each command. Source is read at the run's sha, never from the
working tree (this branch is older than the run).

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10214/job.log \
  | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed in' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info] *** 1 TEST FAILED ***
[info] - A party that became available again is removed from the store *** FAILED ***
[info] Run completed in 20 minutes, 20 seconds.
[info] Tests: succeeded 53, failed 1, canceled 0, ignored 0, pending 0
```

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10214/job.log | grep -a -A8 'FAILED \*\*\*' \
  | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | head -8
```
```
[info] - A party that became available again is removed from the store *** FAILED ***
[info]   Vector(alice__wallet__user-9ad8328b::122057390c3d...) was not empty (AutoIgnoreUnresponsivePartiesIntegrationTest.scala:298)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
[info]   at org.scalatest.matchers.should.Matchers$AnyShouldWrapper.shouldBe(Matchers.scala:7770)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest.$anonfun$new$28(AutoIgnoreUnresponsivePartiesIntegrationTest.scala:298)
```

The assertion, at the run's sha:

```
git show 95dc17d3d9:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/AutoIgnoreUnresponsivePartiesIntegrationTest.scala \
  | sed -n '290,300p'
```
```
  "A party that became available again is removed from the store" in { implicit env =>
    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
      {
        clue("Reconnect alice's participant so that the next retry succeeds") {
          aliceValidatorBackend.participantClient.synchronizers.reconnect_all()
        }
        clue("Alice is removed from the store once the expiry submission succeeds") {
          eventually(timeUntilSuccess = 60.seconds) {
            ignoredParties shouldBe empty
```

`ignoredParties` reads the SV app's store directly (same file, lines 60-63):
`sv1Backend.dsoDelegateBasedAutomation.unavailablePartiesStore.listParties()(TraceContext.empty).futureValue`.

## 2. The party in the failure is not this suite's party

The party id carries the test config id. The failing suite's config is `579f135f`; the party reported by the
assertion carries `9ad8328b`.

```
zcat log/10214/logs-wall-clock-time-7/canton_network_test.clog.gz \
  | grep -aoE '"logger_name":"[^"]*IntegrationTest/config=[0-9a-f]{8}' \
  | sed -E 's/.*:([A-Za-z0-9]+IntegrationTest)\/config=([0-9a-f]{8})/\2 \1/' | sort -u
```
```
1bb4b4bc BftScanConnectionIntegrationTest
209700e0 SvOnboardingAddlIntegrationTest
3701f37f BftScanConnectionIntegrationTest
507ef648 DowngradeSvPackagesIntegrationTest
579f135f AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest
6c57cf58 BftScanConnectionIntegrationTest
7da7c937 SvOnboardingAddlIntegrationTest
9ad8328b ExpiryWithNoVettedAmuletVersionIntegrationTest
a9241c49 WalletTxLogIntegrationTest
bd2f45f2 WalletPaymentIntegrationTest
cd21b619 SvOnboardingAddlIntegrationTest
ec12bb4a AutomationServiceIntegrationTest
f13bfd3e BftScanConnectionIntegrationTest
f1eb67f8 WalletIntegrationTest
```

`9ad8328b` is `ExpiryWithNoVettedAmuletVersionIntegrationTest`. The two suites are adjacent in the shard's
sbt invocation (from the job log's `cmd:` line, shard index 7):

```
... ExpiryWithNoVettedAmuletVersionIntegrationTest
    AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest
    WalletPaymentIntegrationTest ...
```

## 3. Suite timeline: the previous suite hands over a populated store

```
zcat log/10214/logs-wall-clock-time-7/canton_network_test.clog.gz \
  | grep -aE "Starting test suite '|Test (succeeded|failed): '" | python3 -c "
import sys,json
for l in sys.stdin:
    d=json.loads(l); m=d['message']
    if 'ExpiryWithNoVetted' in m or 'AutoIgnoreUnresponsivePartiesWithPersistence' in m:
        print(d['@timestamp'],'|',m[:140].replace('\n',' '))
"
```
```
2026-09-23T15:15:14.049Z | Starting test suite 'ExpiryWithNoVettedAmuletVersionIntegrationTest'...
2026-09-23T15:16:22.283Z | Test succeeded: 'ExpiryWithNoVettedAmuletVersionIntegrationTest/Amulet expiry ignores parties with no vetted amulet version'
2026-09-23T15:16:22.289Z | Starting test suite 'AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest'...
2026-09-23T15:17:53.113Z | Test succeeded: 'AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest/Expiry triggers auto-ignore parties whose participant is disconnected (ME
2026-09-23T15:17:55.051Z | Test succeeded: 'AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest/Ignored parties survive an SV app restart'
2026-09-23T15:18:35.173Z | Test succeeded: 'AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest/A party that is still unavailable is retried and ignored again with a dou
2026-09-23T15:19:35.901Z | Test failed: 'AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest/A party that became available again is removed from the store', message: Vec
```

The previous suite's passing assertion is what puts the row there. At the run's sha,
`ExpiryWithNoVettedAmuletVersionIntegrationTest` runs with persistence on and asserts the party is present,
and it never removes it:

```
git show 95dc17d3d9:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ExpiryWithMinimalVettedPackagesIntegrationTest.scala \
  | sed -n '386,390p;424,432p'
```
```
class ExpiryWithNoVettedAmuletVersionIntegrationTest
    extends ExpiryWithMinimalVettedPackagesIntegrationTestBase {

  override protected val enablePersistedUnavailableParties: Boolean = true

      "Alice is ignored and her dust amulets are not expired",
      _ => {
        val ignored = sv1Backend.dsoDelegateBasedAutomation.unavailablePartiesStore
          .listParties()(TraceContext.empty)
          .futureValue
        ignored should contain(alice)
        ignored should not contain dsoParty
        aliceWalletClient.list().amulets should have length 2L withClue "dust amulets"
      },
```

## 4. Every write to the table in this shard

The store logs each insert itself. There are exactly five, and only the first belongs to the previous suite:

```
zcat log/10214/logs-wall-clock-time-7/canton_network_test.clog.gz \
  | grep -a 'DbUnavailablePartiesStore' | python3 -c "
import sys,json
for l in sys.stdin:
    d=json.loads(l)
    print(d['@timestamp'], d.get('logger_name','')[:56], '|', d['message'][:80])
"
```
```
2026-09-23T15:16:21.810Z o.l.s.s.d.DbUnavailablePartiesStore:ExpiryWithNoVetted | Marking 1 parties as unavailable at 1790176581810479
2026-09-23T15:17:49.131Z o.l.s.s.d.DbUnavailablePartiesStore:AutoIgnoreUnrespon | Marking 1 parties as unavailable at 1790176669131898
2026-09-23T15:17:49.220Z o.l.s.s.d.DbUnavailablePartiesStore:AutoIgnoreUnrespon | Marking 1 parties as unavailable at 1790176669220853
2026-09-23T15:18:34.789Z o.l.s.s.d.DbUnavailablePartiesStore:AutoIgnoreUnrespon | Marking 1 parties as unavailable at 1790176714789215
2026-09-23T15:18:35.598Z o.l.s.s.d.DbUnavailablePartiesStore:AutoIgnoreUnrespon | Marking 1 parties as unavailable at 1790176715598347
```

Every party the failing suite itself ignored is its own, never `9ad8328b`:

```
zcat log/10214/logs-wall-clock-time-7/canton_network_test.clog.gz \
  | grep -aE 'added [0-9]+ to ignore list|No vetted Amulet version' \
  | sed -E 's/"logger_name":"([^"]*)".*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{56}([0-9a-f]{4})/..\1/g' \
  | cut -c1-200
```
```
2026-09-23T15:17:49.133Z Completed processing with outcome: Batch failed due to unresponsive parties, added 1 to ignore list: Set(alice__wallet__user-579f135f__tc0::122057390c3d...)", [o.l.s.s.a.d.Exp
2026-09-23T15:17:49.222Z Completed processing with outcome: Batch failed due to unresponsive parties, added 1 to ignore list: Set(alice__wallet__user-579f135f__tc0::122057390c3d...)", [o.l.s.s.a.d.Exp
2026-09-23T15:18:34.790Z Completed processing with outcome: Batch failed due to unresponsive parties, added 1 to ignore list: Set(alice__wallet__user-579f135f__tc0::122057390c3d...)", [o.l.s.s.a.d.Exp
2026-09-23T15:18:35.600Z Suppressed INFO: Completed processing with outcome: Batch failed due to unresponsive parties, added 1 to ignore list: Set(alice__wallet__user-579f135f__tc0::122057390c3d...)",
```

## 5. The feature under test worked: this suite's own party was recovered

```
zcat log/10214/logs-wall-clock-time-7/canton_network_test.clog.gz \
  | grep -a 'unavailable parties' | sed -E 's/"logger_name":"([^"]*)".*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{56}([0-9a-f]{4})/..\1/g' \
  | cut -c1-210
```
```
2026-09-23T15:01:00.578Z Migrating schema \"public\" to version \"073 - dso unavailable parties\"", [o.f.c.i.command.DbMigrate]
2026-09-23T15:19:07.920Z Suppressed INFO: Submission succeeded, recovered 1 unavailable parties: Vector(alice__wallet__user-579f135f__tc0::122057390c3d...)", [o.l.s.s.a.d.ExpiredAmuletTrigger:AutoIgnoreUnrespon
```

The first line settles the shared-database question. The shard's first suite starts at 15:00:54.537
(`BftScanConnectionIntegrationTest`) and the `dso_unavailable_parties` schema is migrated once during it, at
15:01:00.578. That migration line occurs exactly once in the whole log
(`zcat ... | grep -ac '073 - dso unavailable parties'` returns `1`), while nine suites run over the next
20 minutes. The database is created once per shard and is not recreated between suites.

The reconnect ran at 15:18:35.174-35.894, the recovery landed at 15:19:07.920, and the deadline was
15:19:35.895. The log line the test also asserts on (`assertEventuallyLogsSeq`) was produced. The only reason
the test failed is that `listParties()` still returned the other suite's row:

```
zcat log/10214/logs-wall-clock-time-7/canton_network_test.clog.gz \
  | grep -aE '"(Running|Finished|Failed) clue' | python3 -c "
import sys,json
for l in sys.stdin:
    d=json.loads(l)
    if not d['@timestamp'].startswith('2026-09-23T15:18') and not d['@timestamp'].startswith('2026-09-23T15:19'): continue
    if 'AutoIgnoreUnresponsivePartiesWithPersistence' not in d.get('logger_name',''): continue
    print(d['@timestamp'],'|',d['message'][:90])
"
```
```
2026-09-23T15:18:35.174Z | Running clue: Reconnect alice's participant so that the next retry succeeds
2026-09-23T15:18:35.894Z | Finished clue: Reconnect alice's participant so that the next retry succeeds
2026-09-23T15:18:35.894Z | Running clue: Alice is removed from the store once the expiry submission succeeds
2026-09-23T15:19:35.895Z | Failed clue: Alice is removed from the store once the expiry submission succeeds
```

## 6. Why the stale row is visible: no store_id predicate on the read

```
git show 95dc17d3d9:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/store/db/DbUnavailablePartiesStore.scala \
  | sed -n '59,72p;92,108p'
```
```
        .update(
          sql"""insert into dso_unavailable_parties
                  (party, updated_at, ignore_duration, store_id)
                select u.party, $nowMicros, $baseMicros, $storeId
                from unnest($partyArray) as u(party)
                on conflict (party) do update
                  set updated_at = excluded.updated_at,
                      ignore_duration = least(
                            dso_unavailable_parties.ignore_duration * 2,
                            $maxMicros)
                  where dso_unavailable_parties.updated_at + dso_unavailable_parties.ignore_duration <= excluded.updated_at
             """.asUpdate,
          "addParties",
        )
  // Removes parties from the table with matching store ID.
  def removePartiesUpToStoreId(maxStoreId: Long)(implicit tc: TraceContext): Future[Int] =
    storage.update(
      sqlu"""delete from dso_unavailable_parties where store_id <= $maxStoreId""",
      "removePartiesUpToStoreId",
    )

  // List all parties for which updated_at + ignore_duration > nowMicros.
  private[splice] def listPartiesAt(nowMicros: Long)(implicit
      tc: TraceContext
  ): Future[Seq[PartyId]] =
    storage.query(
      sql"""select party
            from dso_unavailable_parties
            where updated_at + ignore_duration > $nowMicros""".as[PartyId],
      "listParties",
    )
```

`store_id` is written on insert and is the discriminator used by `removePartiesUpToStoreId`, but neither
`listPartiesAt` nor `removeParties` (which deletes `where party = any(...)`) constrains it. The store id comes
from a descriptor that is distinct per SV app identity:

```
git show 95dc17d3d9:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/NodeInitializerUtil.scala \
  | sed -n '209,218p'
```
```
        DbUnavailablePartiesStore(
          StoreDescriptor(
            version = 1,
            name = "DbUnavailablePartiesStore",
            party = dsoStore.key.dsoParty,
            participant = participantId,
            key = Map("svParty" -> dsoStore.key.svParty.toProtoPrimitive),
          ),
```

The two suites bootstrap different DSO parties (`DSO-9ad8328b-9ad8328b::...` and `DSO-579f135f-...` appear in
their respective `SubmitAndWaitTransaction` lines), so they allocate different store ids against the same
`dso_unavailable_parties` table.

## 7. Why the row was still live 3 minutes later

`listPartiesAt` only hides a row once `updated_at + ignore_duration` has passed. The previous suite does not
override the backoff parameters, so it uses the defaults:

```
git show 95dc17d3d9:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala \
  | sed -n '736,740p'
```
```
final case class UnavailablePartiesBackoffParameters(
    baseIgnoreDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
    // 24h: 100k parties with 1 task each leads to 100k / (24*3600s) = 1.15 tasks/s
    maxIgnoreDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofHours(24),
)
```

(The failing suite overrides them to 15 s / 30 s, but the stale row was written by the other suite and carries
the other suite's 10 minutes.)

```
python3 -c "
import datetime
add=1790176581810479
exp=add+10*60*1000000
f=lambda u: datetime.datetime.fromtimestamp(u/1e6, datetime.UTC).isoformat()
fail=datetime.datetime(2026,9,23,15,19,35,895000,tzinfo=datetime.UTC)
print('row written at              :', f(add))
print('visible to listParties until:', f(exp))
print('assertion failed at         :', fail.isoformat())
print('margin remaining at failure :', round((exp-fail.timestamp()*1e6)/1e6,1),'s')
"
```
```
row written at              : 2026-09-23T15:16:21.810479+00:00
visible to listParties until: 2026-09-23T15:26:21.810479+00:00
assertion failed at         : 2026-09-23T15:19:35.895000+00:00
margin remaining at failure : 405.9 s
```

The failing suite runs entirely inside the previous suite's window, with 6.8 minutes to spare.

## 8. Timeline

```
15:15:14.049  ExpiryWithNoVettedAmuletVersionIntegrationTest starts (config 9ad8328b, persistence on)
15:16:21.810  its store writes alice__wallet__user-9ad8328b, ignore_duration 10 min (default)
15:16:22.283  that suite's "ignored should contain(alice)" passes; suite ends, table not cleaned
15:16:22.289  AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest starts (config 579f135f)
15:17:49.131  its own alice (579f135f__tc0) added to the same table
15:18:34.789  re-added, ignore duration doubled (test 3 passes)
15:18:35.174  test 4: alice's participant reconnected
15:19:07.920  "Submission succeeded, recovered 1 unavailable parties: ...579f135f__tc0" - feature works
15:19:35.895  ignoredParties shouldBe empty fails: listParties returns the 9ad8328b row
15:26:21.810  the stale row would finally have aged out
```

## Verdict

New. Not a duplicate of any catalogue family, and not a Canton issue.

Not a flake. The shard's suite order is fixed by the split, `ExpiryWithNoVettedAmuletVersionIntegrationTest`
runs immediately before the failing suite, and the assertion lands with 6.8 minutes of the stale row's window
still to run. Expect this to fail on every run whose shard puts these two suites together.

The defect is in production code, in the read path of the persisted store:
`DbUnavailablePartiesStore.listPartiesAt` selects from `dso_unavailable_parties` without a `store_id`
predicate, so an SV app is shown parties ignored by any other store sharing the database. `removeParties`
has the same gap in the other direction: it deletes `where party = any(...)`, so a recovery in one store can
delete another store's row for the same party id. `store_id` is already written on insert and is already used
by `removePartiesUpToStoreId`, so the column exists for exactly this purpose and is simply not applied on
these two paths.

`listPartiesAt` was not touched by #7439 (`git show 95dc17d3d9 -- .../DbUnavailablePartiesStore.scala` changes
only `removeParties`), so the unscoped read predates the PR; #7439 added the first assertion
(`ignoredParties shouldBe empty`) that is sensitive to it. The earlier tests in the suite use `contain` and
`not be empty`, which a stale row does not disturb, which is why only this one test fails.

Still present on main: no commit between 95dc17d3d9 and origin/main (5eb871da28) touches the store, the guard
or either test file, and `listPartiesAt` on the main tip is unchanged.

Fix described, NOT written, because it is production code (conventions: production changes are described and
left to the owner):

- Add `and store_id = $storeId` to `listPartiesAt`, and scope `removeParties` the same way. This makes the
  test correct as written and removes the cross-store interference in production, where any redeployment that
  reuses a database under a new DSO or SV identity would hit the same thing.
- A test-side change (asserting `should not contain unresponsiveParty` instead of `shouldBe empty`, or
  cleaning the table between suites) would make CI green while leaving the unscoped read in place. Rejected on
  the "root cause, not symptom" rule; the assertion as written is the one that caught a real bug.

NOT verified:

- The test configuration that assigns the database was not read; the shared-database conclusion rests on the
  single `073 - dso unavailable parties` migration for the whole shard (section 5) plus the behavioural
  evidence that the failing suite's `listParties()` returned a party only the other suite's store ever wrote
  (section 4).
- The numeric `store_id` values of the two stores are not logged, so "different store ids" rests on the
  descriptor inputs (DSO party, participant, sv party) differing between suites, which the logs do show.
- Whether other shards or runs hit this. Only this job was examined; the prediction that it is deterministic
  for this shard composition was not tested against another run.
- Nothing was compiled or run.
