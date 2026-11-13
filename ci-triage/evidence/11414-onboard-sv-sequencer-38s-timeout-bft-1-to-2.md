# 11414 - sv1's /api/sv/v0/onboard/sv/sequencer for sv4 times out after 38 s while the 1 -> 2 BFT ordering step waits 22 s for the newcomer sv3; retry succeeds (run 43457213815)

DUPLICATE of 11255 (BFT ordering-topology onboarding window), family B signature (2), same shape as 11350 (there the
2 -> 3 step, here 1 -> 2). main 09c8608e46, job `wall-clock-time (1)` attempt 1. All 23 tests in 8 suites passed;
checkErrors flags one WARN from sv1's HttpErrorHandler during the environment setup of SvOnboardingIntegrationTest.
sv3, sv4 and sv2 onboard their sequencers through sv1 within 31 s. sv4's request reaches sv1 at 16:19:30.189, 18 ms
before sv1's sequencer starts epoch 156, the first epoch of the 2-node ordering topology (sv3 the newcomer, strong
quorum 2, 1 node authenticated). The sv4 sequencer-add proposals are submitted at 16:19:30.3-31.0 into that epoch;
sv1's sequencer delivers no block from 16:19:30.123 to 16:19:52.324 (22.2 s) until sv3's sequencer enters epoch 156
at 16:19:50.672. The add becomes effective at 16:20:04.546; pekko's 38 s request timeout fires at 16:20:08.202, 4 s
earlier than sv1's handler needed. sv4's retry at 16:20:08.413 succeeds in 2.5 s.

- Run: https://github.com/canton-network/splice/actions/runs/43457213815 (attempt 1), main 09c8608e46 ("Support
  initializing the party to participant metrics for millions of entries (#8478)"), job 131994865740
  `ci / scala_test_wall_clock_time / wall-clock-time (1)`.
- Runtime canton: 3.7.3.
- Component: Canton BFT ordering (onboarding step), surfacing in the SV app's HTTP timeout; test environment setup.

## 1. Job, flagged line, test result

One attempt; the run's other failed job is simtime (0) (11413).
```
gh api repos/canton-network/splice/actions/runs/43457213815 --jq '{run_attempt,conclusion,head_sha,display_title}'
export NAME='wall-clock-time (1)'
gh api --paginate "repos/canton-network/splice/actions/runs/43457213815/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
```
```
{"conclusion":"failure","display_title":"Support initializing the party to participant metrics for millions of...","head_sha":"09c8608e46ea296de76da6d86fff03ba3b2b2e7b","run_attempt":1}
131994865740 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (1)
```
(The display title ends in a Unicode ellipsis in the API output, written `...` here.)

Tests passed; the only problem line not marked as ignored:
```
L=log/11414/job.log
sed -E 's/\x1b\[[0-9;]*m//g' $L | grep -aE 'Tests: succeeded|contains problems|All tests passed|Suites: completed' | cut -c1-200
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' $L | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -av 'ignore this line' | cut -c1-900
```
```
2026-11-13T16:34:12.6338865Z [info] Suites: completed 8, aborted 0
2026-11-13T16:34:12.6340246Z [info] Tests: succeeded 23, failed 0, canceled 0, ignored 0, pending 0
2026-11-13T16:34:12.6340730Z [info] All tests passed.
2026-11-13T16:35:42.9064804Z [error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
2026-11-13T16:35:42.9078647Z [error] (checkErrors) log/canton_network_test.clog contains problems.
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-13T16:20:08.202Z","message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.","logger_name":"o.l.s.a.h.HttpErrorHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1","thread_name":"SvOnboardingIntegrationTest-b4585c38-env-ec-5168","level":"WARN","trace-id":"e4bbe10d62eb2508ef2969ad2558ea74","span-id":"a03856e3d8c43c3d"***
Total: 1 lines with problems.
```
Artifact `logs-wall-clock-time-1` (id 14058090470, created 2026-11-13T16:36:13Z) downloaded to
`log/11414/logs-wall-clock-time-1/`.

## 2. Which test was running

The WARN is in the setup of SvOnboardingIntegrationTest (its first test runs 16:18:20-16:20:51; the SV apps
initialize inside it).
```
T=log/11414/logs-wall-clock-time-1/canton_network_test.clog.gz
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'T16:1[5-9]\|T16:2[0-2]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-11-13T16:18:19.937Z Test succeeded: 'AnsIntegrationTest/ans should an SV's ANS entry can be seen via scan api'",
2026-11-13T16:18:20.007Z Starting test suite 'SvOnboardingIntegrationTest'...",
2026-11-13T16:18:20.007Z Starting 'SvOnboardingIntegrationTest/fail registration with invalid tokens, succeed with a valid token'...",
2026-11-13T16:20:51.912Z Test succeeded: 'SvOnboardingIntegrationTest/fail registration with invalid tokens, succeed with a valid token'",
2026-11-13T16:20:51.913Z Starting 'SvOnboardingIntegrationTest/SVs can onboard new validators'...",
2026-11-13T16:22:30.941Z Test succeeded: 'SvOnboardingIntegrationTest/SVs can onboard new validators'",
```
(subset; the lines before 16:18:19 are AnsIntegrationTest.) The WARN's trace id is sv4's app initialization:
`zcat $T | grep -a e4bbe10d62eb2508ef2969ad2558ea74 | head -1` gives
`2026-11-13T16:18:22.514Z sv4 app initialization: Starting initialization`.

## 3. Three sequencer onboardings through sv1; sv4's takes 40.3 s

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'
zcat $T | grep -a 'onboard/sv/sequencer' | grep -a 'T16:1[89]\|T16:2[01]' | sed -E "$F" | cut -c1-260 | grep -a -E 'Requesting with|received request|Responding with status|timeout'
```
```
2026-11-13T16:19:25.511Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv3::..e7e6\"}",[o.l.s.s.SvApp:SvOnboardingIntegrationTest/config=b4585c38/SV=sv3] DEBUG
2026-11-13T16:19:25.513Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:57384): received request.",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
2026-11-13T16:19:30.187Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv4::..a33e\"}",[o.l.s.s.SvApp:SvOnboardingIntegrationTest/config=b4585c38/SV=sv4] DEBUG
2026-11-13T16:19:30.189Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:47102): received request.",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
2026-11-13T16:19:33.073Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:57384): Responding with status code: 200 OK, took 7560 ms",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
2026-11-13T16:19:55.880Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv2::..8c7d\"}",[o.l.s.s.SvApp:SvOnboardingIntegrationTest/config=b4585c38/SV=sv2] DEBUG
2026-11-13T16:19:55.883Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:45990): received request.",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
2026-11-13T16:20:08.202Z Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.",[o.l.s.a.h.HttpErrorHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] WARN
2026-11-13T16:20:08.413Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv4::..a33e\"}",[o.l.s.s.SvApp:SvOnboardingIntegrationTest/config=b4585c38/SV=sv4] DEBUG
2026-11-13T16:20:08.416Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:55606): received request.",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
2026-11-13T16:20:10.132Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:45990): Responding with status code: 200 OK, took 14249 ms",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
2026-11-13T16:20:10.519Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:47102): Responding with status code: 200 OK, took 40329 ms",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
2026-11-13T16:20:10.949Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:55606): Responding with status code: 200 OK, took 2533 ms",[o.l.s.a.a.HttpRequestLogger:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] DEBUG
```
sv3: 7.6 s; sv4: 40.3 s (client got 503 at 38 s, `HTTP request took 38019 ms ... 503 Service Unavailable` at
16:20:08.207 on SV=sv4); sv2: 14.2 s; sv4 retry: 2.5 s.

## 4. What sv1's handler waited on

The new sequencer in SequencerSynchronizerState: polled from 16:19:30.191, not found until the add was effective at
16:20:04.546, observed at 16:20:08.329, 0.13 s after the client had given up.
```
zcat $T | grep -a 'SV=sv1"' | grep -a -E "Got 'New sequencer is observed|Waiting for sequencer SEQ|Downloading sequencer onboarding" | grep -a 'T16:19\|T16:20:[01]' | sed -E "$F" | cut -c1-220
```
```
2026-11-13T16:19:25.520Z Waiting for sequencer SEQ::sv3::1220e227ee25... to be onboarded before querying its onboarding state",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
2026-11-13T16:19:26.782Z Got 'New sequencer is observed in SequencerSynchronizerState through existing sequencer': 2026-11-13T16:19:26.418948Z",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38
2026-11-13T16:19:30.190Z Waiting for sequencer SEQ::sv4::1220cb9b7b38... to be onboarded before querying its onboarding state",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
2026-11-13T16:19:32.494Z Downloading sequencer onboarding state for SEQ::sv3::1220e227ee25...",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
2026-11-13T16:19:55.884Z Waiting for sequencer SEQ::sv2::12201a655064... to be onboarded before querying its onboarding state",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
2026-11-13T16:20:07.341Z Got 'New sequencer is observed in SequencerSynchronizerState through existing sequencer': 2026-11-13T16:20:06.449093Z",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38
2026-11-13T16:20:08.329Z Got 'New sequencer is observed in SequencerSynchronizerState through existing sequencer': 2026-11-13T16:20:04.545927Z",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38
2026-11-13T16:20:08.417Z Waiting for sequencer SEQ::sv4::1220cb9b7b38... to be onboarded before querying its onboarding state",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
2026-11-13T16:20:08.424Z Got 'New sequencer is observed in SequencerSynchronizerState through existing sequencer': 2026-11-13T16:20:04.545927Z",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38
2026-11-13T16:20:09.957Z Downloading sequencer onboarding state for SEQ::sv2::12201a655064...",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
2026-11-13T16:20:10.355Z Downloading sequencer onboarding state for SEQ::sv4::1220cb9b7b38...",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
2026-11-13T16:20:10.789Z Downloading sequencer onboarding state for SEQ::sv4::1220cb9b7b38...",[o.l.s.s.a.h.HttpSvPublicHandler:SvOnboardingIntegrationTest/config=b4585c38/SV=sv1] INFO
```
The polls before failed with `NOT_FOUND: Sequencer SEQ::sv4::1220cb9b7b38... is not in active sequencers` (first at
16:19:30.196; `zcat $T | grep -a 'SV=sv1"' | grep -a 'SEQ::sv4' | grep -a -m1 'is not in active sequencers'`).
The 16:20:08.329 line belongs to the first (timed-out) request, 16:20:08.424 to the retry.

## 5. sv4's sequencer-add proposals were submitted at 16:19:30.3-31.0

```
zcat $T | grep -a -E 'T16:(19|20):' | grep -a -E '(Submitted proposal SequencerSynchronizerState|Success: Sequencer is added to the topology state).*(sv4::|SEQ::sv4)' | sed -E "$F; s/SvOnboardingIntegrationTest\/config=b4585c38\///; s/synchronizerId = global-domain::[0-9a-f.]*, //g" | cut -c1-330 | grep -a -v 'Add sequencer SEQ::sv2'
```
```
2026-11-13T16:19:30.348Z Submitted proposal SequencerSynchronizerState(threshold = 1, active = Seq(SEQ::sv1::122063c297bd..., SEQ::sv3::1220e227ee25..., SEQ::sv4::1220cb9b7b38...)) for Add sequencer SEQ::sv4::1220cb9b7b38..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv3] INFO
2026-11-13T16:19:30.797Z Submitted proposal SequencerSynchronizerState(threshold = 1, active = Seq(SEQ::sv1::122063c297bd..., SEQ::sv3::1220e227ee25..., SEQ::sv4::1220cb9b7b38...)) for Add sequencer SEQ::sv4::1220cb9b7b38..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv4] INFO
2026-11-13T16:19:30.815Z Submitted proposal SequencerSynchronizerState(threshold = 1, active = Seq(SEQ::sv1::122063c297bd..., SEQ::sv3::1220e227ee25..., SEQ::sv4::1220cb9b7b38...)) for Add sequencer SEQ::sv4::1220cb9b7b38..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv1] INFO
2026-11-13T16:19:30.991Z Submitted proposal SequencerSynchronizerState(threshold = 1, active = Seq(SEQ::sv1::122063c297bd..., SEQ::sv3::1220e227ee25..., SEQ::sv4::1220cb9b7b38...)) for Add sequencer SEQ::sv4::1220cb9b7b38..., waiting until the proposal gets accepted",[o.l.s.e.ParticipantAdminConnection:SV=sv2] INFO
2026-11-13T16:20:06.685Z Success: Sequencer is added to the topology state for MediatorToOnboard(mediatorId = MED::sv4::12203c7ccf54..., sequencerId = SEQ::sv4::1220cb9b7b38...)",[o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SV=sv2] INFO
2026-11-13T16:20:07.112Z Success: Sequencer is added to the topology state for MediatorToOnboard(mediatorId = MED::sv4::12203c7ccf54..., sequencerId = SEQ::sv4::1220cb9b7b38...)",[o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SV=sv3] INFO
2026-11-13T16:20:09.218Z Success: Sequencer is added to the topology state for MediatorToOnboard(mediatorId = MED::sv4::12203c7ccf54..., sequencerId = SEQ::sv4::1220cb9b7b38...)",[o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SV=sv1] INFO
2026-11-13T16:20:09.651Z Success: Sequencer is added to the topology state for MediatorToOnboard(mediatorId = MED::sv4::12203c7ccf54..., sequencerId = SEQ::sv4::1220cb9b7b38...)",[o.l.s.s.a.s.o.SvOnboardingMediatorProposalTrigger:SV=sv4] INFO
```

## 6. BFT ordering topology: the 1 -> 2 step (sv3 the newcomer) stalls ordering for 22 s

Epochs on globalSequencerSv1 with size and activation time:
```
C=log/11414/logs-wall-clock-time-1/canton_before_shutdown.clog.gz
zcat $C | grep -a -E 'T16:(19|20:[01])' | grep -a -E 'New epoch 1(5[5-9]|60) has started' | grep -a 'sequencer=globalSequencerSv1/' | sed -E 's/^\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) .*activationTime = ([0-9T:.Z-]+).{0,10}size = ([0-9]+).*/\1 epoch \2 activation \3 size \4/' | cut -c1-120
```
```
2026-11-13T16:19:24.309Z epoch 155 activation 2026-11-13T16:19:24.027478Z size 1
2026-11-13T16:19:30.207Z epoch 156 activation 2026-11-13T16:19:28.468218Z size 2
2026-11-13T16:19:54.487Z epoch 157 activation 2026-11-13T16:19:28.508218Z size 2
2026-11-13T16:19:58.888Z epoch 158 activation 2026-11-13T16:19:58.726471Z size 2
2026-11-13T16:20:03.059Z epoch 159 activation 2026-11-13T16:19:58.726471Z size 2
2026-11-13T16:20:09.658Z epoch 160 activation 2026-11-13T16:20:09.340694Z size 4
```
Epoch starts and quorum lines per sequencer node (family B confirming grep):
```
zcat $C | grep -a -E 'New epoch 15[5-9] has started|New epoch 160 has started|below strong quorum' | grep -a 'T16:19\|T16:20:[01]' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{0,40}).*"logger_name":"[^"]*(sequencer=[^/]*)\/.*"level":"([A-Z]+)".*/\1 \3 \4 \2/' | cut -c1-200
```
```
2026-11-13T16:19:24.309Z sequencer=globalSequencerSv1 INFO New epoch 155 has started with leaders =
2026-11-13T16:19:30.200Z sequencer=globalSequencerSv1 INFO Authenticated P2P nodes count (including
2026-11-13T16:19:30.207Z sequencer=globalSequencerSv1 INFO New epoch 156 has started with leaders =
2026-11-13T16:19:44.989Z sequencer=globalSequencerSv1 INFO Authenticated P2P nodes count (including
2026-11-13T16:19:50.672Z sequencer=globalSequencerSv3 INFO New epoch 156 has started with leaders =
2026-11-13T16:19:54.488Z sequencer=globalSequencerSv3 INFO New epoch 157 has started with leaders =
2026-11-13T16:19:54.487Z sequencer=globalSequencerSv1 INFO New epoch 157 has started with leaders =
2026-11-13T16:19:58.886Z sequencer=globalSequencerSv3 INFO New epoch 158 has started with leaders =
2026-11-13T16:19:58.888Z sequencer=globalSequencerSv1 INFO New epoch 158 has started with leaders =
2026-11-13T16:20:03.055Z sequencer=globalSequencerSv3 INFO New epoch 159 has started with leaders =
2026-11-13T16:20:03.059Z sequencer=globalSequencerSv1 INFO New epoch 159 has started with leaders =
2026-11-13T16:20:09.656Z sequencer=globalSequencerSv3 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:09.657Z sequencer=globalSequencerSv3 INFO New epoch 160 has started with leaders =
2026-11-13T16:20:09.658Z sequencer=globalSequencerSv1 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:09.658Z sequencer=globalSequencerSv1 INFO New epoch 160 has started with leaders =
2026-11-13T16:20:13.823Z sequencer=globalSequencerSv2 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:13.826Z sequencer=globalSequencerSv2 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:14.319Z sequencer=globalSequencerSv1 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:14.333Z sequencer=globalSequencerSv3 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:14.820Z sequencer=globalSequencerSv4 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:14.826Z sequencer=globalSequencerSv4 INFO Authenticated P2P nodes count (including
2026-11-13T16:20:17.962Z sequencer=globalSequencerSv2 INFO New epoch 160 has started with leaders =
2026-11-13T16:20:19.078Z sequencer=globalSequencerSv4 INFO New epoch 160 has started with leaders =
```
The sv1 quorum line at 16:19:30.200 in full: `Authenticated P2P nodes count (including this node) 1 is currently below
strong quorum size 2, ordering may not be able to proceed until more nodes are authenticated`.

No block delivered by sv1's sequencer during epoch 156 before sv3 joined it:
```
zcat $C | grep -a -E '"@timestamp":"2026-11-13T16:(19:(29|[3-5][0-9])|20:0[0-9])' | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'Delivering block|Completed epoch' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(.{0,40}).*/\1 \2/' > log/11414/sv1-blocks.txt
awk 'NR>1{split(prev,a," "); split($1,b,":"); split(a[1],c,":"); d=(b[3]+0)-(c[3]+0)+60*((b[2]+0)-(c[2]+0)); if(d>2) print "GAP", d, "s:", prev, "->", $0} {prev=$0}' log/11414/sv1-blocks.txt
grep -E 'Completed epoch' log/11414/sv1-blocks.txt
```
```
GAP 22.201 s: 2026-11-13T16:19:30.123Z Delivering block 2969 to post-ordering ( -> 2026-11-13T16:19:52.324Z Delivering block 2970 to post-ordering (
GAP 2.157 s: 2026-11-13T16:19:52.430Z Delivering block 3006 to post-ordering ( -> 2026-11-13T16:19:54.587Z Delivering block 3010 to post-ordering (
2026-11-13T16:19:30.119Z Completed epoch 155, but no new epoch to
2026-11-13T16:19:30.120Z Completed epoch 155 that could alter seq
2026-11-13T16:19:52.401Z Completed epoch 156, but no new epoch to
2026-11-13T16:19:52.429Z Completed epoch 156 that could alter seq
2026-11-13T16:19:58.795Z Completed epoch 157, but no new epoch to
2026-11-13T16:19:58.797Z Completed epoch 157 that could alter seq
2026-11-13T16:20:03.049Z Completed epoch 158, but no new epoch to
2026-11-13T16:20:03.051Z Completed epoch 158 that did not change 
2026-11-13T16:20:09.626Z Completed epoch 159 that could alter seq
2026-11-13T16:20:09.626Z Completed epoch 159, but no new epoch to
```
The proposals were held, not rejected: no `P2P connectivity is not ready` or `currently blacklisted` line on any node
16:19:30-16:20:09; the 20 `Rejecting submission request` lines in that window are all
`SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT` (duplicate aggregate sends, harmless):
```
zcat $C | grep -a -E 'T16:(19:[3-5]|20:0)' | grep -a -E 'Rejecting submission request|P2P connectivity is not ready|currently blacklisted' > log/11414/rej.txt
grep -a -c -E 'P2P connectivity is not ready|currently blacklisted' log/11414/rej.txt
grep -a -c 'Rejecting submission request' log/11414/rej.txt
grep -a 'Rejecting submission request' log/11414/rej.txt | grep -a -c -v 'SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SEN'
```
```
0
20
0
```

## 7. Duplicate check

Search for the WARN text across cn-test-failures (open and closed):
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "onboard/sv/sequencer"' --jq '.items[]|"\(.number) \(.state) \(.created_at[0:10]) \(.title[0:110])"'
```
```
11350 closed 2026-11-11 GHA Run 42948633392 : job wall-clock-time (7) in scala_test Failed :fire:
11255 open 2026-11-06 Timeouts due to changing bft ordering topology
10165 closed 2026-09-17 sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 sequencer-add topology transaction 
11379 open 2026-11-12 deploy_lsu_operator: SV re-onboarded after the LSU never initializes, SV app waits on the uninitialized pre-LS
9342 closed 2026-07-21 GHA Run 29850578502 : job wall-clock-time (9) in scala_test Failed :fire:
9212 closed 2026-07-14 roll-forward-lsu test fails with `localhost:27108 was not equal to localhost:27308`
10087 closed 2026-09-10 sv runbook fails to re-onboard, while other svs get the request
8857 closed 2026-06-18 RollForwardLsuDRIntegrationTest - SEQUENCING_TIME_NOT_ADMISSIBLE and request freezes in the sequencer 
```
- 11350: same WARN, same mechanism (sequencer-add submitted into a BFT onboarding step, 2 -> 3 there), closed as dup
  of 11255. Same failure.
- 10165: same WARN and mechanism (1 -> 3 step), merged into 11255 by rautenrieth-da on 2026-11-08. Same failure.
- 11255: open, assignee rautenrieth-da (`gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq ...` gives
  `11255 open assignees=rautenrieth-da Timeouts due to changing bft ordering topology`); the parent.
- 11379: cluster LSU re-onboarding, SV app waits on an uninitialized legacy sequencer (family AY); not this.
- 9342, 9212, 10087, 8857: older, pre-BFT-default or LSU/runbook contexts; not counted.

INDEX rows with this WARN: 10165, 11350, 11401 (`grep -n 'onboard/sv/sequencer' ci-triage/evidence/INDEX.tsv`).

## 8. Ignore and timeout state at the run sha

Unchanged since 11350: the validator onboarding endpoint has an ignore with the "calls have retries" justification;
the sequencer endpoint does not; `onboardSvSequencer` has no custom timeout.
```
git show 09c8608e46:nix/canton-sources.json | grep -m1 version
git show 09c8608e46:project/ignore-patterns/canton_network_test_log.ignore.txt | grep -n -B2 'onboard/'
git show 09c8608e46:apps/app/src/test/resources/include/svs/_sv.conf | grep -n -A3 'custom-timeouts'
```
```
  "version": "3.7.3",
165-
166-# Calls to this have retries; if the retries were not enough, we'll get a louder error.
167:api/sv/v0/onboard/validator \(POST\) resulted in a timeout
57:    custom-timeouts {
58-      # names should match those of the OpenAPI definition
59-      onboardSvPartyMigrationPrepare = 5 minutes
60-    }
```

## Verdict

Duplicate of 11255 (family B, signature (2); wall-clock form of the 11255 onboarding window, same as 11350 and
10165). Flake in environment setup; all tests passed and sv4's retry succeeded 2.5 s later. The concrete event:
sv1's sequencer starts the 2-node epoch 156 at 16:19:30.207 with only itself authenticated, delivers no block until
sv3's sequencer joins the epoch (16:19:50.672; next block 16:19:52.324), and sv4's sequencer-add, submitted
16:19:30.3-31.0, lands in that stall. Root cause stays with 11255 (Canton BFT onboarding step below strong quorum
while a newcomer catches up; three SV sequencer onboardings back to back in initDso). Splice-side mitigations as
listed in family B: serialise SV sequencer onboardings, give `onboardSvSequencer` a `custom-timeouts` entry above the
onboarding step in tests, or extend the `onboard/validator` ignore to `onboard/sv/sequencer` with the same retry
justification. No new fix proposed here.

Not verified: why the add became effective only at 16:20:04.546, 12 s after ordering resumed (epochs 157-158 ran
normally); not needed for the verdict.
