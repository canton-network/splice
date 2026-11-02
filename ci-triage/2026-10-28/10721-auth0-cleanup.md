# 10721 auth0_user_cleanup: second weekly failure in a row, same as 10480

Verdict: same failure as 10480 (open, 10-21, backfilled by krzysztofczyz-da for cni#7615); needs the GHA log
(router refuses Actions on canton-network-internal). Likely cause from the workflow definitions: the GHA port has no sbt
cache setup, so the 15-minute `sbt tools/run` step compiles apps-app including its tests from scratch.

- 10721: run 40641314724, workflow "Auth0 User Cleanup", cni main 3e9ecb1, scheduled Wed 06:00 UTC (10-28).
- 10480: run 36676064976, same workflow, the previous weekly runs; body "Backfilling because of
  https://github.com/DACH-NY/canton-network-internal/issues/7615" (internal issue, not readable from the sandbox).
- Earlier history: 8666 (2026-06-03, CircleCI, closed as duplicate).

## The GHA job vs the CircleCI job it replaces

`.github/workflows/auth0_user_cleanup.yml` (cni main 1ef4be0):

```
      - name: Run Auth0 user cleanup
        uses: ./.github/actions/nix/run
        with:
          cmd: |
            cd splice
            timeout --kill-after=30s 15m sbt "tools/run"
```

No `setup_sbt` step (the preflight workflows use `./splice/.github/actions/sbt/setup_sbt` to restore the compiled
classes). `.circleci/config/jobs/cleanup.yml` `auth0_user_cleanup` runs `setup_sbt_test_job` first, then
`tools/run` with a 10 m timeout. In splice `build.sbt:208`, `tools` `.dependsOn(\`apps-app\` % "compile->test")`, so
`tools/run` compiles all of apps-app and its test sources; from a cold cache that does not fit in 15 minutes (a cold
main compile is ~13 min of the 40 min CI budget even with dependencies resolved, see family J / 10358).

The CircleCI trigger `.circleci/triggers/enabled/auth0-user-cleanup.json` (Wed 06:00) is still enabled as well, so the
cleanup may run on both systems.

To confirm, paste the end of the `Run Auth0 user cleanup` step from run 40641314724: `Terminated` / exit code 124
after ~15 min with `[info] compiling ...` lines means the timeout; an Auth0 management API error (401/429) means
credentials or rate limits instead.

Fix (cni): add the `setup_sbt` step (as in reusable_preflight_check_docker.yml) before `tools/run`, or raise the
timeout; disable the CircleCI trigger once the GHA job is green.

Action: close 10721 as duplicate of 10480; comment on 10480 with the suspected cause.
