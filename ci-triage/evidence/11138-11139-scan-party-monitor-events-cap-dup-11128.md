# 11138, 11139 - ScanPartyMonitorScriptIntegrationTest: private verdict held back by the /v0/events cap (runs 41428422164, 41438225454)

DUPLICATE of 11128 (family Z). Both are post-merge `main` runs on 2026-11-02 with Canton 3.7.1, both after #8107
(the test's origin) and with no fix on main yet. One test failed per shard, the same assertion as 11128:
the script never reports alice `confirming` the private TransferOffer. In both runs the script's cursor stays at the
record time of the last DSO-visible update for 20 s, while sv1Scan has already ingested the verdict of the private
transaction 1 microsecond later; the update history only advances `@ None` until the test gives up.

- Run 11138: https://github.com/canton-network/splice/actions/runs/41428422164, main 288c61b7e8 ("[ci] Bump web-vitals
  from 4.2.4 to 6.2.2 in /apps (#8042)"), job 125221165964 attempt 1 `ci / scala_test_wall_clock_time / wall-clock-time (0)`.
- Run 11139: https://github.com/canton-network/splice/actions/runs/41438225454, main b2b57fc37b ("Bump versions and clear
  release notes after 0.10.0 cut (#8174)"), job 125253822686 attempt 1 `ci / scala_test_wall_clock_time / wall-clock-time (8)`.
- Runtime canton: 3.7.1 at both shas.
- Component: test (ScanPartyMonitorScriptIntegrationTest, added by #8107).

## 1. Job id from the issue's `Job:` line

```
for p in "41428422164 wall-clock-time (0)" "41438225454 wall-clock-time (8)"; do
  RUN=${p%% *}; export NAME="${p#* }"
  gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" \
    --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
done
```
```
125221165964 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (0)
125253822686 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (8)
```

## 2. Failing assertion (identical in both)

```
for R in 11138 11139; do echo "== $R"; sed -E 's/\x1b\[[0-9;]*m//g' log/$R/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded' | sed -E 's/^[^Z]*Z //' | sort -u; sed -E 's/\x1b\[[0-9;]*m//g' log/$R/job.log | grep -a -m1 -A1 'confirmations \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | tail -1; done
```
```
== 11138
[info] *** 1 TEST FAILED ***
[info] - scan_party_monitor.py reports transfers to unexpected recipients and confirmations *** FAILED ***
[info] Tests: succeeded 24, failed 1, canceled 0, ignored 0, pending 0
[info]   Check The script reports alice confirming the private transaction for Alice offers bob a transfer, which the DSO party does not see List() did not contain element "confirming" (ScanPartyMonitorScriptIntegrationTest.scala:273)
== 11139
[info] *** 1 TEST FAILED ***
[info] - scan_party_monitor.py reports transfers to unexpected recipients and confirmations *** FAILED ***
[info] Tests: succeeded 37, failed 1, canceled 0, ignored 0, pending 0
[info]   Check The script reports alice confirming the private transaction for Alice offers bob a transfer, which the DSO party does not see List() did not contain element "confirming" (ScanPartyMonitorScriptIntegrationTest.scala:273)
```

## 3. The act step and its window

```
export TMPDIR=$PWD/log/ghtmp; mkdir -p $TMPDIR
gh run download 41428422164 --repo canton-network/splice -n logs-wall-clock-time-0 -D log/11138/logs-wall-clock-time-0
gh run download 41438225454 --repo canton-network/splice -n logs-wall-clock-time-8 -D log/11139/logs-wall-clock-time-8
for p in "11138 logs-wall-clock-time-0" "11139 logs-wall-clock-time-8"; do set -- $p; echo "== $1"; zcat log/$1/$2/canton_network_test.clog.gz | grep -a -E '"logger_name":"[^"]*ScanPartyMonitorScriptIntegrationTest' | grep -a -E "Alice offers bob" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//'; done
```
```
== 11138
2026-11-02T10:59:17.786Z Running clue: (act) Alice offers bob a transfer, which the DSO party does not see
2026-11-02T10:59:18.378Z Finished clue: (act) Alice offers bob a transfer, which the DSO party does not see
== 11139
2026-11-02T12:13:10.120Z Running clue: (act) Alice offers bob a transfer, which the DSO party does not see
2026-11-02T12:13:11.027Z Finished clue: (act) Alice offers bob a transfer, which the DSO party does not see
```

## 4. Family Z confirm (a): the script's cursor does not move for 20 s

The test logs the script's stderr at DEBUG (`<script> exited with 0:`). Last four runs per shard:
```
cat > stderr.py <<'EOF'
import sys, json
for line in sys.stdin:
    try:
        d = json.loads(line)
    except Exception:
        continue
    m = d.get("message", "")
    if "exited with" not in m:
        continue
    print(d["@timestamp"], "exited with" + m.split("exited with", 1)[1].split("\n", 1)[0])
    for l in m.split("\n")[1:]:
        if " INFO " in l:
            print(l)
    print("---")
EOF
for p in "11138 logs-wall-clock-time-0" "11139 logs-wall-clock-time-8"; do set -- $p; echo "== $1"; zcat log/$1/$2/canton_network_test.clog.gz | grep -a -E '"logger_name":"[^"]*ScanPartyMonitorScriptIntegrationTest' | grep -a 'exited with' | python3 -I stderr.py | awk 'BEGIN{RS="---\n"} {n++; r[n]=$0} END{for(i=n-3;i<=n;i++) if(i>0) printf "%s---\n", r[i]}' | grep -v 'Caught up'; done
```
```
== 11138
2026-11-02T10:59:26.438Z exited with 0:
2026-11-02 10:59:26,397 INFO Streaming events after 2026-11-02T10:59:17.853991Z (migration id 0), tracking 3 parties
2026-11-02 10:59:26,415 INFO At record time 2026-11-02T10:59:17.853991Z (migration id 0, 8s behind); this run: 0 transactions, 0 matching
---
2026-11-02T10:59:30.844Z exited with 0:
2026-11-02 10:59:30,805 INFO Streaming events after 2026-11-02T10:59:17.853991Z (migration id 0), tracking 3 parties
2026-11-02 10:59:30,823 INFO At record time 2026-11-02T10:59:17.853991Z (migration id 0, 12s behind); this run: 0 transactions, 0 matching
---
2026-11-02T10:59:36.147Z exited with 0:
2026-11-02 10:59:36,108 INFO Streaming events after 2026-11-02T10:59:17.853991Z (migration id 0), tracking 3 parties
2026-11-02 10:59:36,127 INFO At record time 2026-11-02T10:59:17.853991Z (migration id 0, 18s behind); this run: 0 transactions, 0 matching
---
2026-11-02T10:59:38.677Z exited with 0:
2026-11-02 10:59:38,631 INFO Streaming events after 2026-11-02T10:59:17.853991Z (migration id 0), tracking 3 parties
2026-11-02 10:59:38,652 INFO At record time 2026-11-02T10:59:17.853991Z (migration id 0, 20s behind); this run: 0 transactions, 0 matching
---
== 11139
2026-11-02T12:13:19.027Z exited with 0:
2026-11-02 12:13:18,983 INFO Streaming events after 2026-11-02T12:13:10.194132Z (migration id 0), tracking 3 parties
2026-11-02 12:13:19,003 INFO At record time 2026-11-02T12:13:10.194132Z (migration id 0, 8s behind); this run: 0 transactions, 0 matching
---
2026-11-02T12:13:23.436Z exited with 0:
2026-11-02 12:13:23,389 INFO Streaming events after 2026-11-02T12:13:10.194132Z (migration id 0), tracking 3 parties
2026-11-02 12:13:23,408 INFO At record time 2026-11-02T12:13:10.194132Z (migration id 0, 13s behind); this run: 0 transactions, 0 matching
---
2026-11-02T12:13:28.723Z exited with 0:
2026-11-02 12:13:28,678 INFO Streaming events after 2026-11-02T12:13:10.194132Z (migration id 0), tracking 3 parties
2026-11-02 12:13:28,698 INFO At record time 2026-11-02T12:13:10.194132Z (migration id 0, 18s behind); this run: 0 transactions, 0 matching
---
2026-11-02T12:13:31.327Z exited with 0:
2026-11-02 12:13:31,282 INFO Streaming events after 2026-11-02T12:13:10.194132Z (migration id 0), tracking 3 parties
2026-11-02 12:13:31,302 INFO At record time 2026-11-02T12:13:10.194132Z (migration id 0, 21s behind); this run: 0 transactions, 0 matching
---
```

## 5. Family Z confirm (b): sv1Scan has the later verdict, its update history has no later record time

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
for p in "11138 logs-wall-clock-time-0 T10:59:(1[6-9]|[23][0-9])" "11139 logs-wall-clock-time-8 T12:13:(0[89]|[123][0-9])"; do set -- $p; echo "== $1"; zcat log/$1/$2/canton_network_test.clog.gz | grep -a -E "$3" | grep -a 'scan=sv1Scan' | grep -a -E 'migration 0 ingesting|Last ingested verdict record_time' | sed -E "$F" | sed -E 's/ \[[^]]*\] [A-Z]+$//; s/",\[o\.l\.s\.s\.UpdateHistory.*//' | cut -c1-170; done
```
```
== 11138
2026-11-02T10:59:16.236Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T10:59:15.706782Z). Inse
2026-11-02T10:59:16.594Z History 34 migration 0 ingesting 3480 => 3483 @ Some(2026-11-02T10:59:15.706782Z)
2026-11-02T10:59:17.307Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T10:59:16.934061Z). Inse
2026-11-02T10:59:17.459Z History 34 migration 0 ingesting 3483 => 3486 @ Some(2026-11-02T10:59:16.934061Z)
2026-11-02T10:59:18.238Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T10:59:17.853991Z). Inse
2026-11-02T10:59:18.242Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T10:59:17.853992Z). Inse
2026-11-02T10:59:18.369Z History 34 migration 0 ingesting 3486 => 3489 @ Some(2026-11-02T10:59:17.853991Z)
2026-11-02T10:59:24.010Z History 34 migration 0 ingesting 3489 => 3497 @ None
2026-11-02T10:59:39.033Z History 34 migration 0 ingesting 3497 => 3519 @ None
== 11139
2026-11-02T12:13:08.381Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T12:13:07.890069Z). Inse
2026-11-02T12:13:08.762Z History 32 migration 0 ingesting 3186 => 3189 @ Some(2026-11-02T12:13:07.890069Z)
2026-11-02T12:13:09.425Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T12:13:08.979013Z). Inse
2026-11-02T12:13:09.790Z History 32 migration 0 ingesting 3189 => 3192 @ Some(2026-11-02T12:13:08.979013Z)
2026-11-02T12:13:10.631Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T12:13:10.194132Z). Inse
2026-11-02T12:13:10.641Z Inserted 1 verdicts, 1 traffic summaries, 0 app activity records. Last ingested verdict record_time is now Some(2026-11-02T12:13:10.194133Z). Inse
2026-11-02T12:13:11.013Z History 32 migration 0 ingesting 3192 => 3195 @ Some(2026-11-02T12:13:10.194132Z)
2026-11-02T12:13:22.788Z History 32 migration 0 ingesting 3195 => 3213 @ None
2026-11-02T12:13:37.570Z History 32 migration 0 ingesting 3213 => 3235 @ None
```
During the act, two verdicts are ingested 1 microsecond apart (10:59:17.853991 / .853992, 12:13:10.194132 / .194133).
The update history records only the first record time; afterwards it moves `@ None` until after the failure. The
script's cursor sits exactly at the update's record time, so `/v0/events` (capped at min(last verdict, last update)
record time, see 11128 in `evidence/2026-11-02-singles-b.md` section 4) never serves the later, DSO-invisible verdict. Same
mechanism as 11128; here the hidden verdict follows the last DSO-visible update by 1 microsecond instead of ~250 ms.
The later of the two verdicts is attributed to the private TransferOffer by the cursor position, not by contract id;
not checked further.

## 6. No fix on main

```
git log --format='%h %ad %s' --date=short origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ScanPartyMonitorScriptIntegrationTest.scala scripts/scan-party-monitor/ | head -3
for s in 288c61b7e8 b2b57fc37b; do echo "$s: $(git show $s:nix/canton-sources.json | grep -m1 version) contains-8107=$(git merge-base --is-ancestor 3525c637db $s && echo yes || echo no)"; done
git rev-parse --short origin/main
```
```
3525c637db 2026-10-30 scan-party-monitor: use events stream to report on private transaction confirmations too (#8107)
758fd48a00 2026-10-29 type-check and unittest Python in CI (#8098)
d3be3a4304 2026-10-29 Scan per-party transfers/confirmations monitor+Slack notifier (#7982)
c95027ee5f
```
```
288c61b7e8:   "version": "3.7.1", contains-8107=yes
b2b57fc37b:   "version": "3.7.1", contains-8107=yes
```

## Verdict

Duplicate of 11128 (family Z), test flake. With 11128 and 11136, four failures of this test on 2026-11-02 alone
(05:00 PG14 nightly, 07:43, 10:23 and 11:36 push runs), so it is frequent, not rare. Fix location unchanged: in the
test, cause one DSO-visible transaction (e.g. a tap) after `createTransferOffer` so the /v0/events cap moves past
the private verdict. No fix branch written. Not verified: which contract each of the two act verdicts belongs to.
