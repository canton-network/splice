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
