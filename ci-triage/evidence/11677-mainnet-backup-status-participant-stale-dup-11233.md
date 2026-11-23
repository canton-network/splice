# 11677 - Mainnet backup_status_cluster: sv-da-1 participant stale at the 09:05 check

DUPLICATE of 11233 (Cloud SQL backup slower than the backup script allows: `Timed out waiting for backup of <db> db`
in `wait_for_cloudsql_backup`, the job restarts the node from its first component, and the status check runs before
the component that is backed up last has a fresh backup). First Mainnet hit of the family. The 06:06 Mainnet Backup
run timed out three times on cn-apps of sv-da-1 (07:20:56, 08:29:18, 09:40:04) and reached the participant only at
09:56:48, so the 09:05 check found the participant backup of the 04:05 run (04:59:37, 4 h 06 min old, threshold 4 h).
Mainnet runs `cluster/scripts/node-backup.sh` from splice `release-line-0.10.0`, which has neither splice#8310 nor
splice#8520: when the backup create command gives up waiting client-side, the script retries the create and leaves
two backups with the same RUN_ID description, and the wait loop compares the two statuses `SUCCESSFUL\nSUCCESSFUL`
to `SUCCESSFUL`, so it can never match. Both fixes are on splice main; the resolution is a backport to
release-line-0.10.0. Job: Mainnet Backup Status workflow (schedule), job backup_status_mainnet / Backup status
cluster, 2026-11-20 09:04 to 09:06 UTC.

- Parent: 11233.
- Runtime canton: n/a (no test run; cluster backup check).
- Component: splice cluster script (`cluster/scripts/node-backup.sh` on release-line-0.10.0), missing backport.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. What the status check saw

One stale instance, the sv-da-1 participant, with a latest backup older than the 4 hour threshold (created 04:59:37
UTC); every other instance had a backup from the 06:06 run.

## 2. The backup runs around the check

The 04:05 run took 14 min per node. The sv-da-1 job of the 06:06 run took 3 h 52 min (06:08 to 10:00), the sv-1 job
1 h 33 min. The 08:05 run queued behind it and started at 10:01.

In the sv-da-1 job of the 06:06 run cn-apps timed out three times, each timeout followed by a restart at cantonBft;
cn-apps was ready on the fourth attempt (09:56:35), then the participant (09:59:49), 51 min after the status check.
The sv-1 job of the same run timed out once on cn-apps (07:22:26) and finished on the second attempt (participant
ready 07:40:54), before the check.

## 3. Why the wait timed out: two backups with the same description, both successful

In each failed attempt the create ran over the client-side wait of the create command (about 10 min) and was
reported as failed; the script retried the create (first attempt: seven rejections because another operation was
already in progress, then success at 06:31:18), and the poll loop then listed two backups for the run's description
on every one of its 301 polls. Both listed backups were in status SUCCESSFUL, the first finished at 06:25:51 and the
second at 06:31:17, long before the wait gave up at 07:20:56. The same pair pattern (two backups, 301 listings each)
shows in the second and third attempts.

The first cn-apps backup took 13 min 51 s (06:12:00 to 06:25:51); in the 04:05 run it was done in under 5 min. The
fourth attempt's create returned without the client-side give-up (09:43:14 to 09:53:11), so there was one backup and
the wait matched at once.

## 4. The script that ran, and the fixes it lacks

The backup job checks out splice `release-line-0.10.0`. In `cluster/scripts/node-backup.sh` on that branch the create
is issued with the RUN_ID as description, and the wait loop lists all backups with that description, takes
`status=$(echo "$backup" | jq -r '.[].status')` and tests `if [ "$status" == "SUCCESSFUL" ]`: with two backups the
status is two lines and never equals the one string.

Main has both fixes (await the same operation instead of a second create; accept any successful backup of the
description); the release line (tip 500817c15d, 2026-11-19 23:21 -0500) has neither, and no backport PR is listed.

```
git log -1 --format='%h %ci %s' c20fdd262b; git log -1 --format='%h %ci %s' d2bedc6627
for c in c20fdd262b d2bedc6627; do git merge-base --is-ancestor $c origin/release-line-0.10.0 && echo "$c in" || echo "$c NOT in release-line-0.10.0"; done
git log --oneline -3 origin/release-line-0.10.0 -- cluster/scripts/node-backup.sh
gh pr list -R canton-network/splice --state all --base release-line-0.10.0 --limit 60 --json number,title | jq -r '.[]|.title' | grep -i -c -E 'backup|8520|8310'
```
```
c20fdd262b 2026-11-17 13:46:32 -0500 Fix duplicate CloudSQL backups in node-backup.sh (#8520)
d2bedc6627 2026-11-05 14:10:44 +0100 fix backup code (#8310)
c20fdd262b NOT in release-line-0.10.0
d2bedc6627 NOT in release-line-0.10.0
0a3a7925e6 more printouts in backup script (#7695)
efc9af496f Fix backup & restore on helmless postgres (#6668)
e6cdebb47b Nicu/cantonbft/add backup restore back (#6447)
0
```

## Verdict

Duplicate of 11233 (open, not itself a duplicate), confirmed with the family signature (`Timed out waiting for backup
of cn-apps db` three times, ready for the same db on a later attempt) on the sv-da-1 job of the 06:06 Mainnet Backup.
Real script bug, not a flake: it fires whenever one Cloud SQL backup exceeds the client-side wait of the create
command. Fix location: splice, backport of #8310 (d2bedc6627) and #8520 (c20fdd262b) to release-line-0.10.0 (and any
later line Mainnet moves to); #8392 sits between them on main, so the cherry-picks may need a manual resolution. No
fix branch written: the fix exists on main and a release-line backport is the release owner's decision.

Not verified: why the cn-apps backup of sv-da-1 took 14 min at 06:12 against under 5 min at 04:51; whether the status
check recovered at 12:00 (run after this triage); whether the cherry-picks apply cleanly.
