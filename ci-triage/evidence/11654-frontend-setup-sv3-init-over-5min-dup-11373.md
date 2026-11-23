# 11654 - frontend-wall-clock-time (2): SvFrontendIntegrationTest setup, sv3 not initialized within the 5 min wait; 6 later environments fail sv1 DSO party allocation (run 44500851782)

DUPLICATE of 11373 (family AM shape: SV init slower than a fixed wait; family B setup-failure blast radius for the
later suites). Post-merge run on main, sha 77c89208ee (#8524), attempt 1, job `frontend-wall-clock-time (2)`:
6 suites aborted, 1 test failed (the one test of AnsAuth0FrontendIntegrationTest, same setup exception), 0 passed,
no checkErrors output. In the first suite, SvFrontendIntegrationTest, the environment setup's sequential
`waitForNodeInitialization` checked sv3 first; its 5 min ran from about 10:27:19.27 (0.4 s after sv3, the last
node, started) to 10:32:19.268. sv3 was still advancing: ACS snapshot imported at 10:32:00.754, `add sv to Dso`
being retried on contention at 10:32:12, 7 s before the timeout. No SV other than sv1 was initialized after 5 min.
The runner was not uniformly slow (CPU-only package signature loading 31.3 s, the 30 s baseline), but everything
that goes through the synchronizer was: sv1 init 2m01s (baseline 1m36s), joining-SV package vetting 101-114 s
(68 s in 11550, 100-113 s in 11558), sv3's `onboard/sv/start` call to the sponsor 37.6 s, and the domain time seen
by sv1 lagged up to 21 s behind wall clock from 10:29 to 10:31 while six participants uploaded and vetted DARs.
The DAR dedup (#8467) is in (65 uploads per SV, 0 repeats). The setup failure skipped the namespace reset (zero
`has been reset` lines), so in each of the 6 later environments sv1 could not allocate the new DSO party
(`Not fully authorized`) and timed out after 5 min. Attempt 2 of the run passed.

- Run: https://github.com/canton-network/splice/actions/runs/44500851782, main 77c89208ee ("Support backing up the
  SV runbook node (#8524)"), event push, attempt 1, job 135480484996
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`, 10:14:53 to 11:07:08 UTC, runner
  self-hosted-k8s-large-runner-wdr8h. It is the only failed job of attempt 1 among the frontend-wall-clock-time
  shards ((0) and (1) passed); the run as a whole is `success` after attempt 2.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: test (setup wait budget vs joining-SV init time on a loaded shard).

## 1. Job and failures

```
gh api repos/canton-network/splice/actions/runs/44500851782 --jq '{run_attempt,conclusion,status,head_sha,updated_at}'
gh api "repos/canton-network/splice/actions/runs/44500851782/attempts/1/jobs?per_page=100" --paginate --jq '.jobs[] | select(.name|test("frontend-wall-clock-time")) | {name,id,conclusion,started_at,completed_at}'
```
```
{"conclusion":"success","head_sha":"77c89208eea707c687399ca547968a2e4c85529a","run_attempt":2,"status":"completed","updated_at":"2026-11-19T11:56:27Z"}
{"completed_at":"2026-11-19T11:07:08Z","conclusion":"failure","id":135480484996,"name":"ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)","started_at":"2026-11-19T10:14:53Z"}
{"completed_at":"2026-11-19T10:34:28Z","conclusion":"success","id":135480485030,"name":"ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (0)","started_at":"2026-11-19T10:14:54Z"}
{"completed_at":"2026-11-19T10:42:16Z","conclusion":"success","id":135480485118,"name":"ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (1)","started_at":"2026-11-19T10:14:56Z"}
```

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135480484996/logs > log/11654/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11654/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed|Suites: completed|SUITES ABORTED|tests\.[A-Za-z0-9]+ \*\*\* ABORTED' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++'
sed -E 's/\x1b\[[0-9;]*m//g' log/11654/job.log | grep -a -m1 -A8 'SvFrontendIntegrationTest \*\*\* ABORTED' | sed -E 's/^[^Z]*Z //'
```
```
[info] org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SplitwellFrontendIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.WalletPaymentFrontendIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.WalletTransferOffersFrontendIntegrationTest *** ABORTED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.WalletFrontendIntegrationTest *** ABORTED ***
[info] - should allow login via auth0 *** FAILED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.WalletNoDevNetFrontendIntegrationTest *** ABORTED ***
[info] Run completed in 37 minutes, 32 seconds.
[info] Suites: completed 1, aborted 6
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
[info] *** 6 SUITES ABORTED ***
[info] *** 1 TEST FAILED ***
[info] - allow login via auth0 *** FAILED ***
[info] org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest *** ABORTED ***
[info]   com.digitalasset.canton.integration.EnvironmentSetup$EnvironmentSetupException: Creating environment failed at step Running setup (EnvironmentSetup.scala:235)
[info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
[info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:197)
[info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:192)
[info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
[info]   at org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition$.$anonfun$waitForNodeInitialization$1(EnvironmentDefinition.scala:665)
[info]   at org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition$.$anonfun$waitForNodeInitialization$1$adapted(EnvironmentDefinition.scala:665)
```

The AnsAuth0FrontendIntegrationTest failure carries the same exception and stack (it creates its environment
inside the test case, so the setup failure is reported as a failed test, not an aborted suite).

```
git show 77c89208ee:nix/canton-sources.json | grep -m1 version
git show --stat --format='%h %s' 77c89208ee | head -5
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
77c89208ee Support backing up the SV runbook node (#8524)

 build-tools/cncluster                 | 8 ++++++--
 cluster/scripts/find-recent-backup.sh | 5 ++++-
 cluster/scripts/utils.source          | 6 +++++-
```

The head commit touches cluster scripts only.

## 2. Suite timeline: one 5 min init timeout per environment (confirming grep of 11373, part 1)

Artifact `logs-frontend-wall-clock-time-2` (id 14523676959) to `log/11654/logs-frontend-wall-clock-time-2/`.

```
cd log/11654/logs-frontend-wall-clock-time-2
zcat canton_network_test.clog.gz | grep -a -E 'Starting test suite|Timeout while waiting for initialization|Test (succeeded|failed)|has been reset' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/'
```
```
2026-11-19T10:26:30.162Z Starting test suite 'SvFrontendIntegrationTest'...
2026-11-19T10:32:19.268Z Timeout while waiting for initialization of sv3
2026-11-19T10:32:21.240Z Starting test suite 'SplitwellFrontendIntegrationTest'...
2026-11-19T10:37:38.341Z Timeout while waiting for initialization of sv1
2026-11-19T10:37:38.404Z Starting test suite 'WalletPaymentFrontendIntegrationTest'...
2026-11-19T10:42:55.780Z Timeout while waiting for initialization of sv1
2026-11-19T10:42:55.840Z Starting test suite 'WalletTransferOffersFrontendIntegrationTest'...
2026-11-19T10:48:09.586Z Timeout while waiting for initialization of sv1
2026-11-19T10:48:09.635Z Starting test suite 'WalletFrontendIntegrationTest'...
2026-11-19T10:53:26.739Z Timeout while waiting for initialization of sv1
2026-11-19T10:53:26.790Z Starting test suite 'AnsAuth0FrontendIntegrationTest'...
2026-11-19T10:58:43.750Z Timeout while waiting for initialization of sv1
2026-11-19T10:58:43.801Z Test failed: 'AnsAuth0FrontendIntegrationTest/A Name Service UI should allow login via auth0', message: Creating environment failed at step Running se
2026-11-19T10:58:43.817Z Starting test suite 'WalletNoDevNetFrontendIntegrationTest'...
2026-11-19T11:04:00.716Z Timeout while waiting for initialization of sv1
```

## 3. First suite: node start and init-finish times; sv3 was checked first; signature loading 31 s

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=291c8f8b/' | grep -a -E 'app initialization: (Starting initialization|Initialize app finished)' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/' | awk '!seen[substr($0,26)]++' | sort
```
```
2026-11-19T10:26:42.935Z providerSplitwellBackend app initialization: Starting initialization
2026-11-19T10:26:44.436Z splitwellValidator app initialization: Starting initialization
2026-11-19T10:26:47.450Z sv1Validator app initialization: Starting initialization
2026-11-19T10:26:50.497Z bobValidator app initialization: Starting initialization
2026-11-19T10:26:53.478Z aliceValidator app initialization: Starting initialization
2026-11-19T10:26:56.543Z sv1 app initialization: Starting initialization
2026-11-19T10:26:59.477Z sv1Scan app initialization: Starting initialization
2026-11-19T10:27:02.475Z sv2 app initialization: Starting initialization
2026-11-19T10:27:02.824Z sv3Validator app initialization: Starting initialization
2026-11-19T10:27:03.275Z sv4Validator app initialization: Starting initialization
2026-11-19T10:27:03.623Z sv4Scan app initialization: Starting initialization
2026-11-19T10:27:06.839Z sv4 app initialization: Starting initialization
2026-11-19T10:27:09.884Z sv2Validator app initialization: Starting initialization
2026-11-19T10:27:12.868Z sv3Scan app initialization: Starting initialization
2026-11-19T10:27:15.907Z sv2Scan app initialization: Starting initialization
2026-11-19T10:27:18.869Z sv3 app initialization: Starting initialization
2026-11-19T10:28:55.170Z sv1Scan app initialization: Initialize app finished after PT1M55.693014464S
2026-11-19T10:28:57.403Z sv1 app initialization: Initialize app finished after PT2M0.850595084S
2026-11-19T10:29:39.668Z sv1Validator app initialization: Initialize app finished after PT2M52.213146098S
2026-11-19T10:31:37.389Z bobValidator app initialization: Initialize app finished after PT4M46.886906269S
2026-11-19T10:31:58.255Z aliceValidator app initialization: Initialize app finished after PT5M4.772895511S
2026-11-19T10:32:04.071Z providerSplitwellBackend app initialization: Initialize app finished after PT5M21.134887361S
2026-11-19T10:32:10.600Z sv2Scan app initialization: Initialize app finished after PT4M54.692162115S
```

The sv3 timeout at 10:32:19.268 means its 5 min started at about 10:27:19.27, 0.4 s after sv3 itself started
(10:27:18.869, the last node) and before any node was initialized, so sv3 was the first node checked. No SV app
other than sv1 finished. sv1 took 2m01s (baseline 2026-11-10: 1m36s; 11373: 2m48s; 11550: 1m49s; 11558: 2m38s).

The CPU-only step that separates a slow runner (11373: 57 s, 11558: 61 s) from a slow step (11550: 30.9 s):

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=291c8f8b/' | grep -a -E '(Loading|Finished loading) package signatures' | grep -a -E 'SV=sv[13]"' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,70}).*(SV=sv[0-9]).*/\1 \3 \2/'
```
```
2026-11-19T10:27:19.224Z SV=sv3 Loading package signatures from resources
2026-11-19T10:27:20.009Z SV=sv1 Loading package signatures from resources
2026-11-19T10:27:50.555Z SV=sv3 Finished loading package signatures from resources
2026-11-19T10:27:50.555Z SV=sv1 Finished loading package signatures from resources
```

31.3 s, the baseline: the runner's CPU was not slow.

## 4. sv3 progressed until the end (confirming grep of 11373, part 2)

```
T=canton_network_test.clog.gz; for sv in sv2 sv4 sv3; do zcat $T | grep -a "config=291c8f8b/SV=$sv\"" | grep -a -E '"(Onboarding sequencer|Onboarded sequencer|Initializing sequencer|Onboarding mediator|Initializing mediator|Requesting to be onboarded via the sponsor SV|Packages vetting completed|Vetting packages based on state from sponsor)[^"]*"' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,45}).*/\1 \2/' | sed "s/^/$sv /"; done
```
```
sv2 2026-11-19T10:29:05.976Z Vetting packages based on state from sponsor
sv2 2026-11-19T10:30:53.503Z Packages vetting completed
sv2 2026-11-19T10:30:57.562Z Requesting to be onboarded via the sponsor SV
sv4 2026-11-19T10:29:08.193Z Vetting packages based on state from sponsor
sv4 2026-11-19T10:31:01.933Z Packages vetting completed
sv4 2026-11-19T10:31:03.892Z Requesting to be onboarded via the sponsor SV
sv3 2026-11-19T10:28:59.866Z Vetting packages based on state from sponsor
sv3 2026-11-19T10:30:40.557Z Packages vetting completed
sv3 2026-11-19T10:30:45.092Z Requesting to be onboarded via the sponsor SV
```

The joining SVs could only start vetting after sv1 was up (10:28:57), 1m38s into sv3's 5 min; vetting took 101 s
(sv3), 108 s (sv2), 114 s (sv4) against 68 s in 11550. Unlike 11550 and 11558, no joiner reached `Onboarding
sequencer` before the timeout; sv3 spent its last 94 s in the DSO onboarding steps before it:

```
zcat $T | grep -a 'config=291c8f8b/SV=sv3"' | awk -F'"' '$4>="2026-11-19T10:30:45" && $4<="2026-11-19T10:32:20"' | grep -a -E 'onboard/sv/start\): (Requesting|HTTP request took)|"(Onboarding sequencer|Onboarding mediator|Importing|Imported|Downloaded|Successfully|Reconnect|candidate SV|Disconnecting|Proposing party|Got .SvOnboardingConfirmed|Authorizing|Waiting for)[^"]{0,80}' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/' | head -30
zcat $T | grep -a 'config=291c8f8b/SV=sv3"' | awk -F'"' '$4>="2026-11-19T10:32:05" && $4<="2026-11-19T10:32:19.3"' | grep -a '"level":"INFO"' | grep -a -v -E 'DomainTimeIngestion' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/; s/1220[0-9a-f]{60}/../g' | tail -4
```
```
2026-11-19T10:30:45.178Z HTTP client (POST /api/sv/v0/onboard/sv/start): Requesting with entity data: {\
2026-11-19T10:31:22.800Z HTTP client (POST /api/sv/v0/onboard/sv/start): HTTP request took 37621 ms to complete. Received response with
2026-11-19T10:31:37.960Z Got 'SvOnboardingConfirmed contract for digital-asset-eng-3-291c8f8b::12200641678f...': Contract(\n  contractI
2026-11-19T10:31:37.987Z Proposing party allocation to participant PAR::sv3::12200641678f...
2026-11-19T10:31:43.313Z Disconnecting from all domains
2026-11-19T10:31:43.328Z candidate SV participant disconnected from global domain
2026-11-19T10:32:00.754Z Imported Acs snapshot from sponsor SV participant to candidate participant
2026-11-19T10:32:00.864Z candidate SV participant reconnected to global domain
2026-11-19T10:32:02.002Z Got 'SvOnboardingConfirmed contract for digital-asset-eng-3-291c8f8b::12200641678f...': Contract(\n  contractI
2026-11-19T10:32:19.455Z Successfully closed 'AsyncCloseable(name=http binding admin service)'.
2026-11-19T10:32:19.455Z Successfully closed 'org.lfdecentralizedtrust.splice.admin.http.HttpAdminService$HttpAdminServiceImpl@30790d8f
2026-11-19T10:32:12.124Z Request (tid:367ad72f4553c929773f57f65e6bf2eb) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:5301: failed with ABORTED/LOCAL_VERDIC
2026-11-19T10:32:12.127Z The operation 'add sv to Dso' failed with a retryable error (full stack trace omitted):\ncategory=Some(ContentionOnSharedResources)\nErrorInfoDetail(L
2026-11-19T10:32:12.127Z The operation 'add sv to Dso' has failed with an exception. Retrying after a number of 1 failures, and after 292 milliseconds. 
2026-11-19T10:32:12.419Z Now retrying operation 'add sv to Dso'. 
```

No hang: the sponsor call took 37.6 s, the confirmed contract arrived 15 s later, the ACS import finished at
10:32:00.754, and `add sv to Dso` was being retried 7 s before the timeout.

## 5. Synchronizer round trips were slow from 10:29 to 10:31

Lag between the wall clock of the sv1 DomainTimeIngestionTrigger poll and the domain time it read, per minute:

```
zcat $T | grep -a 'config=291c8f8b/SV=sv1"' | grep -a 'Updated domain time to' | sed -E 's/.*for = ([0-9T:.-]+)Z\) with outcome: Updated domain time to ([0-9T:.-]+)Z.*/\1 \2/' | awk '{split($1,a,"T"); split($2,b,"T"); split(a[2],x,":"); split(b[2],y,":"); d=(x[1]*3600+x[2]*60+x[3])-(y[1]*3600+y[2]*60+y[3]); m=substr(a[2],1,5); if(d>mx[m]) mx[m]=d; if(!(m in mn)||d<mn[m]) mn[m]=d; n[m]++} END{for(m in n) printf "%s n=%d minlag=%.1f maxlag=%.1f\n", m, n[m], mn[m], mx[m]}' | sort
```
```
10:27 n=1 minlag=-2.2 maxlag=0.0
10:28 n=39 minlag=-0.4 maxlag=2.2
10:29 n=17 minlag=-5.3 maxlag=13.5
10:30 n=16 minlag=-0.4 maxlag=20.1
10:31 n=43 minlag=0.4 maxlag=21.1
10:32 n=18 minlag=0.7 maxlag=6.2
```

The window is the one in which the three joining SVs and three validators upload and vet their DARs (section 6).
The splitwellValidator `TopupMemberTrafficTrigger` WARNs (`SUBMISSION_ALREADY_IN_FLIGHT`, 9 from 10:31:43 to
10:32:16) are the same slow confirmations seen from a validator. Why the synchronizer was slow is not in the
artifacts (no host or Postgres metrics); not chased further for a duplicate.

## 6. DAR uploads: dedup of 11299 is in, 65 per SV, no repeats

```
zcat canton_network_test.clog.gz | grep -a 'config=291c8f8b/' | grep -a 'Sending upload dar for' | sed -E 's/.*"@timestamp":"([^"]+)".*Sending upload dar for ([^"]+)".*config=291c8f8b\/([A-Za-z]+=[A-Za-z0-9]+).*/\3 \1 \2/' | awk '{n[$1]++; c[$1" "$3]++; if(!f[$1]) f[$1]=$2; l[$1]=$2} END{for(k in n){d=0;r=0; for(j in c){split(j,a," "); if(a[1]==k){d++; if(c[j]>1) r+=c[j]-1}} print f[k], l[k], k, "uploads", n[k], "distinct", d, "repeats", r}}' | sort
```
```
2026-11-19T10:28:09.738Z 2026-11-19T10:28:34.838Z SV=sv1 uploads 65 distinct 65 repeats 0
2026-11-19T10:28:59.933Z 2026-11-19T10:30:07.984Z SV=sv3 uploads 65 distinct 65 repeats 0
2026-11-19T10:29:03.799Z 2026-11-19T10:30:09.135Z validator=bobValidator uploads 58 distinct 58 repeats 0
2026-11-19T10:29:06.051Z 2026-11-19T10:30:22.562Z SV=sv2 uploads 65 distinct 65 repeats 0
2026-11-19T10:29:08.259Z 2026-11-19T10:30:28.159Z SV=sv4 uploads 65 distinct 65 repeats 0
2026-11-19T10:29:21.559Z 2026-11-19T10:30:43.607Z validator=aliceValidator uploads 58 distinct 58 repeats 0
2026-11-19T10:29:58.614Z 2026-11-19T10:31:35.191Z validator=splitwellValidator uploads 59 distinct 59 repeats 0
```

sv1's 65 uploads took 25 s (21 s in 11550, 45 s in 11558).

## 7. Later environments: no namespace reset after the setup failure, sv1 cannot allocate the DSO party

```
zcat canton_network_test.clog.gz | grep -a -c 'has been reset'
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -a -o -E 'PartyToParticipant\(partyId = DSO-[0-9a-f]+' | sort | uniq -c
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -a 'PartyToParticipant(partyId = DSO-' | head -1 | grep -a -o -E 'rejectionReason = [^)]{0,60}'
```
```
0
     82 PartyToParticipant(partyId = DSO-626ea0cf
     86 PartyToParticipant(partyId = DSO-7fb2000c
     84 PartyToParticipant(partyId = DSO-a0e3b451
     84 PartyToParticipant(partyId = DSO-a99fe932
     84 PartyToParticipant(partyId = DSO-c4b67f40
     86 PartyToParticipant(partyId = DSO-ffc579ad
rejectionReason = Not fully authorized\n
```

6 DSO parties = the 6 later environments (a0e3b451 Splitwell, c4b67f40 WalletPayment, 7fb2000c
WalletTransferOffers per the logger names in the test log; the other three are Wallet, AnsAuth0 and
WalletNoDevNet, not mapped one by one). Same blast radius as 11373 section 7, 11550 section 8, 11558 section 6.

## 8. Duplicate check and frequency

```
for n in 11373 11550 11558; do gh issue view $n -R DACH-NY/cn-test-failures --json number,title,state,stateReason,assignees,closedAt --jq '[.number,.state,.stateReason,.closedAt,.title,([.assignees[].login]|join(","))]|@tsv'; done
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "frontend-wall-clock-time (2)" created:>=2026-11-11' --jq '.items[] | "\(.number) \(.state)/\(.state_reason) \(.created_at) \(.title[0:90])"'
```
```
11373	OPEN			SvFrontendIntegrationTest setup: sv3 init outlasts the 5 min wait on a slow runner, later suites fail without namespace reset	
11550	CLOSED	DUPLICATE	2026-11-17T21:23:50Z	GHA Run 44151467568 : job frontend-wall-clock-time (2) in scala_test Failed :fire:	pawelperek-da
11558	CLOSED	DUPLICATE	2026-11-18T08:19:57Z	GHA Run 44241658562 : job frontend-wall-clock-time (2) in scala_test Failed :fire:	pawelperek-da
11654 open/null 2026-11-19T11:06:59Z GHA Run 44500851782 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11373 open/null 2026-11-11T18:38:19Z SvFrontendIntegrationTest setup: sv3 init outlasts the 5 min wait on a slow runner, later 
11630 closed/duplicate 2026-11-18T15:21:44Z GHA Run 44327510557 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11595 closed/duplicate 2026-11-18T15:00:15Z GHA Run 44328806873 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11564 closed/duplicate 2026-11-18T12:59:51Z GHA Run 44301172116 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11558 closed/duplicate 2026-11-18T05:55:41Z GHA Run 44241658562 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11550 closed/duplicate 2026-11-17T19:25:19Z GHA Run 44151467568 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11353 closed/completed 2026-11-11T05:45:16Z SvFrontendIntegrationTest Featured App Right: appKind needs splice-amulet 0.1.25, test unt
```

Family AM confirming grep for the 11373 shape (known-families.md): `Timeout while waiting for initialization of
sv<N>` in `Running setup` with the node's `app initialization` phases still advancing; compare `Finished loading
package signatures` minus `Loading` against about 30 s. Both parts hold (sections 2 and 4); the comparison puts
this hit on the 11550 side (runner CPU at baseline, synchronizer-bound steps slow), section 3. 11373 is open,
unassigned and not closed as a duplicate. 11564, 11595 and 11630 fall into the 11566 sibling range of the
2026-11-18 compile break (family BH) and were not opened here. Fourth setup timeout of this suite on this shard in
9 days (11373 on 11-11, 11550 on 11-17, 11558 on 11-18, this one on 11-19).

## Verdict

Duplicate of 11373: same suite, shard and wait (`EnvironmentDefinition.waitForNodeInitialization`, sv3 checked
first, 5 min from 0.4 s after its start), sv3 still advancing at the timeout, and the same blast radius in the
later environments. Test flake; commit 77c89208ee (#8524, cluster backup scripts) does not touch this path and
attempt 2 of the same run passed. The fixes proposed in 11373 that remain (one overall init deadline across all
nodes sized above the slowest observed init, and the namespace reset after a failed setup) are still not on main.
No fix branch: duplicate.

Not verified: why synchronizer round trips were slow from 10:29 to 10:31 (no host or Postgres metrics in the
artifact, Canton log not analysed beyond section 7); the `-runner` artifact (14524356535) was downloaded but not
read; the three later-environment config ids not mapped to suites; the failure rate against the number of runs of
this shard.
