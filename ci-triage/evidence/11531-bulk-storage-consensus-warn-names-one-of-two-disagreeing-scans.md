# 11531 - BulkStorageCommitFromStagingTest: final disagreement WARN names scan_6 only, test expects scan_5 and scan_6 (run 44021473992)

DUPLICATE of 10737 (family I, test race). main dbf2ab6c8f, scheduled workflow "Integration tests against mainnet
Daml version", job `docker-no-canton (0)`: 78 tests passed, 1 failed, no checkErrors findings. The failing test
(`BulkStorageCommitFromStagingTest`, "wait until all objects are known to the peers, and report disagreement on
consensus correctly") asserts in its last step that one WARN names both disagreeing scans, scan_5 and scan_6. Seven
mocked scans have data, the BFT call uses the first five that answer `Available`, and in this run scan_5 was not among
those five, so the WARN named scan_6 only. The fix is the open, approved splice PR #8083 (test-only, not on main).
10737 was closed as completed on 2026-10-28 when an unrelated PR's rerun passed; nothing was merged for it.

- Run: https://github.com/canton-network/splice/actions/runs/44021473992, main dbf2ab6c8f ("Pause the reward triggers
  in ScanNewNetworkBackfillingTimeBasedIntegrationTest (#8420)"), job 133832631842
  `build / scala_test_with_docker_no_canton / docker-no-canton (0)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (not involved: the suite is a unit test with mocked scans
  and an s3mock container).
- Component: test.
- Other failed jobs of the run, not mapped to this ref: `simtime (1)` 133832315070, `wall-clock-time (3)` 133832627487.

## 1. Failing assertion

The job has one failed test; the clue is the last `assertLogsSeqStep` of the test (lines 339-344 at dbf2ab6c8f).

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/133832631842/logs > log/11531/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11531/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - should wait until all objects are known to the peers, and report disagreement on consensus correctly *** FAILED ***
[info] - wait until all objects are known to the peers, and report disagreement on consensus correctly *** FAILED ***
[info] All tests passed.
[info] Run completed in 13 minutes, 6 seconds.
[info] Run completed in 4 minutes, 7 seconds.
[info] Tests: succeeded 4, failed 0, canceled 0, ignored 0, pending 0
[info] Tests: succeeded 78, failed 1, canceled 0, ignored 0, pending 0
```

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11531/job.log | grep -a -A12 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | cut -c1-330 | sed -n 1,12p
```
```
[info] - should wait until all objects are known to the peers, and report disagreement on consensus correctly *** FAILED ***
[info]   forAtLeast(1) failed, because no element satisfied the assertion block: 
[info]     at index 0, DEBUG was not equal to WARN (BulkStorageCommitFromStagingTest.scala:341), 
[info]     at index 1, DEBUG was not equal to WARN (BulkStorageCommitFromStagingTest.scala:341), 
[info]     at index 2, DEBUG was not equal to WARN (BulkStorageCommitFromStagingTest.scala:341), 
[info]     at index 3, DEBUG was not equal to WARN (BulkStorageCommitFromStagingTest.scala:341), 
[info]     at index 4, DEBUG was not equal to WARN (BulkStorageCommitFromStagingTest.scala:341), 
[info]     at index 5, DEBUG was not equal to WARN (BulkStorageCommitFromStagingTest.scala:341), 
[info]     at index 6, "The following Scan URLs disagreed with consensus:
[info]       http://scan_6
[info]     consensus response: Success(GetBulkObjectChecksumsResponse(Vector(Checksums(Some(RFAIt/KTKSK9sYR3HZl4UWpPiddwAMLW6rGLCJSqw6c=)), Checksums(Some(q6+kQovcjDTa4ou8FzA6YhdfJ07fWXV7PpiYIVpCilY=)), Checksums(Some(Cm2VV5uj3S95yHCQb9iUAHzkSQINER01iJTPu82aA6Q=)))))
[info]     disagreeing response: SuccessfulResponse(GetBulkObjectChecksumsResponse(Vector(Checksums(Some(RFAIt/KTKSK9sYR3HZl4UWpPiddwAMLW6rGLCJSqw6c=)), Checksums(Some(wrong-digest)), Checksums(Some(Cm2VV5uj3S95yHCQb9iUAHzkSQINER01iJTPu82aA6Q=)))))" included substring "The following Scan URLs disagreed with consensus", but "The following Scan URLs disagreed with consensus:
```
The entry ends with `did not include substring "scan_5" (BulkStorageCommitFromStagingTest.scala:342)`.

The assertion at the run's sha:

```
git show dbf2ab6c8f:apps/scan/src/test/scala/org/lfdecentralizedtrust/splice/scan/store/bulk/BulkStorageCommitFromStagingTest.scala | sed -n 331,346p
```
```
          assertLogsSeqStep(
            clue(
              "Enough scans do agree - the copy flow should complete successfully, but still warn about those that did not agree"
            ) {
              Seq.range(2, 5).foreach(i => mockScanConnections.scanAgrees(i))
              sub.expectNext(5.seconds, "go")
              assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
            },
            (logEntries: Seq[LogEntry]) =>
              forAtLeast(1, logEntries)(entry => {
                entry.level shouldBe Level.WARN
                entry.message should (include(
                  "The following Scan URLs disagreed with consensus"
                ) and include("scan_5") and include("scan_6"))
              }),
          ),
```

## 2. Duplicate check

Not in `known-families.md` before this packet (only 10149, an older Mockito stubbing race of the same suite). The
tracker has the same test with the same clue, mirrored (WARN names scan_5 only):

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "report disagreement on consensus"' --jq '.items[]|"\(.number) \(.state) \(.state_reason) \(.created_at) \(.title)"'
```
```
10737 closed completed 2026-10-28T12:51:53Z BulkStorageCommitFromStagingTest: should wait until all objects are known to the peers, and report disagreement on consensus correctly *** FAILED
10149 closed completed 2026-09-16T12:21:57Z BulkStorageCommitFromStagingTest hangs after a Mockito stubbing race (run 35092848061)
```

10737 (run 40619618938, PR branch `is/scan-lock-transfer`, 2026-10-28) body: `at index 6, "The following Scan URLs
disagreed with consensus:\n http://scan_5\n consensus response: ...` at the same line 341. Its closing comment
(2026-10-28T19:40:00Z): "closing. https://github.com/canton-network/splice/pull/8056 auto-merged after successful CI
run". #8056 is the PR the failure happened on, not a fix.

## 3. Mechanism: five of seven scans with data are used, whichever answer first

The run's own selection lines, from test start (05:32:23.695) to the failure (05:32:43.246). The confirming grep for
this family is the `Found enough scans` line right before the last WARN: it lacks scan_5 or scan_6.

```
zcat log/11531/logs-docker-no-canton-0/canton_network_test.clog.gz | grep -a 'BulkStorageCommitFromStagingTest' | grep -a -E 'Found enough scans with available data|disagreed with consensus|Test failed' | awk -F'"' '$4 >= "2026-11-17T05:32:41" && $4 <= "2026-11-17T05:32:43.3"' | sed -E 's/","logger_name.*//; s/\\nconsensus response.*//; s/, message: forAtLeast.*//; s/^\{"@timestamp":"//; s/","message":"/ /'
```
```
2026-11-17T05:32:41.170Z Suppressed DEBUG: Found enough scans with available data: List(http://scan_0, http://scan_5, http://scan_6, http://scan_4, http://scan_2), completing the future with those
2026-11-17T05:32:41.171Z Suppressed WARN: The following Scan URLs disagreed with consensus:\n  http://scan_0
2026-11-17T05:32:42.189Z Suppressed DEBUG: Found enough scans with available data: List(http://scan_0, http://scan_4, http://scan_2, http://scan_6, http://scan_3), completing the future with those
2026-11-17T05:32:42.191Z Suppressed WARN: The following Scan URLs disagreed with consensus:\n  http://scan_6
2026-11-17T05:32:43.209Z Suppressed DEBUG: Found enough scans with available data: List(http://scan_0, http://scan_6, http://scan_4, http://scan_2, http://scan_1), completing the future with those
2026-11-17T05:32:43.211Z Suppressed WARN: The following Scan URLs disagreed with consensus:\n  http://scan_6
2026-11-17T05:32:43.246Z Test failed: 'BulkStorageCommitFromStagingTest/BulkStorageCommitFromStaging with BFT reads enabled should wait until all objects are known to the peers, and report disagreement on consensus correctly'
```

Until 05:32:41 every poll picked scans 0, 2, 4, 5, 6 in some order; the two polls of the last step (after scans 2-4
were set to agree) picked 0, 2, 3, 4, 6 and 0, 1, 2, 4, 6, both without scan_5:

```
zcat log/11531/logs-docker-no-canton-0/canton_network_test.clog.gz | grep -a 'BulkStorageCommitFromStagingTest' | grep -a 'Found enough scans with available data' | awk -F'"' '$4 >= "2026-11-17T05:32:23.695" && $4 <= "2026-11-17T05:32:43.246"' | grep -a -oE 'List\([^)]*\)' | sed 's#http://scan_##g' | awk '{c[$0]++} END{for(k in c) print c[k], k}' | sort -k2
```
```
1 List(0, 4, 2, 6, 3)
1 List(0, 5, 4, 6, 2)
1 List(0, 5, 6, 2, 4)
5 List(0, 5, 6, 4, 2)
1 List(0, 6, 4, 2, 1)
1 List(0, 6, 4, 5, 2)
2 List(0, 6, 5, 4, 2)
```

Why five and why "first to answer" (splice dbf2ab6c8f): 7 scans, f = 2, so `requestsToDo = 2f + 1 = 5`
(`BftScanConnection.scala` `forAvailableData`, line 1265), and phase 1 completes with the first `requiredNumber`
scans whose `hasData` callback reports `Available` (`BftCallExecutor.scala` lines 317-345):

```
git show dbf2ab6c8f:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/api/client/BftCallExecutor.scala | sed -n 317,321p
```
```
    /* For simplicity, we call hasData on all scans, not only `requiredNumber`.
       The assumption is that this call is cheap enough to afford calling all `n` scans.
       This does slightly reduce the randomness for which scan is eventually called for the actual data in the second phase,
       as we complete this phase as soon as `requiredNumber` scans report that they have the required data, and then use
       those (i.e. the firsts to respond) for the second phase, of fetching the actual data. */
```

In the last step all seven mocks have data (0-4 agree, 5 and 6 return `wrong-digest`). Which five callbacks land
first is thread scheduling on the test execution context; any five contain at least three agreeing scans, so the copy
succeeds either way, but the WARN lists only the disagreeing scans that were among the five. The test needs both.

## 4. Fix state

splice PR #8083 "[ci] Deflake bulk storage consensus disagreement test" (branch `is/deflake-bulk-storage-consensus-test`,
head e9b3c59a9e, author tellary, opened 2026-10-28, "Fixes .../cn-test-failures/issues/10737") makes scans 0 and 1
report no data in the last step, so exactly scans 2-6 have data and all five are used:

```
+              Seq(0, 1).foreach(mockScanConnections.setHasNoDataResponse(_))
               Seq.range(2, 5).foreach(i => mockScanConnections.scanAgrees(i))
```

State on 2026-11-17: OPEN, reviewDecision APPROVED (ray-roestenburg-da), last update 2026-11-06, last CI run
42265829760 red on `resource-intensive (0)` and `simtime (2)` (not this suite); the author asked twice for someone
with permission to restart CI. Not on main:

```
git rev-parse --short origin/main; git grep -c 'Seq(0, 1).foreach(mockScanConnections.setHasNoDataResponse' origin/main -- apps/scan/src/test/scala/org/lfdecentralizedtrust/splice/scan/store/bulk/BulkStorageCommitFromStagingTest.scala; echo "exit $?"
```
```
d52fe77b48
exit 1
```

## Verdict

Duplicate of 10737: same test, same assertion line, same mechanism (one of the two disagreeing scans is not among the
five scans the BFT call uses). Flake, test-side. Frequency: 2 hits in 3 weeks (2026-10-28, 2026-11-17). No fix branch
written here because #8083 already carries the fix; it needs a rebase or CI rerun and a merge. 10737 is closed as
completed although nothing was merged for it: reopen it (or keep 11531 open as the tracking issue) until #8083 lands.

Not verified: no sbt run, neither of the test at dbf2ab6c8f nor of #8083; why scan_5 answered late in exactly the two
polls after the mocks were re-stubbed was not chased further (scheduling on the shared runner; the suite ran in
parallel with LocalNetFrontendIntegrationTest's docker-compose start).
