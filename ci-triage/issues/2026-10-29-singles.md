# 2026-10-29 inbox: the single items

## 10740 - TokenStandardV2TransferIntegrationTest: forced ACS snapshot 500 on the snapshot index lock (new)

Run 40716480501 (post-merge, main 77c77f03d4 = #7923), job `wall-clock-time (0)`; 29/30 tests pass.

```
gh api repos/canton-network/splice/actions/jobs/122930983566/logs | grep -a 'FAILED'
gh api repos/canton-network/splice/actions/artifacts/12833920984/zip > a.zip && unzip -q a.zip
zcat canton_network_test.clog.gz | grep -a '"level":"ERROR"'
```
```
16:31:36.373 ERROR ...scan=sv1Scan Request to http://127.0.0.1:5012/api/scan/v0/state/acs/force resulted in an unexpected exception: Failed to acquire session-scoped advisory lock 1936747523.
16:31:36.375 ERROR ... HttpCommandException: HTTP 500 Internal Server Error POST at '/api/scan/v0/state/acs/force'
16:31:36.378 ERROR ... Test failed: 'TokenStandardV2TransferIntegrationTest/... support create, list, accept, reject and withdraw'
```

Lock 1936747523 = 0x73706c00 + 3 = `AdvisoryLockIds.acsSnapshotIndex`. `AcsSnapshotStore.indexSnapshotStakeholdersTable`
takes it with `pg_try_advisory_lock` (no wait). The automation (`AcsSnapshotTriggerBase`, `AcsSnapshotIndexTrigger`)
treats `FailedToAcquireLockException` as "skip, retry later", but `HttpScanHandler.forceAcsSnapshotNow` (per-table
snapshots, `NextV2`) lets it escape as HTTP 500 when the index trigger holds the lock at that moment. Race between
the forced snapshot used by tests and the background index trigger (per-table ACS snapshots, #6515 / #7788, Oriol).
Fix: retry `FailedToAcquireLockException` in the force path (or have the client retry 500s from `forceAcsSnapshotNow`
with a clue). First hit found; owner: OriolMunoz-da.

## 10742 - RollForwardLsuDRIntegrationTest: aliceValidator close times out in afterAll (new, teardown)

Run 40736353951 (post-merge, main e13f440491 = #8082), job `roll-forward-lsu (0)`. The test passes
(`Tests: succeeded 1`), the suite aborts in `afterAll`:

```
ShutdownFailedException: Unable to close 'org.lfdecentralizedtrust.splice.environment.SpliceEnvironment@68b26ddc'
```
canton_network_test.clog (artifact logs-roll-forward-lsu-0, 12840724293):
```
18:32:35.800 SpliceEnvironment Closing environment...
18:32:40.801 ValidatorApps Task closing node-aliceValidator still not completed after 5 seconds. Continue waiting...
18:32:45.805 WARN RetryProvider Timeout 10 seconds expired, but readers are still active. Shutting down forcibly.
18:32:45.806 ApiClientRequestLogger ... CommandService/SubmitAndWait to 127.0.0.1:5501: failed with UNAVAILABLE/Channel shutdownNow invoked
18:32:45.807 ReceiveFaucetCouponTrigger Giving up on retrying ... due to shutdown
18:32:45.816 WARN ValidatorApps Task closing node-aliceValidator did not complete within 10 seconds.
```

aliceValidator's ReceiveFaucetCouponTrigger had a SubmitAndWait in flight to its participant (127.0.0.1:5501) when the
environment closed; the RetryProvider waited for it for its full 10 s, so the close exceeded the 10 s budget. Not
related to #8082 (repair service): the DR part of the test passed. First hit; low priority teardown flake. Fix: let the
validator's shutdown cancel in-flight ledger submissions before waiting, or give the test env a longer close timeout.

## 10745 - Devnet Backup `backup_cluster` (cni GHA run 40721834944) - needs log

canton-network-internal Actions logs are refused by the router. The run is at cni 4c3b77c062 (#7746), 10-28 21:20,
after #7727 (backup step timeout fix, merged 09:47). Different job from #10643 (`backup_status_cluster`). Needs:
`gh run view 40721834944 -R DACH-NY/canton-network-internal --log-failed`.

## CircleCI items - need logs

| Issue | Workflow | Job | Created | Note |
|---|---|---|---|---|
| 10741 | deploy_sv_reonboard 87496 | 526074 | 10-28 17:58 | cni #7748 "Fix ciperiodic reonboard for the disabled repair service world" merged 17:30, run may predate it |
| 10843 | monitor_sv_catchup_test 87522 | 526215 | 10-28 23:13 | during the main test-compile break (18:56 - ~08:28, #8109) |
| 10844 | deploy_lsu_operator 87538 | 526299 | 10-29 00:05 | first run after cni #7746; also during the compile break |
| 10846 | deploy_ciperiodic_non_devnet 87548 | 526332 | 10-29 04:45 | after cni #7743 (10701 key-download fix); during the compile break |
| 10892 | deploy_sv_reonboard 87574 | 526417 | 10-29 09:44 | after #7748 and after #8109 |

Lead: CircleCI ciperiodic workflows build splice main for their preflight jobs, so 10843/10844/10846 may just be the
`truncateInstant` test-compile break (see main-break-8109.md). Not confirmed without logs. For 10844 the pipeline job
list also tells whether `upgrade_logical_synchronizer` itself passed (the real check of cni #7746).
