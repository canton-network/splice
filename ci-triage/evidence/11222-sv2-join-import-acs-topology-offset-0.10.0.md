# 11222 - simtime (1) on release-line-0.10.0: sv2 join ImportPartyAcs refused, last event is a topology offset (family U)

Fifth hit of family U (parent 10907), the first on a release line. sv2's ACS import during the environment start of
SvExpiredRewardsCollectionTimeBasedIntegrationTest is refused by the Canton 3.7 repair check; the SV app exits the
JVM, so the shard has no `Tests:` line.

- Run 42020381491, release-line-0.10.0 8458061baa, job 127221921196 `simtime (1)`, attempt 1.
- Canton 3.7.0-snapshot.20261022.20797.0.vd0a4e6f2 (canton-simtime.clog).
- Artifact logs-simtime-1 (13412939188) in log/11222/art/.

## 1. Job log

```
sed -E 's/\x1b\[[0-9;]*m//g' log/run-42020381491/127221921196.log | grep -anE 'IMPORT_ACS_ERROR|JoiningNodeInitializer joining Dso|Initialize app failed|Tests: '
```
```
2026-11-05T12:13:50.1463523Z java.lang.RuntimeException: sv2 app initialization: Initialize app failed
2026-11-05T12:13:50.1474703Z Caused by: java.lang.RuntimeException: sv2 app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-05T12:13:50.1476870Z Caused by: io.grpc.StatusRuntimeException: INVALID_ARGUMENT: IMPORT_ACS_ERROR(8,c87dfb2a): Cannot apply a repair command as the last event is a topology offset. Please reconnect to the synchronizer
```
No `Tests:` line: the SV app exit takes the test JVM down (evidence loss for the shard).

## 2. canton_network_test.clog

```
zcat canton_network_test.clog.gz | grep -a 'Starting test suite' | jq -r ...
zcat canton_network_test.clog.gz | grep -a 'IMPORT_ACS_ERROR' | head -2 | jq -r ...
```
```
12:09:49.407 Starting test suite 'ScanWithGradualStartsTimeBasedIntegrationTest'...
12:12:59.517 Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...
12:13:49.432 INFO ApiClientRequestLogger:SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=ed9332d5/SV=sv2 Request (tid:c87dfb2aad527846bc1602bf76604582) com.digitalasset.canton.admin.participant.v30.PartyManagementService/ImportPartyAcs to 127.0.0.1:15202: failed with INVALID_ARGUMENT/IMPORT_ACS_ERROR(8,c87dfb2a): Cannot apply a repair command as the las...
12:13:49.435 WARN ParticipantAdminConnection:SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=ed9332d5/SV=sv2 The operation 'Imports the acs in the participant' failed with a non-retryable error, not retrying:...
12:13:49.436 INFO sv2 app initialization: JoiningNodeInitializer joining Dso with key failed
```
The same signature as family U (`known-families.md`, U): import refused as non-retryable, SV app init fails.

## 3. Duplicate check

- `known-families.md` U: 10907 (main, 10-29), 11163 (main, 11-03), 11107 and 11177 (dups). Search
  `repo:DACH-NY/cn-test-failures "last event is a topology offset"`: 10907 (open, no assignee), 11163 and 11177
  (closed as duplicate).
- 10907 has an owner discussion (moritzkiefer-da, 10-29: ImportPartyAcs does not pass the force flag), no fix on main.
- Release-line-0.10.0 runs Canton 3.7 too, so it is exposed like main; not a missing backport.

## Verdict

Duplicate of 10907 (family U). Hits: 10907 10-29, 11107 11-02, 11163 11-03, 11177 11-04, 11222 11-05: five in eight
days across main, the Daml nightly and release-line-0.10.0, each losing a whole simtime shard. Rank up: no longer rare.
Fix direction unchanged (retry the import after reconnect once ledger end moves past the topology offset, in
JoiningNodeDsoPartyHosting, or a Canton-side force flag on ImportPartyAcs).
