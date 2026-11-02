# Host commands, 2026-10-28 round b (10694 10695 10696 10698 10699 10700 10701 10721 10724)

Run on the host (zsh). 10695/10696 get no command: ask Pasindu first. 10699/10701 get a comment only; close them as
duplicates of 10520 once the CircleCI log confirms.

```zsh
gh issue close 10694 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10643"
gh issue close 10698 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10643"
gh issue close 10700 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10643"
gh issue close 10724 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10643"
gh issue comment 10643 --repo DACH-NY/cn-test-failures --body "Devnet Backup Status now fails on every run (every ~3 h since 10-27 18:00: #10692 #10694 #10698 #10700 #10724), 17 issues since 10-23. So Devnet has had no fresh backups for those nodes since then, not only a red check. Is the 400 Mi runner request change in DACH-NY/infra in flight?"
gh issue comment 10699 --repo DACH-NY/cn-test-failures --body "Every ciperiodic workflow has failed on every run since 10-23 ~17:00 UTC (sv_reonboard 87114 first, then lsu_operator, non_devnet, deploy_upgrade), the same window as #10520 (canton-network/splice#7944 / #7910). ciperiodic is the only cluster that bootstraps at PV 36 (cluster/deployment/ciperiodic/sv.yaml override), and #10520 shows SV1 bootstrap at PV 36 fails with INITIAL_ONBOARDING_ERROR / TOPOLOGY_SERIAL_MISMATCH since then. Likely the same; the CircleCI job 525633 output (or sv-1 app logs on cn-ciperiodicnet) should show INITIAL_ONBOARDING_ERROR to confirm."
gh issue comment 10701 --repo DACH-NY/cn-test-failures --body "Likely the same as #10699: ciperiodic bootstraps at PV 36 and SV1 bootstrap at PV 36 fails since 10-23 (#10520). The CircleCI job 525660 output should show sv-1 INITIAL_ONBOARDING_ERROR to confirm."
gh issue close 10721 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #10480"
gh issue comment 10480 --repo DACH-NY/cn-test-failures --body "Failed again on 10-28 (#10721). Suspected cause: the GHA Auth0 User Cleanup job runs 'timeout 15m sbt tools/run' without the setup_sbt cache step that the CircleCI job had (setup_sbt_test_job), and tools depends on apps-app compile->test, so it compiles apps-app and its tests from scratch. Adding the setup_sbt step (as in reusable_preflight_check_docker.yml) or a longer timeout should fix it; the CircleCI trigger auth0-user-cleanup.json is also still enabled."
```
