# Host commands, 2026-11-02 fork C: infra (11106, 11137, 11117, 11118)

Packet: `infra-c.md`. Run on the host (zsh). State-guarded, safe to run twice.

## Issues

```zsh
[ "$(gh issue view 11117 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh issue view 11117 --repo DACH-NY/cn-test-failures --json title -q .title | grep -q '^GHA Run' && gh issue edit 11117 --repo DACH-NY/cn-test-failures --title "Devnet backup_status_cluster: stale backups at 06:00 on 10-31, first night after the Devnet LSU to serial 7"
[ "$(gh issue view 11117 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && ! gh issue view 11117 --repo DACH-NY/cn-test-failures --comments | grep -q 'First night after the Devnet LSU' && gh issue comment 11117 --repo DACH-NY/cn-test-failures --body "First night after the Devnet LSU config switch (cni #7836/#7837, 10-30 20:28: synchronizerMigration.active.id 6 -> 7). From the 00:00 run on, Devnet Backup backs up and the status check checks serial 7 (backup_nodes 7: sequencer-7, mediator-7, sequencer-bft-7, cn-apps, participant; status: Cloud SQL instances labelled migration_id=7 or unlabelled). At 06:00 some instance had no backup newer than 22:00, so the 00:00 backup had not produced one; the 04:00 backup (run 41166827191, #11118) failed at ~07:10. Not the 18:00 schedule gap of #10915 (at 06:00 the 00:00 backup is only 6 h old). No Devnet backup or status failure on 11-01/11-02, so it recovered. Open question: a slow first serial-7 backup (like #10745) or a serial-7 component that failed to back up; the stale list in this run and the failing step of 41166827191 will tell. Worth knowing before the MainNet LSU. @krzysztofczyz-da"
[ "$(gh issue view 11118 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && gh issue close 11118 --repo DACH-NY/cn-test-failures --reason "not planned" --comment "Duplicate of #11117: the 04:00 Devnet Backup of the first night after the Devnet LSU switch to serial 7, same incident as the 06:00 status failure."
[ "$(gh issue view 11106 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && ! gh issue view 11106 --repo DACH-NY/cn-test-failures --comments | grep -q 'Not #10844' && gh issue comment 11106 --repo DACH-NY/cn-test-failures --body "Not #10844: the deployed splice commit 76e1efc724 (#8035) contains #8125, and the 10-30 08:00 run passed. Intermittent (2 of the 7 sv_reonboard runs from 10-30 08:00 to 11-02 08:00 failed, the other is #11137). Cause needs the CircleCI step output of job 527403."
[ "$(gh issue view 11137 --repo DACH-NY/cn-test-failures --json state -q .state)" = OPEN ] && ! gh issue view 11137 --repo DACH-NY/cn-test-failures --comments | grep -q 'Not #10844' && gh issue comment 11137 --repo DACH-NY/cn-test-failures --body "Not #10844: the deployed splice commit e4825eacda (#8156) contains #8125; the 10-31 and 11-01 runs passed. Same workflow failed in #11106 (10-30 16:00). Cause needs the CircleCI step output of job 527732."
```

## Logs to fetch (needs CIRCLECI_TOKEN; results land in ~/src/splice/log/inbox-2026-11-02)

```zsh
CCI=https://circleci.com/api; P=project/github/DACH-NY/canton-network-internal; OUT=~/src/splice/log/inbox-2026-11-02; mkdir -p ${OUT}
cci_steps() { local job=$1 sname idx st url apath aurl; mkdir -p ${OUT}/job-${job}; curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v1.1/${P}/${job}" > ${OUT}/job-${job}/job.json; jq -r '.steps[] | .name as $n | .actions[] | [($n|gsub("[^A-Za-z0-9]+";"_")), .index, .status, (.output_url // "")] | @tsv' ${OUT}/job-${job}/job.json | while IFS=$'\t' read -r sname idx st url; do [ -n "${url}" ] && curl -sSL --compressed "${url}" | jq -r '.[].message' > "${OUT}/job-${job}/${sname}.${idx}.${st}.log"; done; curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/${P}/${job}/artifacts" > ${OUT}/job-${job}/artifacts.json; jq -r '.items[] | [.path, .url] | @tsv' ${OUT}/job-${job}/artifacts.json | grep -E '\.(log|txt|clog|json)(\.gz)?\b' | while IFS=$'\t' read -r apath aurl; do mkdir -p "${OUT}/job-${job}/artifacts/$(dirname "${apath}")"; curl -sSL -H "Circle-Token: ${CIRCLECI_TOKEN}" "${aurl}" -o "${OUT}/job-${job}/artifacts/${apath}"; done; }
cci_pipeline_jobs() { local pid wid; pid=$(curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/${P}/pipeline/$1" | jq -r .id); curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/pipeline/${pid}/workflow" | jq -r '.items[].id' | while read -r wid; do curl -sS -H "Circle-Token: ${CIRCLECI_TOKEN}" "${CCI}/v2/workflow/${wid}/job" | jq -r '.items[] | [.job_number, .name, .status] | @tsv'; done > ${OUT}/pipeline-$1-jobs.tsv; cat ${OUT}/pipeline-$1-jobs.tsv; }
cci_pipeline_jobs 87744
cci_pipeline_jobs 87814
cci_steps 527403
cci_steps 527732
gh run view 41175569098 -R DACH-NY/canton-network-internal --log-failed > ${OUT}/gha-41175569098.log
gh run view 41166827191 -R DACH-NY/canton-network-internal --log-failed > ${OUT}/gha-41166827191.log
ls -la ${OUT}
```

If `job-527403`/`job-527732` only hold a notify step, run `cci_steps` on the failed job numbers from the
`pipeline-*-jobs.tsv` files.
