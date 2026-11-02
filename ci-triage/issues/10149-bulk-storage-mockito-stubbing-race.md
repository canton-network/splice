# 10149 - BulkStorageCommitFromStagingTest hangs after a Mockito stubbing race (run 35092848061)

Branch release-line-0.8.1, sha b3e6bfa49d "[ci] backport: order apps-app compile after copyResources (#7176)",
post-merge CI 2026-09-16T11:54Z. Failed job 104783740346 `scala_test_with_docker_no_canton / docker-no-canton (0)`.
No Canton node involved (unit test against s3mock + mocked scan connections). Commands verified against the
downloaded artifacts in `log/10149/`.

Ref mapping: given by Raymond as (35092848061, 10149).

## Categorization

- Test failed: `BulkStorageCommitFromStagingTest` / "BulkStorageCommitFromStaging with BFT reads enabled
  should wait until all objects are known to the peers, and report disagreement on consensus correctly"
  (apps-scan unit test). 3 other tests in the shard passed.
- Failure type: assertion timeout (`sub.expectNext(20.seconds, "go")`), caused by the stream under test
  stalling after a `ClassCastException` inside a Future callback.
- Component: the test's `MockScanConnections` (Mockito re-stubbing while the flow runs). Not the
  backport (#7176 touches build.sbt only), not Canton, not the production code path as such.
- Flake vs real: test bug, timing-dependent. main has the same re-stubbing pattern (its copy differs only by the
  `requiredCatchupTimestamp` argument added to `getBulkObjectChecksums`), so main is exposed too.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35092848061 --repo canton-network/splice -n logs-docker-no-canton-0 -D dl
gh api repos/canton-network/splice/actions/jobs/104783740346/logs > dl/job-104783740346.log
cd dl
```

## 1. Failed job and test

```
gh run view 35092848061 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
grep -aE 'FAILED \*\*\*|Tests: succeeded' job-104783740346.log | sed -E 's/^[0-9T:.Z-]+ //' | sed 's/\x1b\[[0-9;]*m//g' | head -3
```
```
104783740346  ci / scala_test_with_docker_no_canton / docker-no-canton (0)
[info] - should wait until all objects are known to the peers, and report disagreement on consensus correctly *** FAILED ***
[info] *** 1 TEST FAILED ***
[info] Tests: succeeded 4, failed 0, canceled 0, ignored 0, pending 0
```
(The "succeeded 4" summary is the second, LocalNet suite group; the apps-scan group reports the failure.)

## 2. Assertion and stack trace

```
zcat canton_network_test.clog.gz | grep -aE 'Test failed:.*BulkStorageCommitFromStaging' | head -1 \
  | grep -aoE 'message: [^,]*, location[^"]*'
grep -aE 'AssertionError|BulkStorageCommitFromStagingTest.scala' job-104783740346.log | sed 's/\x1b\[[0-9;]*m//g' | sed -E 's/^[0-9T:.Z-]+ \[info\] +//' | head -6
```
```
message: assertion failed: timeout (20 seconds) during expectMsg while waiting for OnNext(go), location: SeeStackDepthException
java.lang.AssertionError: assertion failed: timeout (20 seconds) during expectMsg while waiting for OnNext(go)
at org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageCommitFromStagingTest.$anonfun$new$18(BulkStorageCommitFromStagingTest.scala:183)
at org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageCommitFromStagingTest.$anonfun$new$15(BulkStorageCommitFromStagingTest.scala:181)
at org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageCommitFromStagingTest.$anonfun$new$11(BulkStorageCommitFromStagingTest.scala:188)
at org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageCommitFromStagingTest.runTest(BulkStorageCommitFromStagingTest.scala:40)
```
Line 183 is the third clue of the test: after re-stubbing scans 2..4 to agree, it expects the copy flow to
emit "go" within 20 s.

## 3. The test at b3e6bfa49d (same stubbing pattern as main)

```
T=apps/scan/src/test/scala/org/lfdecentralizedtrust/splice/scan/store/bulk/BulkStorageCommitFromStagingTest.scala
gh api "repos/canton-network/splice/contents/$T?ref=b3e6bfa49d" -H 'Accept: application/vnd.github.raw' > t.scala
git show <main sha>:$T > m.scala; diff t.scala m.scala | grep -c '^[<>]'
awk 'NR>=181&&NR<=184 || NR>=246&&NR<=254 || NR>=257&&NR<=262 {printf "%4d  %s\n", NR, $0}' t.scala
```
```
14
 181        clue("Enough scans do agree - the copy flow should complete successfully") {
 182          Seq.range(2, 5).foreach(i => mockScanConnections.scanAgrees(i))
 183          sub.expectNext(20.seconds, "go")
 184          assertObjectsMoved(stagingS3Connection, committedS3Connection, objsWithDigests)
 246        private val singleScanConnections: Seq[SingleScanConnection] = Seq.range(0, 7).map { i =>
 247          val mockConn = mock[SingleScanConnection]
 248          when(mockConn.config) thenReturn ScanAppClientConfig(
 249            NetworkAppClientConfig(
 250              Uri(s"http://dummy-admin-$i")
 251            )
 252          )
 253          when(mockConn.url) thenReturn Uri(s"http://scan_$i")
 254          mockConn
 257        def scanAgrees(idx: Integer): Unit = {
 258          when(
 259            singleScanConnections(idx)
 260              .getBulkObjectChecksums(any[Seq[String]])(any[ExecutionContext], any[TraceContext])
 261          )
 262            .thenReturn(
```
The 14 differing lines against main (896a62310c) are the `requiredCatchupTimestamp` argument and its
`any[CantonTimestamp]` matchers; the stubbing pattern is the same. `scanAgrees`, `scanDisagreesOnDigest` and
`scanMissingAnObject` all re-stub `getBulkObjectChecksums` with
`when(...).thenReturn(...)` on mocks that the running flow is invoking concurrently from the test execution
context. The flow polls every `bftRetryInterval = 1 s` (line 110).

## 4. The flow stalls: last poll at 12:13:04.939, nothing until the timeout

```
zcat canton_network_test.clog.gz | grep -aE 'BulkStorageCommitFromStaging' \
  | grep -aE '"@timestamp":"2026-09-16T12:13:(0[3-9]|1|2[0-3])' | grep -av 's3Mock' \
  | sed -E 's/.*"@timestamp":"([^"]*)".*"message":"([^"]{0,110}).*"level":"([A-Z]+)".*/\1 \3 :: \2/'
```
```
2026-09-16T12:13:03.149Z DEBUG :: Checking BFT agreement for objects: object1.txt, object2.txt, object3.txt
2026-09-16T12:13:03.924Z DEBUG :: Running clue: Enough scans do agree - the copy flow should complete successfully
2026-09-16T12:13:03.924Z DEBUG :: BFT agreement not yet reached for the objects at go. Will retry after delay.
2026-09-16T12:13:03.925Z DEBUG :: Consensus achieved on 3 out of 3 objects
2026-09-16T12:13:03.925Z DEBUG :: All objects are known to the BFT peers. Checking if checksums match.
2026-09-16T12:13:03.925Z INFO :: Suppressed ERROR: Checksums do not match for objects object1.txt, object2.txt, object3.txt. My checksums are: ...
2026-09-16T12:13:03.925Z INFO :: The following Scan URLs disagreed with consensus:\n  http://scan_0\n  http://scan_1\nconsensus response: ...
2026-09-16T12:13:04.939Z DEBUG :: Checking BFT agreement for objects: object1.txt, object2.txt, object3.txt
2026-09-16T12:13:05.887Z DEBUG :: BFT agreement not yet reached for the objects at go. Will retry after delay.
2026-09-16T12:13:05.891Z INFO :: Suppressed ERROR: A fatal error has occurred in BulkStorageCommitFromStagingTest-test-execution-context. Terminating thread.
2026-09-16T12:13:23.926Z INFO :: Suppressed ERROR: Failed clue: Enough scans do agree - the copy flow should complete successfully
2026-09-16T12:13:23.931Z ERROR :: Test failed: 'BulkStorageCommitFromStagingTest/BulkStorageCommitFromStaging with BFT reads enabled should wait until ...
```
Reading order: `mapAsync(1)` starts poll k+1 ("Checking") in the same turn in which it emits poll k's result, so
"Checking"(k+1) precedes "not yet reached"(k) by a millisecond; the "Consensus achieved" lines belong to the
poll whose "Checking" is just above them. So: poll B starts 03.149, its consensus callbacks run at 03.925,
exactly while the test thread executes `scanAgrees(2..4)` (clue starts 03.924). Poll C starts 04.939, poll B's
"not yet" is emitted 05.887, and poll C never logs a consensus line or a result. No "Checking" follows for the
remaining 18 s: the stream is stuck inside poll C.

## 5. Why poll C never completes: a ClassCastException in executeCall's callback

```
zcat canton_network_test.clog.gz | grep -aE 'A fatal error has occurred' | head -1 | python3 -c '
import sys,json; d=json.loads(sys.stdin.readline()); print(d["thread_name"]); print("\n".join(d["stack_trace"].splitlines()[:6]))'
```
```
BulkStorageCommitFromStagingTest-test-execution-context-440
java.lang.ClassCastException: class scala.concurrent.impl.Promise$DefaultPromise cannot be cast to class org.apache.pekko.http.scaladsl.model.Uri (...)
	at org.lfdecentralizedtrust.splice.scan.admin.api.client.SingleScanConnection$MockitoMock$1002545587.url(Unknown Source)
	at org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection$.$anonfun$executeCall$6(BftScanConnection.scala:1196)
	at java.base/java.util.concurrent.ConcurrentHashMap.compute(ConcurrentHashMap.java:1940)
	at org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection$.$anonfun$executeCall$5(BftScanConnection.scala:1196)
	at org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection$.$anonfun$executeCall$5$adapted(BftScanConnection.scala:1192)
```
`mockConn.url` returned a `Future` instead of a `Uri`. That is the signature of a Mockito stubbing race: the
test thread ran `when(mock.getBulkObjectChecksums(...))` and then `.thenReturn(Future.successful(...))`, while
the pool thread invoked `mock.url` on the same mock in between. Mockito's per-mock invocation container keeps
one shared "invocation for potential stubbing" slot; the pool thread's `url()` overwrote it, so `thenReturn`
bound the `Future` answer to `url()`. Stubbing a mock while another thread invokes it is documented as
unsupported by Mockito.

The message "A fatal error has occurred in ... Terminating thread." is only the test execution context's
`reportFailure` logger (canton `ThrowableUtil.logThrowable`, vendored canton 3.5.7-SNAPSHOT; same text in any
version); the thread survives, but the callback died.

Consequence in `executeCall` at b3e6bfa49d:

```
gh api "repos/canton-network/splice/contents/apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/admin/api/client/BftScanConnection.scala?ref=b3e6bfa49d" \
  -H 'Accept: application/vnd.github.raw' | awk 'NR>=1192&&NR<=1197 || NR==1209 || NR==1230 {printf "%4d  %s\n", NR, $0}'
```
```
1192          .foreach { case (key, response) =>
1193            val agreements =
1194              responses.compute(
1195                key,
1196                (_, scans) => scan.url :: Option(scans).getOrElse(List.empty),
1197              )
1209            if (nResponsesDone.incrementAndGet() == requestFrom.size) { // all Scans are done
1230      finalResponse.future
```
The callback threw at line 1196, before `nResponsesDone.incrementAndGet()` at 1209. `finalResponse` can only
complete on consensus or when all responses are counted; with one response lost and no consensus among the
rest, it never completes. `checkBftForObjects` therefore never completes, `mapAsync(1)` never emits, the flow
never polls again, and `expectNext(20.seconds)` times out.

## 6. Not a regression, not the backport

```
gh pr view 7176 --repo canton-network/splice --json files --jq '[.files[].path] | join(" ")'
for r in 35073296402 35052914872 34594287649 34565043316; do printf "%s " $r; gh run view $r --repo canton-network/splice \
  --json jobs --jq '[.jobs[] | select(.name|test("docker-no-canton")) | .conclusion] | join(",")'; done
```
```
build.sbt
35073296402 success
35052914872 success
34594287649 success
34565043316 success
```
The backport changes build.sbt only. The same shard passed on the previous four release-line-0.8.1 runs and
on the recent main runs checked; the race needs the test thread's re-stub to land inside a poll's callback
window (about 1 ms per 1 s poll), which is why it is rare.

## Root cause / hypothesis

Proven:
- The test failed on `expectNext(20 s)` at line 183, after the flow stopped polling at 12:13:04.939.
- A `ClassCastException` (Promise cast to Uri) was thrown from the mocked `SingleScanConnection.url` inside
  `executeCall`'s response callback at 12:13:05.891, on the test execution context.
- The test re-stubs `getBulkObjectChecksums` on live mocks (lines 257-310) while the flow invokes them; the
  re-stub for clue 3 (03.924) coincides with poll B's callbacks (03.925).

Inferred (mechanism, standard Mockito behaviour): the concurrent `url()` invocation replaced the pending
stubbing target, so the `Future` answer was bound to `url()`; the next poll's callback died on the cast, the
response was never counted, and the BFT call's promise never completed.

## Duplicates / related

- None known. First occurrence in the recent docker-no-canton history on both branches.
- The test's `assertLogsSeq(LevelAndAbove(ERROR))` with `forAtLeast(1, ...)` swallowed the fatal ERROR as a
  suppressed entry, so the ScalaTest report shows only the timeout; the cause is only in the clog.
- Same production code (`BftScanConnection.executeCall`) as PR #7298 (eventual-consistency reads); the PR
  does not change the stubbing pattern of this test, so it inherits the flake.
- An earlier draft of this packet said the file was byte-identical on main; that compared against a stale
  checkout. Corrected above.

## Suggested next step / owner

Fix the test, not the code: stub each mock once at construction with `thenAnswer` that reads a per-scan
`AtomicReference[GetBulkObjectChecksumsResponse]` (or a `Behaviour` enum), and have `scanAgrees` /
`scanDisagreesOnDigest` / `scanMissingAnObject` only set that reference. No `when(...)` after the flow is
materialized. Optionally assert that no "A fatal error has occurred" line is logged during the test so the
next such race fails loudly instead of as a timeout. Owner: scan bulk-storage (isegall-da). Applies to main as
well (same pattern). FIX WRITTEN AND VERIFIED 2026-09-17: branch `ray/fix-bulk-storage-test-stubbing-race`, one test-only commit
(63359204ae after rebase onto main 22e775d614). Compiled in the sandbox (`apps-scan/Test/compile`, sbt run straight
from the nix store, see `log/sbt-env.sh`), `apps-scan/Test/scalafmtCheck` clean, and
`apps-scan/testOnly ...BulkStorageCommitFromStagingTest` run 6 times against the local Postgres container and the
adobe/s3mock testcontainer: 6 x `Tests: succeeded 5, failed 0`, zero `A fatal error has occurred` /
`ClassCastException` lines in `log/canton_network_test.clog`. Unpushed; needs a PR against main and backports
to release-line-0.8.x/0.8.1 (10149 ran on 0.8.1).
