# 11324, 11325, 11332 - simtime on main after the Canton 3.7.3 bump: 11255 lost-verdict shape; a setup-step failure skips the topology reset and fails every later suite of the shard (runs 42766394442, 42790498517)

DUPLICATE of 11255 (lost-verdict shape, family B), all three. Post-merge main runs on 2026-11-10, Canton
3.7.3-snapshot.20261109.20894.0.ve6e6c252 (new since the earlier 11255 hits on 3.7.2; same log lines, same
mechanism). In each shard the first suite grows the ordering topology 1 -> 2 -> 4; sv4 (a newcomer of the 2 -> 4
step) is blacklisted for 3 epochs, so globalMediatorSv4 cannot send its verdicts (`Mempool received client request but
this node is currently blacklisted`); the mediator group already has 4 active members with threshold 2, one of the
other mediators sends and the other delays its verdict 3000 ms on a sim clock that does not advance, so some
requests get 1 of 2 results and never finish on any participant. The SV participants' indexers stop at the first such
request and the app waiting on a later command times out. New compared with the earlier hits: when this happens in a
suite whose 4-SV setup runs in `EnvironmentSetup` "Running setup" (TrafficBasedRewardsSvAppTimeBasedIntegrationTest in
11324 and 11332), the failure path only closes the fixture and never runs the plugins' `beforeEnvironmentDestroyed`,
so `ResetDecentralizedNamespace` never resets the DSO namespace (stays at serial 4, threshold 3, 4 owners) and the sim
clock never passes the stuck requests' decision time. Every later suite of the shard then fails: sv1 cannot
re-allocate the DSO party (`Not fully authorized`), the SV participants' indexers stay frozen; in 11332 seven more
suites time out, in 11324 two more time out (the second leaves its environment open), so the last three fail at
`Creating fixture` with `Address already in use` (family H teardown leak cascade, 10176). 11325 failed inside a test
of SvTimeBasedRoundMgmtIntegrationTest; there the resets ran, the next test advanced sim time, and the shard
recovered.

- Runs: https://github.com/canton-network/splice/actions/runs/42766394442 (main 39ef05f244, "Fix bug in
  issuencePerFeaturedAppRewardCoupon computation (#8374)"), jobs 129664076593 `simtime (0)` (11324) and 129664076754
  `simtime (4)` (11325); https://github.com/canton-network/splice/actions/runs/42790498517 (main 6c0fee0505, "add back
  unused cometbft config (#8390)"), job 129742770623 `simtime (3)` (11332). All attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (both shas).
- Component: Canton BFT ordering / mediator verdict sending under sim time (11255); test framework (setup-failure
  path skips the topology reset plugins).

## 0. Issues (verbatim)

```
== 11324
GHA Run 42766394442 : job simtime (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42766394442).
Job: simtime (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [39ef05f](https://github.com/canton-network/splice/commit/39ef05f244d63ec6e6fd914501a1ceac740ddd76) Fix bug in issuencePerFeaturedAppRewardCoupon computation (#8374)
Actor: meiersi-da

== 11325
GHA Run 42766394442 : job simtime (4) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42766394442).
Job: simtime (4) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [39ef05f](https://github.com/canton-network/splice/commit/39ef05f244d63ec6e6fd914501a1ceac740ddd76) Fix bug in issuencePerFeaturedAppRewardCoupon computation (#8374)
Actor: meiersi-da

== 11332
GHA Run 42790498517 : job simtime (3) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42790498517).
Job: simtime (3) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [6c0fee0](https://github.com/canton-network/splice/commit/6c0fee0505b654bd27ffadddf32c1b0a913feaf2) add back unused cometbft config (#8390)
Actor: moritzkiefer-da
```

## 1. Jobs and canton pin

Recipe 1 (one failed job each):
```
for n in 11324 11325 11332; do RUN=...; export NAME='simtime (N)'; gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'; done
```
```
11324 run=42766394442 name=simtime (0): 129664076593 attempt=1 ci / scala_test_sim_time / simtime (0);
11325 run=42766394442 name=simtime (4): 129664076754 attempt=1 ci / scala_test_sim_time / simtime (4);
11332 run=42790498517 name=simtime (3): 129742770623 attempt=1 ci / scala_test_sim_time / simtime (3);
```
```
cd <splice>; for s in 39ef05f244 6c0fee0505 9dafafbece bd9953a523; do echo "$s $(git show $s:nix/canton-sources.json | jq -c '{version}')"; done
```
```
39ef05f244 {"version":"3.7.3-snapshot.20261109.20894.0.ve6e6c252"}
6c0fee0505 {"version":"3.7.3-snapshot.20261109.20894.0.ve6e6c252"}
9dafafbece {"version":"3.7.2"}
bd9953a523 {"version":"3.7.2"}
```
(9dafafbece and bd9953a523 are the shas of 11287 and 11288, earlier hits of 11255.)

## 2. Classification (job logs)

```
for n in 11324 11325 11332; do sed 's/\x1b\[[0-9;]*m//g' log/$n/job.log | grep -a -A6 '\*\*\* FAILED \*\*\*' | cut -c30-330; done
```
(excerpt, first occurrence per test)
```
=== 11324
[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
...
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
[info] - expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts *** FAILED ***
[info]   java.lang.RuntimeException: The app state of sv2Scan is currently not accessible. Node doesn't have any app state.
[info] - amulet price votes and metrics *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
=== 11325
[info] - round management *** FAILED ***
[info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
...
[info]   at org.lfdecentralizedtrust.splice.console.ValidatorAppReference.waitForInitialization(ValidatorAppReference.scala:52)
[info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$initDso$3(SpliceTests.scala:313)
=== 11332
[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info] - CIP-104 reward accounting pipeline works *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
```
Summaries: 11324 `Tests: succeeded 0, failed 3`; 11325 `Tests: succeeded 10, failed 1`; 11332 `Tests: succeeded
0, failed 2` (setup failures of suites with zero started tests are not counted). Assertion failures with a report,
no checkErrors involvement.

## 3. Suite timelines

Artifacts `logs-simtime-0` (11324), `logs-simtime-4` (11325), `logs-simtime-3` (11332) in `log/<ref>/`.
```
for f in log/11324/logs-simtime-0 log/11332/logs-simtime-3; do echo "== $f"; zcat $f/canton_network_test.clog.gz | jq -r 'select(.message|test("^Starting test suite|^Test (succeeded|failed)|Timeout while waiting for initialization|^Suite aborted|aborted")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:50]) | \(.message|gsub("\\s+";" ")|.[0:330])"' | grep -v 'Ignoring init'; done
```
```
== log/11324/logs-simtime-0
06:23:00.277 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
06:28:47.070 ERROR o.l.s.c.SvAppBackendReference:TrafficBasedRewardsS | Timeout while waiting for initialization of sv3
06:28:47.411 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:234), location: SeeStackDepthException
06:28:47.590 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'WalletMintingDelegationTimeBasedIntegrationTest'...
06:34:01.503 ERROR o.l.s.c.SvAppBackendReference:WalletMintingDelegat | Timeout while waiting for initialization of sv1
06:34:02.126 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvTimeBasedOnboardingIntegrationTest'...
06:39:05.423 ERROR o.l.s.c.ScanAppBackendReference:SvTimeBasedOnboard | Timeout while waiting for initialization of sv1Scan
06:39:05.444 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'SvTimeBasedOnboardingIntegrationTest/expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts', message: The app state of sv2Scan is currently not accessible. Node doesn't have any app state., location: SeeStackDepthException
06:39:06.034 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvTimeBasedAmuletPriceIntegrationTest'...
06:39:06.074 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'SvTimeBasedAmuletPriceIntegrationTest/amulet price votes and metrics', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146), location: SeeStackDepthException
06:39:06.091 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'...
06:39:06.403 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvTimeBasedRewardCouponMissingPartyIntegrationTest'...
== log/11332/logs-simtime-3
09:37:51.331 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
09:43:37.823 ERROR o.l.s.c.SvAppBackendReference:TrafficBasedRewardsS | Timeout while waiting for initialization of sv3
09:43:45.341 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:234), location: SeeStackDepthException
09:43:45.422 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'TrafficBasedRewardsTimeBasedIntegrationTest'...
09:49:02.389 ERROR o.l.s.c.SvAppBackendReference:TrafficBasedRewardsT | Timeout while waiting for initialization of sv1
09:49:02.444 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'TrafficBasedRewardsTimeBasedIntegrationTest/CIP-104 reward accounting pipeline works', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:234), location: SeeStackDepthException
09:49:02.464 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...
09:54:36.055 ERROR o.l.s.c.SvAppBackendReference:SvExpiredRewardsColl | Timeout while waiting for initialization of sv3
09:54:36.147 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'TokenStandardMetadataTimeBasedIntegrationTest'...
09:59:50.040 ERROR o.l.s.c.SvAppBackendReference:TokenStandardMetadat | Timeout while waiting for initialization of sv1
09:59:50.101 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'FeaturedAppRightSwitchOverTimeBasedIntegrationTest'...
10:05:03.977 ERROR o.l.s.c.SvAppBackendReference:FeaturedAppRightSwit | Timeout while waiting for initialization of sv1
10:05:04.045 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvTimeBasedRewardCouponMissingPartyIntegrationTest'...
10:10:17.930 ERROR o.l.s.c.SvAppBackendReference:SvTimeBasedRewardCou | Timeout while waiting for initialization of sv1
10:10:17.981 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'WalletAmuletPriceTimeBasedIntegrationTest'...
10:15:32.077 ERROR o.l.s.c.SvAppBackendReference:WalletAmuletPriceTim | Timeout while waiting for initialization of sv1
```
11325 (same command with `Initialize app failed` added; excerpt):
```
06:19:11.865 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...
06:27:21.582 ERROR o.l.s.c.ValidatorAppBackendReference:SvTimeBasedRoundMgmtIntegrationTe | Timeout while waiting for initialization of sv3Validator
06:27:37.375 ERROR c.d.c.LogReporter:reporter=scala-test | Test failed: 'SvTimeBasedRoundMgmtIntegrationTest/round management', message: Condition never became true within 5 minutes, location: SeeStackDepthException
06:28:58.174 INFO c.d.c.LogReporter:reporter=scala-test | Test succeeded: 'SvTimeBasedRoundMgmtIntegrationTest/round management with scheduled config change of doubled tickDuration'
06:31:06.260 INFO c.d.c.LogReporter:reporter=scala-test | Test succeeded: 'SvTimeBasedRoundMgmtIntegrationTest/round management with scheduled config change of reduced tickDuration'
06:32:23.327 INFO c.d.c.LogReporter:reporter=scala-test | Test succeeded: 'SvTimeBasedRoundMgmtIntegrationTest/round management with very tightly scheduled config'
06:32:23.410 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'TrafficBasedRewardsTimeBasedIntegrationTest'...
06:39:16.056 INFO c.d.c.LogReporter:reporter=scala-test | Test succeeded: 'TrafficBasedRewardsTimeBasedIntegrationTest/CIP-104 reward accounting pipeline works'
... (WalletTimeBased, TokenStandardMetadataTimeBased, SvTimeBasedBootstrappingRound, FeaturedAppRightSwitchOverTimeBased: all succeeded)
```

## 4. Epoch tables (globalSequencerSv1)

```
cat > log/ghtmp/epochs.jq <<'EOF'
select(.logger_name|test("globalSequencerSv1")) | select(.message|test("^New epoch"))
| (.message|capture("New epoch (?<e>[0-9]+)").e) as $e
| (.message|capture("leaders = List\\((?<l>[^)]*)\\)").l | [scan("SEQ::(sv[0-9]+)::")|.[0]] | join(",")) as $l
| (.message|capture("blacklisted nodes = List\\((?<b>[^)]*)\\)").b | [scan("SEQ::(sv[0-9]+)::")|.[0]] | join(",")) as $b
| (.message|capture("size = (?<s>[0-9]+)").s) as $s
| "\(.["@timestamp"][11:23]) \($e) size=\($s) leaders=\($l) bl=\($b)"
EOF
for f in log/11324/logs-simtime-0 log/11325/logs-simtime-4 log/11332/logs-simtime-3; do echo "== $f"; zcat $f/canton-simtime.clog.gz | jq -r -f log/ghtmp/epochs.jq | awk '{k=$3" "$4" "$5; if(k!=p){print; p=k}}'; done
```
(excerpt; after the blacklist ends every epoch has size 4 and no blacklisted node until the end of the log)
```
== log/11324/logs-simtime-0
06:24:21.366 0 size=1 leaders=sv1 bl=
06:26:53.951 14 size=2 leaders=sv1,sv2 bl=
06:27:03.084 15 size=2 leaders=sv2,sv1 bl=
06:27:10.984 16 size=4 leaders=sv1,sv2,sv3,sv4 bl=
06:27:21.302 17 size=4 leaders=sv3,sv1,sv2 bl=sv4
06:27:34.502 18 size=4 leaders=sv1,sv2,sv3 bl=sv4
06:27:49.475 19 size=4 leaders=sv2,sv3,sv1 bl=sv4
06:28:04.377 20 size=4 leaders=sv1,sv2,sv3,sv4 bl=
== log/11325/logs-simtime-4
06:19:54.993 0 size=1 leaders=sv1 bl=
06:21:43.446 13 size=2 leaders=sv3,sv1 bl=
06:21:50.999 14 size=2 leaders=sv1,sv3 bl=
06:21:58.390 15 size=4 leaders=sv4,sv1,sv2,sv3 bl=
06:22:07.882 16 size=4 leaders=sv2,sv3,sv1 bl=sv4
06:22:18.563 17 size=4 leaders=sv3,sv1,sv2 bl=sv4
06:22:31.296 18 size=4 leaders=sv1,sv2,sv3 bl=sv4
06:22:46.270 19 size=4 leaders=sv4,sv1,sv2,sv3 bl=
== log/11332/logs-simtime-3
09:39:10.148 0 size=1 leaders=sv1 bl=
09:41:30.397 13 size=2 leaders=sv3,sv1 bl=
09:41:38.563 14 size=2 leaders=sv1,sv3 bl=
09:41:46.215 15 size=4 leaders=sv4,sv1,sv2,sv3 bl=
09:41:55.404 16 size=4 leaders=sv2,sv3,sv1 bl=sv4
09:42:07.683 17 size=4 leaders=sv3,sv1,sv2 bl=sv4
09:42:22.453 18 size=4 leaders=sv1,sv2,sv3 bl=sv4
09:42:37.204 19 size=4 leaders=sv4,sv1,sv2,sv3 bl=
```
1 -> 2 (sv2 in 11324, sv3 in 11325/11332), then 2 -> 4; sv4 blacklisted for 3 epochs in all three. Ordering keeps
running (an epoch every ~11 s until the end of each log), so the stall is not in ordering.

## 5. Family check: lost-verdict shape, not rejected-send

Confirming greps of family B (11255 shapes) on each canton log:
```
for f in log/11324/logs-simtime-0 log/11325/logs-simtime-4 log/11332/logs-simtime-3; do echo "== $f"; zcat $f/canton-simtime.clog.gz | awk '/Failed to send result/{a++} /Rejecting submission request by/ && /P2P connectivity is not ready/{b++} /Delaying/ && /mediator/ {c++} /Another flush cycle/{d++} END{print "FailedToSendResult="a+0, "RejectP2P="b+0, "mediatorDelaying="c+0, "flushCycle="d+0}'; done
```
```
== log/11324/logs-simtime-0
FailedToSendResult=4 RejectP2P=0 mediatorDelaying=9 flushCycle=17
== log/11325/logs-simtime-4
FailedToSendResult=11 RejectP2P=0 mediatorDelaying=825 flushCycle=17
== log/11332/logs-simtime-3
FailedToSendResult=7 RejectP2P=0 mediatorDelaying=13 flushCycle=25
```
No `P2P connectivity is not ready` rejections (rules out the rejected-send shape); mediator `Failed to send result`
present. All failed sends are globalMediatorSv4 refused while blacklisted:
```
for f in ...; do zcat $f/canton-simtime.clog.gz | grep -a 'Failed to send result' | jq -r '"\(.["@timestamp"][11:23]) \(.level) \(.logger_name|sub("^.*mediator=";"mediator=")|.[0:40]) | \(.message|gsub("\\s+";" ")|.[0:300])"' | head -6; done
```
(first line per shard)
```
06:27:22.600 ERROR mediator=globalMediatorSv4/psid=global-d | Failed to send result to sequencer for request 1970-01-01T00:00:00.280Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,872e35d5): Mempool received client request but this node is currently blacklisted, reje
06:22:10.322 ERROR mediator=globalMediatorSv4/psid=global-d | Failed to send result to sequencer for request 1970-01-01T00:00:00.343Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,c36e7c47): Mempool received client request but this node is currently blacklisted, reje
09:41:57.981 ERROR mediator=globalMediatorSv4/psid=global-d | Failed to send result to sequencer for request 1970-01-01T00:00:00.265001Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,b784e7a5): Mempool received client request but this node is currently blacklisted, r
```
Mediator group at that time (threshold 2 with 4 active, so a verdict needs two senders):
```
for r in 11324:logs-simtime-0 11325:logs-simtime-4 11332:logs-simtime-3; do ...; zcat log/$n/$d/canton-simtime.clog.gz | grep -a 'MediatorSynchronizerState' | jq -r 'select(.logger_name|test("sequencer=globalSequencerSv1")) | (.message|gsub("\\s+";" ")) as $m | ($m|[scan("MediatorSynchronizerState\\([^)]*active = Seq\\([^)]*\\)[^)]*")]|.[0]) as $s | select($s!=null) | "\(.["@timestamp"][11:23]) \($s|gsub("1220[0-9a-f]+(\\.\\.\\.)?";"")|.[0:200])"' | awk '{k=substr($0,14); if(!(k in seen)){seen[k]=1; print}}'; done
```
```
== 11324
06:27:03.657 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv2::)
06:27:06.992 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv2::, MED::sv4::) 
06:27:07.027 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv2::, MED::sv4::)
06:27:10.179 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 2, active = Seq(MED::sv1::, MED::sv2::, MED::sv4::, MED::sv3::) 
== 11325
06:21:43.361 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv3::)
06:21:53.574 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv3::, MED::sv4::) 
06:21:53.576 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv3::, MED::sv4::)
06:22:08.464 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 2, active = Seq(MED::sv1::, MED::sv3::, MED::sv4::, MED::sv2::) 
== 11332
09:41:39.016 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv3::)
09:41:40.297 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv3::, MED::sv4::) 
09:41:40.299 MediatorSynchronizerState(synchronizerId = global-domain::, group = 0, threshold = 1, active = Seq(MED::sv1::, MED::sv3::, MED::sv4::)
09:41:44.580 MediatorSynchronizerState( synchronizerId = global-domain::, group = 0, threshold = 2, active = Seq(MED::sv1::, MED::sv3::, MED::sv4::, MED::sv2::)
```
Per refused request, which mediators sent and which delayed (verdict events into a TSV, then joined on the request id
and on the trace id of the `Delaying` line):
```
for r in 11324:logs-simtime-0 11325:logs-simtime-4 11332:logs-simtime-3; do n=${r%%:*}; d=${r#*:}; zcat log/$n/$d/canton-simtime.clog.gz | grep -a -E 'Sent result for request|Failed to send result|Delaying sending of verdict|Phase 6: Finalized request' | jq -r 'select(.logger_name|test("mediator=globalMediatorSv")) | "\(.["@timestamp"][11:23])\t\(.logger_name|capture("mediator=globalMediator(?<n>Sv[0-9])").n)\t\(.["trace-id"])\t\(.message|gsub("\\s+";" ")|.[0:110])"' > log/$n/mediator-verdicts.tsv; done
cat > log/ghtmp/verdicts.awk <<'EOF'
BEGIN{FS="\t"}
{ m=$4
  if (match(m,/request=RequestId\(1970[^)]*\)/)) { id=substr(m,RSTART+18,RLENGTH-19); tr[$3]=id; fin[id]=fin[id] " " $2 "@" $1 }
  else if (match(m,/Sent result for request 1970[^ ]*/)) { id=substr(m,RSTART+24,RLENGTH-24); sent[id]=sent[id] " " $2 "@" $1 }
  else if (match(m,/Failed to send result to sequencer for request 1970[^ ]*/)) { id=substr(m,RSTART+47,RLENGTH-47); failed[id]=failed[id] " " $2 "@" $1 }
  else if (m ~ /Delaying sending of verdict/) { dl[$3]=dl[$3] " " $2 "@" $1 }
}
END{ for (t in dl) if (t in tr) delay[tr[t]]=delay[tr[t]] dl[t]
     for (id in failed) print id " | failed:" failed[id] " | sent:" sent[id] " | delayed:" delay[id] }
EOF
for n in 11324 11325 11332; do echo "== $n"; awk -f log/ghtmp/verdicts.awk log/$n/mediator-verdicts.tsv | sort; done
```
(rows with only one sender; the other rows have two senders)
```
== 11324
1970-01-01T00:00:00.280001Z | failed: Sv4@06:27:22.605 | sent: Sv2@06:27:22.052 | delayed: Sv1@06:27:21.815
1970-01-01T00:00:00.280Z | failed: Sv4@06:27:22.600 | sent: Sv1@06:27:22.049 | delayed: Sv2@06:27:21.797
== 11325
1970-01-01T00:00:00.343Z | failed: Sv4@06:22:10.322 | sent: Sv1@06:22:09.235 | delayed: Sv3@06:22:08.792
== 11332
1970-01-01T00:00:00.265001Z | failed: Sv4@09:41:57.981 | sent: Sv3@09:41:46.198 | delayed: Sv1@09:41:45.667
```
The delayed verdict is never sent (no later `Sent result` for these ids up to the end of each log; the fourth
mediator, the newest member, has no `Finalized` line for them). The 11324 lines in full:
```
F=log/11324/logs-simtime-0/canton-simtime.clog.gz; zcat $F | grep -a -E '00:00:00\.280(001)?Z' | jq -r 'select(.logger_name|test("participant=|mediator=")) | select(.message|test("Phase 2: Registered|Phase 6|Phase 7|About to wrap up|Sent result|Failed to send|imeout")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>(participant|mediator)=[A-Za-z0-9]+)").n) | \(.message|gsub("\\s+";" ")|.[0:150])"'
zcat $F | grep -a 'Delaying sending of verdict' | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<n>mediator=[A-Za-z0-9]+)").n) trace=\(.["trace-id"]) | \(.message)"' | head -12
```
```
06:27:10.533 INFO mediator=globalMediatorSv1 | Phase 2: Registered request=1970-01-01T00:00:00.280Z from submittingParticipant=PAR::sv2::12208dc537fd... with 1 view(s).
06:27:10.535 INFO mediator=globalMediatorSv2 | Phase 2: Registered request=1970-01-01T00:00:00.280Z from submittingParticipant=PAR::sv2::12208dc537fd... with 1 view(s).
06:27:10.575 INFO mediator=globalMediatorSv2 | Phase 2: Registered request=1970-01-01T00:00:00.280001Z from submittingParticipant=PAR::sv2::12208dc537fd... with 1 view(s).
06:27:10.575 INFO mediator=globalMediatorSv1 | Phase 2: Registered request=1970-01-01T00:00:00.280001Z from submittingParticipant=PAR::sv2::12208dc537fd... with 1 view(s).
06:27:21.794 INFO mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.280Z) with verdict Approve at 1970-01-01T00:01:00.280Z
06:27:21.795 INFO mediator=globalMediatorSv2 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.280Z) with verdict Approve at 1970-01-01T00:01:00.280Z
06:27:21.814 INFO mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.280001Z) with verdict Approve at 1970-01-01T00:01:00.280001Z
06:27:21.814 INFO mediator=globalMediatorSv2 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.280001Z) with verdict Approve at 1970-01-01T00:01:00.280001Z
06:27:22.049 DEBUG mediator=globalMediatorSv1 | Sent result for request 1970-01-01T00:00:00.280Z
06:27:22.052 DEBUG mediator=globalMediatorSv2 | Sent result for request 1970-01-01T00:00:00.280001Z
06:27:22.450 INFO mediator=globalMediatorSv4 | Phase 2: Registered request=1970-01-01T00:00:00.280Z from submittingParticipant=PAR::sv2::12208dc537fd... with 1 view(s).
06:27:22.454 INFO mediator=globalMediatorSv4 | Phase 2: Registered request=1970-01-01T00:00:00.280001Z from submittingParticipant=PAR::sv2::12208dc537fd... with 1 view(s).
06:27:22.537 INFO mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.280Z) with verdict Approve at 1970-01-01T00:01:00.280Z
06:27:22.566 INFO mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.280001Z) with verdict Approve at 1970-01-01T00:01:00.280001Z
06:27:22.600 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:00:00.280Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer
06:27:22.605 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:00:00.280001Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer

06:27:21.797 mediator=globalMediatorSv2 trace=872e35d5015ee96c9881c4275b9fc4f8 | Delaying sending of verdict by 3000 ms
06:27:21.815 mediator=globalMediatorSv1 trace=da673f41fbf9c8d8d89ee6f9fcdb3ecb | Delaying sending of verdict by 3000 ms
```
(872e35d5 and da673f41 are the trace ids in the sv4 refusals of .280Z and .280001Z, section 5 first block.) No
participant line `About to wrap up` for either request: all four SV participants validated both (Phase 3 at
06:27:10.5) and none finished them:
```
for r in 11324:logs-simtime-0:00:00:00.280Z 11324:logs-simtime-0:00:00:00.280001Z 11325:logs-simtime-4:00:00:00.343Z 11332:logs-simtime-3:00:00:00.265001Z; do IFS=: read n d rest <<<"$r"; id="1970-01-01T${r#*:*:}"; echo "== $n $id"; zcat log/$n/$d/canton-simtime.clog.gz | grep -a -F "RequestId($id)" | jq -r 'select(.message|test("About to wrap up|Phase 7")) | ...'; zcat log/$n/$d/canton-simtime.clog.gz | grep -a -F "request=$id" | jq -r 'select(.message|test("Phase 3: Validating")) | "\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<n>participant=[A-Za-z0-9]+)").n) | \(.message|.[0:80])"'; done
```
```
== 11324 1970-01-01T00:00:00.280Z
06:27:10.514 participant=sv1Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280Z with 1 envelope
06:27:10.516 participant=sv3Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280Z with 1 envelope
06:27:10.520 participant=sv2Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280Z with 1 envelope
06:27:10.520 participant=sv4Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280Z with 1 envelope
== 11324 1970-01-01T00:00:00.280001Z
06:27:10.531 participant=sv3Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280001Z with 1 envel
06:27:10.570 participant=sv4Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280001Z with 1 envel
06:27:10.572 participant=sv1Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280001Z with 1 envel
06:27:10.648 participant=sv2Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.280001Z with 1 envel
== 11325 1970-01-01T00:00:00.343Z
06:22:08.472 participant=sv3Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.343Z with 1 envelope
== 11332 1970-01-01T00:00:00.265001Z
09:41:45.304 participant=sv2Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.265001Z with 1 envel
09:41:45.309 participant=sv4Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.265001Z with 1 envel
09:41:45.312 participant=sv3Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.265001Z with 1 envel
09:41:45.312 participant=sv1Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.265001Z with 1 envel
```
(zero wrap-up lines in all four.) Same mechanism as 11287 and 11306/11308 (3.7.2): the Canton bump did not change it.

## 6. What the stuck apps waited on

11324: the sv3 SV app's submission during `setting domain config` (command e2a61c91) is accepted at record time
.392001Z, after the lost requests, but its completion never reaches the command service:
```
F=log/11324/logs-simtime-0/canton-simtime.clog.gz; zcat $F | grep -a 'e2a61c91-4f6f-44bd-87d1-4a9941afeb8c' | jq -r '"\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:60]) trace=\(.["trace-id"]) | \(.message|gsub("\\s+";" ")|.[0:220])"'
```
(excerpt)
```
06:27:25.798 INFO c.d.c.p.p.TransactionProcessor:participant=sv3Participant/ps trace=d4a1d4d47f9575bdc9ca3a913fc2e8f9 | Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-eng-3-3a3f3ffb::1220c9bf793f..., command-id e2a61c91-4f6f-44bd-87d1-4a9941afeb8c
06:27:27.467 DEBUG c.d.c.p.p.TransactionProcessor:participant=sv3Participant/ps trace=d4a1d4d47f9575bdc9ca3a913fc2e8f9 | About to wrap up request RequestId(1970-01-01T00:00:00.392001Z) with event Some( SequencedTransactionAccepted( recordTime = 1970-01-01T00:00:00.392001Z, updateId = 12207ac7f3a1839ee32c45e91d8dac04e4e704f9603bfd33315a28a0
06:29:25.760 INFO c.d.c.p.a.s.c.CommandServiceImpl:participant=sv3Participant trace=d4a1d4d47f9575bdc9ca3a913fc2e8f9 | REQUEST_TIME_OUT(3,db0f7feb): Timed out while awaiting for a completion corresponding to a command submission with command-id=e2a61c91-4f6f-44bd-87d1-4a9941afeb8c and submission-id=db0f7feb-e71f-43dd-80ff-ba2a9956170c.
```
11332: the same for sv3's commands 9470b5ca (request .267003Z, accepted 09:42:00.285, `REQUEST_TIME_OUT` 09:43:45.082):
```
F=log/11332/logs-simtime-3/canton-simtime.clog.gz; zcat $F | grep -a -E '9470b5ca-9aaa-46a4-93c1-4bf4e23e9c09|a0ab2e24-cdf2-459b' | jq -r 'select(.message|test("Phase 1 completed|About to wrap up|Timed out|SUBMISSION_ALREADY|command-id")) | "\(.["@timestamp"][11:23]) \(.level) trace=\(.["trace-id"]) | \(.message|gsub("\\s+";" ")|.[0:200])"'
```
(excerpt)
```
09:41:45.169 INFO trace=48524b1dea45bf383cced895229e4b09 | Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-eng-3-8d559027::12200d2f4bf5..., command-id 9470b5ca-9aaa-46a4-93c1-4bf4e23e9c09
09:42:00.285 DEBUG trace=48524b1dea45bf383cced895229e4b09 | About to wrap up request RequestId(1970-01-01T00:00:00.267003Z) with event Some( SequencedTransactionAccepted( recordTime = 1970-01-01T00:00:00.267003Z, updateId = 12207c49737d9fbb1fb6b7a8e1dcc3996199
09:43:45.082 INFO trace=48524b1dea45bf383cced895229e4b09 | REQUEST_TIME_OUT(3,f76f78ac): Timed out while awaiting for a completion corresponding to a command submission with command-id=9470b5ca-9aaa-46a4-93c1-4bf4e23e9c09 and submission-id=f76f78ac-4211-4f5d-
```
11325: sv3Validator's `installWalletForUser` is the lost request .343Z itself (sv3Participant is the only participant
that validates it, section 5), followed by 120 s DEADLINE_EXCEEDED, then SUBMISSION_ALREADY_IN_FLIGHT retries that trip the circuit
breaker until the test gives up:
```
F=log/11325/logs-simtime-4/canton-simtime.clog.gz; t=$(zcat $F | grep -a -F 'Phase 3: Validating Transaction request=1970-01-01T00:00:00.343Z' | jq -r '.["trace-id"]' | head -1); echo trace=$t; zcat $F | grep -a -F "$t" | jq -r 'select(.level!="TRACE") | select(.message|test("command-id|Phase 1|Phase 3|in-flight|Registering handle|SUBMISSION_ALREADY|Timed out|Delaying|Sent result|Failed to send")) | ...'
```
(excerpt)
```
trace=c36e7c473b4c0efddfbd3e1004a1dee7
06:22:01.343 DEBUG participant=sv3Participant | Command org.lfdecentralizedtrust.splice.validator.installWalletForUser_88c8f3b7d85fd2dd826d9bfe1bae67bfc4b4e54d7c7e627a48746b28eca9b2df is now in-flight.
06:22:08.472 INFO participant=sv3Participant | Phase 3: Validating Transaction request=1970-01-01T00:00:00.343Z with 1 envelope(s)
06:22:08.792 DEBUG mediator=globalMediatorSv3 | Delaying sending of verdict by 3000 ms
06:22:09.235 DEBUG mediator=globalMediatorSv1 | Sent result for request 1970-01-01T00:00:00.343Z
06:22:10.322 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:00:00.343Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/
06:24:01.221 INFO participant=sv3Participant | REQUEST_TIME_OUT(3,e340cbd1): Timed out while awaiting for a completion corresponding to a command submission with command-id=org.lfdecentralizedtrust.splice.validator.installWalletForUser_8
```
```
f=log/11325/logs-simtime-4/canton_network_test.clog.gz; zcat $f | jq -r 'select(.["@timestamp"] < "2026-11-10T06:27:22") | select(.logger_name|test("SvTimeBasedRoundMgmtIntegrationTest/config=521d5c[0-9a-f]*/validator=sv3Validator$")) | select(.message|test("installWalletForUser.*failed with a retryable error|tripped|SubmitAndWaitForTransaction.*(sending request|failed with)")) | ...' | awk '{k=substr($0,14,100); if(k!=p){print; p=k}}' | head -4
```
```
06:24:01.219 INFO ValidatorApp | The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999836245s. Name resolution delay 0.000000000 seconds. [closed=[CANCELLED], committed=[remote_addr=127.0.0.1/127.0.0.1:15301]] statusCode=DEADLINE_EXCEEDED
06:24:59.498 WARN SpliceCircuitBreaker | Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure
06:24:59.499 INFO ValidatorApp | The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted): category=Some(ContentionOnSharedResources) ErrorInfoDetail(SUBMISSION_ALREADY_IN_FLIGHT,HashMap(participant -> sv3Participant, physicalSynchronizerId -> global-domain::1220678a2522...::37-0, changeId -> ChangeId(sv3_validator_user-521d
```
Indexer activity per participant: the SV participants stop just before the first lost request and, in 11324 and
11332, never resume (canton logs end 06:39:14 and 10:15:42):
```
for r in 11324:logs-simtime-0:06:2 11325:logs-simtime-4:06:2 11332:logs-simtime-3:09:4; do IFS=: read n d h m <<<"$r"; echo "== $n"; zcat log/$n/$d/canton-simtime.clog.gz | grep -a -E 'ParallelIndexerSubscription|InMemoryStateUpdater' | jq -r '"\(.logger_name|capture("participant=(?<n>[A-Za-z0-9]+)").n // "-") \(.["@timestamp"][11:23])"' | awk -v from="$h:$m" '$2>from {last[$1]=$2; c[$1]++} END{for(k in c) print k, last[k], c[k]}' | sort; done
```
```
== 11324
aliceParticipant 06:34:04.255 791
bobParticipant 06:34:04.258 674
splitwellParticipant 06:34:04.262 827
sv1Participant 06:27:10.349 837
sv2Participant 06:27:10.346 663
sv3Participant 06:27:10.345 578
sv4Participant 06:27:10.349 642
== 11325
aliceParticipant 06:48:44.451 3696
bobParticipant 06:48:44.252 3152
splitwellParticipant 06:48:44.809 3787
sv1Participant 06:48:49.834 9111
sv2Participant 06:48:38.939 2275
sv3Participant 06:48:38.934 2201
sv4Participant 06:48:38.934 2254
== 11332
aliceParticipant 10:10:37.260 715
bobParticipant 10:10:37.263 732
splitwellParticipant 10:10:37.263 792
sv1Participant 09:41:44.802 634
sv2Participant 09:41:44.790 526
sv3Participant 09:41:44.793 539
sv4Participant 09:41:44.792 543
```
11325, sv3Participant: the only request in 06:21:50-06:22:40 without a result is .343Z, and its indexer pauses from
06:21:58 until 06:27:55:
```
zcat log/11325/logs-simtime-4/canton-simtime.clog.gz | awk '/"@timestamp":"2026-11-10T06:2[1-2]:[0-5]/' | grep -a 'participant=sv3Participant' | jq -r 'select(.message|test("Phase 3: Validating|Got result for|Schedule publication")) | (.message|capture("(request=|RequestId\\()(?<r>1970[^ )]*)").r // "") as $r | "\(.["@timestamp"][11:23]) \(.message|.[0:30]) \($r)"' | awk '$1>"06:21:50" && $1<"06:22:40"' | awk '{if($0 ~ /Phase 3/) v[$NF]=$1; else if($0 ~ /Got result/) g[$NF]=$1} END{for(k in v) print k, "validated " v[k], "result " (k in g ? g[k] : "NONE")}' | sort
zcat log/11325/logs-simtime-4/canton-simtime.clog.gz | grep -a -E 'ParallelIndexerSubscription|InMemoryStateUpdater' | jq -r 'select(.logger_name|test("participant=sv3Participant")) | .["@timestamp"][11:23]' | awk '{split($1,a,":"); s=a[1]*3600+a[2]*60+a[3]; if(p!="" && s-ps>30) print "gap " p " -> " $1 " (" int(s-ps) " s)"; p=$1; ps=s}' | head -2
```
```
1970-01-01T00:00:00.221001Z validated 06:21:51.395 result 06:21:52.054
...
1970-01-01T00:00:00.255Z validated 06:21:57.560 result 06:21:58.224
1970-01-01T00:00:00.343Z validated 06:22:08.472 result NONE
1970-01-01T00:00:00.358001Z validated 06:22:10.562 result 06:22:11.603
...
1970-01-01T00:00:00.473Z validated 06:22:27.200 result 06:22:28.723
gap 06:20:43.866 -> 06:21:27.216 (43 s)
gap 06:21:58.372 -> 06:27:55.315 (356 s)
```

## 7. Why 11325 recovered and 11324/11332 did not: sim time and the topology reset

Domain time seen by sv1 per suite (first line per suite and minute):
```
for r in 11324:logs-simtime-0 11325:logs-simtime-4 11332:logs-simtime-3; do n=${r%%:*}; d=${r#*:}; echo "== $n"; zcat log/$n/$d/canton_network_test.clog.gz | grep -a 'Updated domain time to' | jq -r 'select(.logger_name|test("SV=sv1$")) | "\(.["@timestamp"][11:19]) \(.logger_name|capture("^[^:]*:(?<s>[A-Za-z]+)").s) \(.message|capture("to (?<t>[0-9T:.Z-]+)").t)"' | awk '{k=$2" "substr($3,1,16); if(k!=p){print; p=k}}' | head -30; done
```
(excerpt)
```
== 11324
06:24:23 TrafficBasedRewardsSvAppTimeBasedIntegrationTest 1970-01-01T00:00:00.004001Z
06:29:02 WalletMintingDelegationTimeBasedIntegrationTest 1970-01-01T00:00:00.402Z
06:34:03 SvTimeBasedOnboardingIntegrationTest 1970-01-01T00:00:01.016001Z
== 11325
06:19:57 SvTimeBasedRoundMgmtIntegrationTest 1970-01-01T00:00:00.004001Z
06:27:55 SvTimeBasedRoundMgmtIntegrationTest 1970-01-01T00:10:10.001001Z
06:28:06 SvTimeBasedRoundMgmtIntegrationTest 1970-01-01T00:30:00.004002Z
...
== 11332
09:39:12 TrafficBasedRewardsSvAppTimeBasedIntegrationTest 1970-01-01T00:00:00.004001Z
09:44:02 TrafficBasedRewardsTimeBasedIntegrationTest 1970-01-01T00:00:00.404Z
09:49:37 SvExpiredRewardsCollectionTimeBasedIntegrationTest 1970-01-01T00:00:01.205001Z
09:54:50 TokenStandardMetadataTimeBasedIntegrationTest 1970-01-01T00:00:03.599001Z
10:00:07 FeaturedAppRightSwitchOverTimeBasedIntegrationTest 1970-01-01T00:00:05.847001Z
10:05:21 SvTimeBasedRewardCouponMissingPartyIntegrationTest 1970-01-01T00:00:08.130001Z
10:10:35 WalletAmuletPriceTimeBasedIntegrationTest 1970-01-01T00:00:10.394001Z
```
The lost requests' decision time is 00:01:00.xxx (the `Finalized ... at 1970-01-01T00:01:00.280Z` lines). In 11325 the
next test jumped to 00:10:10 at 06:27:55, the same second sv3Participant's indexer resumed; in 11324 and 11332 the
clock never passed 00:00:11, so the requests stayed open for the rest of the shard.

Topology reset after each environment (ResetDecentralizedNamespace / ResetSequencerSynchronizerStateThreshold):
```
for r in 11324:logs-simtime-0 11332:logs-simtime-3 11325:logs-simtime-4; do n=${r%%:*}; d=${r#*:}; echo "== $n"; zcat log/$n/$d/canton_network_test.clog.gz | jq -r 'select(.message|test("has been reset|Restarting .* reset|Resetting .* failed|Failed to reset|Exceeded max retries")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:80]) | \(.message|gsub("\\s+";" ")|.[0:200])"' | head -12; done
```
```
== 11324
== 11332
== 11325
06:27:36.709 INFO o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace | decentralized namespace has been reset
06:27:37.286 INFO o.l.s.i.p.ResetSequencerSynchronizerStateThreshold:ResetSequencerSynchronizerSta | sequencer domain state threshold has been reset
06:28:58.105 INFO o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace | decentralized namespace has been reset
...
```
DSO (synchronizer) namespace definitions persisted on sv1Participant:
```
for r in 11324:logs-simtime-0 11332:logs-simtime-3 11325:logs-simtime-4; do n=${r%%:*}; d=${r#*:}; echo "== $n"; zcat log/$n/$d/canton-simtime.clog.gz | grep -a 'Persisted topology transactions' | grep -a 'DecentralizedNamespaceDefinition' | jq -r 'select(.logger_name|test("participant=sv1Participant/psid")) | (.message|gsub("\\s+";" ")) as $m | "\(.["@timestamp"][11:23]) \($m|capture("EffectiveTime\\((?<e>[^)]*)\\)").e) \($m|capture("DecentralizedNamespaceDefinition\\(namespace = (?<ns>1220[0-9a-f]{8})[^,]*, threshold = (?<t>[0-9]+), owners = (?<o>Seq\\([^)]*\\)|[^)]*)\\), serial = (?<s>[0-9]+)")|"ns=\(.ns) serial=\(.s) threshold=\(.t) owners=\(.o|[scan("1220")]|length)")"' | head -12; done
```
(excerpt)
```
== 11324
06:24:22.905 0001-01-01T00:00:00.000001Z ns=12202ea6a91d serial=1 threshold=1 owners=1
06:26:43.559 1970-01-01T00:00:00.175001Z ns=12202ea6a91d serial=2 threshold=2 owners=2
06:26:48.256 1970-01-01T00:00:00.185002Z ns=12202ea6a91d serial=3 threshold=2 owners=3
06:26:51.645 1970-01-01T00:00:00.193004Z ns=12202ea6a91d serial=4 threshold=3 owners=4
== 11332
09:39:11.494 0001-01-01T00:00:00.000001Z ns=1220d7f67594 serial=1 threshold=1 owners=1
09:41:20.790 1970-01-01T00:00:00.167001Z ns=1220d7f67594 serial=2 threshold=2 owners=2
09:41:24.245 1970-01-01T00:00:00.176002Z ns=1220d7f67594 serial=3 threshold=2 owners=3
09:41:26.484 1970-01-01T00:00:00.182001Z ns=1220d7f67594 serial=4 threshold=3 owners=4
== 11325
06:19:56.675 0001-01-01T00:00:00.000001Z ns=1220678a2522 serial=1 threshold=1 owners=1
06:21:37.158 1970-01-01T00:00:00.161Z ns=1220678a2522 serial=2 threshold=2 owners=2
06:21:39.149 1970-01-01T00:00:00.167001Z ns=1220678a2522 serial=3 threshold=2 owners=3
06:21:52.430 1970-01-01T00:00:00.227Z ns=1220678a2522 serial=4 threshold=3 owners=4
06:27:36.587 1970-01-01T00:00:02.684Z ns=1220678a2522 serial=5 threshold=1 owners=1
```
Highest serial over the whole shard in 11324 and 11332:
```
for r in 11324:logs-simtime-0:12202ea6a91d 11332:logs-simtime-3:1220d7f67594; do IFS=: read n d ns <<<"$r"; echo "== $n ns=$ns"; zcat log/$n/$d/canton-simtime.clog.gz | grep -a 'Persisted topology transactions' | grep -a "DecentralizedNamespaceDefinition(namespace = $ns" | jq -r 'select(.logger_name|test("participant=sv1Participant/psid")) | (.message|gsub("\\s+";" ")|capture("serial = (?<s>[0-9]+)").s) as $s | "\(.["@timestamp"][11:23]) serial=\($s)"' | awk '{last=$0; if($2>max) max=$2} END{print "max " max "; last persisted: " last}'; zcat log/$n/$d/canton-simtime.clog.gz | tail -1 | jq -r '"canton log ends " + .["@timestamp"]'; done
```
```
== 11324 ns=12202ea6a91d
max serial=4; last persisted: 06:26:52.242 serial=4
canton log ends 2026-11-10T06:39:14.245Z
== 11332 ns=1220d7f67594
max serial=4; last persisted: 09:41:28.256 serial=4
canton log ends 2026-11-10T10:15:42.252Z
```
Why no reset: the reset is `ResetTopologyStatePlugin.beforeEnvironmentDestroyed`, and a failure in the
`Running setup` step only closes the fixture (vendored Canton test framework, compiled into the splice test build;
file unchanged since #7944):
```
git show 6c0fee0505:canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala | sed -n '228,252p;292,301p'
git log -1 --format='%h %ci %s' 6c0fee0505 -- canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala
```
```
        if (!finalConfig.parameters.manualStart)
          step("Starting all nodes") {
            handleStartupLogs(testEnvironment.startAll())
          }

        step("Running setup") {
          envDef.setups.foreach(setup => setup(testEnvironment))
        }

        testEnvironment
      } catch {
        case NonFatal(ex) =>
          // attempt to ensure the environment is shutdown if anything in the startup of initialization fails
          try {
            environmentFixture.close()
          } catch {
            case NonFatal(shutdownException) =>
              // we suppress the exception thrown by the shutdown as we want to propagate the original
              // exception, however add it to the suppressed list on this thrown exception
              ex.addSuppressed(shutdownException)
          }
          // rethrow exception
          throw ex
      }
  protected def manualDestroyEnvironment(environment: BaseTestConsoleEnvironment[C, E]): Unit = {
    val config = environment.actualConfig
    plugins.foreach(_.beforeEnvironmentDestroyed(environment))
    try {
      environment.close()
    } finally {
      envDef.teardown(())
      plugins.foreach(_.afterEnvironmentDestroyed(config))
    }
  }
f0d0c48b1f 2026-10-23 13:57:44 +0200 Bump canton fork to 3.7 (#7944)
```
```
git show 6c0fee0505:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala | sed -n 25,41p
```
```
  override def beforeEnvironmentDestroyed(
      env: SpliceTests.SpliceTestConsoleEnvironment
  ): Unit = {

    // Stop all nodes to stop them from submitting topology TXs.
    env.stopAll()

    try {
      attemptToResetTopologyState(env)
    } catch {
      case NonFatal(e) =>
        val msg = s"Resetting $topologyType failed with: $e, giving up"
        logger.error(msg)
        System.err.println(msg)
        sys.exit(1)
    }
  }
```
11325's first failure was in a test body (`initDso` from the test, `SpliceTests.scala:313`), so its environment was
destroyed normally and the resets ran (above).

## 8. Consequence in the later suites

11332 second suite, sv1 cannot re-establish the DSO party in the namespace that still needs 3 of 4 owners:
```
f=log/11332/logs-simtime-3/canton_network_test.clog.gz; zcat $f | jq -r 'select(.logger_name|test("TrafficBasedRewardsTimeBasedIntegrationTest/config=c280827e/SV=sv1$")) | select(.message|test("Check whether Party DSO.*failed with a retryable error")) | "\(.["@timestamp"][11:23]) \(.level) | \(.message|gsub("\\s+";" ")|.[0:600])"' | sed -n '1p;$p'
F=log/11332/logs-simtime-3/canton-simtime.clog.gz; zcat $F | grep -a 'e0af5e6c284bd90f5bc63961068ad8ac' | jq -r 'select(.level!="DEBUG" and .level!="TRACE") | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:70]) | \(.message|gsub("\\s+";" ")|.[0:500])"' | head -3
```
```
09:44:02.440 INFO | The operation 'Check whether Party DSO-c280827e-c280827e::1220d7f67594... is allocated on PAR::sv1::1220ec1fed7e... and establish it if needed' failed with a retryable error (full stack trace omitted): INVALID_ARGUMENT: An error occurred. Please contact the operator and inquire about the request e0af5e6c284bd90f5bc63961068ad8ac with tid e0af5e6c284bd90f5bc63961068ad8ac statusCode=INVALID_ARGUMENT
09:49:01.594 INFO | The operation 'Check whether Party DSO-c280827e-c280827e::1220d7f67594... is allocated on PAR::sv1::1220ec1fed7e... and establish it if needed' failed with a retryable error (full stack trace omitted): INVALID_ARGUMENT: An error occurred. Please contact the operator and inquire about the request 6851cbf55497161b774f3d6581c05134 with tid 6851cbf55497161b774f3d6581c05134 statusCode=INVALID_ARGUMENT
09:44:02.412 INFO c.d.c.t.SynchronizerTopologyManager:participant=sv1Participant/psid=gl | Attempting to build, sign, and Replace PartyToParticipant(partyId = DSO-c280827e-c280827e::1220d7f67594..., participants = PAR::sv1::1220ec1fed7e... -> Submission) with serial Some(1) 
09:44:02.436 INFO c.d.c.t.p.TopologyStateProcessorImpl:participant=sv1Participant/psid=g | Topology transactions failed: ValidatedTopologyTransaction( SignedTopologyTransaction( TopologyTransaction( PartyToParticipant(partyId = DSO-c280827e-c280827e::1220d7f67594..., participants = PAR::sv1::1220ec1fed7e... -> Submission), serial = 1, operation = Replace, hash = SHA-256:afe783452ae5... ), signatures = 1220ec1fed7e... ), rejectionReason = Not fully authorized )
09:44:02.439 WARN c.d.c.t.SynchronizerTopologyManager:participant=sv1Participant/psid=gl | TOPOLOGY_UNAUTHORIZED_TRANSACTION(5,e0af5e6c): Topology transaction is missing authorizations by namespaces=Set(1220d7f67594...) and keys=Set()
```
The same retry in 11324's second and third suites (count of sv1 retry lines per suite), and the canton-side refusals:
```
f=log/11324/logs-simtime-0/canton_network_test.clog.gz; zcat $f | jq -r 'select(.logger_name|test("/SV=sv1$")) | select(.message|test("Check whether Party DSO.*failed with a retryable error")) | "\(.logger_name|capture("^[^:]*:(?<s>[A-Za-z]+)").s)"' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
```
```
83 WalletMintingDelegationTimeBasedIntegrationTest
85 SvTimeBasedOnboardingIntegrationTest
```
11324's last three suites fail at `Creating fixture`: the SvTimeBasedOnboarding environment was never closed because
`UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` threw (its scans never initialized) before
`environment.close()` (`manualDestroyEnvironment` above), so the metrics port stays bound (family H, teardown leak
cascade, 10176):
```
sed 's/\x1b\[[0-9;]*m//g' log/11324/job.log | grep -a -A40 'expire stale.*\*\*\* FAILED' | cut -c30-200 | grep -a -E 'Plugin|EnvironmentSetup' | head -4
f=log/11324/logs-simtime-0/canton_network_test.clog.gz; zcat $f | jq -r 'select(.["@timestamp"] > "2026-11-10T06:39:05.40" and .["@timestamp"] < "2026-11-10T06:39:07") | select(.level=="ERROR" or .level=="WARN" or (.message|test("Prometheus"))) | ...'
```
(excerpt)
```
[info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed(UpdateHistorySanityCheckPlugin.scala:49)
[info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed(UpdateHistorySanityCheckPlugin.scala:30)
[info]   at com.digitalasset.canton.integration.EnvironmentSetup.$anonfun$manualDestroyEnvironment$1(EnvironmentSetup.scala:296)
06:39:06.072 INFO c.d.c.metrics.MetricsRegistry$ | Exposing metrics for Prometheus on port 0.0.0.0:25000 
06:39:06.073 ERROR o.l.s.i.t.SvTimeBasedAmuletPriceIntegrationTest:SvTimeBasedAmuletPrice | Failed creating environment: Creating fixture (EnvironmentSetup.scala:146) Caused by: java.net.BindException: Address already in use
06:39:06.960 DEBUG c.d.c.t.WallClock:SvTimeBasedOnboardingIntegrationTest/config=cd29fc4d | Running queued action: schedule_after_unless_shutdown 
```
(the family H confirming grep: the old environment's `config=cd29fc4d` keeps logging after the failure.)

## 9. Duplicate check and hit rate

Parent 11255 (open, rautenrieth-da, "Timeouts due to changing bft ordering topology"); its candidate fix for the
lost-verdict shape is canton-network/splice#8356 (disable BFT blacklisting in integration tests), still open:
```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"#\(.number) [\(.state)] \(.title) assignees=\([.assignees[].login]|join(",")) comments=\(.comments)"'
gh pr view 8356 -R canton-network/splice --json state,title,mergedAt,url --jq '"\(.state) \(.mergedAt) \(.title) \(.url)"'
```
```
#11255 [open] Timeouts due to changing bft ordering topology assignees=rautenrieth-da comments=2
OPEN null Disable BFT sequencer blacklisting in integration tests https://github.com/canton-network/splice/pull/8356
```
simtime issues since 2026-11-06:
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue created:>=2026-11-06 simtime in:title' -f per_page=100 --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:16]) \(.title[0:90])"' | sort
```
```
#11241 closed/duplicate 2026-11-06T14:24 GHA Run 42230239729 : job simtime (2) in scala_test Failed :fire:
#11242 closed/duplicate 2026-11-06T14:39 GHA Run 42230239729 : job simtime (1) in scala_test Failed :fire:
#11258 closed/duplicate 2026-11-06T17:12 GHA Run 42253265180 : job simtime (2) in scala_test Failed :fire:
#11275 closed/duplicate 2026-11-07T05:41 GHA Run 42341066277 : job simtime (4) in scala_test Failed :fire:
#11276 closed/duplicate 2026-11-07T05:53 GHA Run 42341066277 : job simtime (2) in scala_test Failed :fire:
#11277 closed/duplicate 2026-11-08T05:35 GHA Run 42449239264 : job simtime (5) in scala_test Failed :fire:
#11278 closed/duplicate 2026-11-08T05:39 GHA Run 42449239264 : job simtime (3) in scala_test Failed :fire:
#11281 closed/duplicate 2026-11-09T05:26 GHA Run 42565193320 : job simtime (5) in scala_test Failed :fire:
#11282 closed/duplicate 2026-11-09T05:27 GHA Run 42565193320 : job simtime (3) in scala_test Failed :fire:
#11283 closed/duplicate 2026-11-09T05:29 GHA Run 42565193320 : job simtime (0) in scala_test Failed :fire:
#11284 closed/duplicate 2026-11-09T05:43 GHA Run 42565193320 : job simtime (1) in scala_test Failed :fire:
#11287 closed/duplicate 2026-11-09T10:20 GHA Run 42591855865 : job simtime (3) in scala_test Failed :fire:
#11288 closed/duplicate 2026-11-09T11:44 GHA Run 42608940134 : job simtime (4) in scala_test Failed :fire:
#11289 closed/duplicate 2026-11-09T13:00 GHA Run 42618450792 : job simtime (3) in scala_test Failed :fire:
#11291 closed/duplicate 2026-11-09T13:50 GHA Run 42623223930 : job docker-canton-simtime (0) in scala_test Failed :fire:
#11306 closed/duplicate 2026-11-09T15:34 GHA Run 42637895044 : job simtime (3) in scala_test Failed :fire:
#11308 closed/duplicate 2026-11-09T15:43 GHA Run 42639148146 : job simtime (3) in scala_test Failed :fire:
#11323 open/- 2026-11-10T05:43 GHA Run 42759795825 : job simtime (4) in scala_test Failed :fire:
#11324 open/- 2026-11-10T06:39 GHA Run 42766394442 : job simtime (0) in scala_test Failed :fire:
#11325 open/- 2026-11-10T06:49 GHA Run 42766394442 : job simtime (4) in scala_test Failed :fire:
#11326 open/- 2026-11-10T08:28 GHA Run 42780003623 : job simtime (0) in scala_test Failed :fire:
#11332 open/- 2026-11-10T10:16 GHA Run 42790498517 : job simtime (3) in scala_test Failed :fire:
#11334 open/- 2026-11-10T10:33 GHA Run 42794742288 : job simtime (3) in scala_test Failed :fire:
#11337 open/- 2026-11-10T12:13 GHA Run 42810067070 : job simtime (2) in scala_test Failed :fire:
```
Not all of these are 11255 (11291 is family AF; 11323, 11326, 11334, 11337 are other groups of this round, not
checked here). This group: 3 more hits of the lost-verdict shape on 2026-11-10, on two of the day's post-merge main runs.

## Verdict

11324, 11325, 11332: duplicates of 11255 (family B, lost-verdict shape: sv4 blacklisted after the 2 -> 4 ordering
step, mediator threshold 2 of 4, one verdict sent, the other delayed 3000 ms on a sim clock that does not advance,
participants never finish the request and stop publishing). Unchanged by Canton 3.7.3-snapshot.20261109. Flake in
the test setup under BFT + sim time; fix location as in 11255 (splice#8356 removes the blacklist refusal; sim-time
verdict delay or sequential onboarding for the rest).

New fact for the parent (changes its cost, not its cause): when the hit lands in a suite whose 4-SV `initDso` runs in
the environment's setup step, the setup-failure path of `EnvironmentSetup.manualCreateEnvironment` closes only the
fixture and skips the plugins' `beforeEnvironmentDestroyed`, so the DSO namespace keeps 4 owners / threshold 3 and the
stuck requests stay open; every later suite of the shard fails (7 more suites in 11332, 5 more in 11324: 2 time out,
3 fail at Creating fixture). Possible hardening, separate from 11255: run the topology reset plugins on the
setup-failure path (vendored Canton test framework, so a CANTON_CODE_CHANGES entry, or a splice-side hook). Owner
open.

Not verified: why the delayed verdict is never sent once sim time stays put (the 11274-11276 packet covers the
VerdictSender rule); whether 11306/11308 (setup failures too) also poisoned their shards.

Lesson candidate: cleanup that restores shared state must run on every exit path, including a failure while the
fixture is still being built (here: topology resets only in the destroy hook, skipped when setup throws).

## Addendum (main thread, 2026-11-10): 11255 hits per post-merge main run since the Canton bump

Combined with the g2 (11323 11326), g3 (11337) and g4 (11334) packets, all dups of 11255, for the parent comment.
```
gh api "repos/canton-network/splice/actions/runs?branch=main&event=push&created=>=2026-11-09T19:00:00Z&per_page=100" --jq '.workflow_runs[] | select(.name|test("post-merge|mainnet Daml")) | "\(.id) \(.head_sha[0:10]) \(.name[0:40]) \(.status)/\(.conclusion) \(.created_at)"'
```
```
42823122392 1844b95301 CI post-merge to main or release-line br in_progress/null 2026-11-10T13:17:08Z
42810067070 88491ce56e CI post-merge to main or release-line br completed/failure 2026-11-10T11:45:33Z
42804747879 c119dc37fa CI post-merge to main or release-line br completed/success 2026-11-10T11:06:41Z
42799831067 f78a3ebdeb CI post-merge to main or release-line br completed/failure 2026-11-10T10:31:13Z
42794742288 6dbbda48ca CI post-merge to main or release-line br completed/failure 2026-11-10T09:55:21Z
42791495886 f367de93c5 CI post-merge to main or release-line br completed/success 2026-11-10T09:32:00Z
42790612651 a9038ea432 CI post-merge to main or release-line br completed/cancelled 2026-11-10T09:25:38Z
42790576154 e59889cad0 CI post-merge to main or release-line br completed/failure 2026-11-10T09:25:22Z
42790498517 6c0fee0505 CI post-merge to main or release-line br completed/failure 2026-11-10T09:24:50Z
42785850771 087f79bf73 CI post-merge to main or release-line br completed/failure 2026-11-10T08:50:59Z
42782393978 6aed1a2ab2 CI post-merge to main or release-line br completed/success 2026-11-10T08:24:31Z
42780003623 e5741cdd9f CI post-merge to main or release-line br completed/failure 2026-11-10T08:06:04Z
42766394442 39ef05f244 CI post-merge to main or release-line br completed/failure 2026-11-10T06:07:27Z
42687934161 885cd84668 CI post-merge to main or release-line br completed/failure 2026-11-09T19:55:11Z
```
12 completed (cancelled excluded). Runs with an 11255 issue in this round: 42766394442 (11324 11325),
42780003623 (11326), 42790498517 (11332), 42794742288 (11334), 42810067070 (11337): 5 of 12. Plus the scheduled
mainnet-Daml run 42759795825 (11323). 42687934161 has no issue in this round (not checked).

Lines quoted in the parent comment for 11323 and 11326 (g2 packet; re-run here):
```
c=log/11323/logs-simtime-4/canton-simtime.clog.gz   # artifact of 11323
zcat $c | grep -aF 'P2P connectivity is not ready' | grep -aF 'T05:37:26.6' | head -1 | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|split(":")[1][0:60]) | \(.message|gsub("\n";" ")|.[0:260])"'
zcat log/11323/*/canton_network_test.clog.gz | grep -aF 'Mediator has been granted unlimited traffic' | head -1 | jq -r ...
zcat log/11323/*/*.clog.gz | grep -aF 'UserNotFound(sv2_validator_user-b520bd32)' | tail -1 | jq -r ...
zcat log/11326/logs-simtime-0/canton-simtime.clog.gz | grep -aF 'At epoch 13 received message after closing' | head -1 | jq -r ...
```
```
05:37:26.600 sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv1::1220475c4a8a...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
05:37:30.580 SvTimeBasedRoundMgmtIntegrationTest/config=b520bd32/SV=sv2 | Waiting until Mediator has been granted unlimited traffic
05:42:32.151 WARN participant=sv2Participant | PERMISSION_DENIED(7,a62659d4): Could not resolve is_deactivated status for user 'sv2_validator_user-b520bd32' and identity_provider_id 'Default' due to 'UserNotFound(sv2_validator_user-b520bd32)'
08:23:20.047 sequencer=globalSequencerSv1/psid=global-domain | At epoch 13 received message after closing, so discarding LocalAvailability(ProposalCreated(209,OrderingBlock(List(ProofOfAvailability(BatchId(122077f209ae6df6a8e0333465494b61cfa47346c6e0c9ea7e3990658
```
