# 11683 - Mainnet backup_status_cluster: sv-1 participant stale at the 12:05 check

DUPLICATE of 11233 (Cloud SQL backup slower than the backup script allows: `Timed out waiting for backup of <db> db`
in `wait_for_cloudsql_backup`, the job restarts the node from its first component, and the status check runs before
the component that is backed up last has a fresh backup). Second Mainnet hit of the family, three hours after 11677,
this time on the other node: the sv-1 job of the 08:05 Mainnet Backup run (started 10:05 because it queued behind the
06:06 run) timed out twice on cn-apps (11:18:45, 12:30:36) and reached the participant only at 12:43:25, so the 12:05
check found the sv-1 participant backup of the 06:06 run (07:38:14, 4 h 28 min old, threshold 4 h). Same mechanism as
11677: Mainnet runs `cluster/scripts/node-backup.sh` from splice `release-line-0.10.0`, which has neither splice#8310
nor splice#8520; the create ran over the client-side wait of the create command, the retry made a second backup with
the same RUN_ID description, and the wait loop compared two statuses to `SUCCESSFUL`. Resolution is the backport to
release-line-0.10.0. Job: Mainnet Backup Status workflow (schedule), job backup_status_mainnet / Backup status
cluster, 2026-11-20 12:05 to 12:06 UTC.

- Parent: 11233.
- Runtime canton: n/a (no test run; cluster backup check).
- Component: splice cluster script (`cluster/scripts/node-backup.sh` on release-line-0.10.0), missing backport.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. What the status check saw

One stale instance, the sv-1 participant, with a latest backup older than the 4 hour threshold (created 07:38:14
UTC); the nine other instances had a backup from 10:06 to 11:39.

## 2. The backup runs around the check

The 07:38:14 participant backup is from the sv-1 job of the 06:06 run (see the 11677 packet). The 08:05 run started
at 10:05, after the 3 h 52 min sv-da-1 job of the 06:06 run; its sv-1 job ran 2 h 41 min (10:05 to 12:46), its
sv-da-1 job about 22 min. The 10:03 run was cancelled, the 12:05 and 14:04 runs passed.

In the sv-1 job of the 08:05 run cn-apps timed out twice, each timeout followed by a restart at cantonBft; cn-apps
was ready on the third attempt (12:43:12), then the participant (started 12:43:25, ready 12:46:25), 38 min after the
status check. The job checks out splice `release-line-0.10.0`.

## 3. Release-line confirm: two backups with one description, both listed on every poll

In both failed attempts the create command gave up client-side after about 10 min, and the poll loop then listed two
backups for the run's description on each of its 301 polls, so `jq -r '.[].status'` gave two lines that never equal
`SUCCESSFUL`. In the first attempt both backups had finished (10:20:22 and 10:24:56) almost an hour before the wait
gave up at 11:18:45. The third attempt's create returned within the client wait: one backup, matched at once.

The first cn-apps backup took 10 min 18 s (10:10:04 to 10:20:22), 22 s longer than the client wait that gave up at
10:20:00.

## 4. The fixes are still not on the release line

```
git -C ~/Projects/splice fetch -q origin release-line-0.10.0
for c in c20fdd262b d2bedc6627; do git -C ~/Projects/splice merge-base --is-ancestor $c origin/release-line-0.10.0 && echo "$c in" || echo "$c NOT in release-line-0.10.0"; done
git -C ~/Projects/splice log -1 --format='%h %ci %s' origin/release-line-0.10.0
gh issue view 11233 -R DACH-NY/cn-test-failures --json state,stateReason
```
```
c20fdd262b NOT in release-line-0.10.0
d2bedc6627 NOT in release-line-0.10.0
500817c15d 2026-11-19 23:21:20 -0500 Backport PR #8521 to release-line-0.10.0 (#8594)
{"state":"OPEN","stateReason":""}
```

## Verdict

Duplicate of 11233 (open, not itself a duplicate; 11677, the first Mainnet hit, is closed as its duplicate),
confirmed with the family signature (`Timed out waiting for backup of cn-apps db` twice, ready for the same db on the
third attempt) and the release-line confirm (backup pairs listed on all 301 polls) on the sv-1 job of the 08:05
Mainnet Backup. Real script bug, not a flake. Fix location: splice, backport of #8310 (d2bedc6627) and #8520
(c20fdd262b) to release-line-0.10.0; the fix exists on main, so no fix branch was written and a release-line backport
is left to the release owner. Until then expect a Mainnet status failure whenever a cn-apps backup of either node
runs over about 10 min; both nodes have now hit it on the same day.

Not verified: why the cn-apps backups of sv-1 and sv-da-1 now run over 10 min, the backup list of the instances
beyond what the job output shows, and whether the next status check (15:00) passed; the 12:05 and 14:04 backup runs
passed.
