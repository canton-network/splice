# 10183 or 10184 (job simtime (2); Raymond to confirm the mapping) - globalMediatorSv3's pruning scheduler fires 160 ms after a 30-day sim-time jump and WARNs that now - retention is 30 s past the mediator's safe pruning bound (run 35611022158)

New, sim-time only, test-infrastructure. All 18 tests pass; checkErrors fails on one WARN from
`MediatorPruningScheduler` on globalMediatorSv3 at 14:43:45.424: requested pruning timestamp 1970-01-31T07:25:11Z is
later than the earliest available pruning timestamp 1970-01-31T07:24:41.000003Z. WalletTimeBasedIntegrationTest
advanced sim time by 30 days at 14:43:45.262 (from 07:25:11 to 03-02 07:25:11). The scheduler computes the requested
timestamp as `clock.now - retention` with the SV default retention of exactly 30 days, so it landed on the pre-jump
time 07:25:11, which is also the mediator's clean (last processed) timestamp. `Mediator.prune` refuses anything later
than clean timestamp minus `confirmationResponseTimeout` (30 s), hence 07:24:41.000003. sv2 and sv4 ran their windows 2
and 5 s later, after processing a post-jump event, and pruned fine; sv1 had run 41 s earlier. The scheduler logs the
refusal at WARN and retries at its next window. In production the clock does not jump, so `now - 30 d` is always far
below `clean - 30 s`; the line can only fire in sim time after a jump of at least the retention period.

- Run: https://github.com/canton-network/splice/actions/runs/35611022158 (main 4f5d6220eb, "upgrade the observability
  stack (#7433)", run still in progress when triaged), job 106370595499 `ci / scala_test_sim_time / simtime (2)`.
  The run has a second failed job, 106370913055 `wall-clock-time (2)`, triaged separately; the ref-to-job mapping is
  open until Raymond confirms it.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824 (`git show 4f5d6220eb:nix/canton-sources.json`).
- Component: test infrastructure (sim-time clock jumps versus the Canton mediator pruning scheduler); fix is an
  ignore pattern in the sim-time-only ignore file.

## 1. Classification: all tests pass, one non-ignored WARN

```
gh api repos/canton-network/splice/actions/jobs/106370595499/logs > log/10183-10184-simtime-2/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10183-10184-simtime-2/job.log | grep -a -E 'Tests: succeeded|contains problems' | sed -E 's/^[^Z]*Z //' | sort -u
grep -a '^\S*Z \*\*\*"@timestamp"' log/10183-10184-simtime-2/job.log | grep -a -v 'ignore this line' | sed -E 's/^[^Z]*Z \*\*\*//; s/\\n/ /g' | cut -c1-520
grep -a '^\S*Z \*\*\*"@timestamp"' log/10183-10184-simtime-2/job.log | grep -a -c 'ignore this line'
```
```
[error] (checkErrors) log/canton-simtime_before_shutdown.clog contains problems.
[info] Tests: succeeded 18, failed 0, canceled 0, ignored 0, pending 0
"@timestamp":"2026-09-21T14:43:45.424Z","message":"Backing off 1s or until next window after error: Requested pruning timestamp [1970-01-31T07:25:11Z] is later than the earliest available pruning timestamp [1970-01-31T07:24:41.000003Z]","logger_name":"c.d.c.s.m.MediatorPruningScheduler:mediator=globalMediatorSv3/psid=global-domain::122097dc7f84::36-0","thread_name":"canton-env-ec-402","level":"WARN","trace-id":"199bb03c70a0962541de9162c34d11e9","span-name":"run_scheduler_job","span-id":"64f53dd8a2655234"
348
```
Shard suites: DisabledWalletTimeBasedIntegrationTest SvTimeBasedRoundMgmtIntegrationTest
TokenStandardCliTestDataTimeBasedIntegrationTest TrafficBasedRewardsDryRunTimeBasedIntegrationTest
WalletMintingDelegationTimeBasedIntegrationTest WalletTimeBasedIntegrationTest
WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest. No family in the catalogue and no prior packet mentions
`MediatorPruningScheduler`; `project/ignore-patterns/` has no pruning pattern.

## 2. The jump: 30 days at 14:43:45.262, in WalletTimeBasedIntegrationTest

```
T=log/10183-10184-simtime-2/logs-simtime-2/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting '|advancing time by" | grep -a -E 'T14:43:(0[9]|[1-5])' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-130
```
```
2026-09-21T14:43:09.956Z Starting 'WalletTimeBasedIntegrationTest/A wallet should allow a user to list multiple subscriptions in different states'...
2026-09-21T14:43:15.125Z advancing time by PT240H to 1970-01-11T06:55:11Z
2026-09-21T14:43:21.863Z advancing time by PT10M to 1970-01-11T07:05:11Z
2026-09-21T14:43:25.432Z advancing time by PT240H to 1970-01-21T07:05:11Z
2026-09-21T14:43:32.138Z advancing time by PT10M to 1970-01-21T07:15:11Z
2026-09-21T14:43:37.027Z advancing time by PT240H to 1970-01-31T07:15:11Z
2026-09-21T14:43:43.732Z advancing time by PT10M to 1970-01-31T07:25:11Z
2026-09-21T14:43:45.262Z advancing time by PT720H to 1970-03-02T07:25:11Z
2026-09-21T14:43:51.958Z advancing time by PT10M to 1970-03-02T07:35:11Z
```
```
git show cn/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/WalletTimeBasedIntegrationTest.scala | sed -n '122,127p'
```
```
              // Renewal duration is 30 days and entry lifetime is 90 days.
              // To reach the renewal period of alice1 subscription, we need to advance (90 - 30 days) = 60 days
              // We have already advanced by 30 days previously (10 days * 3)
              // So we will have to advance  (90 - 30 - 30 days) = 30 days to make alice1 subscription expired
              // TODO (#996): consider replacing with stopping and starting triggers
              advanceTimeAndWaitForRoundAutomation(Duration.ofDays(30))
```

## 3. The scheduler run, 160 ms after the jump

```
C=log/10183-10184-simtime-2/logs-simtime-2/canton-simtime_before_shutdown.clog.gz
zcat $C | grep -a 'MediatorPruningScheduler' | grep -a -v '"level":"DEBUG"' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,150}).*mediator=([A-Za-z0-9]+)[^"]*".*"level":"([A-Z]+)".*/\1 \4 \3 \2/' | grep -a -E 'T14:4[3-7]'
zcat $C | grep -a 'MediatorPruningScheduler:mediator=globalMediatorSv3' | grep -a -E '"@timestamp":"2026-09-21T14:43:45' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(.{0,110}).*"level":"([A-Z]+)".*/\1 \3 \2/'
zcat $C | grep -a 'mediator=globalMediatorSv3' | grep -a -E '"@timestamp":"2026-09-21T14:43:4[3-5]' | grep -a -oE 'Advancing sim clock to [^"]+|timestamp = 1970-01-31T07:25:11\.[0-9]+Z' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
```
```
2026-09-21T14:43:04.049Z INFO globalMediatorSv1 Pruned up to 1969-12-02T06:55:11Z
2026-09-21T14:43:45.424Z WARN globalMediatorSv3 Backing off 1s or until next window after error: Requested pruning timestamp [1970-01-31T07:25:11Z] is later than the earliest available pruning timestamp [1970-01-31T07:24:41.000003Z]
2026-09-21T14:43:47.515Z INFO globalMediatorSv2 Pruned up to 1970-01-31T07:25:11Z
2026-09-21T14:43:50.192Z INFO globalMediatorSv4 Pruned up to 1970-01-31T07:25:11Z
2026-09-21T14:47:53.088Z INFO globalMediatorSv1 Pruned up to 1970-02-04T09:20:32Z
2026-09-21T14:43:45.403Z DEBUG Starting scheduler job
2026-09-21T14:43:45.404Z DEBUG About to prune up to 1970-01-31T07:25:11Z
2026-09-21T14:43:45.424Z DEBUG Completed scheduler job
2026-09-21T14:43:45.424Z WARN Backing off 1s or until next window after error: Requested pruning timestamp [1970-01-31T07:25:11Z] is later than ...
1 Advancing sim clock to 1970-03-02T07:25:11Z
2 Advancing sim clock to 1970-01-31T07:25:11Z
4 timestamp = 1970-01-31T07:25:11.000003Z
```
sv3's clock became 1970-03-02T07:25:11 at 14:43:45.263; its scheduler started at .403 and asked to prune up to
03-02 07:25:11 minus 30 days = 01-31 07:25:11. The last event sv3 had processed was sequenced at 01-31
07:25:11.000003 (the time-proof and tick traffic right after the previous 10-minute jump). The refusal bound is that
clean timestamp minus 30 s. sv2 and sv4, whose windows opened 2 and 5 s later, pruned the same 07:25:11 without a
complaint: by then their clean timestamps were past the jump. 348 ignored lines in the same shard are the usual
sim-time timeout noise (`canton_log_simtime_extra.ignore.txt`).

## 4. Where the numbers come from

Retention 30 days and a 10-minute cron are the SV app's defaults for the mediator pruning schedule, which the SV app
installs on its mediator at startup (`SvApp.scala:422-424` -> `LocalSynchronizerNode.ensureMediatorPruningSchedule`);
no test config overrides them:
```
git show cn/main:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/config/SvAppConfig.scala | sed -n '637,643p'
git grep -n -iE 'pruning|retention' cn/main -- 'apps/app/src/test/resources/**' | grep -v '#'
```
```
    pruning: Option[PruningConfig] = Some(
      PruningConfig(
        cron = "0 /10 * * * ?", // Run every 10min,
        maxDuration = PositiveDurationSeconds.ofMinutes(5),
        retention = PositiveDurationSeconds.ofDays(30),
      )
    ),
apps/app/src/test/resources/include/sequencers.conf:26:    batching.max-pruning-time-interval = "10 minutes"
```
Canton side (public mirror `digital-asset/canton`, state 2026-09-15.22; the running jar 3.6.0-snapshot.20260916 has
the same `retentionTimestamp` field in `MediatorPruningScheduler` and the `CannotPruneAtTimestamp` message text):
```
community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/mediator/MediatorPruningScheduler.scala:63
      retentionTimestamp = clock.now - pruningSchedule.retention
community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/mediator/Mediator.scala:278-283, 314-323, 498-503
      _ <- EitherT.cond(timestamp <= cleanTimestamp, (), PruningError.CannotPruneAtTimestamp(timestamp, cleanTimestamp))
      val latestSafePruningTs = Mediator.latestSafePruningTsBefore(synchronizerParametersChanges, cleanTimestamp)
      _ <- EitherTUtil.condUnitET(pruneAt <= latestSafePruningTs, PruningError.CannotPruneAtTimestamp(pruneAt, latestSafePruningTs))
    lazy val timeout = synchronizerParameters.parameters.confirmationResponseTimeout
    lazy val cappedSafePruningTs = synchronizerParameters.validFrom.max(cleanTs - timeout)
```
So the bound is `clean timestamp - confirmationResponseTimeout`; 07:25:11.000003 - 07:24:41.000003 = 30 s, the
default timeout. The scheduler's own comment (MediatorPruningScheduler.scala:74-77) says errors are surfaced at WARN
"to raise operator visibility as these are not expected to happen in production scenarios with long retention
values". A sim-time jump of at least the retention period is exactly the case they did not have in mind.

## 5. Not a regression, not seen in the other sim-time artifacts here

```
for d in log/10154/logs-simtime-0 log/10166/logs-simtime-2 log/10170/logs-simtime-0 log/10171/logs-simtime-3 log/10173/logs-simtime-2; do ... grep -c 'Backing off' / 'Pruned up to' ...; done   # log/10183-10184-simtime-2/prior-occurrences.txt
```
```
log/10154/logs-simtime-0 3.5.17 backoff_warns=0 pruned_up_to=6
log/10166/logs-simtime-2 3.5.18-snapshot.20260916.19252.0.v9635aea8 backoff_warns=0 pruned_up_to=8
log/10170/logs-simtime-0 3.5.18 backoff_warns=0 pruned_up_to=4
log/10171/logs-simtime-3 3.5.18 backoff_warns=0 pruned_up_to=33
log/10173/logs-simtime-2 3.5.18 backoff_warns=0 pruned_up_to=12
```
Those five shards (release lines, canton 3.5.x) did not contain WalletTimeBasedIntegrationTest's 30-day jump. The
race needs a jump of at least `retention - confirmationResponseTimeout` and a mediator whose 10-minute cron window
opens in the seconds before it processes a post-jump event; with four mediators and per-mediator cron phase that is a
few-percent-per-jump event, not a regression of 4f5d6220eb (the two commits since the last triaged sha touch the
observability stack and an ignore pattern for DownloadTopologyStateForInit).

## 6. Fix

Branch `ray/fix-10183-10184-simtime-2-mediator-pruning-backoff-ignore` (see README for the sha; to be renamed to the
confirmed ref), off cn/main: one pattern appended to `project/ignore-patterns/canton_log_simtime_extra.ignore.txt`,
the file that exists for "issues in simtime where in-flight requests end up timing out when we concurrently advance
time":
```
Backing off .* after error: Requested pruning timestamp .* is later than the earliest available pruning timestamp.*MediatorPruningScheduler
```
The logger name is matched after the message because check-logs runs ripgrep over the whole JSON line, where
`logger_name` follows `message`. Why an ignore and not a config or test change: in wall-clock CI the mediator never has 30 days of data, so sim time
is the only place mediator pruning does real work in CI (`Pruned up to` appears 4 to 33 times per sim-time shard);
raising the retention or shrinking the jumps would remove that coverage. The pattern is scoped to sim-time logs and to
the scheduler's retry message, and in a sim-time run the refusal can only mean a jump landed inside the timeout
margin, which the next window resolves. In production the same line means the mediator's clean timestamp lags the
clock by more than the retention period and stays visible, because `canton_log.ignore.txt` is untouched.
Verified with ripgrep: the run's WARN line and the job console line match (1 each), a `Pruned up to` INFO line does not
(0), and the whole canton-simtime log has exactly 1 match; ASCII only; not run through CI.

## Verdict

- New family (sim-time clock jump versus mediator pruning scheduler); flake in CI terms, deterministic given a jump of
  at least retention - 30 s followed by a scheduler window before the mediator's next event.
- Fix: sim-time-only ignore pattern, written and rg-verified. Alternative if the team prefers no ignore: ask Canton to
  log `CannotPruneAtTimestamp` from the scheduler at INFO when the requested timestamp exceeds the bound by less than
  the confirmation response timeout.
- Not verified: the ref mapping (10183 or 10184); the exact cron phase of each mediator's window (the 10-minute cron
  is anchored to wall-clock minutes, the scheduler runs on the sim clock).
