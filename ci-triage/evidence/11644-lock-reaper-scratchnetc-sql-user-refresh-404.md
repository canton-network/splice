# 11644 - Cluster Lock Reaper (scratchnet): scratchnetc reset fails, refresh of gcp:sql:User sv-1-user-bqdatastream 404 (Cloud SQL instance does not exist)

New, not a duplicate (not the "Cluster reset race" of 11170: the Helm release-not-found signature of that family
is absent). Scheduled run of the Cluster Lock Reaper workflow of 2026-11-19 05:11 UTC. The scratchnetc job finds the
lock (held 6 h 53 min) stale and runs the cluster reset command, which refreshes and destroys all stacks in
parallel. The refresh of the canton-network stack of scratchnetc finds the sv-1 apps Cloud SQL instance gone in GCP
and drops it from the state (05:13:14.812), then the refresh of its dependent `gcp:sql:User sv-1-user-bqdatastream`
fails with a 404 from the provider saying that the Cloud SQL instance does not exist (05:13:15.035). The failed
refresh aborts every other running destroy (20 cancelled operations), exit code 1. The state is sticky: the SQL user
stays in the stack state, so the next three hourly runs (06:19, 07:12, 08:15) fail on the same resource; scratchnetc
stays locked and half-destroyed until the user is removed from the state by hand. No test ran, so there is no
checkErrors or assertion evidence.

- Job: Cluster Lock Reaper (scratchnet), the job that reaps stale cluster locks on scratchnetc, failed in the step
  that resets the cluster if the lock is older than 6 hours. The only failed job of the run; the scratchneta, b, d,
  e jobs passed.
- Runtime canton: not applicable (cluster reset, no Canton started by the job).
- Component: infra, cluster reset in splice `cluster/pulumi` (`pulumiOperations.ts` `downStack`, `common-sv`
  `bigQuery.ts` replicator user) plus the pulumi gcp provider (google-beta 9.18.0).
- Public splice commit: main 1eafba3113 (the code quoted in section 2).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. The refresh of the canton-network stack: instance gone, then the SQL user read fails

The lock is stale, the reset starts, the first failed operation is the down operation of the canton-network stack
(05:13:21.059), and the step ends with exit code 1 at 05:13:58. The refresh removes the missing sv-1 apps Cloud SQL
instance from the state without an error; 0.22 s later the read of the user on that instance returns 404 and the
provider reports it as an error instead of a missing resource. The refresh summary shows 1 resource deleted and 30
unchanged. The failing command is `pulumi refresh`, so the destroy of this stack never starts.

## 2. Why one failed refresh fails the whole reset

`downStack` (splice main 1eafba3113, `cluster/pulumi/pulumiOperations.ts`) refreshes, then destroys; any exception
other than `ConcurrentUpdateError` aborts the shared controller, which cancels every other stack operation.

```
git show origin/main:cluster/pulumi/pulumiOperations.ts | sed -n '49,62p'
```
```
  try {
    console.error(`[${name}] Refreshing`);
    await stack.refresh(pulumiOptsWithPrefix(`[${name}]`, abortController.signal));
    console.error(`[${name}] Destroying`);
    await stack.destroy(pulumiOptsWithPrefix(`[${name}]`, abortController.signal));
  } catch (e) {
    if (e instanceof automation.ConcurrentUpdateError) {
      console.error(`[${name}] Stack is locked, cancelling and re-running.`);
      await stack.cancel();
      await downStack(stack, abortController);
    } else {
      abortController.abort(`${stack.name} - Aborting because of caught exception`);
      throw e;
    }
```

The user is the BigQuery replicator user, created on the cn-apps instance in
`cluster/pulumi/common-sv/src/bigQuery.ts` (`createPostgresReplicatorUser`, `instance: databaseInstance.name`).

## 3. Sticky: the next three hourly runs fail on the same resource

The partial refresh result was saved: in the scratchnetc jobs of the later runs (06:19, 07:12, 08:15 UTC; refs
11646, 11647, 11648) the instance is no longer refreshed at all, the user still is and fails the same way, and the
first failed operation is again the canton-network stack. The lock age grows from run to run (8 h 1 min, 8 h 54 min,
9 h 59 min). The two hourly runs before 05:11 (03:13, 04:12) succeeded; all four from 05:11 to 08:15 failed.

## 4. Side observation: the cluster was left by an interrupted deployment

Three stacks carry pending CREATE operations from a previous deployment (the sv stack 1, the sv-canton stack of sv
on migration 0 13, sv-runbook 7), and the validator runbook stacks were last updated at 02:23 UTC with 0 resources,
2 h 49 min before the reaper. So the lock holder's last operations on scratchnetc were interrupted; what removed the
Cloud SQL instance without the stack state is not in the job log.

## 5. Duplicate check

No family in `known-families.md` (grep run before family BK was added) and no tracker issue carries the signature;
the earlier reaper failures are the 11170 family (Helm release not found) and the one-off 11259 (Kubernetes API
unavailable).

```
grep -c -i -E 'instanceDoesNotExist|Cloud SQL instance does not exist|bqdatastream' ci-triage/known-families.md
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Cloud SQL instance does not exist"' --jq '.total_count'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue bqdatastream' --jq '.total_count'
```
```
0
0
0
```

## Verdict

New (family BK in `known-families.md`, parent 11644). Real and hard broken, not a flake: every hourly reaper run
fails on scratchnetc since 05:11 (4 of 4) and will until the stale `gcp:sql:User sv-1-user-bqdatastream` is removed
from the state of the canton-network stack of scratchnetc (owner action on the cluster, e.g. `pulumi state delete`
of that resource, then a reset). 11646, 11647, 11648 are the same failure (section 3) and would close as duplicates
of 11644.

Fix location: splice `cluster/pulumi`, owner decision, not written. Options: make the reset tolerate a failed
refresh (go on to destroy, or drop resources whose parent instance is gone), or let the user go with the instance
(`deletedWith` covers destroy, not refresh), or a provider-side fix (the SQL user read treating a 404 for a missing
instance as gone). None is verifiable without a cluster in this state, and it changes production reset code. No fix
branch.

Not verified: what deleted the sv-1 apps Cloud SQL instance outside the stack state (not visible in the job log;
needs the GCP audit log of the scratchnet project or the lock holder); why the refresh summary counts two errors
with one diagnostic; whether `pulumi destroy` without the refresh would get past the user; runs after 08:15 UTC.
