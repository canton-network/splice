# Host commands, 2026-10-30b part 4: 10922 cluster_lock_reaper (log in log/inbox-2026-10-30/gha-41000672485.log)

scratchnetb, 10-30 04:03: the lock held by pperek was 6 h 53 min old, so the reaper ran `cncluster ci_reset_unlock`.
The Pulumi stacks were still locked by another runner (`runner-nfrx9`, pid 9739, locks created 2026-10-29T23:04:33Z),
an earlier scratchnetb operation that never released them. The reaper cancelled those locks and re-ran; the reset
then failed on `namespace sv ... is being terminated` and `context canceled`. Same family as 10500 / 10635 (Pulumi
interrupted, locks left behind); no reaper failures since.

```zsh
R=DACH-NY/cn-test-failures
[ "$(gh issue view 10922 --repo $R --json state -q .state)" = OPEN ] && gh issue close 10922 --repo $R --reason "not planned" --comment "scratchnetb reaper (lock by pperek, 6 h 53 min old): the Pulumi stacks were still locked by another runner (runner-nfrx9, pid 9739, locks created 10-29 23:04:33Z), an earlier operation that never released them. The reaper cancelled the locks and re-ran, then the reset failed with 'namespace sv ... is being terminated' and context canceled. Same family as #10500 / #10635 (interrupted Pulumi run leaves locks); later reaper runs passed."
```
