# 11347 - SvReonboardingIntegrationTest: sv1 SvApp init exceeds the 5 min startAllSync wait, teardown leak cascades into 5 more tests (run 42923829975)

new (related to 11299 family AM for the slow init, family H teardown leak cascade for the follow-on failures).
Post-merge CI on main, sha 2ef0423979 (#8356), job `key-download (0)`. Six tests failed, but only the first is a
real failure: in SvReonboardingIntegrationTest the sv1 SvApp was still bootstrapping (87 DAR uploads took 190 s, 22
of them repeats) when `startAllSync`'s 5 min `waitForInitialization` gave up at 00:32:04.6. The teardown then threw in
`UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` (sv2Scan running but its app state not yet available,
because sv2 was waiting for sv1), so `environment.close()` never ran and the leaked environment kept its ports: the
next five tests failed at `Creating fixture` with `Could not create Prometheus HTTP server` / `Address already in
use`. First hit of this shape: key-download (0) passed in the 22 other main runs since 2026-11-09 14:57.

- Run: https://github.com/canton-network/splice/actions/runs/42923829975, main 2ef0423979 ("Disable BFT sequencer
  blacklisting in simtime integration tests (#8356)"), job 130205489387 `ci / scala_test_key_download / key-download (0)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (startAllSync wait vs SvApp init time; teardown plugin), splice app (repeated DAR uploads, AM).

## 1. Failing tests

```
sed -E 's/\x1b\[[0-9;]*m//g' log/inbox-1111/130205489387.log > log/inbox-1111/130205489387.clean
grep -anE '\*\*\* FAILED|ABORTED' log/inbox-1111/130205489387.clean | cut -c1-200
```
```
13605:2026-11-11T00:32:17.0061098Z [info] - reonboard SV with new party id and recover amulet via new regular validator *** FAILED ***
13701:2026-11-11T00:34:03.5847443Z [info] - re-onboard validator *** FAILED ***
13917:2026-11-11T00:34:32.6037853Z [info] - re-onboard validator with partiesToMigrate *** FAILED ***
14133:2026-11-11T00:34:38.6369662Z [info] - should produce a participant identities dump via a download from the ValidatorApp admin api *** FAILED ***
14351:2026-11-11T00:34:38.6553029Z [info] - should produce a participant identities dump via a download from the ValidatorApp admin api *** FAILED ***
14569:2026-11-11T00:34:39.6443984Z [info] org.lfdecentralizedtrust.splice.integration.tests.SvIdentitiesDumpIntegrationTest *** ABORTED ***
14713:2026-11-11T00:34:41.6574844Z [info] - We can import and export Canton participant identities dumps with plaintext keys in them *** FAILED ***
```

First failure (reported exception is from teardown, line 13605 ff.):

```
sed -n '13605,13607p;13612,13618p' log/inbox-1111/130205489387.clean | cut -c29-260
```
```
 [info] - reonboard SV with new party id and recover amulet via new regular validator *** FAILED ***
 [info]   java.lang.RuntimeException: The app state of sv2Scan is currently not accessible. Node doesn't have any app state.
 [info]   at org.lfdecentralizedtrust.splice.console.AppBackendReference.$anonfun$_appState$8(SpliceInstanceReference.scala:244)
 [info]   at org.lfdecentralizedtrust.splice.console.ScanAppBackendReference.automation(ScanAppReference.scala:970)
 [info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.$anonfun$beforeEnvironmentDestroyed$4(UpdateHistorySanityCheckPlugin.scala:58)
 ...
 [info]   at com.digitalasset.canton.integration.EnvironmentSetup.manualDestroyEnvironment(EnvironmentSetup.scala:296)
 [info]   at org.lfdecentralizedtrust.splice.integration.tests.SvReonboardingIntegrationTest.manualDestroyEnvironment(SvReonboardingIntegrationTest.scala:54)
 [info]   at com.digitalasset.canton.integration.BaseIsolatedEnvironments.testFinished(EnvironmentSetup.scala:383)
```

All later failures (13701, 13917, 14133, 14713 and the rest):

```
sed -n '13701,13703p' log/inbox-1111/130205489387.clean | cut -c29-260
grep -anE 'Address already in use' log/inbox-1111/130205489387.clean | cut -c1-120
```
```
 [info] - re-onboard validator *** FAILED ***
 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Creating fixture (EnvironmentSetup.scala:146)
 [info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
13805:2026-11-11T00:34:03.6341462Z [info]   Cause: java.net.BindException: Address already in use
14021:2026-11-11T00:34:32.6128469Z [info]   Cause: java.net.BindException: Address already in use
14238:2026-11-11T00:34:38.6466925Z [info]   Cause: java.net.BindException: Address already in use
14456:2026-11-11T00:34:38.6634336Z [info]   Cause: java.net.BindException: Address already in use
14637:2026-11-11T00:34:39.6496763Z [info]   Cause: java.net.BindException: Address already in use
14817:2026-11-11T00:34:41.6661924Z [info]   Cause: java.net.BindException: Address already in use
```

## 2. The real failure: sv1 init timeout inside the test body

Artifact `logs-key-download-0` (id 13817685388) to `log/11347/logs-key-download-0/`.

```
zcat canton_network_test.clog.gz | grep -a 'SvReonboardingIntegrationTest' | grep -aE '(Running|Finished|Failed) clue|Starting test|Test failed|Timeout while waiting' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,140}).*/\1 \2/'
```
```
2026-11-11T00:26:47.584Z Starting test suite 'SvReonboardingIntegrationTest'...
2026-11-11T00:26:54.658Z Running clue: Starting external Canton process sv123-reonboarding with List(canton.features.enable-repair-commands=yes)
2026-11-11T00:26:54.683Z Finished clue: Starting external Canton process sv123-reonboarding with List(canton.features.enable-repair-commands=yes)
2026-11-11T00:26:54.684Z Running clue: Using external Canton process sv123-reonboarding
2026-11-11T00:26:54.685Z Running clue: Starting external Canton process sv4-reonboarding with List(canton.parameters.enable-key-download=yes, canton.features.enable-
2026-11-11T00:26:54.706Z Finished clue: Starting external Canton process sv4-reonboarding with List(canton.parameters.enable-key-download=yes, canton.features.enable
2026-11-11T00:26:54.707Z Running clue: Using external Canton process sv4-reonboarding
2026-11-11T00:32:04.583Z Timeout while waiting for initialization of sv1
2026-11-11T00:32:04.584Z Failed clue: Using external Canton process sv4-reonboarding
2026-11-11T00:32:04.584Z Running clue: Destroying external Canton process sv4-reonboarding
2026-11-11T00:32:07.599Z Finished clue: Destroying external Canton process sv4-reonboarding
2026-11-11T00:32:07.599Z Failed clue: Using external Canton process sv123-reonboarding
2026-11-11T00:32:07.599Z Running clue: Destroying external Canton process sv123-reonboarding
2026-11-11T00:32:16.324Z Finished clue: Destroying external Canton process sv123-reonboarding
2026-11-11T00:32:16.517Z Test failed: 'SvReonboardingIntegrationTest/reonboard SV with new party id and recover amulet via new regular validator', message: The app s
```

The wait is `startAllSync` (test line 241) -> `HttpAppReference.waitForInitialization` with a 5 min default:

```
S=2ef042397984ed873ab5e815fd747a87d0c62258
git show $S:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvReonboardingIntegrationTest.scala | sed -n '234,243p'
git show $S:apps/app/src/main/scala/org/lfdecentralizedtrust/splice/console/SpliceInstanceReference.scala | sed -n '190,210p'
```
```
      ) = withCantonSvNodes(
        (None, None, None, Some(sv4Backend)),
        logSuffix = "sv4-reonboarding",
        svs123 = false,
        // required for dumping sv4's participant identities
        enableKeyDownload = true,
      )() {
        startAllSync(
          (sv1Nodes ++ sv2Nodes ++ sv3Nodes ++ sv4Nodes)*
        )
    NonNegativeDuration.tryFromDuration(5.minute)
  private val defaultHealthStatusMaxBackoff: NonNegativeDuration =
    NonNegativeDuration.tryFromDuration(5.seconds)

  override def waitForInitialization(
      timeout: NonNegativeDuration = defaultHealthStatusTimeout,
      maxBackoff: NonNegativeDuration = defaultHealthStatusMaxBackoff,
  ): Unit =
    try {
      ConsoleMacros.utils.retry_until_true(
        timeout,
        maxBackoff,
      )(
        httpHealth.successOption.exists(_.active)
      )
    } catch {
      case NonFatal(e) =>
        noTracingLogger.error(s"Timeout while waiting for initialization of ${name}", e)
        throw e
    }
```

## 3. Where sv1's init time went

sv1 SvApp milestones:

```
zcat canton_network_test.clog.gz | grep -a 'SvReonboardingIntegrationTest/config=5351b299/SV=sv1"' | grep -aE 'app initialization: Starting initialization|Node has no identity, generating a new one|Ensure participant is initialized with expected id finished|package signatures from resources|SV1Initializer bootstrapping Dso started|Bootstrapping the domain as sv1|Participant connected to domain|Ensuring that SV1 has uploaded|Success: SV1 has uploaded|Ensuring that the DsoRules and AmuletRules' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/'
zcat canton_network_test.clog.gz | grep -a 'config=5351b299/SV=sv1"' | grep -a 'Sending upload dar for' | sed -n '1p;$p' | cut -c1-90
```
```
2026-11-11T00:26:59.860Z sv1 app initialization: Starting initialization
2026-11-11T00:27:24.224Z Node has no identity, generating a new one
2026-11-11T00:27:25.771Z sv1 app initialization: Ensure participant is initialized with expected id finished after PT25.322656328S
2026-11-11T00:27:25.803Z Loading package signatures from resources
2026-11-11T00:27:58.114Z Finished loading package signatures from resources
2026-11-11T00:27:58.182Z sv1 app initialization: SV1Initializer bootstrapping Dso started
2026-11-11T00:28:05.939Z Node has no identity, generating a new one
2026-11-11T00:28:14.096Z Node has no identity, generating a new one
2026-11-11T00:28:14.207Z Bootstrapping the domain as sv1
2026-11-11T00:28:22.843Z Participant connected to domain
2026-11-11T00:28:22.912Z Ensuring that SV1 has uploaded the initial set of packages
2026-11-11T00:31:32.756Z Success: SV1 has uploaded the initial set of packages, result is ()
2026-11-11T00:31:38.736Z Ensuring that the DsoRules and AmuletRules are bootstrapped
{"@timestamp":"2026-11-11T00:28:23.026Z","message":"Sending upload dar for splice-wallet-0
{"@timestamp":"2026-11-11T00:31:22.909Z","message":"Sending upload dar for splice-api-rewa
```

So: 25 s for the external participant to come up, 32 s loading package signatures, 25 s domain bootstrap, 190 s of
DAR uploads (00:28:22.9 to 00:31:32.8), and the DsoRules/AmuletRules bootstrap was still running when the wait ended
at 00:32:04.6.

DAR uploads: 87 sends, 65 distinct, 22 repeats (the family AM pattern of 11299):

```
zcat canton_network_test.clog.gz | grep -a 'config=5351b299/SV=sv1"' | grep -a 'Sending upload dar for' | sed -E 's/.*Sending upload dar for ([^"]+)".*/\1/' | sort | uniq -c | awk '{n++; t+=$1; if($1>1) r+=$1-1} END{print "uploads",t,"distinct",n,"repeats",r}'
```
```
uploads 87 distinct 65 repeats 22
```

Upload duration on the participant (sv1StandaloneParticipant in the external canton, `Processing package upload of`
to `Managed to upload one or more archives` per submission id; includes the System package upload at 00:27:25):

```
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneParticipant' | grep -aE 'Processing package upload of|Managed to upload one or more archives' | sed -E 's/.*"@timestamp":"([^"]+)","message":"(Processing|Managed)[^"]*submissionId ([0-9a-f-]+).*/\1 \2 \3/' | awk '{split($1,t,"T"); split(t[2],h,":"); s=h[1]*3600+h[2]*60+h[3]; if($2=="Processing") st[$3]=s; else if($3 in st){d=s-st[$3]; n++; sum+=d; if(d>mx)mx=d; if(d<mn||mn=="")mn=d}} END{printf "uploads %d mean %.1fs min %.1fs max %.1fs\n", n, sum/n, mn, mx}'
```
```
uploads 88 mean 7.5s min 0.5s max 17.8s
```

Only sv1 uploaded in that window (sv2/sv3 had one upload each, the System package), so the uploads did not compete
with other SVs on the same canton:

```
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'Processing package upload of' | sed -E 's/.*"@timestamp":"([^"]{16}).*participant=([A-Za-z0-9]+).*/\1 \2/' | awk '{print substr($1,1,15)"x", $2}' | sort | uniq -c
```
```
     36 2026-11-11T00:2x sv1StandaloneParticipant
      1 2026-11-11T00:2x sv2StandaloneParticipant
      1 2026-11-11T00:2x sv3StandaloneParticipant
     52 2026-11-11T00:3x sv1StandaloneParticipant
```

(11299 measured a 3.5 s mean per upload on a loaded simtime shard; here it is 7.5 s. Why this runner was slower was
not investigated: passing runs of this shard upload no log artifact, so no baseline for sv1's init time in this test
exists.)

## 4. Teardown leak: sv2Scan running but not initialized

The plugin filters scans by `is_initialized` and then calls `scan.automation`:

```
git show $S:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/UpdateHistorySanityCheckPlugin.scala | grep -n 'automation.trigger\|is_initialized'
git grep -n 'def is_initialized' $S -- canton/community/app-base | cut -c42-300
```
```
51:      val initializedScans = environment.scans.local.filter(scan => scan.is_initialized)
58:            val trigger = scan.automation.trigger[AcsSnapshotTrigger]
canton/community/app-base/src/main/scala/com/digitalasset/canton/console/InstanceReference.scala:199:  def is_initialized: Boolean = nodes.getRunning(name).exists(_.isInitialized)
```

`is_initialized` is true once the node bootstrap is running; `_appState` additionally needs the Splice app state,
which exists only after app init (`state <- spliceNode.getState.toRight("Node doesn't have any app state.")`,
SpliceInstanceReference.scala:240 at the run sha). sv2Scan was still in `Get primary party` (waiting for sv2, which waits for sv1):

```
zcat canton_network_test.clog.gz | grep -a 'config=5351b299/scan=sv2Scan"' | grep -aE 'app initialization: [A-Z]' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,120}).*/\1 sv2Scan \2/' | awk '!seen[substr($0,26)]++'
zcat canton_network_test.clog.gz | grep -a 'Checking update histories'
```
```
2026-11-11T00:27:02.076Z sv2Scan sv2Scan app initialization: Starting initialization
2026-11-11T00:27:02.077Z sv2Scan sv2Scan app initialization: Initialize app started
2026-11-11T00:27:02.080Z sv2Scan sv2Scan app initialization: Wait for user started
2026-11-11T00:27:32.481Z sv2Scan sv2Scan app initialization: Wait for user finished after PT30.400768485S
2026-11-11T00:27:32.481Z sv2Scan sv2Scan app initialization: Initialize node started
2026-11-11T00:27:32.481Z sv2Scan sv2Scan app initialization: Acquire ledger connection started
2026-11-11T00:27:32.482Z sv2Scan sv2Scan app initialization: Acquire ledger connection finished
2026-11-11T00:27:32.482Z sv2Scan sv2Scan app initialization: Get primary party started
{"@timestamp":"2026-11-11T00:32:16.500Z","message":"Checking update histories for List(sv2Scan, sv3Scan, sv1Scan, sv4Scan)",...
```

The vendored `manualDestroyEnvironment` runs the plugins' `beforeEnvironmentDestroyed` outside its `try`, so the
exception skips `environment.close()`:

```
git show $S:canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala | sed -n '294,303p'
```
```
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
```

Confirming grep for family H (the old environment keeps logging after the failure): 10933 lines of `config=5351b299`
after 00:32:17, last one at 00:34:42.7:

```
zcat canton_network_test.clog.gz | grep -aE 'config=5351b299' | awk -F'"@timestamp":"' '{print substr($2,1,19)}' | awk '$1>"2026-11-11T00:32:17"' | wc -l
zcat canton_network_test.clog.gz | grep -a 'config=5351b299' | tail -1 | cut -c1-200
```
```
10933
{"@timestamp":"2026-11-11T00:34:42.702Z","message":"sv3Scan app initialization: Initialization failed","logger_name":"o.l.s.s.ScanApp:SvReonboardingIntegrationTest/config=5351b299/scan=sv3Scan","threa
```

## 5. Frequency and duplicate check

key-download (0) on post-merge main since 2026-11-09 14:53 (23 runs): only this run failed.

```
for r in $(awk '{print $1}' log/11347/runs.txt); do gh api "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=100" --paginate --jq '.jobs[]|select(.name|endswith("/ key-download (0)"))|"\(.id) a=\(.run_attempt) \(.conclusion) \(.started_at)"' | sed "s/^/$r /"; done
```
```
42975175376 130373188906 a=1 success 2026-11-11T06:52:25Z
42948633392 130287685643 a=1 success 2026-11-11T02:50:42Z
42948633392 130343180813 a=2 success 2026-11-11T02:50:42Z
42923829975 130205489387 a=1 failure 2026-11-11T00:13:31Z
42914678423 130180436494 a=1 success 2026-11-11T00:09:25Z
42848234115 129943380446 a=1 success 2026-11-10T15:52:04Z
42823122392 129853791635 a=1 success 2026-11-10T13:29:56Z
42810067070 129809318572 a=1 success 2026-11-10T11:46:35Z
42804747879 129791301899 a=1 success 2026-11-10T11:07:47Z
42799831067 129774597893 a=1 success 2026-11-10T10:32:21Z
42794742288 129757329519 a=1 success 2026-11-10T10:09:00Z
42791495886 129746248189 a=1 success 2026-11-10T09:44:29Z
42790612651 129743939669 a=1 success 2026-11-10T09:43:22Z
42790576154 129743133460 a=1 success 2026-11-10T09:28:26Z
42790498517 129742780242 a=1 success 2026-11-10T09:27:25Z
42785850771 129727041348 a=1 success 2026-11-10T08:51:59Z
42782393978 129715626082 a=1 success 2026-11-10T08:25:34Z
42780003623 129707842428 a=1 success 2026-11-10T08:08:09Z
42766394442 129664095586 a=1 success 2026-11-10T06:08:39Z
42687934161 129400208568 a=1 success 2026-11-09T19:58:22Z
42658360509 129303379062 a=1 success 2026-11-09T16:49:02Z
42646231899 129260567799 a=1 success 2026-11-09T15:36:51Z
42639148146 129235693559 a=1 success 2026-11-09T14:57:03Z
```

Earlier key-download issues 11197, 11214 (closed dups) and 11217: the 2026-11-05 `_V2` credential breakage (Auth0,
GCP, KMS 401/invalid_grant), not this. Searches:

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Could not create Prometheus HTTP server"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "SvReonboardingIntegrationTest"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Timeout while waiting for initialization of sv1"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Node doesn'"'"'t have any app state"'
```
```
## "Could not create Prometheus HTTP server"
8982 closed 2026-06-29 Creating environment failed at step Creating fixture: ...   -> same cascade, closed not_planned; nicu-da: spin off why the server is not shut down
10176 closed 2026-09-18 SvInitializationIntegrationTest teardown: pausing sv2Scan's AcsSnapshotTrigger times out ... -> same cascade, other plugin failure (H3, fixed)
9124 closed 2026-07-08 SvOnboardingViaNonFoundingSvIntegrationTest - Task Starting node sv1Scan did not complete within 2 minutes. -> cascade, other first failure
7150 closed 2026-02-01 EnvironmentSetupException: Could not create Prometheus HTTP server -> cascade after WalletTimeBasedIntegrationTest abort
6275 closed 2025-10-30 Cannot rate prometheus server, possibly because UpdateHistorySanityCheckPlugin times out on something -> cascade from the same plugin
5960, 5967 closed 2025-10 EventHistorySanityCheckPlugin ... -> other plugin, not this
## "SvReonboardingIntegrationTest"
10487, 9745, 10144, 8829, 7924, 7808, 7898, 7935 closed -> checkErrors lines, topology, sequencer list assertions; none is an sv1 init timeout
## "Timeout while waiting for initialization of sv1"
10235 closed 2026-09-29 sv-1 reconnect to global synchronizer going silent -> different (reconnect hang)
6726 closed 2026-01-07 GHA Run ... wall-clock-time (2) -> old, not checked further
## "Node doesn't have any app state"
4994 closed 2025-07-14 sv4Scan is currently not accessible -> same exception, other test (SvOnboardingAddl), cause not found then
```

The cascade (family H) has recurred since 2025 (6275, 7150, 8982, 10176) without a fix to the leak itself; 11299 (AM,
Triaged, unassigned, no PR) proposes the DAR dedup.

```
gh api repos/DACH-NY/cn-test-failures/issues/11299 --jq '"\(.state) \(.title) assignees=\([.assignees[].login]|join(","))"'
```
```
open SvTimeBasedOnboardingIntegrationTest: sv4 init outlasts the 60 s wait, a quarter of its DAR uploads are repeats assignees=
```

## Verdict

New first hit, test flake on a slow runner, not a duplicate: the failing wait (startAllSync, 5 min, sv1) differs from
11299 (60 s eventually, sv4), but the dominant cost is the same repeated-DAR upload path (22 of 87 uploads repeated).
The five follow-on failures are the family H teardown leak cascade and carry no signal of their own.

Fixes (none on main):
1. Teardown robustness (stops the cascade, independent of the cause of the first failure):
   `UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` should only check scans whose app state is available
   (e.g. filter on the scan's HTTP health being active, not only `is_initialized`), so a test that failed during
   init still tears its environment down. A more general fix is in the vendored canton
   `EnvironmentSetup.manualDestroyEnvironment` (run `beforeEnvironmentDestroyed` inside the `try`), which needs a
   CANTON_CODE_CHANGES.md entry.
2. Init time: the 11299 dedup of the DAR upload list by package id removes a quarter of the uploads; whether that
   alone would have brought sv1 under 5 min here is not verified (the DsoRules bootstrap was still running at the
   timeout). A longer timeout for this test's `startAllSync` is the fallback.

Not verified: why the uploads averaged 7.5 s (runner load); sv1 init time in passing runs (no artifact uploaded on
success).
