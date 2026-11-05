# 11182 - checkErrors: scan bulk storage logs a false "Not all objects are known" ERROR (run 41790532527)

New, scan bug. main 7307892450, Canton 3.7.1, job docker-canton-simtime (0). Tests passed (5); checkErrors flagged one
ERROR from sv2Scan in ScanNewNetworkBackfillingTimeBasedIntegrationTest. `waitForBftAgreement` starts one more BFT
check right after a successful one; that extra check asks sv1Scan for the staging checksums while sv1Scan is
deleting the same objects from staging after its own commit, gets `null` for one object, and logs ERROR. The commit
of sv2Scan went through on the first check.

- Run: https://github.com/canton-network/splice/actions/runs/41790532527, main 7307892450 ("Add support for sweep to the validator runbook (#8123)"), job 126443186462 `ci / scala_test_with_docker_and_canton_simtime / docker-canton-simtime (0)`, attempt 1.
- Runtime canton: 3.7.1.
- Component: splice scan app (`apps/scan/.../store/bulk/BulkStorageCommitFromStaging.scala`).

## 1. Flagged line

```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/11182/job.log | grep -a -A2 'Found problems' | cut -c1-300
```
```
Found problems in log/canton_network_test.clog:
{"@timestamp":"2026-11-04T07:31:03.542Z","message":"Not all objects are known to the BFT peers, despite them indicating that they have caught up to the required timestamp. This indicates that different peers have different data, and must be investigated.","logger_name":"o.l.s.s.s.b.BulkStorageCommitFromStaging:ScanNewNetworkBackfillingTimeBasedIntegrationTest/config=ef4a1c56/scan=sv2Scan",...,"level":"ERROR"
Total: 1 lines with problems.
```

## 2. The two checks and the peer's delete (artifact logs-docker-canton-simtime-0)

```
zcat canton_network_test.clog.gz | awk (07:31:03.40 to 07:31:03.545) | grep -aE 'bulk/checksums|Deleted object|committed objects|Consensus|BFT agreement' (trimmed, scan name added)
```
```
07:31:03.466 [sv2Scan] Checking BFT agreement for objects: 1970-01-03T00:00:00Z~1970-01-04T00:00:00Z/updates_compact_json_0.zstd, ..._protobuf_json_0.zstd
07:31:03.484 [sv2Scan] Received response with entity data: {"checksums":[{"value":"nug+...="},{"value":"+kaK...="}]}
07:31:03.484 [sv2Scan] All objects are known to the BFT peers. Checking if checksums match.
07:31:03.484 [sv2Scan] Checking BFT agreement for objects: (same two objects)
07:31:03.484 [sv2Scan] BFT agreement reached for the objects of UpdatesSegment(1970-01-03T00:00:00Z,1970-01-04T00:00:00Z). Proceeding with commit.
07:31:03.488 [sv2Scan] HTTP client (POST /api/scan/v0/history/bulk/checksums): Requesting with entity data: (same two keys)
07:31:03.490 [sv1Scan] HTTP POST /api/scan/v0/history/bulk/checksums from (127.0.0.1:55920): received request.
07:31:03.498 [sv1Scan] Deleted object 1970-01-03T00:00:00Z~1970-01-04T00:00:00Z/updates_protobuf_json_0.zstd from staging storage
07:31:03.539 [sv2Scan] Successfully committed objects for timestamp UpdatesSegment(1970-01-03T00:00:00Z,1970-01-04T00:00:00Z)
07:31:03.541 [sv1Scan] HTTP POST /api/scan/v0/history/bulk/checksums from (127.0.0.1:55920): Responding with entity data: {"checksums":[{"value":"nug+...="},{"value":null}]}
07:31:03.542 [sv2Scan] Consensus achieved on 1 out of 2 objects
07:31:03.542 [sv2Scan] Not all objects are known to the BFT peers, ... (ERROR)
```

## 3. Why a second check runs

```
git show 7307892450:apps/scan/src/main/scala/org/lfdecentralizedtrust/splice/scan/store/bulk/BulkStorageCommitFromStaging.scala | sed -n '167,194p'
```
```
    Flow[(T, Seq[ObjectKeyAndChecksum])].flatMapConcat { case (t, obj) =>
      Source
        .repeat(obj)
        .mapAsync(parallelism = 1)(obj =>
          checkBftForObjects(getRequiredCatchupTimestamp(t), obj).map(result => (obj, result))
        )
        .flatMapConcat { ... }
        .takeWhile({ case (_, bftReached) => !bftReached }, inclusive = true)
```
mapAsync pulls the next repeated element as soon as a check completes, so a check starts before takeWhile sees the
success (second `Checking BFT agreement` at 07:31:03.484, same millisecond). Its outcome is discarded, but
`checkBftForBatch` logs the ERROR itself (line 76).

```
git blame -L 76,76 7307892450 -- .../BulkStorageCommitFromStaging.scala; gh api repos/canton-network/splice/commits/4a7f355b17 --jq '.author.login'
git log --oneline 7307892450..https-origin/main -- .../BulkStorageCommitFromStaging.scala
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "different peers have different data"' --jq .total_count
```
```
4a7f355b176 (Itai Segall 2026-10-02 ...) "Not all objects are known to the BFT ..."   -> isegall-da, "support eventual consistency for bulk storage staging (#7509)"
(no later change on main)
0
```

## Verdict

New, real bug in scan (production code), first hit. Effect here: a false ERROR (checkErrors); sv2Scan committed
correctly. Fix: stop starting a check after a successful one (a Future-based retry loop, or `Source.unfoldAsync`
that ends on success, instead of `Source.repeat` + `mapAsync` + `takeWhile`). Owner: isegall-da (#7509). Not verified:
whether the same false ERROR shows on clusters (it should, whenever a peer commits a segment within one check).
