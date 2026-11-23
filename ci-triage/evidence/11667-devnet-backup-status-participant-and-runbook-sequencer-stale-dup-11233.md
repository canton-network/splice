# 11667 - Devnet backup_status_cluster: sv-da-1 participant and the SV runbook sequencer stale at the 18:11 check

DUPLICATE of 11233 (Devnet Cloud SQL backups slower than `wait_for_cloudsql_backup` allows; the backup job gives up
and restarts the node from its first component, and the 18:00 status check runs before the components that are
backed up late have a fresh backup). The sv-da-1 participant is the same instance and time of day as 11370, 11397,
11417, 11426, 11522, 11549. New in this run: a second stale instance, the sequencer of the SV runbook node (namespace
sv). A deployment change merged on 2026-11-19 at 13:54 added the runbook node to the Devnet Backup and removed its
instances from the exclude list of the status check; the 16:01 backup was the first to include it and hit the same
timeout on its job. Job: Devnet Backup Status workflow (schedule), job backup_status_devnet / Backup status cluster,
2026-11-19 18:09 to 18:11 UTC. Confirmed with the family's signature (timeout, then ready for the same db on the
next attempt) on both jobs of the 16:01 Devnet Backup run.

- Parent: 11233.
- Runtime canton: n/a (no test run; cluster backup check).
- Component: infra / splice cluster script (`cluster/scripts/node-backup.sh`).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. What the status check saw

Two stale instances, both older than the 8 hour threshold: the sv-da-1 participant (latest backup created at 05:22
UTC by the 04:01 backup run) and the runbook sequencer (latest backup created at 02:00:00 UTC).

The other runbook instances passed. The runbook participant's latest backup was from 15:00:00 UTC; like the
sequencer's 02:00:00 backup it predates the first runbook backup job (started 16:20, section 2), and it passed only
because it was younger than 8 h. The runbook BFT sequencer (17:09), apps (17:47) and mediator (18:09) had backups
from the running job.

## 2. The backup run that should have refreshed them

The 16:01 Devnet Backup run, the first to include the runbook node, was still running at 18:11. Its sv-da-1 job took
3 h 1 min (16:05 to 19:06), its sv (runbook) job 4 h 38 min (16:20 to 20:58); both ended green. The sv-1 job took
about 2 h 31 min, validator1 and splitwell about 6 min each.

sv-da-1: cantonBft timed out at 16:56:11 and was ready on the next attempt at 16:59:59; cn-apps timed out at 18:38:00
and was ready at 18:57:50. Each attempt restarted at cantonBft. The participant backup only started at 18:57:52,
47 min after the status check.

sv (runbook): the same shape. cantonBft timed out at 17:09:27 and was ready at 17:12:01; cn-apps timed out at
19:04:44 and 20:33:19 and was ready at 20:53:34. The sequencer backup of the second attempt started at 18:14:20, 3 min
after the status check read the instance, which is why the check still saw the 02:00 backup.

As in 11549, the status check of section 1 already saw backups created inside attempts that later reported a timeout
for that db (runbook apps at 17:47:33 and sv-da-1 cn-apps at 17:12:41, both in attempts that ended with a cn-apps
timeout): the backup existed, the wait did not see it as done.

## 3. Poll budget unchanged on splice main

```
git log -1 --format='%h %cI' origin/main
git show origin/main:cluster/scripts/node-backup.sh | sed -n 175,182p
```
```
1e80ae1dea 2026-11-20T11:03:20+01:00
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

The 18:00 check passed on 2026-11-18 and failed again on 11-19; the earlier checks of 11-19 and the checks at 21:07
and later passed. Earlier hits of the 18:00 check, all closed as duplicates: 11417 (11-13), 11426 (11-15), 11522
(11-16), 11549 (11-17). 11677 (2026-11-20 09:06) is a different run and was not looked at here.

## Verdict

Duplicate of 11233 (open, no assignee, not itself a duplicate). Not a flake: it recurs whenever the backups of a node
in the 16:01 run exceed the 25 min poll budget, and since the runbook node was added to the backup it is a second
node that can do so (its job was the slowest of the run). Fix location: splice `cluster/scripts/node-backup.sh`
(`wait_for_cloudsql_backup` budget, or not restarting the whole node after one timeout) and/or the schedule of the
workflows (18:00 check against the 16:00 backup with an 8 h threshold). No fix attempted here: duplicate.

Not verified: why the Devnet Cloud SQL backups take this long, why the wait misses backups that already exist, and
what created the runbook backups at 02:00:00 and 15:00:00 UTC (round timestamps before the first runbook backup job;
presumably Cloud SQL automated backups).
