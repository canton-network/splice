# 10088 - two LSU failures (run 34474728903)

Push CI on branch main (backport #7239). Canton runtime 3.6.0-snapshot.20260909.20251.0.v0a9e6e25.
Two unrelated failures. Commands verified against the downloaded artifacts, streamed with zcat.

## Setup

```
# root fs is nearly full; stage gh download on a roomy mount and keep gzipped
TMPDIR=<roomy>/ghtmp gh run download 34474728903 --repo canton-network/splice -n logs-roll-forward-lsu-0 -D rfl0
TMPDIR=<roomy>/ghtmp gh run download 34474728903 --repo canton-network/splice -n logs-logical-sync-upgrade-0 -D lsu0
```

## Which jobs

```
gh run view 34474728903 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | "\(.databaseId)  \(.conclusion)  \(.name)"' \
  | grep -iE 'roll-forward-lsu \(|logical-sync-upgrade \('
```
```
102863017343  cancelled  ci / scala_test_roll_forward_lsu / roll-forward-lsu (1)
102863017352  cancelled  ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)
102863018481  failure    ci / scala_test_logical_sync_upgrade / logical-sync-upgrade (0)
```
Only logical-sync-upgrade(0) is GH conclusion=failure (B). Both roll-forward-lsu shards are cancelled
(A) - their logs show a deterministic terminal init failure.

---

# Failure A - roll-forward-lsu / synchronizer limits

Jobs 102863017352 (shard 0) and 102863017343 (shard 1), both cancelled. Reads rfl0/.

## A0. Canton version

```
zcat rfl0/canton.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
```
```
Canton version 3.6.0-snapshot.20260909.20251.0.v0a9e6e25
```

## A1. Suite

```
zcat rfl0/canton_network_test.clog.gz | grep -aoE 'RollForwardLsu[A-Za-z]*IntegrationTest' | sort | uniq -c
```
```
  78475 RollForwardLsuDRIntegrationTest
```
Shard 0 = RollForwardLsuDRIntegrationTest (config=d01e6af6); shard 1 = RollForwardLsuIntegrationTest
(config=7b56367).

## A2. The synchronizerLimits failure

```
zcat rfl0/canton_network_test.clog.gz | grep -aE 'expected default value for synchronizerLimits' \
  | grep 'SV=sv1Local' | head -1 | sed -E 's/[0-9a-f]{40,}/<HASH>/g' \
  | grep -aoE '"@timestamp":"[^"]*"|"level":"[A-Z]+"|InvalidStaticSynchronizerParameters: expected default value for synchronizerLimits[^\\]*|LsuNodeInitializer[^"]*scala:[0-9]+|SynchronizerParameters\.scala:[0-9]+'
```
```
"@timestamp":"2026-09-10T12:19:48.161Z"
"level":"WARN"
InvalidStaticSynchronizerParameters: expected default value for synchronizerLimits in GenericClassTag but found SynchronizerLimits(
SynchronizerParameters.scala:105
LsuNodeInitializer.$anonfun$initializeSynchronizer$6(LsuNodeInitializer.scala:98)
```
Rolling the LSU sequencer forward from the predecessor's static params, canton 3.6 rejects the copied
StaticSynchronizerParameters because synchronizerLimits is non-default (a populated SynchronizerLimits /
TransactionProtocolLimits where a default was expected). Thrown from splice
LsuNodeInitializer.scala:98 (via RetryProvider.scala:382), canton SynchronizerParameters.scala:105.
LsuNodeInitializer logs "not retrying".

## A3. All four SVs hit it; count + window

```
zcat rfl0/canton_network_test.clog.gz | grep -aoE '"logger_name":"o\.l\.s\.s\.l\.LsuNodeInitializer:[^"]*"' | sort | uniq -c
```
```
      6 "logger_name":"o.l.s.s.l.LsuNodeInitializer:RollForwardLsuDRIntegrationTest/config=d01e6af6/SV=sv1Local"
      6 "logger_name":"o.l.s.s.l.LsuNodeInitializer:RollForwardLsuDRIntegrationTest/config=d01e6af6/SV=sv2Local"
      6 "logger_name":"o.l.s.s.l.LsuNodeInitializer:RollForwardLsuDRIntegrationTest/config=d01e6af6/SV=sv3Local"
      6 "logger_name":"o.l.s.s.l.LsuNodeInitializer:RollForwardLsuDRIntegrationTest/config=d01e6af6/SV=sv4Local"
```
```
zcat rfl0/canton_network_test.clog.gz | grep -aE 'expected default value for synchronizerLimits' | grep -aoE '"@timestamp":"[^"]*"' | sed -n '1p;$p'
```
```
"@timestamp":"2026-09-10T12:19:48.161Z"
"@timestamp":"2026-09-10T12:19:48.165Z"
```
All four SVs (sv1-sv4) fail identically; deterministic, not a flake. (Shard 1 is the same with
config=7b56367; window 12:22:11.938Z .. 12:22:11.942Z.)

## A4. Terminal outcome - SV app init fails, log ends, no ScalaTest summary

```
zcat rfl0/canton_network_test.clog.gz | grep -aE 'app initialization: Initialization failed' | grep -aoE 'SV=sv[0-9]Local' | sort | uniq -c
```
```
      1 SV=sv1Local
      1 SV=sv2Local
      1 SV=sv3Local
      1 SV=sv4Local
```
```
zcat rfl0/canton_network_test.clog.gz | grep -acE 'TestFailed|TestSucceeded|RUN ABORTED|SuiteAborted'
```
```
0
```
```
zcat rfl0/canton_network_test.clog.gz | grep -aoE '"@timestamp":"[^"]*"' | tail -1
```
```
"@timestamp":"2026-09-10T12:19:48.165Z"
```
All four SvApps log "Initialization failed" (RuntimeException, NodeBase.scala:225) and the log ends at
that instant with no ScalaTest summary. The GH job is marked "cancelled" because it was terminated at
the terminal init abort before ScalaTest emitted a result; the underlying cause is the synchronizerLimits
rejection, not an external cancel. (The claim that PR #7234 fixes this is not verifiable from these logs.)

---

# Failure B - logical-sync-upgrade (LSU)

Job 102863018481, GH conclusion=failure. Reads lsu0/.

## B1. Suite

```
cat lsu0/test-full-class-names-lsu.log
```
```
org.lfdecentralizedtrust.splice.integration.tests.LsuIntegrationTest
```

## B2. The infinite retry - bobValidatorLocal waiting to observe registration on ::36-0

```
zcat lsu0/canton_network_test.clog.gz | grep -aE 'Wait until observing participant registered' \
  | grep 'bobValidator' | grep -aoE '"logger_name":"[^"]*"' | sort | uniq -c
```
```
    263 "logger_name":"o.l.s.e.ParticipantAdminConnection:LsuIntegrationTest/config=9f4b1ae/validator=bobValidatorLocal"
```
```
zcat lsu0/canton_network_test.clog.gz | grep -aE 'Wait until observing participant registered' \
  | grep 'bobValidator' | grep -aoE '"@timestamp":"[^"]*"' | sed -n '1p;$p'
```
```
"@timestamp":"2026-09-10T12:29:06.375Z"
"@timestamp":"2026-09-10T12:34:07.001Z"
```
The target PSID in that wait is physicalSynchronizerId = global-domain::<HASH>...::36-0. bobValidatorLocal
retries the wait 263 times over ~5 min (12:29:06 -> 12:34:07); the registration on ...::36-0 never appears.

## B3. The timeout that fails the test

```
zcat lsu0/canton_network_test.clog.gz | grep -aE 'Timeout while waiting for initialization of bobValidatorLocal' | head -1 | grep -aoE '"@timestamp":"[^"]*"|Condition never became true within 5 minutes'
```
```
"@timestamp":"2026-09-10T12:33:54.059Z"
Condition never became true within 5 minutes
```
```
zcat lsu0/canton_network_test.clog.gz | grep -aE "Test failed: .LsuIntegrationTest/upgrade synchronizer" | head -1 | grep -aoE '"@timestamp":"[^"]*"|Test failed: .[^,]*without downtime.'
```
```
"@timestamp":"2026-09-10T12:33:55.532Z"
Test failed: 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'
```
```
zcat lsu0/canton_network_test.clog.gz | grep -acE 'Condition never became true within 5 minutes'
```
```
5
```
The 5-min retry budget exhausts at 12:33:54 (IllegalStateException from ConsoleMacros retry_until_true),
bobValidatorLocal init times out, and ScalaTest records the test "upgrade synchronizer to new physical
synchronizer without downtime" as failed at 12:33:55.

## B4. Not a checkErrors failure

```
zcat lsu0/canton_network_test.clog.gz | grep -acE 'lines with problems'
```
```
0
```
This is a plain ScalaTest condition-timeout, not a checkErrors log-scan failure.

## B5. Root cause: the validator picks the OLD psid to modify, and loops once the LSU deactivates it

The "Wait until observing registered on ::36-0" (B2) is only the outer timeout. The operation bob is
actually stuck on is inside its init step "Ensuring decentralized synchronizer registered"
(ValidatorApp.scala, domainConnector.ensureDecentralizedSynchronizerRegisteredAndConnectedWithCurrentConfig)
which, a few calls down, does "Set the new synchronizer connection if required" (modify_synchronizer_connection).
It picks the OLD physical synchronizer id ::36-0 to modify. But the LSU has just activated the NEW psid
::36-2 and deactivated ::36-0, so the modify fails and bob retries it forever against the dead psid, with
no logic to re-pick the now-active ::36-2.

The init step start, then the stuck modify with SYNCHRONIZER_STATUS_NOT_ACTIVE (verbatim from bob's log):
```
12:24:00.927  ValidatorApp:...validator=bobValidatorLocal  bobValidatorLocal app initialization: Ensuring decentralized synchronizer registered started
12:29:06.263  ParticipantAdminConnection:...validator=bobValidatorLocal  The operation 'Set the new synchronizer connection if required' failed with a retryable error
12:29:06.263  ... Detected an error. io.grpc.StatusRuntimeException: FAILED_PRECONDITION: SYNC_SERVICE_SYNCHRONIZER_STATUS_NOT_ACTIVE(9,...): Synchronizer 'global' is not active which prevents operation `modify synchronizer` from being performed.
12:29:06.263  ... The operation 'Set the new synchronizer connection if required' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a number of 0 failures, and after 100 milliseconds.
12:33:54.057  ... 'Wait until observing participant registered Synchronizer 'global' with config SynchronizerConnectionConfig(...   <- the outer wait, times out
```
bob only ever targets the old psid, never the new one:
```
zcat lsu0/canton_network_test.clog.gz | grep 'bobValidatorLocal' \
  | grep -aE 'modify_synchronizer_connection|Set the new synchronizer connection' | grep -aoE '::36-[0-9]' | sort | uniq -c
```
```
      1 ::36-0
```
(The visible SYNCHRONIZER_STATUS_NOT_ACTIVE lines are few - canton's retry util logs the first-error trio
then quiets; the 100ms retries continue silently from 12:29:06 until the outer wait times out at 12:33:54.)

Root cause (per the code owner, confirmed by the logs): a race in the validator's synchronizer-connection
setup. bob picked the old psid ::36-0 to modify; the LSU changed the active psid to ::36-2 right after;
bob keeps retrying the modify against the now-inactive ::36-0 with no logic to bubble back up and retry
with the new psid. Fix belongs in the validator (ValidatorApp / domainConnector /
ParticipantAdminConnection): on SYNCHRONIZER_STATUS_NOT_ACTIVE, re-resolve the active psid instead of
retrying the dead one. This is validator-side; it is independent of the sv4 issue below.

## B6. A separate incidental issue in the same run (NOT the cause of bob's hang)

The run also has a degraded upgrade - sv4 published its sequencer successor too late, so the upgrade
dropped sv4's sequencer. This is real but orthogonal to why bob hangs (bob would hit the psid race even
with all four successors present).
```
zcat lsu0/canton.clog.gz | grep -aE 'LSU_MALFORMED_REQUEST' | head -1 | grep -aoE '"message":"[^"]{0,90}|"logger_name":"[^"]*"'
zcat lsu0/canton_network_test.clog.gz | grep -aE 'SV=sv4' | grep -a 'Not publishing sequencer successor' | grep -aoE '"@timestamp":"[^"]*","message":"[^"]{0,70}'
```
```
"message":"LSU_MALFORMED_REQUEST(8,f1402076): Invalid LSU request: No sequencer successor was found
"logger_name":"c.d.c.p.t.SequencerConnectionSuccessorListener:participant=sv2Participant"
"@timestamp":"2026-09-10T12:27:39.999Z","message":"Not publishing sequencer successor as we are past upgrade time
```
sv4 onboarded ~2 min behind sv1-3 and finished its LSU sequencer init at 12:27:39.999, ~43s past the
12:26:56.563 upgrade time, so LsuNodeInitializer (SV=sv4) skipped publishing its successor; the successor
listener then logged "No sequencer successor was found" 34x. Worth a separate look, but not what stalls bob.

## Summary

- A (cancelled shards): canton 3.6 rejects the LSU predecessor's StaticSynchronizerParameters -
  InvalidStaticSynchronizerParameters over non-default synchronizerLimits (LsuNodeInitializer.scala:98,
  canton SynchronizerParameters.scala:105); all 4 SVs abort init deterministically; log ends with no
  ScalaTest summary.
- B (failure): LsuIntegrationTest "upgrade synchronizer to new physical synchronizer without downtime".
  Root cause (validator-side): bob's init "Ensuring decentralized synchronizer registered" ->
  "Set the new synchronizer connection if required" picks the OLD psid ::36-0 to modify; the LSU
  activates the new psid ::36-2 and deactivates ::36-0, so the modify fails FAILED_PRECONDITION
  SYNC_SERVICE_SYNCHRONIZER_STATUS_NOT_ACTIVE and bob retries it forever (100ms, infinite) with no logic
  to re-pick the new active psid. The "Wait until observing registered on ::36-0" is just the 5-min outer
  timeout on top -> "Condition never became true within 5 minutes". Fix in the validator: re-resolve the
  active psid on NOT_ACTIVE. Separate/incidental in the same run: sv4 published its sequencer successor
  ~43s past the upgrade time (dropped from the pool) - real but not why bob hangs.
