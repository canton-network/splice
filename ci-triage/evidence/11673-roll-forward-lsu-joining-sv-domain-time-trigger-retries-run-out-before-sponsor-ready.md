# 11673 - RollForwardLsuIntegrationTest passes, checkErrors: sv4 DomainTimeIngestionTrigger ERROR `Not connected to any synchronizer` during environment setup (run 44673774254)

New (family BQ), not a duplicate. release-line-0.10.1 5ddb73d3d9, job `roll-forward-lsu (1)`, Canton 3.7.2. The one
suite of the shard passed (`Tests: succeeded 1, failed 0`); checkErrors flagged one ERROR line in
`canton_network_test.clog`. The three joining SV apps (sv2, sv3, sv4) register the global synchronizer on their
participants at 04:48:20.55 and connect only after the sponsor sv1 answers `/v0/dso`. From registration on, each
joiner's `DomainTimeIngestionTrigger` calls `FetchTime`, gets `INVALID_ARGUMENT: Not connected to any synchronizer`,
and retries with a budget of 35 retries (109.8 s here). sv1 needed 108.8 s to bootstrap the DSO on a slow runner
(91 s of it for 87 DAR uploads, mean 3.04 s; 32 s for the same 87 on 2026-11-12), so sv4 gave up 1.16 s before the
sponsor answered; sv2 and sv3 were at 31 and 33 failures. Contributing on this release line: 22 of the 87 uploads
are repeats, the dedup #8467 (87d584aa16) is not on release-line-0.10.1.

- Run: https://github.com/canton-network/splice/actions/runs/44673774254, release-line-0.10.1 5ddb73d3d9
  ("Backport PR #8521 to release-line-0.10.1 (#8595)"), job 136081791130 `ci / scala_test_roll_forward_lsu / roll-forward-lsu (1)`.
- Runtime canton: 3.7.2 (`git show 5ddb73d3d9:nix/canton-sources.json | grep -m1 version`).
- Component: splice app (trigger retry budget during SV onboarding) exposed by a slow runner; no test assertion involved.
- The run has two more failed jobs with their own refs (11675 `wall-clock-time (8)`, 11674 `resource-intensive (0)`); not examined here.

Paths below are relative to `log/11673/`; `T=logs-roll-forward-lsu-1/canton_network_test.clog.gz`.

## 1. Flagged line

```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-520
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 6 minutes, 34 seconds.
[info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-20T04:50:10.452Z","message":"Skipping processing of \nPeriodicTask(for = 2026-11-20T04:48:20.614846Z)\ndue to unexpected failure","logger_name":"o.l.s.a.DomainTimeIngestionTrigger:RollForwardLsuIntegrationTest/config=2a8e922a/SV=sv4","thread_name":"RollForwardLsuIntegrationTest-2a8e922a-env-ec-985","level":"ERROR","stack_trace":"io.grpc.StatusRuntimeException: INVALID_ARGUMENT: Not connected to any synchronizer with id global-domain::1220f448d2ef...\n\tat io.grpc.Status.asRuntimeException(Status.java:532)
Total: 1 lines with problems.
```

## 2. Which test was running

The ERROR falls into the environment setup of the only test case (start 04:47:16.911, first clue 04:51:56.259).

```
zcat $T | grep -a -E "Starting test suite|Starting '|Running clue" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-110 | head -3
```
```
2026-11-20T04:47:03.825Z Starting test suite 'RollForwardLsuIntegrationTest'...",
2026-11-20T04:47:16.911Z Starting 'RollForwardLsuIntegrationTest/roll forward LSU'...",
2026-11-20T04:51:56.259Z Running clue: (act) Create some transaction history",
```

## 3. sv4: synchronizer registered at 04:48:20.555, connected only after the sponsor answered at 04:50:11.607

`sv4.jsonl` is `zcat $T | grep -a 'SV=sv4'`. The formatter `F` is the generic one of recipe 4 plus
`s/RollForwardLsuIntegrationTest\/config=2a8e922a\///`.

```
grep -a -E 'SynchronizerConnectivityService/(Register|Connect|Reconnect)[A-Za-z]* to [0-9.:]+: (succeeded|sending)|"level":"ERROR"|Got .DSO party ID|Total maximum' sv4.jsonl | F 215 | awk '$1<"2026-11-20T04:50:15"'
```
```
2026-11-20T04:48:17.604Z Request (tid:6d121c5cb244fea0c6bb9648156bd837) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/RegisterSynchronizer to 127.0.0.1:5402: sending request RegisterS
2026-11-20T04:48:20.555Z Request (tid:6d121c5cb244fea0c6bb9648156bd837) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/RegisterSynchronizer to 127.0.0.1:5402: succeeded(OK)",[o.l.s.a.a
2026-11-20T04:50:10.450Z The operation 'processTaskWithRetry' has failed with an exception. Total maximum number of retries 35 exceeded. Giving up. ",[o.l.s.a.DomainTimeIngestionTrigger:SV=sv4] INFO
2026-11-20T04:50:10.452Z Skipping processing of \nPeriodicTask(for = 2026-11-20T04:48:20.614846Z)\ndue to unexpected failure",[o.l.s.a.DomainTimeIngestionTrigger:SV=sv4] ERROR
2026-11-20T04:50:11.607Z Got 'DSO party ID from sponsoring SV': DSO-2a8e922a-2a8e922a::1220f448d2ef...",[o.l.s.s.o.j.JoiningNodeInitializer:SV=sv4] INFO
2026-11-20T04:50:11.665Z Request (tid:8677d2b2e103e1ead96d8fcdb40bb98d) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizers to 127.0.0.1:5402: sending request Reconne
2026-11-20T04:50:14.572Z Request (tid:8677d2b2e103e1ead96d8fcdb40bb98d) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizers to 127.0.0.1:5402: succeeded(OK)",[o.l.s.a
```

Until the sponsor is up the joiner polls `http://127.0.0.1:5012/api/scan/v0/dso` (sv1Scan) and gets 404 (log lines,
several per attempt):

```
grep -a -c 'requested resource could not be found: http://127.0.0.1:5012/api/scan/v0/dso' sv4.jsonl
```
```
68
```

## 4. The trigger: NOT_FOUND before registration is skipped, "not connected" after it is retried 35 times

The first polls see no registered synchronizer (`GetSynchronizerId` NOT_FOUND, which the trigger turns into a
no-op); the first poll after registration resolves the id and fails in `FetchTime`.

First occurrence of each outcome of the two calls the trigger makes, then the number of refused `FetchTime` calls
up to the ERROR (the first attempt plus 35 retries):

```
grep -a -E '(GetSynchronizerId|FetchTime) to 127.0.0.1:5402: (failed|succeeded)' sv4.jsonl | sed -E 's/\{"@timestamp":"([^"]+)","message":"Request \(tid:[0-9a-f]*\) com\.digitalasset\.canton\.[a-z.0-9]*\./\1 /; s/(\\n|",).*//' | cut -c1-175 | awk '{k=$2" "$5} !seen[k]++'
grep -a 'FetchTime to 127.0.0.1:5402: failed with INVALID_ARGUMENT/Not connected' sv4.jsonl | awk -F'"' '$4<="2026-11-20T04:50:10.452Z"' | wc -l
```
```
2026-11-20T04:48:17.443Z SynchronizerConnectivityService/GetSynchronizerId to 127.0.0.1:5402: failed with NOT_FOUND/SYNC_SERVICE_UNKNOWN_SYNCHRONIZER(11,d197a126): The synchro
2026-11-20T04:48:20.669Z SynchronizerConnectivityService/GetSynchronizerId to 127.0.0.1:5402: succeeded(OK)
2026-11-20T04:48:20.677Z SynchronizerTimeService/FetchTime to 127.0.0.1:5402: failed with INVALID_ARGUMENT/Not connected to any synchronizer with id global-domain::1220f448d2e
2026-11-20T04:50:16.267Z SynchronizerTimeService/FetchTime to 127.0.0.1:5402: succeeded(OK)
36
```
First refusal 04:48:20.677, give-up 04:50:10.450: 109.8 s.

Source at the run's sha (`git show 5ddb73d3d9:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/DomainTimeIngestionTrigger.scala`,
unchanged on origin/main, last touched afcce7cff9 2025-04-22):

```
    participantAdminConnection.getSynchronizerId(synchronizerAlias).transformWith {
      case Failure(s: StatusRuntimeException) if s.getStatus.getCode == Status.Code.NOT_FOUND =>
        // This can happen during initialization, just skip it to reduce log noise.
        Future.successful(TaskNoop)
      case Failure(e) => Future.failed(e)
      case Success(synchronizerId) =>
        for {
          time <- participantAdminConnection
            .getDomainTimeLowerBound(synchronizerId, context.config.pollingInterval)
```

## 5. All three joiners were in the same race; sv4 ran out 1.16 s before the sponsor answered

Output order: sv2 (2 lines), sv3 (2 lines), sv4 (4 lines); the cut at 200 columns truncates the logger name.

```
for s in sv2 sv3 sv4; do zcat $T | grep -a "SV=$s\"" | grep -a 'DomainTimeIngestionTrigger' | grep -a -E 'after a number of|Skipping|exceeded|Completed processing' | F 250 | awk '/Skipping|exceeded/ {print; next} /after a number of/ {last=$0} /Completed/ && !c++ {print last; print}'; done | cut -c1-200
```
```
2026-11-20T04:50:09.338Z The operation 'processTaskWithRetry' has failed with an exception. Retrying after a number of 31 failures, and after 2565 milliseconds. ",[o.l.s.a.DomainTimeIngestionTrigger:S
2026-11-20T04:50:11.911Z Completed processing PeriodicTask(for = 2026-11-20T04:48:21.457077Z) with outcome: Updated domain time to 2026-11-20T04:50:11.778476Z",[o.l.s.a.DomainTimeIngestionTrigger:SV=s
2026-11-20T04:50:12.075Z The operation 'processTaskWithRetry' has failed with an exception. Retrying after a number of 33 failures, and after 3557 milliseconds. ",[o.l.s.a.DomainTimeIngestionTrigger:S
2026-11-20T04:50:16.305Z Completed processing PeriodicTask(for = 2026-11-20T04:48:21.440907Z) with outcome: Updated domain time to 2026-11-20T04:50:14.700307Z",[o.l.s.a.DomainTimeIngestionTrigger:SV=s
2026-11-20T04:50:10.450Z The operation 'processTaskWithRetry' has failed with an exception. Total maximum number of retries 35 exceeded. Giving up. ",[o.l.s.a.DomainTimeIngestionTrigger:SV=sv4] INFO
2026-11-20T04:50:10.452Z Skipping processing of \nPeriodicTask(for = 2026-11-20T04:48:20.614846Z)\ndue to unexpected failure",[o.l.s.a.DomainTimeIngestionTrigger:SV=sv4] ERROR
2026-11-20T04:50:13.495Z The operation 'processTaskWithRetry' has failed with an exception. Retrying after a number of 4 failures, and after 2247 milliseconds. ",[o.l.s.a.DomainTimeIngestionTrigger:SV
2026-11-20T04:50:16.268Z Completed processing PeriodicTask(for = 2026-11-20T04:50:11.412925Z) with outcome: Updated domain time to 2026-11-20T04:50:14.700307Z",[o.l.s.a.DomainTimeIngestionTrigger:SV=s
```

## 6. Why the sponsor was late: sv1 DSO bootstrap 108.8 s, 91 s of it uploading 87 DARs

`sv1.jsonl` is `zcat $T | grep -a 'SV=sv1"'`.

```
grep -a -E 'Bootstrapping the domain as sv1|Ensuring that SV1 has uploaded|Success: SV1 has uploaded|Success: the DsoRules and AmuletRules|SV1Initializer bootstrapping Dso finished' sv1.jsonl | F 170
grep -a -oE 'PackageService/UploadDar to 127.0.0.1:5102: succeeded' sv1.jsonl | sort | uniq -c
```
```
2026-11-20T04:48:17.612Z Bootstrapping the domain as sv1",[o.l.s.s.o.s.SV1Initializer:SV=sv1] INFO
2026-11-20T04:48:24.804Z Ensuring that SV1 has uploaded the initial set of packages",[o.l.s.s.o.s.SV1Initializer:SV=sv1] INFO
2026-11-20T04:49:56.154Z Success: SV1 has uploaded the initial set of packages, result is ()",[o.l.s.s.o.s.SV1Initializer:SV=sv1] INFO
2026-11-20T04:50:05.851Z Success: the DsoRules and AmuletRules are bootstrapped, result is ()",[o.l.s.s.o.s.SV1Initializer:SV=sv1] INFO
2026-11-20T04:50:06.307Z sv1 app initialization: SV1Initializer bootstrapping Dso finished after PT1M48.825470736S",[o.l.s.s.SvApp:SV=sv1] INFO
     87 PackageService/UploadDar to 127.0.0.1:5102: succeeded
```

Per-upload durations (send to `succeeded` by trace id, Python over the `UploadDar` lines of `sv1.jsonl`):

```
uploads 87 first send 04:48:25 last done 04:49:54.263000
sum of durations 264.1 s, mean 3.04 s, max 18.91 s
```

The repeated DARs of family AM are present (each of these DAR names is processed twice on sv1Participant):

```
zcat logs-roll-forward-lsu-1/canton_before_shutdown.clog.gz | grep -a 'PackageUploader:participant=sv1Participant' | grep -a -E '"@timestamp":"2026-11-20T04:4[89]' | grep -a -oE '"message":"[^"]{0,140}' | sed -E 's/[0-9a-f]{8}-[0-9a-f-]{27}/<id>/g; s/ [0-9]+ packages/ N packages/' | sort | uniq -c | sort -rn | head -4
```
```
     87 "message":"Managed to upload one or more archives for submissionId <id>
      2 "message":"Processing package upload of N packages from splice-wallet-payments-0.1.25 for submissionId <id>
      2 "message":"Processing package upload of N packages from splice-wallet-0.1.26 for submissionId <id>
      2 "message":"Processing package upload of N packages from splice-validator-lifecycle-0.1.10 for submissionId <id>
```

```
git merge-base --is-ancestor 87d584aa16 origin/release-line-0.10.1 && echo present || echo MISSING     # #8467 Deduplicate DAR uploads
```
```
MISSING
```
(also MISSING on release-line-0.10.0 and 0.10.2, present on release-line-0.10.3 and main.)

## 7. Comparison: same shard and suite on 2026-11-12 (run 43207139518, ref 11384, main 0a0229c744), 47 s instead of 111 s

Passing runs upload no log artifact, so the comparison is the setup phase of the 11384 run (it failed later, in a
vote). Same 87 uploads, 32 s; joiners at 2 to 6 trigger failures.

```
zcat cmp-43207139518/canton_network_test.clog.gz | grep -a -E 'SV=sv[1-4]"' | grep -a -E 'Ensuring that SV1 has uploaded|Success: SV1 has uploaded|SV1Initializer bootstrapping Dso finished|RegisterSynchronizer to 127.0.0.1:5402: succeeded|Got .DSO party ID from sponsoring' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"[^"]*(SV=sv[0-9]).*/ \1/; s/Request \(tid:[0-9a-f]*\) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService.//' | cut -c1-150 | grep -v 'sv[23]$'
```
```
2026-11-12T11:23:49.520Z RegisterSynchronizer to 127.0.0.1:5402: succeeded(OK) SV=sv4
2026-11-12T11:23:53.783Z Ensuring that SV1 has uploaded the initial set of packages SV=sv1
2026-11-12T11:24:26.012Z Success: SV1 has uploaded the initial set of packages, result is () SV=sv1
2026-11-12T11:24:32.918Z sv1 app initialization: SV1Initializer bootstrapping Dso finished after PT46.324744357S SV=sv1
2026-11-12T11:24:35.878Z Got 'DSO party ID from sponsoring SV': DSO-b8b476c4-b8b476c4::122076dfb0b4... SV=sv4
```

CPU-only reference (package signature loading, about 30 s on a fast runner per family AM): 49.9 s here, 37.0 s on
2026-11-12.

```
for d in logs-roll-forward-lsu-1 cmp-43207139518; do zcat $d/canton_network_test.clog.gz | grep -a -E 'oading package signatures' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"[^"]*\/([^"]*)".*/ [\1]/' | awk 'NR==1 || /Finished/' | head -2; done
```
```
2026-11-20T04:47:27.173Z Loading package signatures from resources [SV=sv1]
2026-11-20T04:48:17.308Z Finished loading package signatures from resources [SV=sv4]
2026-11-12T11:23:09.560Z Loading package signatures from resources [SV=sv3]
2026-11-12T11:23:46.520Z Finished loading package signatures from resources [SV=sv3]
```

## 8. Duplicate check

```
grep -n -i -E 'DomainTimeIngestionTrigger|Not connected to any synchronizer' ci-triage/known-families.md
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "DomainTimeIngestionTrigger"' --jq '.items[] | "\(.number) \(.state) \(.created_at) \(.title)"'
```
```
(no family line)
10235 closed 2026-09-29T09:24:02Z Test fails due to sv-1 reconnect to global synchronizer going silent and never finishing
10170 closed 2026-09-18T09:36:22Z TrafficBasedRewardsTimeBasedIntegrationTest "CIP-104 reward accounting pipeline works": round 11 not opened within 20 s after a 10-minute simulated time jump (runs 35326825932, 32257514623)
9491 closed 2026-08-06T10:44:02Z UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest fails at "Check waiting for open and issuing round automation"
8190 closed 2026-04-27T12:53:15Z Error in logs: DomainTimeIngestionTrigger INVALID_ARGUMENT: Not connected to any synchronizer with id global-domain
8145 closed 2026-04-22T12:39:39Z GHA Run 24777567697 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
7968 closed 2026-04-08T18:22:00Z Test failed: 'WalletMetricsTest/Unlocked coin metrics should update when tapping coin'
6121 closed 2025-10-22T21:26:31Z ParticipantKmsIdentitiesEnterpriseIntegrationTest fails to start because canton crashes
```

8190 has the same flagged line but another mechanism: aliceValidator, already connected, after
`reconnect to the domain ... for new sequencer configuration to take effect` (a reconnect that never completed,
Canton, closed as completed in April). Here the participant was never connected yet and every reconnect completes.
Not family AM either: AM is a test wait or `waitForInitialization` timing out; here all nodes initialize and the
test passes. AM's slow-runner measurements (signature loading, 87 DARs with 22 repeats) do apply as the trigger.

## Verdict

New, family BQ; flake (slow runner against a fixed budget), first hit of this shape in the tracker. The window
"registered but not connected" on a joining SV lasts until the sponsor has bootstrapped the DSO, which only tests
(all SVs started at once on a cold Canton) stretch to minutes; the trigger tolerates 109.8 s of it.

Fix options, none written (owner decision):
1. Splice app, small: in `DomainTimeIngestionTrigger.completeTask` treat the `INVALID_ARGUMENT` "Not connected to
   any synchronizer" answer of `getDomainTimeLowerBound` like the NOT_FOUND case next to it (`TaskNoop`). About 5
   lines, but a production change: a participant that loses its synchronizer connection in production would no
   longer log this ERROR every 110 s. The suite cannot verify it (needs a sponsor slower than 110 s).
2. Release lines only: backport #8467 (87d584aa16, DAR upload dedup, 87 to 65 uploads per participant) to
   release-line-0.10.1 / 0.10.2; shortens the window, does not remove the race. 222 lines, production code.
3. Do nothing on release-line-0.10.1 (superseded by 0.10.3, which has #8467) and wait for a second hit.

Not written here because the candidate fix is production code whose log-level semantics the owner should decide,
and the failing branch is an old release line. Not verified: why the DAR uploads were 2.8x slower than on
2026-11-12 while the CPU-only phase was 1.35x slower (database or disk on the runner; nothing in the artifacts);
the other two failed jobs of the run (11674, 11675) were not read, so a run-wide host slowdown is not established.
No sbt, no Canton run.
