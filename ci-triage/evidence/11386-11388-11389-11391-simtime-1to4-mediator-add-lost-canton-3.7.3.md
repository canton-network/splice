# 11386, 11388, 11389, 11391 - simtime on main and release-line-0.10.2 2026-11-12 (Canton 3.7.3): mediator-add topology change lost at the 1 -> 4 ordering step, joining SV never initializes (runs 43211423532, 43211354109, 43228778010)

DUPLICATE of 11255 (family B, rejected-send / batch-never-ordered shape), all four. Same mechanism as 11358, 11362,
11368, 11374 (2026-11-11) and 11381 (2026-11-12, closed as dup by rautenrieth-da). In each shard one suite's 4-SV
environment setup grows the global ordering topology from 1 to 4 sequencers in one step; within 0.2 to 1.1 s after the
switch the SV participants' signatures on the MediatorSynchronizerState serial 4 proposal (adds the joining SV's
mediator) are rejected by globalSequencerSv1 with `P2P connectivity is not ready (authenticated = 1 < dissemination
quorum = 2)`, or pushed and never answered, or never pushed because the participant's outbox flush is still
waiting for an earlier push. The proposal never gets enough signatures, and the joining SV app waits on `Mediator has
been granted unlimited traffic` until the 5 min init wait gives up. No blacklisting (zero `currently blacklisted`),
no lost verdicts (zero `Failed to send result`), no false off-boarding. Because the failure is in `Running setup`,
the topology reset plugins never run and every later suite in the shard fails sv1's DSO party allocation with
`TOPOLOGY_UNAUTHORIZED_TRANSACTION` (11386, 11389, 11391; in 11388 the failing suite was the last in the shard),
plus the family H Prometheus port cascade (`Creating fixture`) in 11386 and 11389.
Nothing new from Canton 3.7.3 final: #8453 says the release has "the same contents as the snapshot version we already
had" (3.7.3-snapshot.20261111, on which 11374 already failed this way). splice#8356 (blacklisting off) is in both shas;
it never targeted this shape. The release-line-0.10.2 runs are on the same code: 43211423532 ran on 4db185089a, the
same sha as main run 43211354109, and e214318836 only adds the release commit.

- Runs (all post-merge, attempt 1):
  - 11386: https://github.com/canton-network/splice/actions/runs/43211423532, release-line-0.10.2 4db185089a ("Bump Canton to 3.7.3 (#8453)"), job 131160106132 `simtime (5)`.
  - 11388: https://github.com/canton-network/splice/actions/runs/43211354109, main 4db185089a, job 131159873787 `simtime (0)`.
  - 11389: same run, job 131159873807 `simtime (2)`.
  - 11391: https://github.com/canton-network/splice/actions/runs/43228778010, release-line-0.10.2 e214318836 ("[release] Release 0.10.2 [ci] (#8454)"), job 131219123664 `simtime (4)`.
- Runtime canton: 3.7.3 (both shas).
- Component: Canton BFT ordering at the 1 -> 4 ordering-topology step under sim time (11255); test framework
  (setup-failure path skips the topology reset plugins).

## 0. Issues (verbatim) and job ids

```
for r in 11386 11388 11389 11391; do gh api repos/DACH-NY/cn-test-failures/issues/$r --jq '"\(.title)\nstate=\(.state) created=\(.created_at) comments=\(.comments) assignees=\([.assignees[].login]|join(","))\n\(.body)"'; done
```
```
GHA Run 43211423532 : job simtime (5) in scala_test Failed :fire:
state=open created=2026-11-12T12:19:16Z comments=0 assignees=

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43211423532).
Job: simtime (5) in scala_test
Branch: [refs/heads/release-line-0.10.2](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.2)
Workflow: CI post-merge to main or release-line branches
Commit: [4db1850](https://github.com/canton-network/splice/commit/4db185089a4e7892d7aad1b557650f9c6e65f492) Bump Canton to 3.7.3 (#8453)
Actor: martinflorian-da

GHA Run 43211354109 : job simtime (0) in scala_test Failed :fire:
state=open created=2026-11-12T12:21:05Z comments=0 assignees=

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43211354109).
Job: simtime (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [4db1850](https://github.com/canton-network/splice/commit/4db185089a4e7892d7aad1b557650f9c6e65f492) Bump Canton to 3.7.3 (#8453)
Actor: martinflorian-da

GHA Run 43211354109 : job simtime (2) in scala_test Failed :fire:
state=open created=2026-11-12T12:26:51Z comments=0 assignees=

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43211354109).
Job: simtime (2) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [4db1850](https://github.com/canton-network/splice/commit/4db185089a4e7892d7aad1b557650f9c6e65f492) Bump Canton to 3.7.3 (#8453)
Actor: martinflorian-da

GHA Run 43228778010 : job simtime (4) in scala_test Failed :fire:
state=open created=2026-11-12T14:18:35Z comments=0 assignees=

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43228778010).
Job: simtime (4) in scala_test
Branch: [refs/heads/release-line-0.10.2](https://github.com/canton-network/splice/tree/refs/heads/release-line-0.10.2)
Workflow: CI post-merge to main or release-line branches
Commit: [e214318](https://github.com/canton-network/splice/commit/e214318836feb7cee420dee33e85c7a6abbc7c04) [release] Release 0.10.2 [ci] (#8454)
Actor: martinflorian-da
```

Job ids (recipe 1, exact-name match, all attempts, conclusion failure; one match each):

```
cat log/11386/jobs.tsv   # recipe 1 for each ref: ref, run id, job id, attempt, job name
```
```
11386 43211423532 131160106132 attempt=1 ci / scala_test_sim_time / simtime (5)
11388 43211354109 131159873787 attempt=1 ci / scala_test_sim_time / simtime (0)
11389 43211354109 131159873807 attempt=1 ci / scala_test_sim_time / simtime (2)
11391 43228778010 131219123664 attempt=1 ci / scala_test_sim_time / simtime (4)
```

Runs, shas, Canton pin, #8356 ancestry:

```
for r in 43211423532 43211354109 43228778010; do gh api "repos/canton-network/splice/actions/runs/$r" --jq '"\(.head_branch) \(.head_sha) attempt=\(.run_attempt) \(.created_at) \(.conclusion)"'; done
for s in 4db185089a e214318836; do echo "== $s $(git log -1 --format='%s' $s)"; git show $s:nix/canton-sources.json | grep -m1 version; done
git show 4db185089a^:nix/canton-sources.json | grep -m1 version
M=$(gh pr view 8356 -R canton-network/splice --json mergeCommit --jq .mergeCommit.oid); for s in 4db185089a e214318836; do git merge-base --is-ancestor $M $s && echo "$s contains #8356"; done
git log --oneline -3 origin/release-line-0.10.2
git show 4db185089a --stat | sed -n 5,12p
```
```
release-line-0.10.2 4db185089a4e7892d7aad1b557650f9c6e65f492 attempt=1 2026-11-12T11:42:19Z failure
main 4db185089a4e7892d7aad1b557650f9c6e65f492 attempt=1 2026-11-12T11:41:50Z failure
release-line-0.10.2 e214318836feb7cee420dee33e85c7a6abbc7c04 attempt=1 2026-11-12T13:35:35Z failure
== 4db185089a Bump Canton to 3.7.3 (#8453)
  "version": "3.7.3",
== e214318836 [release] Release 0.10.2 [ci] (#8454)
  "version": "3.7.3",
  "version": "3.7.3-snapshot.20261111.20904.0.vbe797d9d",
4db185089a contains #8356
e214318836 contains #8356
e214318836 [release] Release 0.10.2 [ci] (#8454)
4db185089a Bump Canton to 3.7.3 (#8453)
0a0229c744 Check whether artifacts are public, before finishing the release (#8443)
    Bump Canton to 3.7.3 (#8453)

    For DACH-NY/canton-network-internal#8000

    Quite literally the same contents as the snapshot version we already had.

    Signed-off-by: Martin Florian <martin.florian@digitalasset.com>
```

Artifacts (recipe 3), downloaded to `log/<ref>/logs-simtime-<n>/`:

```
for r in 43211423532 43211354109 43228778010; do gh api "repos/canton-network/splice/actions/runs/$r/artifacts?per_page=100" --jq '.artifacts[] | select(.name|test("simtime")) | "\(.id) \(.size_in_bytes) \(.name) \(.created_at) expired=\(.expired)"'; done
```
```
13943323624 164819913 logs-simtime-5 2026-11-12T12:19:03Z expired=false
13943288652 176345 logs-simtime-5-runner 2026-11-12T12:19:05Z expired=false
13944506469 172629 logs-simtime-0-runner 2026-11-12T12:20:56Z expired=false
13944196768 207877975 logs-simtime-0 2026-11-12T12:20:54Z expired=false
13943784153 214818058 logs-simtime-2 2026-11-12T12:26:40Z expired=false
13943689237 177471 logs-simtime-2-runner 2026-11-12T12:26:42Z expired=false
13951798843 172746 logs-simtime-4-runner 2026-11-12T14:18:22Z expired=false
13951733756 207292339 logs-simtime-4 2026-11-12T14:18:20Z expired=false
```

## 1. Failing tests: environment init timeouts and their cascade

```
for j in 131160106132 131159873787 131159873807 131219123664; do gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/$j/logs > log/11386/job-$j.log; done   # recipe 1
for j in 131160106132 131159873787 131159873807 131219123664; do echo "######## $j"; sed -E 's/\x1b\[[0-9;]*m//g' log/11386/job-$j.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Creating environment failed' | sed -E 's/^[^Z]*Z //' | sort | uniq -c | cut -c1-200; done
```
```
######## 131160106132
      2 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
      2 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
      2 [info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
      2 [info] - expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts *** FAILED ***
      1 [info] Tests: succeeded 4, failed 2, canceled 0, ignored 1, pending 0
######## 131159873787
      2 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
      1 [info] Tests: succeeded 15, failed 0, canceled 0, ignored 0, pending 0
######## 131159873807
      2 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
      6 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
      2 [info] - Disabled wallet *** FAILED ***
      2 [info] - SV triggers complete the reward pipeline for the initial round *** FAILED ***
      2 [info] - TestNet initializes correctly *** FAILED ***
      1 [info] - list and automatically collect app & validator rewards *** FAILED ***
      1 [info] - should list and automatically collect app & validator rewards *** FAILED ***
      1 [info] Tests: succeeded 5, failed 4, canceled 0, ignored 0, pending 0
######## 131219123664
      4 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
      1 [info] Tests: succeeded 7, failed 0, canceled 0, ignored 0, pending 0
```

(11388 and 11391 report `failed 0` because the suites whose setup failed abort before any test case starts.)

The `Creating fixture` failures (11386 ExternallySignedTxs, 11389 later suite) are the port-reuse cascade (family H):

```
for j in 131160106132 131159873807; do sed -E 's/\x1b\[[0-9;]*m//g' log/11386/job-$j.log | grep -a -A1 'Creating environment failed at step Creating fixture' | sed -E 's/^[^Z]*Z //' | sort -u; done
```
```
--
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
--
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
```

## 2. Suite timelines: the first failing suite is a joining SV's init in setup; no reset after it

```
cd log; for d in 11386/logs-simtime-5 11388/logs-simtime-0 11389/logs-simtime-2 11391/logs-simtime-4; do echo "=== $d"; zcat $d/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Timeout while waiting for initialization|has been reset" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200; done
```
```
=== 11386/logs-simtime-5 (from the last passing suite)
2026-11-12T12:08:00.393Z decentralized namespace has been reset",
2026-11-12T12:08:00.404Z sequencer domain state threshold has been reset",
2026-11-12T12:08:00.441Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...",
2026-11-12T12:13:37.054Z Timeout while waiting for initialization of sv3",
2026-11-12T12:13:37.767Z Starting test suite 'SvTimeBasedOnboardingIntegrationTest'...",
2026-11-12T12:18:41.406Z Timeout while waiting for initialization of sv1Scan",
2026-11-12T12:18:41.415Z Test failed: 'SvTimeBasedOnboardingIntegrationTest/expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts', message: The
2026-11-12T12:18:41.426Z Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...",
2026-11-12T12:18:41.649Z Test failed: 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance',
=== 11388/logs-simtime-0 (from the last passing suite)
2026-11-12T12:12:59.003Z decentralized namespace has been reset",
2026-11-12T12:12:59.015Z sequencer domain state threshold has been reset",
2026-11-12T12:12:59.047Z Starting test suite 'SvTimeBasedRewardCouponIntegrationTest'...",
2026-11-12T12:20:29.344Z Timeout while waiting for initialization of sv4",
=== 11389/logs-simtime-2 (from the last passing suite)
2026-11-12T12:03:00.197Z decentralized namespace has been reset",
2026-11-12T12:03:00.210Z sequencer domain state threshold has been reset",
2026-11-12T12:03:00.242Z Test succeeded: 'TimeBasedTreasuryIntegrationTestWithoutMerging/scan-connection caching avoids unnecessary network calls and re-sending contracts if they are already known by 
2026-11-12T12:03:00.322Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
2026-11-12T12:10:19.755Z Timeout while waiting for initialization of sv2",
2026-11-12T12:10:19.959Z Test failed: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:234), l
2026-11-12T12:10:19.978Z Starting test suite 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest'...",
2026-11-12T12:15:56.601Z Timeout while waiting for initialization of sv3",
2026-11-12T12:15:56.668Z Test failed: 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest/SV triggers complete the reward pipeline for the initial round', message: Creating environment failed at step Ru
2026-11-12T12:15:56.678Z Starting test suite 'WalletRewardsTimeBasedIntegrationTest'...",
2026-11-12T12:21:13.587Z Timeout while waiting for initialization of sv1",
2026-11-12T12:21:13.625Z Test failed: 'WalletRewardsTimeBasedIntegrationTest/A wallet should list and automatically collect app & validator rewards', message: Creating environment failed at step Runni
2026-11-12T12:21:13.636Z Starting test suite 'DisabledWalletTimeBasedIntegrationTest'...",
2026-11-12T12:26:15.453Z Suppressed ERROR: Timeout while waiting for initialization of sv1",
2026-11-12T12:26:15.456Z Test failed: 'DisabledWalletTimeBasedIntegrationTest/Disabled wallet', message: The app state of sv1Scan is currently not accessible. Node doesn't have any app state., locatio
2026-11-12T12:26:15.463Z Starting test suite 'WalletAmuletPriceTimeBasedIntegrationTest'...",
=== 11391/logs-simtime-4 (from the last passing suite)
2026-11-12T14:07:03.288Z decentralized namespace has been reset",
2026-11-12T14:07:03.302Z sequencer domain state threshold has been reset",
2026-11-12T14:07:03.348Z Test succeeded: 'ValidatorFaucetCapZeroTimeBasedIntegrationTest/system works with optValidatorFaucetCap=0 and handles stale liveness records'",
2026-11-12T14:07:03.367Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...",
2026-11-12T14:12:37.311Z Timeout while waiting for initialization of sv3",
2026-11-12T14:12:37.622Z Starting test suite 'WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest'...",
2026-11-12T14:17:54.635Z Timeout while waiting for initialization of sv1",
```

First failing suite per shard: 11386 and 11391 SvExpiredRewardsCollectionTimeBased (sv3), 11388
SvTimeBasedRewardCoupon (sv4, last suite of the shard), 11389 TimeBasedTestNetPreview (sv2). Every suite after it fails
on init (victims).

## 3. What the joining SV waited on: the mediator add

```
cd log; f(){ d=$1 suite=$2 sv=$3 from=$4 to=$5; echo "=== $d $suite $sv"; zcat $d/canton_network_test.clog.gz | grep -a "$suite/" | grep -a "SV=$sv\"" | grep -a -E 'Waiting until|Gave up|app initialization: [^"]*(started|failed)"' | awk -F'"@timestamp":"' -v f=$from -v t=$to '{split($2,a,"\""); if (a[1] > f && a[1] < t) print}' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | tail -8; }
f 11386/logs-simtime-5 SvExpiredRewardsCollectionTimeBasedIntegrationTest sv3 2026-11-12T12:08 2026-11-12T12:14
f 11388/logs-simtime-0 SvTimeBasedRewardCouponIntegrationTest sv4 2026-11-12T12:12 2026-11-12T12:21
f 11389/logs-simtime-2 TimeBasedTestNetPreviewIntegrationTest sv2 2026-11-12T12:03 2026-11-12T12:11
f 11391/logs-simtime-4 SvExpiredRewardsCollectionTimeBasedIntegrationTest sv3 2026-11-12T14:07 2026-11-12T14:13
```
```
=== 11386/logs-simtime-5 SvExpiredRewardsCollectionTimeBasedIntegrationTest sv3
2026-11-12T12:09:49.916Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::122077d61a91..., mediatorId = MED::sv3::122040dc3416..., sequencer
2026-11-12T12:09:53.895Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-12T12:13:37.204Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-12T12:13:37.205Z Gave up ensuring Add mediator MED::sv3::122040dc3416...", INFO
2026-11-12T12:13:37.205Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
...
=== 11388/logs-simtime-0 SvTimeBasedRewardCouponIntegrationTest sv4
2026-11-12T12:14:58.481Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::12208f80b0a6..., mediatorId = MED::sv4::122020ffe0c3..., sequencer
2026-11-12T12:15:04.890Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-12T12:20:29.507Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-12T12:20:29.508Z sv4 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
2026-11-12T12:20:29.508Z Gave up ensuring Add mediator MED::sv4::122020ffe0c3...", INFO
...
=== 11389/logs-simtime-2 TimeBasedTestNetPreviewIntegrationTest sv2
2026-11-12T12:04:52.715Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::1220b6d8932a..., mediatorId = MED::sv2::122064a66425..., sequencer
2026-11-12T12:04:56.909Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-12T12:10:19.883Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-12T12:10:19.884Z Gave up ensuring Add mediator MED::sv2::122064a66425...", INFO
2026-11-12T12:10:19.884Z sv2 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
...
=== 11391/logs-simtime-4 SvExpiredRewardsCollectionTimeBasedIntegrationTest sv3
2026-11-12T14:09:19.002Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::122005c14d30..., mediatorId = MED::sv3::122000dea825..., sequencer
2026-11-12T14:09:24.917Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-12T14:12:37.525Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-12T14:12:37.525Z Gave up ensuring Add mediator MED::sv3::122000dea825...", INFO
2026-11-12T14:12:37.525Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
...
```

Time in that final wait: 11386 223 s, 11388 325 s, 11389 323 s, 11391 193 s.

## 4. Canton: the 1 -> 4 step, no blacklisting, participant sends rejected

```
cd log; for d in 11386/logs-simtime-5 11388/logs-simtime-0 11389/logs-simtime-2 11391/logs-simtime-4; do echo "=== $d"; zcat $d/canton-simtime.clog.gz | awk '/currently blacklisted/{b++} /Failed to send result/{f++} /P2P connectivity is not ready/{p++} /blacklist/{bl++} END{print "currently_blacklisted",b+0,"failed_send_result",f+0,"p2p_not_ready",p+0,"any_blacklist",bl+0}'; done
for d in 11386/logs-simtime-5 11388/logs-simtime-0 11389/logs-simtime-2 11391/logs-simtime-4; do echo "$d off-boarded: $(zcat $d/canton-simtime.clog.gz | grep -a -c "this node isn't part of it")"; done
```
```
=== 11386/logs-simtime-5
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 54 any_blacklist 18017
=== 11388/logs-simtime-0
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 54 any_blacklist 19370
=== 11389/logs-simtime-2
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 43 any_blacklist 27828
=== 11391/logs-simtime-4
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 18 any_blacklist 21435
11386/logs-simtime-5 off-boarded: 0
11388/logs-simtime-0 off-boarded: 0
11389/logs-simtime-2 off-boarded: 0
11391/logs-simtime-4 off-boarded: 0
```

Ordering-topology size changes on the global synchronizer and the rejected submissions in the failing suite's window
(one line per sequencer and size change; splitwell synchronizer filtered out):

```
cd log; g(){ d=$1 from=$2 to=$3; echo "=== $d ($from - $to)"; zcat $d/canton-simtime.clog.gz | awk -F'"@timestamp":"' -v f=$from -v t=$to '{split($2,a,"\""); if (a[1] > f && a[1] < t) print}' | grep -a -E 'New epoch [0-9]+ has started|Rejecting submission request by|Failed to send result|currently blacklisted' | grep -a -v 'splitwellValidator' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n */ /g' | sed -E 's/(New epoch [0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\).*size = ([0-9]+),.*sequencer=([A-Za-z0-9]+).*/\1 blacklisted=[\3] size=\4 \5/' | cut -c1-230 | awk '{sz=""; if (match($0,/size=[0-9]+/)) sz=substr($0,RSTART,RLENGTH); if ($0 ~ /New epoch/) { if (sz!=last[$NF]) print; last[$NF]=sz } else print}' | head -20; }
g 11386/logs-simtime-5 2026-11-12T12:08 2026-11-12T12:14
g 11388/logs-simtime-0 2026-11-12T12:12:59 2026-11-12T12:21
g 11389/logs-simtime-2 2026-11-12T12:03 2026-11-12T12:11
g 11391/logs-simtime-4 2026-11-12T14:07 2026-11-12T14:13
```
```
=== 11386/logs-simtime-5 (2026-11-12T12:08 - 2026-11-12T12:14)
2026-11-12T12:08:04.926Z New epoch 75 blacklisted=[] size=1 globalSequencerSv1
2026-11-12T12:09:50.296Z New epoch 84 blacklisted=[] size=4 globalSequencerSv1
2026-11-12T12:09:50.475Z Rejecting submission request by Some(PAR::sv3::1220b98ac0bf...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T12:09:50.557Z Rejecting submission request by Some(PAR::sv2::1220ebdc38c0...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T12:09:50.767Z Rejecting submission request by Some(PAR::sv4::1220ccc4b145...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T12:09:57.987Z New epoch 84 blacklisted=[] size=4 globalSequencerSv3
2026-11-12T12:09:58.398Z New epoch 84 blacklisted=[] size=4 globalSequencerSv4
2026-11-12T12:09:58.460Z New epoch 84 blacklisted=[] size=4 globalSequencerSv2
2026-11-12T12:10:00.368Z Rejecting submission request by Some(MED::sv4::12201e3d4501...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:f
=== 11388/logs-simtime-0 (2026-11-12T12:12:59 - 2026-11-12T12:21)
2026-11-12T12:13:07.037Z New epoch 125 blacklisted=[] size=1 globalSequencerSv1
2026-11-12T12:14:58.803Z New epoch 134 blacklisted=[] size=4 globalSequencerSv1
2026-11-12T12:14:59.073Z Rejecting submission request by Some(PAR::sv3::12204f316e66...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T12:14:59.073Z Rejecting submission request by Some(PAR::sv4::122086016990...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T12:14:59.847Z Rejecting submission request by Some(PAR::sv1::1220ccd05d99...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T12:15:09.138Z New epoch 134 blacklisted=[] size=4 globalSequencerSv4
2026-11-12T12:15:09.480Z New epoch 134 blacklisted=[] size=4 globalSequencerSv3
2026-11-12T12:15:10.518Z New epoch 134 blacklisted=[] size=4 globalSequencerSv2
2026-11-12T12:15:12.318Z Rejecting submission request by Some(MED::sv2::1220c28c0b72...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:8
=== 11389/logs-simtime-2 (2026-11-12T12:03 - 2026-11-12T12:11)
2026-11-12T12:03:06.076Z New epoch 66 blacklisted=[] size=1 globalSequencerSv1
2026-11-12T12:04:52.814Z New epoch 75 blacklisted=[] size=4 globalSequencerSv1
2026-11-12T12:04:53.508Z Rejecting submission request by Some(PAR::sv1::1220c5643c46...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T12:05:01.139Z New epoch 75 blacklisted=[] size=4 globalSequencerSv2
2026-11-12T12:05:01.223Z New epoch 75 blacklisted=[] size=4 globalSequencerSv4
2026-11-12T12:05:01.472Z New epoch 75 blacklisted=[] size=4 globalSequencerSv3
=== 11391/logs-simtime-4 (2026-11-12T14:07 - 2026-11-12T14:13)
2026-11-12T14:07:06.777Z New epoch 129 blacklisted=[] size=1 globalSequencerSv1
2026-11-12T14:09:19.993Z New epoch 140 blacklisted=[] size=4 globalSequencerSv1
2026-11-12T14:09:20.192Z Rejecting submission request by Some(PAR::sv1::1220fff746c5...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-12T14:09:29.433Z New epoch 140 blacklisted=[] size=4 globalSequencerSv3
2026-11-12T14:09:30.493Z New epoch 140 blacklisted=[] size=4 globalSequencerSv2
2026-11-12T14:09:31.446Z New epoch 140 blacklisted=[] size=4 globalSequencerSv4
```

## 5. The rejected or unanswered sends are the signatures on the proposal that adds the joining SV's mediator

Signing of MediatorSynchronizerState next to the participant rejections (serial 3 = previous SV's mediator,
threshold 1, went through before the switch; serial 4 = the joining SV's mediator, threshold 2):

```
cd log; g(){ d=$1 from=$2 to=$3; echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a -E 'Attempting to build, sign, and Replace MediatorSynchronizerState|Rejecting submission request by Some\(PAR' | awk -F'"@timestamp":"' -v f=$from -v t=$to '{split($2,a,"\""); if (a[1] > f && a[1] < t) print}' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n */ /g; s/active = Seq\(([^)]*)\).*with serial (Some\([0-9]+\)).*participant=([A-Za-z0-9]+).*/active=[\1] serial \2 \3/; s/with SequencerDeliverError.*/P2P not ready/; s/::1220[0-9a-f]+\.\.\.//g' | cut -c1-260; }
g 11386/logs-simtime-5 2026-11-12T12:09:48 2026-11-12T12:09:52; g 11388/logs-simtime-0 2026-11-12T12:14:56 2026-11-12T12:15:01; g 11389/logs-simtime-2 2026-11-12T12:04:50 2026-11-12T12:04:55; g 11391/logs-simtime-4 2026-11-12T14:09:17 2026-11-12T14:09:22
```
```
=== 11386/logs-simtime-5
...
2026-11-12T12:09:50.441Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv3Participant
2026-11-12T12:09:50.475Z Rejecting submission request by Some(PAR::sv3) P2P not ready
2026-11-12T12:09:50.531Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv2Participant
2026-11-12T12:09:50.557Z Rejecting submission request by Some(PAR::sv2) P2P not ready
2026-11-12T12:09:50.645Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv1Participant
2026-11-12T12:09:50.742Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv4Participant
2026-11-12T12:09:50.767Z Rejecting submission request by Some(PAR::sv4) P2P not ready
=== 11388/logs-simtime-0
...
2026-11-12T12:14:58.817Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv3, MED::sv4] serial Some(4) sv2Participant
2026-11-12T12:14:59.036Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv3, MED::sv4] serial Some(4) sv4Participant
2026-11-12T12:14:59.037Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv3, MED::sv4] serial Some(4) sv3Participant
2026-11-12T12:14:59.073Z Rejecting submission request by Some(PAR::sv3) P2P not ready
2026-11-12T12:14:59.073Z Rejecting submission request by Some(PAR::sv4) P2P not ready
2026-11-12T12:14:59.821Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv3, MED::sv4] serial Some(4) sv1Participant
2026-11-12T12:14:59.847Z Rejecting submission request by Some(PAR::sv1) P2P not ready
=== 11389/logs-simtime-2
...
2026-11-12T12:04:52.434Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv3, MED::sv4, MED::sv2] serial Some(4) sv3Participant
2026-11-12T12:04:52.579Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv3, MED::sv4, MED::sv2] serial Some(4) sv4Participant
2026-11-12T12:04:52.740Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv3, MED::sv4, MED::sv2] serial Some(4) sv2Participant
2026-11-12T12:04:53.483Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv3, MED::sv4, MED::sv2] serial Some(4) sv1Participant
2026-11-12T12:04:53.508Z Rejecting submission request by Some(PAR::sv1) P2P not ready
=== 11391/logs-simtime-4
...
2026-11-12T14:09:19.493Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv2Participant
2026-11-12T14:09:19.518Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv4Participant
2026-11-12T14:09:19.584Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv3Participant
2026-11-12T14:09:20.152Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] serial Some(4) sv1Participant
2026-11-12T14:09:20.192Z Rejecting submission request by Some(PAR::sv1) P2P not ready
```

(`...` = the serial 2 and 3 lines, threshold 1, before the switch.)

Per participant: push and answer (`Accepted,TxHash`) of each topology push, failing-suite window up to the timeout.
Only answers matter for the serial 4 transaction; the hashes differ per run:

```
cd log; p(){ d=$1 from=$2 to=$3; echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a -E 'Attempting to build, sign, and Replace MediatorSynchronizerState|responded the following for the given topology transactions|Attempting to push [0-9]+ topology transactions' | awk -F'"@timestamp":"' -v f=$from -v t=$to '{split($2,a,"\""); if (a[1] > f && a[1] < t) print}' | grep -a -oE '"@timestamp":"[^"]+"|participant=sv[1-4]Participant|serial Some\([0-9]+\)|Accepted,TxHash\(SHA-256:[0-9a-f]+|Failed[A-Za-z]*|Attempting to push [0-9]+|Attempting to build, sign' | paste -sd' ' | sed 's/"@timestamp":/\n/g' | grep -a 'participant=sv' | sed -E 's/"//g' | cut -c1-140; }
p 11386/logs-simtime-5 2026-11-12T12:09:48 2026-11-12T12:13:38; p 11388/logs-simtime-0 2026-11-12T12:14:56 2026-11-12T12:20:30; p 11389/logs-simtime-2 2026-11-12T12:04:51 2026-11-12T12:10:20; p 11391/logs-simtime-4 2026-11-12T14:09:17 2026-11-12T14:12:38
```
```
=== 11386/logs-simtime-5
...
2026-11-12T12:09:50.113Z Attempting to push 1 participant=sv1Participant 
2026-11-12T12:09:50.256Z Accepted,TxHash(SHA-256:faf98d045f6c participant=sv2Participant 
2026-11-12T12:09:50.441Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-12T12:09:50.460Z Attempting to push 1 participant=sv3Participant 
2026-11-12T12:09:50.531Z Attempting to build, sign serial Some(4) participant=sv2Participant 
2026-11-12T12:09:50.549Z Attempting to push 1 participant=sv2Participant 
2026-11-12T12:09:50.645Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-12T12:09:50.742Z Attempting to build, sign serial Some(4) participant=sv4Participant 
2026-11-12T12:09:50.759Z Attempting to push 1 participant=sv4Participant 
2026-11-12T12:11:39.736Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-12T12:11:41.311Z Attempting to build, sign serial Some(4) participant=sv2Participant 
2026-11-12T12:11:45.042Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-12T12:11:53.819Z Attempting to build, sign serial Some(4) participant=sv4Participant 
2026-11-12T12:13:29.329Z Attempting to build, sign serial Some(4) participant=sv3Participant
=== 11388/logs-simtime-0
...
2026-11-12T12:14:58.497Z Attempting to push 1 participant=sv2Participant 
2026-11-12T12:14:58.720Z Accepted,TxHash(SHA-256:0e1c10103b43 participant=sv4Participant 
2026-11-12T12:14:58.745Z Accepted,TxHash(SHA-256:0e1c10103b43 participant=sv3Participant 
2026-11-12T12:14:58.817Z Attempting to build, sign serial Some(4) participant=sv2Participant 
2026-11-12T12:14:59.036Z Attempting to build, sign serial Some(4) participant=sv4Participant 
2026-11-12T12:14:59.037Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-12T12:14:59.055Z Attempting to push 1 participant=sv4Participant 
2026-11-12T12:14:59.056Z Attempting to push 1 participant=sv3Participant 
2026-11-12T12:14:59.821Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-12T12:14:59.839Z Attempting to push 1 participant=sv1Participant 
2026-11-12T12:16:56.067Z Attempting to build, sign serial Some(4) participant=sv3Participant 
...
=== 11389/logs-simtime-2
...
2026-11-12T12:04:52.434Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-12T12:04:52.452Z Attempting to push 1 participant=sv3Participant 
2026-11-12T12:04:52.579Z Attempting to build, sign serial Some(4) participant=sv4Participant 
2026-11-12T12:04:52.596Z Attempting to push 1 participant=sv4Participant 
2026-11-12T12:04:52.733Z Accepted,TxHash(SHA-256:a5dd599c1ff3 participant=sv3Participant 
2026-11-12T12:04:52.740Z Attempting to build, sign serial Some(4) participant=sv2Participant 
2026-11-12T12:04:52.767Z Accepted,TxHash(SHA-256:a5dd599c1ff3 participant=sv4Participant 
2026-11-12T12:04:52.768Z Attempting to push 1 participant=sv2Participant 
2026-11-12T12:04:53.483Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-12T12:04:53.501Z Attempting to push 1 participant=sv1Participant 
2026-11-12T12:06:55.482Z Attempting to build, sign serial Some(4) participant=sv2Participant 
2026-11-12T12:06:58.385Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-12T12:08:58.037Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-12T12:09:00.100Z Attempting to build, sign serial Some(4) participant=sv2Participant
=== 11391/logs-simtime-4
...
2026-11-12T14:09:19.493Z Attempting to build, sign serial Some(4) participant=sv2Participant 
2026-11-12T14:09:19.515Z Attempting to push 1 participant=sv2Participant 
2026-11-12T14:09:19.518Z Attempting to build, sign serial Some(4) participant=sv4Participant 
2026-11-12T14:09:19.584Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-12T14:09:19.602Z Accepted,TxHash(SHA-256:e44492ee6766 participant=sv4Participant 
2026-11-12T14:09:19.609Z Attempting to push 1 participant=sv4Participant 
2026-11-12T14:09:19.619Z Attempting to push 1 participant=sv3Participant 
2026-11-12T14:09:19.939Z Accepted,TxHash(SHA-256:268dcbd86326 participant=sv2Participant 
2026-11-12T14:09:20.152Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-12T14:09:20.176Z Attempting to push 1 participant=sv1Participant 
2026-11-12T14:11:15.320Z Attempting to build, sign serial Some(4) participant=sv4Participant 
2026-11-12T14:11:22.385Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-12T14:11:23.527Z Attempting to build, sign serial Some(4) participant=sv1Participant
```

(`...` = earlier lines of the window, serial 2/3 pushes, and in 11388 the later re-proposals; full output from the
command. Switch times from section 4: 11386 12:09:50.296, 11388 12:14:58.803, 11389 12:04:52.814, 11391
14:09:19.993.) Reading per run, serial 4 signatures that were answered before the timeout:
- 11386: none. sv2, sv3, sv4 rejected (section 4); sv1 did not push (its push of 12:09:50.113, just before the
  switch, was never answered, so its flush cycle was still open, see below).
- 11388: none. sv1, sv3, sv4 rejected; sv2 did not push (its push of 12:14:58.497 was never answered).
- 11389: sv3 and sv4 (a5dd599c1ff3, 12:04:52.733/.767, before the switch at .814); sv2's push of 12:04:52.768 never
  answered; sv1 rejected.
- 11391: sv2 only (268dcbd86326, 14:09:19.939, before the switch at .993); sv3 and sv4 pushes never answered; sv1
  rejected.

The re-proposals find the old flush cycle still open (first four after each switch):

```
cd log; h(){ d=$1 from=$2; echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a 'Another flush cycle is currently ongoing' | awk -F'"@timestamp":"' -v f=$from '{split($2,a,"\""); if (a[1] > f) print a[1], $0}' | grep -a -oE '^[^ ]+|participant=sv[1-4]Participant' | paste -d' ' - - | head -4; }
h 11386/logs-simtime-5 2026-11-12T12:09:50.296; h 11388/logs-simtime-0 2026-11-12T12:14:58.803; h 11389/logs-simtime-2 2026-11-12T12:04:52.814; h 11391/logs-simtime-4 2026-11-12T14:09:19.993
```
```
=== 11386/logs-simtime-5
2026-11-12T12:09:50.662Z participant=sv1Participant
2026-11-12T12:11:39.760Z participant=sv3Participant
2026-11-12T12:11:41.331Z participant=sv2Participant
2026-11-12T12:11:45.063Z participant=sv1Participant
=== 11388/logs-simtime-0
2026-11-12T12:14:58.835Z participant=sv2Participant
2026-11-12T12:16:56.099Z participant=sv3Participant
2026-11-12T12:16:57.829Z participant=sv2Participant
2026-11-12T12:17:00.113Z participant=sv1Participant
=== 11389/logs-simtime-2
2026-11-12T12:06:55.509Z participant=sv2Participant
2026-11-12T12:06:58.414Z participant=sv1Participant
2026-11-12T12:08:58.060Z participant=sv1Participant
2026-11-12T12:09:00.119Z participant=sv2Participant
=== 11391/logs-simtime-4
2026-11-12T14:11:15.351Z participant=sv4Participant
2026-11-12T14:11:22.411Z participant=sv3Participant
2026-11-12T14:11:23.551Z participant=sv1Participant
```

## 6. Blast radius: setup failure skips the resets, later suites fail sv1's DSO party allocation

```
cd log; for d in 11386/logs-simtime-5 11388/logs-simtime-0 11389/logs-simtime-2 11391/logs-simtime-4; do echo "=== $d"; zcat $d/canton_network_test.clog.gz | awk '/decentralized namespace has been reset/{r++; last=substr($0,15,24)} /Address already in use/{a++} END{print "resets",r+0,"last_reset",last,"address_in_use",a+0}'; done
for d in 11386/logs-simtime-5 11388/logs-simtime-0 11389/logs-simtime-2 11391/logs-simtime-4; do echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a 'TOPOLOGY_UNAUTHORIZED_TRANSACTION' | grep -a 'participant=sv1Participant' | awk -F'"@timestamp":"' '{split($2,a,"\""); n++; if(!f) f=a[1]; l=a[1]} END{print "sv1 unauthorized:",n+0,"first",f,"last",l}'; done
```
```
=== 11386/logs-simtime-5
resets 2 last_reset "2026-11-12T12:08:00.393 address_in_use 2
=== 11388/logs-simtime-0
resets 13 last_reset "2026-11-12T12:12:59.003 address_in_use 0
=== 11389/logs-simtime-2
resets 5 last_reset "2026-11-12T12:03:00.197 address_in_use 2
=== 11391/logs-simtime-4
resets 7 last_reset "2026-11-12T14:07:03.288 address_in_use 0
=== 11386/logs-simtime-5
sv1 unauthorized: 82 first 2026-11-12T12:13:39.973Z last 2026-11-12T12:18:39.093Z
=== 11388/logs-simtime-0
sv1 unauthorized: 0 first  last 
=== 11389/logs-simtime-2
sv1 unauthorized: 249 first 2026-11-12T12:10:59.914Z last 2026-11-12T12:26:14.285Z
=== 11391/logs-simtime-4
sv1 unauthorized: 87 first 2026-11-12T14:12:54.675Z last 2026-11-12T14:17:53.803Z
```

The last reset is always before the first failing suite (section 2). The later suites of 11389 wait on sv1's DSO
party allocation until their init timeout:

```
cd log; zcat 11389/logs-simtime-2/canton_network_test.clog.gz | grep -a -E 'WalletRewardsTimeBasedIntegrationTest/' | grep -a 'Gave up ensuring Party DSO' | sed -E 's/"logger_name".*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-12T12:21:13.606Z Gave up ensuring Party DSO-61cae5d7-61cae5d7::1220b6d8932a... is allocated on PAR::sv1::1220c5643c46..., as we are shutting down",
```

The setup-failure path of the vendored test framework is unchanged since the 11358 packet (section 6 there):

```
F=canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala
git diff --quiet 915616a77d 4db185089a -- $F && echo "4db1850 same as 915616a"; git diff --quiet 915616a77d e214318836 -- $F && echo "e214318 same as 915616a"
```
```
4db1850 same as 915616a
e214318 same as 915616a
```

## 7. Frequency since the last count (11374, 2026-11-11 18:46)

Post-merge runs (workflow 163795441, main and release lines) created since 2026-11-11 18:00 and their simtime jobs (all
attempts):

```
gh api "repos/canton-network/splice/actions/workflows/163795441/runs?created=>=2026-11-11T18:00:00Z&per_page=100" --jq '.workflow_runs[] | "\(.id) \(.head_branch) \(.head_sha[0:10]) \(.created_at) \(.status) \(.conclusion)"' > log/11386/runs.txt
awk '$5=="completed"{print $1}' log/11386/runs.txt | while read r; do gh api --paginate "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=100" --jq '.jobs[] | select(.name|test("(^| / )simtime \\([0-9]+\\)$")) | "\(.run_id) \(.id) a=\(.run_attempt) \(.conclusion) \(.name|sub(".* / ";""))"' > log/11386/jobs-$r.txt; echo "$r $(grep -c . log/11386/jobs-$r.txt) simtime jobs: $(awk '{print $4}' log/11386/jobs-$r.txt | sort | uniq -c | tr '\n' ' ') failed: $(awk '$4=="failure"{printf "%s %s; ", $5, $6}' log/11386/jobs-$r.txt)"; done
```
```
43228778010 6 simtime jobs:       1 failure       5 success  failed: simtime (4); 
43211423532 6 simtime jobs:       1 failure       5 success  failed: simtime (5); 
43211354109 6 simtime jobs:       2 failure       4 success  failed: simtime (0); simtime (2); 
43207139518 6 simtime jobs:       6 success  failed: 
43187848056 6 simtime jobs:       1 failure       5 success  failed: simtime (4); 
43176206980 6 simtime jobs:       6 success  failed: 
43078335366 6 simtime jobs:       6 success  failed: 
43073759004 6 simtime jobs:       1 failure       5 success  failed: simtime (0); 
43073677994 6 simtime jobs:       6 success  failed: 
```

The other failing simtime jobs in that list have issues already closed as duplicates by the parent's assignee
(43073759004 is 11374):

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "simtime" created:>=2026-11-11T18:00:00Z' --jq '.items[] | "\(.number) \(.state) \(.created_at) \(.title)"'
for r in 11380 11381; do gh api repos/DACH-NY/cn-test-failures/issues/$r --jq '"#\(.number) \(.state) \(.state_reason) closed_by=\(.closed_by.login)"'; gh api repos/DACH-NY/cn-test-failures/issues/$r/comments --jq '.[] | "-- \(.user.login) \(.created_at): \(.body[0:300])"'; done
```
```
11392 open 2026-11-12T14:58:32Z GHA Run 43236500158 : job simtime (0) in scala_test Failed :fire:
11391 open 2026-11-12T14:18:35Z GHA Run 43228778010 : job simtime (4) in scala_test Failed :fire:
11389 open 2026-11-12T12:26:51Z GHA Run 43211354109 : job simtime (2) in scala_test Failed :fire:
11388 open 2026-11-12T12:21:05Z GHA Run 43211354109 : job simtime (0) in scala_test Failed :fire:
11386 open 2026-11-12T12:19:16Z GHA Run 43211423532 : job simtime (5) in scala_test Failed :fire:
11381 closed 2026-11-12T09:43:29Z GHA Run 43187848056 : job simtime (4) in scala_test Failed :fire:
11380 closed 2026-11-12T05:29:29Z GHA Run 43158880011 : job simtime (3) in scala_test Failed :fire:
11374 closed 2026-11-11T18:46:29Z GHA Run 43073759004 : job simtime (0) in scala_test Failed :fire:
#11380 closed duplicate closed_by=rautenrieth-da
-- rautenrieth-da 2026-11-12T12:52:15Z: Same 1 to 4 ordering topology step in simtime setup: the splitwell participant confirmation response for its wallet install was rejected with P2P connectivity is not ready 2 ms after the switch, the request was never finalized on the frozen sim clock, and splitwellValidator init timed out.
#11381 closed duplicate closed_by=rautenrieth-da
-- rautenrieth-da 2026-11-12T12:51:46Z: Same 1 to 4 ordering topology step in simtime setup: all four SV signatures on the proposal adding the sv3 mediator were rejected with P2P connectivity is not ready, and sv3 waited for mediator traffic until init timed out.
```

11380's run is the scheduled mainnet-Daml workflow, not the post-merge one:

```
gh api repos/canton-network/splice/actions/runs/43158880011 --jq '"\(.name) \(.event) \(.head_branch) \(.head_sha[0:10]) \(.created_at) workflow_id=\(.workflow_id)"'
```
```
Integration tests against mainnet Daml version schedule main d356bcf573 2026-11-12T05:04:14Z workflow_id=167847128
```

Hit rate, completed post-merge runs since 2026-11-11 18:00: 5 of 9 runs with at least one failed simtime shard
(6 shards), all of this shape where checked (11374, 11381 by its closing comment, these four). 11380 (scheduled
mainnet-Daml run 43158880011) is the same step with a splitwell confirmation response, per its closing comment.
11392 (main 0911e29902, opened 14:58, after this brief) is not triaged here.

Parent state:

```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"#\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) \(.title)"'
```
```
#11255 open assignees=rautenrieth-da Timeouts due to changing bft ordering topology
```

The assignee posted a root-cause summary on 11255 at 2026-11-12T14:50:42Z (fix options in the triage branch under
`fixes/11255/`).

## Verdict

Duplicate of 11255 (family B, rejected-send / batch-never-ordered shape at the 1 -> 4 ordering step under sim time),
all four. Flake in Canton BFT ordering plus the test-side amplification (the joining SV setup cannot recover on a
stopped sim clock; the failed setup skips the namespace reset and poisons the rest of the shard). Fix location as on
11255 (owner rautenrieth-da): onboard the SV sequencers one at a time, or keep the sim clock moving during setup, and
run the reset plugins on the `Running setup` failure path. Canton 3.7.3 final is the same as the 20261111 snapshot, so
the bump neither caused nor fixed it; release-line-0.10.2 carries the same code and will hit it at the same rate.
Not verified: whether the unanswered pushes (11389 sv2, 11391 sv3/sv4, 11386 sv1, 11388 sv2) sat in a batch that was
never ordered (the 11358 variant); it does not change the verdict.
