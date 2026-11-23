# 11685 - simtime (1): four suites aborted in setup after SvExpiredRewardsCollectionTimeBased (run 44729952801)

DUPLICATE of 11255 (family B, rejected-send shape under simulated time, same shape as 11415 and 11535). Post-merge
main ae237a8ac9 (#8617), job `ci / scala_test_sim_time / simtime (1)`, Canton 3.7.4. The first two suites of the shard
(UnhideAndExpireRewardCouponV2TimeBased, WalletMintingDelegationTimeBased) passed, 6 tests. In the setup of the third
suite, SvExpiredRewardsCollectionTimeBasedIntegrationTest, the BFT ordering topology of the global synchronizer grew
1 -> 4 at 12:07:58.519 (epoch 112, no blacklisting). The MediatorSynchronizerState proposal with MED::sv4 in the active
set was pushed by sv3, sv1, sv2 and sv4 between 12:07:58.531 and 12:07:59.203; globalSequencerSv1 rejected all four
pushes 26 to 691 ms after the switch (`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`)
and the participants did not retry them. sv2 and sv3 got their mediator traffic grants; sv4 waited on its grant from
12:08:02.921 until the 5 min init wait timed out at 12:13:24.656. The next three suites each aborted on the sv1 init
wait (blast radius of the failed setup). The report was kept: 2 suites completed, 4 aborted, 6 tests succeeded.

- Run: https://github.com/canton-network/splice/actions/runs/44729952801, main ae237a8ac9 ("fix how validator common
  IaC handles disableAuth after the introduction of participant admin API auth (#8617)"), job 136267208631
  `ci / scala_test_sim_time / simtime (1)`.
- Runtime canton: 3.7.4 (`nix/canton-sources.json` at ae237a8ac9).
- Component: Canton BFT ordering (sequencer onboarding window under sim time).

Artifact: `log/11685/logs-simtime-1/` (`gh run download 44729952801 --repo canton-network/splice -n logs-simtime-1`),
job log `log/11685/job.log` (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/136267208631/logs`),
`log/11685/job.clean.log` is the job log after `sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //'`.
In the commands `A=log/11685/logs-simtime-1` and `fmt` is the generic formatter from recipes section 4.

The same run also has a failed `simtime (2)` (job 136267208750, ref 11684, also a duplicate of 11255) and a cancelled
`docker-compose (1)`; the latter is not part of this ref and was not looked at.

## 1. Job log: report kept, four suites aborted in setup

```
grep -a -E 'Suites: completed|Tests: succeeded|\*\*\* ABORTED \*\*\*|Cause: |Run completed' log/11685/job.clean.log | sort | uniq -c | cut -c1-200
```
```
      8 [info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
      4 [info]  *** ABORTED ***
      1 [info] Run completed in 36 minutes, 46 seconds.
      1 [info] Suites: completed 2, aborted 4
      1 [info] Tests: succeeded 6, failed 0, canceled 0, ignored 0, pending 0
      1 [info] org.lfdecentralizedtrust.splice.integration.tests.SvExpiredRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
      1 [info] org.lfdecentralizedtrust.splice.integration.tests.TokenStandardCliTestDataTimeBasedIntegrationTest *** ABORTED ***
      1 [info] org.lfdecentralizedtrust.splice.integration.tests.WalletAmuletPriceTimeBasedIntegrationTest *** ABORTED ***
      1 [info] org.lfdecentralizedtrust.splice.integration.tests.WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest *** ABORTED ***
```
Every abort is `EnvironmentSetupException: Creating environment failed at step Running setup
(EnvironmentSetup.scala:235)` with the stack head `ConsoleMacros$utils$.retry_until_true` /
`HttpAppReference.waitForInitialization` (SpliceInstanceReference.scala:197) / `SvAppReference.waitForInitialization`
/ `EnvironmentDefinition$.waitForNodeInitialization` (EnvironmentDefinition.scala:665). No `FAILED ***`, no
`contains problems` (the sbt step failed on the aborted suites). The "custom container" annotation is the runner's
wording for a failed step, not an infra fault.

## 2. Test log: suite timeline and the init timeouts

```
zcat $A/canton_network_test.clog.gz | grep -a -E "Starting test suite|(Completed|Aborted) test suite|Timeout while waiting for initialization" | fmt 200 | sed -E 's/,\[.*app=(sv[0-9])\] ERROR/ [\1]/; s/",\[c.d.c.LogReporter.*//'
```
```
2026-11-20T11:52:21.845Z Starting test suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'...
2026-11-20T12:02:04.559Z Completed test suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'.
2026-11-20T12:02:04.691Z Starting test suite 'WalletMintingDelegationTimeBasedIntegrationTest'...
2026-11-20T12:06:16.278Z Completed test suite 'WalletMintingDelegationTimeBasedIntegrationTest'.
2026-11-20T12:06:16.288Z Starting test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'...
2026-11-20T12:13:24.656Z Timeout while waiting for initialization of sv4" [sv4]
2026-11-20T12:13:24.894Z Aborted test suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'.
2026-11-20T12:13:24.919Z Starting test suite 'TokenStandardCliTestDataTimeBasedIntegrationTest'...
2026-11-20T12:18:38.689Z Timeout while waiting for initialization of sv1" [sv1]
2026-11-20T12:18:38.798Z Aborted test suite 'TokenStandardCliTestDataTimeBasedIntegrationTest'.
2026-11-20T12:18:38.808Z Starting test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'...
2026-11-20T12:23:52.618Z Timeout while waiting for initialization of sv1" [sv1]
2026-11-20T12:23:52.665Z Aborted test suite 'WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest'.
2026-11-20T12:23:52.674Z Starting test suite 'WalletAmuletPriceTimeBasedIntegrationTest'...
2026-11-20T12:29:06.386Z Timeout while waiting for initialization of sv1" [sv1]
2026-11-20T12:29:06.433Z Aborted test suite 'WalletAmuletPriceTimeBasedIntegrationTest'.
```
The first abort is the cause; the three later ones start on the synchronizer the first one left behind and fail on
sv1, the first node checked (blast radius, as in 11415 and 11535). They were not analysed separately.

## 3. What sv4 waited on

`log/11685/svexp.jsonl` is `zcat $A/canton_network_test.clog.gz | grep -a 'SvExpiredRewardsCollectionTimeBasedIntegrationTest/config=43c7712'`.
```
grep -a 'SV=sv[1-4]"' log/11685/svexp.jsonl | grep -a -E '"message":"sv[1-4] app initialization: (Initialization complete|JoiningNodeInitializer joining Dso with key (finished|failed))' | fmt 200 | sed -E 's/",\[.*//'
grep -a 'Mediator has been granted unlimited traffic' log/11685/svexp.jsonl | grep -a -E 'Waiting until|Success' | sed -E 's/"logger_name":"[^"]*(SV=sv[0-9])".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-140
```
```
2026-11-20T12:06:54.080Z sv1 app initialization: Initialization complete, running on version 0.10.4-ci-snapshot.20261120.1.0.vae237a8a-dirty
2026-11-20T12:08:20.682Z sv3 app initialization: JoiningNodeInitializer joining Dso with key finished after PT1M27.600311323S
2026-11-20T12:08:20.685Z sv2 app initialization: JoiningNodeInitializer joining Dso with key finished after PT1M29.792580104S
2026-11-20T12:08:22.089Z sv3 app initialization: Initialization complete, running on version 0.10.4-ci-snapshot.20261120.1.0.vae237a8a-dirty
2026-11-20T12:08:22.093Z sv2 app initialization: Initialization complete, running on version 0.10.4-ci-snapshot.20261120.1.0.vae237a8a-dirty
2026-11-20T12:13:24.849Z sv4 app initialization: JoiningNodeInitializer joining Dso with key failed
```
```
2026-11-20T12:08:02.915Z Waiting until Mediator has been granted unlimited traffic",[SV=sv2]
2026-11-20T12:08:02.921Z Waiting until Mediator has been granted unlimited traffic",[SV=sv4]
2026-11-20T12:08:02.998Z Waiting until Mediator has been granted unlimited traffic",[SV=sv3]
2026-11-20T12:08:10.950Z Success: Mediator has been granted unlimited traffic",[SV=sv2]
2026-11-20T12:08:15.062Z Success: Mediator has been granted unlimited traffic",[SV=sv3]
```
sv4 has no `Success` line. Between 12:08:15.6 and 12:13:24 its SV app logs 81 x `Check whether Mediator has been
granted unlimited traffic ... NOT_FOUND: No traffic state found for member MED::sv4`, 302 x `Member MED::sv4 ... does
not yet have a traffic state, skipping` (SvOnboardingUnlimitedTrafficTrigger) and 89 x `check established Add mediator
MED::sv4 ... FAILED_PRECONDITION: Condition is not yet observed`: MED::sv4 never enters the mediator state. The export
queue of family BD is not involved: every `Starting onboarding snapshot export` on sv1 has its `Finished` line (last
one 12:07:59.345).

The MED::sv4 proposals of the four SV apps, all submitted within 0.7 s after the switch of section 4 (MED::sv2 and
MED::sv3 were added before it, `Success: Add mediator MED::sv3` on sv3 at 12:07:58.449):
```
grep -a -E 'Submitted proposal MediatorSynchronizerState' log/11685/svexp.jsonl | grep -a 'MED::sv4' | sed -E 's/"logger_name":"[^"]*(SV=sv[0-9])".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/Submitted proposal MediatorSynchronizerState.*for (Add mediator MED::sv4)[^"]*/Submitted proposal ... for \1 .../' | head -4
```
```
2026-11-20T12:07:58.532Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv3]
2026-11-20T12:07:58.567Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv1]
2026-11-20T12:07:58.668Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv2]
2026-11-20T12:07:59.203Z Submitted proposal ... for Add mediator MED::sv4 ...",[SV=sv4]
```
Each SV resubmits at 12:09:54-12:10:02 and 12:11:51-12:11:59 (`No proposal found ... submitting new one`); none is
observed.

## 4. Confirming grep of family B: 1 -> 4 switch, then rejected pushes

Ordering topology of the global synchronizer on globalSequencerSv1:
```
zcat $A/canton-simtime.clog.gz | grep -a 'New epoch [0-9]* has started with leaders' | grep -a globalSequencerSv1 | grep -a 'T12:0[78]:' | fmt 9999 | sed -E 's/New epoch ([0-9]+) .*blacklisted nodes = (List\([^)]*\)).*size = ([0-9]+).*/epoch \1 blacklisted \2 size \3/' | head -5
```
```
2026-11-20T12:07:21.683Z epoch 108 blacklisted List() size 1
2026-11-20T12:07:38.089Z epoch 109 blacklisted List() size 1
2026-11-20T12:07:44.663Z epoch 110 blacklisted List() size 1
2026-11-20T12:07:51.838Z epoch 111 blacklisted List() size 1
2026-11-20T12:07:58.519Z epoch 112 blacklisted List() size 4
```
Topology pushes of the SV participants around the switch and the rejections:
```
zcat $A/canton-simtime.clog.gz | grep -a 'T12:07:5[89]' | grep -a 'Attempting to push' | grep -a 'psid=global-domain' | fmt 9999 | sed -E 's/(Attempting to push [0-9]+ topology trans[a-z]*).*(QueueBasedSynchronizerOutbox:[^]]*\]).*/\1 ... \2/' | cut -c1-200
zcat $A/canton-simtime.clog.gz | grep -a 'T12:0[6-9]:\|T12:1[0-3]:' | grep -a 'Rejecting submission request by' | fmt 260
```
```
2026-11-20T12:07:58.118Z Attempting to push 1 topology transactions ... QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220b60e6a6f::37-0]
2026-11-20T12:07:58.303Z Attempting to push 1 topology transactions ... QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220b60e6a6f::37-0]
2026-11-20T12:07:58.531Z Attempting to push 1 topology transactions ... QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220b60e6a6f::37-0]
2026-11-20T12:07:58.566Z Attempting to push 1 topology transactions ... QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220b60e6a6f::37-0]
2026-11-20T12:07:58.667Z Attempting to push 1 topology transactions ... QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220b60e6a6f::37-0]
2026-11-20T12:07:59.203Z Attempting to push 1 topology transactions ... QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220b60e6a6f::37-0]
```
```
2026-11-20T12:07:58.545Z Rejecting submission request by Some(PAR::sv3::1220092e83f3...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.G
2026-11-20T12:07:58.572Z Rejecting submission request by Some(PAR::sv1::1220fd0fff65...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.G
2026-11-20T12:07:58.675Z Rejecting submission request by Some(PAR::sv2::1220d50353d2...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.G
2026-11-20T12:07:59.210Z Rejecting submission request by Some(PAR::sv4::1220b7aa65fd...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.G
```
These are the only four `Rejecting submission request by` lines of the suite. The four pushes after the switch
(12:07:58.531 sv3, .566 sv1, .667 sv2, 12:07:59.203 sv4) line up with the four MED::sv4 proposals of section 3 (each
within 1 ms of its `Submitted proposal` line) and each is rejected 6 to 14 ms later, 26, 53, 156 and 691 ms after
the switch. The pushes at 12:07:58.118 and .303 are before the switch and have no rejection (the one of sv1 matches
its MED::sv3 proposal, `Submitted proposal` 12:07:58.303, observed 12:07:58.514). The participant side logs `Request failed for server-DefaultSequencer-0 ... GrpcServiceUnavailable:
UNAVAILABLE/SEQUENCER_UNAVAILABLE` for each. Sv1 reports the newcomers authenticated 5 s after the switch:
```
zcat $A/canton-simtime.clog.gz | grep -a 'T12:0[78]:' | grep -a 'Authenticated P2P nodes count' | grep -a globalSequencerSv1 | fmt 200 | sed -E 's/",\[.*//; s/, dissemination and ordering.*//' | sed -n '1p;$p'
```
```
2026-11-20T12:07:58.514Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2
2026-11-20T12:08:03.697Z Authenticated P2P nodes count (including this node) 3 is now again above strong quorum size 3
```
Ruling out the lost-verdict and blacklisting shapes, and the open outbox flushes:
```
zcat $A/canton-simtime.clog.gz | grep -a 'T12:0[6-9]\|T12:1[0-3]' | awk '/currently blacklisted/{b++} /Failed to send result/{f++} /Another flush cycle is currently ongoing/{a++} /authenticated = 1 < dissemination quorum = 2/{q++} /Rejecting submission request by/{r++} END{print "blacklisted",b+0,"failedSendResult",f+0,"anotherFlush",a+0,"quorumText",q+0,"rejectedBy",r+0}'
```
```
blacklisted 0 failedSendResult 0 anotherFlush 35 quorumText 97 rejectedBy 4
```
Epochs keep coming after the switch (113 at 12:08:19.228 through the end, size 4, no blacklisting), so the ordering
layer is live; `Another flush cycle is currently ongoing` is logged by all four SV participants up to 12:11:59.002,
which is why the later re-proposals of section 3 never reach the sequencer.

## Verdict

Duplicate of 11255 ("Timeouts due to changing bft ordering topology", open, assignee rautenrieth-da), family B
rejected-send shape under simulated time, the 11415 / 11535 variant (SvExpiredRewardsCollectionTimeBased setup, report
kept, later suites aborted as blast radius). Flake, cause in Canton BFT ordering (the new ordering topology is active
before the newcomers are authenticated, and the participant does not retry the rejected topology broadcast, whose
outbox flush then stays open against a max sequencing time the sim clock never reaches); the splice-side mitigation
is splice#8363 ("Onboard SV sequencers one at a time in tests"), still open on 2026-11-20. No fix branch: the
mitigation already exists as an open PR and the cause is not in splice. Not verified: the hash and serial of the
MED::sv4 proposal (the pushes were matched to the proposals by participant and time, not by transaction id); the
three later aborts beyond their timeline; the cancelled `docker-compose (1)` job of the same run.
