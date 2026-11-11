# 11370 - Devnet backup_status_cluster: sv-da-1-participant-1 stale at the 18:09 check (run 43074831511)

DUPLICATE of 11233 (Devnet Cloud SQL backups of sv-da-1 slower than `wait_for_cloudsql_backup` allows; the 18:00
status check then finds the participant stale). Same instance as 11342.

- Run: https://github.com/DACH-NY/canton-network-internal/actions/runs/43074831511, workflow Devnet Backup Status,
  job backup_status_cluster, cni main 878cf5f. Issue filed 2026-11-11T18:09:36Z.
- Log not readable from the sandbox (canton-network-internal Actions); the user read it and posted the step output on
  the issue.

## 1. Flagged output (user, issue comment 2026-11-11T23:11:27Z)

```
gh api repos/DACH-NY/cn-test-failures/issues/11370/comments --jq '.[]|.body'
```
```
In Evaluate backup status:
Run source /tmp/snapshots.sh
Sourced environment files
Stale backups found: sv-da-1-participant-1-pg-765498f
Error: Process completed with exit code 1.
```

## 2. Same instance and time of day as 11342 (dup of 11233)

```
gh api repos/DACH-NY/cn-test-failures/issues/11342/comments --jq '.[]|.body'
```
```
Same failure as #11233, a consequence of #11344. At 18:09 the 16:01 Devnet Backup was still retrying and had not backed up the SV participants yet, so their latest backups were from the early morning:
18:09:23.041 The latest cloud sql backup of sv-da-1-participant-1-pg-765498f is older than 8 hours ago with creationTimestamp: 2026-11-10T04:26:24.793Z.
...
```
11233 (open, no assignee) has the mechanism: the Devnet sv-da-1 Cloud SQL backups exceed the poll budget of
`wait_for_cloudsql_backup` (300 polls of 5 s), the backup job retries, and the 18:00 status check runs before the
participant is backed up.

## 3. Poll budget unchanged on splice main

```
git show FETCH_HEAD:cluster/scripts/node-backup.sh | sed -n 156,163p   # FETCH_HEAD = splice main 2026-11-11
```
```
    if [ "$status" == "SUCCESSFUL" ]; then
      _info "Backup of $description ready! Backup ID: $id "
      break
    else
      (( i++ ))&& (( i > 300 )) &&_error "Timed out waiting for backup of $description db"
      sleep 5
      _info "$(date -u --iso-8601=seconds) still waiting..."
    fi
```

## 4. Difference to 11-10

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "backup_cluster" created:>=2026-11-10' --jq '.items[]|"\(.number) \(.state) \(.state_reason) \(.created_at) \(.title[:70])"'
```
```
11378 closed duplicate 2026-11-11T20:49:55Z GHA Run 43074272995 : job backup_cluster Failed :fire:
11344 closed duplicate 2026-11-10T20:48:40Z GHA Run 42850476882 : job backup_cluster Failed :fire:
11377 closed completed 2026-11-11T20:48:43Z job backup_cluster fails at Display GCloud logs link with a 502
```
11377 and 11378 are Mainnet Backup (workflow line of their bodies). No Devnet backup_cluster issue on 2026-11-11, so
the 16:00 Devnet Backup of 11-11 did not fail (it was late, not given up). Not verified: its timing (cni log).

## Verdict

Duplicate of 11233; Devnet hits 2026-11-05 (11230, 11233), 11-10 (11342), 11-11 (11370). 11233 has no assignee.
