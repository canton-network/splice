# 11640 - ciperiodic deploy_upgrade deploy_cluster_for_upgrade: SV, scan and splitwell apps never start, ledger API auth secrets are created without a `token` key

New, not a duplicate (new family BJ). Real cross-version regression from splice#8494 (ba914d47e5, 2026-11-17)
and splice#8216 (8399d63908, merged 2026-11-18 10:49 UTC), both by the same author. #8494 changed the fixed-token
cache that the pulumi stacks keep in `default/auth0-fixed-token-cache-<project>-<stack>` to a format v1 (a `version`
key, and per client `{ledgerApi: {...}, participantAdminApi: {...}}` instead of `{accessToken, expiry}`), under the
SAME secret name. #8216 adds participant admin API tokens, so the first main deploy with it renews and rewrites the
cache of every stack that has clients. On ciperiodic that was the deploy_sv_reonboard run of 11638 (splice
10de6749e0) at 16:49:42-43, for the stacks sv.sv-1, sv.sv-2, sv.sv-3, sv.sv-da-1 and splitwell. This run deploys
the base release first (splice 48f45bf33, release-line-0.10.2, which has neither PR). Its loader reads each v1
entry as an old one: `new Date(undefined)` is Invalid Date, the expiry check is false, and it returns
`cachedSecret.accessToken`, which is undefined. The ledger API auth secrets of exactly those five namespaces are
created without a `token` key, and every SV app, scan app, the splitwell app and the splitwell validator app stay in
CreateContainerConfigError from 18:32:56 to 19:53:21 (409 events). The issue was filed 19:51:24. Hard broken: every
day the main deploys on ciperiodic rewrite these caches as v1 before the next base deploy, so every deploy_upgrade
run fails until the release line reads v1 or main stops writing v1 under the old name.

- Workflow deploy_upgrade, job deploy_cluster_for_upgrade, cluster ciperiodic, base splice 48f45bf33
  (release-line-0.10.2).
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at splice 48f45bf33); not involved.
- Component: splice cluster deployment code (`cluster/pulumi/common/src/auth0`), cross-version compatibility between
  main and the release line deployed on the same cluster. Not Canton, not infra.
- Parent: none; 11640 is the parent of family BJ. Related, different mechanism: 11638 (BI, same PR #8216, the
  deploy_sv_reonboard run whose cache writes break this run).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. What the cluster events and the audit log of the run show

Not family BE (11551 was the same job one day earlier): there is no image pull failure on the cluster from 18:15
on; the istio sidecar image is already present on the node for every pod.

The failure events of the job window are all of one kind: a container cannot be configured because the key `token`
is missing in a ledger API auth secret. They are in five namespaces: splitwell (`splice-app-splitwell-ledger-api-auth`
and `splice-app-validator-ledger-api-auth`, 41 events each) and sv-1, sv-2, sv-3 and sv-da-1
(`splice-app-sv-ledger-api-auth`, 81 or 82 events each). The first is at 18:32:56, the last at 19:53:21. The
affected pods are the ten app pods that read a ledger API token: sv-app and scan-app in each of the four SV
namespaces, and splitwell-app and validator-app in splitwell. They run the 0.10.2 images. The only other failure
events are two for validator1, the tail of 11638 (the validator-app pod of the earlier run, last event 18:20:29).

The validator1 validator-app of this run starts but crash-loops from 18:49:26 because its initialization times out
(18:53:52): a consequence, no SV or scan app is running. Its namespace has no token error, because its stack cache
was not rewritten.

Who wrote the caches: the audit log of the cache secrets in namespace default since the previous day shows that on
2026-11-18 at 16:49:42-43 the caches of exactly the five failing stacks (sv.sv-1, sv.sv-2, sv.sv-3, sv.sv-da-1 and
splitwell) were each rewritten by a create that fails with a conflict, a delete and a create. That is the persist
path of main's `persistFixedTokensCache`, during the deploy_sv_reonboard run of 11638 (splice 10de6749e0, contains
#8216). validator1 was deployed with disableAuth there (family BI) and gets no rewrite; the sv-canton stacks have no
clients that need a participant admin token. All earlier writes (2026-11-17 and the night to 2026-11-18) are plain
creates of other stacks' caches.

The cluster reset does not touch namespace default, so the caches survive into this run. Between 2026-11-16 and
18:30 on 11-18 the missing-`token` error never occurred on this cluster (yesterday's deploy_upgrade 11551 ran before
#8216 had rewritten the sv caches).

## 2. Mechanism in the code: v1 cache under the old name, read by the 0.10.2 loader

Ancestry: the base commit has neither PR, the main commit of 11638 has both.

```
git log -1 --format='%h %an %ad %s' --date=iso-strict ba914d47e5; git log -1 --format='%h %an %ad %s' --date=iso-strict 8399d63908
git merge-base --is-ancestor ba914d47e5 48f45bf33 || echo "8494 not in 48f45bf33"
git merge-base --is-ancestor 8399d63908 10de6749e0 && echo "8216 in 10de6749e0"
```
(author name transliterated to ASCII)
```
ba914d47e5 Mateusz Blazejewski 2026-11-17T12:50:03+01:00 refactor auth0 related IaC in preparation for participant admin API changes (#8494)
8399d63908 Mateusz Blazejewski 2026-11-18T11:49:54+01:00 implement participant admin API auth support in clusters (#8216)
8494 not in 48f45bf33
8216 in 10de6749e0
```

Main (`cluster/pulumi/common/src/auth0/fixedTokensCache.ts` since #8494) writes the per-client value
`{ledgerApi?: {accessToken, expiry}, participantAdminApi?: {...}}` plus `version: 1`, under
`auth0-fixed-token-cache-${pulumi.getProject()}-${pulumi.getStack()}` (the name the release line also uses:
`${this.cfg.fixedTokenCacheName}-${pulumi.getProject()}-${pulumi.getStack()}`). Main's loader understands both v0 and
v1; the release line's loader understands only v0:

```
git show 48f45bf33:cluster/pulumi/common/src/auth0/auth0.ts | grep -n 'cacheMap\[clientId\]\|cachedSecretExpiry\|return cachedSecret.accessToken'
```
```
122:        cacheMap[clientId] = JSON.parse(Buffer.from(data[clientId], 'base64').toString('ascii'));
212:        const cachedSecretExpiry = new Date(cachedSecret.expiry);
213:        if (addTimeSeconds(now, REQUIRED_TOKEN_LIFETIME) > cachedSecretExpiry) {
217:          return cachedSecret.accessToken;
```

What that code does with a v1 entry (same expressions, Node):

```
node -e '
const v1 = {ledgerApi:{accessToken:"t1",expiry:"2026-12-18T00:00:00.000Z"},participantAdminApi:{accessToken:"t2",expiry:"2026-12-18T00:00:00.000Z"}};
const cachedSecret = JSON.parse(JSON.stringify(v1));
const exp = new Date(cachedSecret.expiry);
const now = new Date(); now.setSeconds(now.getSeconds()+8*86400);
console.log("expiry:", String(exp), "| expired?", now > exp, "| returned token:", cachedSecret.accessToken);
console.log("stringData keys:", Object.keys(JSON.parse(JSON.stringify({audience:"a", token: cachedSecret.accessToken, "ledger-api-user":"u"}))));'
```
```
expiry: Invalid Date | expired? false | returned token: undefined
stringData keys: [ 'audience', 'ledger-api-user' ]
```

`ledgerApiSecretContent` (release line, `secrets.ts`, `if (fixedTokens())` branch; ciperiodic deploys with fixed
tokens) returns `{audience, token: accessToken, 'ledger-api-user'}`; with an undefined token the Secret is created
without the `token` key (inferred: pulumi drops undefined inputs as JSON does; the secret itself was not read). The
chart (`splice-util-lib.auth0-env-vars` with `fixedTokens: true`) references `secretKeyRef key: token, optional:
false`, which is exactly the container configuration error of section 1. The `version` entry parses as the number 1
and is never looked up.

## Verdict

New family BJ, parent 11640. Real regression in splice cluster deployment code (cache format change of #8494, made
live for the SV and splitwell stacks by #8216), not a flake, not Canton, not infra. Hard broken for ciperiodic
deploy_upgrade: any main deploy with #8216 on the cluster leaves v1 caches that the next base (release) deploy
misreads. Same exposure for any fixed-token cluster that deploys a release without #8494 after a main or 0.11
deploy (rollback, or a cluster shared between workflows).

Fix options (production IaC, left to the owner of #8494/#8216; none written):
1. Release line: backport a v1-tolerant loader to release-line-0.10.2 (skip `version`, take `ledgerApi` when present);
   the base deploy picks up the release-line head the next time its splice commit is updated. About 10 lines, fixes
   this workflow only for releases that carry it.
2. Main: keep the old secret name in v0 format (ledger API tokens only) for older releases and store v1 under a new
   name (e.g. `auth0-fixed-token-cache-v1-...`), loading the new name first and falling back to the old. Fixes all
   older releases and self-heals ciperiodic on the next main deploy; 30-40 lines plus `fixedTokensCache.test.ts`.
3. Interim: delete the fixed-token cache secrets of the four sv stacks and of the splitwell stack in namespace
   default before the base deploy (operator action; recurs after the next main deploy).

Not verified: the output of the failed deploy step (which pulumi operations it reports as failed is inferred from
the cluster events) and the contents of the created secrets and caches (not read).
