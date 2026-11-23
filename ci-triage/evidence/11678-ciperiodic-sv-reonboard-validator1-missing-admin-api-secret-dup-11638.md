# 11678 - ciperiodic deploy_sv_reonboard deploy_cluster: validator1 validator-app waits on a participant admin API secret that is never installed

DUPLICATE of 11638 (family BI), confirmed by the family's check on the Kubernetes events of the run. The run
deployed splice 6c71d08233 (#8599), which contains the re-land of splice#8216 (5eff61ca40, #8577) and not yet the
fix splice#8617 (ae237a8ac9, merged 2026-11-20 11:43 UTC, two hours after this run). The deployment config of the
run sets `validator1.disableAuth: true`; the validator-app pod of namespace validator1 reports 41 times that the
secret `splice-app-validator-participant-admin-api-auth` was not found, from 08:24:03 to the end of the window,
while every other namespace is quiet from 08:31:20 on. No fix here: the fix is on main since #8617.

- Workflow deploy_sv_reonboard, job deploy_cluster, cluster ciperiodic, splice 6c71d08233, run of 2026-11-20
  (deployment config committed 08:17:02Z, issue filed 09:39:57Z).
- Parent: 11638 (family BI).
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at splice 6c71d08233); not involved.
- Component: splice cluster deployment code (`cluster/pulumi/common-validator`).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. What the cluster events of the run show

Family BI confirming check: the warning events of the cluster were examined from 08:00 to 09:45 (999 events, first
08:00:52, last 09:44:16). Namespace validator1 has 41 failure events, from 08:24:03 to 09:44:16, all on the
validator-app pod and all saying that the secret `splice-app-validator-participant-admin-api-auth` was not found.

Every other namespace is quiet long before the job ends. The last warnings are at 08:19:05 for docs, 08:20:00 for
fluent-bit, 08:21:25 for observability, 08:23:25 for sweet, 08:24:50 for splitwell, 08:26:28 for sv-1, 08:28:52 for
sv-da-1, 08:29:17 for gmp-system, 08:29:45 for sv-3, 08:31:20 for sv-2 and 08:36:09 for node events. The SV,
splitwell and infra namespaces only have startup warnings (FailedScheduling, ProvisioningFailed, Unhealthy) that end
by 08:31:20. cluster-ingress repeats one load balancer deletion warning from before the deploy (08:00:52) to after
it (09:40:52), unrelated to the deploy. validator1 is the only namespace with warnings up to the end of the window
(09:44:16).

Not family BE: no event in the window mentions a 403.

## 2. The run has the BI code and the BI configuration

The deployment config of the run has `validator1.disableAuth: true`. Its splice commit has the re-land of #8216 and
not the fix:

```
git merge-base --is-ancestor 5eff61ca40 6c71d08233 && echo "5eff61ca40 (#8577) is in 6c71d08233"
git merge-base --is-ancestor ae237a8ac9 6c71d08233 || echo "ae237a8ac9 (#8617) is NOT in 6c71d08233"
git show 6c71d08233:cluster/pulumi/common-validator/src/validator.ts | grep -n 'disableParticipantAdminApiAuth ?? false'
```
```
5eff61ca40 (#8577) is in 6c71d08233
ae237a8ac9 (#8617) is NOT in 6c71d08233
267:          disableAuth: config.participant?.disableParticipantAdminApiAuth ?? false,
```

## 3. The fix is on main since 2026-11-20 11:43 UTC

```
git log -1 --format='%h %cI %s' ae237a8ac9
git show --stat --format= ae237a8ac9 | tail -5
git show ae237a8ac9:cluster/pulumi/common-validator/src/validator.ts | sed -n '265p;267,270p'
```
```
ae237a8ac9 2026-11-20T11:43:42Z fix how validator common IaC handles disableAuth after the introduction of participant admin API auth (#8617)
 .../validator1-participant.kubernetes_helm.sh_v3_Release.json         | 2 +-
 ...validator1-validator-validator1.kubernetes_helm.sh_v3_Release.json | 2 +-
 cluster/pulumi/common-validator/src/participant.ts                    | 4 +++-
 cluster/pulumi/common-validator/src/validator.ts                      | 4 +++-
 4 files changed, 8 insertions(+), 4 deletions(-)
        adminApi: {
          disableAuth:
            (config.disableAuth ?? false) ||
            (config.participant?.disableParticipantAdminApiAuth ?? false),
        },
```

## 4. Tracker state

The deploy_sv_reonboard issues since 2026-11-14: 11547 (closed as duplicate, filed 2026-11-17 17:58:25Z), 11638
(closed as completed, filed 2026-11-18 18:12:38Z), 11666 (open, filed 2026-11-19 18:10:59Z) and 11678 (open, filed
2026-11-20 09:39:57Z).

## Verdict

Duplicate of 11638 (family BI; 11638 is closed as completed, not as a duplicate, and is the issue the family names).
Real regression in splice cluster deployment code, hard broken on deploy_sv_reonboard since the re-land of #8216
(11666 and 11678, two daily runs in a row), fixed on main by splice#8617 (ae237a8ac9), which makes
`config.disableAuth` also disable the participant client and participant admin API auth for the validator. The
local branch pperek/fix-11638-validator1-disable-auth-admin-api (9eb5f3b841, never pushed) is superseded by #8617.
No fix branch, no sbt or Canton run.

Not verified: the output of the failed deploy step (which step failed and with what message). The verdict rests on
the cluster events alone. That 11666 has the same events was not checked in this packet (its window is 2026-11-19
16:40-18:15Z, same check). Whether the first deploy_sv_reonboard run on a commit with #8617 passes is to be seen on
the next run (11547, family BE era, shows the job has other failure modes).
