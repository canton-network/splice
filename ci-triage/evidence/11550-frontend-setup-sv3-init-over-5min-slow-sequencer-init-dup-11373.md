# 11550 - frontend-wall-clock-time (2): SvFrontendIntegrationTest setup, sv3 not initialized within the 5 min wait (sv3 sequencer init 57.6 s on a slow database); 4 later suites fail sv1 DSO party allocation (run 44151467568)

DUPLICATE of 11373 (family AM shape: SV init slower than a fixed wait; family B setup-failure blast radius for the
later suites). Post-merge CI on main, sha c20fdd262b (#8520), job `frontend-wall-clock-time (2)`: 5 suites aborted,
0 tests ran, no checkErrors output. In the first suite, SvFrontendIntegrationTest, the environment setup's
sequential `waitForNodeInitialization` checked sv3 first; its 5 min ran from about 18:57:46.3 (0.4 s after the last
node started) to 19:02:46.284. sv3 was still advancing: it had just finished initializing its sequencer
(19:02:46.199) and logged `Onboarding mediator` at 19:02:46.229, 55 ms before the timeout. Unlike 11373 the runner
was not uniformly slow (package signature loading 30.9 s, sv1 1m49s, sv2 4m13s, sv4 3m33s, all at the level of the
passing 2026-11-10 baseline in the 11373 packet). The overrun came from one step: Canton's
`InitializeSequencerFromOnboardingStateV2` for sv3 took 57.6 s, against 3.6 s (sv2) and 2.6 s (sv4) one minute
earlier, because database writes were slow from 19:01:03 on (topology snapshot import 25.5 s vs 0.5-0.75 s, 29
Flyway migrations over 23 s vs under 3 s; the unrelated single-node splitwell sequencers ordered 2x-3x slower in
the same window). The DAR dedup of 11299 (#8467) is in this sha (65 uploads per SV, 0 repeats), so the first of the
three fixes proposed in 11373 is done and did not prevent this. The setup failure skipped the namespace reset (zero
`has been reset` lines), so in each of the 4 later suites sv1 could not allocate the new DSO party
(`Not fully authorized`) and timed out after 5 min.

- Run: https://github.com/canton-network/splice/actions/runs/44151467568, main c20fdd262b ("Fix duplicate CloudSQL
  backups in node-backup.sh (#8520)"), job 134281215494
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`, 18:47:47 to 19:25:29 UTC.
  The run's other non-successful job, `docker-compose (1)` (134281154747, cancelled), is not part of this ref.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: test (setup wait budget vs joining-SV init time); cause of the slow database not in the artifacts.

## 1. Job and failures

```
gh run view 44151467568 -R canton-network/splice --json headSha,headBranch,event,createdAt,conclusion,jobs --jq '{headSha,headBranch,event,createdAt,conclusion,failed:[.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{name,databaseId,conclusion,startedAt,completedAt}]}'
```
```
{"conclusion":"failure","createdAt":"2026-11-17T18:46:35Z","event":"push","failed":[{"completedAt":"2026-11-17T19:42:33Z","conclusion":"cancelled","databaseId":134281154747,"name":"ci / scala_test_docker_compose / docker-compose (1)","startedAt":"2026-11-17T18:47:33Z"},{"completedAt":"2026-11-17T19:25:29Z","conclusion":"failure","databaseId":134281215494,"name":"ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)","startedAt":"2026-11-17T18:47:47Z"}],"headBranch":"main","headSha":"c20fdd262b51f999dcfffdd84b124fb1edb9f3b6"}
```

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134281215494/logs > log/11550/job.log
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11550/job.log > log/11550/job.clean.log
grep -a -E 'Run completed|Tests: succeeded|SUITES ABORTED' log/11550/job.clean.log
grep -a 'ABORTED \*\*\*' log/11550/job.clean.log | sed 's/.*tests\.//' | head -5
sed -n '13489,13497p' log/11550/job.clean.log
```
```
[info] Run completed in 27 minutes, 50 seconds.
[info] Tests: succeeded 0, failed 0, canceled 0, ignored 0, pending 0
[info] *** 5 SUITES ABORTED ***
SvFrontendIntegrationTest *** ABORTED ***
SplitwellFrontendIntegrationTest *** ABORTED ***
TokenStandardWalletTransfersFrontendIntegrationTest *** ABORTED ***
WalletPaymentFrontendIntegrationTest *** ABORTED ***
AllocationsFrontendIntegrationTest *** ABORTED ***
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

The wait is unchanged on main (sequential, 5 min per node):

```
git grep -n -A1 'def waitForNodeInitialization' origin/main -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala
git show c20fdd262b:nix/canton-sources.json | grep -m1 version
```
```
origin/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala:664:  def waitForNodeInitialization(env: SpliceConsoleEnvironment): Unit =
origin/main:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/EnvironmentDefinition.scala-665-    env.amuletNodes.local.foreach(_.waitForInitialization())
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 2. Suite timeline: one 5 min init timeout per suite (confirming grep of 11373, part 1)

Artifact `logs-frontend-wall-clock-time-2` (id 14361438233) to `log/11550/logs-frontend-wall-clock-time-2/`.

```
cd log/11550/logs-frontend-wall-clock-time-2
zcat canton_network_test.clog.gz | grep -a -E 'Starting test suite|Timeout while waiting for initialization|Test (succeeded|failed)|has been reset' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/'
```
```
2026-11-17T18:56:36.246Z Starting test suite 'SvFrontendIntegrationTest'...
2026-11-17T19:02:46.284Z Timeout while waiting for initialization of sv3
2026-11-17T19:02:53.160Z Starting test suite 'SplitwellFrontendIntegrationTest'...
2026-11-17T19:08:33.847Z Timeout while waiting for initialization of sv1
2026-11-17T19:08:33.915Z Starting test suite 'TokenStandardWalletTransfersFrontendIntegrationTest'...
2026-11-17T19:13:50.996Z Timeout while waiting for initialization of sv1
2026-11-17T19:13:51.047Z Starting test suite 'WalletPaymentFrontendIntegrationTest'...
2026-11-17T19:19:07.963Z Timeout while waiting for initialization of sv1
2026-11-17T19:19:08.018Z Starting test suite 'AllocationsFrontendIntegrationTest'...
2026-11-17T19:24:24.912Z Timeout while waiting for initialization of sv1
```

## 3. First suite: node start and init-finish times; sv3 was checked first

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=8bf6a8e2/' | grep -a -E 'app initialization: (Starting initialization|Initialize app finished)' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,110}).*/\1 \2/' | awk '!seen[substr($0,26)]++' | sort
```
```
2026-11-17T18:57:10.773Z sv1Scan app initialization: Starting initialization
2026-11-17T18:57:11.452Z bobValidator app initialization: Starting initialization
2026-11-17T18:57:14.432Z sv1 app initialization: Starting initialization
2026-11-17T18:57:17.965Z aliceValidator app initialization: Starting initialization
2026-11-17T18:57:20.597Z splitwellValidator app initialization: Starting initialization
2026-11-17T18:57:23.561Z sv2 app initialization: Starting initialization
2026-11-17T18:57:26.449Z providerSplitwellBackend app initialization: Starting initialization
2026-11-17T18:57:29.518Z sv1Validator app initialization: Starting initialization
2026-11-17T18:57:29.889Z sv3 app initialization: Starting initialization
2026-11-17T18:57:30.278Z sv4 app initialization: Starting initialization
2026-11-17T18:57:30.674Z sv4Validator app initialization: Starting initialization
2026-11-17T18:57:33.909Z sv3Scan app initialization: Starting initialization
2026-11-17T18:57:36.925Z sv2Validator app initialization: Starting initialization
2026-11-17T18:57:39.899Z sv3Validator app initialization: Starting initialization
2026-11-17T18:57:42.918Z sv2Scan app initialization: Starting initialization
2026-11-17T18:57:45.913Z sv4Scan app initialization: Starting initialization
2026-11-17T18:59:02.354Z sv1Scan app initialization: Initialize app finished after PT1M51.579611978S
2026-11-17T18:59:03.652Z sv1 app initialization: Initialize app finished after PT1M49.213281127S
2026-11-17T18:59:21.971Z sv1Validator app initialization: Initialize app finished after PT1M52.448620365S
2026-11-17T19:00:32.747Z bobValidator app initialization: Initialize app finished after PT3M21.214443565S
2026-11-17T19:00:37.842Z sv2Scan app initialization: Initialize app finished after PT2M54.92307039S
2026-11-17T19:00:39.867Z sv4Scan app initialization: Initialize app finished after PT2M53.953642764S
2026-11-17T19:00:40.053Z aliceValidator app initialization: Initialize app finished after PT3M22.083578596S
2026-11-17T19:00:53.138Z sv3Scan app initialization: Initialize app finished after PT3M19.22901848S
2026-11-17T19:01:03.062Z sv4 app initialization: Initialize app finished after PT3M32.779670672S
2026-11-17T19:01:28.308Z providerSplitwellBackend app initialization: Initialize app finished after PT4M1.858643048S
2026-11-17T19:01:37.052Z sv2 app initialization: Initialize app finished after PT4M13.486632999S
2026-11-17T19:01:38.458Z sv4Validator app initialization: Initialize app finished after PT4M7.779541354S
2026-11-17T19:02:10.260Z splitwellValidator app initialization: Initialize app finished after PT4M49.659077657S
2026-11-17T19:02:37.254Z sv2Validator app initialization: Initialize app finished after PT5M0.324890581S
```

The sv3 timeout at 19:02:46.284 means its 5 min started at about 18:57:46.3, 0.4 s after the last node started
(18:57:45.913) and before any node was initialized (first: 18:59:02), so sv3 was the first node checked and got 5
min from 16.4 s after its own start. sv3 and sv3Validator never finished. Compared with the passing baseline of
2026-11-10 in the 11373 packet (sv1 1m36s, sv2 4m12s, sv4 3m40s, sv3 3m48s) sv1, sv2 and sv4 are at baseline speed.

The CPU-only step that was 1.8x slower in 11373 (57 s) is at its normal 30 s here:

```
zcat canton_network_test.clog.gz | grep -a 'SvFrontendIntegrationTest/config=8bf6a8e2/' | grep -a -E '(Loading|Finished loading) package signatures' | grep -a -E 'SV=sv[13]"' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,70}).*(SV=sv[0-9]).*/\1 \3 \2/'
```
```
2026-11-17T18:57:47.297Z SV=sv3 Loading package signatures from resources
2026-11-17T18:57:48.191Z SV=sv1 Loading package signatures from resources
2026-11-17T18:58:18.216Z SV=sv1 Finished loading package signatures from resources
2026-11-17T18:58:18.216Z SV=sv3 Finished loading package signatures from resources
```

## 4. sv3 progressed until the end (confirming grep of 11373, part 2); its sequencer init took 58 s

Join steps of the three joining SVs:

```
T=canton_network_test.clog.gz; for sv in sv2 sv4 sv3; do zcat $T | grep -a "config=8bf6a8e2/SV=$sv\"" | grep -a -E '"(Onboarding sequencer|Onboarded sequencer|Initializing sequencer|Onboarding mediator|Initializing mediator|Requesting to be onboarded via the sponsor SV|Packages vetting completed|Vetting packages based on state from sponsor)[^"]*"' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,45}).*/\1 \2/' | sed "s/^/$sv /"; done
```
```
sv2 2026-11-17T18:59:07.369Z Vetting packages based on state from sponsor
sv2 2026-11-17T19:00:14.879Z Packages vetting completed
sv2 2026-11-17T19:00:16.071Z Requesting to be onboarded via the sponsor SV
sv2 2026-11-17T19:00:45.747Z Onboarding sequencer
sv2 2026-11-17T19:00:47.140Z Onboarding sequencer SEQ::sv2::122086bddde7... throu
sv2 2026-11-17T19:00:49.939Z Onboarded sequencer SEQ::sv2::122086bddde7...
sv2 2026-11-17T19:00:49.939Z Initializing sequencer SEQ::sv2::122086bddde7...
sv2 2026-11-17T19:00:53.543Z Onboarding mediator
sv2 2026-11-17T19:01:03.993Z Initializing mediator MED::sv2::12200d448cee...
sv4 2026-11-17T18:59:13.470Z Vetting packages based on state from sponsor
sv4 2026-11-17T19:00:21.946Z Packages vetting completed
sv4 2026-11-17T19:00:22.278Z Requesting to be onboarded via the sponsor SV
sv4 2026-11-17T19:00:44.190Z Onboarding sequencer
sv4 2026-11-17T19:00:45.141Z Onboarding sequencer SEQ::sv4::12204da7d86d... throu
sv4 2026-11-17T19:00:51.592Z Onboarded sequencer SEQ::sv4::12204da7d86d...
sv4 2026-11-17T19:00:51.592Z Initializing sequencer SEQ::sv4::12204da7d86d...
sv4 2026-11-17T19:00:54.242Z Onboarding mediator
sv4 2026-11-17T19:00:59.162Z Initializing mediator MED::sv4::1220a7a9ca37...
sv3 2026-11-17T18:59:11.848Z Vetting packages based on state from sponsor
sv3 2026-11-17T19:00:20.465Z Packages vetting completed
sv3 2026-11-17T19:00:20.852Z Requesting to be onboarded via the sponsor SV
sv3 2026-11-17T19:01:21.930Z Onboarding sequencer
sv3 2026-11-17T19:01:36.952Z Onboarding sequencer SEQ::sv3::1220dcc82e4a... throu
sv3 2026-11-17T19:01:48.540Z Onboarded sequencer SEQ::sv3::1220dcc82e4a...
sv3 2026-11-17T19:01:48.540Z Initializing sequencer SEQ::sv3::1220dcc82e4a...
sv3 2026-11-17T19:02:46.229Z Onboarding mediator
```

sv3 from request to `Onboarding sequencer`: 61 s (sv2 30 s, sv4 22 s); `Initializing sequencer` to
`Onboarding mediator`: 57.7 s (sv2 3.6 s, sv4 2.7 s). The last sv3 lines (INFO, SV app):

```
2026-11-17T19:02:46.229Z Waiting until Mediator has been granted unlimited traffic
2026-11-17T19:02:46.302Z The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable error (full stack trace omitted): NOT_FOUND: No traffic state found for member MED::sv3::12201f8c21d1...
2026-11-17T19:02:51.478Z Stopping sv3 node
2026-11-17T19:02:51.480Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down
2026-11-17T19:02:51.569Z sv3 app initialization: Ignoring initialization failure, we are actually shutting down. Message was: sv3 app initialization: Initialize app failed
```

## 5. Canton side: InitializeSequencerFromOnboardingStateV2 57.6 s for sv3, 3.6 s and 2.6 s for sv2 and sv4

```
zcat canton.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-17T19:00:40" && t<"2026-11-17T19:02:52") print}' > ../win.clog
cd ..; for s in 2 4 3; do echo "== Sv$s"; grep -a "sequencer=globalSequencerSv$s" win.clog | grep -a -E 'InitializeSequencerFromOnboardingStateV2 by [^ ]+ (received a message|sending response)|Assigning sequencer to synchronizer|Applied [0-9]+ migrations|db-migration still|Starting with membership' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/; s/Request com.digitalasset.canton.sequencer.admin.v30.SequencerInitializationService.//; s/ by grpc[^:]*:[^:]*:[0-9]+//' | cut -c1-100; echo "initial-validation lines: $(grep -a "sequencer=globalSequencerSv$s" win.clog | grep -a -c 'purpose=initial-validation') first/last: $(grep -a "sequencer=globalSequencerSv$s" win.clog | grep -a 'purpose=initial-validation' | sed -n '1p;$p' | grep -a -o '"@timestamp":"[^"]*"' | tr '\n' ' ')"; done
```
```
== Sv2
2026-11-17T19:00:49.953Z InitializeSequencerFromOnboardingStateV2: received a message InitializeSeque
2026-11-17T19:00:50.018Z Assigning sequencer to synchronizer with existing snapshot
2026-11-17T19:00:53.170Z Applied 29 migrations successfully
2026-11-17T19:00:53.469Z Starting with membership = Membership( myId = \"SEQ::sv2::..d2c4\", orderingT
2026-11-17T19:00:53.520Z InitializeSequencerFromOnboardingStateV2: sending response InitializeSequencer
2026-11-17T19:00:57.986Z Starting with membership = Membership( myId = \"SEQ::sv2::..d2c4\", orderingT
initial-validation lines: 198 first/last: "@timestamp":"2026-11-17T19:00:50.039Z" "@timestamp":"2026-11-17T19:00:50.790Z"
== Sv4
2026-11-17T19:00:51.596Z InitializeSequencerFromOnboardingStateV2: received a message InitializeSeque
2026-11-17T19:00:51.611Z Assigning sequencer to synchronizer with existing snapshot
2026-11-17T19:00:53.973Z Applied 29 migrations successfully
2026-11-17T19:00:54.155Z Starting with membership = Membership( myId = \"SEQ::sv4::..ae49\", orderingT
2026-11-17T19:00:54.220Z InitializeSequencerFromOnboardingStateV2: sending response InitializeSequencer
2026-11-17T19:00:58.676Z Starting with membership = Membership( myId = \"SEQ::sv4::..ae49\", orderingT
initial-validation lines: 180 first/last: "@timestamp":"2026-11-17T19:00:51.729Z" "@timestamp":"2026-11-17T19:00:52.187Z"
== Sv3
2026-11-17T19:01:48.584Z InitializeSequencerFromOnboardingStateV2: received a message InitializeSeque
2026-11-17T19:01:48.659Z Assigning sequencer to synchronizer with existing snapshot
2026-11-17T19:02:29.978Z Task db-migration still not completed after 10 seconds. Continue waiting...
2026-11-17T19:02:39.994Z Task db-migration still not completed after 20016 milliseconds. Continue waiting...
2026-11-17T19:02:43.371Z Applied 29 migrations successfully
2026-11-17T19:02:45.348Z Starting with membership = Membership( myId = \"SEQ::sv3::..47dc\", orderingT
2026-11-17T19:02:46.199Z InitializeSequencerFromOnboardingStateV2: sending response InitializeSequencer
initial-validation lines: 261 first/last: "@timestamp":"2026-11-17T19:01:49.094Z" "@timestamp":"2026-11-17T19:02:14.583Z"
```

Two database-bound steps account for it: importing the topology snapshot (`purpose=initial-validation`, one store
write per sequenced topology transaction) 25.5 s for sv3 vs 0.75 s and 0.46 s, then the 29 Flyway migrations of
the sequencer's synchronizer schema, over 23 s vs under 3 s. In the sv3 import each write takes 200-350 ms, e.g.:

```
2026-11-17T19:01:49.444Z Processing addition batch 0   [DbTopologyStore]
2026-11-17T19:01:49.740Z Persisted topology transactions (SequencedTime(2026-11-17T18:58:23.411128Z), ...
2026-11-17T19:01:49.784Z Processing addition batch 0   [DbTopologyStore]
2026-11-17T19:01:50.033Z Persisted topology transactions (SequencedTime(2026-11-17T18:58:26.745670Z), ...
2026-11-17T19:01:50.041Z Processing addition batch 0   [DbTopologyStore]
2026-11-17T19:01:50.357Z Persisted topology transactions (SequencedTime(2026-11-17T18:58:28.140351Z), ...
```

No hang, no WARN or ERROR: while sv3's sequencer initialized, the other three kept ordering (sv3 was a leader of
epoch 29 from 19:01:47 without running, so it was blacklisted for epochs 30 and 31; 5 `reaching local timeout`
lines in the window, zero `P2P connectivity is not ready`, so not the family B rejected-send shape).

## 6. The slowness is global: unrelated single-node sequencers order 2x-3x slower in the same windows

Epoch durations (time between consecutive `New epoch N has started`), splitwellSequencer (one node, own
synchronizer, no topology change) and globalSequencerSv1:

```
zcat logs-frontend-wall-clock-time-2/canton.clog.gz | grep -a -E 'New epoch [0-9]+ has started' | grep -a -E 'sequencer=(splitwellSequencer|globalSequencerSv1)/' | sed -E 's/.*"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) .*sequencer=([A-Za-z0-9]+).*/\3 \2 \1/' > epochs.txt
# python: per sequencer, t(n+1) - t(n) for epochs starting 18:58 to 19:03
```
```
== splitwellSequencer
18:58:02 e114 2.8s; ... 18:58:58 e132 3.0s; 18:59:01 e133 3.3s; 18:59:05 e134 5.1s; 18:59:10 e135 9.6s; 18:59:19 e136 17.1s; 18:59:36 e137 17.3s; 18:59:54 e138 12.2s; 19:00:06 e139 12.8s; 19:00:19 e140 5.2s; 19:00:24 e141 3.0s; ... 19:01:00 e153 2.9s; 19:01:03 e154 5.2s; 19:01:08 e155 10.5s; 19:01:19 e156 7.1s; 19:01:26 e157 6.7s; 19:01:33 e158 6.9s; 19:01:39 e159 6.9s; 19:01:46 e160 6.2s; 19:01:53 e161 9.5s; 19:02:02 e162 8.2s; 19:02:10 e163 8.7s; 19:02:19 e164 9.2s; 19:02:28 e165 7.7s; 19:02:36 e166 5.1s; 19:02:41 e167 4.9s; 19:02:46 e168 5.9s
== globalSequencerSv1
18:58:21 e0 3.0s; ... 18:59:00 e13 2.3s; 18:59:02 e14 6.9s; 18:59:09 e15 12.4s; 18:59:21 e16 21.7s; 18:59:43 e17 21.5s; 19:00:04 e18 14.8s; 19:00:19 e19 6.2s; 19:00:25 e20 4.6s; 19:00:30 e21 4.6s; 19:00:35 e22 5.6s; 19:00:40 e23 4.8s; 19:00:45 e24 3.9s; 19:00:49 e25 10.0s; 19:00:59 e26 4.4s; 19:01:03 e27 20.1s; 19:01:23 e28 23.3s; 19:01:47 e29 23.2s; 19:02:10 e30 30.9s; 19:02:41 e31 18.8s
```

Two slow windows. The first, 18:59:05 to 19:00:24, coincides with the DAR uploads (section 7). The second starts
19:01:03 and lasts to the end of the suite; sv2 and sv4 initialized their sequencers at 19:00:50-54, between the
two, sv3 at 19:01:48, inside the second. What loads the shared Postgres / Canton JVM from 19:01:03 is not
identified (no Postgres log, no host metrics in the artifact); the Canton log volume halves from about 4000 to
2000 lines/s at 19:01:10.

## 7. DAR uploads: dedup of 11299 is in, 65 per SV, no repeats

```
zcat canton_network_test.clog.gz | grep -a 'config=8bf6a8e2/' | grep -a 'Sending upload dar for' | sed -E 's/.*"@timestamp":"([^"]+)".*Sending upload dar for ([^"]+)".*config=8bf6a8e2\/([A-Za-z]+=[A-Za-z0-9]+).*/\3 \1 \2/' | awk '{n[$1]++; c[$1" "$3]++; if(!f[$1]) f[$1]=$2; l[$1]=$2} END{for(k in n){d=0;r=0; for(j in c){split(j,a," "); if(a[1]==k){d++; if(c[j]>1) r+=c[j]-1}} print f[k], l[k], k, "uploads", n[k], "distinct", d, "repeats", r}}' | sort
```
```
2026-11-17T18:58:28.009Z 2026-11-17T18:58:49.440Z SV=sv1 uploads 65 distinct 65 repeats 0
2026-11-17T18:59:07.616Z 2026-11-17T19:00:02.439Z SV=sv2 uploads 65 distinct 65 repeats 0
2026-11-17T18:59:11.996Z 2026-11-17T19:00:14.153Z SV=sv3 uploads 65 distinct 65 repeats 0
2026-11-17T18:59:13.573Z 2026-11-17T19:00:15.459Z SV=sv4 uploads 65 distinct 65 repeats 0
2026-11-17T18:59:23.379Z 2026-11-17T19:00:17.244Z validator=bobValidator uploads 58 distinct 58 repeats 0
2026-11-17T18:59:33.241Z 2026-11-17T19:00:23.864Z validator=aliceValidator uploads 58 distinct 58 repeats 0
2026-11-17T19:00:09.750Z 2026-11-17T19:00:49.083Z validator=splitwellValidator uploads 59 distinct 59 repeats 0
```

(11373: 87 per SV, 22 repeats, 83 s vetting for sv3; here 69 s.) Vetting still costs each joining SV about 68 s.

## 8. Later suites: no namespace reset after the setup failure, sv1 cannot allocate the DSO party

```
zcat canton_network_test.clog.gz | grep -a -c 'has been reset'
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -a -o -E 'PartyToParticipant\(partyId = DSO-[0-9a-f]+' | sort | uniq -c
zcat canton.clog.gz | grep -a 'Topology transactions failed' | grep -a 'PartyToParticipant(partyId = DSO-' | head -1 | grep -a -o -E 'rejectionReason = [^)]{0,60}'
```
```
0
     80 PartyToParticipant(partyId = DSO-88fb6229
     84 PartyToParticipant(partyId = DSO-8c48eb3c
     81 PartyToParticipant(partyId = DSO-c0d77f97
     82 PartyToParticipant(partyId = DSO-f931a3be
rejectionReason = Not fully authorized\n
```

4 DSO parties = the 4 later suites (the suffix is the suite's config id). Same blast radius as 11373 section 7.

## 9. Duplicate check and frequency

```
for n in 11373 11347 11299; do gh issue view $n -R DACH-NY/cn-test-failures --json number,state,stateReason,title,assignees,closedAt --jq '"\(.number) \(.state)/\(.stateReason) assignees=\([.assignees[].login]|join(",")) closed=\(.closedAt) \(.title[0:90])"'; done
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "frontend-wall-clock-time (2)" created:>=2026-11-10' --jq '.items[] | "\(.number) \(.state)/\(.state_reason) \(.created_at) \(.title[0:90])"'
```
```
11373 OPEN/ assignees= closed=null SvFrontendIntegrationTest setup: sv3 init outlasts the 5 min wait on a slow runner, later
11347 OPEN/ assignees= closed=null SvReonboardingIntegrationTest: sv1 init exceeds the 5 min startAllSync wait, teardown leak
11299 CLOSED/COMPLETED assignees=stephencompall-DA closed=2026-11-13T14:34:35Z SvTimeBasedOnboardingIntegrationTest: sv4 init outlasts the 60 s wait, a quarter of its DA
11550 open/null 2026-11-17T19:25:19Z GHA Run 44151467568 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11373 open/null 2026-11-11T18:38:19Z SvFrontendIntegrationTest setup: sv3 init outlasts the 5 min wait on a slow runner, later
11340 closed/duplicate 2026-11-10T14:08:14Z GHA Run 42823122392 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11330 closed/duplicate 2026-11-10T10:01:46Z GHA Run 42790498517 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
11353 closed/completed 2026-11-11T05:45:16Z SvFrontendIntegrationTest Featured App Right: appKind needs splice-amulet 0.1.25, test unt
```

Family AM confirming grep for the 11373 shape (known-families.md): `Timeout while waiting for initialization of
sv<N>` in `Running setup` with the node's `app initialization` phases still advancing. Both hold (sections 2 and 4).
11373 is open, unassigned and not closed as a duplicate. Second setup timeout of this suite on this shard in 7 days
(11373 on 2026-11-11, this one on 2026-11-17). The number of post-merge runs in between was not counted.

Not BD (11539, #7795 is an ancestor of this sha): the sponsor's sequencer onboarding state for sv3 was served
after one retry (`Onboarding snapshot 4f54a990-... is exporting` at 19:01:47.784, `Onboarded sequencer` 19:01:48.540).

## Verdict

Duplicate of 11373: same suite, shard, wait (`EnvironmentDefinition.waitForNodeInitialization`, sv3 checked first,
5 min from 16 s after its start), sv3 still advancing at the timeout, and the same family B blast radius in the
later suites. Test flake; nothing in commit c20fdd262b (#8520, a cluster backup script) touches this path. The
variant is worth recording on 11373: the runner was not uniformly slow this time; a single database-bound Canton
step of the last-joining SV (sequencer init from the onboarding state, 57.6 s vs about 3 s) used up the margin, and
the DAR dedup (#8467) is already in. So of the fixes proposed in 11373, the wait budget (one overall init deadline
across all nodes, sized above the slowest observed init) and the reset after a failed setup remain; neither is on
main (section 1). No fix branch (duplicate).

Not verified: why database writes were slow from 19:01:03 (no Postgres log or host metrics in the artifact); how
long sv3 would have needed (it was at the mediator step, which took sv2 about 44 s and sv4 about 9 s to finish
init); the failure rate against the number of runs.
