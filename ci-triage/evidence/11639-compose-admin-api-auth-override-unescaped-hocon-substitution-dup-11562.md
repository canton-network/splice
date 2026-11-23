# 11639 - docker-compose (1): validator restart with auth fails, validator config does not parse (run 44364590139)

DUPLICATE of 11562 (family BG), confirmed by both confirming greps of the family. Post-merge run on main 53e7efb8e2
("Fix SvOnboardingSnapshotServiceTest compilation (#8569)"), job `docker-compose (1)`, Canton
3.7.4-snapshot.20261112.20915.0.v9e248620, DockerComposeValidatorFrontendIntegrationTest, 0 passed, 1 failed:
`Failed to start docker-compose validator (with auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)`.
The start with `-a` prints the docker compose warning that `_admin_api_auth_service` and
`_participant_client_admin_api_auth_config` are not set (18:40:57), the validator app exits at 18:41:06.565 with
`Failed to read config at startup ... /app/additional-config.conf: 16: Cannot concatenate object or list with a
non-object-or-list`, and compose reports `container splice-validator-validator-1 is unhealthy`. None of the three
commits since the 11597 run (465b29cc47) touches `cluster/compose`, and the override still has the single `$` on
origin/main, so this is the same deterministic regression of splice#8216, not a flake.

- Run: https://github.com/canton-network/splice/actions/runs/44364590139, main 53e7efb8e2 (#8569), push, job
  135015333831 `ci / scala_test_docker_compose / docker-compose (1)`.
- Ref mapping: issue 11639 body names `Job: docker-compose (1) in scala_test` of this run; it is the only failed job
  of the run.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 53e7efb8e2).
- Component: splice compose files (regression of splice#8216), hard broken on main.
- Parent: 11562, open, not closed as a duplicate; 11563 and 11597 are already closed as duplicates of 11562.
- Artifacts: `log/11639/job.log`, `log/11639/logs-docker-compose-1/`.

## 1. Failing assertion

```
gh api --paginate "repos/canton-network/splice/actions/runs/44364590139/jobs?filter=all&per_page=100" --jq '.jobs[] | select(.conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135015333831/logs > log/11639/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11639/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
135015333831 attempt=1 ci / scala_test_docker_compose / docker-compose (1)
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - docker-compose based validator works *** FAILED ***
[info] Run completed in 17 minutes, 35 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
```
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11639/job.log | grep -a -m1 -A1 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //'
```
```
[info] - docker-compose based validator works *** FAILED ***
[info]   Failed to start docker-compose validator (with auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)
```

## 2. Which step failed

```
zcat log/11639/logs-docker-compose-1/canton_network_test.clog.gz | grep -a -E 'Failed to start docker-compose|Starting test suite|Failed clue|Test (succeeded|failed): ' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-220
```
```
2026-11-18T18:24:03.242Z Starting test suite 'DockerComposeValidatorFrontendIntegrationTest'...",
2026-11-18T18:41:12.131Z Failed clue: Restart the validator, with auth",
2026-11-18T18:41:36.920Z Test failed: 'DockerComposeValidatorFrontendIntegrationTest/docker-compose based validator works', message: Failed to start docker-compose validator (with auth), location: SeeStackDepthException"
```

## 3. Confirming grep 1 of family BG: docker compose blanks the two substitutions

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11639/job.log | grep -a -E '_(participant_client_admin_api_auth_config|admin_api_auth_service).. variable is not set|is unhealthy|Validator failed to become ready'
```
```
time="2026-11-18T18:40:57Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
time="2026-11-18T18:40:57Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
time="2026-11-18T18:41:05Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
time="2026-11-18T18:41:05Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
container splice-validator-validator-1 is unhealthy
ERROR: Validator failed to become ready
```

## 4. Confirming grep 2 of family BG: the validator app cannot parse its config

```
zcat log/11639/logs-docker-compose-1/compose-validator.clog.gz | grep -a 'Failed to read config at startup' | cut -c1-330
```
```
{"@timestamp":"2026-11-18T18:41:06.565Z","message":"Failed to read config at startup: Error(List(com.typesafe.config.ConfigException$WrongType: /app/additional-config.conf: 16: Cannot concatenate object or list with a non-object-or-list, Unquoted(\"canton.validator-apps.validator_backend.storage\") and SimpleConfigObject({\"conf
```

## 5. The cause is still on main

The three commits between the 11597 run (465b29cc47) and this run do not touch `cluster/compose`; the override still
has the single `$` on origin/main (fetched 2026-11-18), last changed by #8216.

```
git log --oneline 465b29cc47..53e7efb8e2
git log --oneline 465b29cc47..53e7efb8e2 -- cluster/compose
git log --oneline -3 origin/main -- cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml
git show origin/main:cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml | grep -n -E '_admin_api_auth_service|_participant_client_admin_api_auth_config'
```
```
53e7efb8e2 Fix SvOnboardingSnapshotServiceTest compilation (#8569)
10de6749e0 Only fetch the git refs a CI job needs in `common_setup.sh` (#7932)
a4ca636ec0 Export sequencer onboarding states on separate queue (#8558)
8399d63908 implement participant admin API auth support in clusters (#8216)
10:          canton.participants.participant.admin-api.auth-services=[${_admin_api_auth_service}]
21:          canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = ${_participant_client_admin_api_auth_config}
```

## 6. Parent state in the tracker

```
for i in 11562 11563 11597; do gh issue view $i -R DACH-NY/cn-test-failures --json number,state,stateReason,title --jq '"\(.number) \(.state) \(.stateReason) \(.title)"'; done
```
```
11562 OPEN  DockerComposeValidatorFrontendIntegrationTest: validator restart "with auth" fails, compose override of #8216 blanks the HOCON substitutions of the participant admin API auth config
11563 CLOSED DUPLICATE GHA Run 44301172116 : job docker-compose (1) in scala_test Failed :fire:
11597 CLOSED DUPLICATE GHA Run 44322981802 : job docker-compose (1) in scala_test Failed :fire:
```

## Verdict

Duplicate of 11562 (family BG): same test, same step, same docker compose warning, same config parse error at the
same config line. Real regression of splice#8216 in the compose files, deterministic: every docker-compose (1) run
on main fails until the override escapes the two substitutions (`$${...}`). Mechanism and fix proposal are in the
11562 packet (`evidence/11562-compose-admin-api-auth-override-unescaped-hocon-substitution.md`); nothing was fixed
here. Fourth hit on main the same day: the fix for 11562 is the priority, not this ref. Proposed issue action (not
run, for the user on the host):
`close_duplicates.py 11562 11639 --comment 'Duplicate of 11562: same compose override regression of splice 8216, validator config parse error at additional-config.conf line 16.'`
