# 10733: deploy_cimain (CircleCI pipeline 87435, job 525868), needs the job log

Verdict: needs log. Most likely NOT the cluster deploy itself: no deploy_basic issue was filed for
build/cimain-deploy-basic around 10-28 11:00, so the failing job is probably one of the two notify-fail jobs of the
wrapper workflow (`wait_for_previous_pipeline` or `dependencies_scan`). Not the 10693 situation either.

- cni main 1451bfce38 (#7727, backup workflows only, GHA). Splice submodule since cni #7719 (10-28 08:38):
  d0a311567d. Splice change vs the previous cni bump (#7690, 74cdc0dc82):

```
git log --format='%h %ad %an | %s' --date=format:%m-%d.%H:%M 74cdc0dc82..d0a311567d
```
```
d0a311567d 10-28.15:13 Divam | Daml for TBAR v1.1 Amendment (#7627)
dee813861c 10-27.17:23 Matt Dziuban | Test that governance locks merge when created with 0.0 for the amount (#8003)
4471e055ac 10-27.18:59 Martin Florian | `Bump Canton to 3.7.0-snapshot.20261027.20812.0.v1af931f4` and switch to using `enable-key-download` flag (#7998)
```

- 10693 (deploy_basic on cimain, 10-27) is closed: Martin, 10-28 10:14, it ran shortly before cni #7718 set
  `enableKeyDownload` on cimain's validator-runbook, so it was expected. #7718 is in 1451bfce38, so 10733 is not that.
- The workflow (`.circleci/config.yml`, `deploy_cimain`): `wait_for_previous_pipeline` (notify-fail) ->
  `launch_deploy_basic_and_wait` (spawns a deploy_basic pipeline on build/cimain-deploy-basic, cluster cimain, no
  notify-fail of its own; failures there file `Pipeline N : workflow deploy_basic Failed`) and `dependencies_scan`
  (notify-fail; `bundle` then `blackduck-scan.sh --rapid`, which on splice main reports new vulnerabilities to Slack
  without failing, then a full scan). Earlier deploy_cimain failures: 10305 (dependencies_scan, `fork/exec /bin/bash:
  operation not permitted`, runner fault) and 10493 (coursier `Stale file handle` during sbt update in the same job).

Paste: the failing job name and its last ~40 lines from
https://app.circleci.com/pipelines/github/DACH-NY/canton-network-internal/87435/workflows/616b51d0-5f25-4db8-91cc-4ee588ffe0b9/jobs/525868
If it is `dependencies_scan` failing in `bundle`/sbt update or Black Duck, it is the 10305/10493 runner family; if it
is `launch_deploy_basic_and_wait`, look for the child deploy_basic pipeline.
