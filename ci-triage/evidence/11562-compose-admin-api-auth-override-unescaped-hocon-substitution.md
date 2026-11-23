# 11562 - docker-compose (1): validator restart "with auth" fails, compose override blanks the HOCON substitutions of the admin API auth config (run 44289075848)

New, not a duplicate: real regression from the commit under test, splice#8216 ("implement participant admin API auth
support in clusters"), hard broken on main from 8399d63908 on. Post-merge CI on main 8399d63908, job
`docker-compose (1)`, DockerComposeValidatorFrontendIntegrationTest, 0 passed, 1 failed:
`Failed to start docker-compose validator (with auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)`.
#8216 makes `build-tools/splice-compose.sh start -a` pass `--enable-participant-admin-api-auth`, which adds the new
`cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml`. That file writes the HOCON substitutions
`${_admin_api_auth_service}` and `${_participant_client_admin_api_auth_config}` with a single `$`, so docker compose
interpolates them itself as (unset) environment variables and passes blank strings. The validator container gets
`...participant-client.admin-api.auth-config = ` with no value, the next line of `/app/additional-config.conf`
(`canton.validator-apps.validator_backend.storage {`) becomes the value, and the validator app exits at 11:32:10.760
with `CANNOT_PARSE_CONFIG_FILES`. The four earlier starts of the same test (no `-a`) do not load the override and
pass. The docker-compose job never ran on the PR (skipped on every PR run), so the post-merge run is the first run of
this path.

- Run: https://github.com/canton-network/splice/actions/runs/44289075848, main 8399d63908 ("implement participant
  admin API auth support in clusters (#8216)"), push, job 134750366700
  `ci / scala_test_docker_compose / docker-compose (1)`.
- Ref mapping: issue 11562 body names job docker-compose (1) of this run; it is the only failed job. Not inferred.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 8399d63908).
- Component: splice compose deployment files (cluster/compose/validator), not a test flake.
- Artifacts: `log/11562/job.log`, `log/11562/logs-docker-compose-1/`.

## 1. Failing assertion

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134750366700/logs > log/11562/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11562/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/11562/job.log | grep -a -m1 -A1 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //'
```
```
[info] - docker-compose based validator works *** FAILED ***
[info] Run completed in 16 minutes, 30 seconds.
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
[info] - docker-compose based validator works *** FAILED ***
[info]   Failed to start docker-compose validator (with auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)
```

## 2. Where in the test: the fifth start, the first one with `-a`

```
zcat log/11562/logs-docker-compose-1/canton_network_test.clog.gz | grep -a -E 'Running clue: (Restart the validator, with auth|Stop the validator)|Failed clue: Restart the validator' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat log/11562/logs-docker-compose-1/compose-validator.clog.gz | grep -a -E 'Starting Splice version|CANNOT_PARSE_CONFIG_FILES\(8,0\): Received an exception' | cut -c1-110
```
```
2026-11-18T11:29:46.836Z Running clue: Stop the validator (without wiping its data)",
2026-11-18T11:31:56.508Z Running clue: Stop the validator (without wiping its data)",
2026-11-18T11:31:58.508Z Running clue: Restart the validator, with auth",
2026-11-18T11:32:13.533Z Failed clue: Restart the validator, with auth",
{"@timestamp":"2026-11-18T11:19:08.771Z","message":"Starting Splice version 0.10.3-snapshot.20261118.1.0.v8399d639
{"@timestamp":"2026-11-18T11:23:52.275Z","message":"Starting Splice version 0.10.3-snapshot.20261118.1.0.v8399d639
{"@timestamp":"2026-11-18T11:26:05.295Z","message":"Starting Splice version 0.10.3-snapshot.20261118.1.0.v8399d639
{"@timestamp":"2026-11-18T11:29:59.201Z","message":"Starting Splice version 0.10.3-snapshot.20261118.1.0.v8399d639
{"@timestamp":"2026-11-18T11:32:10.147Z","message":"Starting Splice version 0.10.3-snapshot.20261118.1.0.v8399d639
{"@timestamp":"2026-11-18T11:32:10.754Z","message":"CANNOT_PARSE_CONFIG_FILES(8,0): Received an exception (full st
```

The test starts the compose validator five times; only the last start (`startFlags = Seq("-a", "-g", "-P", ...)`,
test line 454) uses auth.

## 3. docker compose warns that it blanks the two variables, the validator container is unhealthy 8 s later

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11562/job.log | grep -a -n -E 'variable is not set|is unhealthy|ERROR: Validator failed' | cut -c1-200
```
```
25278:time="2026-11-18T11:32:02Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
25279:time="2026-11-18T11:32:02Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
25334:time="2026-11-18T11:32:10Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
25335:time="2026-11-18T11:32:10Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
25367:container splice-validator-validator-1 is unhealthy
25368:ERROR: Validator failed to become ready
```

The two warnings appear only in this start (no hit before line 25278).

## 4. The validator app cannot parse its config

```
zcat log/11562/logs-docker-compose-1/compose-validator.clog.gz | grep -a 'T11:32:10.760Z' | cut -c1-330
```
```
{"@timestamp":"2026-11-18T11:32:10.760Z","message":"Failed to read config at startup: Error(List(com.typesafe.config.ConfigException$WrongType: /app/additional-config.conf: 16: Cannot concatenate object or list with a non-object-or-list, Unquoted(\"canton.validator-apps.validator_backend.storage\") and SimpleConfigObject({\"config\":{\"dataS
```

`cluster/images/common/entrypoint.sh` appends every `ADDITIONAL_CONFIG*` variable to `/app/additional-config.conf` in
name order (`for cfg in ${!ADDITIONAL_CONFIG@}`). `ADDITIONAL_CONFIG_ENABLE_ADMIN_API_AUTH` ends with
`...admin-api.auth-config = ` (blank), and the next variable, `ADDITIONAL_CONFIG_PERSISTENCE`
(`cluster/compose/validator/compose.yaml`), starts with `canton.validator-apps.validator_backend.storage {`: HOCON
reads that key and its object as the missing value, which is the concatenation in the error.

## 5. The override file and what docker compose makes of it

```
git show 8399d63908:cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml | grep -n '_admin_api_auth_service\|_participant_client_admin_api_auth_config'
git show 8399d63908:cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml > SCRATCH/override.yaml
docker compose -f SCRATCH/override.yaml config --no-consistency 2>&1 | grep -E '_admin_api_auth_service|_participant_client|auth-services|auth-config'
```
```
10:          canton.participants.participant.admin-api.auth-services=[${_admin_api_auth_service}]
21:          canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = ${_participant_client_admin_api_auth_config}
time="2026-11-18T12:04:32Z" level=warning msg="The \"_admin_api_auth_service\" variable is not set. Defaulting to a blank string."
time="2026-11-18T12:04:32Z" level=warning msg="The \"_participant_client_admin_api_auth_config\" variable is not set. Defaulting to a blank string."
          canton.participants.participant.admin-api.auth-services=[]
      ADDITIONAL_CONFIG_ENABLE_ADMIN_API_AUTH: "\n  canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = \"\"\n  canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = \n"
```

Both names are HOCON objects defined inside the images (`cluster/images/canton-participant/additional-config.conf`
line 35, `cluster/images/validator-app/app.conf` line 13), not environment variables. On the participant the blank
gives `auth-services=[]`, which parses: the participant came up with admin API auth silently disabled. So the
"enable" path has never run with admin API auth actually on.

With `$$` (the docker compose escape for a literal `$`):

```
docker compose -f cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml config --no-consistency 2>&1 | grep -E 'variable is not set.*_(admin|participant_client)|auth-services|auth-config ='
```
```
          canton.participants.participant.admin-api.auth-services=[$${_admin_api_auth_service}]
          canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = ""
          canton.validator-apps.validator_backend.participant-client.admin-api.auth-config = $${_participant_client_admin_api_auth_config}
```

(`config` prints the escape again; the container receives `${...}`.) No warning for the two names.

## 6. The path never ran before the merge; first run after it

```
for r in 43238285821 43442521459 44095734797 44286001175; do gh api --paginate "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=100" --jq '.jobs[]|select(.name|test("docker_compose"))|"\(.conclusion) \(.name)"'; done | sort | uniq -c
gh run list -R canton-network/splice --workflow "CI post-merge to main or release-line branches" --branch main -L 3 --json databaseId,headSha,conclusion --jq '.[]|"\(.databaseId) \(.headSha[0:10]) \(.conclusion)"'
```
```
   4 skipped ci / scala_test_docker_compose / ${{ inputs.test_name }} (${{ matrix.runner-index }})
   4 success ci / scala_test_docker_compose / Split the tests into parallel runs
44289075848 8399d63908 failure
44273112018 e74e9bf844 failure
44265197651 6bcd71db6c success
```

The four runs are the "CI on PRs" runs of the PR branch `mblaze-da/participant-admin-api-auth-in-clusters/393`
(d2960a7c04, 78cec36f6a, 46de358c0d, cac2715903). 8399d63908 is the tip of main at triage time, so there is no later
main run yet. 44273112018 (e74e9bf844) is before #8216 and failed in other jobs (11560, simtime).

## 7. Duplicate check

`known-families.md` has three compose entries: the Auth0 login `access_denied` (10896 / 10901), the tap row stall (AT,
10918) and ghcr pull timeouts (10133). None matches: confirming greps for them
(`grep -a 'access_denied' log/11562/job.log`, the tap assertion) have no hit, the test fails before any login.
`gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue docker-compose created:>=2026-11-17'`
returns only 11562.

## Verdict

New, real, deterministic: every `splice-compose.sh start -a` on main from 8399d63908 fails at validator start. Same
flag is set for the `-a` starts of the cluster preflight (`_start_validator` in `build-tools/splice-compose.sh` line
126), so DockerComposeValidatorPreflightIntegrationTest runs with auth are expected to fail the same way once a
deployment picks up this splice commit (not observed yet).

Fix location: splice, `cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml`, escape the two
substitutions as `$${...}` (2 lines).

Fix state: NOT committed, not verified. The 2-line edit is an uncommitted change in the worktree
`~/Projects/fix-11562` (branch `pperek/fix-11562-compose-admin-api-auth-escape`, at origin/main 8399d63908). Only the
`docker compose config` comparison of section 5 was run on it.

- `sbt -batch apps-app/Test/compile` in the worktree was stopped by the triager (exit 143) while compiling
  `apps/scan` main classes: free disk on the machine fell from 7.1 GB to 2.2 GB during the cold build. The build
  outputs were removed again (`git clean -fdX` in the worktree).
- The suite (DockerComposeValidatorFrontendIntegrationTest) was not run. It needs `make docker-build` on top of the
  compile (canton-participant, validator-app, wallet-web-ui, ans-web-ui images), which does not fit on the disk.
  Docker itself was running (server 29.4.1).

Not verified, and the reason the fix may be incomplete: with the escape, admin API auth is on in compose for the first
time. The validator then requests a client-credentials token for the audience
`https://participant_admin_api.example.com` (`_export_auth0_env_vars`, `build-tools/splice-compose.sh` line 80) from
the tenant canton-network-test.us.auth0.com as the "Docker-Compose Validator App", and the participant accepts only
`<client id>@clients` with that audience. Whether that API and a client grant for the app exist in the test tenant is
not known (the pulumi code of #8216 creates per-namespace audiences
`https://participant_admin_api.<namespace>.<cluster>.canton.network` for the cluster tenants, nothing for
`example.com`). If they are missing, the test fails one step later, at the validator's token request. The author of
#8216 (mblaze-da) is the person to confirm; alternative until then: drop the
`extra_flags+=("--enable-participant-admin-api-auth")` line in `_start_validator` (or revert the compose part of
#8216).
