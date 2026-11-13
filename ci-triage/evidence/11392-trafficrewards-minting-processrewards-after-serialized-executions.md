# 11392 - TrafficBasedRewardsSvAppTimeBasedIntegrationTest: R8 minting ProcessRewardsV2 created 1.15 s after the 20 s wait (DSO delegate executes confirmed actions one at a time)

Verdict: new, test timing. The clue `ProcessRewardsV2 are created and RewardMetricsTrigger reports active counts`
waits 20 s (default `eventually()`) from the moment the CalculateRewards triggers resume. In that time three confirmed
`CRARC_StartProcessingRewardsV2` actions (R6 dry run, R8 dry run, R8 minting) must each be confirmed by 3 SVs and then
executed by the DSO delegate (sv4). `ExecuteConfirmedActionTrigger` takes one global write lock per SV app
(TODO(tech-debt) "Replace single lock by one per action"), so the three executions ran strictly one after another:
14:47:04.820, 14:47:09.415, 14:47:13.290. The R8 minting action was last; the wait had given up at 14:47:12.137.
The first execution's SubmitAndWait took 8.3 s (baseline on sv4 in the minutes before: median 1.7 s, max 3.9 s).
Not 11255: no blacklisting, no `P2P connectivity is not ready`, no rejected sends in the canton log of the window.

## 0. Issue and job

```
$ gh api repos/DACH-NY/cn-test-failures/issues/11392 --jq '.title, .body'
GHA Run 43236500158 : job simtime (0) in scala_test Failed :fire:
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43236500158).
Job: simtime (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [0911e29](https://github.com/canton-network/splice/commit/0911e29902a180ce65c1245da8a058fbe03bdd8f) WalletSurviveCantonRestartIntegrationTest: 5 min budget for the tap after the Canton restart (#8444)
Actor: stephencompall-DA
```
Job (recipe 1, NAME='simtime (0)'): `131245653457 attempt=1 ci / scala_test_sim_time / simtime (0)`.
Canton: `git show 0911e29902:nix/canton-sources.json | grep -m1 version` -> `"version": "3.7.3",`.
Artifact: `gh run download 43236500158 --repo canton-network/splice -n logs-simtime-0 -D log/11392/logs-simtime-0`
(artifact 13954683807, created 2026-11-12T14:58:19Z, attempt 1).

## 1. Failure

```
$ gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/131245653457/logs > log/11392/job.log   # recipe 1
$ sed -E 's/\x1b\[[0-9;]*m//g' log/11392/job.log | grep -a -A6 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | head -6
[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info]   0 was not equal to 1 (TrafficBasedRewardsSvAppTimeBasedIntegrationTest.scala:289)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
[info]   at org.scalatest.matchers.should.Matchers$AnyShouldWrapper.shouldBe(Matchers.scala:7539)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.TrafficBasedRewardsSvAppTimeBasedIntegrationTest.$anonfun$new$26(TrafficBasedRewardsSvAppTimeBasedIntegrationTest.scala:293)
```
The assertion (test at the run sha):
```
$ git show 0911e29902:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsSvAppTimeBasedIntegrationTest.scala | awk 'NR>=279 && NR<=296 {printf "%d: %s\n", NR, $0}'
279:         } // Resume CalculateRewardsTrigger(s)
280: 
281:         clue("ProcessRewardsV2 are created and RewardMetricsTrigger reports active counts") {
282:           eventually() {
283:             sv1RewardMetricsTrigger.runOnce().futureValue
284:             metricValue(
285:               sv1Backend,
286:               "process_rewards_v2.active_contracts",
287:               Map("dryRun" -> "true"),
288:             ) shouldBe 2L
289:             metricValue(
290:               sv1Backend,
291:               "process_rewards_v2.active_contracts",
292:               Map("dryRun" -> "false"),
293:             ) shouldBe 1L
294:           }
295:         }
```
So in the last attempt the dry-run count was already 2 and the minting count still 0. The Calculate triggers were
paused for the steps before (lines 181-183) and resume at line 279, right before this clue.

## 2. Test timeline

```
$ zcat log/11392/logs-simtime-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Failed clue|Running clue: (ProcessRewardsV2|CalculateRewardsV2|SV trigger|Scan metrics|vote to)|Finished clue: (ProcessRewardsV2|CalculateRewardsV2|SV trigger|Scan metrics)" | (format) | grep -a -A15 TrafficBasedRewardsSvApp
2026-11-12T14:38:08.069Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-12T14:38:08.105Z Starting 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure'...",
2026-11-12T14:44:04.105Z Running clue: vote to enable dryRunVersion",
2026-11-12T14:44:34.743Z Running clue: vote to disable dryRunVersion",
2026-11-12T14:45:17.004Z Running clue: vote to enable dryRunVersion + mintingVersion",
2026-11-12T14:46:52.009Z Running clue: CalculateRewardsV2 are created for rounds, 6 and 8",
2026-11-12T14:46:52.032Z Finished clue: CalculateRewardsV2 are created for rounds, 6 and 8",
2026-11-12T14:46:52.032Z Running clue: SV trigger tasks are created and metrics updated",
2026-11-12T14:46:52.103Z Finished clue: SV trigger tasks are created and metrics updated",
2026-11-12T14:46:52.103Z Running clue: CalculateRewardsV2 contracts are also visible in scan rewards reference store",
2026-11-12T14:46:52.105Z Finished clue: CalculateRewardsV2 contracts are also visible in scan rewards reference store",
2026-11-12T14:46:52.105Z Running clue: Scan metrics are updated",
2026-11-12T14:46:52.122Z Finished clue: Scan metrics are updated",
2026-11-12T14:46:52.124Z Running clue: ProcessRewardsV2 are created and RewardMetricsTrigger reports active counts",
2026-11-12T14:47:12.137Z Failed clue: ProcessRewardsV2 are created and RewardMetricsTrigger reports active counts",
2026-11-12T14:47:56.376Z Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', message: 0 was not equal to 1, lo
```
The wait lasted 20.0 s (14:46:52.124 to 14:47:12.137).

## 3. Confirmations: all three actions reach the threshold by 14:46:59

Window file: `W` = lines of canton_network_test.clog.gz with timestamps in [14:46:52, 14:47:57).
```
$ grep -a -E '"logger_name":"[^"]*CalculateRewards(DryRun)?Trigger[^"]*"' $W | grep -a -E 'Completed processing' | (format: time level sv trigger | message)
2026-11-12T14:46:55.811Z INFO sv4 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
2026-11-12T14:46:55.888Z INFO sv3 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
2026-11-12T14:46:55.894Z INFO sv4 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 6, processingDelay=PT20M20S",
2026-11-12T14:46:55.894Z INFO sv3 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 6, processingDelay=PT20M20S",
2026-11-12T14:46:57.629Z INFO sv2 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 6, processingDelay=PT20M20S",
2026-11-12T14:46:57.691Z INFO sv2 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
2026-11-12T14:46:57.777Z INFO sv1 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
2026-11-12T14:46:57.863Z INFO sv2 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
2026-11-12T14:46:57.988Z INFO sv1 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 6, processingDelay=PT20M20S",
2026-11-12T14:46:57.996Z INFO sv1 CalculateRewardsDryRunTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
2026-11-12T14:46:57.999Z INFO sv3 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
2026-11-12T14:46:59.387Z INFO sv4 CalculateRewardsTrigger | Completed processing with outcome: created confirmation for CalculateRewardsV2 round 8, processingDelay=PT0S",
```
The R8 minting action had its third confirmation (sv3) at 14:46:57.999.

Which action is the minting one: sv1's `CalculateRewardsTrigger` task `Task(round = 8, dryRun = false)` (trace
fc1ceb694e8f4d8e16f6c97a5ea33a5b) submitted the confirmation with `calculateRewardsCid` 00595a367fa3...:
```
$ grep -a 'tid:1f8b66e8b6f20ba6e038a4a79ca27623' $W | head -1 | grep -a -oE '"(logger_name|trace-id|span-name)":"[^"]*"'
"logger_name":"o.l.s.a.a.c.ApiClientRequestLogger:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=ee2469f0/SV=sv1"
"trace-id":"fc1ceb694e8f4d8e16f6c97a5ea33a5b"
"span-name":"CalculateRewardsTrigger"
$ grep -a '"trace-id":"fc1ceb694e8f4d8e16f6c97a5ea33a5b"' $W | ... | head -1
"@timestamp":"2026-11-12T14:46:52.649Z"	"message":"Processing\nTask(round = 8, dryRun = false)	"logger_name":"o.l.s.s.a.c.CalculateRewardsTrigger:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=ee2469f0/SV=sv1"
```
and that request (tid 1f8b66e8..., 14:46:52.660Z) carries `CRARC_StartProcessingRewardsV2 ... calculateRewardsCid,
Value( ContractId( 00595a367fa371cac0667068442ad28456bbe0094de38b2ec16e7a2c1ecd9e7973ca12122...`, batchHash
`c3866104c8c64c044e04246b90772f58ee748da8d4ae3095b6e43ab9b0ff386f` (python excerpt of the line).

## 4. Execution by the DSO delegate (sv4): one at a time

```
$ grep -a '"logger_name":"o.l.s.s.a.d.ExecuteConfirmedActionTrigger' $W | grep -a 'executed action' | (format) | cut -c1-...
2026-11-12T14:47:04.820Z Completed processing with outcome: executed action ARC_AmuletRules( Record( fields = CRARC_StartProcessingRewardsV2( Record( fields = Seq( ContractId(009b87b9ff0db00d...), Record(fields = \"02d077af719d4c987130c943506f07c8d180570cdfced27777f93e8a1a0915b9\") ) ) ) ) ) as there are 3 confirmation(s) which is >= the required 3 confirmations.
2026-11-12T14:47:09.415Z Completed processing with outcome: executed action ARC_AmuletRules( Record( fields = CRARC_StartProcessingRewardsV2( Record( fields = Seq( ContractId(00e63da594a82ad7...), Record(fields = \"c3866104c8c64c044e04246b90772f58ee748da8d4ae3095b6e43ab9b0ff386f\") ) ) ) ) ) as there are 4 confirmation(s) which is >= the required 3 confirmations.
2026-11-12T14:47:13.290Z Completed processing with outcome: executed action ARC_AmuletRules( Record( fields = CRARC_StartProcessingRewardsV2( Record( fields = Seq( ContractId(00595a367fa371ca...), Record(fields = \"c3866104c8c64c044e04246b90772f58ee748da8d4ae3095b6e43ab9b0ff386f\") ) ) ) ) ) as there are 4 confirmation(s) which is >= the required 3 confirmations.
```
(all three from `SV=sv4`; contract ids shortened here.) The R8 minting action (00595a367fa3) was executed at
14:47:13.290, 1.15 s after the wait ended. sv1's `ProcessRewardsTrigger` then picked up `Task(round = 8, dryRun = false)`
at 14:47:14.806.

sv4 executor tasks (trace id prefix) and the lock:
```
$ grep -a 'SV=sv4"' $W | grep -a 'Acquired lock after' | (time, message, trace prefix) | awk '$1>="14:46:55" && $1<"14:47:14"'
14:46:56.065Z	Acquired lock after PT0.000037S	209acac4	ExecuteConfirmedActionTrigger"
14:46:56.414Z	Acquired lock after PT0.000028S	b108d591	ExecuteConfirmedActionTrigger"
14:46:56.520Z	Acquired lock after PT0.000035S	644a598b	ExecuteConfirmedActionTrigger"
14:47:04.820Z	Acquired lock after PT8.144287S	3274df26	ExecuteConfirmedActionTrigger"
14:47:09.415Z	Acquired lock after PT12.159438S	6a7be0ae	ExecuteConfirmedActionTrigger"
14:47:09.416Z	Acquired lock after PT11.099898S	c96bf729	ExecuteConfirmedActionTrigger"
14:47:09.418Z	Acquired lock after PT3.722255S	1cedf7c0	ExecuteConfirmedActionTrigger"
14:47:13.290Z	Acquired lock after PT3.414013S	5fc5fefd	ExecuteConfirmedActionTrigger"
14:47:13.291Z	Acquired lock after PT2.939908S	b45fd7d5	ExecuteConfirmedActionTrigger"
14:47:13.292Z	Acquired lock after PT2.702427S	5accd817	ExecuteConfirmedActionTrigger"
$ (per-task timeline of the sv4 ExecuteConfirmedActionTrigger: time, first words, contract id prefix, trace prefix)
14:46:55.978 Processing AssignedContract(   009b87b9ff0d 644a598b
14:46:56.519 Completing dso delegate task A 009b87b9ff0d 644a598b
14:46:56.675 Completing dso delegate task A 00e63da594a8 3274df26
14:46:57.255 Completing dso delegate task A 009b87b9ff0d 6a7be0ae
14:46:58.316 Completing dso delegate task A 00e63da594a8 c96bf729
14:47:04.820 Completed processing with outc 009b87b9ff0d 644a598b
14:47:04.821 Processing AssignedContract(   00595a367fa3 1cedf7c0
14:47:09.415 Completed processing with outc 00e63da594a8 3274df26
14:47:09.418 Completed processing with outc 00e63da594a8 c96bf729
14:47:13.290 Completed processing with outc 00595a367fa3 1cedf7c0
```
(selected lines of the full timeline.) The four task slots (`AutomationConfig.parallelism`, default 4) were all held
by tasks for the two dry-run actions waiting on the lock; sv4 only started processing a confirmation of the minting
action at 14:47:04.821, then waited 3.7 s for the lock.

The one submission that held the lock longest:
```
$ grep -a '"trace-id":"644a598b78707779c6b8e106c9782eda"' $W | (time message) | cut -c1-200
2026-11-12T14:46:56.519Z Completing dso delegate task AssignedContract(...
2026-11-12T14:46:56.520Z Acquired lock after PT0.000035S
2026-11-12T14:46:56.527Z Request (tid:d9515491914bcc28cec1aa2bf343fd9a) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15401: sending request S
2026-11-12T14:47:04.816Z Request (tid:d9515491914bcc28cec1aa2bf343fd9a) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:15401: succeeded(OK)
2026-11-12T14:47:04.820Z Completed processing with outcome: executed action ARC_AmuletRules(...
```
sv4 SubmitAndWaitForTransaction latencies (python over the `sending request` / `succeeded` pairs with `SV=sv4`):
```
sv4 SubmitAndWaitForTransaction 14:38-14:46: n=7 median=1.71s p90=3.94s max=3.94s
14:46:50-14:47:15: [('14:46:56.527', 8.289), ('14:47:04.869', 4.541), ('14:47:09.473', 3.803)]
```

The lock, at the run sha:
```
$ git show 0911e29902:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/automation/delegatebased/ExecuteConfirmedActionTrigger.scala | sed -n 67,69p
  // Lock to avoid contention as any confirmation after the threshold will trigger a conflicting submission.
  // TODO(tech-debt) Replace single lock by one per action
  private val lock = new StampedLockWithHandle()
$ ... | sed -n 89p
        lock.withWriteLock {
$ git show 0911e29902:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/config/AutomationConfig.scala | sed -n 13,14p
    /** How many automation tasks should be run in parallel per kind of task. */
    parallelism: Int = 4,
```
(`git blame` of line 68: a6daa229d6e, moritzkiefer-da, 2025-07-17.)

## 5. Not the 11255 shapes

Canton log window `C` = canton-simtime.clog.gz lines in [14:46:50, 14:47:20):
```
currently blacklisted: 0
P2P connectivity is not ready: 0
Failed to send result: 0
SEQUENCER_BACKPRESSURE: 0
```
The only `Rejecting submission` lines are mediator `SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT` (normal aggregate
dedup). Ordering epochs on the global synchronizer: 41 at 14:46:50.1, 42 at 14:47:05.0, all four sequencers, no
topology change.

## 6. History and duplicates

```
$ git log --format='%h %ad %an %s' --date=short 0911e29902 -- apps/app/src/test/scala/.../TrafficBasedRewardsSvAppTimeBasedIntegrationTest.scala | head -3
d0a311567d 2026-10-28 Divam Daml for TBAR v1.1 Amendment (#7627)
0c8c834609 2026-10-23 Simon Meier Daml: compatibility mode for SV and FA locking (#7839)
ca71ec01e6 2026-10-22 Stephen Compall unused symbols cleanup skill; apply it (#7873)
$ git log --format=... 0911e29902..origin/main -- <same file>
(no output)
$ git log -L '/ProcessRewardsV2 are created and RewardMetricsTrigger/,+14:<file>' ... | grep -E '^[0-9a-f]{10} '
44fdc1f3cf 2026-06-29 Divam SV app: Add RewardMetricsTrigger (#6100)
2db99bd0d7 2026-06-18 Robert Autenrieth Add a metric for active CalculateRewardsV2 contracts (#5896)
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "TrafficBasedRewardsSvAppTimeBasedIntegrationTest"' --jq '.items[] | ...'
10323 closed 2026-10-09 Warning logs in TrafficBasedRewardsSvAppTimeBasedIntegrationTest: `A database task was rejected from
11374 closed 2026-11-11 GHA Run 43073759004 : job simtime (0) in scala_test Failed :fire:
11324 closed 2026-11-10 GHA Run 42766394442 : job simtime (0) in scala_test Failed :fire:
11177 closed 2026-11-04 GHA Run 41776986892 : job simtime (0) in scala_test Failed :fire:
11334 closed 2026-11-10 GHA Run 42794742288 : job simtime (3) in scala_test Failed :fire:
11326 closed 2026-11-10 GHA Run 42780003623 : job simtime (0) in scala_test Failed :fire:
11255 open 2026-11-06 Timeouts due to changing bft ordering topology
11332 closed 2026-11-10 GHA Run 42790498517 : job simtime (3) in scala_test Failed :fire:
9955 closed 2026-09-03 TrafficBasedRewardsSvAppTimeBasedIntegrationTest: log/canton_network_test.clog contains problems.
10197 closed 2026-09-21  TrafficBasedRewardsSvAppTimeBasedIntegrationTest: the "only sv1 and sv4 confirm round 9" check wait
```
Per hit: 10323 (DB task rejected WARN), 9955 (checkErrors) - different. 11374, 11324, 11326, 11177 - setup/environment
failures (11255 rejected-send / lost-verdict / batch shapes, IMPORT_ACS_ERROR), closed as dups per their comments;
11334, 11332 - other suites in the 11255 family; 11255 - the parent; 10197 - a different clue (`only sv1 and sv4
confirm round 9`, a slow confirmation under the reference driver). None is this clue: first failure of the
`ProcessRewardsV2 are created` clue in the tracker. `"dryRunVersion/mintingVersion"` finds only 10197;
`"process_rewards_v2"` finds nothing.

## Verdict

- Cause: test wait too short for the work it covers. After the Calculate triggers resume, the clue's 20 s must cover
  three CalculateRewardsV2 confirmations per SV plus three confirmed-action executions that the DSO delegate runs one
  at a time behind a single lock. Here the chain finished at 14:47:13.290 (21.2 s after the triggers resumed), driven
  by one slow SubmitAndWait (8.3 s vs a 1.7 s median before) and the serialization. Flake, first hit.
- Fix (test): wait longer in this clue, for example `eventually(60.seconds)` at
  TrafficBasedRewardsSvAppTimeBasedIntegrationTest.scala:282. Optional app improvement (tech debt already noted in the
  code): one lock per action in `ExecuteConfirmedActionTrigger` (line 68), so independent actions execute in parallel.
- Not verified: why that one sv4 submission took 8.3 s (participant or sequencer side); whether the default
  `eventually()` budget is exactly 20 s (observed 20.0 s).
