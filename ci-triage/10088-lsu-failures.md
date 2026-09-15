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

## Summary

- A (cancelled shards): canton 3.6 rejects the LSU predecessor's StaticSynchronizerParameters -
  InvalidStaticSynchronizerParameters over non-default synchronizerLimits (LsuNodeInitializer.scala:98,
  canton SynchronizerParameters.scala:105); all 4 SVs abort init deterministically; log ends with no
  ScalaTest summary.
- B (failure): LsuIntegrationTest "upgrade synchronizer to new physical synchronizer without downtime" -
  bobValidatorLocal never observes the participant registered on source PSID ...::36-0 within 5 min;
  "Condition never became true within 5 minutes".
