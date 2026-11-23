# 11558 - frontend-wall-clock-time (2): SvFrontendIntegrationTest setup, sv3 not initialized within the 5 min wait on a slow runner; 5 later suites fail sv1 DSO party allocation (run 44241658562)

DUPLICATE of 11373 (family AM shape: SV init slower than a fixed wait; family B setup-failure blast radius for the
later suites). Scheduled workflow "Integration tests against mainnet Daml version" on main, sha 49d03025a2 (#8083),
job `frontend-wall-clock-time (2)`: 6 suites aborted, 0 tests ran, no checkErrors output. In the first suite,
SvFrontendIntegrationTest, the environment setup's sequential `waitForNodeInitialization` checked sv3 first; its
5 min ran from about 05:22:54.95 (0.6 s after the last node started, 16 s after sv3 started) to 05:27:54.953. sv3
was still advancing: `Onboarding sequencer` at 05:27:53.956, 1 s before the timeout, `Onboarding mediator` at
05:28:06.737 during the shutdown. This is the slow-runner shape of 11373, not the single slow step of 11550: every
phase was about 2x slower than the passing 2026-11-10 baseline of the 11373 packet (CPU-only package signature
loading 61 s vs 30 s, sv1 init 2m38s vs 1m36s, joining-SV package vetting 100-113 s vs 68 s in 11550), so no SV
other than sv1 was initialized after 5 min. The DAR dedup (#8467) is in (65 uploads per SV, 0 repeats). The setup
failure skipped the namespace reset (zero `has been reset` lines), so in each of the 5 later suites sv1 could not
allocate the new DSO party (`Not fully authorized`) and timed out after 5 min. Not specific to the mainnet Daml
version: the same shard of this nightly passed on 2026-11-14, 11-16 and 11-17.

- Run: https://github.com/canton-network/splice/actions/runs/44241658562, main 49d03025a2 ("[ci] Deflake bulk
  storage consensus disagreement test (#8083)"), event schedule, job 134591917775
  `build / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`, 05:08:32 to 05:55:52 UTC, runner
  self-hosted-k8s-large-runner-99nfj. The run's other non-successful job, `docker-compose (1)` (134592078545,
  cancelled), is not part of this ref.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: test (setup wait budget vs joining-SV init time on a slow runner).

## 1. Job and failures

```
gh run view 44241658562 -R canton-network/splice --json jobs,headSha,headBranch,workflowName,createdAt,conclusion,attempt | jq -c '{headSha,headBranch,workflowName,createdAt,conclusion,attempt, jobs:[.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{name,databaseId,conclusion,startedAt,completedAt}]}'
```
```
{"headSha":"49d03025a2a7bc5f60244067cccf6c7ac18c1852","headBranch":"main","workflowName":"Integration tests against mainnet Daml version","createdAt":"2026-11-18T05:04:30Z","conclusion":"failure","attempt":1,"jobs":[{"name":"build / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)","databaseId":134591917775,"conclusion":"failure","startedAt":"2026-11-18T05:08:32Z","completedAt":"2026-11-18T05:55:52Z"},{"name":"build / scala_test_docker_compose / docker-compose (1)","databaseId":134592078545,"conclusion":"cancelled","startedAt":"2026-11-18T05:10:01Z","completedAt":"2026-11-18T06:05:02Z"}]}
```

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134591917775/logs > log/11558/job.log
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11558/job.log > log/11558/job.clean.log
grep -a -E 'Run completed|Tests: succeeded|SUITES ABORTED' log/11558/job.clean.log
grep -a 'ABORTED \*\*\*' log/11558/job.clean.log | sed 's/.*tests\.//' | head -6
sed -n '13506,13514p' log/11558/job.clean.log
```
```
[info] Run completed in 32 minutes, 55 seconds.
[info] Tests: succeeded 0, failed 0, canceled 0, ignored 0, pending 0
[info] *** 6 SUITES ABORTED ***
SvFrontendIntegrationTest *** ABORTED ***
SplitwellFrontendIntegrationTest *** ABORTED ***
WalletTransactionHistoryFrontendIntegrationTest *** ABORTED ***
WalletTransferOffersFrontendIntegrationTest *** ABORTED ***
WalletFrontendIntegrationTest *** ABORTED ***
WalletSubscriptionsFrontendIntegrationTest *** ABORTED ***
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

The wait is unchanged on main (6bcd71db6c; sequential, 5 min per node):

```
git grep -n -A1 'def waitForNodeInitialization' origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala
git show 49d03025a2:nix/canton-sources.json | grep -m1 version
```
```
origin/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala:664:  def waitForNodeInitialization(env: SpliceConsoleEnvironment): Unit =
origin/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala-665-    env.amuletNodes.local.foreach(_.waitForInitialization())
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 2. Suite timeline: one 5 min init timeout per suite (confirming grep of 11373, part 1)

Artifact `logs-frontend-wall-clock-time-2` (id 14401854427) to `log/11558/logs-frontend-wall-clock-time-2/`.

```
cd log/11558/logs-frontend-wall-clock-time-2
zcat canton_network_test.clog.gz | grep -a -E 'Starting test suite|Timeout while waiting for initialization|Test (succeeded|failed)|has been reset' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/'
```
```
2026-11-18T05:21:56.943Z Starting test suite 'SvFrontendIntegrationTest'...
2026-11-18T05:27:54.953Z Timeout while waiting for initialization of sv3
2026-11-18T05:28:10.890Z Starting test suite 'SplitwellFrontendIntegrationTest'...
2026-11-18T05:33:28.810Z Timeout while waiting for initialization of sv1
2026-11-18T05:33:34.506Z Starting test suite 'WalletTransactionHistoryFrontendIntegrationTest'...
2026-11-18T05:38:51.693Z Timeout while waiting for initialization of sv1
2026-11-18T05:38:56.089Z Starting test suite 'WalletTransferOffersFrontendIntegrationTest'...
2026-11-18T05:44:09.867Z Timeout while waiting for initialization of sv1
2026-11-18T05:44:12.018Z Starting test suite 'WalletFrontendIntegrationTest'...
2026-11-18T05:49:25.819Z Timeout while waiting for initialization of sv1
2026-11-18T05:49:30.058Z Starting test suite 'WalletSubscriptionsFrontendIntegrationTest'...
2026-11-18T05:54:44.446Z Timeout while waiting for initialization of sv1
```

## 3. First suite: node start and init-finish times; sv3 was checked first; signature loading 61 s

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=bce80fd6/' | grep -a -E 'app initialization: (Starting initialization|Initialize app finished)' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/' | awk '!seen[substr($0,26)]++' | sort
```
```
2026-11-18T05:22:16.650Z providerSplitwellBackend app initialization: Starting initialization
2026-11-18T05:22:19.991Z sv1Validator app initialization: Starting initialization
2026-11-18T05:22:22.582Z splitwellValidator app initialization: Starting initialization
2026-11-18T05:22:25.727Z sv2 app initialization: Starting initialization
2026-11-18T05:22:28.691Z bobValidator app initialization: Starting initialization
2026-11-18T05:22:31.689Z sv1Scan app initialization: Starting initialization
2026-11-18T05:22:34.682Z aliceValidator app initialization: Starting initialization
2026-11-18T05:22:37.740Z sv1 app initialization: Starting initialization
2026-11-18T05:22:38.249Z sv4Validator app initialization: Starting initialization
2026-11-18T05:22:38.666Z sv2Validator app initialization: Starting initialization
2026-11-18T05:22:39.036Z sv3 app initialization: Starting initialization
2026-11-18T05:22:42.323Z sv4 app initialization: Starting initialization
2026-11-18T05:22:45.366Z sv4Scan app initialization: Starting initialization
2026-11-18T05:22:48.434Z sv3Scan app initialization: Starting initialization
2026-11-18T05:22:51.490Z sv2Scan app initialization: Starting initialization
2026-11-18T05:22:54.343Z sv3Validator app initialization: Starting initialization
2026-11-18T05:25:15.364Z sv1 app initialization: Initialize app finished after PT2M37.613419531S
2026-11-18T05:25:17.144Z sv1Scan app initialization: Initialize app finished after PT2M45.454648025S
2026-11-18T05:25:25.118Z sv1Validator app initialization: Initialize app finished after PT3M4.850572129S
2026-11-18T05:27:32.843Z aliceValidator app initialization: Initialize app finished after PT4M58.156160244S
2026-11-18T05:27:42.769Z bobValidator app initialization: Initialize app finished after PT5M14.067470666S
2026-11-18T05:27:45.388Z sv3Scan app initialization: Initialize app finished after PT4M56.954055895S
2026-11-18T05:27:49.243Z sv4Scan app initialization: Initialize app finished after PT5M3.87707485S
2026-11-18T05:27:52.455Z sv2Scan app initialization: Initialize app finished after PT5M0.964729323S
```

The sv3 timeout at 05:27:54.953 means its 5 min started at about 05:22:54.95, 0.6 s after the last node started
(05:22:54.343) and before any node was initialized, so sv3 was the first node checked. No SV app other than sv1
finished. sv1 took 2m38s (baseline 2026-11-10: 1m36s; 11373: 2m48s; 11550: 1m49s).

The CPU-only step that separates a slow runner (11373: 57 s) from a slow step (11550: 30.9 s):

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=bce80fd6/' | grep -a -E '(Loading|Finished loading) package signatures' | grep -a -E 'SV=sv[13]"' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,70}).*(SV=sv[0-9]).*/\1 \3 \2/'
```
```
2026-11-18T05:22:56.141Z SV=sv3 Loading package signatures from resources
2026-11-18T05:22:57.418Z SV=sv1 Loading package signatures from resources
2026-11-18T05:23:58.522Z SV=sv1 Finished loading package signatures from resources
2026-11-18T05:23:58.522Z SV=sv3 Finished loading package signatures from resources
```

61.1 s, twice the 30 s baseline.

## 4. sv3 progressed until the end (confirming grep of 11373, part 2)

```
T=canton_network_test.clog.gz; for sv in sv2 sv4 sv3; do zcat $T | grep -a "config=bce80fd6/SV=$sv\"" | grep -a -E '"(Onboarding sequencer|Onboarded sequencer|Initializing sequencer|Onboarding mediator|Initializing mediator|Requesting to be onboarded via the sponsor SV|Packages vetting completed|Vetting packages based on state from sponsor)[^"]*"' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,45}).*/\1 \2/' | sed "s/^/$sv /"; done
```
```
sv2 2026-11-18T05:25:25.792Z Vetting packages based on state from sponsor
sv2 2026-11-18T05:27:15.265Z Packages vetting completed
sv2 2026-11-18T05:27:17.197Z Requesting to be onboarded via the sponsor SV
sv4 2026-11-18T05:25:43.954Z Vetting packages based on state from sponsor
sv4 2026-11-18T05:27:23.561Z Packages vetting completed
sv4 2026-11-18T05:27:24.045Z Requesting to be onboarded via the sponsor SV
sv4 2026-11-18T05:27:54.544Z Onboarding sequencer
sv4 2026-11-18T05:27:56.680Z Onboarding sequencer SEQ::sv4::1220375cbc03..
sv3 2026-11-18T05:25:27.369Z Vetting packages based on state from sponsor
sv3 2026-11-18T05:27:19.988Z Packages vetting completed
sv3 2026-11-18T05:27:20.563Z Requesting to be onboarded via the sponsor SV
sv3 2026-11-18T05:27:53.956Z Onboarding sequencer
sv3 2026-11-18T05:27:55.551Z Onboarding sequencer SEQ::sv3::12200fb20a23..
sv3 2026-11-18T05:27:59.447Z Onboarded sequencer SEQ::sv3::12200fb20a23...
sv3 2026-11-18T05:27:59.447Z Initializing sequencer SEQ::sv3::12200fb20a23
sv3 2026-11-18T05:28:06.737Z Onboarding mediator
```

The joining SVs could only start vetting after sv1 was up (05:25:15), 2m36s into sv3's 5 min; vetting took 110 s
(sv2), 100 s (sv4), 113 s (sv3) against 68 s in 11550; sv3 requested onboarding 34 s before the timeout. The sv3
sequencer init itself was quick (7.3 s), so this is not the 11550 variant. sv3 kept going after the timeout until
the environment shut it down (05:28:10.599 `Successfully completed shutdown of sv3`).

## 5. DAR uploads: dedup of 11299 is in, 65 per SV, no repeats

```
zcat canton_network_test.clog.gz | grep -a 'config=bce80fd6/' | grep -a 'Sending upload dar for' | sed -E 's/.*"@timestamp":"([^"]+)".*Sending upload dar for ([^"]+)".*config=bce80fd6\/([A-Za-z]+=[A-Za-z0-9]+).*/\3 \1 \2/' | awk '{n[$1]++; c[$1" "$3]++; if(!f[$1]) f[$1]=$2; l[$1]=$2} END{for(k in n){d=0;r=0; for(j in c){split(j,a," "); if(a[1]==k){d++; if(c[j]>1) r+=c[j]-1}} print f[k], l[k], k, "uploads", n[k], "distinct", d, "repeats", r}}' | sort
```
```
2026-11-18T05:24:09.762Z 2026-11-18T05:24:54.749Z SV=sv1 uploads 65 distinct 65 repeats 0
2026-11-18T05:25:25.872Z 2026-11-18T05:27:06.984Z SV=sv2 uploads 65 distinct 65 repeats 0
2026-11-18T05:25:27.546Z 2026-11-18T05:27:10.873Z SV=sv3 uploads 65 distinct 65 repeats 0
2026-11-18T05:25:39.791Z 2026-11-18T05:27:00.870Z validator=aliceValidator uploads 58 distinct 58 repeats 0
2026-11-18T05:25:44.154Z 2026-11-18T05:27:17.551Z SV=sv4 uploads 65 distinct 65 repeats 0
2026-11-18T05:26:20.682Z 2026-11-18T05:27:24.442Z validator=bobValidator uploads 58 distinct 58 repeats 0
2026-11-18T05:27:15.284Z 2026-11-18T05:28:03.776Z validator=splitwellValidator uploads 58 distinct 58 repeats 0
```

sv1's 65 uploads took 45 s here against 21 s in 11550, the same factor of 2.

## 6. Later suites: no namespace reset after the setup failure, sv1 cannot allocate the DSO party

```
zcat canton_network_test.clog.gz | grep -a -c 'has been reset'
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -a -o -E 'PartyToParticipant\(partyId = DSO-[0-9a-f]+' | sort | uniq -c
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -a 'PartyToParticipant(partyId = DSO-' | head -1 | grep -a -o -E 'rejectionReason = [^)]{0,60}'
```
```
0
     82 PartyToParticipant(partyId = DSO-37d6b992
     83 PartyToParticipant(partyId = DSO-39d57f83
     83 PartyToParticipant(partyId = DSO-51ca3d9d
     83 PartyToParticipant(partyId = DSO-6ba62734
     82 PartyToParticipant(partyId = DSO-6c5e8417
rejectionReason = Not fully authorized\n
```

5 DSO parties = the 5 later suites (the suffix is the suite's config id, section 2 of the timeline in the test
log: 6c5e8417 Splitwell, 51ca3d9d WalletTransactionHistory, 37d6b992 WalletTransferOffers, 6ba62734 Wallet,
39d57f83 WalletSubscriptions). Same blast radius as 11373 section 7 and 11550 section 8.

## 7. Duplicate check and frequency

```
for n in 11373 11550; do gh issue view $n -R DACH-NY/cn-test-failures --json number,state,stateReason,title,assignees --jq '"\(.number) \(.state)/\(.stateReason) assignees=\([.assignees[].login]|join(",")) \(.title[0:110])"'; done
for r in 44021473992 43807916670 43559647676; do gh api "repos/canton-network/splice/actions/runs/$r/jobs?per_page=100" --paginate --jq '.jobs[]|select(.name|test("frontend-wall-clock-time \\(2\\)"))|"\(.run_id) \(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)"'; done
```
```
11373 OPEN/ assignees= SvFrontendIntegrationTest setup: sv3 init outlasts the 5 min wait on a slow runner, later suites fail without 
11550 CLOSED/DUPLICATE assignees=pawelperek-da GHA Run 44151467568 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
44021473992 success 2026-11-17T05:26:32Z 2026-11-17T06:04:11Z self-hosted-k8s-large-runner-pf2db
43807916670 success 2026-11-16T05:08:45Z 2026-11-16T05:44:46Z self-hosted-k8s-large-runner-gpknr
43559647676 success 2026-11-14T05:28:31Z 2026-11-14T06:02:20Z self-hosted-k8s-large-runner-8gb4n
```

(The 2026-11-15 run 43679441242 answered HTTP 502 on the jobs API and was not retried.) Family AM confirming grep
for the 11373 shape (known-families.md): `Timeout while waiting for initialization of sv<N>` in `Running setup`
with the node's `app initialization` phases still advancing; compare `Finished loading package signatures` minus
`Loading` against about 30 s. All hold (sections 2, 3, 4). 11373 is open, unassigned and not closed as a
duplicate. Third setup timeout of this suite on this shard in 8 days (11373 on 11-11, 11550 on 11-17, this one on
11-18), the last two within 11 hours.

## Verdict

Duplicate of 11373: same suite, shard, wait (`EnvironmentDefinition.waitForNodeInitialization`, sv3 checked first,
5 min from 16 s after its start), sv3 still advancing at the timeout, uniformly slow runner (signature loading
61 s vs 30 s), and the same blast radius in the later suites. Test flake; commit 49d03025a2 (#8083, a bulk storage
test) does not touch this path, and the mainnet Daml version is not the trigger (same nightly shard green on the
three earlier days checked). The fixes proposed in 11373 that remain (one overall init deadline across all nodes
sized above the slowest observed init, and the namespace reset after a failed setup) are still not on main. No fix
branch: duplicate.

Not verified: why the runner was 2x slower (no host metrics in the artifact; runner host logs not read); the
`-runner` artifact (14402800763) was not downloaded; the failure rate against the number of runs.
