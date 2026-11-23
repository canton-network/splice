# 11638 - ciperiodic deploy_sv_reonboard deploy_cluster: validator1 (disableAuth) validator-app never starts, secret splice-app-validator-participant-admin-api-auth not found

New, not a duplicate (new family BI). Real regression from splice#8216 (8399d63908, "implement participant admin
API auth support in clusters", merged 2026-11-18 10:49 UTC), first deployed to ciperiodic by this run (splice
10de6749e0). deploy_sv_reonboard is the only workflow that deploys validator1 with `validator1.disableAuth=true`.
With disableAuth, `installValidatorApp` skips `installValidatorSecrets`, so namespace validator1 gets no
`splice-app-validator-participant-admin-api-auth` secret, but #8216 renders the validator chart with
`participantClient.adminApi.disableAuth: false`, whose env vars are `secretKeyRef`s to that secret. The
validator-app pod stays in CreateContainerConfigError from 16:55:19 to 18:10:20 (40 events saying that this secret
was not found), the issue was filed 18:12:38. Every other namespace is free of warnings from 17:04:28 on. Hard
broken: every sv_reonboard run fails until fixed.

- Workflow deploy_sv_reonboard, job deploy_cluster, cluster ciperiodic, splice 10de6749e0.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at splice 10de6749e0); not
  involved.
- Component: splice cluster deployment code (`cluster/pulumi/common-validator`), not Canton, not infra.
- Parent: none; 11638 is the parent of family BI.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. What the cluster events of the run show

Not family BE: in the job window there is no istio sidecar image pull failure with a 403. The one sidecar pull
failure is a deadline timeout, and the namespace of that pod has no warning after 17:04.

Only validator1 is still unhealthy at the end of the job. The warning events of every other namespace (docs,
fluent-bit, gmp-system, observability, splitwell, sv-1, sv-2, sv-3, sv-da-1 and sweet) settle by 17:04:28.
The exceptions are validator1 (until 18:10:20) and cluster-ingress, which repeats a load balancer deletion warning
every 5 minutes since before the job started at 16:40:52, unrelated.

The validator1 validator-app cannot start because its secret does not exist: all 40 failure events of namespace
validator1, from 16:55:19 to 18:10:20, belong to the one validator-app pod and all say that the secret
`splice-app-validator-participant-admin-api-auth` was not found.

## 2. Why the secret is missing: validator1 runs with disableAuth on this workflow only

The deployment config of this run has `validator1.disableAuth: true`. The deploy job of deploy_sv_reonboard sets
it (the same was true for 11547); no other workflow does.

In splice at 10de6749e0, `installValidatorApp` installs no secrets when disableAuth is set, but #8216 renders the
participant client admin API auth from the separate flag only:

```
git show 10de6749e0:cluster/pulumi/common-validator/src/validator.ts | sed -n '141,143p;264,265p;267,269p'
git merge-base --is-ancestor 8399d63908 10de6749e0 && echo "8399d63908 (#8216) is in 10de6749e0"
```
```
  const validatorSecrets: Secret[] = baseConfig.disableAuth
    ? []
    : await installValidatorSecrets(config.xns, config.auth0Client);
      participantClient: {
        adminApi: {
          disableAuth: config.participant?.disableParticipantAdminApiAuth ?? false,
        },
      },
8399d63908 (#8216) is in 10de6749e0
```
The chart then reads the admin API auth env from `splice-app-validator-participant-admin-api-auth`
(`cluster/helm/splice-util-lib/templates/_helpers.tpl`, `splice-util-lib.auth0-participant-admin-api-env-vars`,
`optional false`). The same applies to the validator1 participant (`installParticipant`, `adminApi.disableAuth` from
the separate flag only), which would enable admin API auth on a participant whose only admin client has no token.

The PR CI already rendered the broken combination: the mock deployment config has `validator1.disableAuth: true`,
and the expected files on origin/main have no admin API secret for validator1 but `disableAuth: false` for the
participant client:

```
git ls-tree --name-only origin/main cluster/expected/validator1/ | grep -c participant-admin-api-auth
git show origin/main:cluster/expected/validator1/validator1-validator-validator1.kubernetes_helm.sh_v3_Release.json | grep -n -A3 '"participantClient"'
```
```
0
80:        "participantClient": {
81-          "adminApi": {
82-            "disableAuth": false
83-          }
```

## 3. Fix (splice, branch pperek/fix-11638-validator1-disable-auth-admin-api)

Commit 9eb5f3b841 "Disable participant admin API auth for validator1 when disableAuth is set [ci]" off origin/main
53e7efb8e2: `disableAuth || disableParticipantAdminApiAuth` for `participantClient.adminApi.disableAuth`
(common-validator validator.ts) and the participant's `adminApi.disableAuth` (common-validator participant.ts).
`disableAuth` is only ever true for validator1 (`config.ts`: "currently ignored everywhere except for validator1"),
so only validator1 changes; this also matches the TODO in the charts ("change this condition to .Values.disableAuth").

Verification run on the fix branch:

```
direnv exec . make cluster/pulumi/update-expected     # exit 0; only the two validator1 Release files change
(cd cluster/pulumi/common-validator && npm run check) # prettier, eslint, tsc: exit 0
(cd cluster/pulumi/validator1 && npm run type:check)  # exit 0
direnv exec . make cluster/pulumi/validator1/test     # exit 0
helm template x cluster/helm/splice-validator --show-only templates/validator.yaml -f <validator1 expected values> ...
```
Excerpt of the grep `participant-admin-api-auth|ADMIN_API_AUTH|auth-config` over the render:
```
fixed values:
559:        - name: ADDITIONAL_CONFIG_DISABLE_PARTICIPANT_ADMIN_API_AUTH
with --set participantClient.adminApi.disableAuth=false (origin/main values):
563:              name: splice-app-validator-participant-admin-api-auth   (4 secretKeyRefs)
```
The participant chart with the new values renders `ADDITIONAL_CONFIG_DISABLE_ADMIN_API_AUTH`
(`admin-api.auth-services=[]`). The pre-commit hook `run pulumi tests and check expected files` passed on commit.
No sbt or Canton run: no Scala, Daml or test source is affected.

## Verdict

New family BI, parent 11638. Real regression, hard broken (deploy_sv_reonboard runs daily, the first run after
#8216 failed, and the next one will too). Fix location: splice cluster deployment code; fix branch
pperek/fix-11638-validator1-disable-auth-admin-api (9eb5f3b841), not pushed. Issue action proposed, not run:
Status Triaged, comment with the cause and the fix branch; cc the #8216 author.

Not verified: the output of the failed deploy step was not read, so the failed pulumi operation is inferred from the
cluster events (validator1's validator-app is the only pod not starting); the fix is not deployed to a cluster, only
rendered (pulumi dump-config and helm template). Whether validator1 with participant admin API auth off is the
intended no-auth test setup, versus keeping admin API auth on and installing only the admin API secret, is the
owner's call.
