# Host commands, 2026-10-28 round b2 (10725 10733 10736, correction on 10677)

Run on the host (zsh). 10733 gets a comment only until the CircleCI job log is pasted.

```zsh
gh issue close 10725 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10643. This Mainnet Backup Status run (10-28 09:06) predates the fix, DACH-NY/canton-network-internal#7727 (no 90 min timeout on the backup step, one backup run at a time), merged 09:47. Backup status stays red until the stale nodes have completed a backup with the new workflow."
gh issue comment 10733 --repo DACH-NY/cn-test-failures --body "Not the cluster deploy as far as visible: no deploy_basic issue was filed for build/cimain-deploy-basic around this run, so the failing job is likely wait_for_previous_pipeline or dependencies_scan (the two notify-fail jobs of deploy_cimain). cni 1451bfce38 includes #7718, so this is not #10693. Needs the failing job name and its last lines of CircleCI job 525868."
gh issue close 10736 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10677: same ciperiodic upgrade test (10:00 run), same synchronizerMigration.active config added by the branch after DACH-NY/canton-network-internal#7703. Needs the failing step of CircleCI job 525815 (or 525204 for #10677)."
gh issue comment 10677 --repo DACH-NY/cn-test-failures --body "Failed again on 10-28 (#10736, 10:00 run). Not the PV 36 SV1 bootstrap bug (#10520): this test deploys the latest release (release-line-0.9.x, Canton 3.6.4) and then upgrades to main, and its runs 10-23 18:00 to 10-26 18:00 filed no issues while #10520 was already failing. Both failures are the first 10:00 runs after DACH-NY/canton-network-internal#7703 (switch upgrade to active), whose config the branch adds (synchronizerMigration.active id 0, version local). Needs the failing step of CircleCI job 525815."
```
