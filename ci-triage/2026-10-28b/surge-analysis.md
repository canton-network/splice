# cn-test-failures surge 2026-10-14 .. 2026-10-28: causes, culprits, owners

392 issues were filed in 15 days (4-6 per day before 10-17, 22-77 per day after). About 70 % trace back to the Canton
3.7 migration, and most of the volume is duplication: the issue bot files one issue per failed job, so one broken
scheduled run produces 3-24 issues.

## Data

```
ghx "/repos/DACH-NY/cn-test-failures/issues?state=all&per_page=100&page=N&sort=created&direction=desc&since=2026-10-01T00:00:00Z"
```
Issues created 10-14..10-28 per day:
```
10-14 4, 10-15 6, 10-16 6, 10-17 27, 10-18 23, 10-19 31, 10-20 35, 10-21 9, 10-22 6, 10-23 25, 10-24 29,
10-25 22, 10-26 77, 10-27 53, 10-28 39 (to ~12:00)
```
Per workflow (from the issue body `Workflow:` line or the CircleCI title):
```
202 Integration tests against mainnet Daml version (nightly)
 65 CI post-merge to main or release-line branches (49 distinct runs)
 48 CircleCI deploy_basic (cimain)
 12 CircleCI deploy_lsu_operator, 12 deploy_sv_reonboard, 7 deploy_ciperiodic_non_devnet, 2 deploy_upgrade, 2 deploy_cimain
 18 Devnet/Mainnet Backup Status (GHA) + 3 CircleCI backup_devnet
 23 other (cluster lock reaper, scratchnet, auth0 cleanup, manual)
```

## Families by volume

| # | Family | Issues | First bad | Culprit | Status | Owner |
|---|---|---|---|---|---|---|
| 1 | Nightly mainnet-Daml, SV1 bootstrap at PV 36 fails (`INITIAL_ONBOARDING_ERROR` / `TOPOLOGY_SERIAL_MISMATCH`), 10520 | 107 (5 runs x ~22 per-job dups) | 10-24 | #7944 "Bump canton fork to 3.7" / #7910 "Switch to stable pv 37" (julientinguely-da, both 10-23) | fix #8065 open (Raymond), reproduced and verified locally at PV 36 | Julien reviews #8065 |
| 2 | Nightly mainnet-Daml, `Protocol version 35 is not supported` | 93 (4 runs 10-17..10-20, 10364-10467) | 10-17 | #7746 "canton 3.7 integration" (julientinguely-da, 10-16) dropped PV 35 while `daml_compat_test.yml` pinned PV 35 | fixed by #7756 (Julien, 10-20, PV 35 -> 36) | done |
| 3 | CircleCI deploys: `CANTON_PROTOCOL_VERSION should be set on CI`, #10494 | ~75 (deploy_basic 4 per pipeline, sv_reonboard / lsu_operator / ciperiodic_non_devnet 3 per pipeline) | 10-23 | #7944 (Julien) made the env var mandatory on CI; cni #7694 (krzysztofczyz-da, 10-26) added `CANTON_AUTOMATIC_PROTOCOL_VERSION=1` to the GHA preflights only | #10494 closed 10-27 by krzysztofczyz-da, but `.circleci/config/jobs/preflight_check.yml` is unchanged since 09-15, so CircleCI workflows can still fail this way | Krzysztof (CI preflight port) |
| 4 | Backup Status Devnet/Mainnet, backup jobs OOM on a 100 Mi runner request, #10643 | 21 | 10-23 | cni #7481 "port backup_* jobs" to GHA (krzysztofczyz-da, 10-22) | cni #7727 "ensure that backups run to completion" (Krzysztof) merged 10-28 09:47; #10643 closed; 10725 (09:06) predates it | Krzysztof: confirm the next runs are green |
| 5 | Main post-merge scala_test | 65 in 49 runs | spread | many distinct: 7 x #10358 classes-cache miss -> 40 min sbt timeout (cache-key change; owner pawelperek-da), 2 x #10474, single hits of #10676 (#7922, Julien), #10627 (#7961, fixed #7978), #10147, #10165 (Canton BFT), #10683 | mixed; ~20 still open including today's batch | per umbrella (Pawel 10358/10474, Julien 10676) |
| 6 | cimain helm schema `additionalProperties: false`, #10683 | 3-4 (10683, 10688, 10689) | 10-27 | #7178 (krzysztofczyz-da, merged 10-27 14:21) | reverted by #8015 (moritzkiefer-da, 16:08) | done; 10693 (after the revert) is something else |

## Answer

- **Top causes:** the Canton 3.7 migration by Julien (#7746 -> nightly PV 35, #7944/#7910 -> nightly PV 36 and the
  CircleCI `CANTON_PROTOCOL_VERSION` check) accounts for ~275 of 392 issues; the backup job port (cni #7481) for 21;
  the rest is ordinary post-merge flake noise at roughly the usual rate (4-13 per day).
- **Mostly noise, few problems:** the 200 nightly issues are 2 bugs, the ~75 CircleCI deploy issues are 1 bug, the 21
  backup issues are 1 bug. Real distinct problems in the window: about 15.
- **Correction to the 10-28 round b verdict:** 10699 / 10701 (ciperiodic, CircleCI) fit #10494 (still unfixed for
  CircleCI preflights, same 3-issues-per-pipeline pattern since 10-23) at least as well as 10520; a log line decides.

## Recommended assignments

- Julien (julientinguely-da): review #8065 (10520); owns the Canton 3.7 fallout umbrella and 10676.
- Krzysztof (krzysztofczyz-da): reopen #10494 for the CircleCI preflights (or finish moving those workflows to GHA);
  watch that #7727 turned Backup Status green; 10480 Auth0 cleanup (GHA port runs sbt without the cache step).
- Pawel (pawelperek-da): keeps 10358 (cache-miss timeouts) and 10474.
- Canton team: 10165 (BFT blacklisting) and 10438.

## Cheap process fixes

- One issue per scheduled run when all jobs fail (the nightly alone filed 199 issues for 2 bugs; CircleCI 3-4 per
  pipeline).
- Run the mainnet-Daml (PV 36) nightly on PRs that bump Canton or the protocol version (#7746, #7944, #7910 would have
  shown both nightly breaks before merge).
