# 11350 - sv1's /api/sv/v0/onboard/sv/sequencer for sv2 times out after 38 s while the 2 -> 3 BFT ordering step waits for sv4; retry succeeds (run 42948633392)

DUPLICATE of 11255 (BFT ordering-topology onboarding window; 10165, the umbrella of this exact WARN, was merged into
11255 by rautenrieth-da on 2026-11-08), family B signature (2). main 9dd892fe2e, job `wall-clock-time (7)` attempt 1
(attempt 2 passed). All 18 tests in 9 suites passed; checkErrors flags one WARN from sv1's HttpErrorHandler during
the environment setup of ValidatorSequencerConnectionIntegrationTest. sv2, sv3 and sv4 onboard their sequencers
through sv1 within 21 s; sv2's sequencer-add proposals are submitted at 03:08:23.2-23.8, the same moment the ordering
topology steps to 3 nodes (sv4 added), and that epoch runs below strong quorum (2 < 3) until sv4's sequencer joins at
03:08:46.9. sv2's add becomes effective with the 4-node ordering topology (activation 03:09:00.829); sv1's handler is
still waiting for it when pekko's 38 s request timeout fires at 03:09:01.128. sv2's retry at 03:09:01.3 succeeds in
16 s.

- Run: https://github.com/canton-network/splice/actions/runs/42948633392 (attempt 1), main 9dd892fe2e ("SV UI: Add
  support for new fields in GrantFeaturedAppRight vote creation (#8062)"), job 130287690937
  `ci / scala_test_wall_clock_time / wall-clock-time (7)`.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: Canton BFT ordering (onboarding step), surfacing in the SV app's HTTP timeout; test environment setup.

## 1. Job, attempt, flagged line, test result

The run has two attempts; attempt 2 succeeded, the failing job is attempt 1.
```
gh api "repos/canton-network/splice/actions/runs/42948633392" --jq '{run_attempt,conclusion,status}'
gh api "repos/canton-network/splice/actions/runs/42948633392/attempts/1/jobs?per_page=100" --jq '.jobs[]|select(.conclusion=="failure")|"\(.id) \(.name)"'
```
```
{"conclusion":"success","run_attempt":2,"status":"completed"}
130287690937 ci / scala_test_wall_clock_time / wall-clock-time (7)
```
Tests passed; the only problem line not marked as ignored:
```
L=log/inbox-1111/130287690937.log
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -aE 'Tests: succeeded|contains problems|All tests passed|Suites: completed'
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -a '"@timestamp"' | grep -av 'ignore this line' | sed -E 's/^[0-9T:.Z-]+ //' | cut -c1-600
```
```
2026-11-11T03:28:30.6666373Z [info] Suites: completed 9, aborted 0
2026-11-11T03:28:30.6667123Z [info] Tests: succeeded 18, failed 0, canceled 0, ignored 4, pending 0
2026-11-11T03:28:30.6667737Z [info] All tests passed.
2026-11-11T03:30:21.9166814Z [error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
2026-11-11T03:30:21.9182305Z [error] (checkErrors) log/canton_network_test.clog contains problems.
***"@timestamp":"2026-11-11T03:09:01.128Z","message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.","logger_name":"o.l.s.a.h.HttpErrorHandler:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv1","thread_name":"ValidatorSequencerConnectionIntegrationTest-91d7716c-env-ec-481","level":"WARN","trace-id":"cc6511da005df48fbf27de9fa407fd62","span-id":"6e050ed4cbc3ba38"***
```
Artifact `logs-wall-clock-time-7` (id 13825937290, created 2026-11-11T03:30:59Z, i.e. attempt 1) downloaded to
`log/11350/logs-wall-clock-time-7/`.

## 2. Which test was running

The WARN is in the suite's environment setup (sv2 app initialization), before the first test of
ValidatorSequencerConnectionIntegrationTest finished.
```
T=log/11350/logs-wall-clock-time-7/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): " | grep -a 'T0[23]:[0-5]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | grep -a -B2 -A2 'ValidatorSequencerConnection'
```
```
2026-11-11T03:04:44.359Z Starting test suite 'ValidatorSequencerConnectionIntegrationTest'...",
2026-11-11T03:11:51.371Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers'",
2026-11-11T03:14:22.617Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/scan does not serve a sequencer id claimed by multiple SVs and validators connect to the remaining sequencers'",
```
The trace id of the WARN starts with sv2's app initialization (`2026-11-11T03:04:58.509Z sv2 app initialization:
Starting initialization`, `zcat $T | grep -a cc6511da005df48fbf27de9fa407fd62 | head -1`).

## 3. Three sequencer onboardings through sv1; sv2's is the third and takes 53 s

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $T | grep -a 'onboard/sv/sequencer' | sed -E "$F" | cut -c1-300 | grep -a -E 'Requesting with|Responding with status|timeout|retryable'
```
```
2026-11-11T03:08:02.052Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv3::..6f15\"}",[o.l.s.s.SvApp:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv3] DEBUG
2026-11-11T03:08:05.945Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv4::..18a0\"}",[o.l.s.s.SvApp:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv4] DEBUG
2026-11-11T03:08:09.998Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:36014): Responding with status code: 200 OK, took 7939 ms",[o.l.s.a.a.HttpRequestLogger:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv1] DEBUG
2026-11-11T03:08:23.106Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv2::..2354\"}",[o.l.s.s.SvApp:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv2] DEBUG
2026-11-11T03:08:27.265Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:36026): Responding with status code: 200 OK, took 21314 ms",[o.l.s.a.a.HttpRequestLogger:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv1] DEBUG
2026-11-11T03:09:01.128Z Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.",[o.l.s.a.h.HttpErrorHandler:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv1] WARN
2026-11-11T03:09:01.136Z The operation 'Onbarding sequencer through sponsoring SV' failed with a retryable HTTP error: org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException: HTTP 503 Service Unavailable POST at '/api/sv/v0/onboard/sv/sequencer' on 127.0.0.1:5114. Command fai
2026-11-11T03:09:01.337Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv2::..2354\"}",[o.l.s.s.SvApp:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv2] DEBUG
2026-11-11T03:09:16.197Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:51682): Responding with status code: 200 OK, took 53084 ms",[o.l.s.a.a.HttpRequestLogger:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv1] DEBUG
2026-11-11T03:09:17.674Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:51724): Responding with status code: 200 OK, took 16333 ms",[o.l.s.a.a.HttpRequestLogger:ValidatorSequencerConnectionIntegrationTest/config=91d7716c/SV=sv1] DEBUG
```
sv3: 7.9 s, sv4: 21.3 s, sv2: 53.1 s (first attempt, client got 503 after 38 s), sv2 retry: 16.3 s. The retry is the
client's `Onbarding sequencer through sponsoring SV` retry (spelling as logged).

## 4. What sv1's handler waited on

Two phases: the new sequencer visible in SequencerSynchronizerState (03:08:23.1 -> 03:09:01.0, 38 s), then in the
ordering topology (-> 03:09:16.0).
```
zcat $T | grep -a cc6511da005df48fbf27de9fa407fd62 | grep -a 'SV=sv1' | grep -a HttpSvPublicHandler | grep -a -E 'Success|Waiting|Downloading|Attempting' | sed -E "$F; s/ValidatorSequencerConnectionIntegrationTest\/config=91d7716c\///" | cut -c1-200 | grep -a 'T03:0[89]'
```
```
2026-11-11T03:08:23.114Z Waiting for sequencer SEQ::sv2::12208075901d... to be onboarded before querying its onboarding state",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:08:23.115Z Attempting to get 'New sequencer is observed in SequencerSynchronizerState through existing sequencer'",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:01.048Z Waiting until Wait for SEQ::sv2::12208075901d... to be in the ordering topology",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:01.342Z Waiting for sequencer SEQ::sv2::12208075901d... to be onboarded before querying its onboarding state",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:01.344Z Attempting to get 'New sequencer is observed in SequencerSynchronizerState through existing sequencer'",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:01.352Z Waiting until Wait for SEQ::sv2::12208075901d... to be in the ordering topology",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:16.014Z Success: Wait for SEQ::sv2::12208075901d... to be in the ordering topology",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:16.014Z Downloading sequencer onboarding state for SEQ::sv2::12208075901d...",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:17.511Z Success: Wait for SEQ::sv2::12208075901d... to be in the ordering topology",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
2026-11-11T03:09:17.511Z Downloading sequencer onboarding state for SEQ::sv2::12208075901d...",[o.l.s.s.a.h.HttpSvPublicHandler:SV=sv1] INFO
```
Until 03:09:01 every poll of the first phase failed with `NOT_FOUND: Sequencer SEQ::sv2::12208075901d... is not in
active sequencers` (`zcat $T | grep -a cc6511da005df48fbf27de9fa407fd62 | grep -a 'SV=sv1' | grep -a 'is not in
active sequencers' | head -1`: `2026-11-11T03:08:23.125Z The operation 'Get New sequencer is observed in
SequencerSynchronizerState through existing sequencer' failed with a retryable error (full stack trace omitted):
NOT_FOUND: Sequencer SEQ::sv2::12208075901d... is not in active sequencers`).

## 5. sv2's sequencer-add proposals were submitted at 03:08:23 and took effect ~03:09:13

```
zcat $T | grep -a -E 'T03:0[89]:' | grep -a -E '(Submitted proposal SequencerSynchronizerState|Success: Sequencer is added to the topology state).*(sv2::|SEQ::sv2)' | sed -E "$F; s/ValidatorSequencerConnectionIntegrationTest\/config=91d7716c\///; s/synchronizerId = global-domain::[0-9a-f.]*, //g" | cut -c1-330
```
```
2026-11-11T03:08:23.197Z Submitted proposal SequencerSynchronizerState(\n  synchronizerId = global-domain::1220f8f01b51...,\n  threshold = 2,\n  active = Seq(SEQ::sv1::12203fd9bbc3..., SEQ::sv3::12207bd1b9b3..., SEQ::sv4::12206c78c982..., SEQ::sv2::12208075901d...)\n) for Add sequencer SEQ::sv2::12208075901d..., waiting until th
2026-11-11T03:08:23.284Z Submitted proposal SequencerSynchronizerState(...) for Add sequencer SEQ::sv2::12208075901d..., waiting until th
2026-11-11T03:08:23.460Z Submitted proposal SequencerSynchronizerState(...) for Add sequencer SEQ::sv2::12208075901d..., waiting until th
2026-11-11T03:08:23.830Z Submitted proposal SequencerSynchronizerState(...) for Add sequencer SEQ::sv2::12208075901d..., waiting until th
2026-11-11T03:09:13.914Z Success: Sequencer is added to the topology state for MediatorToOnboard(mediatorId = MED::sv2::12203d6e623f..., sequencerId = SEQ::sv2::12208075901d...)",[o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SV=sv3] INFO
2026-11-11T03:09:16.362Z Success: Sequencer is added to the topology state for MediatorToOnboard(mediatorId = MED::sv2::12203d6e623f..., sequencerId = SEQ::sv2::12208075901d...)",[o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SV=sv1] INFO
```
(The three later proposal lines are the same text as the first, shortened here with `...`; the full lines are in the
command output.)

## 6. BFT ordering topology: the step to 3 nodes (sv4 added) runs below strong quorum for 23 s

Family B confirming grep on the canton log (globalSequencerSv1..4):
```
C=log/11350/logs-wall-clock-time-7/canton_before_shutdown.clog.gz
zcat $C | grep -a -E 'T03:0[89]:' | grep -a -E 'New epoch 3[0-6] has started|below (weak|strong) quorum|P2P connectivity is not ready' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{0,120}).*"logger_name":"[^"]*sequencer=(globalSequencerSv[0-9]).*"level":"([A-Z]+)".*/\1 \3 \4 \2/; s/1220[0-9a-f]{60}/../g' | cut -c1-230
```
```
2026-11-11T03:08:03.256Z globalSequencerSv1 INFO New epoch 30 has started with leaders = List(SEQ::sv1::..c
2026-11-11T03:08:07.375Z globalSequencerSv1 INFO Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 2, ordering may not be able
2026-11-11T03:08:07.380Z globalSequencerSv1 INFO New epoch 31 has started with leaders = List(SEQ::sv3::..6
2026-11-11T03:08:18.645Z globalSequencerSv3 INFO New epoch 31 has started with leaders = List(SEQ::sv3::..6
2026-11-11T03:08:19.179Z globalSequencerSv1 INFO New epoch 32 has started with leaders = List(SEQ::sv1::..c
2026-11-11T03:08:23.487Z globalSequencerSv3 INFO Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able
2026-11-11T03:08:23.495Z globalSequencerSv1 INFO New epoch 33 has started with leaders = List(SEQ::sv1::..c
2026-11-11T03:08:41.980Z globalSequencerSv1 INFO Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able
2026-11-11T03:08:46.914Z globalSequencerSv4 INFO New epoch 33 has started with leaders = List(SEQ::sv1::..c
2026-11-11T03:08:47.620Z globalSequencerSv1 INFO New epoch 34 has started with leaders = List(SEQ::sv3::..6
2026-11-11T03:09:00.752Z globalSequencerSv1 INFO New epoch 35 has started with leaders = List(SEQ::sv4::..1
2026-11-11T03:09:13.932Z globalSequencerSv1 INFO New epoch 36 has started with leaders = List(SEQ::sv1::..c
2026-11-11T03:09:22.347Z globalSequencerSv2 INFO Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 3, ordering may not be able
```
(subset of the output; the full extract is `log/11350/epochs-30-36.txt`). Ordering topology sizes and activation
times from the same epoch lines (`grep 'New epoch' log/11350/epochs.txt`):
```
2026-11-11T03:08:07.380Z New epoch 31 ... activationTime = 2026-11-11T03:08:07.007361Z,\n  size = 2
2026-11-11T03:08:23.488Z New epoch 33 ... activationTime = 2026-11-11T03:08:23.166543Z,\n  size = 3
2026-11-11T03:09:00.752Z New epoch 35 ... activationTime = 2026-11-11T03:08:48.381487Z,\n  size = 3
2026-11-11T03:09:13.920Z New epoch 36 has started with and blacklisted nodes = List(SEQ::sv4::..18a0); ordering topology = OrderingTopology(\n  activationTime = 2026-11-11T03:09:00.829140Z,\n  size = 4
```
Epoch 33 (size 3, sv4 the newcomer) started at 03:08:23.5 with 2 of 3 nodes authenticated; sv4's sequencer only
started that epoch at 03:08:46.9. sv2's sequencer-add proposals (03:08:23.2-23.8) were submitted into that window.
Epoch 36 (size 4, sv2 added, activation 03:09:00.829) started at 03:09:13.9, with sv4 blacklisted (wall-clock suite;
#8356 disables blacklisting only for simtime environments).

## 7. Duplicate check

Search for the WARN text across cn-test-failures (open and closed):
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "onboard/sv/sequencer"' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:110])"'
```
```
11255 open 2026-11-06 Timeouts due to changing bft ordering topology
10165 closed 2026-09-17 sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 sequencer-add topology transaction
9342 closed 2026-07-21 GHA Run 29850578502 : job wall-clock-time (9) in scala_test Failed :fire:
9212 closed 2026-07-14 roll-forward-lsu test fails with `localhost:27108 was not equal to localhost:27308`
10087 closed 2026-09-10 sv runbook fails to re-onboard, while other svs get the request
8857 closed 2026-06-18 RollForwardLsuDRIntegrationTest - SEQUENCING_TIME_NOT_ADMISSIBLE and request freezes in the sequencer
```
- 10165: same WARN, same mechanism (sequencer-add held during a BFT onboarding step, 1 -> 3 there); closed by
  rautenrieth-da on 2026-11-08 with "Merging into https://github.com/DACH-NY/cn-test-failures/issues/11255, which tracks
  general issues due to onboarding sequencers" (`gh api repos/DACH-NY/cn-test-failures/issues/10165/timeline`).
- 11255: open, assignee rautenrieth-da, the umbrella for BFT ordering-step failures; the parent.
- 9342 (closed as duplicate 2026-07), 9212, 10087, 8857: older, pre-BFT-default or LSU/runbook contexts; not counted.

Not the 11331 / #8410 shape: #8410 (merged 2026-11-10 22:27, in the run sha) raised the in-test `onboardUser`
`eventuallySucceeds` budget in ValidatorSequencerConnectionIntegrationTest to 2 minutes; the WARN here is from sv1's
SV API during environment setup, not from the validator's onboardUser call.
```
git merge-base --is-ancestor 5ccef7ff82fc582bb5e4c27785329cf91e3e7a8e 9dd892fe2e && echo '#8410 in run sha'
git merge-base --is-ancestor 2ef042397984ed873ab5e815fd747a87d0c62258 9dd892fe2e && echo '#8356 in run sha'
```
```
#8410 in run sha
#8356 in run sha
```

## 8. Ignore and timeout state at the run sha

The validator onboarding endpoint has an ignore with the "calls have retries" justification; the sequencer endpoint
does not; `onboardSvSequencer` has no custom timeout.
```
git show 9dd892fe2e:nix/canton-sources.json | grep -m1 version
git show 9dd892fe2e:project/ignore-patterns/canton_network_test_log.ignore.txt | grep -n -B2 'onboard/'
git show 9dd892fe2e:apps/app/src/test/resources/include/svs/_sv.conf | grep -n -A3 'custom-timeouts'
```
```
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
165-
166-# Calls to this have retries; if the retries were not enough, we'll get a louder error.
167:api/sv/v0/onboard/validator \(POST\) resulted in a timeout
56:    custom-timeouts {
57-      # names should match those of the OpenAPI definition
58-      onboardSvPartyMigrationAuthorize = 5 minutes
59-    }
```

## Verdict

Duplicate of 11255 (family B, signature (2); the wall-clock form of the 11255 onboarding window). Flake, environment
setup, all tests passed; the joining SV's retry succeeded 16 s later. Root cause stays with 11255 (Canton BFT
onboarding step below strong quorum while a newcomer authenticates; three SV sequencer onboardings back to back).
Splice-side mitigations already listed in the 10165 packet and family B: serialise SV sequencer onboardings, give
`onboardSvSequencer` a `custom-timeouts` entry above the onboarding step in tests, or extend the
`onboard/validator` ignore to `onboard/sv/sequencer` with the same retry justification. No new fix proposed here.
Not verified: why the sv1 node needed until 03:09:00.8 to make sv2's add effective beyond epoch 33's stall (epoch 34
lasted 13 s); not needed for the verdict.
