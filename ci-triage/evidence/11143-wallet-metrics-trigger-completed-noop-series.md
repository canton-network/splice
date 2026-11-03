# 11143 - WalletMetricsTest: trigger.completed lookup finds 2 matching metrics (run 41481391794)

DUPLICATE of #10732 (second hit, same assertion), mechanism now identified. Main 233250e5a9 (#8043, dependency bump
unrelated to the test), job `wall-clock-time (3)`: 31 of 32 tests pass; WalletMetricsTest "User wallet automation
metrics should are labeled with the party ID of the wallet user" fails with `CommandFailure` because
`aliceValidatorBackend.metrics.get("...trigger.completed", Map(trigger_name -> DomainIngestionService, party ->
alice tc1))` finds 2 series for 20 s. `trigger.completed` carries an `outcome` label (TaskbasedTrigger.scala:188-241).
The per-party DomainIngestionService is a quiet PeriodicTaskTrigger polling every 30 s (jitter 0.2): its first poll
(17:04:14.209) ingests and marks `outcome=success`; the next poll finds nothing and marks `outcome=noop` without
logging anything. The test only reaches the check 34.6 s after the first poll (10732: 32.3 s), past the earliest
second poll, so the lookup is ambiguous and stays ambiguous for the rest of the `eventually`. Test bug, not product.

- Run: https://github.com/canton-network/splice/actions/runs/41481391794, main 233250e5a9 ("[ci] Bump
  @mui/x-date-pickers from 8.14.1 to 9.14.0 in /apps (#8043)"), job 125401094260 `wall-clock-time (3)`.
- Runtime canton: 3.7.1 (`nix/canton-sources.json` at 233250e5a9).
- Component: test (`WalletMetricsTest.scala`).

## 1. Failing assertion

The job log shows one failed test and a bare `CommandFailure`.
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11143/job.log | grep -a -A14 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running' | head -12
```
```
[info] - should are labeled with the party ID of the wallet user *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
[info] Run completed in 27 minutes, 30 seconds.
[info] Total number of tests run: 32
[info] Suites: completed 9, aborted 0
[info] Tests: succeeded 31, failed 1, canceled 0, ignored 0, pending 0
[info] *** 1 TEST FAILED ***
[info] WalletMetricsTest:
[info] User wallet automation metrics should
[info] - are labeled with the party ID of the wallet user *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
```

The test log (artifact `logs-wall-clock-time-3`, id 13162886341) has the cause: the same `Found 2 matching metrics`
as #10732, on every retry of the clue.
```
gh api /repos/canton-network/splice/actions/artifacts/13162886341/zip > log/11143/art.zip
zcat log/11143/art/canton_network_test.clog.gz | grep -a -E 'WalletMetricsTest' | grep -a -E '"level":"(ERROR|WARN)"|matching metric|Failed clue' | sed -E 's/"logger_name".*"level":"([A-Z]+)".*/ [\1]/' | cut -c1-200 | head -3
zcat log/11143/art/canton_network_test.clog.gz | grep -a 'clue: DomainIngestionService should report task completions' | grep -a -oE '"@timestamp":"[^"]*|"message":"[^"]{0,70}' | paste - - | sed -E 's/"[a-z@_]+":"//g' | sed -n '1p;$p'
```
```
{"@timestamp":"2026-11-02T17:03:47.836Z","message":"Encountered 4 consecutive transient failures (polling interval 1 second).\nThe last one was:", [WARN]
{"@timestamp":"2026-11-02T17:04:48.806Z","message":"Found 2 matching metrics\n  Command ConsoleEnvironment.run$ invoked from SpliceConsoleEnvironment.scala:34", [ERROR]
{"@timestamp":"2026-11-02T17:04:48.806Z","message":"Failed clue: DomainIngestionService should report task completions correctly", [ERROR]
2026-11-02T17:04:48.803Z	Running clue: DomainIngestionService should report task completions co
2026-11-02T17:05:08.791Z	Failed clue: DomainIngestionService should report task completions cor
```
The polling-trigger clues and `AcceptedTransferOfferTrigger should report task completions` pass in the same
attempt; only the DomainIngestionService task-completion lookup fails, 17 times in 20 s.

## 2. What the test does

`WalletMetricsTest.scala` at 233250e5a9, lines 125-142 (unchanged on main since #5297, 2026-04-29):
```
git show 233250e5a9:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletMetricsTest.scala | sed -n 125,142p
```
```
        Seq(
          "AcceptedTransferOfferTrigger",
          "DomainIngestionService",
        ).foreach(triggerName =>
          clue(s"$triggerName should report task completions correctly") {
            aliceValidatorBackend.metrics
              .get(
                s"$MetricsPrefix.trigger.completed",
                Map(
                  "trigger_name" -> triggerName,
                  "party" -> aliceUserParty.toString,
                ),
              )
              .select[MetricValue.LongPoint]
              .value
              .value should be > 0L
          }
        )
```
`metrics.get` requires exactly one series matching the given labels.

## 3. trigger.completed has one series per outcome

```
git show 233250e5a9:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/TaskbasedTrigger.scala | sed -n 183,214p
```
```
              case TaskSuccess(description) =>
                if (quiet)
                  logger.info(show"Completed processing $task with outcome: $description")
                else
                  logger.info(show"Completed processing with outcome: $description")
                MetricsContext.withExtraMetricLabels(("outcome", "success")) { m =>
                  metrics.completed.mark()(m)
                }
                Success(true)
              case TaskFailed(description) =>
                if (quiet)
                  logger.warn(show"Failed processing $task with outcome: $description")
                else
                  logger.warn(show"Failed processing with outcome: $description")
                MetricsContext.withExtraMetricLabels(("outcome", "fail")) { m =>
                  metrics.completed.mark()(m)
                }
                Success(false)
              case TaskNoop =>
                if (!quiet) logger.info(show"$TaskNoop")
                MetricsContext.withExtraMetricLabels(("outcome", "noop")) { m =>
                  metrics.completed.mark()(m)
                }
                // Signal to polling triggers that no work was done, and it's not worth retrying immediately.
                Success(false)
              case TaskStale =>
                if (!quiet) logger.info(show"$TaskStale")
                MetricsContext.withExtraMetricLabels(("outcome", "stale")) { m =>
                  metrics.completed.mark()(m)
                }
                Success(true)
```
`fail`, `expected_failure` and `failure` log WARN/ERROR; the suite has no such line from DomainIngestionService
(section 1 lists every WARN/ERROR of the suite), so the second series is `noop` or `stale`.

DomainIngestionService is quiet and returns `TaskNoop` when there is nothing to ingest; periodic tasks are never
stale (`PeriodicTaskTrigger.isStaleTask` returns false), so the second series is `outcome=noop`.
```
git show 233250e5a9:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/DomainIngestionService.scala | grep -n -E 'TaskNoop|TaskSuccess|quiet|override'
git show 233250e5a9:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/PeriodicTaskTrigger.scala | sed -n 39,50p
```
```
23:      quiet = true,
26:  override protected def extraMetricLabels = Seq("party" -> sink.ingestionFilter.toString)
28:  override def completeTask(
36:      case Some(changeSummary) => TaskSuccess(show"Ingested domain store changes $changeSummary")
37:      case None => TaskNoop
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    processTaskWithRetry(PeriodicTaskTrigger.PeriodicTask(context.clock.now))
      .map(_ => false)
  }

  override def isStaleTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit tc: TraceContext): Future[Boolean] = {
    // Periodic tasks are never stale, as they have no precondition for their execution.
    Future.successful(false)
  }
```
`performWorkIfAvailable` always returns false, so the next poll comes one polling interval later.

## 4. Timing: the check comes after the second poll

The per-party trigger of the test's alice user (`tc1`) starts and ingests once at 17:04:14.209, with a 30 s polling
interval; no further log line until shutdown (a noop poll is silent).
```
zcat log/11143/art/canton_network_test.clog.gz | awk '/"@timestamp":"2026-11-02T17:0[3-5]/' | grep -a '"logger_name":"[^"]*DomainIngestionService[^"]*aliceValidator' | grep -a -oE '"@timestamp":"[^"]*|"message":"[^"]{0,120}|"logger_name":"[^"]*' | paste - - - | sed -E 's/"[a-z@_]+":"//g; s/::1220[0-9a-f]{60}/::../g' | grep -a 'tc1' | cut -c1-200
```
```
2026-11-02T17:04:14.205Z	Starting trigger polling loop, ConfigSummary(pollingInterval = 30 seconds, pollingJitter = 0.2, maxNumSilentPollingRetri	o.l.s.a.DomainIngestionService:WalletMetricsTest/config=e43236ee/validator=aliceValidator/endUserParty=alice__wallet__user-e43236ee__tc1::1220a5435953
2026-11-02T17:04:14.209Z	Completed processing PeriodicTask(for = 2026-11-02T17:04:14.206379Z) with outcome: Ingested domain store changes (addedD	o.l.s.a.DomainIngestionService:WalletMetricsTest/config=e43236ee/validator=aliceValidator/endUserParty=alice__wallet__user-e43236ee__tc1::1220a5435953
2026-11-02T17:05:20.012Z	Attempting to close 'AsyncCloseable(name=trigger polling loop)'...	o.l.s.a.DomainIngestionService:WalletMetricsTest/config=e43236ee/validator=aliceValidator/endUserParty=alice__wallet__user-e43236ee__tc1::1220a5435953
2026-11-02T17:05:20.013Z	Successfully closed 'AsyncCloseable(name=trigger polling loop)'.	o.l.s.a.DomainIngestionService:WalletMetricsTest/config=e43236ee/validator=aliceValidator/endUserParty=alice__wallet__user-e43236ee__tc1::1220a5435953
```
First check 17:04:48.803 = 34.6 s after the first poll; the second poll is due 24-36 s after it (30 s, jitter 0.2).

Same in #10732 (artifact `logs-wall-clock-time-8`, id 12810992239): first poll 11:00:36.472, first check
11:01:08.766 = 32.3 s later, then 20 s of `Found 2 matching metrics`.
```
gh api /repos/canton-network/splice/actions/artifacts/12810992239/zip > log/10732/art.zip
zcat log/10732/art/canton_network_test.clog.gz | grep -a -E 'DomainIngestionService:WalletMetricsTest/[^"]*endUserParty=alice__wallet__user' | grep -a -oE '"@timestamp":"[^"]*|"message":"[^"]{0,70}|"logger_name":"[^"]*' | paste - - - | sed -E 's/"[a-z@_]+":"//g; s/::1220[0-9a-f]{60}//g; s/o.l.s.a.DomainIngestionService:WalletMetricsTest\/config=[0-9a-f]+\/validator=//' | grep -a 'tc1' | grep -av close
zcat log/10732/art/canton_network_test.clog.gz | grep -a 'clue: DomainIngestionService should report task completions' | grep -a -oE '"@timestamp":"[^"]*|"message":"[^"]{0,70}' | paste - - | sed -E 's/"[a-z@_]+":"//g' | sed -n '1p;$p'
```
```
2026-10-28T11:00:36.469Z	Starting trigger polling loop, ConfigSummary(pollingInterval = 30 seco	aliceValidator/endUserParty=alice__wallet__user-1706653d__tc1::122047d6ef30
2026-10-28T11:00:36.472Z	Completed processing PeriodicTask(for = 2026-10-28T11:00:36.469915Z) w	aliceValidator/endUserParty=alice__wallet__user-1706653d__tc1::122047d6ef30
2026-10-28T11:01:08.766Z	Running clue: DomainIngestionService should report task completions co
2026-10-28T11:01:28.755Z	Failed clue: DomainIngestionService should report task completions cor
```
Once the noop series exists it never goes away, so the `eventually` cannot recover: the test passes only when the
tap and the transfer finish before the second poll (in both hits they took more than 30 s).

## Verdict

Duplicate of #10732, flake in the test; new family AC (provisional letter). Not product: the metric correctly has one
series per outcome. Fix (test, not written): in `WalletMetricsTest.scala` lines 133-136, add `"outcome" ->
"success"` to the label map of the `trigger.completed` lookup, so it reads `Map("trigger_name" -> triggerName,
"party" -> aliceUserParty.toString, "outcome" -> "success")`. Both task-based triggers checked there mark
`success` on the tasks the test causes. Alternative: sum `.value` over all matching series (needs a `list`-style
metrics accessor instead of `get`).

Not verified: the second poll itself is not logged (quiet TaskNoop), so `outcome=noop` is derived from the code
and the absence of WARN/ERROR lines, not observed; the exact labels of the two series are not in the logs. Passing
runs were not checked for a check gap under 24 s.
