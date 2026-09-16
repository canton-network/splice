# 10136 - SvOnboardingIntegrationTest: sv3 init timeout, third BFT sequencer wedges ordering (run 35052864473)

Branch release-line-0.8.0, sha 7cafbf8ef1 "Backport PR #7310 to release-line-0.8.0 (#7314)", post-merge CI
2026-09-16T03:43Z. Canton runtime 3.5.16. One failed job. All commands verified against the downloaded
artifacts.

Ref mapping (inferred): the cn-test-failures issue URL was the only input. The two failed post-merge runs
right before ref 10139 (05:00Z) are the release-line backport runs at 03:43Z, so 10135/10136 map to those
two; this packet takes 10136 = run 35052864473 (release-line-0.8.0). If the issue body names a different
run, re-map before acting.

## Categorization

- Test failed: SvOnboardingIntegrationTest / "fail registration with invalid tokens, succeed with a valid
  token" (the first test of the suite; it never got past `initDso()`).
- Failure type: timeout (`waitForInitialization(sv3)` 5 minutes), test aborted, teardown plugin then
  `sys.exit(1)` so no ScalaTest summary or junit XML was produced.
- Component: canton BFT sequencer onboarding / state transfer (canton 3.5.16), surfaced through the splice
  sv app onboarding flow (`LocalSynchronizerNode`, `HttpSvPublicHandler.onboardSvSequencer`).
- Flake vs real: real, timing-dependent bug in the BFT onboarding path; same family as 10048. Not a test
  bug and not a test-timeout-too-short problem: the network is permanently wedged after 04:05:54.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35052864473 --repo canton-network/splice -n logs-wall-clock-time-8 -D dl
cd dl
# canton.clog.gz = node log (participants/sequencers/mediators); canton_network_test.clog.gz = test harness + splice apps
# job-104657010559.log = `gh api repos/canton-network/splice/actions/jobs/104657010559/logs`
# H trims hashes: used as `sed -E "$H"` below
H='s/[0-9a-f]{16,}/<HASH>/g; s/(SEQ::sv[0-9]|MED::sv[0-9]|PAR::sv[0-9])::[0-9a-f]+(\.\.\.)?/\1/g'
```

## 1. Failed job

```
gh run view 35052864473 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
```
```
104657010559  ci / scala_test_wall_clock_time / wall-clock-time (8)
```
Single failed job; artifact logs-wall-clock-time-8. The shard ran only SvOnboardingIntegrationTest before
dying.

## 2. Failing test, assertion, stack trace

```
grep -aoE 'Test still running after [0-9a-z ,]+: suite name: [A-Za-z]+, test name: [^.]+' job-104657010559.log | tail -1
grep -acE 'FAILED \*\*\*|Failed tests:|Tests: succeeded' job-104657010559.log
grep -aE 'Attempt 1 failed with exit code|cannot stat .*test-reports' job-104657010559.log | sed -E 's/^[0-9T:.Z-]+ //'
```
```
Test still running after 6 minutes, 28 seconds: suite name: SvOnboardingIntegrationTest, test name: fail registration with invalid tokens, succeed with a valid token
0
Attempt 1 failed with exit code 1, retrying
cp: cannot stat 'apps/app/target/test-reports/TEST-*.xml': No such file or directory
```
The job log has zero ScalaTest result lines and no junit XML: the JVM exited (section 11c) before ScalaTest
reported. The assertion and stack trace are only in the harness log:

```
zcat canton_network_test.clog.gz | grep -a '"level":"ERROR"' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,60}|"logger_name":"[^"]{0,95}' | paste - - - | sed -E "$H"
```
```
"@timestamp":"2026-09-16T04:07:49.280Z"	"message":"Gave up waiting until Sequencer is added to the topology sta	"logger_name":"o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SvOnboardingIntegrationTest/config=3f44d34c/S
"@timestamp":"2026-09-16T04:07:50.386Z"	"message":"Gave up waiting until Sequencer is added to the topology sta	"logger_name":"o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SvOnboardingIntegrationTest/config=3f44d34c/S
"@timestamp":"2026-09-16T04:07:52.109Z"	"message":"Gave up waiting until Sequencer is added to the topology sta	"logger_name":"o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SvOnboardingIntegrationTest/config=3f44d34c/S
"@timestamp":"2026-09-16T04:07:55.235Z"	"message":"Gave up waiting until Sequencer is added to the topology sta	"logger_name":"o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SvOnboardingIntegrationTest/config=3f44d34c/S
"@timestamp":"2026-09-16T04:08:13.583Z"	"message":"Timeout while waiting for initialization of sv3	"logger_name":"o.l.s.c.SvAppBackendReference:SvOnboardingIntegrationTest/config=3f44d34c/app=sv3
"@timestamp":"2026-09-16T04:09:44.985Z"	"message":"Failed to reset decentralized namespace	"logger_name":"o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace
```
```
zcat canton_network_test.clog.gz | grep -a 'Timeout while waiting for initialization of sv3' \
  | python3 -c "import sys,json; print(json.loads(sys.stdin.readline())['stack_trace'])" \
  | grep -vE 'scala\.collection|org\.scalatest|RepeatableTest|OutcomeOf' | head -9
```
```
java.lang.IllegalStateException: Condition never became true within 5 minutes
	at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:150)
	at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:194)
	at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:189)
	at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
	at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$initDso$3(SpliceTests.scala:311)
	at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$initDso$3$adapted(SpliceTests.scala:311)
	at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.initDso(SpliceTests.scala:311)
	at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.initDso$(SpliceTests.scala:306)
```
The test failed inside `initDso()` (SvOnboardingIntegrationTest.scala:21 at 7cafbf8ef1) waiting for the
sv3 app's health endpoint to report active. Nothing token-related ever ran.

## 3. Canton runtime version

```
zcat canton.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
git show 7cafbf8ef1:nix/canton-sources.json | grep version; git show 7cafbf8ef1:canton/VERSION
```
```
Canton version 3.5.16
  "version": "3.5.16",
3.5.7-SNAPSHOT
```
release-line-0.8.0 pins and ran canton 3.5.16 (same as 10048). `canton/` in the repo is a stale vendored
copy (3.5.7-SNAPSHOT); no canton source is cited below.

## 4. What the test does and the timeout

```
git show 7cafbf8ef1:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SpliceTests.scala | sed -n '306,312p'
git show 7cafbf8ef1:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/console/SpliceInstanceReference.scala | sed -n '185,186p;189,191p'
```
```
    protected def initDso(
        includeLocal: Boolean = true
    )(implicit env: SpliceTestConsoleEnvironment): Unit = {
      val apps = env.fullDsoApps.local.filter(a => !a.name.endsWith("Local") || includeLocal)
      apps.foreach(_.start())
      apps.foreach(_.waitForInitialization())
    }
    NonNegativeDuration.tryFromDuration(5.minute)
  private val defaultHealthStatusMaxBackoff: NonNegativeDuration =
  override def waitForInitialization(
      timeout: NonNegativeDuration = defaultHealthStatusTimeout,
      maxBackoff: NonNegativeDuration = defaultHealthStatusMaxBackoff,
```
`initDso` starts all 4 SVs (plus scans/validators) of the manual-start `simpleTopology4Svs` and waits 5 min
per app. sv2, sv3, sv4 all onboard concurrently via sponsor sv1.

```
zcat canton_network_test.clog.gz | grep -aE '"message":"sv[1-4] app initialization: (Starting initialization|Initialize app finished|Initialize app failed)' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"sv[1-4] app initialization: [A-Za-z ]+' | paste - - | sort
```
```
"@timestamp":"2026-09-16T04:03:09.419Z"	"message":"sv3 app initialization: Starting initialization
"@timestamp":"2026-09-16T04:03:10.100Z"	"message":"sv2 app initialization: Starting initialization
"@timestamp":"2026-09-16T04:03:10.446Z"	"message":"sv4 app initialization: Starting initialization
"@timestamp":"2026-09-16T04:03:10.803Z"	"message":"sv1 app initialization: Starting initialization
"@timestamp":"2026-09-16T04:04:29.441Z"	"message":"sv1 app initialization: Initialize app finished after PT
"@timestamp":"2026-09-16T04:05:48.796Z"	"message":"sv4 app initialization: Initialize app finished after PT
"@timestamp":"2026-09-16T04:08:24.782Z"	"message":"sv2 app initialization: Initialize app failed
"@timestamp":"2026-09-16T04:08:34.814Z"	"message":"sv3 app initialization: Initialize app failed
```
sv1 (founder) and sv4 initialized; sv3 and sv2 never did (their "failed" lines are the shutdown after the
test gave up).

## 5. sv3 app init timeline: last step reached

```
zcat canton_network_test.clog.gz | grep -a 'SV=sv3' | grep -aE 'JoiningNodeInitializer|LocalSynchronizerNode|JoiningNodeDsoPartyHosting' \
  | grep -aE '"message":"(Got .DSO party ID from sponsoring SV|Packages vetting completed|Requesting to be onboarded|Got .SvOnboardingConfirmed|DSO party is now hosted|Adding sequencer identity transactions|Onboarding sequencer|Onboarded sequencer|Initializing sequencer|Onboarding mediator|Waiting until Mediator has been granted unlimited traffic)' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,75}' | paste - - | sed -E "$H" | awk '!seen[substr($0,32,60)]++'
```
```
"@timestamp":"2026-09-16T04:04:33.108Z"	"message":"Got 'DSO party ID from sponsoring SV': DSO-3f44d34c-3f44d34c::122048c31898.
"@timestamp":"2026-09-16T04:05:21.142Z"	"message":"Packages vetting completed
"@timestamp":"2026-09-16T04:05:21.259Z"	"message":"Requesting to be onboarded via the sponsor SV
"@timestamp":"2026-09-16T04:05:27.283Z"	"message":"Got 'SvOnboardingConfirmed contract for digital-asset-eng-3-3f44d34c::1220b
"@timestamp":"2026-09-16T04:05:31.070Z"	"message":"DSO party is now hosted in the candidate SV participant PAR::sv3
"@timestamp":"2026-09-16T04:05:31.360Z"	"message":"Got 'SvOnboardingConfirmed contract for digital-asset-eng-3-3f44d34c::1220b
"@timestamp":"2026-09-16T04:05:33.514Z"	"message":"Adding sequencer identity transactions for domain global
"@timestamp":"2026-09-16T04:05:36.031Z"	"message":"Onboarding sequencer
"@timestamp":"2026-09-16T04:05:36.557Z"	"message":"Onboarding sequencer SEQ::sv3 through sponsoring SV
"@timestamp":"2026-09-16T04:05:57.582Z"	"message":"Onboarded sequencer SEQ::sv3
"@timestamp":"2026-09-16T04:05:57.582Z"	"message":"Initializing sequencer SEQ::sv3
"@timestamp":"2026-09-16T04:05:59.931Z"	"message":"Onboarding mediator
"@timestamp":"2026-09-16T04:05:59.931Z"	"message":"Waiting until Mediator has been granted unlimited traffic
```
The Daml-side onboarding (SvOnboardingConfirmed, DSO party hosting, ACS import, DsoRules membership) all
completed by 04:05:33. The sequencer onboarding request to sv1 took 21 s (04:05:36.557 -> 04:05:57.582,
section 9). sv3 then initialized its sequencer from the onboarding state, moved on to mediator onboarding
at 04:05:59.931 and never got past "Waiting until Mediator has been granted unlimited traffic".

## 6. What sv3 was waiting on: MED::sv3 traffic state, served by its own stuck sequencer

```
zcat canton_network_test.clog.gz | grep -a 'SV=sv3' | grep -a "Check whether Mediator has been granted unlimited traffic' failed" \
  | grep -aoE '"@timestamp":"[^"]+"' | sed -n '1p;$p'
zcat canton_network_test.clog.gz | grep -a 'SV=sv3' | grep -ac "Check whether Mediator has been granted unlimited traffic' failed"
zcat canton_network_test.clog.gz | grep -a 'SV=sv3' | grep -a "Check whether Mediator has been granted unlimited traffic' failed" \
  | head -1 | grep -aoE 'NOT_FOUND: No traffic state found for member MED::sv3[^ ]*' | sed -E "$H" | cut -c1-60
```
```
"@timestamp":"2026-09-16T04:05:59.936Z"
"@timestamp":"2026-09-16T04:08:32.912Z"
43
NOT_FOUND: No traffic state found for member MED::sv3
```
43 retries over 2.5 minutes until shutdown, always "No traffic state found for member MED::sv3". The
top-up itself was done by sv1 well before, so the state exists on the network:

```
zcat canton_network_test.clog.gz | grep -a 'SV=sv1' | grep -a SvOnboardingUnlimitedTrafficTrigger | grep -a 'Updated traffic limit for MED::sv3' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,90}' | paste - - | sed -E "$H"
```
```
"@timestamp":"2026-09-16T04:05:48.946Z"	"message":"Completed processing with outcome: Updated traffic limit for MED::sv3 to 
```
The query goes to sv3's OWN sequencer (LocalSynchronizerNode uses the local sequencerAdminConnection),
and that sequencer's view of time never moved past 04:05:46.38, i.e. before the 04:05:48.9 top-up:

```
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'to fetch traffic state' | grep -aoE '"message":"[^"]*"' | sort | uniq -c
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'to fetch traffic state' | grep -aoE '"@timestamp":"[^"]+"' | sed -n '1p;$p'
```
```
    678 "message":"Using Some(2026-09-16T04:05:46.384849Z) to fetch traffic state with selector LatestSafe"
"@timestamp":"2026-09-16T04:05:59.947Z"
"@timestamp":"2026-09-16T04:07:54.415Z"
```
678 traffic-state reads over two minutes, every one at the same frozen safe time. sv3's sequencer is
initialized but not progressing (section 8).

## 7. Ordering halted at epoch 21 (size = strongQuorum = 3) at 04:05:54

```
zcat canton.clog.gz | grep -a 'New epoch' | grep -a globalSequencerSv1 \
  | grep -aoE '"@timestamp":"[^"]+"|New epoch [0-9]+ has started|leaders = List\([^)]*\)|size = [0-9]+|strongQuorum = [0-9]+' \
  | sed -E "$H" | paste - - - - - | tail -4
```
```
"@timestamp":"2026-09-16T04:05:31.105Z"	New epoch 18 has started	leaders = List(SEQ::sv1::<HASH>)	size = 1	strongQuorum = 1
"@timestamp":"2026-09-16T04:05:38.264Z"	New epoch 19 has started	leaders = List(SEQ::sv4::<HASH>, SEQ::sv1::<HASH>)	size = 2	strongQuorum = 2
"@timestamp":"2026-09-16T04:05:46.622Z"	New epoch 20 has started	leaders = List(SEQ::sv1::<HASH>, SEQ::sv4::<HASH>)	size = 2	strongQuorum = 2
"@timestamp":"2026-09-16T04:05:54.712Z"	New epoch 21 has started	leaders = List(SEQ::sv1::<HASH>, SEQ::sv3::<HASH>, SEQ::sv4::<HASH>)	size = 3	strongQuorum = 3
```
```
zcat canton.clog.gz | grep -a 'below strong quorum' \
  | grep -aoE '"@timestamp":"[^"]+"|sequencer=globalSequencerSv[0-9]|count \(including this node\) [0-9]+ is currently below strong quorum size [0-9]+' | paste - - -
```
```
"@timestamp":"2026-09-16T04:05:38.251Z"	count (including this node) 1 is currently below strong quorum size 2	sequencer=globalSequencerSv1
"@timestamp":"2026-09-16T04:05:42.435Z"	count (including this node) 1 is currently below strong quorum size 2	sequencer=globalSequencerSv1
"@timestamp":"2026-09-16T04:05:54.710Z"	count (including this node) 2 is currently below strong quorum size 3	sequencer=globalSequencerSv1
"@timestamp":"2026-09-16T04:05:54.710Z"	count (including this node) 2 is currently below strong quorum size 3	sequencer=globalSequencerSv4
"@timestamp":"2026-09-16T04:06:00.062Z"	count (including this node) 1 is currently below strong quorum size 2	sequencer=globalSequencerSv3
"@timestamp":"2026-09-16T04:06:00.065Z"	count (including this node) 1 is currently below strong quorum size 2	sequencer=globalSequencerSv3
"@timestamp":"2026-09-16T04:06:00.268Z"	count (including this node) 2 is currently below strong quorum size 3	sequencer=globalSequencerSv4
"@timestamp":"2026-09-16T04:06:00.304Z"	count (including this node) 2 is currently below strong quorum size 3	sequencer=globalSequencerSv1
```
```
for s in Sv1 Sv4; do zcat canton.clog.gz | grep -a "globalSequencer$s" | grep -a 'OrderedBlockStored: DB stored block' \
  | grep -aoE 'epochNumber=[0-9]+, blockNumber=[0-9]+' | awk -F'blockNumber=' -v s=$s '{if($2+0>m){m=$2+0;l=$0}} END{print s": "l}'; done
zcat canton.clog.gz | grep -a globalSequencerSv1 | grep -a 'OrderedBlockStored: DB stored block' | grep -a 'blockNumber=499' | head -1 | grep -aoE '"@timestamp":"[^"]+"'
zcat canton.clog.gz | grep -ac '"message":"New epoch 22'
```
```
Sv1: epochNumber=20, blockNumber=499
Sv4: epochNumber=20, blockNumber=499
"@timestamp":"2026-09-16T04:05:54.660Z"
0
```
Epoch 21 begins 04:05:54.712 with the freshly added SEQ::sv3 in the ordering topology and strongQuorum =
size = 3 (f = 0), so every block needs all three. sv1 and sv4 immediately see only 2 of 3. The last block
ever ordered is epoch 20 / block 499 at 04:05:54.660; no epoch 22 anywhere. The wedge is permanent.

## 8. SEQ::sv3 was not initialized when epoch 21 started, then never left state transfer

```
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'SequencerStatusService/SequencerStatus' | grep -a WAITING_FOR_EXTERNAL_INPUT | tail -1 | grep -aoE '"@timestamp":"[^"]+"'
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'InitializeSequencerFromOnboardingStateV2' | head -1 | grep -aoE '"@timestamp":"[^"]+"'
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -aE '"message":"(Creating BFT sequencer|Starting Onboarding state transfer|Completed epoch|New epoch)' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,95}' | paste - -
```
```
"@timestamp":"2026-09-16T04:05:59.000Z"
"@timestamp":"2026-09-16T04:05:57.584Z"
"@timestamp":"2026-09-16T04:05:58.209Z"	"message":"Creating BFT sequencer at block height Some(421)
"@timestamp":"2026-09-16T04:05:59.878Z"	"message":"Starting Onboarding state transfer from epoch 19
"@timestamp":"2026-09-16T04:06:04.015Z"	"message":"Completed epoch 19 that could alter sequencing topology: last block mode = StateTransfer; query
```
SEQ::sv3 still answered WAITING_FOR_EXTERNAL_INPUT_INITIALIZATION at 04:05:59.0, 4.3 s after epoch 21 had
already made it a required member. It received its onboarding state at 04:05:57.584, created the BFT
sequencer at block 421 and started state transfer from epoch 19. It completed epoch 19 and then:

```
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -aoE 'block transfer response for block Some..epochNumber=[0-9]+, blockNumber=[0-9]+' | grep -aoE 'epochNumber=[0-9]+' | sort | uniq -c
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -aoE 'block transfer response for block Some..epochNumber=20, blockNumber=[0-9]+' | grep -aoE '[0-9]+$' | sort -n | uniq | sed -n '1p;$p' | paste - -
zcat canton.clog.gz | grep -a globalSequencerSv1 | grep -a 'OrderedBlockStored: DB stored block' | grep -aoE 'epochNumber=20, blockNumber=[0-9]+' | grep -aoE '[0-9]+$' | sort -n | sed -n '1p;$p' | paste - -
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -ac 'current epoch = 20'
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'new head=' | tail -1 | grep -aoE '"@timestamp":"[^"]+"|new head=[0-9]+' | paste - -
```
```
     80 epochNumber=20
420	499
420	499
0
"@timestamp":"2026-09-16T04:06:04.015Z"	new head=421
```
sv3 received block-transfer responses for every one of the 80 blocks of epoch 20 (420..499, exactly the
range sv1 ordered), yet it never emitted a single epoch-20 block ("current epoch = 20" count 0), never
logged "Completed epoch 20", never logged any "New epoch", and its subscription head froze at 421 at
04:06:04.015. The live nodes keep asking it for epoch 21, which nobody has:

```
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'but there are no commit certificates' | grep -aoE 'request from SEQ::sv[0-9]' | sort | uniq -c
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'but there are no commit certificates' | grep -aoE '"@timestamp":"[^"]+"' | sed -n '1p;$p'
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -a 'but there are no commit certificates' | head -1 | grep -aoE '"message":"[^"]{0,110}' | sed -E "$H"
zcat canton.clog.gz | grep -a globalSequencerSv3 | grep -aoE '"@timestamp":"[^"]+"' | tail -1
```
```
     37 request from SEQ::sv1
     37 request from SEQ::sv4
"@timestamp":"2026-09-16T04:06:06.799Z"
"@timestamp":"2026-09-16T04:09:47.255Z"
"message":"Got a retransmission request from SEQ::sv4::<HASH>
"@timestamp":"2026-09-16T04:09:49.656Z"
```
37 retransmission requests from each of sv1 and sv4 for epoch 21, every 3 s from 04:06:06 until the log
ends at 04:09:49, each answered "no commit certificates for it" (sv3 also logs them as "for a non-current
epoch 21": it is still in epoch 20). Also, sv3's "below strong quorum size 2" (section 7) shows its
ordering topology is still the 2-node one of epoch 19/20; it never learned epoch 21's.

## 9. Why sv3 got its onboarding state only after epoch 21 had started

The sponsor hands out the onboarding state only once the new sequencer is in the BFT ordering topology,
which by definition is the first epoch that already requires it:

```
git show 7cafbf8ef1:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/admin/http/HttpSvPublicHandler.scala | sed -n '652,657p;683,691p'
```
```
      _ <-
        if (isBftSequencer) {
          retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
            "sequencer_ordering_topology",
            s"Wait for $sequencerId to be in the ordering topology",
    for {
      _ <- waitForNewSequencerObservedByExistingSequencer(
        isCantonBftSequencer,
        sequencerAdminConnection,
        sequencerId,
      )
      _ = logger.info(s"Downloading sequencer onboarding state for $sequencerId")
      onboardingState <- sequencerAdminConnection.getOnboardingState(Left(sequencerId))
    } yield onboardingState
```
```
zcat canton_network_test.clog.gz | grep -a 'SV=sv1' \
  | grep -aE "Got 'New sequencer is observed|Success: Wait for SEQ::sv[0-9][^ ]* to be in the ordering topology|Downloading sequencer onboarding state" \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,120}' | paste - - | sed -E "$H"
```
```
"@timestamp":"2026-09-16T04:05:35.851Z"	"message":"Got 'New sequencer is observed in SequencerSynchronizerState through existing sequencer': 2026-09-16T04:05:35.850752Z
"@timestamp":"2026-09-16T04:05:38.856Z"	"message":"Success: Wait for SEQ::sv4 to be in the ordering topology
"@timestamp":"2026-09-16T04:05:38.856Z"	"message":"Downloading sequencer onboarding state for SEQ::sv4
"@timestamp":"2026-09-16T04:05:38.988Z"	"message":"Got 'New sequencer is observed in SequencerSynchronizerState through existing sequencer': 2026-09-16T04:05:37.906779Z
"@timestamp":"2026-09-16T04:05:57.417Z"	"message":"Success: Wait for SEQ::sv3 to be in the ordering topology
"@timestamp":"2026-09-16T04:05:57.417Z"	"message":"Downloading sequencer onboarding state for SEQ::sv3
```
SEQ::sv3 was in SequencerSynchronizerState from 04:05:37.906779 (sequenced ~04:05:37.66) but sv1 polled
"in the ordering topology" without success for 18 s and only succeeded at 04:05:57.417, 2.7 s after epoch
21 started. So the new node always begins initializing after the epoch that needs it is already running.

## 10. Why sv3 landed two epochs behind (and sv4, one epoch behind, survived)

The BFT ordering topology for epoch N+1 is read at the BFT time of epoch N's last block. During epoch 19
the network was waiting for sv4 (section 7) and produced 80 empty blocks whose BFT time advanced only 1 ms
per block, so epoch 19's last tick lagged wall clock by ~9 s:

```
for b in 339 419 499; do zcat canton.clog.gz | grep -a globalSequencerSv1 | grep -a "Sending block $b " | head -1 \
  | grep -aoE 'Sending block [0-9]+ \(current epoch = [0-9]+, block.s BFT time = [0-9T:.Z-]+|is last in epoch = [a-z]+' | paste - -; done
zcat canton.clog.gz | grep -a globalSequencerSv1 | grep -aE '"message":"Completed epoch (19|20) that could alter' \
  | grep -aoE '"@timestamp":"[^"]+"|epoch.s last sequencing time [0-9T:.Z-]+' | paste - -
zcat canton.clog.gz | grep -a globalSequencerSv1 | grep -a 'Persisted topology transactions' | grep -a SequencerSynchronizerState \
  | grep -aoE '"@timestamp":"[^"]+"|EffectiveTime\([^)]*\)' | paste - - | tail -4
```
```
Sending block 339 (current epoch = 18, block's BFT time = 2026-09-16T04:05:37.656776Z	is last in epoch = true
Sending block 419 (current epoch = 19, block's BFT time = 2026-09-16T04:05:37.736776Z	is last in epoch = true
Sending block 499 (current epoch = 20, block's BFT time = 2026-09-16T04:05:54.483421Z	is last in epoch = true
"@timestamp":"2026-09-16T04:05:46.584Z"	epoch's last sequencing time 2026-09-16T04:05:37.736776Z
"@timestamp":"2026-09-16T04:05:54.674Z"	epoch's last sequencing time 2026-09-16T04:05:54.483421Z
"@timestamp":"2026-09-16T04:05:35.759Z"	EffectiveTime(2026-09-16T04:05:35.850752Z)
"@timestamp":"2026-09-16T04:05:37.550Z"	EffectiveTime(2026-09-16T04:05:37.565679Z)
"@timestamp":"2026-09-16T04:05:38.080Z"	EffectiveTime(2026-09-16T04:05:37.906778Z)
"@timestamp":"2026-09-16T04:05:38.153Z"	EffectiveTime(2026-09-16T04:05:37.906779Z)
```
- SEQ::sv4 effective 04:05:35.850752 <= epoch 18's tick 04:05:37.656776 -> in epoch 19's topology.
- SEQ::sv3 effective 04:05:37.906779 > epoch 19's tick 04:05:37.736776 (by 170 us, although the tx was
  sequenced at wall clock 04:05:37.66, 9 s before epoch 20 began) -> missed epoch 20, first appears in
  epoch 21 (tick 04:05:54.483421). Its snapshot lands in epoch 19, so it must state-transfer epochs 19 AND
  20 before it can act in 21. These are the last four SequencerSynchronizerState txs ever sequenced; the
  SEQ::sv2 one (section 11) never made it.

Contrast sv4, which had the same "initialized after its epoch began" exposure but only one epoch to
transfer:

```
zcat canton.clog.gz | grep -a globalSequencerSv4 | grep -a 'InitializeSequencerFromOnboardingStateV2' | head -1 | grep -aoE '"@timestamp":"[^"]+"'
zcat canton.clog.gz | grep -a globalSequencerSv4 | grep -aE '"message":"(Creating BFT sequencer|Starting Onboarding state transfer|Completed epoch 1[89]|New epoch (19|20) has started)' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,60}' | paste - - | sed -E "$H"
```
```
"@timestamp":"2026-09-16T04:05:39.239Z"
"@timestamp":"2026-09-16T04:05:39.906Z"	"message":"Creating BFT sequencer at block height Some(329)
"@timestamp":"2026-09-16T04:05:41.663Z"	"message":"Starting Onboarding state transfer from epoch 18
"@timestamp":"2026-09-16T04:05:45.941Z"	"message":"Completed epoch 18 that could alter sequencing topology: las
"@timestamp":"2026-09-16T04:05:46.253Z"	"message":"New epoch 19 has started with leaders = List(SEQ::sv4
"@timestamp":"2026-09-16T04:05:46.532Z"	"message":"Completed epoch 19, but no new epoch topology is available y
"@timestamp":"2026-09-16T04:05:46.573Z"	"message":"Completed epoch 19 that could alter sequencing topology: las
"@timestamp":"2026-09-16T04:05:46.622Z"	"message":"New epoch 20 has started with leaders = List(SEQ::sv1
```
sv4: epoch 19 (needing sv4, quorum 2 of 2) opened on sv1 at 04:05:38.264; sv4 initialized at 04:05:39.239,
state-transferred epoch 18 only, entered epoch 19 live at 04:05:46.253 and the network resumed (epoch 19
closed 0.3 s later). sv3: same shape, but with epoch 20 in between it had to state-transfer an epoch that
ended exactly where the live nodes are stuck, and that transfer never completed (section 8).

## 11. Downstream (consequences, not independent bugs)

### 11a. sv2's sequencer can never be added; sv1's onboard endpoint times out, then 404s

```
zcat canton_network_test.clog.gz | grep -a 'SV=sv1' | grep -a SvOnboardingSequencerTrigger \
  | grep -aE '"message":"(Adding sequencer SequencerToOnboard|Giving up on retrying the operation .processTaskWithRetry)' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,190}' | paste - - | sed -E "$H" | cut -c1-200
zcat canton_network_test.clog.gz | grep -aE '"level":"(WARN|ERROR)"' | grep -aE 'onboard/sv/sequencer|Gave up waiting' \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,100}|SV=sv[0-9]' | paste - - - | sed -E "$H" | cut -c1-210
zcat canton_network_test.clog.gz | grep -a 'SV=sv1' | grep -a 'onboard/sv/sequencer resulted in a gRPC StatusRuntimeException' | head -1 \
  | grep -aoE '"@timestamp":"[^"]+"|"message":"[^"]{0,140}' | paste - - | sed -E "$H"
```
```
"@timestamp":"2026-09-16T04:05:35.496Z"	"message":"Adding sequencer SequencerToOnboard(synchronizerId = global-domain::122048c31898..., sequencerId = SEQ::sv4)
"@timestamp":"2026-09-16T04:05:37.173Z"	"message":"Adding sequencer SequencerToOnboard(synchronizerId = global-domain::122048c31898..., sequencerId = SEQ::sv3)
"@timestamp":"2026-09-16T04:05:54.926Z"	"message":"Adding sequencer SequencerToOnboard(synchronizerId = global-domain::122048c31898..., sequencerId = SEQ::sv2)
"@timestamp":"2026-09-16T04:08:24.775Z"	"message":"Giving up on retrying the operation 'processTaskWithRetry' due to shutdown. Last attempt was None with exception: FAILED_PRECONDITION: Condition is not yet observed. Proposed: SequencerSynch
"@timestamp":"2026-09-16T04:06:32.776Z"	"message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 3	SV=sv1
"@timestamp":"2026-09-16T04:07:11.005Z"	"message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 3	SV=sv1
"@timestamp":"2026-09-16T04:07:49.256Z"	"message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 3	SV=sv1
"@timestamp":"2026-09-16T04:07:49.280Z"	"message":"Gave up waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId 	SV=sv3
"@timestamp":"2026-09-16T04:07:50.386Z"	"message":"Gave up waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId 	SV=sv4
"@timestamp":"2026-09-16T04:07:52.109Z"	"message":"Gave up waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId 	SV=sv1
"@timestamp":"2026-09-16T04:07:55.235Z"	"message":"Gave up waiting until Sequencer is added to the topology state for MediatorToOnboard(synchronizerId 	SV=sv2
"@timestamp":"2026-09-16T04:08:24.776Z"	"message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer resulted in a gRPC StatusRuntimeException: NOT_FOUND: Sequencer SEQ::sv2
```
sv1 proposed the SequencerSynchronizerState adding SEQ::sv2 at 04:05:54.926, 0.2 s after ordering halted;
it is "not yet observed" until shutdown. sv2's `POST /api/sv/v0/onboard/sv/sequencer` to sv1 (port 5114 =
sv1 http-api) therefore times out 3x at 38 s (the only WARNs in the harness log) and finally gets NOT_FOUND
"SEQ::sv2 is not in active sequencers". All four SVs' SvOnboardingMediatorProposalTrigger give up waiting
for SEQ::sv2 for MED::sv2 (the 4 ERRORs at 04:07:49-55). Same downstream signature as 10048 section 10.

### 11b. Teardown: decentralized-namespace reset fails, plugin calls sys.exit(1)

```
zcat canton_network_test.clog.gz | grep -a 'Failed to reset decentralized namespace' | grep -aoE '"@timestamp":"[^"]+"|Condition never became true within [0-9a-z ]+' | paste - -
git show 7cafbf8ef1:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala | sed -n '105,107p'
```
```
"@timestamp":"2026-09-16T04:09:44.985Z"	Condition never became true within 1 minute
        case e: Throwable =>
          logger.error(s"Failed to reset $topologyType", e)
          sys.exit(1)
```
The reset needs a topology tx sequenced on the wedged synchronizer, so it cannot succeed; the plugin then
exits the JVM at 04:09:46 (job log "Attempt 1 failed with exit code 1") before ScalaTest prints any result,
which is why section 2 finds no "FAILED ***" and no junit XML. This is the wall-clock-shard
failure-hiding behaviour already noted for 10048.

## Root cause / hypothesis

Proven from the logs (canton 3.5.16):
1. Epoch 21 started 04:05:54.712 with SEQ::sv3 in the ordering topology, size = strongQuorum = 3, while
   SEQ::sv3 was still WAITING_FOR_EXTERNAL_INPUT (until 04:05:59.0). Ordering stopped at block 499
   (04:05:54.660) and never resumed (0 "New epoch 22"; 2-of-3 quorum warnings on sv1/sv4).
2. sv1 releases the onboarding state only after the new sequencer is in the ordering topology (splice
   `HttpSvPublicHandler.waitForNewSequencerObservedByExistingSequencer`, "sequencer_ordering_topology"),
   which is by construction after the first epoch that requires the new node has begun (sv3: 04:05:57.417
   vs epoch 21 at 04:05:54.712; sv4: 04:05:38.856 vs epoch 19 at 04:05:38.264).
3. SEQ::sv3 completed state transfer of epoch 19, received all 80 blocks of epoch 20, but never completed
   epoch 20, never emitted an epoch-20 block, never entered epoch 21 (head frozen at 421 from 04:06:04).
4. SEQ::sv3 landed two epochs behind because epoch 19's topology tick (BFT time 04:05:37.736776, lagging
   wall clock by 9 s due to 80 empty 1-ms blocks while waiting for sv4) was 170 us before sv3's
   SequencerSynchronizerState effective time (04:05:37.906779), pushing its membership from epoch 20 to 21.
5. sv3's SV app was stuck at "Waiting until Mediator has been granted unlimited traffic" because that
   query is answered by its own frozen sequencer (safe time 04:05:46.38, before the 04:05:48.9 top-up).
   Everything else (sv2 onboarding 38 s timeouts, MED::sv2 "Gave up", namespace reset, sys.exit) follows.

Inferred (needs Canton BFT internals at 3.5.16 to confirm):
- A newly onboarded BFT node cannot finish state transfer of the epoch immediately preceding the epoch
  in which it is itself required for quorum, presumably because completing that epoch needs something
  (commit certificates / first block / topology tick) from the next epoch, which cannot be produced
  without the new node. sv4 survived because it had only its snapshot epoch to transfer and joined the
  next one live; sv3 (here) and sv4 in 10048 each had one extra completed epoch between snapshot and
  membership and wedged. If that is right, the trigger is "new member's topology activation misses the
  next epoch boundary by less than one epoch of wall-clock time", i.e. plain scheduling luck.
- The 1-ms-per-empty-block BFT time behaviour widens the race: any epoch that stalls waiting for a peer
  makes the topology tick lag wall clock, so a tx sequenced seconds before the next epoch still misses it.

## Duplicates / related

- 10048 (ci-triage/10048-bft-deadlock.md, ValidatorIntegrationTest, canton 3.5.16): SAME family and same
  mechanism. Identical signatures: new epoch with size = strongQuorum = 3 including a sequencer still
  WAITING_FOR_EXTERNAL_INPUT; "Authenticated P2P nodes count 2 is currently below strong quorum size 3";
  last live block in epoch N-1; new node "Creating BFT sequencer at block height", "Starting Onboarding
  state transfer from epoch N-2", "Completed epoch N-2", 80 block-transfer responses for N-1, 0 "New
  epoch"; retransmission requests for epoch N answered "no commit certificates for it"; downstream "Gave
  up waiting until Sequencer is added to the topology state for MediatorToOnboard(... MED::sv2 ...)" and
  "No traffic state found for member MED::svX". Differences: the wedged node is not the first leader here
  (leaders [sv1, sv3, sv4]), so "named leader before init" from 10048 is incidental; the essential
  condition is a 2->3 membership step (f = 0, quorum = all) with the new node two epochs behind. This
  packet adds the topology-tick/BFT-time explanation for why the node fell two epochs behind and the
  splice-side ordering (onboarding state released only after ordering-topology membership) that
  guarantees late initialization.
- The still-open "BFT onboarding/off-boarding" family on SV onboarding tests: this is a confirmed member
  (SvOnboardingIntegrationTest, sv3 onboarding), now also on release-line-0.8.0 with canton 3.5.16.
- 10094 (sequencer ack stall): unrelated mechanism.
- ResetDecentralizedNamespace/ResetTopologyStatePlugin sys.exit hiding ScalaTest output: known, noted in
  the 10048 triage; makes these failures look like infrastructure exits in the GHA UI.

## Suggested next step / owner

1. Canton BFT team: attach this packet to the existing BFT onboarding issue (with 10048). Concrete
   question to answer against 3.5.16 source: why does a node in onboarding state transfer not complete
   epoch N-1 after receiving all its blocks when the live nodes are already (stuck) in epoch N, and can
   the ordering topology for epoch N exclude members whose sequencer has not authenticated / finished
   state transfer (or delay the membership change by one epoch)? Also review the 1-ms BFT time advance on
   empty blocks, which makes the epoch topology tick lag wall clock and widens this race.
2. Splice (sv app), as mitigation independent of Canton: consider releasing the onboarding state as soon
   as the new sequencer is in SequencerSynchronizerState (the "sequencer_added_to_topology_state" wait)
   rather than after it is in the ordering topology, so the new node can initialize and state-transfer
   before the epoch that needs it starts; and/or serialize SV sequencer onboardings (sv2/sv3/sv4 were
   onboarding concurrently here) so there is at most one uninitialized member per membership step.
   Owner: whoever holds the BFT onboarding family issue; loop in the sv-app owner for the mitigation.
3. Test infra: the ResetTopologyStatePlugin sys.exit(1) swallows the ScalaTest summary and junit XML on
   every wedge of this kind; make it fail the suite instead of the JVM so the failure is attributed in
   the GHA UI.

## Summary for Canton team

SvOnboardingIntegrationTest, wall-clock-time(8), job 104657010559, release-line-0.8.0, canton 3.5.16.
BFT epoch 21 began 2026-09-16T04:05:54.712 with ordering topology [SEQ::sv1, SEQ::sv3, SEQ::sv4],
strongQuorum = size = 3, while SEQ::sv3 was still NotInitialized (it received its onboarding state only at
04:05:57.584, because the sponsor waits for ordering-topology membership before releasing it). SEQ::sv3
created its BFT sequencer at block 421, state-transferred epoch 19, received all 80 blocks (420..499) of
epoch 20, but never completed epoch 20 nor entered epoch 21; sv1/sv4 sent it 37 retransmission requests
each for epoch 21, all answered "no commit certificates". Last block ordered: epoch 20 / 499 at
04:05:54.660; ordering never resumed. sv3 fell two epochs behind because its SequencerSynchronizerState
became effective at 04:05:37.906779, 170 us after epoch 19's topology tick (04:05:37.736776, itself 9 s
behind wall clock from 80 empty 1-ms blocks while waiting for sv4). sv4, onboarded seconds earlier with
one epoch to transfer, joined its epoch live and survived. Downstream: sv3 app stuck on MED::sv3 traffic
state (served by its frozen sequencer), sv2's SequencerSynchronizerState never sequenced (38 s onboard
timeouts, MED::sv2 "Gave up waiting"), test init timeout after 5 min, teardown reset failure, sys.exit(1).
Same mechanism as 10048.
