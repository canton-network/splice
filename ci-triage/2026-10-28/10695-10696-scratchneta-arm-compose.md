# 10695 preflight_check_docker / 10696 deploy_arm_compose_validator: manual scratchneta deploy_basic

Verdict: needs the GHA job logs (router refuses Actions on canton-network-internal); first occurrences of both job
names, on a developer cluster, so treat as a one-off for the person who ran it.

- Run 40580340029, workflow "Deploy scratchnet basic", cni main 3e9ecb1 ("Update config for deployment to
  scratchneta"), actor pasindutennage-da (manual workflow_dispatch on scratchneta, 10-27 ~22:00 UTC).
- Both jobs depend on `deploy_cluster` and `build_tests_self_hosted` in `.github/workflows/deploy_basic.yml`. No issue
  was filed for `deploy_cluster`, so the cluster deploy itself passed; only the two docker-compose validator jobs failed.
- deploy_basic moved from CircleCI to GHA on 10-20 (cni #7515, krzysztofczyz-da), with preflight fixes on 10-26/27
  (#7694 "stabilize preflights", #7713, #7714 "Fix missing log artifacts on preflights"). The cimain GHA deploy_basic
  runs have not filed preflight_check_docker or deploy_arm_compose_validator issues.

```
# cn-test-failures, all titles since ~2025-12 (3000 issues)
preflight_check_docker 1:        10695
deploy_arm_compose_validator 1:  10696
```

To triage further, paste from run 40580340029: the last ~50 lines of `Run docker-compose preflight tests`
(preflight_check_docker) and the first failing step of deploy_arm_compose_validator (`Wait for SV app to be
responsive` polls `https://sv.sv-2.<cluster>/api/sv/version` for 180 tries, then the compose validator start).
If both show the compose validator failing to onboard to scratchneta, it is one cause.

Action: ask Pasindu whether the scratchneta run was expected to be green; close as not planned if it was an
experiment, otherwise needs the logs above.
