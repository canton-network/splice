# 11543 - simtime (3): MED::sv2 MediatorSynchronizerState pushes lost at the 1 -> 4 switch (sv4 and sv2 never answered, sv3 rejected, sv1 stuck); TimeBasedTestNetPreview setup fails, blast radius into three more suites (run 44115983523)

DUPLICATE of 11255 (family B, mixed rejected-send / never-answered shape at the 1 -> 4 step, setup-failure blast
radius). Main 408f7ed9e7, job simtime (3), Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. 1 test passed, 3 failed,
1 suite aborted (`Suites: completed 4, aborted 1`), checkErrors not reached. In the setup of
TimeBasedTestNetPreviewIntegrationTest (second suite of the shard) the global ordering topology goes from 1 to 4 nodes
at 16:26:24.011. The MediatorSynchronizerState proposal adding MED::sv2 (tx 861e2f202959) is pushed by sv4 69 ms and
by sv2 32 ms before the switch and is never answered; sv3 pushes it 51 ms after the switch and sv1's sequencer rejects
it 13 ms later with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`; sv1 enqueues it
85 ms after the switch behind its own open flush cycle and never pushes it. All four outboxes stay in an open flush
cycle, MED::sv2 is never added, and sv2's SV app waits on `Mediator has been granted unlimited traffic` until the
5 min init wait fails the setup at 16:31:45.537. No `has been reset` line follows; the next suite
(SvTimeBasedOnboarding) cannot bootstrap sv1 on the unreset topology (sv1Scan init timeout 16:36:51.297), its teardown
throws in UpdateHistorySanityCheckPlugin (`sv2Scan ... Node doesn't have any app state`, family H, 11347 shape), the
environment leaks, and SvTimeBasedAmuletPrice and SvTimeBasedRewardCoupon fail in `Creating fixture` with
`Could not create Prometheus HTTP server` / `Address already in use`. Same suite and shape as 11409 (MED::sv2, mixed
hit) and 11399. No blacklisting, no lost verdict. Fourth failed simtime shard of this family on 2026-11-17 (11534,
11535, 11540). splice#8363 (onboard SV sequencers one at a time in tests) is still open.

- Run: https://github.com/canton-network/splice/actions/runs/44115983523, main 408f7ed9e7 ("Retry
  SUBMISSION_SYNCHRONIZER_NOT_READY in test console command submissions (#8526)"), job 134154608139
  `ci / scala_test_sim_time / simtime (3)`. It is the only failed job of the run.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 408f7ed9e7).
- Component: Canton BFT ordering under simulated time (11255); second-order: test teardown plugin (family H).

Artifact `logs-simtime-3` (id 14348851307) in `log/11543/logs-simtime-3/`; the commands below run in that directory,
the job log is `log/11543/job.log` (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/134154608139/logs`).
On macOS `zcat` is the nix gzip one (or use `gzcat`). `F` is the generic line formatter, `../win-canton.log` the
Canton log of the failing setup:

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+(\.\.\.)?/::../g; s/1220[0-9a-f]{60}/../g'
zcat canton-simtime.clog.gz | awk -F'"@timestamp":"' '{split($2,a,"\""); t=a[1]} t>="2026-11-17T16:24:36" && t<"2026-11-17T16:31:46"' > ../win-canton.log
```

## 1. Failing assertions

Three tests fail, one passes; the first failure is the 5 min init wait for sv2 in the setup of TimeBasedTestNetPreviewIntegrationTest.

```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|Cause:|not accessible' | sed -E 's/^([^Z]*Z) /\1 /' | awk '$1<"2026-11-17T16:36:53.79"' | cut -c1-230
```

```
2026-11-17T16:31:46.1122875Z [info] - TestNet initializes correctly *** FAILED ***
2026-11-17T16:31:46.1125482Z [info]   Cause: java.lang.IllegalStateException: Condition never became true within 5 minutes
2026-11-17T16:36:51.7798220Z [info] - expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteRequest` contracts *** FAILED ***
2026-11-17T16:36:51.7799721Z [info]   java.lang.RuntimeException: The app state of sv2Scan is currently not accessible. Node doesn't have any app state.
2026-11-17T16:36:52.7897644Z [info] - amulet price votes and metrics *** FAILED ***
2026-11-17T16:36:52.7900901Z [info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
2026-11-17T16:36:52.8140523Z [info]   Cause: java.net.BindException: Address already in use
2026-11-17T16:36:52.8246470Z [info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
2026-11-17T16:36:52.8309974Z [info]   Cause: java.net.BindException: Address already in use
```

## 2. Suite timeline

The setup of TimeBasedTestNetPreviewIntegrationTest starts at 16:24:36 and times out on sv2 at 16:31:45; the previous suite reset the namespace, the failed setup does not (no `has been reset` after 16:24:36).

```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |has been reset|Timeout while waiting for initialization" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```

```
2026-11-17T16:15:24.062Z Starting test suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'...",
2026-11-17T16:24:36.176Z decentralized namespace has been reset",
2026-11-17T16:24:36.195Z sequencer domain state threshold has been reset",
2026-11-17T16:24:36.271Z Test succeeded: 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest/Unhide and expire of RewardCouponV2'",
2026-11-17T16:24:36.337Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
2026-11-17T16:31:45.537Z Timeout while waiting for initialization of sv2",
2026-11-17T16:31:45.828Z Test failed: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly', message: Creating environment failed at step Running setup (
2026-11-17T16:31:46.689Z Starting test suite 'SvTimeBasedOnboardingIntegrationTest'...",
2026-11-17T16:36:51.297Z Timeout while waiting for initialization of sv1Scan",
2026-11-17T16:36:51.305Z Test failed: 'SvTimeBasedOnboardingIntegrationTest/expire stale `SvOnboardingRequest`, `SvOnboardingConfirmed`,`ValidatorOnboarding` and `VoteReq
2026-11-17T16:36:52.005Z Starting test suite 'SvTimeBasedAmuletPriceIntegrationTest'...",
2026-11-17T16:36:52.068Z Test failed: 'SvTimeBasedAmuletPriceIntegrationTest/amulet price votes and metrics', message: Creating environment failed at step Creating fixtur
2026-11-17T16:36:52.100Z Starting test suite 'SvTimeBasedRewardCouponIntegrationTest'...",
```

## 3. What sv2 waits on

The sv2 SV app is still waiting for its mediator (MED::sv2) to be added to MediatorSynchronizerState when the setup gives up.

```
zcat canton_network_test.clog.gz | grep -a 'TimeBasedTestNetPreviewIntegrationTest/config=[0-9a-f]*/SV=sv2' | grep -a -E 'Gave up|sv2 app initialization: .*failed' | sed -E "$F" | cut -c1-230
```

```
2026-11-17T16:31:45.738Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[o.l.s.s.LocalSynchronizerNode:TimeBasedTestNetPreviewIntegrationTest/config=de3da935/SV=sv2] INFO
2026-11-17T16:31:45.738Z sv2 app initialization: JoiningNodeInitializer joining Dso with key failed",[o.l.s.s.SvApp:TimeBasedTestNetPreviewIntegrationTest/config=de3da935/SV=sv2] INFO
2026-11-17T16:31:45.738Z Gave up ensuring Add mediator MED::sv2::..",[o.l.s.e.ParticipantAdminConnection:TimeBasedTestNetPreviewIntegrationTest/config=de3da935/SV=sv2] INFO
2026-11-17T16:31:45.745Z sv2 app initialization: Initialize node failed",[o.l.s.s.SvApp:TimeBasedTestNetPreviewIntegrationTest/config=de3da935/SV=sv2] INFO
2026-11-17T16:31:45.745Z sv2 app initialization: Initialize app failed",[o.l.s.s.SvApp:TimeBasedTestNetPreviewIntegrationTest/config=de3da935/SV=sv2] INFO
2026-11-17T16:31:45.745Z sv2 app initialization: Ignoring initialization failure, we are actually shutting down. Message was: sv2 app initialization: Initialize app failed",[o.l.s.s.SvApp:TimeBasedTestNetPreviewIntegrationTest/con
```

## 4. Ordering topology 1 -> 4 on globalSequencerSv1

sv1 switches from a 1-node to a 4-node ordering topology at 16:26:24.011 with one authenticated P2P node; no blacklisting.

```
grep -a 'sequencer=globalSequencerSv1' ../win-canton.log | grep -a -E 'New epoch 7[567] has started|Authenticated P2P nodes count' | sed -E "$F" | sed -E 's/ordering topology = OrderingTopology\(\\n  activationTime = [^,]*,\\n  (size = [0-9]+),.*(\[c\.)/\1 \2/' | cut -c1-330 | head -6
```

```
2026-11-17T16:26:17.025Z New epoch 75 has started with leaders = List(SEQ::sv1::..)and blacklisted nodes = List(); size = 1 [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv1/psid=global-domain::..::37-0] INFO
2026-11-17T16:26:24.006Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-domain::..::37-0] INFO
2026-11-17T16:26:24.011Z New epoch 76 has started with leaders = List(SEQ::sv1::.., SEQ::sv2::.., SEQ::sv3::.., SEQ::sv4::..)and blacklisted nodes = List(); size = 4 [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv1/psid=global-domain::..::37-0] INFO
2026-11-17T16:26:28.072Z Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-domain::..::37-0] INFO
2026-11-17T16:26:28.140Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-domain::..::37-0] INFO
2026-11-17T16:26:28.228Z Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes are authenticated",[c.d.c.s.s.b.b.c.m.p.P2PNetworkOutModule:sequencer=globalSequencerSv1/psid=global-domain::..::37-0] INFO
```

## 5. The MED::sv2 add (tx 861e2f202959) is pushed around the switch and never answered

The MediatorSynchronizerState proposal 861e2f202959 is pushed by sv4 (69 ms before the switch), sv2 (32 ms before) and sv3 (51 ms after); sv1 enqueues it 85 ms after the switch behind its own open flush cycle.

```
grep -a '861e2f202959' ../win-canton.log | grep -a -E 'Attempting to push|enqueuing' | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1]<"2026-11-17T16:26:25") print}' | sed -E "$F" | sed -E 's/Attempting to push 1 topology transactions to Synchronizer .global., specifically: /push /' | cut -c1-200; grep -a 'T16:26:24.09' ../win-canton.log | grep -a 'participant=sv1Participant' | grep -a 'Another flush' | sed -E "$F" | cut -c1-200
```

```
2026-11-17T16:26:23.725Z enqueuing: List(TxHash(SHA-256:861e2f202959...))",[c.d.c.t.SynchronizerOutboxQueue:participant=sv4Participant/psid=global-domain::..::37-0] DEBUG
2026-11-17T16:26:23.902Z enqueuing: List(TxHash(SHA-256:861e2f202959...))",[c.d.c.t.SynchronizerOutboxQueue:participant=sv2Participant/psid=global-domain::..::37-0] DEBUG
2026-11-17T16:26:23.942Z push List(TxHash(SHA-256:861e2f202959...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::..::37-0] DEBUG
2026-11-17T16:26:23.979Z push List(TxHash(SHA-256:861e2f202959...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::..::37-0] DEBUG
2026-11-17T16:26:24.061Z enqueuing: List(TxHash(SHA-256:861e2f202959...))",[c.d.c.t.SynchronizerOutboxQueue:participant=sv3Participant/psid=global-domain::..::37-0] DEBUG
2026-11-17T16:26:24.062Z push List(TxHash(SHA-256:861e2f202959...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::..::37-0] DEBUG
2026-11-17T16:26:24.096Z enqueuing: List(TxHash(SHA-256:861e2f202959...))",[c.d.c.t.SynchronizerOutboxQueue:participant=sv1Participant/psid=global-domain::..::37-0] DEBUG
2026-11-17T16:26:24.096Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::..::37-0] DEBUG
```

sv1 sequencer rejects the sv3 push 64 ms after the switch; it is the only `P2P connectivity is not ready` rejection of a participant in the setup.

```
grep -a 'Rejecting submission request by' ../win-canton.log | grep -a 'P2P connectivity is not ready' | sed -E "$F" | cut -c1-300
```

```
2026-11-17T16:26:24.075Z Rejecting submission request by Some(PAR::sv3::..) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INF
```

No push of 861e2f202959 ever gets a synchronizer response (the earlier pushes, e.g. ec1f0574de16 on sv3, do), all four outboxes keep an open flush cycle, and there is no blacklisting and no lost verdict.

```
echo "responses for 861e2f202959: $(grep -a '861e2f202959' ../win-canton.log | grep -a -c 'responded the following')"; echo "responses for ec1f0574de16: $(grep -a 'ec1f0574de16' ../win-canton.log | grep -a -c 'responded the following')"; grep -a 'Another flush cycle is currently ongoing' ../win-canton.log | grep -a -oE 'participant=sv[0-9]Participant' | sort | uniq -c; awk '/currently blacklisted/{b++} /Failed to send result/{c++} END{print "currently-blacklisted",b+0,"failed-to-send-result",c+0}' ../win-canton.log
```

```
responses for 861e2f202959: 0
responses for ec1f0574de16: 3
      7 participant=sv1Participant
      9 participant=sv2Participant
      9 participant=sv3Participant
      8 participant=sv4Participant
currently-blacklisted 0 failed-to-send-result 0
```

sv1 creates one batch 94 ms before the switch (reference epoch 75) and one 1 ms after it; they were not matched to the sv4 and sv2 sends by message id (see Verdict).

```
grep -a 'sequencer=globalSequencerSv1' ../win-canton.log | grep -a -E 'T16:26:(23\.9|24\.0)' | grep -a 'LocalBatchCreated' | sed -E "$F" | cut -c1-250
```

```
2026-11-17T16:26:23.917Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..61a1) with reference epoch 75 for batch received from local mempool containing 1 requests (tag=send, messageId=64e0c379-e90f-44ad-8a89-4d03f7bb1e1a
2026-11-17T16:26:24.012Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..973f) with reference epoch 76 for batch received from local mempool containing 2 requests (tag=send, messageId=fd7bb4d0-aa0c-479e-b9be-3d2eafbe0aab
```

## 6. Blast radius

The next suite (SvTimeBasedOnboardingIntegrationTest, config 5bc994a9) starts on the unreset topology: sv1 stays in `SV1Initializer bootstrapping Dso`, Canton logs unauthorized DSO topology transactions, and sv1Scan times out after 5 min.

```
zcat canton_network_test.clog.gz | grep -a 'config=5bc994a9/SV=sv1"' | grep -a 'app initialization' | sed -E "$F" | cut -c1-200 | tail -1; zcat canton_network_test.clog.gz | grep -a 'config=5bc994a9' | grep -a -E '"level":"(WARN|ERROR)"' | sed -E "$F" | cut -c1-200 | head -1; zcat canton-simtime.clog.gz | grep -a -E 'T16:3[1-6]' | awk '/TOPOLOGY_UNAUTHORIZED_TRANSACTION/{a++} /Not fully authorized/{b++} END{print "TOPOLOGY_UNAUTHORIZED_TRANSACTION",a+0,"Not-fully-authorized",b+0}'
```

```
2026-11-17T16:31:49.460Z sv1 app initialization: SV1Initializer bootstrapping Dso started",[o.l.s.s.SvApp:SvTimeBasedOnboardingIntegrationTest/config=5bc994a9/SV=sv1] DEBUG
2026-11-17T16:36:51.297Z Timeout while waiting for initialization of sv1Scan",[o.l.s.c.ScanAppBackendReference:SvTimeBasedOnboardingIntegrationTest/config=5bc994a9/app=sv1Scan] ERROR
TOPOLOGY_UNAUTHORIZED_TRANSACTION 81 Not-fully-authorized 162
```

Its teardown then throws in UpdateHistorySanityCheckPlugin (sv2Scan has no app state, family H 11347 shape), the environment is not closed (config 5bc994a9 keeps logging), and the remaining suites fail in `Creating fixture` on the Prometheus port.

```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'not accessible|UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed|BindException' | sed -E 's/^[^Z]*Z //' | sort | uniq -c | cut -c1-200; echo "lines of config=5bc994a9 after 16:36:52: $(zcat canton_network_test.clog.gz | grep -a 'T16:36:5[2-9]' | grep -a -c 'config=5bc994a9')"
```

```
      4 [info]   Cause: java.net.BindException: Address already in use
      2 [info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed(UpdateHistorySanityCheckPlugin.scala:30)
      2 [info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed(UpdateHistorySanityCheckPlugin.scala:49)
      2 [info]   java.lang.RuntimeException: The app state of sv2Scan is currently not accessible. Node doesn't have any app state.
lines of config=5bc994a9 after 16:36:52: 205
```

## Verdict

Duplicate of 11255 (open, assignee rautenrieth-da, "Timeouts due to changing bft ordering topology"), family B.
Confirming grep of the family (2026-11-11 and 11409 entries): `blacklisted nodes = List()` on the size-4 epoch, zero
`currently blacklisted` and zero `Failed to send result`, a `Rejecting submission request by Some(PAR::sv3` with
`P2P connectivity is not ready` 64 ms after the switch whose push (`Attempting to push ... 861e2f202959`) precedes it
by 13 ms, pushes of the same transaction 69 and 32 ms before the switch with no synchronizer response, and the stuck
SV's last init line `Gave up waiting until Mediator has been granted unlimited traffic`. Not AM: no DAR uploads in the
wait.

Flake, cause in Canton BFT (the new ordering topology is activated before the newcomers are authenticated); the test
mitigation is splice#8363, open, not part of this triage. The teardown throw of the next suite is the known 11347
shape in family H. No fix branch.

Not verified: that the batch created at 16:26:23.917 contains the sv4 and sv2 sends (message ids were not matched),
and why sv1's own push of ec1f0574de16 at 16:26:23.891 left its flush cycle open. The duplicate close was not run
(GitHub is read-only here).
