# 11256 - LsuIntegrationTest: standalone bobValidator participant crashes on disconnect, AcsCommitmentSender close waits on a 439 s send delay (run 42248944117)

DUPLICATE of 10729 (family O; same mechanism as 10353). main 6f7a5f83bc, job `logical-sync-upgrade (0)`, canton 3.7.2.
LsuIntegrationTest "upgrade synchronizer to new physical synchronizer without downtime" failed with
`CommandFailure` in clue "bob validator local upgrades after upgrade and can tap": the standalone participant of
bobValidatorLocal (`lsu-bob-validator-after-upgrade`) got `DisconnectSynchronizer` at 16:33:00.040; the
AcsCommitmentSender scheduled `Delaying sending commitment by 439 seconds` 1 ms after its pipeline close began, the
close did not complete within 60 s, and the node crashed itself (`Fatal error occurred, crashing the node ...
Failed to disconnect from Synchronizer 'global'`) at 16:34:00.129; the test's next console call got `Connection
reset by peer`. Not related to DA BFT.

- Run: https://github.com/canton-network/splice/actions/runs/42248944117, main 6f7a5f83bc, job 127999162907 `logical-sync-upgrade (0)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: Canton (participant AcsCommitmentSender close).

## 1. Failing assertion



```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11256/job.log | grep -a -A1 'without downtime \*\*\* FAILED' | head -2; zcat log/11256/logs-logical-sync-upgrade-0/canton_network_test.clog.gz | jq -r 'select(.level=="ERROR") | "\(.["@timestamp"]) | \(.message|gsub("\n";" ")|.[0:260])"' | head -4
```

```
[info] - upgrade synchronizer to new physical synchronizer without downtime *** FAILED ***
[info]   com.digitalasset.canton.console.CommandFailure: Command execution failed.
2026-11-06T16:34:00.587Z | Request failed for remote participant for `bobValidatorLocal`, with admin token. Is the server running? Did you configure the server address as 0.0.0.0? Are you using the right TLS settings? (details logged as DEBUG)   GrpcServiceUnavailable: UNAVAILABLE/io ex
2026-11-06T16:34:00.588Z | Failed clue: Using external Canton process lsu-bob-validator-after-upgrade
2026-11-06T16:34:00.588Z | Failed clue: bob validator local upgrades after upgrade and can tap
2026-11-06T16:34:00.588Z | Failed clue: Using external Canton process global-synchronizer-upgrade
```

## 2. Family O confirming grep on the standalone participant

Disconnect, kill switch, the randomized send delay 1 ms into the close, the 60 s close timeout and the crash.

```
zcat log/11256/logs-logical-sync-upgrade-0/canton-standalone-lsu-bob-validator-after-upgrade.clog.gz | jq -r 'select(.message|test("received a message DisconnectSynchronizerRequest|killSwitch shutdown\\)\\x27\\.\\.\\.|Delaying sending commitment|did not complete within 60 seconds|Fatal error occurred")) | "\(.["@timestamp"]) \(.level) | \(.message|gsub("\n";" ")|.[0:170])"'
```

```
2026-11-06T16:32:59.303Z DEBUG | Attempting to close 'SyncCloseable(name=killSwitch shutdown)'...
2026-11-06T16:33:00.040Z DEBUG | Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer by grpc:/127.0.0.1:53244: received a message DisconnectSynchr
2026-11-06T16:33:00.109Z DEBUG | Attempting to close 'SyncCloseable(name=killSwitch shutdown)'...
2026-11-06T16:33:00.110Z DEBUG | Delaying sending commitment by 439 seconds (interval = 30m, min = 0.0, max = 0.9)
2026-11-06T16:34:00.117Z WARN | Task closing pipeline completion future did not complete within 60 seconds. Stack traces:   Thread[#52,extraStandaloneParticipant-wallclock-0,5,main] is-daemon=true state
2026-11-06T16:34:00.129Z ERROR | Fatal error occurred, crashing the node to recover from invalid state: Failed to disconnect from Synchronizer 'global' due to an exception Stack trace:   Thread[#116,daml
```

## 3. Parent issues



```
for n in 10729 10353; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"\(.number) \(.state) \(.title)"'; done
```

```
10729 open LsuIntegrationTest: sv2 participant crashes on disconnect, AcsCommitmentSender close waits on send delay
10353 open LsuIntegrationTest: restored bobValidator participant exits with FatalError on disconnect from 36-2, ACS commitment send scheduled in the closing millisecond is never cancelled
```

## Verdict

Duplicate of 10729 (family O): Canton 3.7 AcsCommitmentSender close waits on a randomized commitment send delay that
the kill switch does not interrupt; here it hit the restored bobValidatorLocal participant, as in 10353. Canton fix
needed; nothing splice-side. Not BFT-related; splice#8356 irrelevant.
