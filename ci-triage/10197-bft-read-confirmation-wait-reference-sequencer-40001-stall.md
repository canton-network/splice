# 10197 - TrafficBasedRewardsSvAppTimeBasedIntegrationTest: the "only sv1 and sv4 confirm round 9" check waits 20 s while globalSequencerSv1's reference driver retries `insert block` on SQLSTATE 40001 for 19.6 s; sv4's confirmation lands 4 s late (run 35617315258)

Sibling of 10139 (family H third bullet): the reference block sequencer's `insert block` write on the shared
Postgres fails on serialization conflicts and backs off exponentially; here it did not kill anything, it delayed
one batch of confirmation responses by 19.6 s and a 20 s test budget expired 4 s early. All 11 other tests passed.
In the BFT-read scenario (sv3 stopped) the test needs sv1's and sv4's `CalculateRewardsV2` confirmations for round
9 within `eventually()` = 20 s. sv1's took 10.2 s end to end; sv4's took 25.0 s because the confirmation
responses for its command were accepted by globalSequencerSv1 at 15:27:24.37 to .78 and only ordered in block 3087 at
15:27:44.12: the driver's `insert block` failed 10 times in a row on `SQL state: 40001` with backoff growing 0.05 s
to 8.35 s. Not a regression of the head commit. The retry storm exists on 3.5.17 and 3.5.18 sim-time runs too but
never reached a backoff above 0.4 s there; here the maximum was 8.62 s.

- Run: https://github.com/canton-network/splice/actions/runs/35617315258, main 703a1df9fa ("Remove the general ip
  whitelisting setting (#7437)"), job 106391616207 `ci / scala_test_sim_time / simtime (3)`. Only failed job.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824 (`git show 703a1df9fa:nix/canton-sources.json`).
  Sequencers use the reference driver (`apps/app/src/test/resources/include/sequencers.conf:33`, `type = reference`),
  clock is `sim-clock` (`simple-topology-canton-simtime.conf:125`).
- Component: test budget (fix written) on top of a Canton reference-driver / Postgres contention issue (10139).

## 1. Failing assertion

```
gh api repos/canton-network/splice/actions/jobs/106391616207/logs > log/10197/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/10197/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded' | sed -E 's/^[^Z]*Z //' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g' log/10197/job.log | grep -a -m1 -A1 'take effect at round closure \*\*\* FAILED' | tail -1 | sed -E 's/^[^Z]*Z //' > log/10197/assertion.txt
grep -c "entityName='Confirmation'" log/10197/assertion.txt; tail -c 120 log/10197/assertion.txt
grep -oE '(digital-asset-2|Digital-Asset-Eng-[0-9])[-a-z0-9]*::1220[0-9a-f]{8}' log/10197/assertion.txt | sort -u
```
```
[info] *** 1 TEST FAILED ***
[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info] Tests: succeeded 11, failed 1, canceled 0, ignored 0, pending 0
1
... had size 1 instead of expected size 2 (TrafficBasedRewardsSvAppTimeBasedIntegrationTest.scala:493)
digital-asset-2-79e07f69::1220daa88be7          (sv1's party: the one confirmation present is sv1's)
```
The check (main at the run sha):
```
git show 703a1df9fa:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsSvAppTimeBasedIntegrationTest.scala | sed -n '484,494p'
```
```
          clue(s"Only sv1 and sv4 confirm round $round, so it is not yet processed") {
            eventually() {
              val startProcessingAction = new ARC_AmuletRules(
                new CRARC_StartProcessingRewardsV2(
                  new AmuletRules_StartProcessingRewardsV2(calculateRewardsCid, new Hash(rootHash))
                )
              )
              sv1Backend.appState.dsoStore
                .listConfirmations(startProcessingAction)
                .futureValue should have size 2
```
`eventually()` is the 20 s default. sv3 is stopped and sv2 is deliberately unable to confirm at this point, so the
count can only be 0, 1 or 2 here; waiting longer cannot make it wrong.

## 2. Timeline: the check ran 15:27:23.056 to 15:27:43.061; sv4's confirmation was created at 15:27:47.054

```
T=log/10197/logs-simtime-3/canton_network_test.clog.gz
zcat $T | grep -a -E "Running clue|Failed clue|advancing time by" | grep -a 'TrafficBasedRewardsSvApp' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | grep -a -A9 'Stop sv3' | cut -c1-120
zcat $T | grep -a -E 'SV=sv[14]' | grep -a 'CalculateRewardsTrigger' | grep -a -E 'created confirmation for CalculateRewardsV2 round 9' | sed -E 's/^\{"@timestamp":"([^"]+)".*(SV=sv[0-9]).*/\1 \2/'
```
```
2026-09-21T15:27:08.358Z Running clue: Stop sv3 to confirm we can perform bft read from sv1, sv4 only
2026-09-21T15:27:12.737Z advancing time by PT10M10S to 1970-01-01T01:41:40Z
2026-09-21T15:27:12.844Z Running clue: (check) waiting for open round automation (should create OpenMiningRound 12)
2026-09-21T15:27:23.048Z Running clue: Round 9 just closed: its CalculateRewardsV2 exists and sv1 serves root-hash
2026-09-21T15:27:23.056Z Running clue: Only sv1 and sv4 confirm round 9, so it is not yet processed
2026-09-21T15:27:43.061Z Failed clue: Only sv1 and sv4 confirm round 9, so it is not yet processed
2026-09-21T15:27:32.176Z SV=sv1 created confirmation for CalculateRewardsV2 round 9
2026-09-21T15:27:47.054Z SV=sv4 created confirmation for CalculateRewardsV2 round 9
```
Both scans had the round 9 root hash at 15:27:21.78 (`RewardComputationTrigger ... Computed rewards for round 9`
on sv1Scan and sv4Scan); both SV apps started their tasks at 15:27:21.4 to 21.7 after two "our own Scan has not yet
computed the root hash" retries. The difference is the ledger round trip of the confirmation command:
```
zcat $T | grep -a -E 'tid:(be4feeedf3c69361017679d652c221c0|cc1a7255e4421ad857a256090f8c8f4d)' | grep -a -E 'sending request|succeeded' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"Request \(tid:([0-9a-f]{4})[^)]*\) [^ ]+ to [^:]+:([0-9]+): ([a-z ]+).*/\1 \2 port \3 \4/'
```
```
2026-09-21T15:27:21.895Z be4f port 15101 sending request      (sv1)
2026-09-21T15:27:32.113Z be4f port 15101 succeeded            10.2 s
2026-09-21T15:27:22.061Z cc1a port 15401 sending request      (sv4)
2026-09-21T15:27:47.027Z cc1a port 15401 succeeded            25.0 s
```

## 3. Where sv4's 25 s went: confirmation responses accepted at 24.4-24.8, ordered at 44.1

```
C=log/10197/logs-simtime-3/canton-simtime.clog.gz
zcat $C | grep -a 'cc1a7255e4421ad857a256090f8c8f4d' | grep -a -E 'Phase [1-6]|Finalized|Got result' | <ts level node message> | awk '!seen[substr($0,25)]++'
zcat $C | grep -a -E '51b9f0de-102b-4093-8981-79344bc8b3d1|d8313026-6a25-4b6a-ad46-60cfb17069d3' | grep -a -E 'sends request|enqueued reference|Processing event at sc' | <ts node message>
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'sequenced at 1970-01-01T01:41:40\.00633[0-9]Z' | grep -a 'Block' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"(Block [0-9]+, chunk [0-9]+, request [0-9]+ sequenced at [^:]+:[^:]+:[0-9.]+Z).*/\1 \2/' | sort -u | head -2
```
```
15:27:22.070 sv4Participant  Phase 1 started (submit confirmation for round 9)
15:27:24.316 sv4Participant  Phase 3: Validating Transaction request=1970-01-01T01:41:40.005159Z
15:27:24.365 sv1Participant  Phase 4: Sending ... msgId=51b9f0de   -> globalSequencerSv1 'PAR::sv1' sends request 51b9f0de at 15:27:24.369, enqueued reference sequencer store request 24.370
15:27:24.775 sv4Participant  Phase 4: Sending ... msgId=d8313026   -> globalSequencerSv1 'PAR::sv4' sends request d8313026 at 15:27:24.777, enqueued 24.778
15:27:44.117 globalSequencerSv1  Block 3087, chunk 0, request 2 sequenced at 1970-01-01T01:41:40.006331Z   (51b9f0de)
15:27:44.118 globalSequencerSv1  Block 3087, chunk 0, request 4 sequenced at 1970-01-01T01:41:40.006333Z   (d8313026)
15:27:44.340 globalMediatorSv3   Phase 5: Received 1 response(s) for request=...005159Z
15:27:45.936 globalMediatorSv1   Phase 6: Finalized ... verdict Approve; "Delaying sending of verdict by 3000 ms"
15:27:46.660 sv4Participant  Got result for Transaction request ...005159Z; "timeout-result succeed successfully but slow after 22290 milliseconds"
```
The participants confirmed within 0.5 s of seeing the request. The 19.6 s is between the sequencer accepting the
responses and ordering them. Block production itself never paused (no gap above 2.1 s between `Processing block`
lines on globalSequencerSv1 in 15:27:20 to 15:27:50), and no BFT event is involved: this shard runs the reference
driver.

## 4. Why the reference driver held the batch: `insert block` SQLSTATE 40001 with exponential backoff

The driver groups accepted requests into a batch and stores it as a block; the responses were enqueued at 24.37 and
the batch was only created once the previous store had gone through:
```
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'Created batch reference-driver-requests-batch|enqueued reference sequencer store request with tag send' | <python: pair first enqueue with next batch; print batches in 15:27 with size and wait>
zcat $C | grep -a 'DbStorageSingle:sequencer=globalSequencerSv1' | grep -a -E 'insert block' | grep -a -E 'T15:27:(2[4-9]|3[0-9]|4[0-4])' | <ts message>
```
```
batch 15:27:24.274 size 5 wait 0.0s
batch 15:27:44.002 size 8 wait 19.6s      <- the responses
batch 15:27:44.265 size 3 wait 0.0s
15:27:24.360 The operation 'insert block' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after 0.05s
15:27:24.643 ... Retrying after 0.055s
15:27:24.806 ... Retrying after 0.11s
15:27:24.919 ... Retrying after 0.224s
15:27:25.198 ... Retrying after 0.564s
15:27:27.135 ... Retrying after 1.292s
15:27:28.439 ... Retrying after 2.312s
15:27:30.981 ... Retrying after 4.506s
15:27:35.629 ... Retrying after 8.349s
15:27:43.978 Now retrying operation 'insert block'.          (succeeds; batch created 15:27:44.002, block 3087 processed 15:27:44.116)
```
Every failure is `Detected an SQLException. SQL state: 40001` = PostgreSQL "could not serialize access due to
read/write dependencies among transactions". The other three global sequencers were in the same loop at the same
time (their `insert block` failures at 15:27:24.22 to 24.36 and onwards), which is what 10139 documented for the
wall-clock nightly. Across the whole run globalSequencerSv1 logged 24 to 105 such failures per minute:
```
zcat $C | grep -a 'DbStorageSingle:sequencer=globalSequencerSv1' | grep -a -c 'SQL state: 40001'; <per minute>
```
```
15:24:50 15:25:82 15:26:96 15:27:24 15:28:35 15:29:95 15:30:91 15:31:67 15:32:68 15:33:105 15:34:78 15:35:76 15:36:60 15:37:73 15:38:79 15:39:87 15:40:97 15:41:48 15:42:93
```
The vendored driver (canton 3.5.7-SNAPSHOT, `ReferenceSequencerDriver.scala:90`) batches with
`groupedWithin(n = config.maxBlockSize, d = config.maxBlockCutMillis.millis)` and stores each batch through
`DbStorage`, whose retry policy treats 40001 as transient with infinite retries and exponential backoff; the
running 3.6 jar carries the same `groupedWithin` and `reference-driver-requests-batch` strings. A batch cannot be
cut while the previous store is still retrying, so one long backoff chain delays everything queued behind it.

## 5. The storm is older than 3.6, its severity here is not

```
for d in log/10154/logs-simtime-0 log/10173/logs-simtime-2 log/10197/logs-simtime-3; do C=$(ls $d/canton-simtime*.clog.gz | grep -v out | head -1); echo "$d $(zcat $C | grep -a -m1 -oE 'Starting Canton version [0-9a-z.-]+') 40001=$(zcat $C | grep -a -c 'SQL state: 40001') max_backoff=$(zcat $C | grep -a -oE "'insert block' has failed with an exception. Retrying after [0-9.]+s" | grep -oE '[0-9.]+s$' | sort -g | tail -1)"; done
```
```
log/10154/logs-simtime-0  3.5.17                                 40001=485   max_backoff=0.19s
log/10173/logs-simtime-2  3.5.18                                 40001=3731  max_backoff=0.392s
log/10197/logs-simtime-3  3.6.0-snapshot.20260916.20284.0.vf27c4824  40001=4737  max_backoff=8.62s
```
Batch waits in this run (first enqueue to batch): median 0.11 s, p90 1.8 s, 37 batches over 5 s, 8 over 15 s,
maximum 74.2 s. The two earlier sim-time runs have the same median and p90 but their maxima are 35 s and 110 s, so
long waits are not new either; what differs is how deep the retry chain went while a test was on a 20 s clock.

## 6. Fix

Branch `ray/fix-10197-bft-read-confirmation-wait` (964e7df114, off main 703a1df9fa): the two-confirmation check
becomes `eventually(90.seconds)`, the budget the same file already uses at line 927 and the one #5779 chose for the
round helpers. Safe because the third confirmation is structurally impossible at that point (sv3 stopped, sv2's
`CalculateRewardsTrigger` paused and its scan answering CannotProvide), so a longer wait can only turn a false
failure into a pass. One line. Verified: ASCII-only diff, `apps-app/Test/scalafmtCheck` (README row). Not run.

The reference-driver contention itself is the 10139 item: four (here six, with splitwell) reference sequencers
writing serializable `insert block` transactions into one Postgres. Options remain as in 10139: fewer writers per
database, a driver that does not use SERIALIZABLE for the block insert, or a capped backoff.

## Verdict

- Family H third bullet's mechanism (reference sequencer `insert block` 40001 retry storm) surfacing as a slow
  ordering path rather than a teardown exit; new family L entry so it can be matched without the sys.exit.
- Flake. Test-side budget fix written; Canton / test-infra root cause open (10139).
- Not verified: why the backoff chain grows to 8 s on this snapshot and never above 0.4 s on 3.5.17/3.5.18 in the
  runs at hand (2 data points each); whether the sim clock changes DbStorage's retry timing.
