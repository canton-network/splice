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

## B5. Root cause: why the ::36-0 registration never appears (sv4 published its successor too late)

The wait never completes because the logical synchronizer upgrade is permanently short one sequencer:
sv4 never published its sequencer successor for the new synchronizer, so a validator migrating through
the upgrade (bob) cannot complete and hangs.

The upstream "no successor" error, and bob dropping sv4:
```
zcat lsu0/canton.clog.gz | grep -aE 'LSU_MALFORMED_REQUEST' | head -1 \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,90}|"logger_name":"[^"]*"'
zcat lsu0/canton.clog.gz | grep -acE 'LSU_MALFORMED_REQUEST'
```
```
"@timestamp":"2026-09-10T12:22:52.743Z"
"message":"LSU_MALFORMED_REQUEST(8,f1402076): Invalid LSU request: No sequencer successor was found
"logger_name":"c.d.c.p.t.SequencerConnectionSuccessorListener:participant=sv2Participant"
34
```
```
zcat lsu0/canton-standalone-lsu-bob-validator-after-upgrade.clog.gz | grep -aE 'Missing successor information' \
  | head -1 | sed -E 's/[0-9a-f]{16,}/<H>/g' | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,120}|"logger_name":"[^"]*"'
```
```
"@timestamp":"2026-09-10T12:29:06.157Z"
"message":"Missing successor information for the following sequencers: Set(Sequencer 'SEQ::sv4::<H>'). They will be removed from the pool of sequencers.
"logger_name":"c.d.c.p.s.AutomaticLogicalSynchronizerUpgrade:participant=extraStandaloneParticipant/lsu=36-2"
```

Why sv4 has no successor - it finished its LSU sequencer init AFTER the upgrade time and skipped
publishing (splice-side guard in LsuNodeInitializer, SV=sv4 only):
```
zcat lsu0/canton.clog.gz lsu0/canton_network_test.clog.gz | grep -aoE 'upgradeTime = 2026-09-10T[0-9:.]+Z' | sort | uniq -c
```
```
    196 upgradeTime = 2026-09-10T12:26:56.563832Z     # this test's upgrade time
    491 upgradeTime = 2026-09-10T13:22:29.282062Z     # a separate/later schedule, not this one
```
```
zcat lsu0/canton_network_test.clog.gz | grep -aE 'o\.l\.s\.s\.l\.LsuNodeInitializer:LsuIntegrationTest/config=9f4b1ae/SV=sv4' \
  | grep -aE '12:27:3[89]' | grep -aoE '"@timestamp":"[^"]*","message":"[^"]{0,80}'
```
```
"@timestamp":"2026-09-10T12:27:38.623Z","message":"Initializing sequencer from predecessor with StaticSynchronizerParameters(
"@timestamp":"2026-09-10T12:27:39.998Z","message":"Success: Initialize sequencer from the state of the predecessor, result is ()
"@timestamp":"2026-09-10T12:27:39.999Z","message":"Not publishing sequencer successor as we are past upgrade time
```
sv4 was ~2 minutes behind sv1-3 (still onboarding: "Requesting to be onboarded via the sponsor SV"
12:21:38, "Check if sequencer is initialized failed with a retryable error" / "Detected an error"
12:21:43). Its LSU sequencer init finished at 12:27:39.999, ~43s after the 12:26:56.563 upgrade time,
so LsuNodeInitializer skipped publishing sv4's successor. sv1/sv2/sv3 reached the same step in time
(~12:26:56-58).

Causal chain:
1. LSU "upgrade without downtime" scheduled with upgrade time 12:26:56.563.
2. sv4 was slow to onboard/init (~2 min behind sv1-3).
3. sv4's LsuNodeInitializer finished its sequencer init at 12:27:39.999 (past the upgrade time) and
   logged "Not publishing sequencer successor as we are past upgrade time" -> sv4 has no successor.
4. SequencerConnectionSuccessorListener (sv2Participant) logs "No sequencer successor was found" 34x
   (12:22:52-12:26:56); bob's participant drops sv4 ("Missing successor information", 12:29:06).
5. bob's participant cannot complete its migration to the successor synchronizer; its init waits 263x
   for the ::36-0 registration (12:29:06-12:34:07), never observes it, and times out after 5 min.

This is splice-side (LsuNodeInitializer), not a Canton bug. The design question: splice should either hold
the upgrade until all SVs' successors are published, or a migrating validator should tolerate/recover a
missing sequencer successor rather than hang for 5 minutes.

## Summary

- A (cancelled shards): canton 3.6 rejects the LSU predecessor's StaticSynchronizerParameters -
  InvalidStaticSynchronizerParameters over non-default synchronizerLimits (LsuNodeInitializer.scala:98,
  canton SynchronizerParameters.scala:105); all 4 SVs abort init deterministically; log ends with no
  ScalaTest summary.
- B (failure): LsuIntegrationTest "upgrade synchronizer to new physical synchronizer without downtime".
  Root cause: sv4 onboarded ~2 min late and finished its LSU sequencer init ~43s after the 12:26:56.563
  upgrade time, so splice's LsuNodeInitializer skipped publishing sv4's sequencer successor ("Not
  publishing sequencer successor as we are past upgrade time"). The upgrade is then permanently short
  sv4's sequencer ("No sequencer successor was found" 34x); bob's participant drops sv4 and cannot
  complete its migration, so bobValidatorLocal's init waits 5 min for the ::36-0 registration that never
  appears -> "Condition never became true within 5 minutes". Splice-side timing race, not a Canton bug.
