# Host commands, 2026-10-30 fork C (infra)

State-guarded: running it twice is harmless.

```zsh
[ "$(gh issue view 10914 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh issue close 10914 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10844: ciperiodic founds on PV 36 since cni #7746 (shared ciperiodic config), the 10-29 16:00 run started before canton-network/splice#8125 merged; the 10-30 08:00 reonboard run filed no issue."
[ "$(gh issue view 10892 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh issue close 10892 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10844: ciperiodic founds on PV 36 since cni #7746 (shared ciperiodic config), fixed by canton-network/splice#8125; the 10-30 08:00 reonboard run filed no issue."
[ "$(gh issue view 10741 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh issue close 10741 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10844: first reonboard run after cni #7746 (PV 36 founding), fixed by canton-network/splice#8125; it also predates cni #7748 (repair service fix). The 10-30 08:00 reonboard run filed no issue."
[ "$(gh issue view 10915 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh issue view 10915 --repo DACH-NY/cn-test-failures --json title -q .title | grep -q '^GHA Run' && gh issue edit 10915 --repo DACH-NY/cn-test-failures --title "Devnet backup_status_cluster: 18:00 check finds stale backups when the 16:00 backup has not finished (12 h gap, 8 h threshold)"
[ "$(gh issue view 10915 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && ! gh issue view 10915 --repo DACH-NY/cn-test-failures --comments | grep -q 'Not #10643' && gh issue comment 10915 --repo DACH-NY/cn-test-failures --body "Not #10643 (fixed by cni #7727, which removed the 90 min Backup node timeout). Devnet Backup runs at 0,4,16,20 UTC, Devnet Backup Status at 0,3,6,18,21 with an 8 h threshold: between the 04:00 and 16:00 backups there are 12 h, so the 18:00 check only passes if the 16:00 backup finished within ~2 h. Earlier Devnet status failures also cluster at 18:xx (#10514, #10638, #10692). Options: check at 19:00/20:00, a 14 h threshold, or an extra backup between 04:00 and 16:00 outside the 08/12 maintenance hours. To confirm: the stale node list in run 40928407585 and the duration of the 10-29 16:00 Devnet Backup run. @krzysztofczyz-da"
[ "$(gh issue view 10922 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh run view 41000672485 -R DACH-NY/canton-network-internal --log-failed | tail -60
```

The last command only prints the reaper log for triage (10922); paste its output.
