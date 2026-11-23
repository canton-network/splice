# 11669 - ciperiodic deploy_upgrade deploy_cluster_for_upgrade: SV, scan and splitwell apps never start, ledger API auth secrets are created without a `token` key

DUPLICATE of 11640 (family BJ, parent, open, not itself closed as a duplicate). A second deploy_upgrade run on
ciperiodic on 2026-11-19 (after 11656 in the morning), base splice b12a1318f (release-line-0.10.2, three backports
after the 48f45bf33 of 11640 and 11656, still without splice#8494), same cluster, same five namespaces. The
fixed-token caches `default/auth0-fixed-token-cache-<project>-<stack>` of sv.sv-1, sv.sv-2, sv.sv-3,
sv.sv-da-1 and splitwell are still the format v1 ones that main wrote on 2026-11-18 16:49:42-43 (no create or delete
of any cache secret since). The 0.10.2 loader reads a v1 entry as an old one and returns an undefined token, so the
ledger API auth secrets are created without `token` and every SV app, scan app, the splitwell app and the splitwell
validator app stay in CreateContainerConfigError: 429 events 18:25:46-19:56:36 (the issue was filed 19:43:47; the
pods stay until the next reset). Third failure in a row of this job: hard broken until the release line reads v1 or
main stops writing v1 under the old secret name.

- Workflow deploy_upgrade, job deploy_cluster_for_upgrade, cluster ciperiodic, base splice b12a1318f
  (release-line-0.10.2), second run of 2026-11-19.
- Parent: 11640 (family BJ).
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at splice b12a1318f); not involved.
- Component: splice cluster deployment code (`cluster/pulumi/common/src/auth0`), cross-version compatibility between
  main and the release line deployed on the same cluster. Not Canton, not infra.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Base commit

The run deploys splice b12a1318f as its base. The base lacks splice#8494 (the v1 cache format), as family BJ
requires; it is three backports past the base of 11640 and 11656, none of them touching the loader (section 3).

```
git merge-base --is-ancestor ba914d47e5 b12a1318f; echo "ba914d47e5 ancestor of b12a1318f: exit $?"
git show b12a1318f:nix/canton-sources.json | grep -i '"version"'
git log --format='%h %ci %s' 48f45bf33..b12a1318f
```
```
ba914d47e5 ancestor of b12a1318f: exit 1
  "version": "3.7.3",
b12a1318f6 2026-11-19 18:09:03 +0000 Backport PR #8521 to release-line-0.10.2 (#8593)
76246c657b 2026-11-19 13:03:19 +0000 Backport PR #8524 to release-line-0.10.2 (#8586)
cc14d2c66a 2026-11-19 12:43:37 +0000 Backport PR #8581 to release-line-0.10.2 (#8583)
```

## 2. What the cluster events and the audit log of the run show

Family BJ, first confirming check: the failure events of the job window are, as in 11640 and 11656, containers that
cannot be configured because the key `token` is missing in a ledger API auth secret, in the same five namespaces:
splitwell (`splice-app-splitwell-ledger-api-auth` and `splice-app-validator-ledger-api-auth`, 43 events each) and
sv-1, sv-2, sv-3 and sv-da-1 (`splice-app-sv-ledger-api-auth`, 85 or 86 events each), 429 events in total. The first
is at 18:25:46, the last at 19:56:36. The affected pods belong to the same ten deployments (same ReplicaSet hashes)
as in 11640 and 11656: sv-app and scan-app in each of the four SV namespaces, splitwell-app and validator-app in
splitwell.

The window also has six validator1 events about the missing participant admin API auth secret. They are not part of
this run: they run from 17:48:53 to 18:13:47, ending 10 minutes before this run's deployment config was committed
(18:23:57), and are the tail of the preceding main deploy on the cluster (deploy_sv_reonboard, 11666, family BI).

Family BJ, second confirming check: the audit log of the cache secrets in namespace default, from before the 11640
run to the end of this one, contains only the v1 rewrites by the main deploy of 11638 (splice 10de6749e0) on
2026-11-18 at 16:49:42-43, for exactly the five failing stacks. Nothing has replaced or removed them since (the main
deploy of 11666 in between did not write them either), and the cluster reset does not touch namespace default.

Not family BE: there is no istio image pull failure in the window.

## 3. No fix on either line yet

No commit touches the loader on the release line after the base of 11640 (so neither in this run's base nor at the
tip); on main nothing after the re-land of #8216.

```
git log --format='%h %ci %s' 48f45bf33..origin/release-line-0.10.2 -- cluster/pulumi/common/src/auth0
git log --format='%h %ci %s' --since=2026-11-17 origin/main -- cluster/pulumi/common/src/auth0
```
```
5eff61ca40 2026-11-19 13:27:49 +0100 Revert "Revert "implement participant admin API auth support in clusters (#8216)"" (#8577)
e59381448e 2026-11-18 21:57:06 +0000 Revert "implement participant admin API auth support in clusters (#8216)" (#8571)
8399d63908 2026-11-18 11:49:54 +0100 implement participant admin API auth support in clusters (#8216)
ba914d47e5 2026-11-17 12:50:03 +0100 refactor auth0 related IaC in preparation for participant admin API changes (#8494)
```
(the first command prints nothing; origin/release-line-0.10.2 and origin/main fetched 2026-11-20)

## Verdict

Duplicate of 11640 (family BJ), confirmed by both confirming checks of the family (section 2) and the base ancestry
check (section 1). Real cross-version regression, not a flake; the mechanism in the code is in the 11640 packet
(section 2 there) and was not re-derived. Fix location: splice cluster deployment code, an owner decision
(release-line backport of a v1-tolerant loader, or main keeping the old format under the old secret name / writing
v1 under a new name); deleting the five cache secrets by hand would unblock one run until the next main deploy
rewrites them. No fix branch, nothing changed outside this packet. NOT verified: the output of the failed deploy
step, the secret contents. The base deploy of 2026-11-20 uses splice da6556610 (#8602); that run is not part of
this ref and was not looked at.
