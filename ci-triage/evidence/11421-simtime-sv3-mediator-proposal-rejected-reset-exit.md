# 11421 - simtime (4) exits without a report in SvTimeBasedRoundMgmt round management (run 43491683136)

DUPLICATE of 11255 (family B, rejected-send shape, same exit path as 11407). Post-merge main 6a996a302c (#8485),
job simtime (4), Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. The shard ran only
SvTimeBasedRoundMgmtIntegrationTest. In its test body (initDso, isolated environment) the BFT ordering topology grew
1 -> 4 at 19:37:04.003; the MediatorSynchronizerState proposal adding MED::sv3 (serial 4) got sv3's signature
sequenced 0.12 s before the switch, sv1's send was batched 2 ms before the switch and never ordered, and sv4's and
sv2's pushes were rejected 79 ms and 270 ms after it (`P2P connectivity is not ready`). sv3 waited on its mediator
traffic grant from 19:37:08.9 until the 5 min init wait timed out at 19:39:36.308; the teardown's
ResetDecentralizedNamespace was not effective within 1 minute, and ResetTopologyStatePlugin called sys.exit(1) at
19:40:52.403, so sbt exited with no test report.

- Run: https://github.com/canton-network/splice/actions/runs/43491683136, main 6a996a302c ("BQ: add config flag for
  which dataset to use for functions (#8485)"), job 132114646749 `simtime (4)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (`nix/canton-sources.json` at 6a996a302c, section 7).
- Component: Canton BFT ordering (sequencer onboarding window under sim time); exit path is the test harness.

Artifact (paths relative to the primary checkout): `log/11421/logs-simtime-4/` (`gh run download 43491683136 --repo canton-network/splice -n logs-simtime-4`).
In the commands `A=log/11421/logs-simtime-4` and the formatter is the generic one from recipes section 4 plus
`s/\\n/ /g`.

## 1. Job log: no report, sbt exit after 6.5 min of the only test

```
grep -n -E 'Test still running|Attempt 1 failed|Exceeded maximum retries' log/11421/job.log | tail -4 | cut -c1-200
```
```
13468:2026-11-13T19:40:50.2204595Z [info] *** Test still running after 6 minutes, 26 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management. 
13469:2026-11-13T19:40:54.2439989Z Attempt 1 failed with exit code 1, retrying
13470:2026-11-13T19:40:54.2443577Z Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
13746:2026-11-13T19:41:00.7747927Z [36;1m    echo "Exceeded maximum retries ($n / $MAX_RETRY), no more attempts << parameters.cmd_name >>" >&2[0m
```

## 2. Test log: sv3 init timeout, then the teardown reset fails as the last line

```
zcat $A/canton_network_test.clog.gz | grep -a -E 'Starting test suite|Starting .SvTime|Timeout while waiting for initialization|Resetting decentralized namespace|Failed to reset decentralized namespace' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | cut -c1-200
```
```
2026-11-13T19:34:22.736Z Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T19:34:22.762Z Starting 'SvTimeBasedRoundMgmtIntegrationTest/round management'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T19:39:36.308Z Timeout while waiting for initialization of sv3",[o.l.s.c.SvAppBackendReference:SvTimeBasedRoundMgmtIntegrationTest/config=b3fce8b6/app=sv3] ERROR
2026-11-13T19:39:52.242Z Resetting decentralized namespace to contain only sv1",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] INFO
2026-11-13T19:40:52.403Z Failed to reset decentralized namespace",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] ERROR
```
```
zcat $A/canton_network_test.clog.gz | tail -1 | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | cut -c1-160
```
```
2026-11-13T19:40:52.403Z Failed to reset decentralized namespace",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] ERROR
```
The sv3 timeout stack (not shown) is `SvAppReference.waitForInitialization` from `SpliceTests.initDso`
(SpliceTests.scala:313) in `SvTimeBasedRoundMgmtIntegrationTest.scala:13`; the reset stack is
`ResetTopologyStatePlugin.beforeEnvironmentDestroyed` via `manualDestroyEnvironment`.

## 3. Exit path: ResetTopologyStatePlugin calls sys.exit(1) after a failed reset

```
git show 6a996a302c:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala | grep -n -B2 -A2 'sys.exit'
```
```
37-        logger.error(msg)
38-        System.err.println(msg)
39:        sys.exit(1)
40-    }
41-  }
--
83-          s"Exceeded max retries for resetting $topologyType: $MAX_RETRIES, giving up"
84-        )
85:        sys.exit(1)
86-      }
87-      try {
--
105-        case e: Throwable =>
106-          logger.error(s"Failed to reset $topologyType", e)
107:          sys.exit(1)
108-      }
109-    }
```
Line 106-107 matches section 2: `Failed to reset decentralized namespace` is the last line, then the JVM exits.

## 4. Ordering topology 1 -> 4, two SV participant sends rejected right after

```
zcat $A/canton-simtime.clog.gz | grep -a -E 'New epoch 1[23] has started|Rejecting submission request by' | grep -a -E 'globalSequencerSv1' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | sed -E 's/with leaders = .*(activationTime)/\1/; s/weakQuorum.*\[/[/' | cut -c1-200
```
```
2026-11-13T19:36:57.182Z New epoch 12 has started activationTime = 1970-01-01T00:00:00.170004Z,   size = 1,   [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv1/psid=global-domain:
2026-11-13T19:37:04.003Z New epoch 13 has started activationTime = 1970-01-01T00:00:00.190002Z,   size = 4,   [c.d.c.s.s.b.b.c.m.c.i.IssConsensusModule:sequencer=globalSequencerSv1/psid=global-domain:
2026-11-13T19:37:04.082Z Rejecting submission request by Some(PAR::sv4::1220cf4452e9...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated
2026-11-13T19:37:04.273Z Rejecting submission request by Some(PAR::sv2::1220a19ebbbc...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated
```
Epoch 13 starts with an empty blacklist:
```
zcat $A/canton-simtime.clog.gz | grep -a 'New epoch 13 has started' | grep -a 'globalSequencerSv1' | grep -a -o 'blacklisted nodes = List([^)]*)' | head -1
```
```
blacklisted nodes = List()
```

## 5. The rejected and lost sends are the MED::sv3 MediatorSynchronizerState signatures

The proposal (tx hash eefc24ee5e31) adds MED::sv3:
```
zcat $A/canton-simtime.clog.gz | grep -a 'eefc24ee5e31' | grep -a -m1 'Enqueued topology transactions' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | sed -E 's/ signatures = .*//' | cut -c1-420
```
```
2026-11-13T19:37:03.764Z Enqueued topology transactions: ValidatedTopologyTransaction(   SignedTopologyTransaction(     TopologyTransaction(       MediatorSynchronizerState(         synchronizerId = global-domain::1220284b22b5...,         group = 0,         threshold = 2,         active = Seq(MED::sv1::122026319e6f..., MED::sv4::12200b7f6efa..., MED::sv2::1220ec91a338..., MED::sv3::12203d6b5082...)       ),       ser
```
Pushes per participant; only sv3's (before the switch) is accepted. sv4's push at .067 is the rejection at .082,
sv2's at .256 the rejection at .273 (section 4):
```
zcat $A/canton-simtime.clog.gz | grep -a 'eefc24ee5e31' | grep -a -E 'Attempting to push|responded the following|Storing topology transaction.*globalSequencerSv1|sequencer=globalSequencerSv1.*Storing' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | cut -c1-230
```
```
2026-11-13T19:37:03.766Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:eefc24ee5e31...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain
2026-11-13T19:37:03.886Z Storing topology transaction 1/1 TxHash(SHA-256:eefc24ee5e31...) with ts=EffectiveTime(1970-01-01T00:00:00.190Z), signedBy=Set(12208877045e...)",[c.d.c.t.p.TopologyStateProcessorImpl:sequencer=globalSequen
2026-11-13T19:37:03.886Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:eefc24ee5e31...)))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=gl
2026-11-13T19:37:03.906Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:eefc24ee5e31...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain
2026-11-13T19:37:04.067Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:eefc24ee5e31...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain
2026-11-13T19:37:04.256Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:eefc24ee5e31...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain
```

sv1's push (message id bcd0c024) is accepted into a local batch 2 ms before epoch 13 and never delivered (mixed
shape of 11409/11410):
```
zcat $A/canton-simtime.clog.gz | grep -a 'bcd0c024-ec6f-4544-bde8-4ad1862f12a6' | grep -a -E 'Sending message ID|sends request|LocalBatchCreated|validated to' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | cut -c1-250
```
```
2026-11-13T19:37:03.911Z Sending message ID bcd0c024-ec6f-4544-bde8-4ad1862f12a6 to sequencer SEQ::sv1::1220992234d9...",[c.d.c.s.c.RichSequencerClientImpl:participant=sv1Participant/psid=global-domain::1220284b22b5::37-0] DEBUG
2026-11-13T19:37:03.913Z 'PAR::sv1::12203291717c...' sends request with id 'bcd0c024-ec6f-4544-bde8-4ad1862f12a6' of size 790 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T19:37:04.001Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..1603) with reference epoch 13 for batch received from local mempool containing 1 requests (tag=send, messageId=bcd0c024-ec6f-4544-bde8-4ad1862f12a6
```
```
zcat $A/canton-simtime.clog.gz | grep -a -c 'bcd0c024-ec6f-4544-bde8-4ad1862f12a6.*validated to'
```
```
0
```
So the MED::sv3 add stays at sv3's signature only, and the DSO namespace never authorizes it.

## 6. sv3 waits on its mediator traffic grant until shutdown; sv2 and sv4 got theirs

```
zcat $A/canton_network_test.clog.gz | grep -a 'Mediator has been granted unlimited traffic' | grep -a -E 'SV=sv[234]' | grep -a -E 'Success|Gave up' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | cut -c1-200
zcat $A/canton_network_test.clog.gz | grep -a 'Waiting until Mediator has been granted unlimited traffic' | grep -a 'SV=sv3' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g' | cut -c1-200
```
```
2026-11-13T19:37:18.654Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=b3fce8b6/SV=sv4] INFO
2026-11-13T19:37:20.856Z Success: Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=b3fce8b6/SV=sv2] INFO
2026-11-13T19:39:52.054Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=b3fce8b6/
2026-11-13T19:37:08.916Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=b3fce8b6/SV=sv3] INFO
```
(The cut-off `Gave up` line is SV=sv3.)

## 7. Confirming greps (known-families 11255 rejected send) and Canton pin

```
for p in 'Failed to send result' 'currently blacklisted' 'Rejecting submission request by' 'Another flush cycle is currently ongoing'; do echo "$p: $(zcat $A/canton-simtime.clog.gz | grep -a -c "$p")"; done
```
```
Failed to send result: 0
currently blacklisted: 0
Rejecting submission request by: 2
Another flush cycle is currently ongoing: 24
```
Zero `Failed to send result` and zero blacklisting rule out the lost-verdict shape; the open outbox flush cycles
(24 lines, all four SV participants) are why the 1 minute namespace reset in the teardown cannot take effect.
```
git show 6a996a302c:nix/canton-sources.json | grep version
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## Verdict

Duplicate of 11255 (family B, rejected-send shape), the 11407 exit path: same suite and shard (SvTimeBasedRoundMgmt,
simtime (4)), same proposal (MED::sv3 add), sv3 init timeout followed by the ResetTopologyStatePlugin sys.exit(1).
Mixed hit as in 11409/11410: one signature batched just before the switch and never ordered, two rejected after it.
Third hit on Canton 3.7.4-snapshot.20261112 (after 11415, 11416). Flake in Canton BFT onboarding under sim time; the
fix belongs to the 11255 owner (Canton / test setup), no test-side fix here. Not verified: why sv1's batch BatchId
..1603 was never ordered in epoch 13 (not chased; the same unanswered-batch pattern is recorded under 11409/11410).
