# 11188 - app-upgrade: sv1 participant never returns from the reconnect after the sequencer connection swap (run 41845069664)

Recurrence of 9440 (closed 2026-09-02, no repro). main 1fb3d07f8c, Canton 3.7.1, job app-upgrade (0). The base-version
sv1 app swaps the participant's sequencer connection from DefaultSequencer to SEQ::sv1; the participant disconnects
and the reconnect hangs after `Ensured ... persistent state`. The test fails waiting, then the
ResetDecentralizedNamespace teardown plugin's own ReconnectSynchronizer times out after 108 s and `sys.exit(1)`s
(family H), so there is no ScalaTest report.

- Run: https://github.com/canton-network/splice/actions/runs/41845069664, main 1fb3d07f8c ("Add scan event download tool (#8232)"), job 126627039428 `ci / scala_test_app_upgrade / app-upgrade (0)`, attempt 1.
- Runtime canton: 3.7.1 (`git show 1fb3d07f8c:nix/canton-sources.json`).
- Component: Canton participant (synchronizer reconnect); test plugin (sys.exit).

## 1. Job log: no report, the plugin gives up

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11188/job.log | grep -a 'Resetting decentralized namespace\|Exceeded maximum retries'
```
```
2026-11-04T15:06:25.4836135Z Resetting decentralized namespace failed with: com.digitalasset.canton.console.CommandFailure: Command execution failed., giving up
2026-11-04T15:06:26.4917160Z Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
```

## 2. Teardown reconnect times out (artifact logs-app-upgrade-0)

```
zcat canton_network_test.clog.gz | grep -a 'DEADLINE_EXCEEDED' | cut -c1-260
```
```
{"@timestamp":"2026-11-04T15:06:25.050Z","message":"Request failed for remote participant for `sv1`, with admin token.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 107.999543177s. ... Request: ReconnectSynchronizer(Synchronizer 'global',true)
```
The test console log has no test-body lines; the TokenStandardCliSanityCheckPlugin runs in `beforeEnvironmentDestroyed` at 15:04:36.415, so the test had ended (failed) by then.

## 3. The swap and the hung reconnect (canton.clog, sv1Participant)

```
zcat canton.clog.gz | grep -a 'participant=sv1Participant' | grep -a 'Replacing configuration\|Disconnected from Synchronizer\|is now in state Ok().*pool=main\|5d6bebee9883273a1ae1022d678ef388' | grep -av 'Supervised futures' (trimmed)
```
```
15:01:17.704 Replacing configuration for (Synchronizer 'global', global-domain::..77fc::37-0) with ... Sequencer 'SEQ::sv1::..c820' ...
15:01:18.327 Disconnected from Synchronizer 'global'
15:01:19.256 'sequencer-connection-pool' is now in state Ok(). Previous state was Not Initialized.   (pool=main, trace 5d6bebee)
15:01:19.257 Synchronizer 'global' maps to global-domain::1220c8564feb...::37-0 after handshake
15:01:20.047 Ensured global-domain::1220c8564feb...::37-0 persistent state
15:01:28.650 connect to Synchronizer 'global' has not completed after 10096 milliseconds
15:06:30.147 Supervised futures for the following trace IDs have not completed ... 5d6bebee9883273a1ae1022d678ef388(311 seconds)
```
Nothing else is logged for trace 5d6bebee after 15:01:20.047. The base-version sv1 app logs `Not connected to any
synchronizer` (ReconcileDynamicSynchronizerParametersTrigger) from 15:01:21 on.

## 4. History

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "ReconnectSynchronizer never returns" OR "reconnect to global synchronizer going silent"'
```
9151 (2026-07-27, resource-intensive), 9440 (2026-07-30, app-upgrade, assignee oliverse-da, closed 2026-09-02 "no
repro"), 10235 (2026-09-29, wall-clock-time (9), closed 2026-10-12 "2w no repro"), now 11188. 9440's chain of
events (swap by LocalSequencerConnectionsTrigger, Disconnect + Reconnect, hang in getSynchronizerHandle) matches
step for step; this run has the extra debug logging that 9440 was waiting for.

## Verdict

Duplicate of 9440 (reopen it). Canton flake, rare (4 hits since July; app-upgrade runs on every main push). Fix
belongs in Canton (participant reconnect after a sequencer connection change). Family H hides the test failure; the
fix for that (`ray/fix-reset-topology-plugin-no-exit`) never merged. Not verified: what the reconnect waits on after
`Ensured ... persistent state` (needs a thread dump or more logging in GrpcSynchronizerRegistry).
