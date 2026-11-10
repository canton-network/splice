# 11327 - checkErrors: scan `claimed by multiple SVs` WARN from sv2-4 scans after the suppression ended (run 42785850771)

DUPLICATE of 11304 (family AJ), closed as completed by splice#8389 (merged 2026-11-10 09:25:35Z). Post-merge main
087f79bf73 (#8391), job `wall-clock-time (6)`, canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252, 70 tests passed;
checkErrors flagged three HttpScanHandler WARNs `Not serving sequencer SEQ::sv2::... as it is claimed by multiple SVs`
from sv3Scan, sv4Scan and sv2Scan at 09:21:07.645-.647Z, 1.4 s after the last suppressed occurrence (sv1Scan
09:21:06.252Z) and 0.2 s after the test's restore check on sv1ScanBackend returned. The request trace belongs to
bobValidator's ReconcileSequencerConnectionsTrigger polling loop. This is exactly the AJ shape; the run's sha predates
#8389, which widens the restore check to all four scans. Not a regression.

- Run: https://github.com/canton-network/splice/actions/runs/42785850771, main 087f79bf73 ("Wait for alice validator
  init before checking OTK rotation in ManualSignatureIntegrationTest (#8391)"), job 129727021108
  `ci / scala_test_wall_clock_time / wall-clock-time (6)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (ValidatorSequencerConnectionIntegrationTest).

## 1. Flagged lines

All tests passed; checkErrors flagged three lines in `canton_network_test.clog`.

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11327/job.log | grep -a -E 'Tests: succeeded'; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11327/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | sed -E 's/1220[0-9a-f]{60}/../g' | cut -c1-300
[info] Tests: succeeded 70, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-10T09:21:07.645Z","message":"Not serving sequencer SEQ::sv2::..b6a9 for synchronizer global-domain::..f3cf and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrat
***"@timestamp":"2026-11-10T09:21:07.646Z","message":"Not serving sequencer SEQ::sv2::..b6a9 for synchronizer global-domain::..f3cf and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrat
***"@timestamp":"2026-11-10T09:21:07.647Z","message":"Not serving sequencer SEQ::sv2::..b6a9 for synchronizer global-domain::..f3cf and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4","logger_name":"o.l.s.s.a.h.HttpScanHandler:ValidatorSequencerConnectionIntegrat
Total: 3 lines with problems.
```

Artifact: `gh run download 42785850771 --repo canton-network/splice -n logs-wall-clock-time-6 -D log/11327/logs-wall-clock-time-6`.

## 2. Which test was running

The flagged timestamps fall into the test case added by #8365.

```
$ zcat log/11327/logs-wall-clock-time-6/canton_network_test.clog.gz | grep -a -E 'Starting .ValidatorSequencerConnectionIntegrationTest|Test succeeded: .ValidatorSequencerConnectionIntegrationTest|Test failed: .ValidatorSequencerConnectionIntegrationTest' | jq -r '"\(."@timestamp") \(.message[0:170])"'
2026-11-10T09:12:03.799Z Starting 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers'...
2026-11-10T09:17:57.426Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers'
2026-11-10T09:17:57.426Z Starting 'ValidatorSequencerConnectionIntegrationTest/scan does not serve a sequencer id claimed by multiple SVs and validators connect to the remaining sequencers'...
2026-11-10T09:21:24.498Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/scan does not serve a sequencer id claimed by multiple SVs and validators connect to the remaining sequencers
```

## 3. Suppression ends on sv1Scan, the other scans still report the duplicate (AJ confirming check, part 1)

Per scan, the duplicate is logged as `Suppressed WARN` (INFO) while the test's log assertion is active. The last
suppressed line is sv1Scan at 09:21:06.252Z; sv1Scan never logs it unsuppressed, sv2-4 do at 09:21:07.645-.647Z.

```
$ zcat log/11327/logs-wall-clock-time-6/canton_network_test.clog.gz | grep -a 'claimed by multiple SVs' | jq -R -r 'fromjson? | select(.logger_name? // "" | test("HttpScanHandler")) | "\(."@timestamp") \(.level) \(.logger_name|capture("(?<s>scan=sv[0-9]Scan)").s) \(."trace-id") \(.message[0:22])"' | awk '$1>="2026-11-10T09:21:04"'
2026-11-10T09:21:04.957Z INFO scan=sv3Scan 8bbfe70bddaa32c34c1c72d1945786f7 Suppressed WARN: Not s
2026-11-10T09:21:04.958Z INFO scan=sv4Scan 8bbfe70bddaa32c34c1c72d1945786f7 Suppressed WARN: Not s
2026-11-10T09:21:04.961Z INFO scan=sv1Scan 8bbfe70bddaa32c34c1c72d1945786f7 Suppressed WARN: Not s
2026-11-10T09:21:06.250Z INFO scan=sv2Scan 8bbfe70bddaa32c34c1c72d1945786f7 Suppressed WARN: Not s
2026-11-10T09:21:06.251Z INFO scan=sv4Scan 8bbfe70bddaa32c34c1c72d1945786f7 Suppressed WARN: Not s
2026-11-10T09:21:06.252Z INFO scan=sv1Scan 8bbfe70bddaa32c34c1c72d1945786f7 Suppressed WARN: Not s
2026-11-10T09:21:07.645Z WARN scan=sv3Scan 8bbfe70bddaa32c34c1c72d1945786f7 Not serving sequencer 
2026-11-10T09:21:07.646Z WARN scan=sv4Scan 8bbfe70bddaa32c34c1c72d1945786f7 Not serving sequencer 
2026-11-10T09:21:07.647Z WARN scan=sv2Scan 8bbfe70bddaa32c34c1c72d1945786f7 Not serving sequencer 
```
```
$ zcat log/11327/logs-wall-clock-time-6/canton_network_test.clog.gz | grep -a 'claimed by multiple SVs' | jq -R -r 'fromjson? | select(.logger_name? // "" | test("HttpScanHandler")) | "\(.level) \(.logger_name|capture("(?<s>scan=sv[0-9]Scan)").s)"' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
1 WARN scan=sv2Scan
1 WARN scan=sv4Scan
11 INFO scan=sv2Scan
5 INFO scan=sv4Scan
10 INFO scan=sv1Scan
11 INFO scan=sv3Scan
1 WARN scan=sv3Scan
```

The test's last console request to `dso-sequencers` before the WARN returned at 09:21:07.419Z; the test case at
087f79bf73 polls only sv1ScanBackend in the restore clue:

```
$ zcat log/11327/logs-wall-clock-time-6/canton_network_test.clog.gz | grep -a '"@timestamp":"2026-11-10T09:21:07.4' | grep -a 'EnvironmentDefinition' | grep -a 'dso-sequencers' | jq -R -r 'fromjson? | "\(."@timestamp") \(.level) \(.logger_name[0:60]) | \(.message[0:120])"'
2026-11-10T09:21:07.419Z DEBUG o.l.s.i.EnvironmentDefinition$$anon$43:ValidatorSequencerCon | HTTP client (GET /api/scan/v0/dso-sequencers): HTTP request took 6 ms to complete. Received response with status code: 2
$ git show 087f79bf73:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/ValidatorSequencerConnectionIntegrationTest.scala | grep -n -A6 'Restoring sv4'
185:          withClue("Restoring sv4's sequencer id makes scan serve all sequencers again") {
186-            setSequencerId(sv4Backend, sv4SequencerId.toProtoPrimitive).futureValue
187-            eventually() {
188-              sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers).map(_.svName).toSet shouldBe
189-                Set(getSvName(1), getSvName(2), getSvName(3), getSvName(4))
190-            }
191-          }
```

## 4. The requests come from bobValidator's reconcile trigger (AJ confirming check, part 2)

```
$ zcat log/11327/logs-wall-clock-time-6/canton_network_test.clog.gz | grep -a 8bbfe70bddaa32c34c1c72d1945786f7 | grep -a -m1 'Starting trigger polling loop' | jq -r '"\(."@timestamp") \(.level) \(.logger_name) | \(.message)"'
2026-11-10T09:20:54.966Z INFO o.l.s.v.a.ReconcileSequencerConnectionsTrigger:ValidatorSequencerConnectionIntegrationTest/config=f62cb534/validator=bobValidator | Starting trigger polling loop, ConfigSummary(pollingInterval = 1 second, pollingJitter = 0.2, maxNumSilentPollingRetries = 3)
```

## 5. Parent closed by #8389, which is not in this run's sha

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11304 --jq '"#\(.number) \(.state)/\(.state_reason) assignees=\([.assignees[].login]|join(",")) title=\(.title)"'
#11304 closed/completed assignees=nicu-da title=ValidatorSequencerConnectionIntegrationTest: duplicate-sequencer WARN from other scans escapes the suppression block
$ gh pr view 8389 -R canton-network/splice --json number,title,mergedAt,body --jq '"#\(.number) \(.title) merged=\(.mergedAt)"'; gh pr view 8389 -R canton-network/splice --json body --jq .body | grep -i fixes
#8389 Check that all the scans were updated to avoid warnings merged=2026-11-10T09:25:35Z
fixes https://github.com/DACH-NY/cn-test-failures/issues/11304
- [ ] Specify fixed issues with `Fixes #n`, and mention issues worked on using `#n`
$ git merge-base --is-ancestor 087f79bf73 a9038ea432 && echo "087f79bf73 is ancestor of a9038ea432"; git merge-base --is-ancestor a9038ea432 087f79bf73 || echo "a9038ea432 not in 087f79bf73"
087f79bf73 is ancestor of a9038ea432
a9038ea432 not in 087f79bf73
```

The fix waits until all four scans serve the four sequencers inside the restore clue:

```
$ git show a9038ea432 -- '*.scala' | sed -n '/^@@/,$p'
@@ -185,8 +185,11 @@ class ValidatorSequencerConnectionIntegrationTest
           withClue("Restoring sv4's sequencer id makes scan serve all sequencers again") {
             setSequencerId(sv4Backend, sv4SequencerId.toProtoPrimitive).futureValue
             eventually() {
-              sv1ScanBackend.listDsoSequencers().flatMap(_.sequencers).map(_.svName).toSet shouldBe
-                Set(getSvName(1), getSvName(2), getSvName(3), getSvName(4))
+              forAll(Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend)) {
+                scanBackend =>
+                  scanBackend.listDsoSequencers().flatMap(_.sequencers).map(_.svName).toSet shouldBe
+                    Set(getSvName(1), getSvName(2), getSvName(3), getSvName(4))
+              }
             }
           }
         },
```

## 6. No hit after the fix (tracker search)

Every issue with the WARN text is 11304 or one of its duplicates from before the fix; 11327 itself is not yet indexed
by search. Post-merge main runs completed after #8389: 42791495886, 42794742288, 42799831067, 42804747879, 42810067070;
none filed an issue with this WARN (their failures in this round are other signatures).

```
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "claimed by multiple SVs"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason) \(.created_at[0:16]) \(.title[0:100])"'
#11304 closed/completed 2026-11-09T15:29 ValidatorSequencerConnectionIntegrationTest: duplicate-sequencer WARN from other scans escapes the s
#11316 closed/duplicate 2026-11-09T17:38 GHA Run 42658360509 : job wall-clock-time (5) in scala_test Failed :fire:
#11309 closed/duplicate 2026-11-09T15:43 GHA Run 42639148146 : job wall-clock-time (2) in scala_test Failed :fire:
#11307 closed/duplicate 2026-11-09T15:35 GHA Run 42639073931 : job wall-clock-time (2) in scala_test Failed :fire:
$ gh api "repos/canton-network/splice/actions/runs?branch=main&event=push&created=>=2026-11-10T09:25:00Z&per_page=50" --jq '.workflow_runs[] | select(.name|test("post-merge")) | "\(.id) \(.head_sha[0:10]) \(.status)/\(.conclusion) \(.created_at)"'
42823122392 1844b95301 in_progress/null 2026-11-10T13:17:08Z
42810067070 88491ce56e completed/failure 2026-11-10T11:45:33Z
42804747879 c119dc37fa completed/success 2026-11-10T11:06:41Z
42799831067 f78a3ebdeb completed/failure 2026-11-10T10:31:13Z
42794742288 6dbbda48ca completed/failure 2026-11-10T09:55:21Z
42791495886 f367de93c5 completed/success 2026-11-10T09:32:00Z
42790612651 a9038ea432 completed/cancelled 2026-11-10T09:25:38Z
42790576154 e59889cad0 completed/failure 2026-11-10T09:25:22Z
```

Run 42790576154 (e59889cad0) also predates the fix on main's first-parent history (newest first); its failures in
this round (11331, 11333) are other signatures.

```
$ git log --first-parent --format=%h origin/main | grep -n -E '^(e59889cad0|a9038ea432)'
7:a9038ea432
8:e59889cad0
```

## Verdict

Duplicate of 11304 (family AJ), test flake from #8365, already fixed by splice#8389 (a9038ea432), which this run
(087f79bf73) does not contain. Not a regression. Action: close as duplicate of 11304; no parent comment (the parent is
closed as fixed and this occurrence predates the fix). Not verified: that ValidatorSequencerConnectionIntegrationTest
ran in a post-fix shard (only that no post-fix issue carries the WARN).
