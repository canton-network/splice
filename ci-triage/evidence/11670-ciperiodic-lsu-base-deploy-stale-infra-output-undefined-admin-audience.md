# 11670 - ciperiodic deploy_lsu_operator deploy_cluster_base_version: stacks read the infra auth0 output before the infra stack adds `participantAdminApi`, participants start with admin audience `undefined`, sv-runbook stack hits the operator retry limit

New (family BP), not a duplicate. Base deploy of splice dbb2a2c9d (main, contains the #8216 re-land #8577) through
the Pulumi operator on ciperiodic, whose infra stack output was still the one of the preceding release-line-0.10.2
deploy (11669), i.e. without `audiences.participantAdminApi`. The operator starts all stacks at once; the infra
stack adds the audience at 23:29:04.796 (2026-11-19 UTC), and every stack that read the output before that either
fails its Auth0 token request because the required audience parameter is undefined (sv-runbook twice, validator1,
validator-runbook) or deploys its participant with the literal `undefined` as admin API target audience (sv-1, sv-2,
sv-3, sv-da-1, splitwell, sv, validator, validator1). The SV and splitwell apps are refused by their own participant
for an audience mismatch (949 WARN 23:29:59-23:42:10) until the first update of their stack times out after 13 min
and the retry redeploys the participant with the right audience. validator1 therefore crash-loops on getting its BFT
scan connection and is ready only at 23:50:28. The sv-runbook stack waits for validator1 for about 3 min per update
(`getValidator1PartyId`); its updates 3 and 4 run out of retries at 23:32:32 and 23:46:16, and after the fourth
failure the operator (2.10.0, `maxUpdateFailures = 3`) stops retrying at 23:55:24.970 until its inputs change. The SV
app of the runbook SV is never installed, its scan app waits for a ledger API user nobody creates, and the job fails
(issue filed 00:08:43).

- Base splice: dbb2a2c9d (0.10.3-snapshot.20261119.4432.0.vdbb2a2c9), deployed on ciperiodic by workflow
  deploy_lsu_operator, job deploy_cluster_base_version.
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at splice dbb2a2c9d, and the version the participants report at
  start); not involved.
- Component: splice cluster deployment code (`cluster/pulumi`: `common/src/auth0/audiences.ts`, operator stack
  ordering, `sv-runbook/src/utils.ts`), cross-version transition between a release-line deploy and main on the same
  cluster. Not Canton, not infra.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Base commit

The base contains the re-land of #8216; the release line deployed before it on the cluster (11669, splice
b12a1318f) has no participant admin API audience in its infra stack.

```
git show dbb2a2c9d:nix/canton-sources.json | grep -m1 '"version"'
git merge-base --is-ancestor 5eff61ca40 dbb2a2c9d; echo "5eff61ca40 (#8577) ancestor of dbb2a2c9d: exit $?"
git grep -c participantAdminApi b12a1318f -- cluster/pulumi/infra/src/auth0.ts; echo "grep exit $?"
```
```
  "version": "3.7.4",
5eff61ca40 (#8577) ancestor of dbb2a2c9d: exit 0
grep exit 1
```

## 2. What is unhealthy at the end: the runbook SV has a scan app and no SV app

Warning events of the app pods in the job window (23:00-00:30): every app recovers by 23:51 except the scan app of
namespace sv, and there is no sv-app and no validator-app pod in namespace sv at all.

- SV apps and scan apps of sv-1, sv-2, sv-3 and sv-da-1: unready from 23:29-23:30 until about 23:45 (sv-app of sv-1
  until 23:40:12).
- splitwell validator app: unready 23:30:08-23:45:11. validator app of namespace validator: 23:32:15-23:47:16.
- validator1 validator app: unready 23:30:26-23:50:28.
- Validator apps of sv-1, sv-2, sv-3 and sv-da-1: unready for 75 to 82 s each between 23:45:34 and 23:51:19.
- scan app of namespace sv: unready from 23:31:59 to the end of the window (00:27:02), in restart back-off at
  00:22:23.

The runbook scan app gives up its initialization six times (23:39:22, 23:47:11, 23:55:23, 00:03:37, 00:12:01,
00:21:14) on the ledger API user of the runbook SV backend client, the user the SV app would create.

## 3. The sv-runbook stack: two audience failures, two validator1 waits, then the operator stops

Updates of the sv-runbook stack by the operator:

- Update 1 starts 23:28:14 and fails after 18 s, update 2 starts 23:28:45 and fails after 9 s. Both fail in the
  token request for the participant admin API audience of the runbook SV backend client
  (`cluster/pulumi/common/src/auth0/auth0.ts`, line 126), which the Auth0 client rejects because the required
  audience parameter is null or undefined.
- Update 3 starts 23:29:27. The validator1 wait runs out of retries at 23:32:32, the scan Helm release of namespace
  sv does not become available within its timeout, and the update fails at 23:42:09 after 12 min 42 s.
- Update 4 starts 23:43:13 (after the operator's 1 min back-off). The validator1 wait runs out of retries at
  23:46:16, the scan release times out again, and the update fails at 23:55:24 after 12 min 11 s.
- At 23:55:24.970 the operator records that the update retry limit is reached and stops retrying the stack. It is
  the only occurrence of that condition on the cluster between 2026-11-13 and 2026-11-20.

For comparison, the night before (11642, base e59381448e, the revert of #8216): the first update starts 23:31:44,
fails on the validator1 wait (23:35:08) and the scan release timeout and ends at 23:44:54 after 13 min 10 s; the
second starts 23:45:08 and succeeds in 6 min 5 s. On a normal night the first update fails on the validator1 wait
and the second succeeds.

The limit in the operator the cluster runs (`cluster/pulumi/operator/src/operator.ts` at dbb2a2c9d:
`operatorVersion = '2.10.0'`, since #8203 of 2026-11-03); the failure counter starts at 0 on the first failure, so
the fourth failure with unchanged inputs is the last attempt.

```
curl -sL https://raw.githubusercontent.com/pulumi/pulumi-kubernetes-operator/v2.10.0/operator/internal/controller/pulumi/stack_controller.go -o stack_controller.go
sed -n '1091p;1139,1141p;1291,1293p' stack_controller.go
```
```
const maxUpdateFailures = 3
	if last != nil && last.Generation == current.Generation && last.ReconcileRequest == current.ReconcileRequest {
		instance.Status.LastUpdate.Failures = last.Failures + 1
	}
		if stack.Status.LastUpdate.Failures >= maxUpdateFailures {
			// Retry limit reached with unchanged inputs; stop retrying.
			log.V(1).Info("Synced: update retry limit reached; not retrying until inputs change",
```

The validator1 wait is 20 retries, 1 s doubling to a cap of 10 s, about 175 s (splice dbb2a2c9d).

```
git show dbb2a2c9d:cluster/pulumi/sv-runbook/src/utils.ts | sed -n 14,18p
```
```
export async function getValidator1PartyId(): Promise<string> {
  return retry('getValidator1PartyId', 1000, 20, async () => {
    const validatorApiUrl = config.envFlag('SPLICE_DEPLOYMENT_SV_USE_INTERNAL_VALIDATOR_DNS')
      ? `http://validator-app.validator1:5003/api/validator/v0/validator-user`
      : `https://wallet.validator1.${CLUSTER_HOSTNAME}/api/validator/v0/validator-user`;
```

## 4. Where the undefined audience comes from: the infra stack adds it at 23:29:04, the other stacks started at 23:28

Update starts and ends of the relevant stacks in the operator:

- deployment stack: 23:24:15-23:25:25. canton-network stack: 23:28:20-23:28:46.
- sv-runbook starts 23:28:14, validator1 23:28:18, the sv-1 SV stack 23:28:22, validator-runbook 23:28:35.
- infra stack: update starts 23:28:34 and ends 23:29:05 (27 s).
- Audience errors: sv-runbook 23:28:29 and 23:28:54, validator1 23:28:32, validator-runbook 23:28:54, all before the
  infra update ends.
- validator1: first update ends 23:29:26 (1 min 9 s), second starts 23:29:40 and fails at 23:42:06 after
  12 min 27 s. validator-runbook: first update ends 23:29:37 (1 min 2 s), second starts 23:29:51.
- sv-1 SV stack: first update fails at 23:41:34 after 13 min 13 s, retried from 23:41:48.

The diff of the infra update adds the `participantAdminApi` key to the audiences in the stack output at
23:29:04.796 (eight added entries, among them sv-1 and sv-2), so the key was absent in the output the other stacks
read. The same update also touches the Auth0 resource servers `ParticipantAdminApiSv*`. It is the only infra
update in the operator logs since 12:00 that day.

The code reads the audience from that output without a check, in the token request and in the participant values
(splice dbb2a2c9d): `getNamespaceConfig` only throws when the whole namespace entry is missing.

```
git show dbb2a2c9d:cluster/pulumi/common/src/auth0/audiences.ts | sed -n 21,26p
git show dbb2a2c9d:cluster/pulumi/sv/src/participant.ts | sed -n 160,161p
git show dbb2a2c9d:cluster/pulumi/common/src/auth0/secrets.ts | grep -n 'audiences.participantAdminApi'
```
```
export function getParticipantAdminApiAudience(
  auth0Config: Auth0Config,
  namespace: string
): string {
  return getNamespaceConfig(auth0Config, namespace).audiences.participantAdminApi;
}
      OIDC_AUTHORITY_LEDGER_API_AUDIENCE: getLedgerApiAudience(auth0, xns.logicalName),
      OIDC_AUTHORITY_ADMIN_API_AUDIENCE: getParticipantAdminApiAudience(auth0, xns.logicalName),
329:  const audience = cfg.namespacedConfigs[namespace]!.audiences.participantAdminApi;
```

## 5. Participants started with the admin API target audience `undefined`

Admin API server config per participant start in the window: every first participant pod is configured with the
literal `undefined` as target audience; the stacks that are retried replace it with a pod that has the real
audience.

- validator1: first start 23:29:21, replaced 23:30:02. validator: 23:29:34, replaced 23:30:13.
- sv-2: 23:29:35, replaced 23:41:58. sv-da-1: 23:29:36, replaced 23:42:00. sv-1: 23:29:49, replaced 23:42:12.
  sv-3: 23:29:58, replaced 23:42:17. splitwell: 23:29:41, replaced 23:42:25.
- sv (the runbook SV participant, whose stack's first update succeeded): 23:29:21, never replaced, keeps
  `undefined`.

The participants refuse their own apps for 12 minutes. Audience mismatch warnings per namespace: splitwell 196
(23:30:09-23:42:10), sv-1 185 (23:30:12-23:41:58), sv-2 189 (23:30:00-23:41:46), sv-3 191 (23:30:08-23:42:03),
sv-da-1 188 (23:29:59-23:41:43).

On sv-1: the participant's auth interceptor rejects the token from 23:30:12 because its audience does not match the
target value; the SV app gets UNAUTHENTICATED on its participant id request 184 times (23:30:12-23:41:58) and fails
its initialization at 23:37:32. After the participant is replaced the SV app passes the participant id check at
23:42:35 and completes its initialization at 23:44:23.

## 6. validator1 is ready 20 minutes after its pod start, 4 minutes after sv-runbook's last wait ended

validator1's validator app starts at 23:30:21, fails its initialization seven times on the BFT scan connection
(first 23:31:17, last 23:44:37; no SV scan is up before 23:44:40) and then sits in the restart back-off; its eighth
start is at 23:50:17.

For comparison, on the two previous nights the SV apps and validator1 are ready 2 to 5 minutes after their pod
start.

## 7. Not family BJ

The missing-secret-key events (token key not found) in the window run from 23:00:48 to 23:11:32, before the deploy
starts (23:19:55): they are the pods the 11669 run left behind, removed by the reset. None after the deploy starts.

No newer commit on main touches the code involved (origin/main 7b71d35779, fetched 2026-11-20).

```
git log --format='%h %ci %s' dbb2a2c9d..origin/main -- cluster/pulumi/common/src/auth0 cluster/pulumi/common/src/operator cluster/pulumi/sv-runbook cluster/pulumi/infra/src/auth0.ts cluster/pulumi/sv/src/participant.ts cluster/pulumi/common-validator/src/participant.ts
```
```
```
(no output)

## Verdict

New, family BP; real, deterministic for this sequence of deploys, not a flake. Three things had to coincide:

1. The infra stack output on the cluster predates #8216 (left by the release-line-0.10.2 base deploy of 11669,
   which failed in family BJ before upgrading to main), and the operator runs the infra stack concurrently with the
   stacks that read its `auth0` output.
2. `audiences.participantAdminApi` is read without a check: token requests fail (two wasted sv-runbook updates),
   participant charts get the literal `undefined` as target audience and install fine (13 minutes of
   UNAUTHENTICATED until the stack update times out and is retried).
3. The sv-runbook stack normally needs one failed update for its validator1 wait (175 s), and operator 2.10.0 stops
   after the fourth failure.

Fix location: splice `cluster/pulumi`, an owner decision. Options, none written: (a) make the audience getters and
`participantAdminApiSecretContent` throw on a missing audience, so a stack never installs a participant with
`undefined` (fails fast and is retried once infra is updated; costs retries of the limit of 4); (b) let the
dependent stacks wait for the infra stack in the operator deployment, or have the deploy job reconcile infra
first; (c) let `getValidator1PartyId` wait longer than 175 s so the sv-runbook stack does not spend an update per
wait; (d) request a reconciliation of a stack that reached the retry limit from the job that waits for the stacks.
The condition ends by itself once the release line deployed on ciperiodic carries the audience (or once
deploy_upgrade passes again and leaves the infra stack on main), but (2) stays a trap for the next output key.
Still broken on the cluster after this job: the runbook SV participant with target audience `undefined` (section 5).

No fix branch, nothing changed outside this packet. NOT verified: the step output of the failed job (which step
failed and its message); that the stale infra output was written by the 11669 release deploy (inferred from the
added keys in the infra update and `git grep` on b12a1318f, the earlier infra update is not in the operator logs of
the day); why the SV stacks did not hit the audience error in their token request (not looked at).
The next nightly run (2026-11-20 23:20) will show the same if a release-line deploy precedes it again.
