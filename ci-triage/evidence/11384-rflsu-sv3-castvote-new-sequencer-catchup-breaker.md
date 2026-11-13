# 11384 - RollForwardLsuIntegrationTest: sv3 castVote refused by its catching-up sequencer, SV app breaker outlasts the wait (run 43207139518)

New (family AX proposed). `roll forward LSU` fails in its first step after `startAllSync`: sv3 cannot cast its vote on
the LSU schedule. sv3's BFT sequencer was onboarded at 11:29:57 by state transfer from an 11:27:45 snapshot, so the
blocks it replayed were more than 90 s old and Canton's sequencer circuit breaker stopped accepting submissions at
11:30:01.4 for 30 s (until 11:30:31.5). The sv3 SV app switched its participant to the local sequencer only at
11:30:12.4. The test cast the votes at 11:30:17: sv2 succeeded in 1.1 s; all 20 `DsoRules_CastVote` submissions of
sv3 between 11:30:17.1 and 11:30:27.7 were refused with SEQUENCER_BACKPRESSURE, which tripped the sv3 SV app
`low-priority-connection` breaker (20 failures) at 11:30:27.662 for 30 s. Every later castVote attempt was aborted by
that breaker, so the test's `sv3 accepts vote` wait ran out at 11:30:39.3, 8 s after the sequencer accepted again.
Test-side timing flake, not AK (11303): different suite, shard and step. 1 failure of `roll-forward-lsu (1)` in 31
completed post-merge main jobs since 2026-11-10 08:06.

- Run: https://github.com/canton-network/splice/actions/runs/43207139518, main 0a0229c744 ("Check whether artifacts
  are public, before finishing the release (#8443)"), job 131145468720 `ci / scala_test_roll_forward_lsu /
  roll-forward-lsu (1)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261111.20904.0.vbe797d9d.
- Component: test (RollForwardLsuIntegrationTest votes right after setup, with SV-app circuit breakers on).

## 1. Failing assertion

The only test of the shard fails in the vote-casting future of `scheduleLogicalSynchronizerUpgrade`; the inner cause is
the `eventually` at SvTestUtil.scala:271 (`sv.castVote`, wrapped in `fail(e)`), whose last error is a console
`CommandFailure`.

```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/131145468720/logs > log/11384/job.log   # recipe 1
L=log/11384/job.log
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' $L | grep -a -E 'Tests: succeeded|FAILED \*\*\*|The future returned|SvTestUtil.scala:27[17]\)|Cause: ' | sort | uniq -c
```
```
      2 [info]   Cause: com.digitalasset.canton.console.CommandFailure: Command execution failed.
      2 [info]   Cause: org.scalatest.exceptions.TestFailedException:
      2 [info]   The future returned an exception of type: org.scalatest.exceptions.TestFailedException. (SvTestUtil.scala:277)
      2 [info]   at org.lfdecentralizedtrust.splice.util.SvTestUtil.$anonfun$scheduleLogicalSynchronizerUpgrade$1(SvTestUtil.scala:277)
      2 [info]   at org.lfdecentralizedtrust.splice.util.SvTestUtil.$anonfun$scheduleLogicalSynchronizerUpgrade$13(SvTestUtil.scala:271)
      2 [info]   at org.lfdecentralizedtrust.splice.util.SvTestUtil.$anonfun$scheduleLogicalSynchronizerUpgrade$6(SvTestUtil.scala:277)
      1 [info] *** 1 TEST FAILED ***
      2 [info] - roll forward LSU *** FAILED ***
      1 [info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
```

The code at the sha (SvTestUtil.scala lines 250-278): each voting SV first sees the vote request, then
`eventually() { sv.castVote(...) }`, all inside `loggerFactory.suppressErrors` and `.futureValue`.

```
git show 0a0229c74436:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/SvTestUtil.scala | sed -n '250,278p'
```
```
        // Concurrent uses of log suppression don't work so we suppress here instead of within eventually succeeds
        loggerFactory.suppressErrors {
          MonadUtil
            .parTraverseWithLimit(PositiveInt.four)(svsToCastVotes) { sv =>
              Future {
                clue(s"${svsToCastVotes.map(_.name)} see the vote request") {
                  val svVoteRequest = eventually() {
                    onlySetConfigVoteRequests(sv.listVoteRequests()).loneElement
                  }
                  getTrackingId(svVoteRequest) shouldBe voteRequest.contractId
                }
                clue(s"${sv.name} accepts vote") {
                  eventually() {
                    try {
                      sv.castVote(
                        voteRequest.contractId,
                        true,
                        "url",
                        "description",
                      )
                    } catch {
                      case NonFatal(e) => fail(e)
                    }
                  }
                }
              }
            }
            .futureValue
        }
```

## 2. Test timeline

Artifact `logs-roll-forward-lsu-1` (created 2026-11-12T11:31:17Z, the failing attempt 1), downloaded to
`log/11384/logs-roll-forward-lsu-1/`. Setup (`startAllSync`) took 7 min; the vote starts 1.1 s after "All sequencers
are registered", sv2 votes in 1.1 s, sv3 fails after 22 s.

```
T=log/11384/logs-roll-forward-lsu-1/canton_network_test.clog.gz
zcat $T | grep -a -E '"message":"(Starting test suite|Starting .RollForward|Running clue: (All sequencers|Schedule logical|List\(sv2, sv3\)|sv[23] accepts)|Finished clue: (All sequencers|sv2 accepts|List)|Failed clue|Suppressed ERROR: Failed clue|Test failed)' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-11-12T11:22:54.784Z Starting test suite 'RollForwardLsuIntegrationTest'...",
2026-11-12T11:23:02.804Z Starting 'RollForwardLsuIntegrationTest/roll forward LSU'...",
2026-11-12T11:30:15.829Z Running clue: All sequencers are registered",
2026-11-12T11:30:15.866Z Finished clue: All sequencers are registered",
2026-11-12T11:30:15.874Z Running clue: Schedule logical synchronizer upgrade",
2026-11-12T11:30:17.046Z Running clue: List(sv2, sv3) see the vote request",
2026-11-12T11:30:17.046Z Running clue: List(sv2, sv3) see the vote request",
2026-11-12T11:30:17.059Z Finished clue: List(sv2, sv3) see the vote request",
2026-11-12T11:30:17.059Z Finished clue: List(sv2, sv3) see the vote request",
2026-11-12T11:30:17.060Z Running clue: sv2 accepts vote",
2026-11-12T11:30:17.060Z Running clue: sv3 accepts vote",
2026-11-12T11:30:18.196Z Finished clue: sv2 accepts vote",
2026-11-12T11:30:39.279Z Suppressed ERROR: Failed clue: sv3 accepts vote",
2026-11-12T11:30:39.281Z Failed clue: (act) Voting on an DsoRules config change for scheduled logical synchronizer upgrade",
2026-11-12T11:30:39.281Z Failed clue: Schedule logical synchronizer upgrade",
2026-11-12T11:30:39.288Z Test failed: 'RollForwardLsuIntegrationTest/roll forward LSU', message: The future returned an exception of type: org.scalatest.exceptions.TestFa
```

## 3. sv3 castVote: three HTTP attempts, 36 handler retries, first SEQUENCER_BACKPRESSURE then the breaker

The test's `eventually` made three castVote HTTP calls; the SV app's handler retried each 12 times. 19 retries failed
with SEQUENCER_BACKPRESSURE, 17 with the app's own circuit breaker.

```
for k in SEQUENCER_BACKPRESSURE 'aborted by circuit breaker'; do printf '%s: ' "$k"; zcat $T | grep -a "The operation 'castVote' failed with a retryable error" | grep -a -c "$k"; done
zcat $T | grep -a -E "Decoded token with subject = sv3-[a-z0-9]+ for operation 'castVote'|operation 'castVote' has failed with an exception. Total maximum" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
SEQUENCER_BACKPRESSURE: 19
aborted by circuit breaker: 17
2026-11-12T11:30:17.071Z Decoded token with subject = sv3-b8b476c4 for operation 'castVote'",
2026-11-12T11:30:24.654Z The operation 'castVote' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. ",
2026-11-12T11:30:24.675Z Decoded token with subject = sv3-b8b476c4 for operation 'castVote'",
2026-11-12T11:30:32.027Z The operation 'castVote' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. ",
2026-11-12T11:30:32.037Z Decoded token with subject = sv3-b8b476c4 for operation 'castVote'",
2026-11-12T11:30:39.276Z The operation 'castVote' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. ",
```

The first backpressure error names the refusing sequencer (globalSequencerSv3) and its reason:

```
zcat $T | grep -a -m1 "The operation 'castVote' failed with a retryable error" | grep -oE 'Sequencer can.t t[^"]{0,400}' | head -c 600
```
```
Sequencer can't take requests because it is behind on processing events\n  Request: send-async-versioned/1f193ee3-eaf3-456c-81c8-bac474a9c940\n  DecodedCantonError(\n  code = 'SEQUENCER_OVERLOADED',\n  category = ContentionOnSharedResources,\n  cause = \
Sequencer can't take requests because it is behind on processing events\
Sequencer can't take requests because it is behind on processing events', 'sequencer=>globalSequencerSv3', 'definite_answer=>true')\n)\n), psid -> global-domain::122076dfb0b4...::37-0, category -> 2, tid -> 40b27c71989fd483f1a155243c395662, definite_answer -> true))\nRetr
```

From 11:30:27.7 on, the retries fail on the SV app breaker, not the sequencer (sample, the first two after 11:30:30):

```
zcat $T | grep -a 'T11:30:3' | grep -a 'SV=sv3' | grep -a "castVote' failed with a retryable error" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-230 | head -2
```
```
2026-11-12T11:30:30.484Z The operation 'castVote' failed with a retryable error (full stack trace omitted): ABORTED: Command submission aborted by circuit breaker due to too many successive failures, next attempt in 30s\nstatusCod
2026-11-12T11:30:31.222Z The operation 'castVote' failed with a retryable error (full stack trace omitted): ABORTED: Command submission aborted by circuit breaker due to too many successive failures, next attempt in 30s\nstatusCod
```

## 4. The castVote retries themselves tripped the sv3 low-priority breaker

The sv3 SV app's ledger client saw exactly 20 SEQUENCER_BACKPRESSURE refusals, all for `DsoRules_CastVote`; the breaker
trips on the 20th (11:30:27.661) and moves to half-open only at 11:30:57.677, 18 s after the test gave up.

```
zcat $T | grep -a 'SV=sv3' | grep -a 'failed with ABORTED/SEQUENCER_BACKPRESSURE' | grep -oE 'tid:[0-9a-f]+' | sort -u > log/11384/out/bp-tids.txt; wc -l < log/11384/out/bp-tids.txt
zcat $T | grep -a -F -f log/11384/out/bp-tids.txt | grep -a 'sending request SubmitAndWaitForTransactionRequest' | grep -oE 'DsoRules_[A-Za-z]+' | sort | uniq -c
zcat $T | grep -a 'SV=sv3' | grep -a 'failed with ABORTED/SEQUENCER_BACKPRESSURE' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-110 | sed -n '1p;$p'
zcat $T | grep -a -E 'SV=sv3' | grep -a -E 'sv3 app initialization: Initialization complete|LocalSequencerConnectionsTrigger.*setting SynchronizerConnectionConfig|Circuit breaker low-priority-connection' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-260
```
```
20
     20 DsoRules_CastVote
2026-11-12T11:30:17.161Z Request (tid:40b27c71989fd483f1a155243c395662) com.daml.ledger.api.v2.CommandService/
2026-11-12T11:30:27.661Z Request (tid:5b7769c9ef201a5f229798bb25ca81c9) com.daml.ledger.api.v2.CommandService/
2026-11-12T11:30:08.588Z sv3 app initialization: Initialization complete, running on version 0.10.2-ci-snapshot.20261112.1.0.v0a0229c7-dirty",[o.l.s.s.SvApp:RollForwardLsuIntegrationTest/config=b8b476c4/SV=sv3] INFO
2026-11-12T11:30:12.377Z setting SynchronizerConnectionConfig.sequencerConnections to the local sequencer only.",[o.l.s.s.a.s.LocalSequencerConnectionsTrigger:RollForwardLsuIntegrationTest/config=b8b476c4/SV=sv3] INFO
2026-11-12T11:30:27.662Z Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure",[o.l.s.u.SpliceCircuitBreaker:RollForwardLsuIntegrationTest/config=b8b476c4/SV=sv3] WARN
2026-11-12T11:30:57.677Z Circuit breaker low-priority-connection moving to half-open state",[o.l.s.u.SpliceCircuitBreaker:RollForwardLsuIntegrationTest/config=b8b476c4/SV=sv3] INFO
```

Test config: SV apps' low-priority breaker trips after 20 failures (`_sv.conf`), reset timeout default 30 s
(`CircuitBreakerConfig.resetTimeout`).

```
git show 0a0229c74436:apps/app/src/test/resources/include/svs/_sv.conf | sed -n '46,55p'
git show 0a0229c74436:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/config/SpliceConfig.scala | grep -n 'resetTimeout: NonNegativeFiniteDuration'
```
```
  parameters {
    circuit-breakers {
      # increase the max-failures in tests as things can be flaky
      medium-priority {
        max-failures = 20
      }
      low-priority {
        max-failures = 20
      }
    }
41:    resetTimeout: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
```

## 5. Why globalSequencerSv3 refused: it had just been onboarded and was replaying blocks older than 90 s

sv3's sequencer starts its onboarding state transfer at 11:29:57 from epoch 33 (ordering topology snapshot activation
11:27:45.93); Canton's sequencer circuit breaker stops accepting client requests at 11:30:01.4 because consecutive
blocks are more than 90 s behind, and resumes at 11:30:31.5. Only sv3's sequencer tripped in this run.

```
C=log/11384/logs-roll-forward-lsu-1/canton.clog.gz
zcat $C | grep -a 'sequencer=globalSequencerSv3' | grep -a -E 'Starting Onboarding state transfer|Completed state transfer|Sequencer (not accepting|now accepting) requests' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-260
zcat $C | grep -a -c 'Sequencer not accepting requests momentarily'
```
```
2026-11-12T11:29:57.157Z Starting Onboarding state transfer from epoch 33",[c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv3/psid=global-domain::122076dfb0b4::37-0] INFO
2026-11-12T11:30:01.379Z Sequencer not accepting requests momentarily for confirmation request ,test lsu sequencing ,topology transaction ,unexpected ,acknowledgment messages, after 10 consecutive blocks behind more than 90 seconds",[c.d.c.s.s.b.BlockSequencer
2026-11-12T11:30:01.408Z Sequencer not accepting requests momentarily for commitment ,top up ,top up med ,top up pre-onboarding ,time proof messages, after 30 consecutive blocks behind more than 90 seconds",[c.d.c.s.s.b.BlockSequencerCircuitBreaker$IndividualC
2026-11-12T11:30:02.428Z Completed state transfer, new epoch is 37, completing init",[c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv3/psid=global-domain::122076dfb0b4::37-0] INFO
2026-11-12T11:30:31.497Z Sequencer now accepting requests again for confirmation request ,test lsu sequencing ,topology transaction ,unexpected ,acknowledgment messages, after seeing a block with delay below 90 seconds",[c.d.c.s.s.b.BlockSequencerCircuitBreake
2026-11-12T11:30:31.497Z Sequencer now accepting requests again for commitment ,top up ,top up med ,top up pre-onboarding ,time proof messages, after seeing a block with delay below 90 seconds",[c.d.c.s.s.b.BlockSequencerCircuitBreaker$IndividualCircuitBreaker
2
```

The activation time of the ordering topology sv3 starts from:

```
zcat $C | grep -a 'sequencer=globalSequencerSv3' | grep -a 'IssConsensusModule' | grep -a -m1 'Starting with membership' | grep -oE 'activationTime = [0-9T:.Z-]+' | head -1
```
```
activationTime = 2026-11-12T11:27:45.932775Z
```

The breaker config (Canton default, from the sequencer factory line): enabled, 90 s delay, 10/30/60 blocks, 30 s
before it re-assesses.

```
zcat $C | grep -a 'Creating sequencer factory' | grep -a globalSequencerSv3 | python3 -I -c '
import sys,json,re
j=json.loads(sys.stdin.readline()); m=j["message"]; i=m.find("CircuitBre"); print(j["@timestamp"], m[i:i+330])'
```
```
2026-11-12T11:28:26.872Z CircuitBreakerConfig(true,CircuitBreakerByMessageTypeConfig(IndividualCircuitBreakerConfig(1m 30s,10,30s,1.0,876000h),IndividualCircuitBreakerConfig(1m 30s,10,30s,1.0,876000h),IndividualCircuitBreakerConfig(1m 30s,30,30s,1.0,876000h),IndividualCircuitB
```

## 6. The guard that exists elsewhere: LsuIntegrationTest disables the late-joining SV's app breakers

LsuIntegrationTest has the same situation for sv4 and turns that SV's app-level breakers off (#5587, 2026-05-18);
RollForwardLsuIntegrationTest has no such transform, and its voters sv2 and sv3 run with the test defaults (section 4).

```
git show 0a0229c74436:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala | sed -n '108,116p'
git log --format='%h %ad %an %s' --date=short -S 'sv-4 is intentionally a late-joining node' 0a0229c74436 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/LsuIntegrationTest.scala | tail -1
git grep -n -i 'circuitBreaker' 0a0229c74436 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuIntegrationTest.scala | wc -l
```
```
                // sv-4 is intentionally a late-joining node in this test, which means the
                // sequencer spends some time catching up. This can cause the sv-4 apps'
                // circuit breakers to trip, which makes annoying logs and delays init.
                // The circuit breakers tripping a bit during catchup would be just fine
                // IRL, so as a simple fix to this test we disable them for all sv-4 apps
                // (see also the scan and validator app transforms below).
                circuitBreakers =
                  if (name == "sv4") CircuitBreakersConfig.never
                  else config.parameters.circuitBreakers,
9cd6e600b9 2026-05-18 Martin Florian Disable sv4 app-level circuit breakers in LsuIntegrationTest (#5587)
0
```

Test ownership (author of the suite and most of its commits):

```
git log --format='%h %ad %an %s' --date=short --diff-filter=A 0a0229c74436 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuIntegrationTest.scala
git log --format='%an' 0a0229c74436 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuIntegrationTest.scala | sort | uniq -c | sort -rn | head -4
git log --format='%an' 0a0229c74436 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuIntegrationTest.scala | wc -l
```
```
abc20ab6fe 2026-03-27 moritzkiefer-da Support roll-forward LSU in SV app (#4685)
     13 moritzkiefer-da
      3 Nicu Reut
      2 Julien Tinguely
      1 Robert Autenrieth
20
```

## 7. Duplicate check

Not AK (11303): that is `RollForwardLsuDRIntegrationTest` / `roll forward LSU DR` on shard (0), pre-DR SV onboarding
past the 300 s sequencing cutoff; this is `RollForwardLsuIntegrationTest` on shard (1), failing at the first vote.
Not 11255 (B umbrella): no blacklisting or lost verdict; the refusing node is a freshly onboarded sequencer replaying
old blocks. The catalogue and the packets have no entry for the Canton sequencer catch-up breaker:

```
grep -rl 'behind more than\|not accepting requests momentarily' ci-triage/known-families.md ci-triage/evidence/ | grep -v 11384- | wc -l
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "not accepting requests momentarily"' --jq '.total_count'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "behind on processing events"' -f per_page=30 --jq '.items[] | "\(.number) \(.state) \(.created_at[0:10]) \(.title[0:110])"'
```
```
0
0
8644 closed 2026-06-02 DevNet cometbft OutOfMemory / OOM, likely due to slow peers
8414 closed 2026-05-13 LsuIntegrationTest: `sequencerUrlSet` misses a sequencer
7565 closed 2026-03-17 DsoPreflightIntegrationTest: Timeout on onboarding/prepare
7914 closed 2026-04-07 log failures in LSU test
7045 closed 2026-01-26 Sequencer startup takes longer than 10m due to slow query
5521 closed 2025-09-10 SvExpiredRewardsCollectionTimeBasedIntegrationTest fails on check waiting for open and issuing round automatio
```

Per hit: 8644 DevNet CometBFT OOM (infra, not this); 8414 LsuIntegrationTest `sequencerUrlSet` assertion (other test);
7565 DsoPreflight onboarding timeout (preflight, not this); 7914 checkErrors in the LSU test (`request current time`
retries, log-only); 7045 slow sequencer startup query; 5521 simtime round automation. None is a RollForwardLsu vote
failure.

Recent roll-forward-lsu issues: 11224 (nix setup, GitHub 504, per its comment), 11312 11328 11336 (closed as dups of
11303, shard (0), AK). None has this shape.

```
gh api repos/DACH-NY/cn-test-failures/issues/11224/comments --jq '.[] | "-- \(.user.login) \(.created_at): \(.body[0:500])"'
```
```
-- rautenrieth-da 2026-11-05T15:39:09Z: Same run, nix setup failed on the GitHub 504 for the lnav zip.
```

## 8. Frequency

`roll-forward-lsu (1)` on completed post-merge main runs since 2026-11-10 08:06 (two runs returned HTTP 502 for the job
list and are counted as unknown):

```
export NAME='roll-forward-lsu (1)'; grep completed log/11384/runs.txt | grep -v cancelled | while read id sha t s c; do r=$(gh api "repos/canton-network/splice/actions/runs/$id/jobs?filter=latest&per_page=100" --paginate --jq '.jobs[] | select(.name | endswith(" / " + env.NAME)) | "\(.id) \(.conclusion)"' | head -1); echo "$id $sha $t $r"; done | tee log/11384/rflsu1-jobs.txt | awk '{print $5}' | sort | uniq -c
```
```
gh: Server Error (HTTP 502)
gh: Server Error (HTTP 502)
      2 
      1 failure
     30 success
```

## Verdict

New, family AX (proposed). Test-side timing flake: RollForwardLsuIntegrationTest casts the LSU votes as soon as
`startAllSync` returns, while sv3's freshly onboarded BFT sequencer is still inside Canton's 90 s / 30 s catch-up
breaker window; the voter's own castVote retries then trip the sv3 SV-app low-priority breaker (20 failures, 30 s
reset), which outlasts the test's `eventually` around `castVote`. Canton behaves as configured; no splice app change
needed.

Fix (test, RollForwardLsuIntegrationTest): do as LsuIntegrationTest (#5587) and set
`circuitBreakers = CircuitBreakersConfig.never` for the SV apps in this suite (at least sv2 and sv3, the voters).
Without the app breaker, the second castVote attempt (11:30:24.7, retrying until about 11:30:32) would have reached
the sequencer after it accepted again at 11:30:31.5, inside the 22 s the `sv3 accepts vote` clue lasted (section 2); that is an inference from the
timestamps, not verified. A stronger alternative is to wait before scheduling until each voter's participant can
submit (its sequencer accepts requests), which also protects later steps. Suggested assignee: moritzkiefer-da (suite
author, 13 of its 20 commits), optional; first hit, 1 of 31 jobs.

Not verified: the shape of a passing run (artifacts are uploaded only for failed jobs, so no comparison of when sv3's
sequencer breaker tripped relative to the vote); why sv3's sequencer onboarding started 2 min after its snapshot time
(11:27:45.9 to 11:29:57.2), which is what made the replayed blocks older than 90 s.
