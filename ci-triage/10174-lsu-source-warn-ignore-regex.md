# 10174 - LsuIntegrationTest: the WARN that #7311 added for a validator init against a non-active psid fails checkErrors because #7311's own ignore pattern never matches (run 35366024437)

Successor of 10088-B (validator init versus LSU race). #7311 (f1ee318e39, 2026-09-16, "Fixes cn-test-failures 10088")
replaced the infinite `modify synchronizer` retry with a WARN and a skip, and added an ignore pattern for that WARN
in the same commit. The pattern has unescaped parentheses, so it never matched; the first time the WARN path
fired on main, the shard went red on checkErrors alone.

- Run: https://github.com/canton-network/splice/actions/runs/35366024437, main 696b79a4c6 ("enable http2 connect for the
  gateway (#7418)"), job 105669130974 `logical-sync-upgrade (0)`, canton 3.6.0-snapshot.20260916.20284.0.vf27c4824.
  Both LsuIntegrationTest tests passed; `checkErrors` fails `canton_network_test.clog` on one WARN.

## 1. Flagged line

```
grep -a -B400 'contains problems' log/10174/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' | sed -E 's/^[^Z]*Z //'
```
```
16:21:30.509Z WARN o.l.s.e.ParticipantAdminConnection:LsuIntegrationTest/config=37cbfbcd/validator=bobValidatorLocal
  Connection for Synchronizer 'global' with psid Some(global-domain::1220d4f4f736...::36-0) is no longer active (status: LSU source), skipping update of the connection config
```

## 2. Where it comes from

```
git log origin/main --format='%h %ad %s' --date=short -S'is no longer active' -- apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/ParticipantAdminSynchronizerConnection.scala
git show f1ee318e39 --stat --format= | tail -3
git show f1ee318e39 -- project/ignore-patterns/canton_network_test_log.ignore.txt | grep '^+' | grep -v '^+++'
```
```
f1ee318e39 2026-09-16 Don't wait forever on a non-active psid in `ensureSynchronizerRegisteredAndConnected` (#7311)
 .../environment/ParticipantAdminSynchronizerConnection.scala       | 7 +++++++
 project/ignore-patterns/canton_network_test_log.ignore.txt         | 2 ++
+Connection for .* with psid .* is no longer active (status: .*), skipping update of the connection config
```
`ParticipantAdminSynchronizerConnection.scala:201-205` (main): when the registered connection for the requested psid is
not `Active`, log the WARN and return `Right(())` instead of retrying the modify forever (10088-B's 5-minute hang).

## 3. Why the ignore does not match

check-logs.sh feeds the pattern file to ripgrep as regexes. In `(status: .*)` the parentheses form a group, so the
regex expects `active status: ..., skipping` while the log has `active (status: LSU source), skipping`.
```
LINE=$(zcat log/10174/logs-logical-sync-upgrade-0/canton_network_test.clog.gz | grep -a -m1 'is no longer active')
echo "$LINE" | rg -c -e 'Connection for .* with psid .* is no longer active (status: .*), skipping update of the connection config' || echo NO MATCH
echo "$LINE" | rg -c -e 'Connection for .* with psid .* is no longer active \(status: .*\), skipping update of the connection config'
```
```
NO MATCH
1
```
Audit of the other ignore files for the same shape found nothing else:
```
grep -n -E '(^|[^\\])\([a-zA-Z]+: ' project/ignore-patterns/*.txt     # (no output)
```

## 4. What happened in the test (why the WARN path fired)

```
zcat log/10174/logs-logical-sync-upgrade-0/canton_network_test.clog.gz | grep -a STATUS_LSU_SOURCE | grep -a -oE '"@timestamp":"[^"]+"|(validator|SV)=[A-Za-z0-9]+' | paste - - | sort | awk '!seen[$2]++'
zcat ... | grep -a 'validator=bobValidatorLocal' | grep -a -v ApiClientRequestLogger | grep -a 'T16:21:30' | grep -a -E 'Ensuring that participant registered|Set the new synchronizer|no longer active|Success: participant registered'
```
```
16:19:20.207Z SV=sv2 ... 16:20:03.350Z SV=sv4 first see psid 36-0 as STATUS_LSU_SOURCE (the upgrade happened 16:19-16:20)
16:21:30.314Z bobValidatorLocal  Ensuring that participant registered Synchronizer 'global' with config ... physicalSynchronizerId = ...::36-0
16:21:30.396Z bobValidatorLocal  'Set the new synchronizer connection if required' failed with a retryable error (InvalidGivenCurrentSystemStateOther) ... retrying
16:21:30.502Z bobValidatorLocal  first sees 36-0 as STATUS_LSU_SOURCE
16:21:30.509Z bobValidatorLocal  WARN Connection ... 36-0 is no longer active (status: LSU source), skipping update
16:21:30.509Z bobValidatorLocal  Success: participant registered Synchronizer 'global' ...   (init continues, connect by alias succeeds, test passes)
```
bobValidatorLocal is restarted after the upgrade from its pre-upgrade state; `SynchronizerConnector` prefers the psid
the participant already has registered (36-0) over scan's active id ("No registered physical synchronizer id, using
active id from scan" is only the fallback), the first modify is rejected by Canton, and on the retry the participant
has caught up and reports 36-0 as LSU source, which is exactly the branch #7311 added. 38 of the 40 main push runs
since 2026-09-16 passed this shard, so the path is rare (participant catch-up versus app init), as #7311 says.

## 5. Verdict and fix

Two layers. (a) The shard failed only because #7311's ignore regex is wrong: fix branch
`ray/fix-10174-lsu-source-ignore-regex` (8cdbe8996b, off main) escapes the parentheses; verified with ripgrep against
the run's line (1 match; the old pattern 0). This restores the behaviour #7311 intended, it does not add a new
suppression. (b) The WARN itself is legitimate information for operators, and the residual design point stands: a
validator restarting after an LSU still targets the registered (stale) psid instead of the active one; #7311's author
listed crashing or a test tweak as alternatives. That is a validator change, not written here.
