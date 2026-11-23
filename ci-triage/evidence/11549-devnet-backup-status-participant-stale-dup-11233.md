# 11549 - Devnet backup_status_cluster: sv-da-1 participant stale at the 18:11 check

DUPLICATE of 11233 (Devnet Cloud SQL backups of sv-da-1 slower than `wait_for_cloudsql_backup` allows; the backup job
gives up and restarts the SV from its first component, and the 18:00 status check runs before the participant, which
is backed up last, has a fresh backup). Same instance and time of day as 11370, 11397, 11417, 11426, 11522. Job:
Devnet Backup Status workflow (schedule), job backup_status_devnet / Backup status cluster, 2026-11-17 18:10 to
18:12 UTC. Confirmed with the family's signature (timeout, then ready for the same db on the next attempt) on the
sv-da-1 job of the 16:01 Devnet Backup run of the same day.

- Parent: 11233.
- Runtime canton: n/a (no test run; cluster backup check).
- Component: infra / splice cluster script (`cluster/scripts/node-backup.sh`).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. What the status check saw

The only stale instance was the sv-da-1 participant: its latest backup was older than the 8 hour threshold, created
at 06:00 UTC on 2026-11-17 by the 04:01 backup run. The other 13 instances had backups created between 16:08 and
17:57 on 2026-11-17, among them the other sv-da-1 components (BFT sequencer 17:10, cn-apps 17:28, mediator 17:34,
sequencer 17:53).

## 2. The backup run that should have refreshed it

The 16:01 Devnet Backup run was still running at 18:11. Its sv-da-1 job took 4 h 27 min (16:07 to 20:34) and ended
green; the sv-1 job took about 3 h 7 min, validator1 and splitwell about 6 min each.

In the sv-da-1 job three attempts timed out (cantonBft at 17:09, cn-apps at 18:44 and at 20:01). Each new attempt
restarted at cantonBft, which was then ready within 4 to 7 minutes. cn-apps was ready at 20:26 on the fourth attempt;
the participant backup only started at 20:26:29, 2 h 15 min after the status check, and was ready at 20:34.

The status check of section 1 already saw a cn-apps backup created at 17:28:31, inside the attempt that reported the
cn-apps timeout at 18:44: the backup existed, the wait did not see it as done.

## 3. Poll budget unchanged on splice main

```
git log -1 --format='%h %cI' origin/main
git show origin/main:cluster/scripts/node-backup.sh | sed -n 175,182p
```
```
facc9443d3 2026-11-17T20:03:39Z
    if [ -n "$id" ]; then
      _info "Backup of $description ready! Backup ID: $id "
      break
    else
      (( i++ ))&& (( i > 300 )) &&_error "Timed out waiting for backup of $description db"
      sleep 5
      _info "$(date -u --iso-8601=seconds) still waiting..."
    fi
```

## 4. Frequency

The 18:00 check failed three days in a row (2026-11-15, 11-16, 11-17) and passed on 11-14; all other checks of those
days passed. The earlier two failures are 11426 and 11522, both closed as duplicates.

## Verdict

Duplicate of 11233 (open, no assignee, not itself a duplicate). Not a flake: it recurs whenever the sv-da-1 backups of
the 16:01 run exceed the 25 min poll budget. Fix location: splice `cluster/scripts/node-backup.sh`
(`wait_for_cloudsql_backup` budget, or not restarting the whole SV after one timeout) and/or the schedule of the
workflows (18:00 check against the 16:00 backup with an 8 h threshold). No fix attempted here: duplicate.

Not verified: why the Devnet sv-da-1 Cloud SQL backups take this long, and why the wait misses the cn-apps backup
created at 17:28.
