# 10236 - ScanTimeBasedIntegrationTest "snapshotting": since #6515 a new ACS snapshot is hidden from the snapshot-timestamp endpoints until AcsSnapshotIndexTrigger indexes it; the test reads it in that window and gets None at line 254 (run 36548278932)

NEW, regression from #6515 "Implement Table per ACS Snapshot" (cc4539a9ac, merged 2026-09-29 09:18Z, author
Oriol Munoz / OriolMunoz-da). Also 10237, 10241, 10242 (sections 7-9): 4 of the 5 post-merge runs of this shard
since #6515 failed this way, 0 of the 7 before it. Test-side race: the second `eventually` accepts `None` as
"a snapshot different from snapshot1", so it exits while the new snapshot is saved but not yet indexed.

- Run: https://github.com/canton-network/splice/actions/runs/36548278932, main cc4539a9ac ("Implement Table per ACS
  Snapshot (#6515)"), job 109340147492 `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)`.
- Runtime canton: 3.6.0-snapshot.20260928.20326.0.v5616afeb (`nix/canton-sources.json` at cc4539a9ac; same pin at
  0a2f98714e, ab0ba59509, 802b9faea0).
- Component: test (race exposed by a scan app change).
- 4 tests, 1 failed: `ScanTimeBasedIntegrationTest / snapshotting`.

## 1. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10236/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|ScanTimeBasedIntegrationTest.scala:254\)$' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info]   The Option on which value was invoked was not defined. (ScanTimeBasedIntegrationTest.scala:254)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.ScanTimeBasedIntegrationTest.$anonfun$new$13(ScanTimeBasedIntegrationTest.scala:254)
[info] *** 1 TEST FAILED ***
[info] - snapshotting *** FAILED ***
[info] Run completed in 4 minutes, 50 seconds.
[info] Tests: succeeded 3, failed 1, canceled 0, ignored 0, pending 0
```

## 2. What line 254 reads, and the wait before it

```
git show cc4539a9ac:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanTimeBasedIntegrationTest.scala | sed -n '220,228p;243,255p'
```
```
    val snapshot1 = eventually() {
      val snapshot1 = sv1ScanBackend.getDateOfMostRecentSnapshotBefore(
        getLedgerTime,
        migrationId,
      )
      snapshot1 should not be None
      snapshot1.value.toInstant shouldBe >(startTime.toInstant)
      snapshot1
    }
    val snapshotAfter = eventually() {
      val snapshotAfter = sv1ScanBackend.getDateOfMostRecentSnapshotBefore(
        getLedgerTime,
        migrationId,
      )
      snapshot1 should not(be(snapshotAfter))
      snapshotAfter
    }

    sv1ScanBackend.getDateOfFirstSnapshotAfter(startTime, 0).value shouldBe snapshot1.value
    sv1ScanBackend
      .getDateOfFirstSnapshotAfter(CantonTimestamp.tryFromInstant(snapshot1.value.toInstant), 0)
      .value shouldBe snapshotAfter.value
```

The first wait requires a defined snapshot; the second only requires "not equal to snapshot1", which `None` satisfies.

## 3. What #6515 changed: unindexed snapshots are NotFound, and tests enable per-table snapshots

```
git show cc4539a9ac -- apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/http/HttpScanHandler.scala | grep -n -E '^\+.*(indexesCreated|def notFound|case None)|getDateOf'
git show cc4539a9ac -- apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala | grep -E '^\+' | grep -v '^+++'
```
```
43:+    def notFound = ScanResource.GetDateOfMostRecentSnapshotBeforeResponseNotFound(
47:     withSpan(s"$workflowId.getDateOfMostRecentSnapshotBefore") { _ => _ =>
51:+          case None => notFound
52:+          case Some(snapshot) if !snapshot.indexesCreated => notFound
70:     withSpan(s"$workflowId.getDateOfFirstSnapshotAfter") { _ => _ =>
71:+      def notFound = ScanResource.GetDateOfFirstSnapshotAfterResponseNotFound(
78:+          case None => notFound
79:+          case Some(snapshot) if !snapshot.indexesCreated => notFound
+      withPerAcsSnapshotTablesEnabled,
+  def withPerAcsSnapshotTablesEnabled: ConfigTransform =
+    updateAllScanAppConfigs_(c => c.copy(perAcsSnapshotTablesEnabled = true))
+
```

`lookupSnapshotAtOrBefore` still returns the newest snapshot (`order by snapshot_record_time desc limit 1`, no
`indexes_created` filter), so while the newest is unindexed the "before" endpoint answers 404 rather than falling back
to the older, indexed snapshot. With `perAcsSnapshotTablesEnabled` (on in tests via `ConfigTransforms.defaults`, off
by default in the app) the new `AcsSnapshotIndexTrigger` sets `indexes_created` asynchronously after the snapshot is
saved.

## 4. Timeline: both reads landed in the 0.56 s between save and index of the 06:00 snapshot

```
zcat log/10236/logs-docker-canton-simtime-0/canton_network_test.clog.gz | grep -a -E 'advancing time by PT3H1S|Completed processing with outcome: (Saved incremental snapshot|Successfully indexed tables of snapshot)|state/acs/snapshot-timestamp(-after)? from [^"]*: Responding with entity data|Test failed: .ScanTimeBasedIntegrationTest/snapshotting' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/\[([a-z]\.)+([A-Za-z]+):[^]]*\]/[\2]/; s/HTTP GET \/api\/scan\/v0\/state\/acs\///; s/ from \(127[^)]*\)//; s/Completed processing with outcome: //; s/Responding with entity data: //' \
  | cut -c12-150 | awk '/^09:36:0[6-9]|^09:36:1[5-7]|^09:36:27/' | uniq -c -f1
```
```
      1 09:36:06.955Z advancing time by PT3H1S to 1970-01-01T03:50:31.998Z", [ScanTimeBasedIntegrationTest]
      7 09:36:06.995Z snapshot-timestamp: {\"error\":\"No snapshots found before 1970-01-01T03:50:31.998Z\"}", [HttpRequestLogger]
      1 09:36:08.283Z Saved incremental snapshot at 1970-01-01T03:00:00Z", [AcsSnapshotTrigger]
      1 09:36:08.310Z snapshot-timestamp: {\"error\":\"No snapshots found before 1970-01-01T03:50:31.998Z\"}", [HttpRequestLogger]
      1 09:36:09.231Z Successfully indexed tables of snapshot 1970-01-01T03:00:00Z", [AcsSnapshotIndexTrigger]
      1 09:36:09.599Z snapshot-timestamp: {\"record_time\":\"1970-01-01T03:00:00Z\"}", [HttpRequestLogger]
      1 09:36:15.299Z advancing time by PT3H1S to 1970-01-01T06:50:32.998Z", [ScanTimeBasedIntegrationTest]
      7 09:36:15.388Z snapshot-timestamp: {\"record_time\":\"1970-01-01T03:00:00Z\"}", [HttpRequestLogger]
      1 09:36:16.640Z Saved incremental snapshot at 1970-01-01T06:00:00Z", [AcsSnapshotTrigger]
      1 09:36:16.696Z snapshot-timestamp: {\"error\":\"No snapshots found before 1970-01-01T06:50:32.998Z\"}", [HttpRequestLogger]
      1 09:36:16.710Z snapshot-timestamp-after: {\"record_time\":\"1970-01-01T03:00:00Z\"}", [HttpRequestLogger]
      1 09:36:16.716Z snapshot-timestamp-after: {\"error\":\"No snapshots found after 1970-01-01T03:00Z\"}", [HttpRequestLogger]
      1 09:36:17.198Z Successfully indexed tables of snapshot 1970-01-01T06:00:00Z", [AcsSnapshotIndexTrigger]
      1 09:36:27.887Z Test failed: 'ScanTimeBasedIntegrationTest/snapshotting', message: The Option on which value was invoked was not defined., lo
```

- First wait: 404 until snapshot 03:00 was indexed (09:36:09.231), then 03:00 = snapshot1. Correct.
- Second wait: 7 polls return 03:00 (equal to snapshot1, retried). The 06:00 snapshot is saved at 09:36:16.640; the
  poll at 09:36:16.696 gets 404 (newest unindexed, no fallback), `snapshotAfter = None`, which is "not snapshot1", so
  the wait exits.
- Line 252 (`after startTime`) returns 03:00 (09:36:16.710); line 254 (`after 03:00`) gets 404 at 09:36:16.716, since
  the only later snapshot is still unindexed; `.value` on None fails. The index lands 0.48 s later, at 09:36:17.198.

## 5. The shard passed on every main run before #6515 and failed on 4 of 5 after

```
gh run list --repo canton-network/splice --branch main --limit 60 --json databaseId,headSha,createdAt,conclusion,status,workflowName --jq '.[] | "\(.databaseId) \(.headSha[0:10]) \(.createdAt) \(.status)/\(.conclusion) \(.workflowName[0:30])"' | grep -i 'post-merge' | awk '$3 > "2026-09-29T07"' > log/10236/runs.txt
while read id sha c st w; do j=$(gh run view $id --repo canton-network/splice --json jobs --jq '[.jobs[] | select(.name|test("docker-canton-simtime .0.")) | (.conclusion // .status)] | join(",")'); echo "$id $sha $c ${j:-none}"; done < log/10236/runs.txt
for s in 5af1f5a467 0a2f98714e ab0ba59509 802b9faea0 58a8ad7324; do git merge-base --is-ancestor cc4539a9ac $s && echo "$s contains #6515" || echo "$s lacks #6515"; done
```
```
36562304394 802b9faea0 2026-09-29T11:31:46Z failure
36562281828 5af1f5a467 2026-09-29T11:31:32Z success
36562243359 ab0ba59509 2026-09-29T11:31:10Z failure
36548918726 0a2f98714e 2026-09-29T09:24:03Z failure
36548278932 cc4539a9ac 2026-09-29T09:18:08Z failure
36544890026 58a8ad7324 2026-09-29T08:46:47Z success
36544400923 113cb63c44 2026-09-29T08:42:12Z success
36544106177 e5b10c8d8f 2026-09-29T08:39:27Z success
36543300478 92fbd50a20 2026-09-29T08:31:52Z success
36541322662 95e122223c 2026-09-29T08:12:55Z success
36536754273 9dd73aad3b 2026-09-29T07:27:40Z success
5af1f5a467 contains #6515
0a2f98714e contains #6515
ab0ba59509 contains #6515
802b9faea0 contains #6515
58a8ad7324 lacks #6515
```

58a8ad7324 is cc4539a9ac's parent. The one pass after #6515 (5af1f5a467) fits a timing race (window 0.1-0.9 s in the
four failures, section 7-9); its log was not downloaded.

## 6. Fix

Test-side, branch `s11/fix-10236-scan-snapshot-wait-for-index` (7ae2e53dd4, off origin/main 802b9faea0): the
second wait also requires a defined snapshot, like the first.

```
git -C <fix worktree> show 7ae2e53dd4 --stat --format='%h %s'
git -C <fix worktree> show 7ae2e53dd4 | grep -E '^[+-] '
```
```
7ae2e53dd4 [ci] Wait until ScanTimeBasedIntegrationTest's second ACS snapshot is indexed before reading it

 .../splice/integration/tests/ScanTimeBasedIntegrationTest.scala          | 1 +
 1 file changed, 1 insertion(+)
+      snapshotAfter should not be None
```

`getDateOfMostRecentSnapshotBefore` only returns a snapshot once it is indexed, so once the wait sees 06:00, the
`after 03:00` lookup at line 254 also sees it (it is the only snapshot after 03:00). Verified here:
`apps-app/Test/compile` and `apps-app/Test/scalafmtCheck` pass on 7ae2e53dd4 (log/10236/compile-fix.log). NOT run: the
shard needs the docker-based Canton, and a pass would not prove much for a sub-second race.

App-side, for the #6515 owner (described, not written): while the newest snapshot is unindexed,
`getDateOfMostRecentSnapshotBefore` answers 404 even though an older indexed snapshot exists (03:00 above), so a
client polling it sees snapshots disappear for up to ~1 s after every save. Filtering `indexes_created` in the SQL of
the HTTP lookups (not in `lookupSnapshotAfter` itself, which `AcsSnapshotBulkStorageWriterFromDb` also uses) would
fall back to the newest indexed snapshot instead. Only matters with `perAcsSnapshotTablesEnabled`, which is off by
default ("should NOT yet be enabled" in production, #6515 description).

## 7. 10237 (run 36548918726, main 0a2f98714e, job 109342193703 `docker-canton-simtime (0)`) - same race

```
zcat log/10237/logs-docker-canton-simtime-0/canton_network_test.clog.gz | grep -a -E 'Completed processing with outcome: (Saved incremental snapshot at 1970-01-01T06:00:00Z|Successfully indexed tables of snapshot 1970-01-01T06:00:00Z)|snapshot-timestamp(-after)? from [^"]*: Responding with entity data: \{\\"error\\":\\"No snapshots found (before 1970-01-01T06|after)' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | sed -E 's/\[([a-z]\.)+([A-Za-z]+):[^]]*\]/[\2]/; s/HTTP GET \/api\/scan\/v0\/state\/acs\///; s/ from \(127[^)]*\)//; s/Completed processing with outcome: //; s/Responding with entity data: //' | cut -c12-150
```
```
09:41:59.696Z Saved incremental snapshot at 1970-01-01T06:00:00Z", [AcsSnapshotTrigger]
09:41:59.906Z snapshot-timestamp: {\"error\":\"No snapshots found before 1970-01-01T06:50:32.998Z\"}", [HttpRequestLogger]
09:41:59.921Z snapshot-timestamp-after: {\"error\":\"No snapshots found after 1970-01-01T03:00Z\"}", [HttpRequestLogger]
09:42:00.011Z Successfully indexed tables of snapshot 1970-01-01T06:00:00Z", [AcsSnapshotIndexTrigger]
```
Same failure line (`ScanTimeBasedIntegrationTest.scala:254`), 4 tests, 1 failed. Window 0.32 s.

## 8. 10241 (run 36562243359, main ab0ba59509, job 109385885359 `docker-canton-simtime (0)`) - same race

Same command with `log/10241/...`:
```
11:47:22.653Z Saved incremental snapshot at 1970-01-01T06:00:00Z", [AcsSnapshotTrigger]
11:47:22.735Z snapshot-timestamp: {\"error\":\"No snapshots found before 1970-01-01T06:50:32.998Z\"}", [HttpRequestLogger]
11:47:22.752Z snapshot-timestamp-after: {\"error\":\"No snapshots found after 1970-01-01T03:00Z\"}", [HttpRequestLogger]
11:47:23.513Z Successfully indexed tables of snapshot 1970-01-01T06:00:00Z", [AcsSnapshotIndexTrigger]
```
Window 0.86 s.

## 9. 10242 (run 36562304394, main 802b9faea0, job 109386185648 `docker-canton-simtime (0)`) - same race

Same command with `log/10242/...`:
```
11:48:44.068Z Saved incremental snapshot at 1970-01-01T06:00:00Z", [AcsSnapshotTrigger]
11:48:44.945Z snapshot-timestamp: {\"error\":\"No snapshots found before 1970-01-01T06:50:32.998Z\"}", [HttpRequestLogger]
11:48:44.962Z snapshot-timestamp-after: {\"error\":\"No snapshots found after 1970-01-01T03:00Z\"}", [HttpRequestLogger]
11:48:45.091Z Successfully indexed tables of snapshot 1970-01-01T06:00:00Z", [AcsSnapshotIndexTrigger]
```
Window 1.02 s.

## Verdict

- 10236 new (regression of #6515); 10237, 10241, 10242 duplicates of 10236.
- Flake by mechanism (sub-second race), but a near-certain one since #6515 (4 of 5 runs). Test-side fix on
  `s11/fix-10236-scan-snapshot-wait-for-index` (7ae2e53dd4), compiled and scalafmt-checked, not run. App-side
  fallback to the newest indexed snapshot described for the #6515 author (OriolMunoz-da).
- Not verified: the fixed test passing in the docker-canton-simtime shard; whether other scan clients (UI, bulk
  storage) poll these endpoints right after a save in other suites (only ScanTimeBasedIntegrationTest failed); the
  11 s between the 404 at 09:36:16.716 and the "Test failed" report at 09:36:27.887 (not investigated).
