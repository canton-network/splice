# 2026-11-02 fork C: infra (11106, 11137, 11117, 11118)

No logs are readable from the sandbox: CircleCI answers 403 and the router refuses cni Actions
(`gh-cred-router: GET api:/actions/runs/41175569098 not allowed by route DACH-NY/canton-network-internal`).
Evidence below is from issue bodies, cni contents/commits via `ghx`, splice git and splice run metadata via `gh api`.

## 11117 + 11118: Devnet backups on the first night after the Devnet LSU (serial 6 to 7)

| Issue | cni run | Workflow | Created (estimated) | Filed |
|---|---|---|---|---|
| 11118 | 41166827191 | Devnet Backup (cron 0,4,16,20) | ~04:15 10-31 = the 04:00 run | 07:10 |
| 11117 | 41175569098 | Devnet Backup Status (cron 0,3,6,18,21, threshold 8 h) | ~05:56 10-31 = the 06:00 run | 06:12 |

Run creation times are not readable; estimated from splice run ids (5.19 M ids/h):

```
$ for r in 41132609705 41171022068 41275427340; do gh api repos/canton-network/splice/actions/runs/$r --jq '"\(.id) \(.created_at) \(.name)"'; done
41132609705 2026-10-30T21:39:05Z CI post-merge to main or release-line branches
41171022068 2026-10-31T05:03:17Z Integration tests against mainnet Daml version
41275427340 2026-11-01T05:03:34Z Integration tests against mainnet Daml version
```

What changed the evening before: the Devnet LSU config switch, cni #7836 / #7837 at 10-30 20:28.

```
$ ghx "/repos/DACH-NY/canton-network-internal/commits?path=cluster/deployment/devnet&since=2026-10-25T00:00:00Z&per_page=10"
11a0a1dda3 2026-10-30T20:28:20Z Backport PR #7836 to main (#7837)          <- "Post LSU config update (#7836)", cni issue 7645
670b5236ba 2026-10-30T20:25:31Z [static] Bump splice to latest (#7825)
...
$ ghx ".../contents/cluster/deployment/devnet/config.resolved.yaml?ref=<before|after>"   (synchronizerMigration)
before 670b5236ba: active.id 6, legacy.id 5, upgrade.id 7
after  11a0a1dda3: active.id 7, legacy.id 6, additionalLegacy [5], frozenMigrationId 1
```

Both workflows read `ACTIVE_MIGRATION_ID` from `.synchronizerMigration.active.id`, so from the 00:00 run on they back up
and check serial 7: `cncluster backup_nodes 7 <node>` backs up `sequencer-7`, `mediator-7`, `sequencer-bft-7`, `cn-apps`,
`participant` (`cluster/scripts/node-backup.sh`, `utils.source` `create_component_instance`, up/release-line-0.9.4), and
`check-backups-status.sh` checks Cloud SQL instances with `labels.migration_id=7 OR NOT labels.migration_id:*`
(an instance without any backup prints `No snapshot found yet.` and passes).

Timeline of 10-30/31 (from issue creation; no other Devnet backup or status issue exists after 10915):
- 00:00 and 03:00 status passed (threshold 16:00 / 19:00: the 20:00 run, still serial 6, covers them).
- 00:00 Devnet Backup: no issue filed, so it either succeeded or was still running (no step timeout since cni #7727;
  concurrency group `devnet-backup`, `cancel-in-progress: false`, so the 04:00 run queues behind it).
- 06:00 status failed: some checked instance had no backup newer than 22:00, i.e. the 00:00 run had not produced one.
  This is not the 18:00 schedule gap of 10915 (at 06:00 the 00:00 backup is only 6 h old).
- 04:00 Devnet Backup failed at ~07:10.
- 11-01 and 11-02: no Devnet backup or status issue, so it recovered by itself.

Verdict: one incident, the first Devnet backups after the LSU switch to serial 7. Cause not established: either the
first serial-7 backup ran >6 h (same Cloud SQL slow backup + 409 retry pattern as 10745, which was closed as a
dup of 10915), or a serial-7 component failed to back up. 11118 = dup of 11117. Needs-log:
`gh run view 41175569098 -R DACH-NY/canton-network-internal --log-failed` (the stale instance list) and
`gh run view 41166827191 -R DACH-NY/canton-network-internal --log-failed` (which node, which step). Owner: Krzysztof
(backup workflows, 10915) or Pasindu (author of the LSU config change #7836). Mainnet/TestNet will hit the same switch
at their LSU (MainNet 12-12): worth knowing which of the two it was before then.

## 11106 + 11137: ciperiodic deploy_sv_reonboard, intermittent, not 10844

| Issue | Pipeline | Job in issue | cni build commit | splice commit deployed |
|---|---|---|---|---|
| 11106 | 87744, 10-30 16:37 | 527403 | 300c5fb18c | 76e1efc724 (#8035, 10-30 15:31 UTC) |
| 11137 | 87814, 11-02 08:19 | 527732 | 0d480087bd | e4825eacda (#8156, 11-02 07:43 UTC) |

```
$ ghx /repos/DACH-NY/canton-network-internal/commits/040af408e9   (parent of 300c5fb18c)
040af408e9 2026-10-30T16:08:44Z Set Splice commit to: 76e1efc72 [ci] Bump use-debounce from 9.0.4 to 10.1.1 in /apps (#8035)
$ ghx /repos/DACH-NY/canton-network-internal/commits/99aa5c5fb5   (parent of 0d480087bd)
99aa5c5fb5 2026-11-02T08:08:43Z Set Splice commit to: e4825eacd make perf test db instance similar to prod spec (#8156)
$ git merge-base --is-ancestor b4fc190853 <commit> && echo contains #8125
76e1efc7246df7843a05e2f0b591fb0013b730bd contains #8125
e4825eacda2f7f6f81fe1fcc728fcac1a739fc4a contains #8125
```

Both deployed splice commits contain #8125 (the 10844 fix, b4fc190853), and the "Update config for deployment to
ciperiodic" commit is the usual one (adds `synchronizerMigration.active.id 0`, `validator1.disableAuth`). The runs in
between (10-30 08:00, 10-31 and 11-01) filed no issue, so the workflow passes most of the time: 2 of the 7 runs
from 10-30 08:00 to 11-02 08:00 (08:00 and 16:00 daily) failed. Not family N / 10844. Cause unknown without the CircleCI step output. Needs-log: pipelines 87744 and
87814 (job list) and jobs 527403 and 527732 (step logs), recipe in `host-commands-c.md`. 11137 stays a separate
issue until the logs show the same failing step.
