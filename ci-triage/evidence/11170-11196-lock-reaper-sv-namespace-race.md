# 11170, 11196 - Cluster Lock Reaper (scratchnet): reset fails, sv-runbook Helm uninstall "release: not found"

Both runs (canton-network-internal 41669902127 on 2026-11-03 16:04, 41958994262 on 2026-11-05 03:02) find the scratchnete
lock too old and run `cncluster ci_reset_unlock`, which destroys all stacks in parallel (`cluster/pulumi/pulumiDown.ts`).
Logs: host export `ci-triage/export/{11170,11196}/job.log` (gh run view --log-failed).

```
grep -aE 'error: |Aborting|Operation .* (failed|succeeded)' job.log | grep -avE 'context canceled|Resource scheduled|Resource is Ready|warning:'
grep -a -A1 'sv-runbook.scratche\]- kubernetes:helm.sh/v3:Release: (delete)' job.log
grep -a -A1 'kubernetes:core/v1:Namespace: (delete)' job.log
```
11196:
```
03:04:25.609 [organization/sv/sv.sv.scratche]- kubernetes:core/v1:Namespace: (delete)
03:04:25.609     [id=sv]
03:04:34.699 [organization/sv-runbook/sv-runbook.scratche]- kubernetes:helm.sh/v3:Release: (delete)
03:04:34.699     [id=sv/cluster-ingress-sv]
03:04:43.434 [organization/sv-runbook/sv-runbook.scratche] - error - error: uninstallation completed with 1 error(s): uninstall: Failed to purge the release: release: not found
03:04:46.412 Aborting after the wait time: organization/sv-runbook/sv-runbook.scratche - Aborting because of caught exception
```
11170: the same sequence, 16:07:48.459 Namespace sv delete (sv.sv.scratche), 16:07:56.718 delete sv/cluster-ingress-sv,
16:08:05.273 the same uninstall error, 16:08:08.346 abort; from 16:08:19 every other running destroy fails with
`context canceled` / `client rate limiter Wait returned an error: context canceled` (194 lines). Reset step has
`cmd_retry_count: 0`.

Verdict: likely a race in the parallel destroy: the sv stack deletes namespace `sv` while sv-runbook still uninstalls its
Helm releases in it; the namespace deletion removes the release record, so the purge finds no release, the
first exception aborts all operations. Not shown in the log: that the release secret was removed by the namespace
deletion. Fix candidates: destroy sv-runbook before the sv stacks (or exclude the namespace from one of them), or treat
"release: not found" on uninstall as deleted. Owner: pulumiDown.ts / scratchnet sv stacks (#4195, mblaze-da).
