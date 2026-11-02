# 10891 - PeriodicTopologySnapshotIntegrationTest: no snapshot, GCS credentials miss 'scopes' (run 40713988598)

DUPLICATE of 10921 (family V). PR branch is/deflake-bulk-storage-consensus-test 9ff2637f41 (PR 8083), job
wall-clock-time (9), attempt 1, started 2026-10-29 00:58 UTC. Canton 3.7.0-snapshot.20261027.20812.0.v1af931f4.
Tests: succeeded 75, failed 1. The branch contains #8017 (pekko-connectors-google-cloud-storage 1.3.0) and not the
fix #8148, so sv1's PeriodicTopologySnapshotTrigger fails with `ConfigException$Missing ... 'scopes'` and never
logs the line the test waits for. Not family T: the job ran 9 h before the 2026-10-29 secret rotation and the
log has no `invalid_grant`.

- Run: https://github.com/canton-network/splice/actions/runs/40713988598/job/123197207725, is/deflake-bulk-storage-consensus-test
  9ff2637f41 ("[ci] Deflake bulk storage consensus disagreement test", PR 8083), job 123197207725
  `ci / scala_test_wall_clock_time / wall-clock-time (9)`, attempt 1 of 2.
- Runtime canton: 3.7.0-snapshot.20261027.20812.0.v1af931f4.
- Component: splice app config (GCS credentials), fixed on main.

## 1. Failing assertion

The test finds neither the snapshot line nor the "already exists" line in sv1's logs.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10891/job.log | grep -a -A3 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | cut -c1-200 | head -4
```
```
[info] - should produces a topology snapshot in the background *** FAILED ***
[info]   forAtLeast(1) failed, because no element satisfied the assertion block: 
[info]     at index 0, "Starting node sv1" did not include substring "Took a new topology snapshot on 2026-10-29", and "Starting node sv1" did not include substring "Today's topology snapshot already exists." (PeriodicTopologySnapshotIntegrationTest.scala:57), 
[info]     at index 1, "Creating storage, num-combined: 8" did not include substring "Took a new topology snapshot on 2026-10-29", and "Creating storage, num-combined: 8" did not include substring "Today's topology snapshot already exists." (PeriodicTopologySnapshotIntegrationTest.scala:57), 
```

## 2. Job attempt, time and artifact

The URL names job 123197207725 (attempt 1). It ran 00:58-01:32 UTC on 2026-10-29; the matching artifact is the
one created at 01:32 (the 10:08 one belongs to attempt 2).

```
gh api repos/canton-network/splice/actions/jobs/123197207725 --jq '"started=\(.started_at) completed=\(.completed_at) sha=\(.head_sha[0:10])"'
gh api "repos/canton-network/splice/actions/runs/40713988598/artifacts?per_page=100" --jq '.artifacts[] | select(.name=="logs-wall-clock-time-9") | "\(.id) \(.created_at) \(.size_in_bytes)"'
```
```
started=2026-10-29T00:58:10Z completed=2026-10-29T01:32:45Z sha=9ff2637f41
12888738196 2026-10-29T10:08:09Z 103385718
12866465693 2026-10-29T01:32:35Z 107827253
```
```
gh api repos/canton-network/splice/actions/artifacts/12866465693/zip > log/10891/logs-wall-clock-time-9.zip
```

## 3. Suite timeline

```
T=log/10891/logs-wall-clock-time-9/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): " | grep -a PeriodicTopologySnapshot | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-10-29T01:30:39.289Z Starting test suite 'PeriodicTopologySnapshotIntegrationTest'...",
2026-10-29T01:31:33.843Z Test failed: 'PeriodicTopologySnapshotIntegrationTest/sv1 should produces a topology snapshot in the background', message: forAtLeast(1) failed,
```

## 4. Family V signature: the snapshot trigger fails on the missing 'scopes' key

Three seconds into the suite sv1's trigger gives up streaming the onboarding state; the stack ends in
pekko-connectors' `Credentials.parseServiceAccount`, called from `SequencerAdminConnection.streamOnboardingState`.

```
zcat $T | grep -a -c "No configuration setting found for key 'scopes'"
zcat $T | grep -a -m1 "Stream onboarding state to GCP bucket failed" | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-260
zcat $T | grep -a -m1 "Stream onboarding state to GCP bucket failed" | grep -oE '"stack_trace":"[^"]{0,1200}' | sed 's/\\n\\t/\n  /g; s/\\n/\n/g' | grep -E "Missing|streamOnboardingState|parseServiceAccount|Credentials" | head -6
```
```
9
2026-10-29T01:30:42.278Z Stream onboarding state to GCP bucket failed with an unknown exception, not retrying",[o.l.s.s.a.PeriodicTopologySnapshotTrigger:PeriodicTopologySnapshotIntegrationTest/config=3f09e634/SV=sv1] WARN
"stack_trace":"com.typesafe.config.ConfigException$Missing: hardcoded value: No configuration setting found for key 'scopes'
  at org.apache.pekko.stream.connectors.google.auth.Credentials$.parseServiceAccount(Credentials.scala:83)
  at org.apache.pekko.stream.connectors.google.auth.Credentials$.apply(Credentials.scala:75)
  at org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection.streamOnboardingState(SequencerAdminConnection.scala:249)
```

## 5. Not family T

Family T (invalid GCS service account key, rotation 2026-10-29 ~09:57 UTC) would show `invalid_grant`; the job
finished at 01:32 UTC, before the rotation.

```
zcat $T | grep -a -c invalid_grant
```
```
0
```

## 6. The branch has the cause and lacks the fix

```
SHA=9ff2637f413b278727effd5a32b85bd96746dd83
git fetch https://github.com/canton-network/splice.git $SHA
for c in 24bfde02b2 2d373163c3; do git merge-base --is-ancestor $c $SHA && echo "$c present" || echo "$c MISSING"; done
git show $SHA:nix/canton-sources.json | grep -m1 version
```
```
24bfde02b2 present
2d373163c3 MISSING
  "version": "3.7.0-snapshot.20261027.20812.0.v1af931f4",
```
24bfde02b2 is #8017 (pekko-connectors-google-cloud-storage 1.0.2 -> 1.3.0); 2d373163c3 is #8148 ("Pass GCS
service-account scopes where pekko-connectors 1.3.0 reads them", main 2026-10-30).

## Verdict

Duplicate of 10921 (family V), not a flake: deterministic on every run whose sha has #8017 but not #8148 and
that has no snapshot for the day yet. Fixed on main by #8148; a PR branch based before it only needs a rebase.
Not verified: whether release lines that got the #8017 backport (#8073 on 0.9.x) also got #8148.
