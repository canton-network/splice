# 11668 - simtime (2): sv2 and sv3 wait on mediator traffic after the 1 -> 4 ordering topology step, sv3 init timeout (run 44584727319)

DUPLICATE of 11255 (family B, rejected-send shape under simulated time). release-line-0.10.2 b12a1318f6, job
`ci / scala_test_sim_time / simtime (2)`, Canton 3.7.3. Tests: succeeded 5, failed 5. The setup of
TimeBasedTestNetPreviewIntegrationTest grew the BFT ordering topology 1 -> 4 at 18:39:15.657 (epoch 78, no
blacklisting); sv1's sequencer rejected the submissions of PAR::sv3 and PAR::sv2 156 ms and 418 ms after the switch
(`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`); sv2 and sv3 then retried
`Check whether Mediator has been granted unlimited traffic` from 18:39:20 until the 5 min init wait expired on sv3 at
18:42:40.417. The other four failures are the setup-failure blast radius (no topology reset, sv1 init timeout in
the next suite, then the teardown leak cascade of family H).

- Run: https://github.com/canton-network/splice/actions/runs/44584727319, release-line-0.10.2 b12a1318f6
  ("Backport PR #8521 to release-line-0.10.2 (#8593)"), job 135775013250 `ci / scala_test_sim_time / simtime (2)`.
- Runtime canton: 3.7.3 (`git show b12a1318f6:nix/canton-sources.json | grep -m1 version`).
- Component: Canton BFT ordering topology change (simtime); test-side mitigation splice#8363 is still open.

Paths below are relative to the triage checkout; `T=log/11668/logs-simtime-2/canton_network_test.clog.gz`,
`C=log/11668/logs-simtime-2/canton-simtime.clog.gz`.

## 1. Failing assertions

One failed run, one failed job; the tuple is the one given by the user.

```
gh run view 44584727319 -R canton-network/splice --json headSha,headBranch,jobs --jq '{headSha,headBranch,failed:[.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{name,databaseId}]}'
```
```
{"failed":[{"databaseId":135775013250,"name":"ci / scala_test_sim_time / simtime (2)"}],"headBranch":"release-line-0.10.2","headSha":"b12a1318f6c74e478db8519a1982aa3309c7bde1"}
```

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11668/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
[info] *** 5 TESTS FAILED ***
[info] - TestNet initializes correctly *** FAILED ***
[info] - contact point gets updated *** FAILED ***
[info] - lastActiveAt gets updated *** FAILED ***
[info] - list and automatically collect app & validator rewards *** FAILED ***
[info] - should list and automatically collect app & validator rewards *** FAILED ***
[info] - unclaimed ValidatorLivenessActivityRecord contracts should be expired *** FAILED ***
[info] Run completed in 20 minutes, 59 seconds.
[info] Tests: succeeded 5, failed 5, canceled 0, ignored 0, pending 0
```

First failure and stack head (`grep -a -A6 'FAILED \*\*\*'` on the same stream):
```
[info] - TestNet initializes correctly *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
```

## 2. Suite timeline

The first failure is the setup of TimeBasedTestNetPreviewIntegrationTest, timing out on sv3; everything after it
fails within 5 min or immediately.

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Timeout while waiting for initialization" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | sed -n '6,$p'
```
```
2026-11-19T18:37:06.814Z Test succeeded: 'TimeBasedTreasuryIntegrationTestWithoutMerging/scan-connection caching avoids unnecessary network calls and re-sending contracts if they are already known by the client'",
2026-11-19T18:37:06.894Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
2026-11-19T18:42:40.417Z Timeout while waiting for initialization of sv3",
2026-11-19T18:42:40.613Z Test failed: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:234), location: SeeStackDepthException",
2026-11-19T18:42:40.629Z Starting test suite 'ValidatorLicenseMetadataTimeBasedIntegrationTest'...",
2026-11-19T18:47:42.510Z Timeout while waiting for initialization of sv1",
2026-11-19T18:47:42.513Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/contact point gets updated', message: The app state of sv1Scan is currently not accessible. Node doesn't have any app state., location: SeeStackDepthException",
2026-11-19T18:47:42.535Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/lastActiveAt gets updated', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146), location: SeeStackDepthException",
2026-11-19T18:47:42.558Z Test failed: 'ValidatorLicenseMetadataTimeBasedIntegrationTest/unclaimed ValidatorLivenessActivityRecord contracts should be expired', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146), location
2026-11-19T18:47:42.571Z Starting test suite 'WalletRewardsTimeBasedIntegrationTest'...",
2026-11-19T18:47:42.835Z Test failed: 'WalletRewardsTimeBasedIntegrationTest/A wallet should list and automatically collect app & validator rewards', message: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146), location: SeeStack
2026-11-19T18:47:42.849Z Starting test suite 'SvTimeBasedRewardCouponIntegrationTest'...",
2026-11-19T18:47:43.434Z Starting test suite 'FeaturedAppRightSwitchOverTimeBasedIntegrationTest'...",
```

## 3. What sv2 and sv3 were waiting on

sv1 finished init at 18:37:46.711 and sv4 finished `JoiningNodeInitializer joining Dso with key` at 18:39:41.367
(1M57). sv2 and sv3 got their sequencer onboarded through the sponsor at 18:39:16 and then stayed in the mediator
traffic wait (56 and 58 failed checks, the last 2 to 4 s before the timeout) until the environment was closed.

```
zcat $T | grep -a 'T18:39:[12]\|T18:4[0-2]' | grep -a -E 'config=476c84db/SV=sv(2|3)"' | grep -a -E '"message":"(Onboarding (sequencer|mediator)|Waiting until Mediator|HTTP client \(POST /api/sv/v0/onboard/sv/sequencer\): HTTP request took)' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/o.l.s.s.([A-Za-z]+):TimeBasedTestNetPreviewIntegrationTest\/config=476c84db\//\1:/' | cut -c1-200
for s in sv2 sv3; do zcat $T | grep -a "config=476c84db/SV=$s\"" | grep -a "The operation 'Check whether Mediator has been granted unlimited traffic' has failed" | awk -v s=$s '{n++; if(n==1) f=substr($0,16,24); l=substr($0,16,24)} END{print s, n, "first=" f, "last=" l}'; done
zcat $T | grep -a 'app initialization: .*\(Initialization complete\|joining Dso with key finished\)' | grep -a 'config=476c84db' | grep -a -E '"sv[1-4] app' | sed -E 's/"logger_name".*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /'
```
```
2026-11-19T18:39:12.435Z Onboarding sequencer",[LocalSynchronizerNode:SV=sv2] INFO
2026-11-19T18:39:13.413Z Onboarding sequencer SEQ::sv2::122002b7ec9c... through sponsoring SV",[LocalSynchronizerNode:SV=sv2] INFO
2026-11-19T18:39:16.010Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 2596 ms to complete. Received response with status code: 429 Too Many Requests",[SvApp:SV=sv2] DEBUG
2026-11-19T18:39:16.115Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 6312 ms to complete. Received response with status code: 429 Too Many Requests",[SvApp:SV=sv3] DEBUG
2026-11-19T18:39:16.331Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 14 ms to complete. Received response with status code: 429 Too Many Requests",[SvApp:SV=sv3] DEBUG
2026-11-19T18:39:16.408Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 192 ms to complete. Received response with status code: 200 OK",[SvApp:SV=sv2] DEBUG
2026-11-19T18:39:16.912Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 186 ms to complete. Received response with status code: 200 OK",[SvApp:SV=sv3] DEBUG
2026-11-19T18:39:19.809Z Onboarding mediator",[LocalSynchronizerNode:SV=sv2] INFO
2026-11-19T18:39:19.809Z Waiting until Mediator has been granted unlimited traffic",[LocalSynchronizerNode:SV=sv2] INFO
2026-11-19T18:39:20.169Z Onboarding mediator",[LocalSynchronizerNode:SV=sv3] INFO
2026-11-19T18:39:20.169Z Waiting until Mediator has been granted unlimited traffic",[LocalSynchronizerNode:SV=sv3] INFO
sv2 56 first=2026-11-19T18:39:19.813Z last=2026-11-19T18:42:38.000Z
sv3 58 first=2026-11-19T18:39:20.173Z last=2026-11-19T18:42:36.717Z
2026-11-19T18:37:46.711Z sv1 app initialization: Initialization complete, running on version 0.10.2",
2026-11-19T18:39:41.367Z sv4 app initialization: JoiningNodeInitializer joining Dso with key finished after PT1M57.464399034S",
2026-11-19T18:39:42.713Z sv4 app initialization: Initialization complete, running on version 0.10.2",
```

## 4. Family B confirming grep (Canton 3.7.3 log)

Ordering topology of the global synchronizer 1 -> 4 in one step at 18:39:15.657, `blacklisted nodes = List()`;
sv1's sequencer rejects PAR::sv3 156 ms and PAR::sv2 418 ms after the switch; zero `currently blacklisted`, zero
`Failed to send result` (rules out the lost-verdict and breaker shapes).

```
zcat $C | grep -a 'T18:3[89]\|T18:4[0-2]' | grep -a -E 'New epoch [0-9]+.*size = [0-9]|Rejecting submission request by|currently blacklisted|Failed to send result' > log/11668/canton-confirm.txt
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
grep -a 'New epoch' log/11668/canton-confirm.txt | sed -E "$F" | awk '{match($0,/size = [0-9]+/); k=substr($0,RSTART,RLENGTH); if(!(k in s)){s[k]=1; print substr($0,1,300)}}'
grep -a 'Rejecting submission request by' log/11668/canton-confirm.txt | sed -E "$F" | cut -c1-420
grep -a -c 'Rejecting submission request by' log/11668/canton-confirm.txt; grep -a -c 'currently blacklisted' log/11668/canton-confirm.txt; grep -a -c 'Failed to send result' log/11668/canton-confirm.txt
```
```
2026-11-19T18:38:12.064Z New epoch 249 has started with leaders = List(SEQ::splitwellValidator::..4ecc)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T03:03:00.121001Z,\n  size = 1,\n  weakQuorum = 1,\n  strongQuorum = 1,\n  nodesTopologyInfo = \"SEQ::splitwellV
2026-11-19T18:39:15.657Z New epoch 78 has started with leaders = List(SEQ::sv3::..361d, SEQ::sv4::..8648, SEQ::sv1::..3085, SEQ::sv2::..af36)and blacklisted nodes = List(); ordering topology = OrderingTopology(\n  activationTime = 1970-01-01T03:03:00.252002Z,\n  size = 4,\n  weakQuorum = 2,\n  strongQuorum = 3,\n  n
2026-11-19T18:39:15.813Z Rejecting submission request by Some(PAR::sv3::1220cea2fa83...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-19T18:39:16.075Z Rejecting submission request by Some(PAR::sv2::12201202ec19...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2
0
0
```
(The size-1 line is the single-node splitwell synchronizer; the global synchronizer's first epoch line in the window
is the size-4 one.)

## 5. Blast radius

No topology reset ran after the failed setup (the last `has been reset` lines belong to the teardown of the
previous suite), so the next suite's sv1 init times out, its teardown throws in UpdateHistorySanityCheckPlugin and
the remaining environments fail at `Creating fixture` (teardown leak cascade, family H).

```
zcat $T | grep -a 'has been reset' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-120 | tail -2
sed -E 's/\x1b\[[0-9;]*m//g' log/11668/job.log | grep -a -A2 -E 'contact point gets updated \*\*\* FAILED|lastActiveAt gets updated \*\*\* FAILED' | sed -E 's/^[^Z]*Z //' | grep -v -- '--' | head -6
```
```
2026-11-19T18:37:06.766Z decentralized namespace has been reset","logger_name":"o.l.s.i.p.ResetDecentralizedNamespace:Re
2026-11-19T18:37:06.780Z sequencer domain state threshold has been reset","logger_name":"o.l.s.i.p.ResetSequencerSynchro
[info] - contact point gets updated *** FAILED ***
[info]   java.lang.RuntimeException: The app state of sv1Scan is currently not accessible. Node doesn't have any app state.
[info]   at org.lfdecentralizedtrust.splice.console.AppBackendReference.$anonfun$_appState$8(SpliceInstanceReference.scala:244)
[info] - lastActiveAt gets updated *** FAILED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
[info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
```

## 6. State of the parent and of the mitigations

```
gh issue view 11255 -R DACH-NY/cn-test-failures --json state,stateReason,title
gh pr view 8363 -R canton-network/splice --json state,mergedAt,title,baseRefName
git grep -c disableBftBlacklisting b12a1318f6 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala
```
```
{"state":"OPEN","stateReason":"","title":"Timeouts due to changing bft ordering topology"}
{"baseRefName":"main","mergedAt":null,"state":"OPEN","title":"Onboard SV sequencers one at a time in tests"}
b12a1318f6c74e478db8519a1982aa3309c7bde1:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala:1
```
11255 is open and not closed as a duplicate. splice#8356 (blacklisting off in simtime) is on the release line and
does not cover this shape; splice#8363 is open, so there is nothing to backport yet.

## Verdict

Duplicate of 11255, family B, rejected-send shape under simulated time (same as 11358, 11386, 11391, 11643), with
two joiners stuck (sv2 and sv3) instead of one. Flake; cause in Canton BFT (submissions rejected right after a 1 -> 4
ordering topology step are not retried within the sim-time max sequencing time); test-side mitigation is
splice#8363 (open, main). No fix branch. Suggested action for the user: close 11668 as a duplicate of 11255.

Not verified: which topology transactions the two rejected submissions carried (the earlier hits identified the
MediatorSynchronizerState add; here only the rejection lines and the mediator traffic wait were checked); the cause
of the sv1 init timeout in ValidatorLicenseMetadataTimeBasedIntegrationTest beyond the missing reset.
