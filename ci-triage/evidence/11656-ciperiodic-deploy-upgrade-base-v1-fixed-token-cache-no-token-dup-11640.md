# 11656 - ciperiodic deploy_upgrade deploy_cluster_for_upgrade: SV, scan and splitwell apps never start, ledger API auth secrets are created without a `token` key

DUPLICATE of 11640 (family BJ, parent, open, not itself closed as a duplicate). The next daily deploy_upgrade run
on ciperiodic after 11640, same base (splice 48f45bf33, release-line-0.10.2, without splice#8494), same cluster,
same five namespaces. The fixed-token caches `default/auth0-fixed-token-cache-<project>-<stack>` of
sv.sv-1, sv.sv-2, sv.sv-3, sv.sv-da-1 and splitwell are still the format v1 ones that main wrote on 2026-11-18
16:49:42-43 (no create or delete of any cache secret since). The 0.10.2 loader reads a v1 entry as an old one and
returns an undefined token, so the ledger API auth secrets are created without `token` and every SV app, scan app,
the splitwell app and the splitwell validator app stay in CreateContainerConfigError: 412 events 10:19:34-11:44:42
(end of the examined window; the issue was filed 11:36:51). Second failure in a row of a job that runs once a day:
hard broken until the release line reads v1 or main stops writing v1 under the old secret name.

- Workflow deploy_upgrade, job deploy_cluster_for_upgrade, cluster ciperiodic, base splice 48f45bf33
  (release-line-0.10.2), run of 2026-11-19.
- Parent: 11640 (family BJ).
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at splice 48f45bf33); not involved.
- Component: splice cluster deployment code (`cluster/pulumi/common/src/auth0`), cross-version compatibility between
  main and the release line deployed on the same cluster. Not Canton, not infra.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Base commit

The run deploys splice 48f45bf33 as its base. The base lacks splice#8494 (the v1 cache format), as family BJ
requires.

```
git merge-base --is-ancestor ba914d47e5 48f45bf33; echo "ba914d47e5 ancestor of 48f45bf33: exit $?"
git show 48f45bf33:nix/canton-sources.json | grep -i '"version"'
```
```
ba914d47e5 ancestor of 48f45bf33: exit 1
  "version": "3.7.3",
```

## 2. What the cluster events and the audit log of the run show

Family BJ, first confirming check: the failure events of the job window are, as in 11640, containers that cannot be
configured because the key `token` is missing in a ledger API auth secret, in the same five namespaces: splitwell
(`splice-app-splitwell-ledger-api-auth` and `splice-app-validator-ledger-api-auth`, 41 events each) and sv-1, sv-2,
sv-3 and sv-da-1 (`splice-app-sv-ledger-api-auth`, 82 to 84 events each). The first is at 10:19:34, the last at
11:44:42. The affected pods belong to the same ten deployments (same ReplicaSet hashes) as in 11640: sv-app and
scan-app in each of the four SV namespaces, splitwell-app and validator-app in splitwell. The only other failure in
the window is one unrelated image pull timeout of a security agent.

Family BJ, second confirming check: the audit log of the cache secrets in namespace default, from before the 11640
run to the end of this one, contains only the v1 rewrites by the main deploy of 11638 (splice 10de6749e0) on
2026-11-18 at 16:49:42-43, for exactly the five failing stacks. Nothing has replaced or removed them since, and the
cluster reset does not touch namespace default.

Not family BE: there is no istio image pull failure in the window.

## 3. No fix on either line yet

No commit touches the loader on the release line after the deployed base; on main, #8216 was reverted (2026-11-18
21:57 UTC) and re-landed (2026-11-19 12:27 UTC), which changes nothing here: the v1 format comes from #8494 and the
v1 caches of section 2 are already on the cluster.

```
git log --format='%h %ci %s' 48f45bf33..origin/release-line-0.10.2 -- cluster/pulumi/common/src/auth0
git log --format='%h %ci %s' --since=2026-11-17 origin/main -- cluster/pulumi/common/src/auth0
```
```
5eff61ca40 2026-11-19 13:27:49 +0100 Revert "Revert "implement participant admin API auth support in clusters (#8216)"" (#8577)
e59381448e 2026-11-18 21:57:06 +0000 Revert "implement participant admin API auth support in clusters (#8216)" (#8571)
8399d63908 2026-11-18 11:49:54 +0100 implement participant admin API auth support in clusters (#8216)
```
(the first command prints nothing)

## Verdict

Duplicate of 11640 (family BJ), confirmed by both confirming checks of the family (section 2) and the base ancestry
check (section 1). Real cross-version regression, not a flake; the mechanism in the code is in the 11640 packet
(section 2 there) and was not re-derived. Fix location: splice cluster deployment code, an owner decision
(release-line backport of a v1-tolerant loader, or main keeping the old format under the old secret name / writing
v1 under a new name); deleting the five cache secrets by hand would unblock one run until the next main deploy
rewrites them. No fix branch. NOT verified: the output of the failed deploy step, the secret contents, and events
after 11:45 (the last event is at the end of the examined window, so the job was still failing then).
