# 10165 - sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 sequencer-add topology transaction cannot be ordered during the 1 -> 3 BFT onboarding step (run 35223275137)

Same root cause family as 10094 / 10153 / 10161 (BFT ordering topology step with newcomers not yet
P2P-authenticated), surfacing through a different symptom: an HTTP request timeout WARN in the SV app log
instead of a sequencer-client ack WARN. All tests passed.

- Run: https://github.com/canton-network/splice/actions/runs/35223275137, main ce81b9f29b ("Make docker
  compose's WG and Portfolio opt-out ..."), job 105208906926 `wall-clock-time (9)`.
- Runtime canton: 3.6.0-snapshot.20260916.20284.0.vf27c4824 (first triaged run on the #7364 bump). Its
  `consensus/iss` classes are byte-identical to the 20260910 snapshot (see 10048 packet), so no BFT change.
- 35 tests in 8 suites passed; `checkErrors` fails `log/canton_network_test.clog` on one WARN.

## 1. Flagged line

```
grep -a -B400 'contains problems' log/10165/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line'
```
```
13:17:04.269Z WARN o.l.s.a.h.HttpErrorHandler:SvDsoPartyManagementIntegrationTest/config=74d37f5b/SV=sv1
  Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.
```
38 s is `pekko.http.server.request-timeout = 38 seconds` (`apps/app/src/main/resources/application.conf:2`),
applied through `HttpErrorHandler.timeoutDirective`; `onboardSvSequencer` has no entry in the SV
`custom-timeouts` (`apps/app/src/test/resources/include/svs/_sv.conf:62-65` only lists
`onboardSvPartyMigrationAuthorize = 5 minutes`).

## 2. Test and request lifecycle

Test: `SvDsoPartyManagementIntegrationTest/SV users can act as SV party and act the DSO party` (13:15:11 -
13:18:01), which brings up sv2, sv3 and sv4 (initDso).

```
zcat log/10165/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a 'onboard/sv/sequencer' | grep -a -E 'Requesting with|Responding with status|took|timeout'
```
```
13:16:12.431Z sv3 -> sv1  POST onboard/sv/sequencer {sequencer_id: SEQ::sv3}
13:16:14.066Z sv2 -> sv1  POST onboard/sv/sequencer {sequencer_id: SEQ::sv2}
13:16:26.256Z sv4 -> sv1  POST onboard/sv/sequencer {sequencer_id: SEQ::sv4}
13:16:28.872Z sv1 200 OK to sv2  (14.8 s)
13:16:29.316Z sv1 200 OK to sv3  (17 s)
13:17:04.269Z sv1 WARN timeout after 38 s (sv4's request); sv4 receives 503, took 38017 ms
13:17:04.474Z sv4 -> sv1  retry
13:17:26.915Z sv1 200 OK on the first (already timed-out) sv4 request   (60.7 s after it arrived)
13:17:27.416Z sv1 200 OK on the retry                                     (22.9 s)
```

## 3. What sv1's handler was waiting on

`HttpSvPublicHandler.getSequencerOnboardingState` (`HttpSvPublicHandler.scala:645-662`) first runs
`waitForNewSequencerObservedByExistingSequencer` (`:580-643`): retry until the new sequencer is in the
`SequencerSynchronizerState` topology mapping, then (BFT) retry until it is in the sequencer's ordering
topology, then downloads the onboarding state. For sv4:

```
zcat ...canton_network_test.clog.gz | grep -a 'SV=sv1' | grep -a 12200172f50f | grep -a -E 'Waiting for sequencer|not in the ordering topology|Downloading'
```
```
13:16:26.258Z Waiting for sequencer SEQ::sv4::12200172f50f... to be onboarded before querying its onboarding state
13:16:26.4 - 13:17:1x  'check established Add sequencer SEQ::sv4' retries (sv1's own SvOnboardingSequencerTrigger) and
                        'not in active sequencers' retries from the handler
13:17:17.285Z first 'Sequencer SEQ::sv4 is not in the ordering topology' retry (topology mapping now effective)
13:17:26.9   ordering topology contains sv4 (epoch 106 started 13:17:25.661) -> download -> 200 OK
```
So the 38 s budget was consumed waiting for the sv4 `SequencerSynchronizerState` change to become effective.

## 4. All four SVs proposed the change within 0.5 s; it was sequenced 50 s later

```
zcat ...canton_network_test.clog.gz | grep -a ParticipantAdminConnection | grep -a 'Add sequencer SEQ::sv4' | grep -a -E 'Submitted proposal|Success'
```
```
13:16:26.464Z sv1 Submitted proposal SequencerSynchronizerState(threshold = 2, active = Seq(sv1, sv3, sv2, sv4)) ... waiting until the proposal gets accepted
13:16:26.567Z sv2 Submitted proposal (identical)
13:16:26.567Z sv4 Submitted proposal (identical)
13:16:26.939Z sv3 Submitted proposal (identical)
13:17:18.958Z sv2 Success: Add sequencer SEQ::sv4      13:17:19.237Z sv4 Success      13:17:19.598Z sv1 Success
```
Sequencer side (globalSequencerSv1, `Persisted topology transactions ... SequencerSynchronizerState`):
serial 2 (add sv3) and serial 3 (add sv2) were sequenced within 1 s of their proposals (13:16:12.6, 13:16:14.0,
threshold 1); serial 4 (add sv4, threshold 2) was sequenced at 13:17:16.08, effective 13:17:16.33.

## 5. Why: no strong quorum on sv1's sequencer for 25 s, epoch 104 took 38 s

```
zcat log/10165/logs-wall-clock-time-9/canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'New epoch|Completed epoch|Authenticated P2P nodes count' | grep -a 'T13:1[67]'
```
```
13:16:13.454Z New epoch 103 leaders=[sv1]                       size=1
13:16:26.167Z Completed epoch 103, but no new epoch topology is available yet
13:16:26.225Z Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 3, ordering may not be able to proceed ...
13:16:26.228Z New epoch 104 leaders=[sv3, sv1, sv2]             size=3 (activation 13:16:24.70)
13:16:51.751Z Authenticated P2P nodes count 2 is currently below strong quorum size 3
13:16:51.844Z Authenticated P2P nodes count 3 is now again above strong quorum size 3
13:17:04.563Z Completed epoch 104                               (38.3 s; epochs 102/103 took 7 and 13 s)
13:17:04.696Z New epoch 105 leaders=[sv1, sv2, sv3]
13:17:25.661Z New epoch 106 leaders=[sv3, sv4, sv1, sv2]        size=4 (sv4 joins)
13:17:36.083Z New epoch 107                                     blacklisted=[sv4]   (the newcomer, same pattern as sv1 in 10153/10161)
```
Epoch 104 activated the 3-node ordering topology at 13:16:26.2 with only sv1 authenticated; sv2 and sv3
authenticated at 13:16:51.7-51.8 (25 s later). Nothing could be ordered with strong quorum in that window,
and the epoch only completed at 13:17:04.6. The sv4 sequencer-add proposals (submitted 13:16:26.5-26.9,
i.e. 0.3 s after the step) sat in that gap and were sequenced in epoch 105 at 13:17:16. No
`SEQUENCER_OVERLOADED` rejections were logged in this window (0 lines), so the sends were accepted and
held, not refused.

## 6. Relation to the existing ignore for the validator endpoint

`project/ignore-patterns/canton_network_test_log.ignore.txt:171-173`:
```
# Calls to this have retries; if the retries were not enough, we'll get a louder error.
api/sv/v0/onboard/validator \(POST\) resulted in a timeout
```
(added by #6133, 2026-06-26). The sequencer endpoint has the same retry structure (`SvOnboardingSequencerTrigger`
on the joining SV retries; here sv4's retry succeeded 23 s later) but is not covered. 10137 (release-line-0.8.0,
canton 3.5.16) logged the same `onboard/sv/sequencer` timeout three times during its wedge, so this WARN is
the visible edge of the onboarding-step family, not a new bug.

## 7. Verdict

Root cause is the BFT onboarding topology step with unauthenticated newcomers (10094 / 10153 / 10161
family), now on canton 3.6.0-snapshot.20260916; the 38 s HTTP timeout is where it became visible this time.
Two splice-side options, in order of preference: (a) make sv1's handler not block the HTTP request on the
topology wait (return 202/retry-after, or raise `onboardSvSequencer` in `custom-timeouts` for tests to cover the
onboarding step, e.g. 2 minutes); (b) extend the existing ignore to `api/sv/v0/onboard/sv/sequencer \(POST\)
resulted in a timeout` with the same "calls have retries" justification. Neither fixes the quorum stall itself,
which stays with Canton (see 10137 for the splice mitigations: serialise SV sequencer onboardings, or gate
onboarding on P2P authentication).

## 8. Is the stall fixed in canton main? Checked against github.com/digital-asset/canton on 2026-09-17: no

The public repo is a squashed mirror (`[main] Update 2026-09-15.22`, pushed 2026-09-16 06:25; `release-line-3.6`
and `release-line-3.5` updated the same morning). Partial clone under `log/canton-mirror`.

```
cd log/canton-mirror; B=community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering
git diff --stat af87a966d611 origin/main -- $B | tail -1          # 09-04 -> 09-15.22
git show origin/main:$B/core/modules/mempool/MempoolModule.scala | grep -n -B4 'P2P connectivity is not ready'
git show origin/main:$B/docs/P2P.md | grep -n -i -E 'quorum|onboard'
```
```
40 files changed, 1964 insertions(+), 719 deletions(-)
MempoolModule.scala:71-75  // Reject in order to avoid dissemination failing due to insufficient quorum, which
                           //  shortens the client retry cycle and leverages sequencer client amplification.
                           //  This is especially convenient for automation of topology change submissions.
                           s"P2P connectivity is not ready (authenticated = $authenticatedCount < dissemination quorum = $weakQuorum), rejecting"
P2P.md:498  startModulesIfNeeded starts Availability at weak quorum ... and Consensus at strong quorum
P2P.md:571  module-start gating (boots Mempool/Output/Pruning immediately, Availability at weak quorum, Consensus at strong quorum)
```

What changed in bftordering between the two mirror states: the state-transfer/onboarding fixes (#35600, see
10048 packet), a large P2P connection-manager rework that makes "authenticated" stricter (a peer is not
reported authenticated before it actually authenticated), a mempool queue rework (`dequeueForBatch`,
expired-request discarding), `OutputModule` crypto-provider handling for onboarding between epochs, and
`BlacklistLeaderSelectionPolicyConfig` pretty-printing. None of it changes the two behaviours behind this
family:

- A new ordering topology is still activated at the epoch boundary as soon as its activation time passes,
  regardless of whether the newcomers are P2P-authenticated ("module-start gating" in P2P.md is about a
  starting node's own modules, not about the incumbents adopting a topology that lists unauthenticated
  nodes). `IssConsensusModule`, `PreIssConsensusModule`, `OutputModule` and
  `BftOrderingModuleSystemInitializer` at `origin/main` contain no `authenticat*` logic (0 hits each).
- The mempool still rejects submissions while `authenticated < weakQuorum` by design (comment above), and
  the `BlockSequencer` / `BftBlockOrderer` acknowledgement path still holds an accepted ack until ordering
  resumes (the 10153/10161 evidence: accepted, mempool-rejected, answered 120 s later after cancellation).
  `UNRELEASED.md` has no entry about it.

Jar cross-check: of the mirror's bftordering changes, the P2P hardening is already in every jar
(`NoAuthenticatedRecipientCandidates` present in 3.5.17, 20260910, 20260916), the mempool queue rework is in
3.5.17 but not yet in the 3.6 snapshots, and 20260910 -> 20260916 changed only 9 bftordering classes
(`BftBlockOrderer`, `P2PGrpcConnectionManager`, `BftBlockOrdererConfig`, `MempoolModule`, `OutputModule`).
10165 reproduced on 20260916, consistent with no fix being present.

Conclusion: not fixed in any publicly visible canton state as of 2026-09-16. Whatever the internal repo has
after 09-15 22:00 is not observable from here.

## 9. Reproducible walkthrough

The full command-by-command replay with verbatim outputs is appended below (also kept as `10165-repro.md`).

Every block below is a command exactly as run on 2026-09-17 against the downloaded artifact, followed by its
verbatim output (long hashes are cut by the sed/cut inside the command). Re-run in order from a splice checkout.
Analysis and verdict: 10165-onboard-sv-sequencer-http-timeout-bft-1-to-3-stall.md; same family: 10094, 10153, 10161.


### 0. Prerequisites and artifact download

Run from a splice checkout. Needs gh (authenticated for canton-network/splice), zcat, python3. All later commands assume the variables below. The artifact is about 180 MB; keep it gzipped and stream it with zcat.

```
export RUN=35223275137 JOB=105208906926 D=log/10165/logs-wall-clock-time-9
mkdir -p log/10165
gh api repos/canton-network/splice/actions/jobs/$JOB/logs > log/10165/job.log
gh run download $RUN --repo canton-network/splice -n logs-wall-clock-time-9 -D $D
gh run view $RUN --repo canton-network/splice --json headBranch,headSha,createdAt --jq '"branch=\(.headBranch) sha=\(.headSha[0:10]) created=\(.createdAt)"'
ls $D | grep -v test-full
git show ce81b9f29b:nix/canton-sources.json | grep -m1 version
```

```
branch=main sha=ce81b9f29b created=2026-09-17T12:48:51Z
canton-standalone-kms-identities-sv_after_shutdown.clog.gz
canton-standalone-kms-identities-sv_before_shutdown.clog.gz
canton-standalone-kms-identities-validator_after_shutdown.clog.gz
canton-standalone-kms-identities-validator_before_shutdown.clog.gz
canton.out.gz
canton_after_shutdown.clog.gz
canton_before_shutdown.clog.gz
canton_network_test.clog.gz
test-cometbft-full-class-names.log
test-daml-ciupgrade-vote.log
test-sbt.log
toxi.log.gz
  "version": "3.6.0-snapshot.20260916.20284.0.vf27c4824",
```


### 1. What failed: all tests passed, checkErrors flagged one WARN

The GitHub console masks braces as *** and prints every ignored line with the suffix (ignore this line in check-sbt-output.sh). The real problems are the @timestamp lines without that suffix.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/10165/job.log | grep -a -E 'Tests: succeeded|All tests passed|contains problems' | sed -E 's/^[^Z]*Z //' | sort -u
grep -a -B400 'contains problems' log/10165/job.log | grep -a '@timestamp' | grep -a -v 'ignore this line' | sed -E 's/^[^Z]*Z //' | cut -c1-600
```

```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Tests: succeeded 35, failed 0, canceled 0, ignored 0, pending 0
***"@timestamp":"2026-09-17T13:17:04.269Z","message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.","logger_name":"o.l.s.a.h.HttpErrorHandler:SvDsoPartyManagementIntegrationTest/config=74d37f5b/SV=sv1","thread_name":"SvDsoPartyManagementIntegrationTest-74d37f5b-env-ec-3977","level":"WARN","trace-id":"f4fc337997d34d9dc022f94ec3544145","span-id":"dbc815b3c7bfe6d8"***
```


### 2. Which test was running

SvDsoPartyManagementIntegrationTest's first test runs initDso, which onboards sv2, sv3 and sv4.

```
zcat $D/canton_network_test.clog.gz | grep -a -E "Starting '|Test succeeded" | grep -a -E '"@timestamp":"2026-09-17T13:1[5-8]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-160
```

```
2026-09-17T13:15:11.023Z Starting 'SvDsoPartyManagementIntegrationTest/SV users can act as SV party and act the DSO party'...",
2026-09-17T13:18:01.377Z Test succeeded: 'SvDsoPartyManagementIntegrationTest/SV users can act as SV party and act the DSO party'",
2026-09-17T13:18:01.377Z Starting 'SvDsoPartyManagementIntegrationTest/The DSO Party can be setup in the participant after SV has been confirmed to be part of t
```


### 3. Lifecycle of the onboard/sv/sequencer requests on sv1

sv3 and sv2 got their onboarding state in 15-17 s. sv4's request arrived at 13:16:26.256, timed out at 13:17:04.269 (38 s, sv4 sees a 503), sv4 retried at 13:17:04.474, and both the stale first request and the retry completed at 13:17:26.9-27.4.

```
zcat $D/canton_network_test.clog.gz | grep -a 'onboard/sv/sequencer' | grep -a -E '"@timestamp":"2026-09-17T13:1[5-8]' | grep -a -E 'Requesting with|Responding with status|took|timeout' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/SvDsoPartyManagementIntegrationTest\/config=74d37f5b\///' | cut -c1-200
```

```
2026-09-17T13:16:12.431Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv3::1220408c5e2a7011c0daa74347ebb0f076dd368285e64a6e1f19da2d1494e557
2026-09-17T13:16:14.066Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv2::12206dd80a18ec9f7d2acba3a417d8ebb28f058e5ae612f32de68e70a066ff7f
2026-09-17T13:16:26.256Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv4::12200172f50f113b82b38231a0956fd637b444e11df60bcd28546c1ad892d04f
2026-09-17T13:16:28.872Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:45362): Responding with status code: 200 OK",[o.l.s.a.a.HttpRequestLogger:SV=sv1] DEBUG
2026-09-17T13:16:28.873Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 14806 ms to complete. Received response with status code: 200 OK",[o.l.s.s.SvApp:SV=sv2] DEBUG
2026-09-17T13:16:29.316Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:45356): Responding with status code: 200 OK",[o.l.s.a.a.HttpRequestLogger:SV=sv1] DEBUG
2026-09-17T13:16:29.317Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 16886 ms to complete. Received response with status code: 200 OK",[o.l.s.s.SvApp:SV=sv3] DEBUG
2026-09-17T13:17:04.269Z Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.",[o.l.s.a.h.HttpErrorHandler:SV=sv1] WARN
2026-09-17T13:17:04.273Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 38017 ms to complete. Received response with status code: 503 Service Unavailable",[o.l.s.s.SvApp:SV=sv4]
2026-09-17T13:17:04.474Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): Requesting with entity data: {\"sequencer_id\":\"SEQ::sv4::12200172f50f113b82b38231a0956fd637b444e11df60bcd28546c1ad892d04f
2026-09-17T13:17:26.915Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:34272): Responding with status code: 200 OK",[o.l.s.a.a.HttpRequestLogger:SV=sv1] DEBUG
2026-09-17T13:17:27.416Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:44262): Responding with status code: 200 OK",[o.l.s.a.a.HttpRequestLogger:SV=sv1] DEBUG
2026-09-17T13:17:27.417Z HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 22942 ms to complete. Received response with status code: 200 OK",[o.l.s.s.SvApp:SV=sv4] DEBUG
```


### 4. Where the 38 s comes from

Global pekko request timeout for all splice HTTP servers; the SV custom-timeouts in the test config has no entry for onboardSvSequencer.

```
grep -rn 'request-timeout' apps/app/src/main/resources/application.conf
grep -n -A3 'custom-timeouts' apps/app/src/test/resources/include/svs/_sv.conf
grep -n -A6 'def timeoutDirective' apps/common/src/main/scala/org/lfdecentralizedtrust/splice/admin/http/HttpErrorHandler.scala
```

```
2:pekko.http.server.request-timeout = 38 seconds
62:    custom-timeouts {
63-      # names should match those of the OpenAPI definition
64-      onboardSvPartyMigrationAuthorize = 5 minutes
65-    }
197:  def timeoutDirective(implicit traceContext: TraceContext): Directive0 = {
198-    extractRequestTimeout.flatMap { timeout =>
199-      withRequestTimeoutResponse(request => {
200-        timeoutHandler(timeout, request)
201-      })
202-    }
203-  }
```


### 5. What sv1's handler blocks on

getSequencerOnboardingState waits for the new sequencer in SequencerSynchronizerState, then (BFT) in the ordering topology, then downloads the onboarding state. Both waits are retry loops of up to 120 x 5 s.

```
F=apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/admin/http/HttpSvPublicHandler.scala
grep -n -A17 'private def getSequencerOnboardingState' $F
grep -n -A6 'private def waitForNewSequencerObservedByExistingSequencer' $F
grep -n -B1 -A6 'val WaitingOnInitDependency' apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/RetryFor.scala
```

```
645:  private def getSequencerOnboardingState(
646-      isCantonBftSequencer: Boolean,
647-      sequencerAdminConnection: SequencerAdminConnection,
648-      sequencerId: SequencerId,
649-  )(implicit traceContext: TraceContext): Future[ByteString] = {
650-    logger.info(
651-      s"Waiting for sequencer $sequencerId to be onboarded before querying its onboarding state"
652-    )
653-    for {
654-      _ <- waitForNewSequencerObservedByExistingSequencer(
655-        isCantonBftSequencer,
656-        sequencerAdminConnection,
657-        sequencerId,
658-      )
659-      _ = logger.info(s"Downloading sequencer onboarding state for $sequencerId")
660-      onboardingState <- sequencerAdminConnection.getOnboardingState(Left(sequencerId))
661-    } yield onboardingState
662-  }
580:  private def waitForNewSequencerObservedByExistingSequencer(
581-      isBftSequencer: Boolean,
582-      sequencerAdminConnection: SequencerAdminConnection,
583-      sequencerId: SequencerId,
584-  )(implicit traceContext: TraceContext): Future[Unit] = {
585-    for {
586-      decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
39-    */
40:  val WaitingOnInitDependency: RetryFor = RetryFor(
41-    maxRetries = 120,
42-    initialDelay = 200.millis,
43-    maxDelay = 5.seconds,
44-    resetRetriesAfter = None,
45-  )
46-
--
49-    */
50:  val WaitingOnInitDependencyLong: RetryFor = RetryFor(
51-    maxRetries = 500,
52-    initialDelay = 200.millis,
53-    maxDelay = 5.seconds,
54-    resetRetriesAfter = None,
55-  )
56-
```


### 6. sv1's handler timeline for sv4's request

Milestones only: the wait started at 13:16:26.258; the topology-state wait only cleared around 13:17:17 (first 'not in the ordering topology' retry), the ordering-topology wait cleared at 13:17:25.7 (epoch 106), the download followed.

```
zcat $D/canton_network_test.clog.gz | grep -a 'SV=sv1' | grep -a '12200172f50f' | grep -a -E 'Waiting for sequencer|Downloading sequencer onboarding|is not in the ordering topology' | grep -a -E '"@timestamp":"2026-09-17T13:1[67]' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\2]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-170 | sed -n '1p;2p;$p'
zcat $D/canton_network_test.clog.gz | grep -a 'SV=sv1' | grep -a '12200172f50f' | grep -a -c 'is not in the ordering topology'
```

```
2026-09-17T13:16:26.258Z Waiting for sequencer SEQ::sv4::12200172f50f... to be onboarded before querying its onboarding state",[INFO]
2026-09-17T13:17:04.478Z Waiting for sequencer SEQ::sv4::12200172f50f... to be onboarded before querying its onboarding state",[INFO]
2026-09-17T13:17:27.255Z Downloading sequencer onboarding state for SEQ::sv4::12200172f50f...",[INFO]
26
```


### 7. All four SV apps proposed the identical sequencer-add within 0.5 s; it was accepted 52 s later

```
zcat $D/canton_network_test.clog.gz | grep -a ParticipantAdminConnection | grep -a 'Add sequencer SEQ::sv4' | grep -a -E 'Submitted proposal|Success:' | python3 -c '
import sys,re
for l in sys.stdin:
    ts=re.search(r"\"@timestamp\":\"([^\"]+)\"",l); sv=re.search(r"SV=(sv\d)",l); msg=re.search(r"\"message\":\"(.{0,160})",l)
    print(ts.group(1), sv.group(1), re.sub(r"1220[0-9a-f]{60}","..",msg.group(1)).replace("\\n"," "))' | sort
```

```
2026-09-17T13:16:26.464Z sv1 Submitted proposal SequencerSynchronizerState(   synchronizerId = global-domain::122043748ac2...,   threshold = 2,   active = Seq(SEQ::sv1::122014036a89..., 
2026-09-17T13:16:26.567Z sv2 Submitted proposal SequencerSynchronizerState(   synchronizerId = global-domain::122043748ac2...,   threshold = 2,   active = Seq(SEQ::sv1::122014036a89..., 
2026-09-17T13:16:26.567Z sv4 Submitted proposal SequencerSynchronizerState(   synchronizerId = global-domain::122043748ac2...,   threshold = 2,   active = Seq(SEQ::sv1::122014036a89..., 
2026-09-17T13:16:26.939Z sv3 Submitted proposal SequencerSynchronizerState(   synchronizerId = global-domain::122043748ac2...,   threshold = 2,   active = Seq(SEQ::sv1::122014036a89..., 
2026-09-17T13:17:18.958Z sv2 Success: Add sequencer SEQ::sv4::12200172f50f...","logger_name":"o.l.s.e.ParticipantAdminConnection:SvDsoPartyManagementIntegrationTest/config=74d37f5b/SV=sv2",
2026-09-17T13:17:19.237Z sv4 Success: Add sequencer SEQ::sv4::12200172f50f...","logger_name":"o.l.s.e.ParticipantAdminConnection:SvDsoPartyManagementIntegrationTest/config=74d37f5b/SV=sv4",
2026-09-17T13:17:19.598Z sv1 Success: Add sequencer SEQ::sv4::12200172f50f...","logger_name":"o.l.s.e.ParticipantAdminConnection:SvDsoPartyManagementIntegrationTest/config=74d37f5b/SV=sv1",
2026-09-17T13:17:20.501Z sv3 Success: Add sequencer SEQ::sv4::12200172f50f...","logger_name":"o.l.s.e.ParticipantAdminConnection:SvDsoPartyManagementIntegrationTest/config=74d37f5b/SV=sv3",
```


### 8. Sequencer side: when each SequencerSynchronizerState change was sequenced

Serials 2 and 3 (sv3, sv2; threshold 1) were sequenced within a second of their proposals. Serial 4 (sv4; threshold 2), proposed at 13:16:26.5, was sequenced at 13:17:16.08.

```
zcat $D/canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1' | grep -a 'Persisted topology transactions' | grep -a SequencerSynchronizerState | grep -a -E 'T13:1[67]' | grep -a -oE '"@timestamp":"[^"]+"|SequencedTime\([^)]+\)|threshold = [0-9]+|serial = [0-9]+' | paste - - - -
```

```
"@timestamp":"2026-09-17T13:16:12.716Z"	SequencedTime(2026-09-17T13:16:12.627111Z)	threshold = 1	serial = 2
"@timestamp":"2026-09-17T13:16:13.779Z"	SequencedTime(2026-09-17T13:16:13.641441Z)	threshold = 1	serial = 2
"@timestamp":"2026-09-17T13:16:13.853Z"	SequencedTime(2026-09-17T13:16:13.641443Z)	threshold = 1	serial = 2
"@timestamp":"2026-09-17T13:16:14.314Z"	SequencedTime(2026-09-17T13:16:14.011261Z)	threshold = 1	serial = 3
"@timestamp":"2026-09-17T13:16:14.970Z"	SequencedTime(2026-09-17T13:16:14.740384Z)	threshold = 1	serial = 3
"@timestamp":"2026-09-17T13:16:14.994Z"	SequencedTime(2026-09-17T13:16:14.740385Z)	threshold = 1	serial = 3
"@timestamp":"2026-09-17T13:16:15.011Z"	SequencedTime(2026-09-17T13:16:14.740386Z)	threshold = 1	serial = 3
"@timestamp":"2026-09-17T13:17:16.372Z"	SequencedTime(2026-09-17T13:17:16.083856Z)	threshold = 2	serial = 4
"@timestamp":"2026-09-17T13:17:16.481Z"	SequencedTime(2026-09-17T13:17:16.083857Z)	threshold = 2	serial = 4
"@timestamp":"2026-09-17T13:17:16.564Z"	SequencedTime(2026-09-17T13:17:16.083858Z)	threshold = 2	serial = 4
"@timestamp":"2026-09-17T13:17:16.600Z"	SequencedTime(2026-09-17T13:17:16.083859Z)	threshold = 2	serial = 4
```


### 9. Why: the 1 -> 3 ordering topology step at epoch 104 with only sv1 authenticated

Epoch 104 activated the 3-node topology at 13:16:26.2 while sv2 and sv3 were not yet P2P-authenticated (until 13:16:51.8); the epoch lasted 38 s instead of the usual 7-13 s. sv4 joined at epoch 106 and was blacklisted at 107 (the newcomer pattern seen for sv1 in 10153/10161). Zero SEQUENCER_OVERLOADED rejections in the window: submissions were accepted and held, not refused.

```
zcat $D/canton_before_shutdown.clog.gz | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'New epoch [0-9]+ has started|Completed epoch|Authenticated P2P nodes count' | grep -a -E 'T13:1(6|7:[0-3])' | grep -a -v DEBUG | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/ordering topology = OrderingTopology\(\\n  activationTime = ([^,]+),\\n  size = ([0-9]+).*/| activation=\1 size=\2/; s/1220[0-9a-f]{60}/../g; s/"logger_name.*//' | cut -c1-190
zcat $D/canton_before_shutdown.clog.gz | grep -a -E '"@timestamp":"2026-09-17T13:1(6:[2-5]|7:[0-2])' | grep -a -c 'Rejecting submission request.*SEQUENCER_OVERLOADED'
```

```
2026-09-17T13:16:06.424Z Completed epoch 101, but no new epoch topology is available yet",
2026-09-17T13:16:06.431Z New epoch 102 has started with leaders = List(SEQ::sv1::..233e)and blacklisted nodes = List(); | activation=2026-09-17T13:01:29.042196Z size=1
2026-09-17T13:16:13.408Z Completed epoch 102, but no new epoch topology is available yet",
2026-09-17T13:16:13.454Z New epoch 103 has started with leaders = List(SEQ::sv1::..233e)and blacklisted nodes = List(); | activation=2026-09-17T13:16:13.195751Z size=1
2026-09-17T13:16:26.167Z Completed epoch 103, but no new epoch topology is available yet",
2026-09-17T13:16:26.225Z Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated
2026-09-17T13:16:26.228Z New epoch 104 has started with leaders = List(SEQ::sv3::..d82c, SEQ::sv1::..233e, SEQ::sv2::..d888)and blacklisted nodes = List(); | activation=2026-09-17T13:16:24.7
2026-09-17T13:16:51.723Z Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated
2026-09-17T13:16:51.751Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated
2026-09-17T13:16:51.832Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated
2026-09-17T13:16:51.844Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3",
2026-09-17T13:17:04.563Z Completed epoch 104, but no new epoch topology is available yet",
2026-09-17T13:17:04.696Z New epoch 105 has started with leaders = List(SEQ::sv1::..233e, SEQ::sv2::..d888, SEQ::sv3::..d82c)and blacklisted nodes = List(); | activation=2026-09-17T13:16:24.7
2026-09-17T13:17:25.606Z Completed epoch 105, but no new epoch topology is available yet",
2026-09-17T13:17:25.661Z New epoch 106 has started with leaders = List(SEQ::sv3::..d82c, SEQ::sv4::..a354, SEQ::sv1::..233e, SEQ::sv2::..d888)and blacklisted nodes = List(); | activation=202
2026-09-17T13:17:36.067Z Completed epoch 106, but no new epoch topology is available yet",
2026-09-17T13:17:36.083Z New epoch 107 has started with leaders = List(SEQ::sv3::..d82c, SEQ::sv1::..233e, SEQ::sv2::..d888)and blacklisted nodes = List(SEQ::sv4::..a354); | activation=2026-
2026-09-17T13:17:48.254Z New epoch 108 has started with leaders = List(SEQ::sv1::..233e, SEQ::sv2::..d888, SEQ::sv3::..d82c)and blacklisted nodes = List(SEQ::sv4::..a354); | activation=2026-
0
```


### 10. The existing ignore only covers the validator endpoint

```
grep -n -B1 'onboard/validator' project/ignore-patterns/canton_network_test_log.ignore.txt
git log --format='%h %ad %s' --date=short -S'onboard/validator \(POST\) resulted in a timeout' -- project/ignore-patterns | tail -1
```

```
172-# Calls to this have retries; if the retries were not enough, we'll get a louder error.
173:api/sv/v0/onboard/validator \(POST\) resulted in a timeout
1b4fcc9f3b 2026-06-26 Fix ignoring onboard/validator timeout warnings (#6133)
```
