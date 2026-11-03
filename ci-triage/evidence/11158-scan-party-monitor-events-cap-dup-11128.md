# 11158 - ScanPartyMonitorScriptIntegrationTest: private verdict held back by the /v0/events cap (run 41620786495)

DUPLICATE of 11128 (family Z). Post-merge `main` run on 2026-11-03 with Canton 3.7.1, after #8107 (the test's
origin); main has no fix yet (last change to the test is #8107, main at 5c53b74294, 2026-11-03 12:41 UTC). One
test failed in the shard, the same assertion as 11128, 11136, 11138 and 11139: the script never reports alice
`confirming` the private TransferOffer. The script's cursor stays at 11:12:55.878973 (the last DSO-visible update)
for 21 s, while sv1Scan has ingested the verdict of the private transaction at 11:12:56.208982 (330 ms later, like
11128, not the 1 microsecond variant of 11138/11139); the update history only advances `@ None` after it.

- Run: https://github.com/canton-network/splice/actions/runs/41620786495, main 6949aa0f3b ("Add granular db latency
  metrics to perf test (#8180)"), job 125866262813 attempt 1 `ci / scala_test_wall_clock_time / wall-clock-time (3)`.
- Runtime canton: 3.7.1.
- Component: test (ScanPartyMonitorScriptIntegrationTest, added by #8107).

## 1. Job id from the issue's `Job:` line

The run has one attempt and exactly one job with that name.
```
gh api "/repos/canton-network/splice/actions/runs/41620786495/attempts/1/jobs?per_page=100" --paginate --jq '.jobs[] | select(.name | endswith("wall-clock-time (3)")) | "\(.id) \(.name) \(.conclusion)"'
```
```
125866262813 ci / scala_test_wall_clock_time / wall-clock-time (3) failure
```

## 2. Failing assertion

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11158/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++'; sed -E 's/\x1b\[[0-9;]*m//g' log/11158/job.log | grep -a -m1 -A1 'confirmations \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | tail -1
```
```
[info] - scan_party_monitor.py reports transfers to unexpected recipients and confirmations *** FAILED ***
[info] Tests: succeeded 20, failed 1, canceled 0, ignored 0, pending 0
[info] *** 1 TEST FAILED ***
[info]   Check The script reports alice confirming the private transaction for Alice offers bob a transfer, which the DSO party does not see List() did not contain element "confirming" (ScanPartyMonitorScriptIntegrationTest.scala:273)
```

## 3. The act step and its window

```
export TMPDIR=$PWD/log/ghtmp; mkdir -p $TMPDIR
gh run download 41620786495 --repo canton-network/splice -n logs-wall-clock-time-3 -D log/11158/logs-wall-clock-time-3
zcat log/11158/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a -E '"logger_name":"[^"]*ScanPartyMonitorScriptIntegrationTest' | grep -a -E "Alice offers bob" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//'
```
```
2026-11-03T11:12:55.999Z Running clue: (act) Alice offers bob a transfer, which the DSO party does not see
2026-11-03T11:12:56.982Z Finished clue: (act) Alice offers bob a transfer, which the DSO party does not see
```

## 4. Family Z confirm (a): the script's cursor does not move for 21 s

`stderr.py` as in `11138-11139-scan-party-monitor-events-cap-dup-11128.md` section 4 (prints the INFO lines of each
`exited with` debug line); last four runs of the script:
```
zcat log/11158/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a -E '"logger_name":"[^"]*ScanPartyMonitorScriptIntegrationTest' | grep -a 'exited with' | python3 -I stderr.py | awk 'BEGIN{RS="---\n"} {n++; r[n]=$0} END{for(i=n-3;i<=n;i++) if(i>0) printf "%s---\n", r[i]}' | grep -v 'Caught up'
```
```
2026-11-03T11:13:04.547Z exited with 0:
2026-11-03 11:13:04,508 INFO Streaming events after 2026-11-03T11:12:55.878973Z (migration id 0), tracking 3 parties
2026-11-03 11:13:04,525 INFO At record time 2026-11-03T11:12:55.878973Z (migration id 0, 8s behind); this run: 0 transactions, 0 matching
---
2026-11-03T11:13:08.893Z exited with 0:
2026-11-03 11:13:08,859 INFO Streaming events after 2026-11-03T11:12:55.878973Z (migration id 0), tracking 3 parties
2026-11-03 11:13:08,874 INFO At record time 2026-11-03T11:12:55.878973Z (migration id 0, 12s behind); this run: 0 transactions, 0 matching
---
2026-11-03T11:13:14.160Z exited with 0:
2026-11-03 11:13:14,123 INFO Streaming events after 2026-11-03T11:12:55.878973Z (migration id 0), tracking 3 parties
2026-11-03 11:13:14,139 INFO At record time 2026-11-03T11:12:55.878973Z (migration id 0, 18s behind); this run: 0 transactions, 0 matching
---
2026-11-03T11:13:17.232Z exited with 0:
2026-11-03 11:13:17,196 INFO Streaming events after 2026-11-03T11:12:55.878973Z (migration id 0), tracking 3 parties
2026-11-03 11:13:17,212 INFO At record time 2026-11-03T11:12:55.878973Z (migration id 0, 21s behind); this run: 0 transactions, 0 matching
---
```

## 5. Family Z confirm (b): sv1Scan has the later verdict, its update history has no later record time

The verdict at 11:12:56.208982 (the private transaction) is ingested at 11:12:56.654; every later update history
batch is `@ None` (not visible to the DSO party), so the /v0/events cap stays at 11:12:55.878973.
```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat log/11158/logs-wall-clock-time-3/canton_network_test.clog.gz | grep -a -E 'T11:1(2:(5[5-9])|3:[0-2][0-9])' | grep -a 'scan=sv1Scan' | grep -a -E 'migration 0 ingesting|Last ingested verdict record_time' | sed -E "$F" | sed -E 's/ \[[^]]*\] [A-Z]+$//; s/",\[o\.l\.s\.s\.UpdateHistory.*//' | cut -c1-170
```
```
2026-11-03T11:12:55.323Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-03T11:12:54.949082Z). Ins
2026-11-03T11:12:55.680Z History 19 migration 0 ingesting 3345 => 3348 @ Some(2026-11-03T11:12:54.949082Z)
2026-11-03T11:12:56.248Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-03T11:12:55.878973Z). Ins
2026-11-03T11:12:56.346Z History 19 migration 0 ingesting 3348 => 3351 @ Some(2026-11-03T11:12:55.878973Z)
2026-11-03T11:12:56.654Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-03T11:12:56.208982Z). Ins
2026-11-03T11:13:06.079Z History 19 migration 0 ingesting 3351 => 3366 @ None
2026-11-03T11:13:21.201Z History 19 migration 0 ingesting 3366 => 3388 @ None
```

## 6. Sha, canton pin and fix state on main

```
s=6949aa0f3b; echo "$s: $(git show $s:nix/canton-sources.json | grep -m1 version) contains-8107=$(git merge-base --is-ancestor 3525c637db $s && echo yes || echo no)"
gh api "repos/canton-network/splice/commits?sha=main&path=apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanPartyMonitorScriptIntegrationTest.scala&per_page=3" --jq '.[] | "\(.sha[:10]) \(.commit.committer.date[:10]) \(.commit.message | split("\n")[0])"'
gh api "repos/canton-network/splice/commits?sha=main&per_page=1" --jq '.[0] | "main \(.sha[:10]) \(.commit.committer.date)"'
```
```
6949aa0f3b:   "version": "3.7.1", contains-8107=yes
3525c637db 2026-10-30 scan-party-monitor: use events stream to report on private transaction confirmations too (#8107)
758fd48a00 2026-10-29 type-check and unittest Python in CI (#8098)
d3be3a4304 2026-10-29 Scan per-party transfers/confirmations monitor+Slack notifier (#7982)
main 5c53b74294 2026-11-03T12:41:20Z
```

## Verdict

Duplicate of 11128, family Z: test flake, the /v0/events cap (min of last verdict and last update record time)
holds back a verdict the DSO party does not see until the next DSO-visible update, and the test does not cause one
within its 20 s window. Fifth hit since 2026-11-02 (11128, 11136, 11138, 11139, 11158). Fix location: the test
(`ScanPartyMonitorScriptIntegrationTest.scala`, the act step before the check at line 273): cause a DSO-visible
transaction (e.g. a tap) after createTransferOffer, as proposed for 11128; no fix branch. Not verified: the
ScanEventStore cap code itself (taken from the 11128 packet).
