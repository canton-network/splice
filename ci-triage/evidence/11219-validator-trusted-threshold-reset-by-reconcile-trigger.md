# 11219 - ValidatorSequencerConnectionIntegrationTest: configured trust threshold 2 overwritten with 1 by ReconcileSequencerConnectionsTrigger

Production bug, surfaced by a timing window in the test. The validator's `trustedSynchronizerConfig.threshold` is applied
only when the validator first registers the synchronizer (SynchronizerConnector). ReconcileSequencerConnectionsTrigger
rebuilds the sequencer connections with the f+1 threshold (`Thresholds.sequencerConnectionsSizeThreshold`, 1 for 2 or
3 sequencers) whenever the endpoint set changes, and never restores the configured value. In this run the trigger's
first pass used a lagging synchronizer time, dropped sv2 (not yet available at that time), and the configured threshold
was lost for good.

- Filed by hand from canton-network/splice#8261 (adetokunbo). Run 41821374201, attempt 1, job 126545709851
  `wall-clock-time (3)`, PR branch `adetokunbo/scan-tbar-v1-1-compute` 121e286893 (PR #7761, scan reward storage,
  unrelated: touches only apps/scan and SummarizingMiningRoundTrigger). Passed on attempt 2.
- Artifact logs-wall-clock-time-3 (13320335468), in `log/11219/art/`; test log of the suite in `log/11219/suite.clog`.
- Component: validator app, `apps/validator/.../automation/ReconcileSequencerConnectionsTrigger.scala`. Same code on main.

## 1. The failure

```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/11219/job.log | grep -aE 'FAILED|was not equal to 2|Tests: succeeded'
```
```
[info] - validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers *** FAILED ***
[info]   Validator should connect to the filtered list of sequencers from the config 1 was not equal to 2 (ValidatorSequencerConnectionIntegrationTest.scala:112)
[info] Tests: succeeded 27, failed 1, canceled 0, ignored 0, pending 0
```
The test configures aliceValidator with `svNames = sv1, sv2, sv3` and `threshold = 2`. The URL set matched (line 106
passed); the threshold read back from the participant was 1 for the whole 120 s.

## 2. Threshold per sequencer-connection change on aliceValidator

```
jq -r 'select(.logger_name|test("aliceValidator")) | select(.message|test("^For synchronizer Synchronizer .global. at time"))
  | .["@timestamp"][11:23]+" "+(.message|.[0:75])+" | "+(.message|[scan("SEQ::(sv[0-9])")|.[0]]|join(","))' log/11219/suite.clog
jq -r '... select(.message|test("^(Ensuring domain Synchronizer .global|Updating to new synchronizer connection config ...))
  | ... [scan("sequencer trust threshold = ([0-9]+)")] ...' log/11219/suite.clog
```
```
12:00:27.491 For synchronizer Synchronizer 'global' at time 2026-11-04T12:00:27.486534Z  | sv1,sv2,sv3
12:00:27.493 Ensuring domain Synchronizer 'global' registered with config ...             threshold 2
12:00:28.347 Starting trigger org.lfdecentralizedtrust.splice.validator.automation.ReconcileSequencerConnectionsTrigger
12:00:28.365 For synchronizer Synchronizer 'global' at time 2026-11-04T12:00:19.020727Z  | sv1,sv3
12:00:28.381 modifying sequencers connections to SequencerConnections(   (sv1, sv3; sequencer trust threshold = 1)
12:00:29.728 For synchronizer Synchronizer 'global' at time 2026-11-04T12:00:29.543819Z  | sv1,sv2,sv3
12:00:29.735 modifying sequencers connections to SequencerConnections(   (sv1, sv2, sv3; sequencer trust threshold = 1)
```
(Columns after the message prefix are extracted fields, not log text.) No further modification until the test failed
at 12:02:47.420: from 12:00:29 on the endpoint set equals the configured one, so the trigger does nothing.

Why sv2 was dropped at 12:00:28.365: scan's `availableAfter` for sv2 (Digital-Asset-Eng-2) is
`2026-11-04T12:00:25.391578Z`, after the time the trigger used (12:00:19.02, the participant's synchronizer time lower
bound right after connecting; SynchronizerConnector at init uses `clock.now`, 12:00:27.49):
```
grep -a 'dso-sequencers' log/11219/suite.clog | grep -a 'Received response' | tail -1 | jq -r .message | grep -oE '"(svName|availableAfter)":"[^"]*"' | paste - -
```
```
"svName":"Digital-Asset-2"	"availableAfter":"2026-11-04T11:58:57.655444Z"
"svName":"Digital-Asset-Eng-2"	"availableAfter":"2026-11-04T12:00:25.391578Z"
"svName":"Digital-Asset-Eng-3"	"availableAfter":"2026-11-04T12:00:15.311125Z"
"svName":"Digital-Asset-Eng-4"	"availableAfter":"2026-11-04T12:00:14.74043Z"
```
The test waits until all four sequencers are available by the environment clock (line 80-91) and then starts alice; here
sv2 became available only 2 s before alice started, inside the trigger's lag window. Usually the margin is larger, the
trigger's first set equals the init set and nothing is modified, so the test passes.

## 3. The code (run sha 121e286893; unchanged on main)

SynchronizerConnector.scala (init path), lines 231-236:
```scala
val threshold: PositiveInt =
  config.domains.global.trustedSynchronizerConfig match {
    case Some(config) => PositiveInt.tryCreate(config.threshold)
    case None =>
      Thresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size)
  }
```
ReconcileSequencerConnectionsTrigger.scala, lines 101-103 (no trustedSynchronizerConfig anywhere in the file):
```scala
SequencerConnections.tryMany(
  nonEmptyConnections.forgetNE,
  Thresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size),
```
and `modifySequencerConnections` only compares endpoint sets (`differentEndpointSet`), so a wrong threshold with the
right endpoints is never corrected. The amplification factor differs the same way (init: max(config threshold, f+1
amplification); trigger: f+1 amplification only).

Consequence outside tests: any change of the trusted SVs' sequencer list (URL change, a sequencer becoming available,
an SV leaving) silently lowers a validator's configured trust threshold to f+1 of the trusted subset, until the
validator restarts. The test's second half (sv2 URL change, line 115-130) triggers exactly this every run but does not
check the threshold afterwards.

History: `git log -S trustedSynchronizerConfig -- apps/validator/src/main/scala` -> 5473b2614d "Support configurable
threshold for trusted sequencers (#3512)", pasindutennage-da, 2026-01-14; it added the threshold to the init path only.
The trigger was last changed in #7523 (Canton bump) and #5989/#5798 (nicu-da), none touching the threshold.

## 4. Duplicates

`gh search issues -R DACH-NY/cn-test-failures ValidatorSequencerConnectionIntegrationTest`: 8134 (2026-04, CommandFailure),
7902 (2026-04, sequencers not available), both closed, different signatures. No family in known-families.md,
nothing in INDEX.tsv. First hit of this signature.

## Verdict

New, production bug in ReconcileSequencerConnectionsTrigger (ignores `trustedSynchronizerConfig.threshold`), test flake
only as the symptom. Fix: build the trigger's SequencerConnections with the same threshold and amplification as
SynchronizerConnector (share one helper), and compare the threshold too when deciding whether to modify, so an
existing wrong value is repaired; extend the test to assert threshold 2 after the sv2 URL change (that makes the test
fail deterministically without the fix). Owner: pasindutennage-da (#3512).
Not verified: behaviour on a deployed validator (no cluster logs checked).
