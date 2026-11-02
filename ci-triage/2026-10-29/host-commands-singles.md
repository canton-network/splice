# Host commands, 2026-10-29 singles

Run on the host (zsh). 10740 and 10742 stay open (new, first hits); the rest waits for logs.

```zsh
gh issue comment 10740 --repo DACH-NY/cn-test-failures --body "New: TokenStandardV2TransferIntegrationTest's forced ACS snapshot got HTTP 500 from sv1Scan: 'Request to .../api/scan/v0/state/acs/force resulted in an unexpected exception: Failed to acquire session-scoped advisory lock 1936747523' (16:31:36.373). 1936747523 is AdvisoryLockIds.acsSnapshotIndex; AcsSnapshotStore.indexSnapshotStakeholdersTable takes it with pg_try_advisory_lock. The snapshot triggers treat FailedToAcquireLockException as 'skip, retry later', but HttpScanHandler.forceAcsSnapshotNow (per-table snapshots) lets it escape as a 500 when AcsSnapshotIndexTrigger holds the lock at that moment. Fix: retry the lock failure in the force path (or retry 500s from forceAcsSnapshotNow in the test client)."
gh issue edit 10740 --repo DACH-NY/cn-test-failures --add-assignee OriolMunoz-da
gh issue comment 10742 --repo DACH-NY/cn-test-failures --body "New, teardown only: RollForwardLsuDRIntegrationTest passed (Tests: succeeded 1), the suite aborted in afterAll with ShutdownFailedException 'Unable to close SpliceEnvironment'. aliceValidator's ReceiveFaucetCouponTrigger had a SubmitAndWait in flight to its participant (127.0.0.1:5501) when the environment closed (18:32:35.8); RetryProvider waited its full 10 s ('Timeout 10 seconds expired, but readers are still active'), so 'Task closing node-aliceValidator did not complete within 10 seconds'. Not related to #8082: the DR part passed. First hit."
```

## Logs to fetch (needs CIRCLECI_TOKEN; results land in ~/src/splice/log/inbox-2026-10-29)

```zsh
CCI=https://circleci.com/api; P=project/github/DACH-NY/canton-network-internal; OUT=~/src/splice/log/inbox-2026-10-29; mkdir -p ${OUT}
cci_steps() { local job=$1 sname idx st url apath aurl; mkdir -p ${OUT}/job-${job}; curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v1.1/${P}/${job}" > ${OUT}/job-${job}/job.json; jq -r '.steps[] | .name as $n | .actions[] | [($n|gsub("[^A-Za-z0-9]+";"_")), .index, .status, (.output_url // "")] | @tsv' ${OUT}/job-${job}/job.json | while IFS=$'\t' read -r sname idx st url; do [ -n "${url}" ] && curl -sSL --compressed "${url}" | jq -r '.[].message' > "${OUT}/job-${job}/${sname}.${idx}.${st}.log"; done; curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/${P}/${job}/artifacts" > ${OUT}/job-${job}/artifacts.json; jq -r '.items[] | [.path, .url] | @tsv' ${OUT}/job-${job}/artifacts.json | grep -E '\.(log|txt|clog|json)(\.gz)?\b' | while IFS=$'\t' read -r apath aurl; do mkdir -p "${OUT}/job-${job}/artifacts/$(dirname "${apath}")"; curl -sSL -H "Circle-Token: ${CIRCLECI_TOKEN}" "${aurl}" -o "${OUT}/job-${job}/artifacts/${apath}"; done; }
cci_pipeline_jobs() { local pid wid; pid=$(curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/${P}/pipeline/$1" | jq -r .id); curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/pipeline/${pid}/workflow" | jq -r '.items[].id' | while read -r wid; do curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/workflow/${wid}/job" | jq -r '.items[] | [.job_number, .name, .status] | @tsv'; done > ${OUT}/pipeline-$1-jobs.tsv; cat ${OUT}/pipeline-$1-jobs.tsv; }
cci_pipeline_jobs 87538
cci_steps 526299
cci_steps 526074
cci_steps 526215
cci_steps 526332
cci_steps 526417
gh run view 40721834944 -R DACH-NY/canton-network-internal --log-failed > ${OUT}/gha-40721834944.log
ls -la ${OUT}
```
