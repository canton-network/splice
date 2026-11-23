# 11680 - preflight_check_docker: compose image pull fails, Artifact Registry token endpoint 502

New, not a duplicate; infra one-off (family J). Deploy cimain run (splice 1e80ae1dea, snapshot
0.10.4-snapshot.20261120.4434.0.v1e80ae1d), job Preflight check (docker).
DockerComposeValidatorPreflightIntegrationTest failed 22 s into the run at its first step,
`build-tools/splice-compose.sh start` (line 26 of the suite): `docker compose up` started seven image pulls at
11:10:11.69 UTC on 2026-11-20 and 3.2 s later the pull of `canton-participant` got a 502 Bad Gateway from the token
endpoint of Artifact Registry (us-central1). Compose interrupted the other three Artifact Registry pulls
(validator-app, wallet-web-ui, ans-web-ui) and exited; nothing retries the pull or the start. No container was
started, so the cluster and the validator are not involved. Tests: succeeded 0, failed 1.

- Splice: 1e80ae1dea.
- Runtime canton: not involved (the failure precedes any container start).
- Component: infra (Artifact Registry us-central1, transient) hitting an un-retried `docker compose up` in
  `cluster/compose/validator/start.sh`.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

A second failed job of the same run (dependencies scan) has no ref and is not looked at here.

## 1. Why the start failed (from the job output)

The onboarding secret was fetched from the cluster's SV at 11:10:09 (so VPN and cluster were reachable), then
compose pulled. The 502 came on a layer download: the manifest of `canton-participant` had already been resolved
with a valid token (layers were downloading, one of them complete at 11:10:14.79), and the request for a further
token was answered 502 at 11:10:14.90. It is one response, reported twice (once on the image line, once as the
daemon error).

## 2. Nothing retries

The test runs the start script once and fails on a non-zero exit; the script ends in one plain `docker compose up`.

```
git -C ~/Projects/splice show origin/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/runbook/DockerComposeValidatorPreflightIntegrationTest.scala | sed -n 22,27p
git -C ~/Projects/splice show origin/main:cluster/compose/validator/start.sh | sed -n 398p
```
```
    val partyHint = "da-composeValidator-1"
    val ret =
      Seq("build-tools/splice-compose.sh", "start", "-d", "-n", "onvpn", "-w", "-p", partyHint).!
    if (ret != 0) {
      fail("Failed to start docker-compose validator")
    }
docker compose -f "${script_dir}/compose.yaml" "${extra_compose_files[@]}" up -d "${extra_args[@]}"
```

## 3. One-off: neighbouring runs of the job, tracker, Google Cloud status

The same job passed in the two Deploy cimain runs before this one (2026-11-19 23:06 and 2026-11-20 08:22 UTC).

No open issue with this signature. The only other `preflight_check_docker` issue created since 2026-11-06, 11561,
is a different shape (tapAmulets wait, family AT, closed as duplicate of 10918); its confirming clue (failure in
`tapAmulets`) does not apply here, the test never reached the browser.

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "failed to fetch oauth token"' --jq '.items[]|"\(.number) \(.state) \(.state_reason) \(.created_at[0:10]) \(.title)"'
```
```
6614 closed duplicate 2025-12-05 docker build failed to fetch oauth token
6599 closed completed 2025-12-05 Artifactory 401: ERROR: failed to build: failed to solve: failed to fetch oauth token 
```

6599 and 6614 are Artifactory 401s during `docker build` from 2025-12, not this registry and not this status.
The Google Cloud status feed lists no incident starting on or after 2026-11-18: the command prints only the
number of incidents in the feed and the newest start time.

```
curl -sS https://status.cloud.google.com/incidents.json | python3 -I -c "
import json,sys
d=json.load(sys.stdin); print(len(d), max(i.get('begin','') for i in d))
for i in d:
    if i.get('begin','')[:10]>='2026-11-18': print(i.get('begin'),i.get('end'),i.get('service_name'))"
```
```
8 2026-10-20T06:00:00+00:00
```

## Verdict

Not a duplicate: no family in `known-families.md` has this signature with a parent issue; it is filed under family
J (infra, no code change) next to 10133, 10150 and 11671 as another single registry answer that fails a job
because the pull has no retry. Flake, cause outside splice (Artifact Registry us-central1 token endpoint, one 502).
Action: rerun the job.

Possible hardening, not written: retry the pull in `cluster/compose/validator/start.sh` (for example
`docker compose ... pull` in a short retry loop before `up`), or retry the start in the test. Left to the owner
because `start.sh` is the script shipped to validator operators, and the suite only runs against a deployed
cluster through the `onvpn` docker network, so a change cannot be verified locally.

Not verified: the registry side (no access to Artifact Registry logs); whether the two later runs of 2026-11-20
passed this job, they had not reached it at triage time.
