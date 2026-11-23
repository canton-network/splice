# 11647 - Cluster Lock Reaper (scratchnet): scratchnetc reset fails a third time on the refresh of gcp:sql:User sv-1-user-bqdatastream, 404 (Cloud SQL instance does not exist)

DUPLICATE of 11644 (family BK), confirmed by the family's signature in this job's own log. Scheduled run of the
Cluster Lock Reaper workflow of 2026-11-19 07:12 UTC, the second hourly run after the one of 11644. The scratchnetc
job finds the lock (held 8 h 54 min) stale and runs the cluster reset command; `pulumi refresh` of the
canton-network stack of scratchnetc fails at 07:13:55.600 on `gcp:sql:User sv-1-user-bqdatastream` with a 404 from
the provider saying that the Cloud SQL instance does not exist, the abort cancels the other stacks (10 cancelled
operations), exit code 1. The sv-1 apps Cloud SQL instance is not refreshed at all: it left the state in the 05:11
run, the user stayed, which is the sticky state described in 11644. No test ran, so there is no checkErrors or
assertion evidence.

- Job: Cluster Lock Reaper (scratchnet), the job that reaps stale cluster locks on scratchnetc. The only failed job
  of the run; the scratchneta, b, d, e jobs passed.
- Parent: 11644.
- Runtime canton: not applicable (cluster reset, no Canton started by the job).
- Component: infra, cluster reset in splice `cluster/pulumi` plus the state of the canton-network stack of
  scratchnetc.
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Side observation: leftover pulumi locks

Not the cause of the exit: three sv-canton stacks (sv-2 migration 0, sv-3 migration 1, sv-1 migration 1) report a
leftover pulumi lock at 07:13:49, six seconds before the failing refresh. The first failed operation is the
canton-network stack, not one of these.

## Verdict

Duplicate of 11644 (family BK): same cluster, same stack, same resource, same error, two hours later. Same sequence
as 11644 and 11646: stale lock, reset, the first failed operation is the canton-network stack (07:14:01.237), exit
code 1 at 07:14:35; the failing command is `pulumi refresh`, so the destroy of this stack never starts. Both
signatures of family BK are present (the failed refresh of the user and the 404 for the missing instance) and the
signature of the Cluster reset race (11170, Helm release not found) is absent. The parent is open and not itself
closed as a duplicate; the workflow has failed on every hourly run since 05:11 (05:11, 06:19, 07:12, 08:15), after
four successes from 01:11 to 04:12.

Real and hard broken, not a flake: 4 of 4 hourly runs since 05:11 fail and will until
`gcp:sql:User sv-1-user-bqdatastream` is removed from the state of the canton-network stack of scratchnetc (owner
action on the cluster, e.g. `pulumi state delete` of that resource, then a reset). Mechanism and fix options are in
`evidence/11644-lock-reaper-scratchnetc-sql-user-refresh-404.md`; the fix in the reset code is an owner decision.
No fix branch, nothing changed outside this packet.

Not verified: what deleted the Cloud SQL instance outside the stack state (needs the GCP audit log or the lock
holder); whether the three leftover pulumi locks on the sv-canton stacks would fail the reset on their own once the
SQL user is removed; runs after 08:15 UTC.
