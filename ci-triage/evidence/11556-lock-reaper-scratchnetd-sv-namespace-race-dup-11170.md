# 11556 - Cluster Lock Reaper (scratchnet): scratchnetd reset fails, sv-runbook Helm uninstall finds the release gone (release not found)

DUPLICATE of 11170 (family "Cluster reset race"). Scheduled run of the Cluster Lock Reaper workflow of 2026-11-17
21:10 UTC. The scratchnetd job finds the lock (held 6 h 42 min) stale and runs the cluster reset command, which
destroys all stacks in parallel. The sv stack deletes namespace sv at 21:12:47.585, the sv-runbook stack starts the
uninstall of its Helm release cluster-ingress-sv in that namespace at 21:12:53.966 and fails at 21:13:01.164 because
the release to purge is not found. That first failure aborts every other running destroy (86 cancelled operations).
First hit on scratchnetd; the four other cluster jobs of the run passed. No test ran, so there is no checkErrors or
assertion evidence.

- Job: Cluster Lock Reaper (scratchnet), the job that reaps stale cluster locks on scratchnetd, failed in the step
  that resets the cluster if the lock is older than 6 hours.
- Parent: 11170.
- Runtime canton: not applicable (cluster reset, no Canton started by the job).
- Component: infra, cluster reset in splice `cluster/pulumi` (parallel destroy).
- Evidence: private CI job output and cluster logs; not reproduced on this branch.

## 1. Order of the operations: sv-runbook fails first, the rest is the abort

The lock is stale, the reset starts, and the first error of the job is the sv-runbook uninstall; the step ends with
exit code 1 at 21:13:52. Eight destroys succeed before 21:12:22, the down operation of the sv-runbook stack is the
first to fail (21:13:04.104), and all later failures come after the abort of 21:13:14.543, which cancels the still
running sv and sv-canton destroys. The later failed operations are the sv stacks (sv, sv-1, sv-2, sv-3, sv-da-1),
the sv-canton stack of sv on migration 0, the validator runbook, validator1 and splitwell.

## 2. Confirming signature of the family: namespace sv deleted by the sv stack before the sv-runbook release delete

The family's confirmation is the delete of namespace sv by the sv stack before the delete of the sv-runbook Helm
release cluster-ingress-sv. Here: 21:12:47.585 against 21:12:53.966, error 7.2 s later. The four sv-runbook releases
whose delete started before the namespace delete (info, validator, scan, sv-app; 21:12:25 to 21:12:36) raise no
error; the only release whose delete started after it (cluster-ingress-sv) is the only sv-runbook resource delete
between 21:12:53.966 and the error at 21:13:01.164.

## 3. Side observation: pending CREATE operations from an interrupted deployment

Three stacks (sv-runbook with 4, the sv-canton stack of sv on migration 0 with 1, the sv-canton stack of sv-1 on
migration 1 with 4) carry pending operations from a deployment that was interrupted, so the lock holder's last
deployment did not finish. The pending sv-runbook operations are on sv-app and scan, whose deletes succeed, not on
cluster-ingress-sv.

## 4. Frequency

The workflow runs hourly; the 11 runs before this one on 2026-11-17 (10:11 to 20:11 UTC) passed. Earlier hits of the
family: 11170 (11-03), 11196 (11-05), 11343 and 11345 (11-10). This is the fifth failed reset in 15 days and the
third cluster (scratche, scratchnetc, scratchnetd).

## Verdict

Duplicate of 11170 (open, "Cluster reaper failed to reset: release not found"), family "Cluster reset race" in
`known-families.md`; the confirming signature (section 2) matches. Real defect of the parallel reset, not a flake of
this run: the sv stack and the sv-runbook stack both own resources in namespace sv and are destroyed concurrently.
Fix location: the destroy order in `cluster/pulumi` (sv-runbook before the sv stacks) or tolerating a missing
release on uninstall; owner as in 11170. No fix branch (outcome: duplicate).

Not verified: that the namespace deletion removed the Helm release record (not visible in the job log); whether the
later hourly run reset and unlocked scratchnetd (no run after 21:10 existed at triage time; the reset step is not
retried within the job, so the lock and a half-destroyed cluster stay until the next run or a manual reset); whether
the pending CREATE operations of section 3 contributed (they are on other releases).
