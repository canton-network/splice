# 11274, 11275, 11276 - mainnet-Daml nightly: untagged CIP-104 test, and a lost mediator verdict that never times out in simtime (run 42341066277)

Two different causes in one nightly run of "Integration tests against mainnet Daml version" on main 0b2ccec235
(includes the DA BFT switch b77153c, #8234, and the TBAR v1.1 test PR 88e8c83b06, #8224).

- 11274, simtime (5): new, hard broken. TrafficBasedRewardsDryRunTimeBasedIntegrationTest runs against the mainnet
  splice-amulet although #8224 tagged it `SpliceAmulet_0_1_25`: the tag sits on the abstract base class, and the
  scalatest tag annotations are not `@Inherited`, so the concrete classes carry no tag. sv1 bootstrap then sets
  `extraFeaturedAssetActivityWeight = Some(0.2)`, which the mainnet AmuletConfig cannot hold; the SV app fails init
  and sbt exits. First nightly with #8224; it will fail every night until fixed. Owner: #8224 (adetokunbo).
- 11275, simtime (4): new. During the first multi-SV onboarding of the shard (sv4 BFT sequencer blacklisted, see
  splice#8361), the verdict of request 00:00:00.236Z only reached the sequencer from the sv2 mediator; the mediator
  group threshold was 2, the sv4 mediator's send was rejected by its blacklisted sequencer, and the sv1 mediator never
  sent one. The participants never got a result, and since the decision timeout (00:01:00.236Z) is in sim time and
  the test never advanced the clock that far, all four SV participants stopped publishing at 05:32:44 for the rest
  of the shard. sv3 init timed out (5 min), and the remaining tests failed on the leftover environment.
- 11276, simtime (2): same episode, likely same mechanism (link not pinned): sv4 sequencer blacklisted at 05:37, 60
  sv4 mediator verdict sends refused, the sv4 participant's indexer stops at 05:36:57 for the rest of the shard, and
  sv4Validator init times out. Duplicate of 11275.

- Run: https://github.com/canton-network/splice/actions/runs/42341066277, main 0b2ccec235 ("[ci] Bump the
  production-dependencies group in /apps with 3 updates (#8357)"), jobs 128308966780 `simtime (5)`, 128308966802
  `simtime (4)`, 128308966805 `simtime (2)`, attempt 1 each.
- Runtime canton: 3.7.2 (as for the other main runs of 2026-11-06/07; not re-read for this sha).
- Component: 11274 test (scalatest tags); 11275/11276 Canton BFT blacklisting during onboarding (trigger) plus
  simtime (no wall-clock bound on a missing verdict).

## 1. Jobs and failing assertions

```
for REF in 11274 11275 11276; do sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|app initialization: Initialize app failed' | sed -E 's/^[^Z]*Z //' | sort -u; done
```
```
(11274)
java.lang.RuntimeException: sv1 app initialization: Initialize app failed
(11275)
[info] - CIP-104 reward accounting pipeline works *** FAILED ***
[info] - Disabled wallet *** FAILED ***
[info] - round management *** FAILED ***
[info] - round management with scheduled config change of doubled tickDuration *** FAILED ***
[info] - round management with scheduled config change of reduced tickDuration *** FAILED ***
[info] - round management with very tightly scheduled config *** FAILED ***
[info] Tests: succeeded 0, failed 6, canceled 0, ignored 0, pending 0
(11276)
[info] - SV triggers complete the reward pipeline for the initial round *** FAILED ***
[info] Tests: succeeded 3, failed 1, canceled 0, ignored 0, pending 0
```

Suite timelines (artifacts `logs-simtime-5`, `logs-simtime-4`, `logs-simtime-2` under `log/<ref>/`):
```
zcat log/<ref>/logs-simtime-N/canton_network_test.clog.gz | jq -r 'select(.message|test("^Starting test suite|^Test (succeeded|failed)|^Starting test run")) | "\(.["@timestamp"][11:23]) \(.message[0:170])"'
```
```
== 11274/logs-simtime-5
05:30:02.556 Starting test run...
05:30:03.318 Starting test suite 'TrafficBasedRewardsDryRunTimeBasedIntegrationTest'...
== 11275/logs-simtime-4
05:30:02.139 Starting test run...
05:30:05.545 Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...
05:35:31.561 Test failed: 'SvTimeBasedRoundMgmtIntegrationTest/round management', message: Condition never became true within 5 minutes, location: SeeStackDepthException
05:40:33.449 Test failed: 'SvTimeBasedRoundMgmtIntegrationTest/round management with scheduled config change of doubled tickDuration', message: The app state of sv1Scan is currently n
05:40:33.503 Test failed: 'SvTimeBasedRoundMgmtIntegrationTest/round management with scheduled config change of reduced tickDuration', message: Creating environment failed at step Cre
05:40:33.536 Test failed: 'SvTimeBasedRoundMgmtIntegrationTest/round management with very tightly scheduled config', message: Creating environment failed at step Creating fixture (Env
05:40:33.617 Starting test suite 'TrafficBasedRewardsTimeBasedIntegrationTest'...
05:40:33.915 Test failed: 'TrafficBasedRewardsTimeBasedIntegrationTest/CIP-104 reward accounting pipeline works', message: Creating environment failed at step Creating fixture (Enviro
...
05:40:34.805 Test failed: 'DisabledWalletTimeBasedIntegrationTest/Disabled wallet', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146), locatio
== 11276/logs-simtime-2
05:29:18.263 Starting test suite 'TimeBasedTreasuryIntegrationTest'...
05:35:07.276 Test succeeded: 'TimeBasedTreasuryIntegrationTest/merge also amulet that should be expired'
05:35:07.304 Starting test suite 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest'...
05:42:20.153 Test failed: 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest/SV triggers complete the reward pipeline for the initial round', message: Creating environment failed at st
05:42:20.176 Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...
05:47:54.005 Starting test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'...
```
The four `Creating fixture` failures in 11275 are `java.net.BindException: Address already in use` (Prometheus HTTP
server) from the environment left over by the second test; they are consequences.

## 2. 11274: sv1 bootstrap rejected by the mainnet splice-amulet

```
zcat log/11274/logs-simtime-5/canton_network_test.clog.gz | jq -r 'select(.message|test("may not be dropped during downgrading|Gave up ensuring|Initialization failed")) | select(.logger_name|test("SV1Initializer|SvApp")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|split(":")[0]|split(".")[-1]) | \(.message|gsub("\\s+";" ")|.[0:330])"'
```
```
05:31:32.688 WARN SV1Initializer | The operation 'bootstrapping DSO' failed with a non-retryable error, not retrying: category=Some(InvalidIndependentOfSystemState) statusCode=INVALID_ARGUMENT description=COMMAND_PREPROCESSING_FAILED(8,2b1e16bc): An optional contract field ("extraFeaturedAssetActivityWeight") with a value of Some may not be dropped during downgra
05:31:32.689 WARN SV1Initializer | The operation 'Check whether the DsoRules and AmuletRules are bootstrapped and establish it if needed' failed with a non-retryable error, not retrying: ...
05:31:32.690 ERROR SV1Initializer | Gave up ensuring the DsoRules and AmuletRules are bootstrapped
05:31:32.697 ERROR SvApp | sv1 app initialization: Initialization failed
```

The run excluded `SpliceAmulet_0_1_25` (mainnet runs an older splice-amulet):
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11274/job.log | grep -a -m1 'Using scalatest tags' | cut -c1-330
```
```
2026-11-07T05:22:33.1494881Z Using scalatest tags: -l org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck -l org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25 -l org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmuletNameService_0_1_26 -l ...
```

The test sets the field and has the tag, on the abstract base class only (#8224 changed `0_1_19` to `0_1_25`):
```
T=apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsTimeBasedIntegrationTest.scala
git blame -L 62,64 0b2ccec235 -- $T | cut -c1-140; git blame -L 108,108 0b2ccec235 -- $T | cut -c1-140
git show 88e8c83b06 -- $T | grep -E '^[-+].*scalatesttags'
git show 0b2ccec235:$T | sed -n '964,976p'
```
```
1e662ba5cf6 (Divam          2026-03-25 04:12:15 +0000 62) @org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceToke
88e8c83b064 (Timothy Emiola 2026-11-06 19:18:54 +0900 63) @org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmul
b505a4c0d22 (Divam          2026-05-29 12:56:20 +0900 64) abstract class TrafficBasedRewardsTimeBasedIntegrationTestBase
88e8c83b064 (Timothy Emiola 2026-11-06 19:18:54 +0900 108)             extraFeaturedAssetActivityWeight = Some(BigDecimal("0.2")),
-@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
+@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class TrafficBasedRewardsTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
...
class TrafficBasedRewardsDryRunTimeBasedIntegrationTest
    extends TrafficBasedRewardsTimeBasedIntegrationTestBase {
```

The tag annotations are not `@Inherited`, and scalatest 3.2.19 reads class tags with `Class.getAnnotations`, which
returns a superclass annotation only if it is `@Inherited`:
```
git show 0b2ccec235:apps/common/src/test/java/org/lfdecentralizedtrust/splice/util/scalatesttags/SpliceAmulet_0_1_25.java | sed -n 10,14p
git grep -l '@Inherited' 0b2ccec235 -- apps/common/src/test/java/org/lfdecentralizedtrust/splice/util/scalatesttags | wc -l
git grep -n 'scalatest_version = ' 0b2ccec235 -- project/CantonDependencies.scala
javap -c -p -cp ~/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scalatest/scalatest-core_2.13/3.2.19/scalatest-core_2.13-3.2.19.jar 'org.scalatest.Suite$' | grep -A30 'autoTagClassAnnotations(' | grep getAnnotations
```
```
// Don't run this test when testing against splice-amulet < 0.1.25
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SpliceAmulet_0_1_25 {}
0
0b2ccec235:project/CantonDependencies.scala:43:  lazy val scalatest_version = "3.2.19"
      16: invokevirtual #838                // Method java/lang/Class.getAnnotations:()[Ljava/lang/annotation/Annotation;
```
No other integration test has a tag on an abstract base class:
```
git grep -n -B1 '^abstract class' 0b2ccec235 -- 'apps/app/src/test/scala/*IntegrationTest*.scala' | grep scalatesttags
```
```
0b2ccec235:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/TrafficBasedRewardsTimeBasedIntegrationTest.scala-63-@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
```
(Line 62, `SpliceTokenTestTradingApp_1_0_0`, sits on the same class and is equally ignored; harmless while that package
is on mainnet.) The same untagged class `TrafficBasedRewardsTimeBasedIntegrationTest` also ran in simtime (4) (section 1)
but failed earlier on the leftover environment.

First nightly with #8224 (merged 2026-11-06 10:18 UTC); the previous nightly (42166190199, 01436df75f) passed:
```
for s in 01436df75f 0b2ccec235; do git merge-base --is-ancestor 88e8c83b06 $s && echo "$s contains 8224" || echo "$s lacks 8224"; done
```
```
01436df75f lacks 8224
0b2ccec235 contains 8224
```

## 3. 11275: sv3 joins, then its ledger submissions never complete

sv1 initialized at 05:31:27; sv2 joined at 05:32:43; sv3 got stuck in `JoiningNodeInitializer joining Dso with key`:
```
zcat log/11275/logs-simtime-4/canton_network_test.clog.gz | jq -r 'select(.logger_name|test("SvApp:SvTimeBasedRoundMgmtIntegrationTest/config=9b7af849/SV=sv3$")) | select(.message|test("app initialization")) | "\(.["@timestamp"][11:23]) \(.message[0:200])"' | tail -5
```
```
05:30:50.540 sv3 app initialization: JoiningNodeInitializer joining Dso with key started
05:35:29.866 sv3 app initialization: JoiningNodeInitializer joining Dso with key failed
05:35:29.869 sv3 app initialization: Initialize node failed
05:35:29.870 sv3 app initialization: Initialize app failed
05:35:29.870 sv3 app initialization: Ignoring initialization failure, we are actually shutting down. Message was: sv3 app initialization: Initialize app failed
```
Its last init step finished at 05:33:03.672 (`Got 'SV nodes have been granted unlimited traffic': ()`); the next step
(`reconcileSynchronizerNodeConfigIfRequired(OnboardedAfterDelay)` in JoiningNodeInitializer.onboard) submits
`DsoRules_SetSynchronizerNodeConfig`, which hung for the full 120 s gRPC deadline:
```
zcat log/11275/logs-simtime-4/canton_network_test.clog.gz | jq -r 'select(.logger_name|test("config=9b7af849/SV=sv3")) | select(.["@timestamp"] > "2026-11-07T05:33:03.6") | select(.message|test("CommandService/SubmitAndWait")) | "\(.["@timestamp"][11:23]) \(.message|capture("(?<t>tid:[0-9a-f]{8})").t) \(.message|capture("127.0.0.1:[0-9]+: (?<m>.{0,120})").m)"' | head -4
```
```
05:33:03.774 tid:b26861bc sending request SubmitAndWaitRequest(
05:33:03.793 tid:e6f74f32 sending request SubmitAndWaitForTransactionRequest(
05:35:03.774 tid:b26861bc failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999809862s. Name resolution delay 0.000000000 seco
05:35:03.786 tid:e6f74f32 failed with DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999724299s. Name resolution delay 0.000000000 seco
```
(tid e6f74f32 is `DsoRules_SetSynchronizerNodeConfig`, b26861bc `DsoRules_SubmitStatusReport`.)

The transaction was sequenced and accepted at 05:33:05 (record time 00:00:00.355001Z), but the ledger API never
returned the completion:
```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | grep -a '833f52c0-1bb4' | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|split(":")[0]|split(".")[-1]) | \(.message|gsub("\\s+";" ")|.[0:200])"' | grep -E 'Phase 1|About to wrap|REQUEST_TIME_OUT'
```
```
05:33:03.835 TransactionProcessor | Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-eng-3-9b7af849::12205e4964e4..., command-id 833f52c0-1bb4-4396-af9e-af06640d6133
05:33:05.021 TransactionProcessor | About to wrap up request RequestId(1970-01-01T00:00:00.355001Z) with event Some( SequencedTransactionAccepted( recordTime = 1970-01-01T00:00:00.355001Z, updateId = 12200368dd503dc381e6d0dadd115e9c942d6848647905bc46c121595d6389844c03, transactionMeta = Transact
05:35:03.794 CommandServiceImpl | REQUEST_TIME_OUT(3,ebc37d17): Timed out while awaiting for a completion corresponding to a command submission with command-id=833f52c0-1bb4-4396-af9e-af06640d6133 and submission-id=ebc37d17-7a6a-41db-91fa-c0c8e10b5134.
```

## 4. 11275: the indexers of all four SV participants stop at 05:32:44, for the rest of the shard

```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | jq -r 'select(.logger_name|test("ParallelIndexerSubscription|InMemoryStateUpdater")) | "\(.logger_name|capture("participant=(?<n>[A-Za-z0-9]+)").n // "-") \(.["@timestamp"][11:19])"' 2>/dev/null | awk '{last[$1]=$2; c[$1]++} END{for(k in c) print k, last[k], c[k]}'
```
```
sv3Participant 05:32:44 568
sv2Participant 05:32:44 603
sv1Participant 05:32:44 762
sv4Participant 05:32:44 553
```
The canton log runs to the end of the shard (05:40:34), so these are the last indexer lines of the whole run.

## 5. 11275: request 00:00:00.236Z never gets a result

The sv3 participant received results for every later request, but none for 00:00:00.236Z (sc=144, request counter 10):
```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | jq -r 'select((.logger_name // "")|test("participant=sv3Participant")) | select(.message|test("Got result for|Schedule publication")) | "\(.["@timestamp"][11:23]) \(.logger_name|split(":")[0]|split(".")[-1]) | \(.message|gsub("\\s+";" ")|.[0:110])"' | awk '$1>"05:32:43.7"' | head -6
```
```
05:32:43.705 RecordOrderPublisher | Schedule publication for request counter 8
05:32:43.724 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:00:00.224005Z): OpenEnvelope( SignedProtocolMessage(
05:32:43.745 RecordOrderPublisher | Schedule publication for request counter 9
05:32:45.911 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:00:00.236001Z): OpenEnvelope( SignedProtocolMessage(
05:32:45.923 RecordOrderPublisher | Schedule publication for request counter 11
05:33:02.090 TransactionProcessor | Got result for Transaction request at RequestId(1970-01-01T00:00:00.337Z): OpenEnvelope( SignedProtocolMessage(
```

The mediators approved it at 05:32:45 with decision time 00:01:00.236Z. Only the sv2 mediator's send reached the
sequencer; sv1 sent for 00:00:00.236001Z but not for 00:00:00.236Z; the sv4 mediator's send was refused by its
blacklisted sequencer:
```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | jq -r 'select((.logger_name // "")|test("DefaultVerdictSender|ConfirmationRequestAndResponseProcessor")) | select(.message|test("Finalized request=RequestId\\(1970-01-01T00:00:00.236Z\\)|result (for|to sequencer for) request 1970-01-01T00:00:00.236")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>mediator=[A-Za-z0-9]+)").n) | \(.message|gsub("\\s+";" ")|.[0:240])"'
```
```
05:32:45.336 INFO mediator=globalMediatorSv2 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.236Z) with verdict Approve at 1970-01-01T00:01:00.236Z
05:32:45.339 INFO mediator=globalMediatorSv1 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.236Z) with verdict Approve at 1970-01-01T00:01:00.236Z
05:32:45.578 DEBUG mediator=globalMediatorSv2 | Sent result for request 1970-01-01T00:00:00.236Z
05:32:45.583 DEBUG mediator=globalMediatorSv2 | Sent result for request 1970-01-01T00:00:00.236001Z
05:32:45.906 DEBUG mediator=globalMediatorSv1 | Sent result for request 1970-01-01T00:00:00.236001Z
05:33:00.797 INFO mediator=globalMediatorSv4 | Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.236Z) with verdict Approve at 1970-01-01T00:01:00.236Z
05:33:00.864 ERROR mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T00:00:00.236Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,30cb7e5d): Mempool received client request but 
```

The two sv2 sends were sequenced at 00:00:00.239Z as `DeliverReceipt` only (aggregation not complete); the
00:00:00.236001Z result was delivered once sv1's send arrived (00:00:00.242Z, `Deliver`), the 00:00:00.236Z one never:
```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | jq -r 'select((.logger_name // "")|test("sequencer=globalSequencerSv1")) | select(.message|test("validated to:")) | "\(.["@timestamp"][11:23]) \(.message|gsub("\\s+";" ")|.[0:200])"' | awk '$1>"05:32:45.5" && $1<"05:32:46"'
```
```
05:32:45.565 At block 238, the submission request 407fe5c6-2870-4ed1-8a16-177d05ccfcc1 at 1970-01-01T00:00:00.239Z validated to: DeliverReceipt
05:32:45.566 At block 238, the submission request 66639d64-2e27-48f1-91ad-a78f768a5a1c at 1970-01-01T00:00:00.239001Z validated to: DeliverReceipt
05:32:45.890 At block 241, the submission request b705c93d-fa19-4405-bde4-234def0d1af0 at 1970-01-01T00:00:00.242Z validated to: Deliver
```
The global mediator group had reached threshold 2 by then:
```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | grep -a -o -E 'MediatorSynchronizerState\([^"]{0,40}global-domain[^"]{0,60}threshold = [0-9]+' | sed -E 's/::1220[0-9a-f]+/::../g; s/\\n *//g' | sort | uniq -c
```
```
    247 MediatorSynchronizerState(synchronizerId = global-domain::.., group = 0, threshold = 1
    ... (several print formats) ... threshold = 2   (141 lines in total)
```

Sim time never reaches the decision time 00:01:00.236Z: the latest record time the sv3 participant saw was 00:00:01.36:
```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | jq -r 'select((.logger_name // "")|test("participant=sv3Participant")) | select(.message|test("Storing delivery events")) | "\(.["@timestamp"][11:23]) \(.message[0:90])"' | tail -1
```
```
05:35:33.166 Storing delivery events from 1970-01-01T00:00:01.364002Z / 181 to 1970-01-01T00:00:01.3640
```
So the participant can neither publish request counter 10 nor anything after it (record order). Every SV app read
of the ledger API stalls from 05:32:44, which explains sv3's hung submissions and sv2's `Ensure amulet price has a
vote` failing at 05:35:29, and the stall persists into the following tests of the shard (shared Canton).

## 6. 11275: blacklisting at that moment

```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-07T05:29") | select(.message|test("blacklisted, rejecting|blacklisted nodes = List\\(SEQ")) | "\(.["@timestamp"][11:16]) \(.logger_name|capture("sequencer=(?<n>[A-Za-z0-9]+)").n // "-") \(.message|capture("(?<m>blacklisted, rejecting|blacklisted nodes = List\\(SEQ::[a-z0-9]+)").m)"' 2>/dev/null | awk '{c[$0]++} END{for(k in c) print k, c[k]}' | sort
```
```
05:32 globalSequencerSv1 blacklisted nodes = List(SEQ::sv4 1
05:32 globalSequencerSv2 blacklisted nodes = List(SEQ::sv4 1
05:32 globalSequencerSv3 blacklisted nodes = List(SEQ::sv4 1
05:32 globalSequencerSv4 blacklisted nodes = List(SEQ::sv4 1
05:33 - blacklisted, rejecting 28
05:33 globalSequencerSv1 blacklisted nodes = List(SEQ::sv4 2
...
05:33 globalSequencerSv4 blacklisted, rejecting 80
```
The rejected client is MED::sv4 only (20 rejections logged with the member):
```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | jq -r 'select(.message|test("blacklisted, rejecting")) | "\(.["@timestamp"][11:16]) \(.message|capture("by (?<m>Some\\([A-Z]+::[A-Za-z0-9]+)").m)"' | sort | uniq -c
```
```
     20 05:33 Some(MED::sv4
```
This is the one-per-Canton-instance blacklisting episode while sv2-sv4 sequencers are onboarded by the first multi-SV
test (splice#8361); here it coincides with the mediator group growing to sv1, sv2, sv4.

## 7. 11276: same episode, sv4 participant stalls

```
C=log/11276/logs-simtime-2/canton-simtime.clog.gz
zcat $C | jq -r 'select(.message|test("blacklisted, rejecting")) | "\(.["@timestamp"][11:19]) \(.message|capture("by (?<m>Some\\([A-Z]+::[A-Za-z0-9]+)").m)"' | awk '{m[$2]++; if(!($2 in f)) f[$2]=$1; l[$2]=$1} END{for(k in m) print k, f[k], l[k], m[k]}'
zcat $C | jq -r 'select(.level=="ERROR") | select((.logger_name // "")|test("DefaultVerdictSender")) | "\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<n>mediator=[A-Za-z0-9]+)").n) | \(.message|gsub("\\s+";" ")|.[0:200])"' | head -2
zcat $C | jq -r 'select((.logger_name // "")|test("ParallelIndexerSubscription")) | select(.["@timestamp"] > "2026-11-07T05:35") | "\(.logger_name|capture("participant=(?<n>[A-Za-z0-9]+)").n // "-") \(.["@timestamp"][11:19])"' | awk '{last[$1]=$2; if(!($1 in f)) f[$1]=$2} END{for(k in last) print k, f[k], last[k]}'
```
```
Some(MED::sv4 05:37:08 05:37:19 60
05:37:08.899 mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T01:01:00.375Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,09ad2a40): Mempool received
05:37:12.058 mediator=globalMediatorSv4 | Failed to send result to sequencer for request 1970-01-01T01:01:00.396Z RequestRefused( ...
sv3Participant 05:35:47 05:48:10
sv2Participant 05:35:53 05:48:10
bobParticipant 05:35:43 05:48:10
sv1Participant 05:35:43 05:48:10
splitwellParticipant 05:35:43 05:48:10
sv4Participant 05:35:46 05:36:57
aliceParticipant 05:35:43 05:48:10
```
sv4Validator init hangs from 05:36:57 until the 5 min timeout:
```
zcat log/11276/logs-simtime-2/canton_network_test.clog.gz | jq -r 'select(.logger_name|test("ValidatorApp:NonZero.*sv4Validator$")) | select(.message|test("app initialization:")) | "\(.["@timestamp"][11:23]) \(.message[0:120])"' | grep -v 'started$' | tail -3
```
```
05:36:57.812 sv4Validator app initialization: Get DSO party id finished after PT0.004238149S
05:42:19.971 sv4Validator app initialization: Onboard validator wallet users failed
05:42:19.971 sv4Validator app initialization: Initialize node failed
```
Not pinned: which request the sv4 participant is waiting on (the stall starts 11 s before the first logged rejection;
the rejection log lines are rate-limited per member, so earlier rejections may be unlogged).

## 8. Duplicate check

```
for q in 'extraFeaturedAssetActivityWeight' 'may not be dropped during downgrading' 'Failed to send result to sequencer' 'SvTimeBasedRoundMgmtIntegrationTest' 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest'; do gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue \"$q\" created:>=2026-10-15" --jq '.items[] | "\(.number) \(.title)"'; done
grep -n -i 'Failed to send result\|downgrad\|Inherited' ci-triage/known-families.md
```
No hits for any string (only issue bodies are indexed; comments may mention them). Family B (BFT onboarding
blacklisting) is the trigger for 11275/11276; the "lost verdict blocks the participant in simtime" consequence is not
in known-families.md.

## Verdict

- 11274: new, test bug from #8224 (adetokunbo): class-level scalatest tags on the abstract base class are ignored.
  Fix: move `@SpliceAmulet_0_1_25` (and `@SpliceTokenTestTradingApp_1_0_0`) to both concrete classes
  `TrafficBasedRewardsTimeBasedIntegrationTest` and `TrafficBasedRewardsDryRunTimeBasedIntegrationTest`, or add
  `@java.lang.annotation.Inherited` to the tag annotations in `apps/common/src/test/java/.../scalatesttags/`. Hard
  broken: fails every nightly until fixed.
- 11275: new (family B trigger): a confirmation result lost during the sv4 sequencer blacklisting never times out in
  simtime, because the participant decision timeout is in sim time; all SV participants stop publishing for the
  rest of the shard. Fix: #8356 (disable blacklisting in tests) removes this trigger. Open: why the sv1 mediator did
  not send a result for 00:00:00.236Z (it did for 00:00:00.236001Z); worth asking the DA BFT/Canton team since any
  lost verdict in simtime wedges the shard.
- 11276: duplicate of 11275 (same run, same episode; exact stuck request not pinned).

## Addendum 2026-11-09: why the second healthy mediator never sends (11275, 11284, 11258)

The mediator that finalizes the request but does not send is held back by Canton's delayed verdict sender, and
the delay is scheduled on the sequencer client clock, which is the sim clock in simtime tests:

```
sed -n 252,269p canton/community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/mediator/VerdictSender.scala
```
```
      // Delay the submission of the verdict to increase the chance that we don't have to process all mediator verdicts
      val delay = sendVerdictWithDelay.getOrElse(Duration.ZERO)
      ...
        logger.debug(s"Delaying sending of verdict by ${delay.toMillis} ms")
        EitherT
          .right(
            sequencerSend.clock
              .scheduleAfterCancelledOnShutdown(_ => (), "delayed-send-verdict", delay)
          )
          .flatMap(_ => doSend())
```
Who is delayed (VerdictSender.scala:375): `position = ((index + requestId.toMicros) % group.active.size) - group.threshold - livenessMargin`;
negative sends at once. Splice tests set `liveness-margin = 1` (apps/app/src/test/resources/include/mediators.conf,
#6153), so with 4 active mediators and threshold 2, three send at once and one waits 3 s. With 3 active and
threshold 2 nobody waits, so the delay lines below prove the group already had 4 members (sv3 added):

```
zcat log/11275/logs-simtime-4/canton-simtime.clog.gz | grep -a -o 'MediatorSynchronizerState[^"]\{0,400\}' | grep -o 'active = Seq([^)]*)\|threshold = [0-9]*' | sort | uniq -c
```
```
    157 active = Seq(MED::sv1::1220e34a2dab..., MED::sv2::122021acce38...)
    154 active = Seq(MED::sv1::1220e34a2dab..., MED::sv2::122021acce38..., MED::sv4::122054a12eb3...)
    141 active = Seq(MED::sv1::1220e34a2dab..., MED::sv2::122021acce38..., MED::sv4::122054a12eb3..., MED::sv3::1220d9b2381d...)
    388 threshold = 1
    141 threshold = 2
```
(11284: the same three shapes, 240 lines with four members and 240 with threshold 2.)

The delayed mediator, same trace id as its Finalized line (`jq 'select(.["trace-id"]==$t and (.logger_name|contains($m)))'`):
```
11275: 05:32:45.339 ConfirmationRequestAndResponseProcessor: Phase 6: Finalized request=RequestId(1970-01-01T00:00:00.236Z) with verdict Approve at 1970-01-01T00:01:00.236Z   (globalMediatorSv1)
11275: 05:32:45.340 DefaultVerdictSender:mediator=globalMedi Delaying sending of verdict by 3000 ms   (globalMediatorSv1)
11284: 05:34:12.277 DefaultVerdictSender:mediator=globalMedi Delaying sending of verdict by 3000 ms   (globalMediatorSv2, trace of its Finalized line for 03:03:00.303001Z)
11258 (packet line 206): 2026-11-06T17:03:10.916Z mediator=globalMediatorSv1 Delaying sending of verdict by 3000 ms
```
No `Sent result` line for the stuck request follows from the delayed mediator in any of the three runs.

The new sv3 mediator is in the group but its sequencer subscription starts after the request, so it never sees it:
```
zcat log/<ref>/<dir>/canton-simtime.clog.gz | jq -r 'select(.logger_name|test("DbCursorPreheadStore:mediator=globalMediatorSv3")) | ...' | head -2
```
```
11275: 05:33:03.678 ...CursorPrehead(counter = 0, timestamp = 1970-01-01T00:00:00.238009Z)   (request 00:00:00.236Z)
11284: 05:34:23.225 ...CursorPrehead(counter = 0, timestamp = 1970-01-01T03:03:00.305009Z)   (request 03:03:00.303001Z)
```
No globalMediatorSv3 line mentions either request.

So of the three immediate senders one is sv3 (never saw the request), one is sv4 (refused by its blacklisted
sequencer), and one sends: 1 of 2. The fourth, the fallback, waits 3 s of sim time, which never pass because the
test waits on the ledger before it advances the clock. #8356 (open) removes the sv4 refusal; the sv3 gap and the
sim-clock delay remain. Precedent for a simtime-only override: `simple-topology-canton-simtime.conf` already
disables the sequencer circuit breaker because it is based on block time. Candidate fix (not tested):
`_mediator_template.parameters.delayed-verdict-sender.enabled = false` in `simple-topology-canton-simtime.conf`.

### When sv4 is blacklisted (globalSequencerSv1 epochs, 11275 and 11284)

```
zcat log/<ref>/<dir>/canton-simtime.clog.gz | jq -r 'select(.logger_name|test("globalSequencerSv1")) | select(.message|test("^New epoch")) | ... leaders, blacklisted nodes, nodes of the ordering topology'
```
```
== 11275 (stuck request sequenced 05:32:44-45, sv4 refusal 05:33:00.864)
05:32:27.081 epoch 12 leaders=sv1,sv2 blacklisted= nodes=sv1,sv2
05:32:38.080 epoch 13 leaders=sv2,sv1 blacklisted= nodes=sv1,sv2
05:32:47.898 epoch 14 leaders=sv3,sv4,sv1,sv2 blacklisted= nodes=sv1,sv2,sv3,sv4
05:32:59.949 epoch 15 leaders=sv1,sv2,sv3 blacklisted=sv4 nodes=sv1,sv2,sv3,sv4
05:33:13.467 epoch 16 leaders=sv2,sv3,sv1 blacklisted=sv4 nodes=sv1,sv2,sv3,sv4
05:33:27.505 epoch 17 leaders=sv3,sv1,sv2 blacklisted=sv4 nodes=sv1,sv2,sv3,sv4
05:33:42.499 epoch 18 leaders=sv3,sv4,sv1,sv2 blacklisted= nodes=sv1,sv2,sv3,sv4
== 11284 (stuck request sequenced 05:34:12, sv4 refusal 05:34:26.457)
05:34:05.695 epoch 78 leaders=sv1,sv2 blacklisted= nodes=sv1,sv2
05:34:13.330 epoch 79 leaders=sv4,sv1,sv2,sv3 blacklisted= nodes=sv1,sv2,sv3,sv4
05:34:25.014 epoch 80 leaders=sv3,sv1,sv2 blacklisted=sv4 nodes=sv1,sv2,sv3,sv4
05:34:38.239 epoch 81 leaders=sv1,sv2,sv3 blacklisted=sv4 nodes=sv1,sv2,sv3,sv4
05:34:52.294 epoch 82 leaders=sv2,sv3,sv1 blacklisted=sv4 nodes=sv1,sv2,sv3,sv4
```
In both runs the ordering topology goes 1 -> 2 -> 4 (sv3 and sv4 in one step), and sv4 is blacklisted from the
first epoch after the 2 -> 4 switch, i.e. sv4 failed its segment as a newcomer leader in the switch epoch. The stuck
request is sequenced right at the switch. Not checked: why the sv4 segment failed (catch-up, as 11257 / 11173).

## Addendum 2026-11-09 (2): blacklisting only after a two-newcomer step

Supersedes "sv4 failed its segment as a newcomer leader" (section above, never verified). Epoch tables from the
packets: 11257 section 3 (`evidence/11257-onboard-sv-sequencer-timeout-bft-1-to-2.md`), 11173 section 5
(`evidence/11173-distributeddomain-topup-in-flight-circuit-breaker-bft-sv4-segment-stall.md`), 11275 and 11284
"When sv4 is blacklisted" above.

| Run | Step | Blacklisted afterwards |
|---|---|---|
| 11257 (wall clock) | 1 -> 2, sv4 joins alone (epoch 166, 16:36:16.576) | nobody, epochs 166-169 |
| 11257 | 2 -> 4, sv2 and sv3 join (epoch 169, 16:36:59.070) | sv4 from epoch 170 (16:37:14.650), not a newcomer of this step |
| 11173 (wall clock) | 2 -> 4, sv3 and sv4 join (epoch 279) | sv4, epochs 280-282 and 284-288 |
| 11275 (simtime) | 2 -> 4, sv3 and sv4 join (epoch 14, 05:32:47.898) | sv4, epochs 15-17 |
| 11284 (simtime) | 2 -> 4, sv3 and sv4 join (epoch 79, 05:34:13.330) | sv4, epochs 80-82 |

Reading: blacklisting appears only after a step that adds two sequencers at once; the one single-newcomer step
(11257, 1 -> 2) blacklisted nobody (it stalled 33 s below strong quorum instead). So onboarding one at a time
probably removes the lost-verdict shape as well as the rejected and lost-batch shapes; #8356 removes only this
shape. Not tested: no run onboards all three one at a time (4 runs total). Open question: the blacklisted node is
always sv4, also in 11257 where sv4 joined three epochs before the step.
