# 11189, 11191 - BootstrapPackageConfigIntegrationTest: upgraded AmuletRules not visible within 45 s, close vote stuck in ordering (runs 41847691151, 41881231793)

DUPLICATE of 11160 (family L). main a22f7adb43 and ab4c59becb, job resource-intensive (0), 3 of 4 tests passed. The
check `observe AmuletRules with upgraded config` (BootstrapPackageConfigIntegrationTest.scala:291, 45 s) failed with
`"0.1.[19]" was not equal to "0.1.[25]"`: the vote request closed on time, but the DsoRules_CloseVoteRequest
transaction waited 20 to 26 s in the reference sequencer ordering (`insert block` 40001 retry chain on
globalSequencerSv1) and completed after the check had given up.

- Run 11189: https://github.com/canton-network/splice/actions/runs/41847691151, main a22f7adb43 ("Replace package versions in `apps/generated-packages.json`. (#8194)"), job 126636228215 `resource-intensive (0)`, attempt 1.
- Run 11191: https://github.com/canton-network/splice/actions/runs/41881231793, main ab4c59becb ("Drop splice-test-postgres (#8242)"), job 126754644089 `resource-intensive (0)`, attempt 1.
- Runtime canton: 3.7.1 (a22f7adb43), 3.7.2-snapshot.20261104.20872.0.vb10bb82c (ab4c59becb).
- Component: test infra (reference sequencer driver), test budget.

## 1. Failing assertion (both jobs)

```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/<ref>/job.log | grep -a -A1 'upgrade to latest \*\*\* FAILED' | head -2
```
```
[info] - Bootstrap with specific versions and then upgrade to latest *** FAILED ***
[info]   Check observe AmuletRules with upgraded config for sv1-3 accept vote request for upgraded packages "0.1.[19]" was not equal to "0.1.[25]" (BootstrapPackageConfigIntegrationTest.scala:291)
```
Same in 11189 and 11191. The check is `actAndCheck(timeUntilSuccess = 45.seconds)` after sv1-3 cast their votes; the
vote request expires 19 s after creation (`expiration = new RelTime(19_000_000)`) and CloseVoteRequestTrigger then
executes it, so about 20 s of the budget remain for the close transaction.

## 2. Test timeline (11191)

```
zcat log/11191/art/canton_network_test.clog.gz | grep -a 'BootstrapPackageConfigIntegrationTest:BootstrapPackageConfigIntegrationTest' | grep -aE '(Running|Finished|Failed) clue: \((act|check)\) (Creating vote|sv1-3 accept|observe AmuletRules with upgraded)' | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]*"' | paste -d' ' - -
```
```
18:15:20.971 Running clue: (act) Creating vote request for upgraded packages
18:15:24.187 Finished clue: (act) sv1-3 accept vote request for upgraded packages
18:15:24.187 Running clue: (check) observe AmuletRules with upgraded config
18:16:09.204 Failed clue: (check) observe AmuletRules with upgraded config
```
(timestamps shortened)

## 3. The close transaction (11191)

CloseVoteRequestTrigger: readyAt 18:15:40.96 (expiry), processed 18:15:45.97 to 18:15:47.27 on sv1-4. The four
submissions (ApiClientRequestLogger, by tid):
```
18:15:46.671 sv2 sent SubmitAndWaitForTransaction (DsoRules_CloseVoteRequest) -> 18:16:37.572 succeeded(OK)
18:15:46.985 sv4 sent -> 18:16:37.770 ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
18:15:47.011 sv3 sent -> 18:16:37.175 ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
18:15:47.792 sv1 sent -> 18:16:37.583 ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
```
So the vote was accepted and executed, 28 s after the check gave up (18:16:09.2).
11189: submissions 15:26:03.36 to 15:26:04.16; sv4's succeeded at 15:26:32.96, the check gave up at 15:26:28.50.

## 4. Ordering stall (family L confirming check)

```
python3 ci-triage/scripts/ref_seq_contention.py log/11191/art/canton.clog.gz | sed -n 1,9p
```
```
stored batches 4234, failed inserts 2026 (0.48 per stored batch), retry chains 1419, unfinished chains 0
failures before success: 1: 904, 2: 474, 3: 28, 4: 4, 5: 3, 8: 3, 9: 1, 12: 1, 14: 1
chain duration (s): 0.0-0.5: 1369, 0.5-2.0: 29, 2.0-5.0: 14, 5.0-inf: 7
  longest: 21.7 s from 2026-11-04T18:15:48.911Z globalSequencerSv1
```
globalSequencerSv1 `insert block` retries from 18:15:48.911 (backoff 0.05, 0.058, 0.128, 0.286, 0.709, 1.341, 2.521,
3.206, 7.232 s); its next batch was created at 18:16:10.609, so requests enqueued at 18:15:48.973 waited 21.6 s;
globalSequencerSv2 requests enqueued at 18:15:53.084 waited 14.7 s (batch 18:16:07.740).

11189 (`ref_seq_contention.py log/11189/art/canton.clog.gz`): 4841 stored, 3138 failed (0.65 per stored batch),
longest chain 18.0 s from 15:26:11.083 on globalSequencerSv1. Max enqueue-to-batch wait between 15:26:00 and 15:26:35:
Sv1 21.1 s (15:26:08.0 -> 15:26:29.1), Sv2 26.2 s (15:26:04.2 -> 15:26:30.4), Sv3 11.1 s, Sv4 23.2 s.

## 5. Frequency

resource-intensive (0) on main push runs since 2026-10-31 (62 of 63 runs read, one HTTP 502): 2026-11-02 20 success,
11-03 12 success, 11-04 24 success and 2 failures (these two), 11-05 4 success. Earlier hits of this check: 8179
(2026-04, closed, no recurrence). The 10676 family (line 494, vetting) is a different check.

## Verdict

Duplicate of 11160 (family L: reference driver `insert block` contention stalls global ordering for 15 to 25 s).
Flake, 2 of 62 runs. No test bug beyond a budget that leaves only about 20 s for the close transaction; a test-only
mitigation would be a longer `timeUntilSuccess` for this check (e.g. 90 s), not proposed while 11160 is pending.
Not verified: why the close trigger started 5 s after readyAt (both runs).
