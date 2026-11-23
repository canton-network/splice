# 11597 - docker-compose (1): validator restart with auth fails, validator config does not parse (run 44322981802)

DUPLICATE of 11562 (family BG), confirmed by both confirming greps of the family. Post-merge run on main 465b29cc47
("AGENT.md: refer to new DAML_STYLE.md and add instructions for TODO notes (#8551)"), the second commit after
8399d63908 (#8216), job `docker-compose (1)`, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620,
DockerComposeValidatorFrontendIntegrationTest, 0 passed, 1 failed: `Failed to start docker-compose validator (with
auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)`. The starts without auth earlier in the test reach
`validator-1 Healthy`. The start with `-a` prints the docker compose warning that `_admin_api_auth_service` and
`_participant_client_admin_api_auth_config` are not set (14:59:37), the validator app exits at 14:59:49.995 with
`Failed to read config at startup ... /app/additional-config.conf: 16: Cannot concatenate object or list with a
non-object-or-list`, and compose reports `container splice-validator-validator-1 is unhealthy`. #8551 only touches
agent docs; the override file is unchanged between 8399d63908 and origin/main, so this is the same deterministic
regression, not a flake.

- Run: https://github.com/canton-network/splice/actions/runs/44322981802, main 465b29cc47 (#8551), push, job
  134867172596 `ci / scala_test_docker_compose / docker-compose (1)`.
- Ref mapping: issue 11597 body names `Job: docker-compose (1) in scala_test` of this run. The run has a second
  failed job, 134867152260 `simtime (0)`, which is 11565 (triaged separately) and not part of this ref.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 465b29cc47).
- Component: splice compose files (regression of splice#8216), hard broken on main.
- Parent: 11562, open, not closed as a duplicate; 11563 is already closed as a duplicate of 11562.
- Artifacts: `log/11597/job.log`, `log/11597/logs-docker-compose-1/`.

## 1. Failing assertion

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134867172596/logs > log/11597/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11597/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - docker-compose based validator works *** FAILED ***
[info] Run completed in 17 minutes, 24 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
```
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11597/job.log | grep -a -m1 -A1 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //'
```
```
[info] - docker-compose based validator works *** FAILED ***
[info]   Failed to start docker-compose validator (with auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)
```

## 2. Which step failed

```
zcat log/11597/logs-docker-compose-1/canton_network_test.clog.gz | grep -a -E 'Failed to start docker-compose|Starting test suite|Failed clue|Test (succeeded|failed): ' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-220
```
```
2026-11-18T14:42:56.098Z Starting test suite 'DockerComposeValidatorFrontendIntegrationTest'...",
2026-11-18T14:59:52.738Z Failed clue: Restart the validator, with auth",
2026-11-18T15:00:19.472Z Test failed: 'DockerComposeValidatorFrontendIntegrationTest/docker-compose based validator works', message: Failed to start docker-compose validator (with auth), location: SeeStackDepthException"
```

## 3. Confirming grep 1 of family BG: docker compose blanks the two substitutions

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11597/job.log | grep -a -E '_(participant_client_admin_api_auth_config|admin_api_auth_service).. variable is not set|is unhealthy|Validator failed to become ready'
```
```
time="2026-11-18T14:59:37Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
time="2026-11-18T14:59:37Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
time="2026-11-18T14:59:49Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
time="2026-11-18T14:59:49Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
container splice-validator-validator-1 is unhealthy
ERROR: Validator failed to become ready
```

## 4. Confirming grep 2 of family BG: the validator app cannot parse its config

```
zcat log/11597/logs-docker-compose-1/compose-validator.clog.gz | grep -a 'Failed to read config at startup' | cut -c1-330
```
```
{"@timestamp":"2026-11-18T14:59:49.995Z","message":"Failed to read config at startup: Error(List(com.typesafe.config.ConfigException$WrongType: /app/additional-config.conf: 16: Cannot concatenate object or list with a non-object-or-list, Unquoted(\"canton.validator-apps.validator_backend.storage\") and SimpleConfigObject({\"conf
```

## 5. The cause is still on main

The run's sha is two commits after #8216, neither touching compose; the override still has the single `$` on
origin/main (fetched 2026-11-18, tip 53e7efb8e2), and no PR other than #8216 touches the file.

```
git log --oneline 8399d63908^..465b29cc47
git log --oneline -3 origin/main -- cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml
git show origin/main:cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml | grep -n '{_'
gh pr list -R canton-network/splice --state all --search 'compose-enable-participant-admin-api-auth' --json number,title,state,mergedAt --limit 5
```
```
465b29cc47 AGENT.md: refer to new DAML_STYLE.md and add instructions for TODO notes (#8551)
ec059037fa Resume interrupted onboarding snapshot downloads (#7796)
8399d63908 implement participant admin API auth support in clusters (#8216)
8399d63908 implement participant admin API auth support in clusters (#8216)
10:          canton.participants.participant.admin-api.auth-services=[${_admin_api_auth_service}]
21:          canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = ${_participant_client_admin_api_auth_config}
[{"mergedAt":"2026-11-18T10:49:54Z","number":8216,"state":"MERGED","title":"implement participant admin API auth support in clusters"}]
```

## 6. Parent state in the tracker

```
for i in 11562 11563; do gh api repos/DACH-NY/cn-test-failures/issues/$i --jq '"\(.number) \(.state) \(.state_reason) \(.title)"'; done
```
```
11562 open null DockerComposeValidatorFrontendIntegrationTest: validator restart "with auth" fails, compose override of #8216 blanks the HOCON substitutions of the participant admin API auth config
11563 closed duplicate GHA Run 44301172116 : job docker-compose (1) in scala_test Failed :fire:
```

## Verdict

Duplicate of 11562 (family BG): same test, same step, same docker compose warning, same config parse error at the
same config line. Real regression of splice#8216 in the compose files, deterministic: every docker-compose (1) run
on main fails until the override escapes the two substitutions (`$${...}`). Mechanism and fix proposal are in the
11562 packet (`evidence/11562-compose-admin-api-auth-override-unescaped-hocon-substitution.md`); nothing was fixed
here. Proposed issue action (not run, for the user on the host):
`close_duplicates.py 11562 11597 --comment 'Duplicate of 11562: same compose override regression of splice 8216, validator config parse error at additional-config.conf line 16.'`
