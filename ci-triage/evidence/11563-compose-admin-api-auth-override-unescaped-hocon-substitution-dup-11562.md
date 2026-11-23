# 11563 - docker-compose (1): validator restart with auth fails, validator config does not parse (run 44301172116)

DUPLICATE of 11562 (family BG), confirmed by both confirming greps of the family. Post-merge run on main ec059037fa
("Resume interrupted onboarding snapshot downloads (#7796)"), the commit directly after 8399d63908 (#8216), job
`docker-compose (1)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620, DockerComposeValidatorFrontendIntegrationTest,
0 passed, 1 failed: `Failed to start docker-compose validator (with auth)
(DockerComposeValidatorFrontendIntegrationTest.scala:76)`. The starts without auth earlier in the test all reach
`validator-1 Healthy`. The start with `-a` prints the docker compose warning that `_admin_api_auth_service` and
`_participant_client_admin_api_auth_config` are not set (12:48:30), the validator app exits at 12:48:42.066 with
`Failed to read config at startup ... /app/additional-config.conf: 16: Cannot concatenate object or list with a
non-object-or-list`, and compose reports `container splice-validator-validator-1 is unhealthy`. The override file
`cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml` is unchanged between 8399d63908 and
origin/main, so this is the same deterministic regression, not a flake, and not caused by #7796.

- Run: https://github.com/canton-network/splice/actions/runs/44301172116, main ec059037fa (#7796), push, job
  134791792826 `ci / scala_test_docker_compose / docker-compose (1)`.
- Ref mapping: issue 11563 body names job docker-compose (1) of this run. The run has a second failed job,
  134792368662 `frontend-wall-clock-time (2)`, which is not part of this ref and was not looked at.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at ec059037fa).
- Component: splice compose files (regression of splice#8216), hard broken on main.
- Parent: 11562, open, not closed as a duplicate, the ref family BG is filed under.
- Artifacts: `log/11563/job.log`, `log/11563/logs-docker-compose-1/`.

## 1. Failing assertion

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134791792826/logs > log/11563/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11563/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/11563/job.log | grep -a -m1 -A1 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //'
```
```
##[error]Process completed with exit code 1.
[info] - docker-compose based validator works *** FAILED ***
[info] Run completed in 16 minutes, 56 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
[info] - docker-compose based validator works *** FAILED ***
[info]   Failed to start docker-compose validator (with auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)
```

## 2. Which step failed

```
zcat log/11563/logs-docker-compose-1/canton_network_test.clog.gz | grep -a -E 'Failed to start docker-compose|Starting test suite|Failed clue|Test (succeeded|failed): ' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-220
```
```
2026-11-18T12:32:20.400Z Starting test suite 'DockerComposeValidatorFrontendIntegrationTest'...",
2026-11-18T12:48:51.417Z Failed clue: Restart the validator, with auth",
2026-11-18T12:49:15.766Z Test failed: 'DockerComposeValidatorFrontendIntegrationTest/docker-compose based validator works', message: Failed to start docker-compose validator (with auth), location: SeeStackDepthException"
```

## 3. Confirming grep 1 of family BG: docker compose blanks the two substitutions

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11563/job.log | grep -a -E '_(participant_client_admin_api_auth_config|admin_api_auth_service).. variable is not set|is unhealthy|Validator failed to become ready'
```
```
time="2026-11-18T12:48:30Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
time="2026-11-18T12:48:30Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
time="2026-11-18T12:48:41Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
time="2026-11-18T12:48:41Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
container splice-validator-validator-1 is unhealthy
ERROR: Validator failed to become ready
```

## 4. Confirming grep 2 of family BG: the validator app cannot parse its config

```
zcat log/11563/logs-docker-compose-1/compose-validator.clog.gz | grep -a 'Failed to read config at startup' | cut -c1-330
```
```
{"@timestamp":"2026-11-18T12:48:42.066Z","message":"Failed to read config at startup: Error(List(com.typesafe.config.ConfigException$WrongType: /app/additional-config.conf: 16: Cannot concatenate object or list with a non-object-or-list, Unquoted(\"canton.validator-apps.validator_backend.storage\") and SimpleConfigObject({\"config\":{\"dataSourceClass\":\"org.postg
```

## 5. The cause is still on main

The run's sha is the first commit after #8216 and the override still has the single `$` on origin/main (fetched
2026-11-18).

```
git log --oneline 8399d63908^..ec059037fa
git log --oneline -3 origin/main -- cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml
git show origin/main:cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml | grep -n '{_'
```
```
ec059037fa Resume interrupted onboarding snapshot downloads (#7796)
8399d63908 implement participant admin API auth support in clusters (#8216)
8399d63908 implement participant admin API auth support in clusters (#8216)
10:          canton.participants.participant.admin-api.auth-services=[${_admin_api_auth_service}]
21:          canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = ${_participant_client_admin_api_auth_config}
```

## Verdict

Duplicate of 11562 (family BG): same test, same step, same docker compose warning, same config parse error at the
same config line. Real regression of splice#8216 in the compose files, deterministic: every docker-compose (1) run
on main fails until the override escapes the two substitutions (`$${...}`). Mechanism and fix proposal are in the
11562 packet (`evidence/11562-compose-admin-api-auth-override-unescaped-hocon-substitution.md`); nothing was fixed
here. Not verified: the other failed job of the run (frontend-wall-clock-time (2)), which belongs to no ref given.
