# 11358, 11362, 11368, 11374 - simtime on main 2026-11-11: mediator-add topology change lost at the 1 -> 4 ordering step, joining SV never initializes, setup failure poisons the rest of the shard (runs 43012738610, 43030349815, 43055359204, 43073759004)

DUPLICATE of 11255 (family B, rejected-send / batch-never-ordered shape), all four. Post-merge main runs on
2026-11-11, all after splice#8356 (BFT blacklisting off in simtime tests). In each shard one suite's 4-SV environment
setup (SvExpiredRewardsCollectionTimeBased, NonZeroRoundBootstrapBftTimeBased, TimeBasedTestNetPreview,
TrafficBasedRewardsSvAppTimeBased) grows the global ordering topology from 1 to 4 sequencers in one step. Within
0.07 to 1.2 s after the switch the SV participants' signatures on the MediatorSynchronizerState proposal that adds the
joining SV's mediator are rejected by globalSequencerSv1 with `P2P connectivity is not ready (authenticated = 1 <
dissemination quorum = 2)` (11358: two signatures sequenced before the switch, sv3's sat in a batch that was never
ordered, sv4's was rejected). The participants treat the rejected sends as possibly sequenced until their max
sequencing time on a sim clock that does not advance during setup, so their topology outboxes stay blocked
(`Another flush cycle is currently ongoing` on re-proposal) and the mediator is never added. The joining SV app waits
on `Mediator has been granted unlimited traffic` until `waitForInitialization` gives up after 5 min. No blacklisting
(`blacklisted nodes = List()` on every epoch, zero `currently blacklisted`), no lost verdicts (zero `Failed to send
result`). Because the failure is in the `Running setup` step, the topology reset plugins never run (zero `has been
reset` after the failure) and every later suite fails sv1's DSO party allocation with
`TOPOLOGY_UNAUTHORIZED_TRANSACTION` (the 11324/11332/11341 blast radius), then family H `Address already in use` in
11368. Not family AM: the stuck SV's time went into waiting for the lost mediator add, not DAR uploads. No common
2026-11-11 commit: the four shas change unrelated test code, two Canton snapshots are involved, and this shape has
existed since the first run after splice#8234 (2026-11-06).

- Runs (all post-merge main, attempt 1):
  - 11358: https://github.com/canton-network/splice/actions/runs/43012738610, main 2d54198a47 ("SvFrontendIntegrationTest: tag the Featured App Right test (#8427)"), job 130496550275 `simtime (3)`; attempt 2 (job 130710911028) passed.
  - 11362: https://github.com/canton-network/splice/actions/runs/43030349815, main 2fc780a883 ("Pause CloseVoteRequestTrigger on all SVs in the SvFrontendIntegrationTest expiring proposal step (#8417)"), job 130555862019 `simtime (2)`.
  - 11368: https://github.com/canton-network/splice/actions/runs/43055359204, main 1ee8c88066 ("threshold fixes for new perf tests (#8434)"), job 130644484709 `simtime (5)`.
  - 11374: https://github.com/canton-network/splice/actions/runs/43073759004, main 915616a77d ("Allow 80 s for the claimed SV reward coupons check (#8413)"), job 130709771107 `simtime (0)`.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (11358, 11362, 11368); 3.7.3-snapshot.20261111.20904.0.vbe797d9d (11374).
- Component: Canton BFT ordering at the 1 -> 4 ordering-topology step under sim time (11255); test framework (setup-failure
  path skips the topology reset plugins).

## 0. Issues (verbatim) and job ids

```
for n in 11358 11362 11368 11374; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"#\(.number) [\(.state)] \(.title)\n\(.body)\ncomments=\(.comments)"'; done
```
```
#11358 [open] GHA Run 43012738610 : job simtime (3) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43012738610).
Job: simtime (3) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [2d54198](https://github.com/canton-network/splice/commit/2d54198a475f5ad3f818a9fcda403422fdea4fdc) SvFrontendIntegrationTest: tag the Featured App Right test (#8427)
Actor: rautenrieth-da

comments=0
#11362 [open] GHA Run 43030349815 : job simtime (2) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43030349815).
Job: simtime (2) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [2fc780a](https://github.com/canton-network/splice/commit/2fc780a883cbad8279b34400ee5999a2d1b681ff) Pause CloseVoteRequestTrigger on all SVs in the SvFrontendIntegrationTest expiring proposal step (#8417)
Actor: rautenrieth-da

comments=0
#11368 [open] GHA Run 43055359204 : job simtime (5) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43055359204).
Job: simtime (5) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [1ee8c88](https://github.com/canton-network/splice/commit/1ee8c880660fcaf6cfd458700c3dcda6085a204b) threshold fixes for new perf tests (#8434)
Actor: jagathweerasinghe-da

comments=0
#11374 [open] GHA Run 43073759004 : job simtime (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43073759004).
Job: simtime (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [915616a](https://github.com/canton-network/splice/commit/915616a77d56c0f94fcf7ab75f58e810065cdf83) Allow 80 s for the claimed SV reward coupons check (#8413)
Actor: rautenrieth-da

comments=0
```

Failed simtime jobs per run (recipe 1, exact name match on `(^| / )simtime \([0-9]+\)$`, all attempts; listing script in
section 7), and the re-run of 43012738610:

```
for r in 43012738610 43030349815 43055359204 43073759004; do awk '$4=="failure"' log/g1-simtime/jobs-$r.txt; done
grep -h 'a=2' log/g1-simtime/jobs-43012738610.txt | grep 'simtime (3)'
```
```
43012738610 130496550275 a=1 failure 2026-11-11T11:33:21Z 2026-11-11T12:20:46Z simtime (3)
43030349815 130555862019 a=1 failure 2026-11-11T13:36:47Z 2026-11-11T14:22:30Z simtime (2)
43055359204 130644484709 a=1 failure 2026-11-11T16:11:20Z 2026-11-11T16:42:40Z simtime (5)
43073759004 130709771107 a=1 failure 2026-11-11T18:12:23Z 2026-11-11T18:46:39Z simtime (0)
43012738610 130710911028 a=2 success 2026-11-11T18:18:11Z 2026-11-11T18:58:59Z simtime (3)
```

Artifacts (`gh run download`, recipe 3) to `log/<ref>/logs-simtime-<n>/`; the 43012738610 artifact was created at
12:20:24Z, 38 s after attempt 1 failed (attempt 2 ended 18:58:59Z):

```
gh api "repos/canton-network/splice/actions/runs/<run>/artifacts?per_page=100" --jq '.artifacts[] | select(.name|test("simtime")) | "\(.id) \(.size_in_bytes) \(.name) \(.created_at) expired=\(.expired)"'
```
```
== 43012738610
13855030120 229425550 logs-simtime-3 2026-11-11T12:20:24Z expired=false
== 43030349815
13863686617 231573938 logs-simtime-2 2026-11-11T14:22:09Z expired=false
== 43055359204
13872663058 133564920 logs-simtime-5 2026-11-11T16:42:17Z expired=false
== 43073759004
13884741354 100648504 logs-simtime-0 2026-11-11T18:46:16Z expired=false
```

## 1. Failing tests: every failure is an environment init timeout or its cascade

Job logs (ANSI stripped) at `log/inbox-1111b/<job id>.log`.

```
cd log/inbox-1111b; for j in 130496550275 130555862019 130644484709 130709771107; do echo "=== $j"; grep -aE '\*\*\* FAILED|ABORTED|Tests: succeeded|contains problems|Run completed' $j.log | cut -c1-220; done
```
```
=== 130496550275
2026-11-11T12:04:17.7379884Z [info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
2026-11-11T12:09:31.1967425Z [info] org.lfdecentralizedtrust.splice.integration.tests.WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest *** ABORTED ***
2026-11-11T12:14:44.6880807Z [info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
2026-11-11T12:19:59.1376012Z [info] org.lfdecentralizedtrust.splice.integration.tests.FeaturedAppRightSwitchOverTimeBasedIntegrationTest *** ABORTED ***
2026-11-11T12:19:59.1447237Z [info] Run completed in 35 minutes, 39 seconds.
2026-11-11T12:19:59.1448898Z [info] Tests: succeeded 6, failed 1, canceled 0, ignored 0, pending 0
...
=== 130555862019
2026-11-11T14:10:55.0415448Z [info] - SV triggers complete the reward pipeline for the initial round *** FAILED ***
2026-11-11T14:16:08.4322720Z [info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
2026-11-11T14:21:42.8430345Z [info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRewardCouponIntegrationTest *** ABORTED ***
2026-11-11T14:21:42.8492481Z [info] Run completed in 33 minutes, 17 seconds.
2026-11-11T14:21:42.8493798Z [info] Tests: succeeded 10, failed 2, canceled 0, ignored 0, pending 0
...
=== 130644484709
2026-11-11T16:36:52.6769446Z [info] - TestNet initializes correctly *** FAILED ***
2026-11-11T16:41:56.2427138Z [info] - initialize a scan app that joins late *** FAILED ***
2026-11-11T16:41:56.2503084Z [info] org.lfdecentralizedtrust.splice.integration.tests.TokenStandardMetadataTimeBasedIntegrationTest *** ABORTED ***
2026-11-11T16:41:57.2524767Z [info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRewardCouponIntegrationTest *** ABORTED ***
2026-11-11T16:41:57.2641094Z [info] Run completed in 18 minutes, 15 seconds.
2026-11-11T16:41:57.2642440Z [info] Tests: succeeded 1, failed 2, canceled 0, ignored 1, pending 0
...
=== 130709771107
2026-11-11T18:30:22.4646558Z [info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
2026-11-11T18:35:39.0853803Z [info] org.lfdecentralizedtrust.splice.integration.tests.WalletMintingDelegationTimeBasedIntegrationTest *** ABORTED ***
2026-11-11T18:40:52.8824701Z [info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
2026-11-11T18:45:57.8524584Z [info] - expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts *** FAILED ***
2026-11-11T18:45:57.8619807Z [info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedRewardCouponIntegrationTest *** ABORTED ***
2026-11-11T18:45:57.8752337Z [info] org.lfdecentralizedtrust.splice.integration.tests.FeaturedAppRightSwitchOverTimeBasedIntegrationTest *** ABORTED ***
2026-11-11T18:45:58.8625639Z [info] Run completed in 21 minutes, 25 seconds.
2026-11-11T18:45:58.8627799Z [info] Tests: succeeded 0, failed 3, canceled 0, ignored 0, pending 0
...
```

Stack of the first failure (same in all four; the later ones are the same or the cascade below):

```
grep -anE '\*\*\* FAILED|ABORTED \*\*\*' log/inbox-1111b/130496550275.log | head -1   # then sed -n "<n>,<n+6>p" | cut -c29-230
```
```
 [info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
 [info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
 [info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
 [info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
 [info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
 [info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
```

11368 later failures: `initialize a scan app that joins late` with `The app state of sv1Scan is currently not
accessible. Node doesn't have any app state.`, then TokenStandardMetadataTimeBased and SvTimeBasedRewardCoupon abort
at `Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)` / `Could not create Prometheus
HTTP server` (family H leak cascade, 10176/11347).

## 2. Suite timelines: the first failing suite is a joining SV's init in setup; no reset after it

```
cd log/<ref>/logs-simtime-<n>; zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Timeout while waiting for initialization|has been reset" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
=== 11358 (from the last passing suite)
2026-11-11T11:58:42.711Z decentralized namespace has been reset",
2026-11-11T11:58:42.724Z sequencer domain state threshold has been reset",
2026-11-11T11:58:42.765Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...",
2026-11-11T12:04:16.533Z Timeout while waiting for initialization of sv3",
2026-11-11T12:04:16.781Z Starting test suite 'WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest'...",
2026-11-11T12:09:30.591Z Timeout while waiting for initialization of sv1",
2026-11-11T12:09:30.647Z Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...",
2026-11-11T12:14:44.563Z Timeout while waiting for initialization of sv1",
2026-11-11T12:14:44.608Z Test failed: 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance',
2026-11-11T12:14:44.618Z Starting test suite 'FeaturedAppRightSwitchOverTimeBasedIntegrationTest'...",
2026-11-11T12:19:58.504Z Timeout while waiting for initialization of sv1",
=== 11362 (from the last passing suite)
2026-11-11T14:03:34.808Z decentralized namespace has been reset",
2026-11-11T14:03:34.820Z sequencer domain state threshold has been reset",
2026-11-11T14:03:34.865Z Starting test suite 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest'...",
2026-11-11T14:10:54.093Z Timeout while waiting for initialization of sv2",
2026-11-11T14:10:54.296Z Test failed: 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest/SV triggers complete the reward pipeline for the initial round', message: Creating
2026-11-11T14:10:54.313Z Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...",
2026-11-11T14:16:08.222Z Timeout while waiting for initialization of sv1",
2026-11-11T14:16:08.265Z Test failed: 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than l
2026-11-11T14:16:08.277Z Starting test suite 'SvTimeBasedRewardCouponIntegrationTest'...",
2026-11-11T14:21:41.811Z Timeout while waiting for initialization of sv3",
=== 11368 (whole shard)
2026-11-11T16:23:42.039Z Starting test suite 'TrafficBasedRewardsTimeBasedIntegrationTest'...",
2026-11-11T16:31:13.177Z decentralized namespace has been reset",
2026-11-11T16:31:13.199Z sequencer domain state threshold has been reset",
2026-11-11T16:31:13.294Z Test succeeded: 'TrafficBasedRewardsTimeBasedIntegrationTest/CIP-104 reward accounting pipeline works'",
2026-11-11T16:31:13.344Z Starting test suite 'WalletAppRewardsTimeBasedIntegrationTest'...",
2026-11-11T16:31:13.358Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
2026-11-11T16:36:50.133Z Timeout while waiting for initialization of sv3",
2026-11-11T16:36:50.389Z Test failed: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly', message: Creating environment failed at step Running setup (
2026-11-11T16:36:50.407Z Starting test suite 'ScanWithGradualStartsTimeBasedIntegrationTest'...",
2026-11-11T16:41:55.783Z Timeout while waiting for initialization of sv1Scan",
2026-11-11T16:41:55.786Z Test failed: 'ScanWithGradualStartsTimeBasedIntegrationTest/initialize a scan app that joins late', message: The app state of sv1Scan is currentl
2026-11-11T16:41:55.796Z Starting test suite 'TokenStandardMetadataTimeBasedIntegrationTest'...",
2026-11-11T16:41:56.089Z Starting test suite 'SvTimeBasedRewardCouponIntegrationTest'...",
=== 11374 (whole shard)
2026-11-11T18:24:33.819Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
2026-11-11T18:30:21.339Z Timeout while waiting for initialization of sv3",
2026-11-11T18:30:21.708Z Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', mes
2026-11-11T18:30:21.850Z Starting test suite 'WalletMintingDelegationTimeBasedIntegrationTest'...",
2026-11-11T18:35:38.791Z Timeout while waiting for initialization of sv1",
2026-11-11T18:35:38.855Z Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...",
2026-11-11T18:40:52.787Z Timeout while waiting for initialization of sv1",
2026-11-11T18:40:52.829Z Test failed: 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than l
2026-11-11T18:40:53.339Z Starting test suite 'SvTimeBasedOnboardingIntegrationTest'...",
2026-11-11T18:45:56.925Z Timeout while waiting for initialization of sv1Scan",
2026-11-11T18:45:56.943Z Test failed: 'SvTimeBasedOnboardingIntegrationTest/expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteReq
2026-11-11T18:45:56.951Z Starting test suite 'SvTimeBasedRewardCouponIntegrationTest'...",
2026-11-11T18:45:57.568Z Starting test suite 'FeaturedAppRightSwitchOverTimeBasedIntegrationTest'...",
```

So ExternallySignedTxsTimeBased and SvTimeBasedRewardCoupon fail in several shards only because they run after the
poisoned setup; they are victims, not causes.

## 3. What the joining SV waited on: the mediator add, not DAR uploads

11358 sv3 SvApp (INFO and above, first occurrence of each message, from `Initialize node` to the timeout):

```
cd log/11358/logs-simtime-3; zcat canton_network_test.clog.gz | grep -a 'SvExpiredRewardsCollectionTimeBasedIntegrationTest' | grep -a 'SV=sv3"' | grep -a -E '"level":"(INFO|WARN|ERROR)"' | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] > "2026-11-11T11:59:17" && a[1] < "2026-11-11T12:04:17") print}' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/SvExpiredRewardsCollectionTimeBasedIntegrationTest\/config=48acc508\///' | cut -c1-260 | awk '{k=substr($0,26,70)} !seen[k]++' | tail -60
```
```
...
2026-11-11T12:00:45.768Z Planning to add mediators MediatorToOnboard(synchronizerId = global-domain::1220f306b718..., mediatorId = MED::sv3::1220e6db46fa..., sequencerId = SEQ::sv3::1220336bafbd...) to match DsoRulesWithSvNodeStates(\n  domain = global-domain:
2026-11-11T12:00:46.972Z Ensuring that Add mediator MED::sv3::1220e6db46fa...",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-11T12:00:46.985Z No proposal found for Add mediator MED::sv3::1220e6db46fa..., submitting new one.",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-11T12:00:47.011Z Submitted proposal MediatorSynchronizerState(\n  synchronizerId = global-domain::1220f306b718...,\n  group = 0,\n  threshold = 2,\n  active = Seq(MED::sv1::122088c8a637..., MED::sv2::1220f3e794a2..., MED::sv4::1220d13df112..., MED::sv3
2026-11-11T12:00:47.629Z Onboarded sequencer SEQ::sv3::1220336bafbd...",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
2026-11-11T12:00:47.836Z Success: Add sequencer SEQ::sv3::1220336bafbd...",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-11T12:00:51.887Z Onboarding mediator",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
2026-11-11T12:00:51.888Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
2026-11-11T12:00:51.892Z The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable error (full stack trace omitted): NOT_FOUND: No traffic state found for member MED::sv3::1220e6db46fa...\nstatusCode=NOT_FOUND",[o.l.s.s.
2026-11-11T12:02:38.696Z The operation 'Add mediator MED::sv3::1220e6db46fa...' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: MediatorSynchronizerState(\n  synchronizerId = global-domain
2026-11-11T12:04:16.701Z Stopping sv3 node",[o.l.s.s.SvApp:SV=sv3] INFO
2026-11-11T12:04:16.702Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[o.l.s.s.LocalSynchronizerNode:SV=sv3] INFO
2026-11-11T12:04:16.703Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed",[o.l.s.s.SvApp:SV=sv3] INFO
2026-11-11T12:04:16.703Z Gave up ensuring Add mediator MED::sv3::1220e6db46fa...",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
...
```

The same wait in all four (stuck SV per run):

```
cd log; f(){ d=$1 suite=$2 sv=$3 from=$4 to=$5; echo "=== $d $suite $sv"; zcat $d/canton_network_test.clog.gz | grep -a "$suite/" | grep -a "SV=$sv\"" | grep -a -E 'Waiting until|Gave up|app initialization: .* (started|failed)$|app initialization: [^"]*(started|failed)"' | awk -F'"@timestamp":"' -v f=$from -v t=$to '{split($2,a,"\""); if (a[1] > f && a[1] < t) print}' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | tail -8; }
f 11362/logs-simtime-2 NonZeroRoundBootstrapBftTimeBasedIntegrationTest sv2 2026-11-11T14:03 2026-11-11T14:11
f 11368/logs-simtime-5 TimeBasedTestNetPreviewIntegrationTest sv3 2026-11-11T16:31 2026-11-11T16:37
f 11374/logs-simtime-0 TrafficBasedRewardsSvAppTimeBasedIntegrationTest sv3 2026-11-11T18:24 2026-11-11T18:31
f 11358/logs-simtime-3 SvExpiredRewardsCollectionTimeBasedIntegrationTest sv3 2026-11-11T11:58 2026-11-11T12:05
```
```
=== 11362/logs-simtime-2 NonZeroRoundBootstrapBftTimeBasedIntegrationTest sv2
2026-11-11T14:05:29.581Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::12208ef0b6d1..., mediatorId = MED::sv2::122090bd88fc..., sequencer
2026-11-11T14:05:33.942Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-11T14:10:54.229Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-11T14:10:54.230Z sv2 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
2026-11-11T14:10:54.230Z Gave up ensuring Add mediator MED::sv2::122090bd88fc...", INFO
...
=== 11368/logs-simtime-5 TimeBasedTestNetPreviewIntegrationTest sv3
2026-11-11T16:33:16.167Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::12208da24dcf..., mediatorId = MED::sv3::122006b9c0b1..., sequencer
2026-11-11T16:33:20.884Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-11T16:36:50.325Z Gave up ensuring Add mediator MED::sv3::122006b9c0b1...", INFO
2026-11-11T16:36:50.324Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-11T16:36:50.325Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
...
=== 11374/logs-simtime-0 TrafficBasedRewardsSvAppTimeBasedIntegrationTest sv3
2026-11-11T18:28:40.059Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::12203e22f795..., mediatorId = MED::sv3::12205ab84918..., sequencer
2026-11-11T18:28:45.898Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-11T18:30:21.562Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-11T18:30:21.562Z Gave up ensuring Add mediator MED::sv3::12205ab84918...", INFO
2026-11-11T18:30:21.563Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
...
=== 11358/logs-simtime-3 SvExpiredRewardsCollectionTimeBasedIntegrationTest sv3
2026-11-11T12:00:45.772Z Waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId = global-domain::1220f306b718..., mediatorId = MED::sv3::1220e6db46fa..., sequencer
2026-11-11T12:00:51.888Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-11T12:04:16.702Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
2026-11-11T12:04:16.703Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed", INFO
2026-11-11T12:04:16.703Z Gave up ensuring Add mediator MED::sv3::1220e6db46fa...", INFO
...
```

Time in that final wait: 11358 205 s, 11362 320 s, 11368 209 s, 11374 96 s. Family AM (repeated DAR uploads) is
not the cause: in 11374, where the wait was shortest because the shard started with this suite on fresh nodes, all
DAR uploads of the suite ended 18 s before the 1 -> 4 switch (18:28:41.7, section 4):

```
cd log/11374/logs-simtime-0; zcat canton_network_test.clog.gz | grep -a 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/' | grep -a 'Sending upload dar for' | awk -F'"@timestamp":"' '{split($2,a,"\""); n++; if(!f)f=a[1]; l=a[1]} END{print "dar upload sends",n,"first",f,"last",l}'
```
```
dar upload sends 586 first 2026-11-11T18:26:04.074Z last 2026-11-11T18:28:23.807Z
```

## 4. Canton: the 1 -> 4 step, no blacklisting, participant sends rejected

Counts per run (zero blacklist rejections and zero lost verdicts rule out the lost-verdict and breaker shapes;
`any_blacklist` matches the `blacklisted nodes = List()` part of every epoch line):

```
cd log; for d in 11358/logs-simtime-3 11362/logs-simtime-2 11368/logs-simtime-5 11374/logs-simtime-0; do echo "=== $d"; zcat $d/canton-simtime.clog.gz | awk '/currently blacklisted/{b++} /Failed to send result/{f++} /P2P connectivity is not ready/{p++} /blacklist/{bl++} END{print "currently_blacklisted",b+0,"failed_send_result",f+0,"p2p_not_ready",p+0,"any_blacklist",bl+0}'; done
```
```
=== 11358/logs-simtime-3
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 18 any_blacklist 29581
=== 11362/logs-simtime-2
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 84 any_blacklist 27373
=== 11368/logs-simtime-5
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 72 any_blacklist 14984
=== 11374/logs-simtime-0
currently_blacklisted 0 failed_send_result 0 p2p_not_ready 97 any_blacklist 18382
```

Ordering-topology size changes on the global synchronizer and the rejected submissions (one line per sequencer and
size change; splitwell synchronizer filtered out):

```
cd log; for d in 11362/logs-simtime-2 11368/logs-simtime-5 11374/logs-simtime-0; do echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a -E 'New epoch [0-9]+ has started|Rejecting submission request by|Failed to send result|currently blacklisted' | grep -a -v 'splitwellValidator' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n */ /g' | sed -E 's/(New epoch [0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\).*size = ([0-9]+),.*sequencer=([A-Za-z0-9]+).*/\1 blacklisted=[\3] size=\4 \5/' | cut -c1-230 | awk '{sz=""; if (match($0,/size=[0-9]+/)) sz=substr($0,RSTART,RLENGTH); if ($0 ~ /New epoch/) { if (sz!=last[$NF]) print; last[$NF]=sz } else print}' | head -40; done
```
```
=== 11362/logs-simtime-2
2026-11-11T13:49:24.608Z New epoch 0 blacklisted=[] size=1 globalSequencerSv1
2026-11-11T14:05:29.962Z New epoch 117 blacklisted=[] size=4 globalSequencerSv1
2026-11-11T14:05:30.149Z Rejecting submission request by Some(PAR::sv2::122098e4459d...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T14:05:30.360Z Rejecting submission request by Some(PAR::sv3::1220e334abf3...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T14:05:31.011Z Rejecting submission request by Some(PAR::sv1::122099edc2df...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T14:05:38.221Z New epoch 117 blacklisted=[] size=4 globalSequencerSv2
2026-11-11T14:05:39.150Z New epoch 117 blacklisted=[] size=4 globalSequencerSv3
2026-11-11T14:05:40.240Z New epoch 117 blacklisted=[] size=4 globalSequencerSv4
2026-11-11T14:05:45.796Z Rejecting submission request by Some(MED::sv4::12201a9f9ae5...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:2
=== 11368/logs-simtime-5
2026-11-11T16:24:44.886Z New epoch 0 blacklisted=[] size=1 globalSequencerSv1
2026-11-11T16:33:16.633Z New epoch 61 blacklisted=[] size=4 globalSequencerSv1
2026-11-11T16:33:16.700Z Rejecting submission request by Some(PAR::sv3::12204045dad9...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T16:33:16.868Z Rejecting submission request by Some(PAR::sv2::12201161ee81...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T16:33:16.884Z Rejecting submission request by Some(PAR::sv1::12201cb9be9c...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T16:33:17.162Z Rejecting submission request by Some(PAR::sv4::122025ab6bae...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T16:33:25.113Z New epoch 61 blacklisted=[] size=4 globalSequencerSv3
2026-11-11T16:33:25.908Z New epoch 61 blacklisted=[] size=4 globalSequencerSv4
2026-11-11T16:33:27.199Z New epoch 61 blacklisted=[] size=4 globalSequencerSv2
2026-11-11T16:33:27.343Z Rejecting submission request by Some(MED::sv4::12208a2c7296...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:c
=== 11374/logs-simtime-0
2026-11-11T18:26:00.677Z New epoch 0 blacklisted=[] size=1 globalSequencerSv1
2026-11-11T18:28:41.723Z New epoch 15 blacklisted=[] size=4 globalSequencerSv1
2026-11-11T18:28:42.026Z Rejecting submission request by Some(PAR::sv1::122086dac5d8...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T18:28:42.039Z Rejecting submission request by Some(PAR::sv4::1220a7f7893e...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T18:28:42.389Z Rejecting submission request by Some(PAR::sv3::1220295cb7bd...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T18:28:42.937Z Rejecting submission request by Some(PAR::sv2::12209a1a501f...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 
2026-11-11T18:28:50.193Z New epoch 15 blacklisted=[] size=4 globalSequencerSv3
2026-11-11T18:28:50.500Z New epoch 15 blacklisted=[] size=4 globalSequencerSv2
2026-11-11T18:28:50.922Z New epoch 15 blacklisted=[] size=4 globalSequencerSv4
2026-11-11T18:28:52.387Z Rejecting submission request by Some(MED::sv4::1220fc02c3d7...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:6
...
```

11358 (same grep without the size filter, window 11:59-12:04:20, trimmed to the global synchronizer):

```
2026-11-11T12:00:40.409Z New epoch 116 has started with leaders = List(SEQ::sv1::..f9eb)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T08:49:41.219001Z,\n  size = 1,\n  weakQuorum = 1,\n  strongQuorum = 1,\n  nodesTopologyInfo = \"SEQ::sv1::..f9e
2026-11-11T12:00:47.172Z New epoch 117 has started with leaders = List(SEQ::sv2::..aca8, SEQ::sv3::..2388, SEQ::sv4::..fadc, SEQ::sv1::..f9eb)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T08:49:41.239002Z,\n  size = 4,\n  weakQuorum = 2,\n  stro
2026-11-11T12:00:47.828Z Rejecting submission request by Some(PAR::sv4::122091ae5fcb...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-11T12:00:47.874Z Send [744b9db2-8f89-4e47-bcdd-3e8732ac354e] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-DefaultSequencer-0. Is the server running? Did you configure the server address as 0.0.0.0? Are you us
```

## 5. The rejected sends are the signatures on the proposal that adds the joining SV's mediator

Signing of MediatorSynchronizerState next to the participant rejections, per run:

```
cd log; g(){ d=$1 from=$2 to=$3; echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a -E 'Attempting to build, sign, and Replace MediatorSynchronizerState|Rejecting submission request by Some\(PAR' | awk -F'"@timestamp":"' -v f=$from -v t=$to '{split($2,a,"\""); if (a[1] > f && a[1] < t) print}' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n */ /g; s/active = Seq\(([^)]*)\).*with serial (Some\([0-9]+\)).*participant=([A-Za-z0-9]+).*/active=[\1] serial \2 \3/; s/with SequencerDeliverError.*/P2P not ready/; s/::1220[0-9a-f]+\.\.\.//g' | cut -c1-200; }
g 11358/logs-simtime-3 2026-11-11T12:00:46 2026-11-11T12:00:49; g 11362/logs-simtime-2 2026-11-11T14:05:28 2026-11-11T14:05:32; g 11368/logs-simtime-5 2026-11-11T16:33:15 2026-11-11T16:33:18; g 11374/logs-simtime-0 2026-11-11T18:28:40 2026-11-11T18:28:44
```
```
=== 11358/logs-simtime-3
2026-11-11T12:00:46.553Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv4, MED::sv3] ser
2026-11-11T12:00:46.558Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv4, MED::sv3] ser
2026-11-11T12:00:46.988Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv4, MED::sv3] ser
2026-11-11T12:00:47.788Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv2, MED::sv4, MED::sv3] ser
2026-11-11T12:00:47.828Z Rejecting submission request by Some(PAR::sv4) P2P not ready
=== 11362/logs-simtime-2
...
2026-11-11T14:05:30.109Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv3, MED::sv2] ser
2026-11-11T14:05:30.149Z Rejecting submission request by Some(PAR::sv2) P2P not ready
2026-11-11T14:05:30.184Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv3, MED::sv2] ser
2026-11-11T14:05:30.334Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv3, MED::sv2] ser
2026-11-11T14:05:30.360Z Rejecting submission request by Some(PAR::sv3) P2P not ready
2026-11-11T14:05:30.985Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv3, MED::sv2] ser
2026-11-11T14:05:31.011Z Rejecting submission request by Some(PAR::sv1) P2P not ready
=== 11368/logs-simtime-5
2026-11-11T16:33:16.653Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T16:33:16.700Z Rejecting submission request by Some(PAR::sv3) P2P not ready
2026-11-11T16:33:16.837Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T16:33:16.856Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T16:33:16.868Z Rejecting submission request by Some(PAR::sv2) P2P not ready
2026-11-11T16:33:16.884Z Rejecting submission request by Some(PAR::sv1) P2P not ready
2026-11-11T16:33:17.134Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T16:33:17.162Z Rejecting submission request by Some(PAR::sv4) P2P not ready
=== 11374/logs-simtime-0
...
2026-11-11T18:28:41.982Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T18:28:42.006Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T18:28:42.026Z Rejecting submission request by Some(PAR::sv1) P2P not ready
2026-11-11T18:28:42.039Z Rejecting submission request by Some(PAR::sv4) P2P not ready
2026-11-11T18:28:42.353Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T18:28:42.389Z Rejecting submission request by Some(PAR::sv3) P2P not ready
2026-11-11T18:28:42.908Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain, group = 0, threshold = 2, active=[MED::sv1, MED::sv4, MED::sv2, MED::sv3] ser
2026-11-11T18:28:42.937Z Rejecting submission request by Some(PAR::sv2) P2P not ready
```

(The `...` lines in 11362 and 11374 are the earlier serial-3 proposal adding the previous SV's mediator, threshold 1,
which went through before the switch.)

11358 per participant: sv1 and sv2 signatures were accepted before the switch, sv3's push got no response, sv4's was
rejected; the 12:02 re-proposals are the retries:

```
cd log/11358/logs-simtime-3; zcat canton-simtime.clog.gz | grep -a -E 'Attempting to build, sign, and Replace MediatorSynchronizerState|responded the following for the given topology transactions|Attempting to push [0-9]+ topology transactions' | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] > "2026-11-11T12:00:46" && a[1] < "2026-11-11T12:04:17") print}' | grep -a -oE '"@timestamp":"[^"]+"|participant=sv[1-4]Participant|serial Some\([0-9]+\)|Accepted,TxHash\(SHA-256:[0-9a-f]+|Attempting to push [0-9]+|Attempting to build, sign' | paste -sd' ' | sed 's/"@timestamp":/\n/g' | grep -a 'participant=sv' | sed -E 's/"//g' | cut -c1-140
```
```
2026-11-11T12:00:46.123Z Accepted,TxHash(SHA-256:d8424b1119eb participant=sv1Participant 
2026-11-11T12:00:46.232Z Attempting to push 1 participant=sv4Participant 
2026-11-11T12:00:46.455Z Accepted,TxHash(SHA-256:d8424b1119eb participant=sv2Participant 
2026-11-11T12:00:46.553Z Attempting to build, sign serial Some(4) participant=sv2Participant 
2026-11-11T12:00:46.558Z Attempting to build, sign serial Some(4) participant=sv1Participant 
2026-11-11T12:00:46.577Z Attempting to push 1 participant=sv2Participant 
2026-11-11T12:00:46.580Z Attempting to push 1 participant=sv1Participant 
2026-11-11T12:00:46.783Z Accepted,TxHash(SHA-256:d8424b1119eb participant=sv4Participant 
2026-11-11T12:00:46.988Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-11T12:00:47.010Z Attempting to push 1 participant=sv3Participant 
2026-11-11T12:00:47.104Z Accepted,TxHash(SHA-256:2003ec1f0857 participant=sv2Participant 
2026-11-11T12:00:47.128Z Accepted,TxHash(SHA-256:2003ec1f0857 participant=sv1Participant 
2026-11-11T12:00:47.788Z Attempting to build, sign serial Some(4) participant=sv4Participant 
2026-11-11T12:00:47.810Z Attempting to push 1 participant=sv4Participant 
2026-11-11T12:02:38.914Z Attempting to build, sign serial Some(4) participant=sv3Participant 
2026-11-11T12:02:50.581Z Attempting to build, sign serial Some(4) participant=sv4Participant 
```

sv4's rejected push is tx 2003ec1f0857, the MediatorSynchronizerState serial 4 adding MED::sv3 (trace
b3353202262dfe989ce85bfc45e4e103):

```
zcat canton-simtime.clog.gz | grep -a 'b3353202262dfe989ce85bfc45e4e103' | grep -a -iE 'mapping|MediatorSynchronizerState|authoriz|proposal|Sign' | sed -E '...' | cut -c1-400 | head -2
zcat canton-simtime.clog.gz | grep -a 'participant=sv4Participant' | grep -a -E 'QueueBasedSynchronizerOutbox|SynchronizerOutbox' | awk '<12:00:46.5 .. 12:00:48.5>' | tail -2
```
```
2026-11-11T12:00:47.788Z Attempting to build, sign, and Replace MediatorSynchronizerState( synchronizerId = global-domain::1220f306b718..., group = 0, threshold = 2, active = Seq(MED::sv1::122088c8a637..., MED::sv2::1220f3e794a2..., MED::sv4::1220d13df112..., MED::sv3::1220e6db46fa...) ) with serial Some(4) ",[c.d.c.t.SynchronizerTopologyManager:participant=sv4Participant/psid=global-domain::1220f
2026-11-11T12:00:47.809Z dequeuing: List(Traced(TxHash(SHA-256:2003ec1f0857...), tid:b3353202262dfe989ce85bfc45e4e103))",[c.d.c.t.SynchronizerOutboxQueue:participant=sv4Participant/psid=global-domain::1220f306b718::37-0] DEBUG
2026-11-11T12:00:47.810Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:2003ec1f0857...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220f306b718::37-0] DEBUG
```

sv4's send and its max sequencing time (sim time 08:50:01 vs. estimated sequencing time 08:49:41.239):

```
zcat canton-simtime.clog.gz | grep -a '744b9db2-8f89-4e47-bcdd-3e8732ac354e' | sed -E '...' | cut -c1-330 | head -12
```
```
2026-11-11T12:00:47.816Z 'PAR::sv4::122091ae5fcb...' sends request with id '744b9db2-8f89-4e47-bcdd-3e8732ac354e' of size 1028 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-11T12:00:47.817Z Request to send submission with id 744b9db2-8f89-4e47-bcdd-3e8732ac354e with max sequencing time 1970-01-01T08:50:01Z from PAR::sv4::122091ae5fcb... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220f306b718::37-0] DEBUG
2026-11-11T12:00:47.817Z Estimated sequencing time 1970-01-01T08:49:41.239Z for submission with id 744b9db2-8f89-4e47-bcdd-3e8732ac354e",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220f306b718::37-0] DEBUG
2026-11-11T12:00:47.828Z Rejecting submission request (744b9db2-8f89-4e47-bcdd-3e8732ac354e) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-d
```

sv3's push (message ff4885c7) was accepted by globalSequencerSv1 into a batch with reference epoch 116 at 12:00:47.073,
disseminated to the three newcomers after the switch at 12:00:47.178 and never ordered (the 11326 batch variant):

```
zcat canton-simtime.clog.gz | grep -a -E '1220db46d128|db46d128e93c' | sed -E '...' | cut -c1-260 | tail -6
zcat canton-simtime.clog.gz | grep -a 'ff4885c7-ac7d-4d62-9e27-745023b38967' | grep -a -c 'validated to'
```
```
2026-11-11T12:00:47.076Z Availability.LocalDissemination.LocalBatchesStored: persisted local batches List(Traced(BatchId(..0454))(TraceContext(trace id=b47fdbd8a83a9e2c9c3cd9876d97fe86, W3C context=W3CTraceContext(parent=00-b47fdbd8a83a9e2c9c3cd9876d97fe86-796
2026-11-11T12:00:47.077Z Availability.LocalDissemination.LocalBatchesStoredSigned: local batches List(Traced(BatchId(..0454))(TraceContext(trace id=b47fdbd8a83a9e2c9c3cd9876d97fe86, W3C context=W3CTraceContext(parent=00-b47fdbd8a83a9e2c9c3cd9876d97fe86-79653dd
2026-11-11T12:00:47.077Z Availability.LocalDissemination.LocalBatchesStoredSigned: progress of stored and signed BatchId(..0454) is Complete( membership = Membership( myId = \"SEQ::sv1::..f9eb\", orderingTopology = OrderingTopology( activationTime = 1970-01-01
2026-11-11T12:00:47.077Z Availability.LocalDissemination.LocalBatchesStoredSigned: BatchId(..0454) has already been sent to all nodes that should receive it for now,not disseminating further",[c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv1
2026-11-11T12:00:47.178Z Availability.LocalDissemination.LocalBatchesStoredSigned: local batches List(Traced(BatchId(..0454))(TraceContext(trace id=b47fdbd8a83a9e2c9c3cd9876d97fe86, W3C context=W3CTraceContext(parent=00-b47fdbd8a83a9e2c9c3cd9876d97fe86-79653dd
2026-11-11T12:00:47.178Z Availability.LocalDissemination.LocalBatchesStoredSigned: disseminating BatchId(..0454) to SEQ::sv2::..aca8, SEQ::sv4::..fadc, SEQ::sv3::..2388",[c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv1/psid=global-domain::1
0
```

The re-proposals find the old flush cycle still open (first four after each switch; 11374 has none because the
setup timed out 100 s after the switch, before the re-proposal):

```
cd log; h(){ d=$1 from=$2; echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a 'Another flush cycle is currently ongoing' | awk -F'"@timestamp":"' -v f=$from '{split($2,a,"\""); if (a[1] > f) print a[1], $0}' | grep -a -oE '^[^ ]+|participant=sv[1-4]Participant' | paste -d' ' - - | head -4; }
h 11358/logs-simtime-3 2026-11-11T12:00:47.172; h 11362/logs-simtime-2 2026-11-11T14:05:29.962; h 11368/logs-simtime-5 2026-11-11T16:33:16.633; h 11374/logs-simtime-0 2026-11-11T18:28:41.723
```
```
=== 11358/logs-simtime-3
2026-11-11T12:02:38.945Z participant=sv3Participant
2026-11-11T12:02:50.604Z participant=sv4Participant
=== 11362/logs-simtime-2
2026-11-11T14:05:30.202Z participant=sv4Participant
2026-11-11T14:07:23.311Z participant=sv4Participant
2026-11-11T14:07:25.526Z participant=sv3Participant
2026-11-11T14:07:27.497Z participant=sv1Participant
=== 11368/logs-simtime-5
2026-11-11T16:35:05.258Z participant=sv4Participant
2026-11-11T16:35:12.911Z participant=sv3Participant
2026-11-11T16:35:16.863Z participant=sv1Participant
2026-11-11T16:35:21.579Z participant=sv2Participant
=== 11374/logs-simtime-0
```

Not family C (false off-boarding, the older `Mediator has No traffic` hits 10054/10076):

```
cd log; for d in 11358/logs-simtime-3 11362/logs-simtime-2 11368/logs-simtime-5 11374/logs-simtime-0; do echo "$d off-boarded: $(zcat $d/canton-simtime.clog.gz | grep -a -c "this node isn't part of it")"; done
```
```
11358/logs-simtime-3 off-boarded: 0
11362/logs-simtime-2 off-boarded: 0
11368/logs-simtime-5 off-boarded: 0
11374/logs-simtime-0 off-boarded: 0
```

## 6. Blast radius: setup failure skips the resets, later suites fail sv1's DSO party allocation

Resets in the whole test log (section 2 shows none after the first failure; 11374 had none at all because the
failing suite was the shard's first):

```
cd log; for d in 11358/logs-simtime-3 11362/logs-simtime-2 11368/logs-simtime-5 11374/logs-simtime-0; do echo "=== $d"; zcat $d/canton_network_test.clog.gz | awk '/decentralized namespace has been reset/{r++; last=substr($0,15,24)} END{print "resets",r+0,"last_reset",last}'; done
```
```
=== 11358/logs-simtime-3
resets 2 last_reset "2026-11-11T11:58:42.711
=== 11362/logs-simtime-2
resets 6 last_reset "2026-11-11T14:03:34.808
=== 11368/logs-simtime-5
resets 1 last_reset "2026-11-11T16:31:13.177
=== 11374/logs-simtime-0
resets 0 last_reset 
```

sv1 in the next suite of 11358 retries the DSO PartyToParticipant until its init times out:

```
cd log/11358/logs-simtime-3; zcat canton-simtime.clog.gz | grep -a 'ed5dff46c0d25939ae47539a61a2f1d1' | grep -a -E 'WARN|ERROR|INFO' | sed -E '...' | cut -c1-360 | head -4
```
```
2026-11-11T12:08:06.430Z Attempting to build, sign, and Replace PartyToParticipant(partyId = DSO-d3e08a91-d3e08a91::1220f306b718..., participants = PAR::sv1::1220fcc0eac6... -> Submission) with serial Some(1) ",[c.d.c.t.SynchronizerTopologyManager:participant=sv1Participant/psid=global-domain::1220f306b718::37-0] INFO
2026-11-11T12:08:06.448Z Topology transactions failed: ValidatedTopologyTransaction( SignedTopologyTransaction( TopologyTransaction( PartyToParticipant(partyId = DSO-d3e08a91-d3e08a91::1220f306b718..., participants = PAR::sv1::1220fcc0eac6... -> Submission), serial = 1, operation = Replace, hash = SHA-256:cdb7dbab32fd... ), signatures = 1220fcc0eac6... ), re
2026-11-11T12:08:06.448Z TOPOLOGY_UNAUTHORIZED_TRANSACTION(5,ed5dff46): Topology transaction is missing authorizations by namespaces=Set(1220f306b718...) and keys=Set()",[c.d.c.t.SynchronizerTopologyManager:participant=sv1Participant/psid=global-domain::1220f306b718::37-0] WARN
2026-11-11T12:08:06.449Z Request com.digitalasset.canton.topology.admin.v30.TopologyManagerWriteService/Authorize by grpc:/127.0.0.1:49886: failed with INVALID_ARGUMENT/An error occurred. Please contact the operator and inquire about the request ed5dff46c0d25939ae47539a61a2f1d1 with tid ed5dff46c0d25939ae47539a61a2f1d1 Trailers: Metadata(grpc-status-details-
```

The same rejection on sv1Participant in every run, starting right after the first failure:

```
cd log; for d in ...; do echo "=== $d"; zcat $d/canton-simtime.clog.gz | grep -a 'TOPOLOGY_UNAUTHORIZED_TRANSACTION' | grep -a 'participant=sv1Participant' | awk -F'"@timestamp":"' '{split($2,a,"\""); n++; if(!f) f=a[1]; l=a[1]} END{print "sv1 unauthorized:",n+0,"first",f,"last",l}'; done
```
```
=== 11358/logs-simtime-3
sv1 unauthorized: 252 first 2026-11-11T12:04:30.641Z last 2026-11-11T12:19:58.279Z
=== 11362/logs-simtime-2
sv1 unauthorized: 168 first 2026-11-11T14:11:08.288Z last 2026-11-11T14:21:40.548Z
=== 11368/logs-simtime-5
sv1 unauthorized: 86 first 2026-11-11T16:36:54.919Z last 2026-11-11T16:41:57.698Z
=== 11374/logs-simtime-0
sv1 unauthorized: 251 first 2026-11-11T18:30:38.845Z last 2026-11-11T18:45:56.173Z
```

The setup-failure path in the vendored test framework (identical at all four shas) only closes the fixture; the
plugins' `beforeEnvironmentDestroyed` (where the resets run) is not called:

```
F=canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala
git show 915616a77d:$F | sed -n '232,256p'
for s in 2d54198a47 2fc780a883 1ee8c88066; do git diff --quiet $s 915616a77d -- $F && echo "$s same"; done
```
```
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
    }

2d54198a47 same
2fc780a883 same
1ee8c88066 same
```

## 7. Frequency on main, and where it started

Post-merge main runs (workflow 163795441) since 2026-11-06, simtime jobs per run (all attempts), paged with
`per_page=40`:

```
gh api "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&per_page=100&page=$p&created=>=2026-11-06" --jq '.workflow_runs[] | "\(.id) \(.head_sha[0:10]) \(.created_at) \(.status) \(.conclusion) \(.run_attempt) \(.event)"'   # p=1,2 -> log/g1-simtime/runs.txt (55 runs)
fetch(){ r=$1; for p in 1 2 3 4 5; do ... gh api "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=40&page=$p" ...; echo "$out" | jq -r --arg r $r '.jobs[] | select(.name|test("(^| / )simtime \\([0-9]+\\)$")) | "\($r) \(.id) a=\(.run_attempt) \(.conclusion) \(.started_at) \(.completed_at) \(.name|sub(".* / ";""))"'; [ "${n:-0}" -lt 40 ] && break; done > jobs-$r.txt; }
```

Per day (attempt 1):

```
cd log/g1-simtime; awk '{print $1, substr($3,1,10)}' runs.txt | while read r d; do n=$(awk '$3=="a=1"' jobs-$r.txt | wc -l); f=$(awk '$3=="a=1" && $4=="failure"' jobs-$r.txt | wc -l); echo "$d $n $f"; done | awk '$2>0{runs[$1]++; if($3>0) fr[$1]++; jobs[$1]+=$2; fj[$1]+=$3} END{for(d in runs) print d, "runs_with_simtime", runs[d], "runs_with_failed_simtime_a1", fr[d]+0, "simtime_jobs_a1", jobs[d], "failed", fj[d]+0}' | sort
```
```
2026-11-06 runs_with_simtime 11 runs_with_failed_simtime_a1 3 simtime_jobs_a1 58 failed 4
2026-11-09 runs_with_simtime 12 runs_with_failed_simtime_a1 7 simtime_jobs_a1 72 failed 7
2026-11-10 runs_with_simtime 16 runs_with_failed_simtime_a1 6 simtime_jobs_a1 96 failed 7
2026-11-11 runs_with_simtime 15 runs_with_failed_simtime_a1 4 simtime_jobs_a1 90 failed 4
```

Since splice#8356 (2ef0423979, merged 2026-11-10 23:29 UTC), per run: 4 of 16 runs with simtime jobs failed one
simtime shard, exactly the four refs here (43054307546, the #8432 merge, ran no simtime job):

```
cd log/g1-simtime; awk '$3>="2026-11-10T23:00"' runs.txt | while read r s t st c a e; do f=$(awk '$4=="failure"' jobs-$r.txt 2>/dev/null | awk '{printf "%s%s(%s) ", $7,$8,$3}'); n=$(awk '$3=="a=1"' jobs-$r.txt | wc -l); ok=$(awk '$3=="a=1" && $4=="success"' jobs-$r.txt | wc -l); echo "$r $s $t run=$c simtime_a1=$ok/$n failed: $f"; done
```
```
42923829975 2ef0423979 2026-11-10T23:29:49Z run=failure simtime_a1=6/6 failed: 
42948633392 9dd892fe2e 2026-11-11T02:49:37Z run=success simtime_a1=6/6 failed: 
42975175376 b3502738ae 2026-11-11T06:49:59Z run=failure simtime_a1=6/6 failed: 
43000309945 3b2f0ae469 2026-11-11T10:03:06Z run=success simtime_a1=6/6 failed: 
43004518396 d2cf5f33f5 2026-11-11T10:32:50Z run=success simtime_a1=6/6 failed: 
43012738610 2d54198a47 2026-11-11T11:32:11Z run=success simtime_a1=5/6 failed: simtime(3)(a=1) 
43013871503 c961df87ba 2026-11-11T11:40:23Z run=success simtime_a1=6/6 failed: 
43030349815 2fc780a883 2026-11-11T13:34:29Z run=failure simtime_a1=5/6 failed: simtime(2)(a=1) 
43030542628 5bdf3cc88f 2026-11-11T13:35:44Z run=failure simtime_a1=6/6 failed: 
43031077112 644b3ba97a 2026-11-11T13:39:19Z run=failure simtime_a1=6/6 failed: 
43031491527 5a0be64241 2026-11-11T13:42:02Z run=success simtime_a1=6/6 failed: 
43054307546 3b5b82822d 2026-11-11T16:02:25Z run=failure simtime_a1=0/0 failed: 
43055359204 1ee8c88066 2026-11-11T16:08:42Z run=failure simtime_a1=5/6 failed: simtime(5)(a=1) 
43072152132 08774bc437 2026-11-11T17:51:21Z run=failure simtime_a1=6/6 failed: 
43073677994 ee8772cc0b 2026-11-11T18:01:02Z run=failure simtime_a1=6/6 failed: 
43073759004 915616a77d 2026-11-11T18:01:30Z run=failure simtime_a1=5/6 failed: simtime(0)(a=1) 
43078335366 d356bcf573 2026-11-11T18:30:00Z run=success simtime_a1=6/6 failed: 
```

Before #8356: 7 of 12 (2026-11-09) and 6 of 15 (2026-11-10, incl. the first #8356 run) runs; those hits were split
between the lost-verdict and rejected-send shapes (11255 comments of 2026-11-09 and 2026-11-10). #8356 removed the
blacklisting shapes; the rejected-send shape is unchanged.

Start: the first simtime hits of this shape were 11241/11242 on run 42230239729 (c218905f75), the first main run
after splice#8234 ("Use DA BFT in all tests"); the #8234 merge run itself and the run before passed:

```
for n in 11241 11242; do gh api repos/DACH-NY/cn-test-failures/issues/$n --jq '"\(.number) \(.state)/\(.state_reason) \(.created_at) \(.title[0:90])"'; ... | grep -E '^Commit:' | cut -c1-120; done
git log -1 --format='%h %ad %s' --date=iso-strict <#8234 commit>
cd log/g1-simtime; for r in 42214429624 42226645323 42230239729; do echo "$r: $(awk '{printf "%s%s=%s ", $7,$8,$4}' jobs-$r.txt)"; done
```
```
11241 closed/duplicate 2026-11-06T14:24:45Z GHA Run 42230239729 : job simtime (2) in scala_test Failed :fire:
Commit: [c218905](https://github.com/canton-network/splice/commit/c218905f75bc2f9d761d8c552ccea1bfafc1ecde) [ci] Bump @d
11242 closed/duplicate 2026-11-06T14:39:47Z GHA Run 42230239729 : job simtime (1) in scala_test Failed :fire:
Commit: [c218905](https://github.com/canton-network/splice/commit/c218905f75bc2f9d761d8c552ccea1bfafc1ecde) [ci] Bump @d
b77153c658 2026-11-06T14:34:52+01:00 Use DA BFT in all tests (#8234)
42214429624: simtime(1)=success simtime(3)=success simtime(0)=success simtime(2)=success 
42226645323: simtime(5)=success simtime(3)=success simtime(0)=success simtime(2)=success simtime(4)=success simtime(1)=success 
42230239729: simtime(4)=success simtime(5)=success simtime(3)=success simtime(2)=failure simtime(0)=success simtime(1)=failure 
```

No common 2026-11-11 cause: the four shas are unrelated test or perf changes, #8356 is in all four, and the Canton pin
differs (11374 runs the 2026-11-11 snapshot from #8441, the others the 2026-11-09 one):

```
for s in 2d54198 2fc780a 1ee8c88 915616a; do full=$(git rev-parse $s); echo "$s $full $(git log -1 --format='%ad %s' --date=iso-strict $s) | 8356: $(git merge-base --is-ancestor 2ef0423979 $s && echo in || echo no) | canton: $(git show $s:nix/canton-sources.json | grep -m1 version)"; done
```
```
2d54198 2d54198a475f5ad3f818a9fcda403422fdea4fdc 2026-11-11T12:32:07+01:00 SvFrontendIntegrationTest: tag the Featured App Right test (#8427) | 8356: in | canton:   "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
2fc780a 2fc780a883cbad8279b34400ee5999a2d1b681ff 2026-11-11T14:34:25+01:00 Pause CloseVoteRequestTrigger on all SVs in the SvFrontendIntegrationTest expiring proposal step (#8417) | 8356: in | canton:   "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
1ee8c88 1ee8c880660fcaf6cfd458700c3dcda6085a204b 2026-11-11T17:08:38+01:00 threshold fixes for new perf tests (#8434) | 8356: in | canton:   "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
915616a 915616a77d56c0f94fcf7ab75f58e810065cdf83 2026-11-11T19:01:26+01:00 Allow 80 s for the claimed SV reward coupons check (#8413) | 8356: in | canton:   "version": "3.7.3-snapshot.20261111.20904.0.vbe797d9d",
```

#8356 itself is effective (all epoch lines in section 4 show `blacklisted nodes = List()`). Its diff:

```
git show 2ef0423979 -- apps/app/src/main/scala/org/lfdecentralizedtrust/splice/config/ConfigTransforms.scala | sed -n '/A BFT sequencer/,/^ +$/p'
```
```
+  // A BFT sequencer that misses its segment (e.g., right after onboarding or under load) is
+  // blacklisted for several epochs, during which it rejects all client submissions.
+  // In simtime tests this causes flakes right after sequencer onboarding (rejected sends, lost
+  // verdicts). Applied in EnvironmentDefinition.withSimTime only.
+  def disableBftBlacklisting(): ConfigTransform =
```

## 8. Duplicate check

Parent and its owner discussion:

```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"\(.state) \(.title) assignees=\([.assignees[].login]|join(",")) comments=\(.comments)"'
gh api repos/DACH-NY/cn-test-failures/issues/11255/comments --jq '.[] | "-- \(.user.login) \(.created_at)\n\(.body)"' | grep -E '^-- |not addressed|covers the lost-verdict'
```
```
open Timeouts due to changing bft ordering topology assignees=rautenrieth-da comments=3
-- rautenrieth-da 2026-11-08T09:35:18Z
-- stephencompall-DA 2026-11-09T21:27:12Z
- Sends rejected or batches never ordered at the 1 to 4 switch, no blacklisting involved (not addressed): the description above, #11241, #11242, #11282, #11283, #11288, #11289.
-- stephencompall-DA 2026-11-10T14:20:28Z
canton-network/splice#8356 (still open) covers the lost-verdict and breaker shapes, not the rejected-send one.
```

Search for the stuck wait in closed issues:

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Mediator has been granted unlimited traffic"' --jq '.items[] | "\(.number) \(.state)/\(.state_reason) \(.created_at[0:10]) \(.title[0:90])"'
```
```
11323 closed/duplicate 2026-11-10 GHA Run 42759795825 : job simtime (4) in scala_test Failed :fire:
10076 closed/duplicate 2026-09-09 SvOnboardingViaNonFoundingSvIntegrationTest
10054 closed/duplicate 2026-09-07 SvInitializationIntegrationTest: sv3 fails to initialized because Mediator has No traffic 
10044 closed/completed 2026-09-04 Newly onboarded BFT sequencer before membership takes effect is mistaken for off-boarded n
10049 closed/duplicate 2026-09-04 BftScanConnectionIntegrationTest
3093 closed/completed 2025-03-13 sequencer seems to get stuck in time awaiter shortly after initialization
```

- 11323: same shape (MED::sv2 add lost at the 1 -> 4 step), closed as dup of 11255. Same failure.
- 10044, 10049, 10054, 10076: family C (newly onboarded sequencer mistaken for off-boarded), fixed in Canton; ruled
  out here by zero `this node isn't part of it` (section 5).
- 3093: 2025 sequencer time-awaiter hang, unrelated.

The setup-failure blast radius has no fix on main yet (suggested on 11332 on 2026-11-10; no commit since to the
test plugins or EnvironmentDefinition other than #8356):

```
git log --since=2026-11-10 --format='%h %ad %s' --date=short https-origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala
```
```
2ef0423979 2026-11-11 Disable BFT sequencer blacklisting in simtime integration tests (#8356)
```

## Verdict

Duplicates of 11255 (family B), rejected-send / batch-never-ordered shape at the 1 -> 4 ordering-topology step under
sim time, all four; the shard-wide damage is the setup-failure blast radius already described for 11324/11332/11341.
Not AM (DAR uploads are not where the stuck SV's time went), not a 2026-11-11 regression (shape present since the
first run after #8234 on 2026-11-06; #8356 removed only the blacklisting shapes, which leaves this one at 4 of 16
post-merge runs since). Not runner slowness: once the signatures are lost, the add can only complete after the sim
clock passes the max sequencing time, which nothing advances during setup.

Fix location (unchanged from the parent): Canton/test environment for the 1 -> 4 step (e.g. onboard the SV
sequencers one at a time so no epoch starts with one authenticated node, as proposed on 11255), and the test
framework for the blast radius (run the topology reset plugins on the `Running setup` failure path, vendored
`EnvironmentSetup.manualCreateEnvironment` or a splice-side hook; see 11332).

Related groups (not triaged here): 11363 (key-download) and 11373 (frontend-wall-clock-time) run on wall clock, where
a rejected send costs at most its max sequencing time instead of wedging; this cause would explain them only if their
logs show the same rejections at the ordering step, not checked. 11371/11372 roll-forward-lsu: family AK is the same
ordering-topology growth window (BFT since #8234), not checked here.

Not verified: why sv3's batch in 11358 was not ordered by the new 4-node epoch (Canton internals, as in 11326); what
the namespace threshold for the MediatorSynchronizerState change is (2 of 4 signatures in 11358 were not enough).
