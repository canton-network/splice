# 2026-10-30 infra: 10914 (and 10741, 10892), 10915, 10922

CircleCI returns 403 from the sandbox and canton-network-internal Actions logs are refused by the router; verdicts
below come from issue timing, the cni workflow/trigger files and cni commits (all read via ghx on 2026-10-30 ~10:15 UTC).

## 10914 / 10892 / 10741: ciperiodic deploy_sv_reonboard = family 10844 (PV 36 founding), fixed by splice#8125

- Trigger `ciperiodic-sv-reonboard.json`: Mon-Fri 08:00 and 16:00 UTC, branch main, no splice-git-ref (splice main).
- cni #7746 (4c3b77c062, 10-28 15:29) put `canton.sv-apps.sv.onboarding.initial-protocol-version = 36` on sv-1 in
  `cluster/deployment/ciperiodic/config.yaml`. That config is shared by every ciperiodic workflow, so the reonboard
  deploy founds on PV 36 too, the path that failed in 10844 (genesis OwnerToKeyMappings with regenerated keys).
- Runs: 10741 = 10-28 16:00 run (first after #7746; it also predates cni #7748 "Fix ciperiodic reonboard for the
  disabled repair service world", merged 17:30, so the repair-service break may also be in it), 10892 = 10-29 08:00,
  10914 = 10-29 16:00 (pipeline started before splice#8125 merged at 16:35).
- After the fix: the 10-30 08:00 reonboard run filed no issue by 10:13 (the failing runs filed theirs 1 h 45 to
  2 h 10 after the start), and the 10-29 23:00 deploy_lsu_operator run filed none either. No ciperiodic pipeline issue
  since 10914.
- Not log-confirmed. To confirm: in CircleCI, job 526790 (pipeline 87638) shows the sv-1 `SerialMismatch` on the
  OwnerToKeyMapping in the sv-1 app log, as in 10844.
- Verdict: dup of 10844, close all three.

## 10915: Devnet backup_status_cluster at 18:12 on 10-29, after the 10643 fix

- Not 10643: that was the 90 min `Backup node` timeout, fixed by cni #7727 (1451bfce38, 10-28 09:47: timeout removed,
  `concurrency: devnet-backup, cancel-in-progress: false`).
- Since #7727 Devnet status failed once (10915). Every other status run since 10-28 09:47 passed (no issues).
- Schedules: Devnet Backup `0 0,4,16,20`, Devnet Backup Status `0 0,3,6,18,21` with `alert_threshold: '8 hours ago'`.
  Between the 04:00 and the 16:00 backup there are 12 h, so the 18:00 status check passes only if the 16:00 backup
  finished within ~2 h; otherwise the newest backup is the 04:00 one, 14 h old. Earlier Devnet status failures cluster
  at 18:xx too (10514 10-23 18:15, 10638 10-26 18:20, 10692 10-27 18:12).
- Lead: the 18:00 check races the 16:00 backup; with the timeout gone a slow 16:00 backup now runs to completion
  instead of failing, but past 18:00. Fix options: move the status check to 20:00 or 19:00, a threshold of 14 h, or
  a backup at 10:00 or 12:00 (the 08 and 12 hours are excluded for cluster maintenance, per the workflow comment).
- needs-log: `gh run view 40928407585 -R DACH-NY/canton-network-internal --log-failed` (the stale node list) and the
  duration of the 10-29 16:00 Devnet Backup run (`gh run list -R DACH-NY/canton-network-internal --workflow devnet_backup.yml --limit 6`).
- Verdict: keep open, owner Krzysztof (cni #7727 author, 10643 owner).

## 10922: Cluster Lock Reaper (scratchnet) 10-30 04:00 run

- Workflow `cluster_lock_reaper_scratchnet.yml` (hourly Mon-Fri) calls `cluster_lock_reaper.yml` per scratchnet a-e:
  build cluster/pulumi, then `cncluster ci_assert_fresh_lock`, and on a lock older than 6 h `cncluster ci_reset_unlock`
  plus node-pool resizing. Unchanged since 07-22 (#5198).
- Earlier same-title issues 10500, 10568, 10629, 10635 were closed as one-offs ("Pulumi interrupted mid-reset (^C
  received), runner likely killed"); Krzysztof noted on 10643 that the lock reaper problems are independent of the
  backup timeouts. The 05:00 and later reaper runs on 10-30 filed nothing.
- needs-log: `gh run view 41000672485 -R DACH-NY/canton-network-internal --log-failed` (which scratchnet, which step:
  pulumi build, ci_reset_unlock, or gcloud node-pools update).
- Verdict: keep open until the log shows the step; if it is again `^C received` during the reset, close like 10635.
