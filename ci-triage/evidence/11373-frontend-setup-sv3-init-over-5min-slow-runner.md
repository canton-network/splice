# 11373 - frontend-wall-clock-time (2): SvFrontendIntegrationTest setup, sv3 not initialized within the 5 min wait on a slow runner; 5 later suites fail sv1 DSO party allocation (run 43072152132)

new (family AM shape: SV init slower than a fixed wait; family B setup-failure blast radius for the later suites).
Post-merge CI on main, sha 08774bc437 (#8442), job `frontend-wall-clock-time (2)`: 6 suites aborted, 0 tests ran.
In the first suite, SvFrontendIntegrationTest, the environment setup's `waitForNodeInitialization` checks sv3 first;
its 5 min ran from about 18:06:23 (6.6 s after sv3 started) to 18:11:23.123, while sv3 was still in the
sequencer/mediator onboarding steps of its join (no hang, every step progressed). The whole setup ran 1.3x to 1.8x
slower than the same setup on 2026-11-10 (run of 11340, which passed this setup): package signature loading 57 s vs
30 s, sv1 init 2m48s vs 1m36s, sv2 5m30s vs 4m12s, sv4 4m57s vs 3m40s; sv3 needed 3m48s there. No Daml, DAR or
Canton change between the two shas; each SV still uploads 87 DARs (22 repeats). The setup failure skipped the
namespace reset (zero `has been reset` lines), so in each of the 5 later suites sv1 could not allocate the new DSO
party (`Not fully authorized`) and timed out after 5 min. No `NO_TEMPLATES_FOR_PACKAGE_NAME_AND_QUALIFIED_NAME`
(splice#8432 is in this sha) in the job log or artifacts.

- Run: https://github.com/canton-network/splice/actions/runs/43072152132, main 08774bc437 ("Add missing release notes
  for the TBAR v1.1 SV app and UI changes (#8442)"), job 130703023512
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`, attempt 1, runner self-hosted-k8s-large-runner-wrhw5.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (setup wait budget vs SV init time on a slow runner), splice app (repeated DAR uploads, AM).

## 1. Job and failures

```
RUN=43072152132; export NAME='frontend-wall-clock-time (2)'
for p in 1 2 3 4 5; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name) \(.started_at) \(.completed_at) runner=\(.runner_name)"'
```
```
130703023512 attempt=1 ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2) 2026-11-11T17:53:55Z 2026-11-11T18:38:28Z runner=self-hosted-k8s-large-runner-wrhw5
```

```
L=log/inbox-1111b/130703023512.log
grep -anE '\*\*\* FAILED|ABORTED|Tests: succeeded|contains problems|Run completed' $L | cut -c1-220 | head -9
sed -n '13581,13595p' $L | cut -c29-260
```
```
13581:2026-11-11T18:11:30.1551763Z [info] org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest *** ABORTED ***
13647:2026-11-11T18:16:43.8520240Z [info] org.lfdecentralizedtrust.splice.integration.tests.WalletPaymentFrontendIntegrationTest *** ABORTED ***
13713:2026-11-11T18:21:57.2977703Z [info] org.lfdecentralizedtrust.splice.integration.tests.WalletTransferOffersFrontendIntegrationTest *** ABORTED ***
13779:2026-11-11T18:27:14.8016429Z [info] org.lfdecentralizedtrust.splice.integration.tests.WalletTransactionHistoryFrontendIntegrationTest *** ABORTED ***
13845:2026-11-11T18:32:28.2454109Z [info] org.lfdecentralizedtrust.splice.integration.tests.AllocationsFrontendIntegrationTest *** ABORTED ***
13913:2026-11-11T18:37:42.4673111Z [info] org.lfdecentralizedtrust.splice.integration.tests.WalletNoDevNetFrontendIntegrationTest *** ABORTED ***
13979:2026-11-11T18:37:42.4745978Z [info] Run completed in 32 minutes, 6 seconds.
13982:2026-11-11T18:37:42.4747530Z [info] Tests: succeeded 0, failed 0, canceled 0, ignored 0, pending 0
13983:2026-11-11T18:37:42.4748132Z [info] *** 6 SUITES ABORTED ***
 [info] org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest *** ABORTED ***
 [info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:234)
 [info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
 [info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
 [info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
 [info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
 [info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
 [info]   at org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition$.$anonfun$waitForNodeInitialization$1(EnvironmentDefinition.scala:665)
 ...
 [info]   at org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition$.waitForNodeInitialization(EnvironmentDefinition.scala:665)
 [info]   at org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition.$anonfun$withInitializedNodes$1(EnvironmentDefinition.scala:188)
```

The wait is sequential over all local nodes, 5 min each (default of `waitForInitialization`, see 11347 packet section 2):

```
S=08774bc43753ccf96b42bdfc56ea8fa63685bc73
git show $S:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala | sed -n '664,665p'
```
```
  def waitForNodeInitialization(env: SpliceConsoleEnvironment): Unit =
    env.amuletNodes.local.foreach(_.waitForInitialization())
```

## 2. Suite timeline: one 5 min init timeout per suite

Artifact `logs-frontend-wall-clock-time-2` (id 13884305396) to `log/11373/logs-frontend-wall-clock-time-2/`.

```
cd log/11373/logs-frontend-wall-clock-time-2
zcat canton_network_test.clog.gz | grep -aE 'Starting test suite|Timeout while waiting for initialization|Test (succeeded|failed)|has been reset|Failed to reset' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/'
```
```
2026-11-11T18:05:36.424Z Starting test suite 'SvFrontendIntegrationTest'...
2026-11-11T18:11:23.123Z Timeout while waiting for initialization of sv3
2026-11-11T18:11:29.351Z Starting test suite 'WalletPaymentFrontendIntegrationTest'...
2026-11-11T18:16:43.214Z Timeout while waiting for initialization of sv1
2026-11-11T18:16:43.271Z Starting test suite 'WalletTransferOffersFrontendIntegrationTest'...
2026-11-11T18:21:56.985Z Timeout while waiting for initialization of sv1
2026-11-11T18:21:57.034Z Starting test suite 'WalletTransactionHistoryFrontendIntegrationTest'...
2026-11-11T18:27:13.911Z Timeout while waiting for initialization of sv1
2026-11-11T18:27:13.984Z Starting test suite 'AllocationsFrontendIntegrationTest'...
2026-11-11T18:32:27.907Z Timeout while waiting for initialization of sv1
2026-11-11T18:32:27.961Z Starting test suite 'WalletNoDevNetFrontendIntegrationTest'...
2026-11-11T18:37:41.650Z Timeout while waiting for initialization of sv1
```

## 3. First suite: node start and init-finish times

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=1e48a2a8/' | grep -aE 'app initialization: (Starting initialization|Initialize app finished|Initialize node finished)' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/' | awk '!seen[substr($0,26)]++' | grep -v 'Initialize node finished'
```
```
2026-11-11T18:05:46.625Z sv1 app initialization: Starting initialization
2026-11-11T18:05:47.781Z sv2 app initialization: Starting initialization
...
2026-11-11T18:06:06.468Z sv4 app initialization: Starting initialization
...
2026-11-11T18:06:16.521Z sv3 app initialization: Starting initialization
2026-11-11T18:06:19.621Z sv2Scan app initialization: Starting initialization
2026-11-11T18:06:22.642Z sv2Validator app initialization: Starting initialization
2026-11-11T18:08:34.426Z sv1Scan app initialization: Initialize app finished after PT2M43.56682513S
2026-11-11T18:08:34.797Z sv1 app initialization: Initialize app finished after PT2M47.5680974S
2026-11-11T18:08:45.232Z sv1Validator app initialization: Initialize app finished after PT2M45.35299618S
2026-11-11T18:10:32.919Z bobValidator app initialization: Initialize app finished after PT4M30.034307029S
2026-11-11T18:10:36.408Z aliceValidator app initialization: Initialize app finished after PT4M42.501423378S
2026-11-11T18:10:41.735Z sv4Scan app initialization: Initialize app finished after PT4M34.90121347S
2026-11-11T18:10:42.022Z sv2Scan app initialization: Initialize app finished after PT4M22.401069544S
2026-11-11T18:11:03.319Z sv4 app initialization: Initialize app finished after PT4M56.843947927S
2026-11-11T18:11:15.235Z sv4Validator app initialization: Initialize app finished after PT5M4.800007804S
2026-11-11T18:11:16.244Z sv3Scan app initialization: Initialize app finished after PT5M2.750662746S
2026-11-11T18:11:17.916Z sv2 app initialization: Initialize app finished after PT5M30.129699499S
2026-11-11T18:11:21.140Z providerSplitwellBackend app initialization: Initialize app finished after PT5M24.266366749S
2026-11-11T18:11:22.027Z sv2Validator app initialization: Initialize app finished after PT4M59.380034851S
```

Which node the wait checked first: the sv3 timeout at 18:11:23.123 means its 5 min started at about 18:06:23.1, 0.5 s
after the last node started (18:06:22.642). No node was initialized before 18:08:34, so had any node been checked
before sv3, sv3's wait could not have started before 18:08:34. So sv3 was checked first and got 5 min from 6.6 s
after its own start.

## 4. sv3 progressed until the end (no hang)

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=1e48a2a8/SV=sv3"' | grep -aE 'app initialization: [A-Z]' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/' | awk '!seen[substr($0,26)]++'
# (selected lines)
```
```
2026-11-11T18:06:16.521Z sv3 app initialization: Starting initialization
2026-11-11T18:06:16.617Z sv3 app initialization: Wait for user started
2026-11-11T18:06:26.554Z sv3 app initialization: Wait for user finished after PT9.936237864S
2026-11-11T18:06:26.554Z sv3 app initialization: Initialize node started
2026-11-11T18:07:23.712Z sv3 app initialization: Get participant ID started
2026-11-11T18:07:23.850Z sv3 app initialization: JoiningNodeInitializer joining Dso with key started
2026-11-11T18:11:29.187Z sv3 app initialization: JoiningNodeInitializer joining Dso with key failed
2026-11-11T18:11:29.190Z sv3 app initialization: Ignoring initialization failure, we are actually shutting down. Message was: sv3 app initialization: Initialize app failed
```

Join steps (distinct INFO messages with count, last and first time; selected):

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=1e48a2a8/SV=sv3"' | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T18:06:26" && t<"2026-11-11T18:11:30") print}' | grep -aE '"level":"(INFO|WARN|ERROR)"' | grep -avE 'Request \(tid|HTTP GET /api/sv/status|queued action|FetchTime|Detected an error|is not stale|Checking whether the task|[Ii]ndex |HTTP client|Starting trigger|Now retrying|Retrying on same|Sending upload dar|Completed processing PeriodicTask' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-240 | awk '{k=substr($0,26,45); c[k]++; if(!(k in f)) {f[k]=$0; o[++n]=k}; l[k]=substr($0,1,24)} END{for(i=1;i<=n;i++) print c[o[i]], "last", l[o[i]], f[o[i]]}'
```
```
1 last 2026-11-11T18:07:23.989Z 2026-11-11T18:07:23.989Z Attempting to get 'DSO party ID from sponsoring SV'", INFO
46 last 2026-11-11T18:08:33.616Z 2026-11-11T18:07:24.197Z The operation 'Get DSO party ID from sponsoring SV' failed with a retryable error (full stack trace  ... (truncated)", INFO
1 last 2026-11-11T18:08:38.604Z 2026-11-11T18:08:38.604Z Got 'DSO party ID from sponsoring SV': DSO-1e48a2a8-1e48a2a8::1220ecc32eed...", INFO
1 last 2026-11-11T18:09:11.332Z 2026-11-11T18:09:11.332Z Vetting packages based on state from sponsor", INFO
1 last 2026-11-11T18:10:34.227Z 2026-11-11T18:10:34.227Z Packages vetting completed", INFO
1 last 2026-11-11T18:10:34.299Z 2026-11-11T18:10:34.299Z Requesting to be onboarded via the sponsor SV", INFO
1 last 2026-11-11T18:11:03.520Z 2026-11-11T18:11:03.520Z DSO party is now hosted in the candidate SV participant PAR::sv3::12203308a4f3...", INFO
1 last 2026-11-11T18:11:14.238Z 2026-11-11T18:11:14.238Z Success: the DsoRules and AmuletRules are visible", INFO
1 last 2026-11-11T18:11:14.317Z 2026-11-11T18:11:14.317Z Proposing participant PAR::sv3::12203308a4f3... be promoted to Submission rights for the DSO party, will wait for it to take effect.", INFO
1 last 2026-11-11T18:11:21.652Z 2026-11-11T18:11:21.652Z Onboarding sequencer", INFO
1 last 2026-11-11T18:11:22.552Z 2026-11-11T18:11:22.552Z Onboarding sequencer SEQ::sv3::12203fe38808... through sponsoring SV", INFO
1 last 2026-11-11T18:11:22.802Z 2026-11-11T18:11:22.802Z Adding mediator MediatorToOnboard(synchronizerId = global-domain::1220ecc32eed..., mediatorId = MED::sv3::12202011287f..., sequencerId = SEQ::s
1 last 2026-11-11T18:11:24.030Z 2026-11-11T18:11:24.030Z Success: Add mediator MED::sv3::12202011287f...", INFO
1 last 2026-11-11T18:11:26.490Z 2026-11-11T18:11:26.490Z Onboarded sequencer SEQ::sv3::12203fe38808...", INFO
1 last 2026-11-11T18:11:26.532Z 2026-11-11T18:11:26.532Z Stopping sv3 node", INFO
```

So at the timeout (18:11:23.123) sv3 was onboarding its sequencer through the sponsor and adding its mediator; both
steps completed after the timeout, before the shutdown reached sv3 (18:11:26.532).

Where sv3's 5 min went: 10 s waiting for its user, 57 s loading package signatures (section 5), 74 s waiting for the
sponsor sv1 (initialized at 18:08:34.8), 83 s vetting/uploading DARs, 40 s DSO party migration, then the sequencer
and mediator onboarding steps were still running at the timeout.

## 5. Everything slower than the day before (baseline: same shard, same setup, passed)

Baseline: run 42823122392 (11340, main 1844b95301, 2026-11-10), whose SvFrontendIntegrationTest setup passed (11340
failed later inside a test). Artifact `logs-frontend-wall-clock-time-2` (id 13770678830) to `log/11373/baseline-11340/`.

```
for f in log/11373/baseline-11340/canton_network_test.clog.gz log/11373/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz; do echo "== $f"; C=$(zcat $f | grep -ao 'SvFrontendIntegrationTest/config=[0-9a-f]*' | head -1); zcat $f | grep -a "$C/SV=sv[1-4]\"" | grep -aE 'app initialization: (Starting initialization|Initialize app finished after)' | sed -E 's/.*"@timestamp":"([^"]+)","message":"(sv[0-9]) app initialization: ([^"]{0,50}).*/\1 \2 \3/' | awk '!seen[$2" "$3]++' | sort -k2,2 -k1,1; done
```
```
== log/11373/baseline-11340/canton_network_test.clog.gz
2026-11-10T13:42:56.858Z sv1 Starting initialization
2026-11-10T13:44:32.941Z sv1 Initialize app finished after PT1M36.078626382S
2026-11-10T13:42:44.882Z sv2 Starting initialization
2026-11-10T13:46:57.039Z sv2 Initialize app finished after PT4M12.149320258S
2026-11-10T13:43:19.391Z sv3 Starting initialization
2026-11-10T13:47:07.789Z sv3 Initialize app finished after PT3M48.39509848S
2026-11-10T13:43:03.331Z sv4 Starting initialization
2026-11-10T13:46:43.040Z sv4 Initialize app finished after PT3M39.704624749S
== log/11373/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz
2026-11-11T18:05:46.625Z sv1 Starting initialization
2026-11-11T18:08:34.797Z sv1 Initialize app finished after PT2M47.5680974S
2026-11-11T18:05:47.781Z sv2 Starting initialization
2026-11-11T18:11:17.916Z sv2 Initialize app finished after PT5M30.129699499S
2026-11-11T18:06:16.521Z sv3 Starting initialization
2026-11-11T18:06:06.468Z sv4 Starting initialization
2026-11-11T18:11:03.319Z sv4 Initialize app finished after PT4M56.843947927S
```

A pure CPU step, package signature loading (lazy per node, `NodeBase.loadPackageSignatures`), took 1.8x as long:

```
for f in log/11373/baseline-11340/canton_network_test.clog.gz log/11373/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz; do echo "== $f"; zcat $f | grep -a 'SvFrontendIntegrationTest/config=' | grep -aE '(Loading|Finished loading) package signatures' | grep -aE 'SV=sv[13]"' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,70}).*(SV=sv[0-9]).*/\1 \3 \2/'; done
```
```
== log/11373/baseline-11340/canton_network_test.clog.gz
2026-11-10T13:43:19.746Z SV=sv3 Loading package signatures from resources
2026-11-10T13:43:21.686Z SV=sv1 Loading package signatures from resources
2026-11-10T13:43:51.695Z SV=sv3 Finished loading package signatures from resources
2026-11-10T13:43:51.695Z SV=sv1 Finished loading package signatures from resources
== log/11373/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz
2026-11-11T18:06:26.432Z SV=sv1 Loading package signatures from resources
2026-11-11T18:06:26.563Z SV=sv3 Loading package signatures from resources
2026-11-11T18:07:23.701Z SV=sv1 Finished loading package signatures from resources
2026-11-11T18:07:23.704Z SV=sv3 Finished loading package signatures from resources
```

(Same step, other runs today: 25 s in 11363's key-download (0) on an x-large runner, 14:12:00.204 to 14:12:25.885;
32 s in 11347.)

```
zcat log/11363/logs-key-download-0/canton_network_test.clog.gz | grep -aE '(Loading|Finished loading) package signatures' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]+)".*config=([0-9a-f]+)\/([A-Za-z]+=[A-Za-z0-9]+).*/\1 \2 \3 \4/' | awk '{print $1, $2, $NF, $(NF-1)}' | head -8
```
```
2026-11-11T14:12:00.204Z Loading SV=sv3 3601e0e7
2026-11-11T14:12:00.204Z Loading SV=sv2 3601e0e7
2026-11-11T14:12:00.204Z Loading SV=sv1 3601e0e7
2026-11-11T14:12:00.708Z Loading SV=sv4 3601e0e7
2026-11-11T14:12:25.885Z Finished SV=sv3 3601e0e7
2026-11-11T14:12:25.885Z Finished SV=sv2 3601e0e7
2026-11-11T14:12:25.885Z Finished SV=sv1 3601e0e7
2026-11-11T14:12:25.885Z Finished SV=sv4 3601e0e7
```

## 6. DAR uploads: the same 87 per SV as before, 22 repeats; ~590 uploads in one Canton

```
T=canton_network_test.clog.gz; for sv in sv1 sv2 sv3 sv4; do zcat $T | grep -a "SvFrontendIntegrationTest/config=1e48a2a8/SV=$sv\"" | grep -a 'Sending upload dar for' | sed -E 's/.*"@timestamp":"([^"]+)".*Sending upload dar for ([^"]+)".*/\1 \2/' | awk -v sv=$sv '{n++; c[$2]++; if(n==1) f=$1; l=$1} END{for(k in c){d++; if(c[k]>1) r+=c[k]-1} print sv, "uploads", n, "distinct", d, "repeats", r+0, "first", f, "last", l}'; done
```
```
sv1 uploads 87 distinct 65 repeats 22 first 2026-11-11T18:07:37.005Z last 2026-11-11T18:08:20.846Z
sv2 uploads 87 distinct 65 repeats 22 first 2026-11-11T18:08:40.199Z last 2026-11-11T18:10:11.210Z
sv3 uploads 87 distinct 65 repeats 22 first 2026-11-11T18:09:12.000Z last 2026-11-11T18:10:30.907Z
sv4 uploads 87 distinct 65 repeats 22 first 2026-11-11T18:08:38.396Z last 2026-11-11T18:10:06.145Z
```

Participant side (`Processing package upload of` to `Managed to upload one or more archives`, per submission id):

```
zcat canton.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T18:05:30" && t<"2026-11-11T18:11:30") print}' | grep -aE 'Processing package upload of|Managed to upload one or more archives' | sed -E 's/.*"@timestamp":"([^"]+)","message":"(Processing|Managed)[^"]*submissionId ([0-9a-f-]+).*participant=([A-Za-z0-9]+).*/\1 \2 \3 \4/' | awk '{split($1,t,"T"); split(t[2],h,":"); s=h[1]*3600+h[2]*60+h[3]; if($2=="Processing") {st[$3]=s; p[$3]=$4} else if($3 in st){d=s-st[$3]; n[p[$3]]++; sum[p[$3]]+=d; if(d>mx[p[$3]])mx[p[$3]]=d}} END{for(k in n) printf "%s uploads %d mean %.1fs max %.1fs\n", k, n[k], sum[k]/n[k], mx[k]}' | sort
```
```
aliceParticipant uploads 79 mean 3.0s max 7.9s
bobParticipant uploads 79 mean 3.0s max 7.7s
splitwellParticipant uploads 80 mean 2.0s max 5.4s
sv1Participant uploads 87 mean 1.8s max 7.4s
sv2Participant uploads 87 mean 3.0s max 7.4s
sv3Participant uploads 87 mean 2.6s max 7.6s
sv4Participant uploads 87 mean 3.1s max 6.9s
```

No Daml, DAR resource or Canton pin change between 11347's sha (2ef0423979, same 87/22 counts) and this one, nor
between the baseline sha of section 5 (1844b95301, an ancestor, 16 commits) and this one:

```
git log --format='%h %ad %s' --date=iso-strict 2ef0423979..08774bc437 -- daml '*DarResources*.scala' 'apps/common/src/main/resources/dar' | head
git show 08774bc43753ccf96b42bdfc56ea8fa63685bc73:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```
(first command: no output)

```
git merge-base --is-ancestor 1844b95301 08774bc437 && echo ancestor; git log --oneline 1844b95301..08774bc437 | wc -l
git log --format='%h %ad %s' --date=iso-strict 1844b95301..08774bc437 -- daml '*DarResources*.scala' 'apps/common/src/main/resources/dar' nix/canton-sources.json | head
git show 1844b95301:nix/canton-sources.json | grep -m1 version
```
```
ancestor
16
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```
(second command: no output)

## 7. Later suites: no namespace reset after the setup failure, sv1 cannot allocate the DSO party

```
zcat canton_network_test.clog.gz | grep -ac 'has been reset'
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -a 'DSO-53a4c808' | head -1 | grep -aoE 'rejectionReason = [^)]{0,200}'
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -aoE 'PartyToParticipant\(partyId = DSO-[0-9a-f]+' | sort | uniq -c
```
```
0
rejectionReason = Not fully authorized\n
     86 PartyToParticipant(partyId = DSO-31c7e7e3
     84 PartyToParticipant(partyId = DSO-51584def
     86 PartyToParticipant(partyId = DSO-53a4c808
     83 PartyToParticipant(partyId = DSO-a9647822
     84 PartyToParticipant(partyId = DSO-e806ca3d
```

(5 DSO parties = the 5 later suites.) In WalletPaymentFrontendIntegrationTest sv1 checked `Check whether Party
DSO-53a4c808-53a4c808::... is allocated on PAR::sv1::...` 86 times without success, until 18:16:41 (each failed check
logs two lines):

```
zcat canton_network_test.clog.gz | grep -a "The operation 'Check whether Party DSO-53a4c808" | grep -a 'PAR::sv1' | grep -aoE "is allocated on PAR::sv1[^']*' (has failed with an exception|failed with a retryable error)" | sed -E 's/.*\x27 //' | sort | uniq -c
zcat canton_network_test.clog.gz | grep -a 'Check whether Party DSO-53a4c808' | grep -a 'is allocated on' | grep -a 'PAR::sv1' | tail -1 | cut -c1-40
```
```
     86 failed with a retryable error
     86 has failed with an exception
{"@timestamp":"2026-11-11T18:16:41.212Z"
``` This is the setup-failure blast
radius of family B: `EnvironmentSetup.manualCreateEnvironment` only closes the fixture on a setup failure, so the
ResetDecentralizedNamespace plugin never runs.

## 8. splice#8432 check

```
grep -ac 'NO_TEMPLATES_FOR_PACKAGE_NAME_AND_QUALIFIED_NAME' log/inbox-1111b/130703023512.log
zcat canton_network_test.clog.gz canton.clog.gz | grep -ac 'NO_TEMPLATES_FOR_PACKAGE_NAME_AND_QUALIFIED_NAME'
```
```
0
0
```

## 9. Frequency and duplicate check

frontend-wall-clock-time (2) on post-merge main since 2026-11-09 (same loop and runs.txt as 11363 packet section 8:
44 runs created 2026-11-09 to 2026-11-11T22:30Z; re-run 2026-11-12 by the verifier with the same counts):

```
grep 'frontend-wall-clock-time' log/11373/jobs-fwct2-kd0.txt | awk '{c[$4]++} END{for(k in c) print c[k], k}'
grep 'frontend-wall-clock-time' log/11373/jobs-fwct2-kd0.txt | grep failure
```
```
42 success
4 failure
43072152132 130703023512 a=1 failure 2026-11-11T17:53:55Z frontend-wall-clock-time (2)
42823122392 129853835620 a=1 failure 2026-11-10T13:29:54Z frontend-wall-clock-time (2)
42790498517 129742806248 a=1 failure 2026-11-10T09:27:49Z frontend-wall-clock-time (2)
42646231899 129263943605 a=1 failure 2026-11-09T15:42:29Z frontend-wall-clock-time (2)
```

The other three are other failures:

```
for r in 42646231899 42790498517; do gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $r" --jq '.items[] | "\(.number) \(.state)/\(.state_reason) \(.title[0:110])"'; awk -F'\t' -v r=$r '$3==r {print $1, $5, substr($7,1,200), "|", $8}' ci-triage/evidence/INDEX.tsv | grep -i frontend; done
```
```
11310 closed/duplicate GHA Run 42646231899 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11310 129263943605 frontend-wall-clock-time (2) checkErrors canton_before_shutdown.clog: globalSequencerSv1 `Sequencer is unhealthy, so disconnecting all members. BFT orderer can't connect to database` ... | 11211
11330 closed/duplicate GHA Run 42790498517 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11330 129742806248 frontend-wall-clock-time (2) checkErrors canton_before_shutdown.clog: globalSequencerSv1 pool timeout (8/8 active) ... | 11211
```
and 42823122392 is 11340 (family AS, a test bug in the expiring-proposal step). So this is the first setup timeout
on this shard since 2026-11-09 (1 of 46 jobs).

Related: 11299 (AM parent, open, Triaged, no assignee: DAR dedup + test timeout), 11347 (same day, sv1 init over
startAllSync's 5 min in SvReonboardingIntegrationTest, 7.5 s mean DAR upload), and the 4 simtime shards on today's
runs with `Running setup` / 5 min (11358 11362 11368 11374, triaged in group g1 of this round).

## Verdict

New occurrence of the AM shape (SV init slower than a fixed wait), not a duplicate of 11347 (other wait, other
node, no teardown cascade). Test flake on a slow runner: the setup's sequential `waitForNodeInitialization` gives the
first node checked (sv3) only 5 min from its own start, and on this runner every init phase took 1.3x to 1.8x as
long as on 2026-11-10, where sv3 already needed 3m48s. The 5 later aborts are the family B setup-failure blast
radius and carry no signal. No Daml, DAR or Canton change since 2026-11-10 23:29; the slowdown shows in a pure CPU
step, which points at the runner, not a commit. Not the same as the simtime setup failures of the same day (g1: a
wedge after lost mediator-add signatures at the BFT 1 -> 4 step); sv3 here was still advancing at the timeout.

Fixes (none on main):
1. Init time: the 11299 dedup of the DAR upload list by package id (22 of 87 uploads per SV, about 150 of the 590
   uploads in this setup).
2. Wait budget: `EnvironmentDefinition.waitForNodeInitialization` should use one overall deadline sized for the slowest
   runner (e.g. 10 min across all nodes) instead of 5 min per node starting with whichever node comes first.
3. Blast radius: run the namespace reset (or the plugins' destroy hooks) also when setup fails (family B note).

Not verified: why this runner was slow (no resource metrics in the artifacts); how long sv3 would have needed.
