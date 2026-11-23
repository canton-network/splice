# 11666 - ciperiodic deploy_sv_reonboard deploy_cluster fails again after #8216 was re-landed; family BI expected, confirming check not run

Probable duplicate of 11638 (family BI), NOT confirmed. The run deployed splice c34a2a5b91, which contains the
re-land of splice#8216 (5eff61ca40, #8577, merged 2026-11-19 12:27 UTC) with the validator1 code of family BI
unchanged, and its deployment config sets `validator1.disableAuth: true` as every deploy_sv_reonboard run does. By
the code, validator1's validator-app cannot start (secret `splice-app-validator-participant-admin-api-auth` is never
installed), exactly as in 11638. The confirming check of family BI (the Kubernetes events of the cluster for the
run) was not run, and the output of the failed deploy step was not read. So no line from the failed run itself was
read, and the duplicate verdict is left open. 11638 was closed on 2026-11-19 09:16 UTC as fixed by the revert
(#8571); that revert was reverted three hours later, so the regression is back on main and the 2026-11-20 run
failed too (11678). No fix was implemented here: the fix branch of 11638 already exists (9eb5f3b841, not pushed)
and still merges cleanly into origin/main.

- Workflow deploy_sv_reonboard, job deploy_cluster, cluster ciperiodic, splice c34a2a5b91, run of 2026-11-19
  (deployment config committed 16:51:00Z, issue filed 18:10:59Z).
- Parent: 11638 (family BI), not confirmed.
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at splice c34a2a5b91); not involved.
- Component (expected): splice cluster deployment code (`cluster/pulumi/common-validator`).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. The run deploys the re-landed #8216 with the BI code unchanged

```
git log origin/main --format='%h %cI %s' -3 -- cluster/pulumi/common-validator
git merge-base --is-ancestor 5eff61ca40 c34a2a5b9 && echo "5eff61ca40 (#8577) is in c34a2a5b9"
git show c34a2a5b9:cluster/pulumi/common-validator/src/validator.ts | sed -n '141,143p;264,265p;267,269p'
```
```
5eff61ca40 2026-11-19T13:27:49+01:00 Revert "Revert "implement participant admin API auth support in clusters (#8216)"" (#8577)
e59381448e 2026-11-18T21:57:06Z Revert "implement participant admin API auth support in clusters (#8216)" (#8571)
8399d63908 2026-11-18T11:49:54+01:00 implement participant admin API auth support in clusters (#8216)
5eff61ca40 (#8577) is in c34a2a5b9
  const validatorSecrets: Secret[] = baseConfig.disableAuth
    ? []
    : await installValidatorSecrets(config.xns, config.auth0Client);
      participantClient: {
        adminApi: {
          disableAuth: config.participant?.disableParticipantAdminApiAuth ?? false,
        },
      },
```

The expected files at the run's splice commit show the broken combination for validator1 (no admin API secret,
participant client admin API auth on), as in 11638 section 2:

```
git ls-tree --name-only c34a2a5b9 cluster/expected/validator1/ | grep -c participant-admin-api-auth
git show c34a2a5b9:cluster/expected/validator1/validator1-validator-validator1.kubernetes_helm.sh_v3_Release.json | grep -n -A3 '"participantClient"'
```
```
0
80:        "participantClient": {
81-          "adminApi": {
82-            "disableAuth": false
83-          }
```

The deployment config of the run has `validator1.disableAuth: true`, set by the deploy job of deploy_sv_reonboard as
in 11638.

## 2. Tracker state: 11638 closed on the revert, failures continue

11638 is closed as completed; its closing comment of 2026-11-19 09:16:14Z names the validator1 missing admin API
secret and the revert (splice#8571) as the fix. The deploy_sv_reonboard issues since 2026-11-14 are 11547 (closed,
filed 2026-11-17 17:58:25Z), 11638 (closed, filed 2026-11-18 18:12:38Z), 11666 (open, filed 2026-11-19 18:10:59Z)
and 11678 (open, filed 2026-11-20 09:39:57Z). The re-land was merged three hours after 11638 was closed:

```
gh pr view 8577 -R canton-network/splice --json mergedAt --jq .mergedAt
```
```
2026-11-19T12:27:50Z
```
11678 (splice 6c71d08233) was not triaged here; its splice commit also contains 5eff61ca40.

## 3. Existing fix branch still applies

```
git log -1 --format='%h %cI %s' 9eb5f3b841
git merge-tree --write-tree origin/main 9eb5f3b841 >/dev/null && echo "merges cleanly into origin/main $(git rev-parse --short origin/main)"
git ls-remote --heads origin 'pperek/fix-11638*' | wc -l
git show origin/main:cluster/pulumi/common-validator/src/validator.ts | sed -n '267p'
```
```
9eb5f3b841 2026-11-18T19:43:50Z Disable participant admin API auth for validator1 when disableAuth is set [ci]
merges cleanly into origin/main 1e80ae1dea
0
          disableAuth: config.participant?.disableParticipantAdminApiAuth ?? false,
```

## Verdict

Open: probable duplicate of 11638 (family BI, parent closed as completed, not as a duplicate), not confirmed by the
family's check. If confirmed, hard broken since the re-land (two daily runs in a row, 11666 and 11678), real
regression in splice cluster deployment code; the fix is the unpushed branch
pperek/fix-11638-validator1-disable-auth-admin-api (9eb5f3b841), which needs a rebase onto current main and a rerun
of `make cluster/pulumi/update-expected` before a PR. No new fix branch, no sbt or Canton run (no Scala, Daml or
test source involved).

To confirm: look at the failure events of namespace validator1 on the cluster for the window of the run (2026-11-19
16:40-18:15Z). Expected for BI: the validator-app pod reports that the secret
`splice-app-validator-participant-admin-api-auth` was not found until about 18:10Z, and there is no istio sidecar
image pull failure with a 403 (BE).

Not verified: anything from the run itself (step output, cluster events). Whether another failure (BE, BK or a new
one) hit this run first is not excluded. Whether a deploy_sv_reonboard run on the reverted commit passed between
11638 and 11666 was not checked.
