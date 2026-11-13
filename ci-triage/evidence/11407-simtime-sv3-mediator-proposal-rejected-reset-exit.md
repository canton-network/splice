# 11407 - simtime (4): sv3 MediatorSynchronizerState pushes rejected at the 1 -> 4 switch, sv3 init times out, namespace reset fails and exits the JVM (run 43409630073)

DUPLICATE of 11255 (rejected-send shape, inside a test body). Main ee0c92e0fd, job simtime (4), Canton 3.7.3. In
SvTimeBasedRoundMgmtIntegrationTest "round management" the global ordering topology goes to 4 nodes at 10:46:49.570;
sv1's sequencer rejects the outbox pushes of sv3, sv1 and sv4 0.11-0.18 s later, all the serial-4
MediatorSynchronizerState proposal that adds MED::sv3, with `P2P connectivity is not ready (authenticated = 1 <
dissemination quorum = 2)`. The SV participants' outboxes then log `Another flush cycle is currently ongoing`, sv3
waits on `Mediator has been granted unlimited traffic` from 10:46:53.836, and the test gives up on sv3 at 10:49:21.252.
New compared with 11400: the JVM exit is not a validator app. The teardown's ResetDecentralizedNamespace submits its
proposals at 10:49:36 while the outboxes still report an open flush cycle, the namespace still has 4 members after
1 minute, and `ResetTopologyStatePlugin` calls `sys.exit(1)` at 10:50:36.145; hence no ScalaTest report and no stack
on the console (family H).

- Run: https://github.com/canton-network/splice/actions/runs/43409630073, main ee0c92e0fd ("Consolidate participant
  dashboards (#8470)"), job 131832827316 `ci / scala_test_sim_time / simtime (4)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at ee0c92e0fd).
- Component: Canton BFT ordering (P2P connectivity below dissemination quorum right after a topology change) under
  simulated time.

Commands run in `log/11407/logs-simtime-4/` unless a path says otherwise, with
`F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g'`.

## 1. Job: sbt exits with code 1 during "round management", no report, no stack

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11407/job.log | grep -a -n -E 'T10:(49:[2-5]|50:[0-3])' | grep -v 'ignore this line' | sed -E 's/^([0-9]+:)[^ ]+T([0-9:]+)\.[0-9]+Z /\1\2 /' | cut -c1-260 | head -6
```
```
13610:10:49:35 [info] *** Test still running after 5 minutes, 26 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management. 
13611:10:50:05 [info] *** Test still running after 5 minutes, 56 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management. 
13612:10:50:35 [info] *** Test still running after 6 minutes, 26 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management. 
13613:10:50:37 Attempt 1 failed with exit code 1, retrying
13614:10:50:37 Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
13615:10:50:37 ##[error]Error: failed to run script step (id 0f5e6150-df4f-11f1-9f10-35c63ffdfd3d): Error: step failed with return code 1
```

## 2. What exited: sv3 init timeout, then the namespace reset in the teardown

```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Starting '|Running setup" | sed -E "$F" | cut -c1-200
```
```
2026-11-13T10:44:08.360Z Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T10:44:08.384Z Starting 'SvTimeBasedRoundMgmtIntegrationTest/round management'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T10:44:11.177Z Starting creating environment: Running setup",[o.l.s.i.t.SvTimeBasedRoundMgmtIntegrationTest:SvTimeBasedRoundMgmtIntegrationTest] DEBUG
2026-11-13T10:44:11.560Z Finished creating environment: Running setup",[o.l.s.i.t.SvTimeBasedRoundMgmtIntegrationTest:SvTimeBasedRoundMgmtIntegrationTest] DEBUG
```
All WARN and ERROR lines of the test log (first distinct messages; these are the only two):
```
zcat canton_network_test.clog.gz | grep -a -E '"level":"(WARN|ERROR)"' | sed -E "$F" | cut -c1-420 | awk '{k=substr($0,26,90); if(!(k in s)){s[k]=1; print}}'
```
```
2026-11-13T10:49:21.252Z Timeout while waiting for initialization of sv3",[o.l.s.c.SvAppBackendReference:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/app=sv3] ERROR
2026-11-13T10:50:36.145Z Failed to reset decentralized namespace",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] ERROR
```
The reset in the teardown, and the last two lines of the test log:
```
zcat canton_network_test.clog.gz | grep -a 'ResetDecentralizedNamespace"' | grep -a -v 'still contains' | sed -E "$F" | cut -c1-250
zcat canton_network_test.clog.gz | grep -a -c 'still contains'
zcat canton_network_test.clog.gz | tail -2 | sed -E "$F" | cut -c1-300
```
```
2026-11-13T10:49:35.995Z Resetting decentralized namespace to contain only sv1",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] INFO
2026-11-13T10:49:35.996Z The following namespaces need to be removed from the decentralized namespace: Set(122040b93975..., 12204c507cea..., 1220535c3757...)",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] INFO
2026-11-13T10:49:36.136Z All required proposals to reset SV namespace submitted, waiting for it to be effective",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] INFO
2026-11-13T10:50:36.145Z Failed to reset decentralized namespace",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] ERROR
20
2026-11-13T10:50:36.145Z Decentralized namespace still contains Set(122040b93975..., 12204c507cea..., 1220535c3757..., 122077ef15e6...), waiting for it to be reset",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] INFO
2026-11-13T10:50:36.145Z Failed to reset decentralized namespace",[o.l.s.i.p.ResetDecentralizedNamespace:ResetDecentralizedNamespace] ERROR
```
The ERROR carries the stack trace (`zcat canton_network_test.clog.gz | grep -a 'Failed to reset decentralized namespace' | cut -c1-2500`),
which begins:
```
java.lang.IllegalStateException: Condition never became true within 1 minute\n\tat com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)\n\tat com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:120)\n\tat org.lfdecentralizedtrust.splice.integration.plugins.ResetDecentralizedNamespace.$anonfun$resetTopologyState$2(ResetDecentralizedNamespace.scala:155)
```
and passes through `ResetTopologyStatePlugin.beforeEnvironmentDestroyed(ResetTopologyStatePlugin.scala:33)`. The
plugin exits the JVM on that path:
```
git show ee0c92e0fd:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala | grep -n -B1 -A2 'Failed to reset'
```
```
105-        case e: Throwable =>
106:          logger.error(s"Failed to reset $topologyType", e)
107-          sys.exit(1)
108-      }
```

## 3. The 1 -> 4 switch and the rejected pushes

```
zcat canton-simtime.clog.gz | grep -a -E 'New epoch 1[23] has started' | grep -a 'sequencer=global' | sed -E 's/\{"@timestamp":"([^"]+)".*(blacklisted nodes = List\([^)]*\)).*(size = [0-9]+).*sequencer=([A-Za-z0-9]+).*/\1 \4 \3 \2/'
zcat canton-simtime.clog.gz | grep -a 'New epoch [0-9]* has started' | grep -a 'sequencer=global' | grep -a -v -c 'blacklisted nodes = List()'
zcat canton-simtime.clog.gz | grep -a -E "Rejecting submission request by" | sed -E "$F" | cut -c1-360
zcat canton-simtime.clog.gz | grep -a -c 'Failed to send result'; zcat canton-simtime.clog.gz | grep -a -c 'currently blacklisted'
```
```
2026-11-13T10:46:42.775Z globalSequencerSv1 size = 1 blacklisted nodes = List()
2026-11-13T10:46:49.570Z globalSequencerSv1 size = 4 blacklisted nodes = List()
2026-11-13T10:46:58.349Z globalSequencerSv3 size = 4 blacklisted nodes = List()
2026-11-13T10:46:58.646Z globalSequencerSv2 size = 4 blacklisted nodes = List()
2026-11-13T10:46:58.879Z globalSequencerSv4 size = 4 blacklisted nodes = List()
0
2026-11-13T10:46:49.683Z Rejecting submission request by Some(PAR::sv3::122040b93975...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:46:49.707Z Rejecting submission request by Some(PAR::sv1::122077ef15e6...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:46:49.746Z Rejecting submission request by Some(PAR::sv4::1220535c3757...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:47:09.144Z Rejecting submission request by Some(MED::sv2::12201c248f54...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:de7c6b61a0ab... is already marked as delivered)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv2] INFO
0
0
```
The three P2P rejections are outbox pushes of one transaction, TxHash 785a1cebb12d (output below reduced to the
push and request lines of the four SV participants; the grep also returns other senders' request lines):
```
zcat canton-simtime.clog.gz | grep -a -E 'T10:46:49\.[567]' | grep -a -E 'Attempting to push|sends request with id' | sed -E "$F" | cut -c1-330
```
```
2026-11-13T10:46:49.505Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:785a1cebb12d...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:46:49.519Z 'PAR::sv2::12204c507cea...' sends request with id '76d9a66a-1d08-45de-97d6-0f7a17cc80ab' of size 788 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:46:49.660Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:785a1cebb12d...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:46:49.668Z 'PAR::sv3::122040b93975...' sends request with id '663edf97-7b4c-43c2-bcfc-dccc77d6aa10' of size 787 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:46:49.696Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:785a1cebb12d...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:46:49.704Z 'PAR::sv1::122077ef15e6...' sends request with id 'df8723c0-32f7-473f-ba29-065cbc982a56' of size 796 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:46:49.735Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:785a1cebb12d...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:46:49.742Z 'PAR::sv4::1220535c3757...' sends request with id '9fe35ab6-62c9-4b06-a28b-0210a74b5d0b' of size 789 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
```
The transaction is the MediatorSynchronizerState at serial 4 with MED::sv3 added:
```
zcat canton-simtime.clog.gz | grep -a '785a1cebb12d' | grep -a -oE 'TopologyTransaction\(\\n *[A-Za-z]+\(' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
zcat canton-simtime.clog.gz | grep -a '785a1cebb12d' | grep -a -oE 'active = Seq\([^)]*\)|serial = [0-9]+' | sed -E 's/1220[0-9a-f]{60}/../g' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
zcat canton-simtime.clog.gz | grep -a '663edf97-7b4c-43c2-bcfc-dccc77d6aa10' | grep -a 'max sequencing time' | sed -E "$F" | cut -c1-330
```
```
16 TopologyTransaction(\n      MediatorSynchronizerState(
16 TopologyTransaction(\n  SignedTopologyTransaction(
16 active = Seq(MED::sv1::12209e33d43c..., MED::sv4::1220a9b8716a..., MED::sv2::12201c248f54..., MED::sv3::122055c5c26a...)
16 serial = 4
2026-11-13T10:46:49.669Z Request to send submission with id 663edf97-7b4c-43c2-bcfc-dccc77d6aa10 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv3::122040b93975... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220b34bbd94::37-0] DEBUG
```
sv2 pushed 51 ms before the switch and was not rejected; sv3, sv1 and sv4 pushed after it and were. Max sequencing
time is 00:00:20 on a sim clock near 00:00:00.18 (the same request logs `Estimated sequencing time
1970-01-01T00:00:00.180Z`).

## 4. Stuck outboxes; sv3 waits on its mediator; sv3_validator_user never created

```
zcat canton-simtime.clog.gz | grep -a 'T10:4[6-9]\|T10:50' | grep -a 'Another flush cycle' | sed -E 's/.*participant=([^"\/]*).*/\1/' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
zcat canton-simtime.clog.gz | grep -a -E 'T10:(49:[3-5]|50:[0-3])' | grep -a 'Another flush cycle' | sed -E "$F" | cut -c1-260
zcat canton_network_test.clog.gz | grep -a 'SV=sv3' | grep -a 'Mediator has been granted unlimited traffic' | grep -a -v 'Check whether' | sed -E "$F" | cut -c1-200
zcat canton_network_test.clog.gz | grep -a -E "Waiting for user sv._validator_user|Got 'user sv._validator" | sed -E "$F" | cut -c1-200
```
```
8 sv3Participant
8 sv2Participant
4 sv1Participant
7 sv4Participant
2026-11-13T10:49:36.039Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv3Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:49:36.070Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv2Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:49:36.101Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv4Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:49:36.131Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=sv1Participant/psid=global-domain::1220b34bbd94::37-0] DEBUG
2026-11-13T10:46:53.836Z Waiting until Mediator has been granted unlimited traffic",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/SV=sv3] INFO
2026-11-13T10:49:35.806Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down",[o.l.s.s.LocalSynchronizerNode:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/SV=sv3] INFO
2026-11-13T10:44:20.102Z Waiting for user sv4_validator_user-d581463e",[o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/validator=sv4Validator] INFO
2026-11-13T10:44:20.484Z Waiting for user sv3_validator_user-d581463e",[o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/validator=sv3Validator] INFO
2026-11-13T10:44:20.898Z Waiting for user sv2_validator_user-d581463e",[o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/validator=sv2Validator] INFO
2026-11-13T10:44:21.247Z Waiting for user sv1_validator_user-d581463e",[o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/validator=sv1Validator] INFO
2026-11-13T10:45:44.629Z Got 'user sv1_validator_user-d581463e': ()",[o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/validator=sv1Validator] INFO
2026-11-13T10:47:11.220Z Got 'user sv4_validator_user-d581463e': ()",[o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/validator=sv4Validator] INFO
2026-11-13T10:47:12.686Z Got 'user sv2_validator_user-d581463e': ()",[o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/config=d581463e/validator=sv2Validator] INFO
```
(The second command was run with an extra `DecentralizedNamespaceDefinition` alternative that matched nothing more
in the first 12 lines.) The four flush-cycle lines at 10:49:36.039-.131, one per SV participant, fall between the
reset's start (10:49:35.995) and its `All required proposals ... submitted` (10:49:36.136). The test's init wait
ended (10:49:21) before sv3Validator ran out of `Get user` retries, so the validator did not exit the JVM as in
11400; the reset did.

## Verdict

Duplicate of 11255, rejected-send shape: the serial-4 MediatorSynchronizerState pushes of sv3, sv1 and sv4 rejected
0.11-0.18 s after the 1 -> 4 switch, `blacklisted nodes = List()` on every epoch, zero `Failed to send result`, zero
`currently blacklisted`, sv3's last wait `Mediator has been granted unlimited traffic`. Same suite and test as 11400
and 11289. Flake, Canton BFT under sim time; no new fix location. New detail for the family: the JVM exit can come
from `ResetTopologyStatePlugin` (`sys.exit(1)` after the 1 minute reset wait, logged only in the test log), with
nothing on the console.

Not verified: whether sv2's push (accepted 51 ms before the switch) was ordered; that the reset's proposals were held
back by the open flush cycles is inferred from the four flush-cycle lines, not followed per message id. The run's
commit #8470 touches only Grafana dashboard files (`git show --stat ee0c92e0fd`: 7 files, e.g.
`grafana-dashboards/canton/participant.json`).
