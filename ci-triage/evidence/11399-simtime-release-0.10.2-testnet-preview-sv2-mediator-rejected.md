# 11399 - simtime (2) release-line-0.10.2: MED::sv2 proposal rejected at the 1 -> 4 switch in TimeBasedTestNetPreview setup, then 3 suites fail (run 43281177240)

DUPLICATE of 11255 (rejected-send shape plus the setup-failure blast radius). release-line-0.10.2 48f45bf330, job
simtime (2), Canton 3.7.3. In the setup of TimeBasedTestNetPreviewIntegrationTest the global ordering topology goes
to 4 nodes at 20:23:15.807; sv1's sequencer rejects the pushes of the MediatorSynchronizerState proposal that adds
MED::sv2 from sv1, sv2 and sv4 (0.02-0.76 s later, `P2P connectivity is not ready`); only sv3's push went out 0.06 s
before the switch. sv2's SV app waits on `Condition is not yet observed. Proposed: MediatorSynchronizerState` until
the 5 min `SvAppReference.waitForInitialization` timeout at 20:28:36. The setup failure skips
ResetDecentralizedNamespace (no `has been reset` after 20:21:13), so FeaturedAppConfigUpdateInstructionTimeBased (2
tests, setup), ValidatorLicenseMetadataTimeBased (3 tests, `sv1Scan ... no app state`, fixture) and
WalletTxLogWithSynchronizerFeesNoDevNetTimeBased (aborted) fail after it.

- Run: https://github.com/canton-network/splice/actions/runs/43281177240, release-line-0.10.2 48f45bf330 ("Backport
  #8456 to release-line-0.10.2 (#8462)"), job 131403002857 `ci / scala_test_sim_time / simtime (2)`, attempt 1.
- Runtime canton: 3.7.3 (`git show 48f45bf330:nix/canton-sources.json`).
- Component: Canton BFT ordering under simulated time; splice test environment (setup failure skips the namespace
  reset).

## 1. Job: 6 failed, 1 aborted

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11399/job.txt | grep -a -E 'FAILED \*\*\*|ABORTED|Tests: succeeded' | sed -E 's/^[^Z]*Z //' | sort -u
grep -n -A6 'TestNet initializes correctly \*\*\* FAILED' log/11399/job.txt | head -7 | sed -E 's/^[^Z]*Z //'
```
```
[info] - ExecuteFeaturedAppConfigUpdateInstructionTrigger applies the voted config update *** FAILED ***
[info] - ExpireFeaturedAppConfigUpdateInstructionTrigger expires an unexecuted instruction *** FAILED ***
[info] - TestNet initializes correctly *** FAILED ***
[info] - contact point gets updated *** FAILED ***
[info] - lastActiveAt gets updated *** FAILED ***
[info] - unclaimed ValidatorLivenessActivityRecord contracts should be expired *** FAILED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest *** ABORTED ***
[info] Tests: succeeded 5, failed 6, canceled 0, ignored 0, pending 0
[info] - TestNet initializes correctly *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
```

## 2. Suite timeline

Artifact `logs-simtime-2` in `log/11399/logs-simtime-2/`.
```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |(Finished|Failed) creating environment: Running setup|has been reset" | grep -a 'T20:[1-4]' | tail -20
```
```
2026-11-12T20:21:13.182Z decentralized namespace has been reset
2026-11-12T20:21:13.196Z sequencer domain state threshold has been reset
2026-11-12T20:21:13.226Z Test succeeded: 'TimeBasedTreasuryIntegrationTestWithoutMerging/scan-connection caching avoids unnecessary network calls and re-sending contracts if they are already known by ...
2026-11-12T20:21:13.314Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...
2026-11-12T20:28:36.062Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)
2026-11-12T20:28:36.280Z Test failed: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:234), l...
2026-11-12T20:28:36.295Z Starting test suite 'FeaturedAppConfigUpdateInstructionTimeBasedIntegrationTest'...
2026-11-12T20:33:53.249Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)
2026-11-12T20:33:53.293Z Test failed: 'FeaturedAppConfigUpdateInstructionTimeBasedIntegrationTest/ExecuteFeaturedAppConfigUpdateInstructionTrigger applies the voted config update', message: Creating e...
2026-11-12T20:39:06.894Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)
2026-11-12T20:39:06.938Z Test failed: 'FeaturedAppConfigUpdateInstructionTimeBasedIntegrationTest/ExpireFeaturedAppConfigUpdateInstructionTrigger expires an unexecuted instruction', message: Creating ...
2026-11-12T20:39:06.948Z Starting test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'...
2026-11-12T20:44:08.339Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/contact point gets updated', message: The app state of sv1Scan is currently not accessible. Node doesn't have an...
2026-11-12T20:44:08.358Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/lastActiveAt gets updated', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scal...
2026-11-12T20:44:08.377Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/unclaimed ValidatorLivenessActivityRecord contracts should be expired', message: Creating environment failed at ...
2026-11-12T20:44:08.387Z Starting test suite 'WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest'...
```
```
zcat canton_network_test.clog.gz | grep -a -c 'T20:2[89].*has been reset\|T20:[3-4].*has been reset'
```
```
0
```

## 3. The 1 -> 4 switch and the rejected MED::sv2 pushes

```
zcat canton-simtime.clog.gz | grep -a -E "New epoch [0-9]+ .*size = |Rejecting submission request by .*P2P connectivity" | grep -a 'T20:2[2-4]' | <first line per distinct message>
```
```
2026-11-12T20:23:08.902Z New epoch 77 has started with leaders = List(SEQ::sv1::1220a3003d63...) ... size = 1
2026-11-12T20:23:15.807Z New epoch 78 has started with leaders = List(SEQ::sv3::..., SEQ::sv4::...
2026-11-12T20:23:15.852Z Rejecting submission request by Some(PAR::sv1::12209244c6c8...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dis...
2026-11-12T20:23:16.038Z Rejecting submission request by Some(PAR::sv2::12203dc6b591...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dis...
2026-11-12T20:23:16.563Z Rejecting submission request by Some(PAR::sv4::1220df214ce4...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dis...
```
```
zcat canton-simtime.clog.gz | grep -a 'T20:23:1[56]' | grep -a 'QueueBasedSynchronizerOutbox' | grep -a 'Attempting to push' | sed -E '<time participant txhash>'   # + mapping per hash
zcat canton-simtime.clog.gz | grep -a 'a36a3f516aac' | grep -a -oE 'active = [^)]{0,120}' | sort -u | head -1
```
```
2026-11-12T20:23:15.053Z sv4 0ca1e475bf2a SequencerSynchronizerState
2026-11-12T20:23:15.095Z sv1 0ca1e475bf2a SequencerSynchronizerState
2026-11-12T20:23:15.283Z sv3 0ca1e475bf2a SequencerSynchronizerState
2026-11-12T20:23:15.748Z sv3 a36a3f516aac MediatorSynchronizerState
2026-11-12T20:23:15.829Z sv1 a36a3f516aac MediatorSynchronizerState
2026-11-12T20:23:16.030Z sv2 a36a3f516aac MediatorSynchronizerState
2026-11-12T20:23:16.555Z sv4 a36a3f516aac MediatorSynchronizerState
active = Seq(MED::sv1::122000a54d57..., MED::sv4::1220cdd67e31..., MED::sv3::12201c372b0f..., MED::sv2::122050266f83...
```
```
zcat canton-simtime.clog.gz | grep -a -c 'Failed to send result'
zcat canton_network_test.clog.gz | grep -a -E 'T20:2[4-8]' | grep -a 'SV=sv2"' | grep -a -E 'not stale|Giving up' | head -2
```
```
0
2026-11-12T20:28:36.212Z Task that failed with following exception is not stale: FAILED_PRECONDITION: Condition is not yet observed. Proposed: MediatorSynchronizerState(\n  synchronizerId = global-dom...
2026-11-12T20:28:36.212Z Giving up on retrying the operation 'processTaskWithRetry' due to shutdown. Last attempt was None with exception: FAILED_PRECONDITION: Condition is not yet observed. Proposed:...
```

## Verdict

Duplicate of 11255, rejected-send shape on release-line-0.10.2 (same Canton 3.7.3 as main), with the
setup-failure blast radius over three later suites.
