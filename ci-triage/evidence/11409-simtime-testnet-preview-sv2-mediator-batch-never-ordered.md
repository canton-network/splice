# 11409 - simtime (5): MED::sv2 MediatorSynchronizerState signatures of sv1 and sv2 batched 86 ms before the 1 -> 4 switch and never ordered, sv4's rejected; sv2 never initializes, three later suites fail (run 43409630073)

DUPLICATE of 11255 (rejected-send / batch-never-ordered shape). Main ee0c92e0fd, job simtime (5), Canton 3.7.3.
Tests: succeeded 6, failed 3, 1 suite aborted. In the setup of TimeBasedTestNetPreviewIntegrationTest the global
ordering topology goes from 1 to 4 nodes at 10:57:54.267. The MediatorSynchronizerState proposal that adds MED::sv2
(serial 4) is pushed by sv3 at 10:57:53.979 (accepted), by sv2 and sv1 at 10:57:54.166/.171 (accepted by the
mempool, put into a local batch at 10:57:54.182 with reference epoch 91, disseminated at 10:57:54.269 after the
switch and never ordered), and by sv4 at 10:57:54.304 (rejected: `P2P connectivity is not ready (authenticated = 1
< dissemination quorum = 2)`). The proposal stays at one signature, sv2's SV app waits on `Mediator has been
granted unlimited traffic` until the setup gives up at 11:03:24. The setup failure skips the namespace reset, so
ValidatorFaucetCapZeroTimeBased, ExternallySignedTxsTimeBased and SvTimeBasedBootstrappingRound fail in their own
environment creation (blast radius). Not caused by #8470 (dashboards only).

- Run: https://github.com/canton-network/splice/actions/runs/43409630073, main ee0c92e0fd ("Consolidate participant
  dashboards (#8470)"), job 131832827184 `ci / scala_test_sim_time / simtime (5)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at ee0c92e0fd).
- Component: Canton BFT ordering under simulated time.

Artifact `logs-simtime-5` in `log/11409/logs-simtime-5/`; the commands below run in that directory unless they name
`log/11409/job.log`.

## 1. Job: 3 failed, 1 aborted

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11409/job.log | grep -a -E '^\S+Z \[info\] (- .*FAILED|org\.lf.*ABORTED|Run completed|Tests:)' | sed -E 's/^([^ ]+T)([0-9:]+)\.[0-9]+Z /\2 /'
```
```
11:03:24 [info] - TestNet initializes correctly *** FAILED ***
11:04:25 [info] - system works with optValidatorFaucetCap=0 and handles stale liveness records *** FAILED ***
11:09:38 [info] - Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance *** FAILED ***
11:14:53 [info] org.lfdecentralizedtrust.splice.integration.tests.SvTimeBasedBootstrappingRoundIntegrationTest *** ABORTED ***
11:14:53 [info] Run completed in 30 minutes, 51 seconds.
11:14:53 [info] Tests: succeeded 6, failed 3, canceled 0, ignored 0, pending 0
```

## 2. Suite timeline: the first failure is the setup of TimeBasedTestNetPreviewIntegrationTest

```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test failed: |Aborted test suite|has been reset|creating environment: Running setup" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | awk '$1 >= "2026-11-13T10:55:55"'
```
```
2026-11-13T10:55:55.336Z decentralized namespace has been reset",
2026-11-13T10:55:55.348Z sequencer domain state threshold has been reset",
2026-11-13T10:55:55.387Z Starting test suite 'TimeBasedTestNetPreviewIntegrationTest'...",
2026-11-13T10:56:28.826Z Starting creating environment: Running setup",
2026-11-13T11:03:24.207Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",
2026-11-13T11:03:24.414Z Test failed: 'TimeBasedTestNetPreviewIntegrationTest/TestNet initializes correctly', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:234), l
2026-11-13T11:03:24.428Z Starting test suite 'ValidatorFaucetCapZeroTimeBasedIntegrationTest'...",
2026-11-13T11:04:24.856Z Test failed: 'ValidatorFaucetCapZeroTimeBasedIntegrationTest/system works with optValidatorFaucetCap=0 and handles stale liveness records', message: Creating environment faile
2026-11-13T11:04:24.865Z Starting test suite 'ExternallySignedTxsTimeBasedIntegrationTest'...",
2026-11-13T11:04:38.528Z Starting creating environment: Running setup",
2026-11-13T11:09:38.622Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",
2026-11-13T11:09:38.671Z Test failed: 'ExternallySignedTxsTimeBasedIntegrationTest/Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance',
2026-11-13T11:09:38.682Z Starting test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'...",
2026-11-13T11:09:52.495Z Starting creating environment: Running setup",
2026-11-13T11:14:52.613Z Failed creating environment: Running setup (EnvironmentSetup.scala:234)",
2026-11-13T11:14:52.672Z Aborted test suite 'SvTimeBasedBootstrappingRoundIntegrationTest'.",
```
No `has been reset` after 10:55:55 (4 such lines in the whole log, all from the two suites before).

## 3. The 1 -> 4 switch and the rejections

Global sequencer of sv1: epoch size changes and every rejected submission in the setup window.
```
zcat canton-simtime.clog.gz | grep -a -E 'T1[01]:(5[6-9]|0[0-3])' | grep -a -E "New epoch [0-9]+ has started.*sequencer=globalSequencerSv1|Rejecting submission request by " | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220([0-9a-f]{12})[0-9a-f]{56}/1220\1.../g' | awk '/New epoch/{match($0,/size = [0-9]+/); s=substr($0,RSTART,RLENGTH); if(s!=prev){match($0,/blacklisted nodes = [^;]*/); print substr($0,1,45) " ... " s ", " substr($0,RSTART,RLENGTH); prev=s}; next} {print substr($0,1,235)}'
zcat canton-simtime.clog.gz | grep -a -c 'Failed to send result'
zcat canton-simtime.clog.gz | grep -a -c 'currently blacklisted'
```
```
2026-11-13T10:56:05.821Z New epoch 83 has sta ... size = 1, blacklisted nodes = List()
2026-11-13T10:57:54.267Z New epoch 92 has sta ... size = 4, blacklisted nodes = List()
2026-11-13T10:57:54.314Z Rejecting submission request by Some(PAR::sv4::1220a6d79861...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)","logger
2026-11-13T10:58:05.923Z Rejecting submission request by Some(MED::sv4::122070e78df7...) with SequencerDeliverError(code = SEQUENCER_AGGREGATE_SUBMISSION_ALREADY_SENT, message = Not accepting submission as aggregation ID SHA-256:7b7a39e2c3d7... is already mar
0
0
```

## 4. The pushes of the MED::sv2 add around the switch

Outbox pushes to the global synchronizer (time, participant, tx hash), script `log/11409/g3-push.sh`:
```
zcat canton-simtime.clog.gz | grep -a -E 'T10:57:5[3-5]' | grep -a 'QueueBasedSynchronizerOutbox' | grep -a 'Attempting to push' | grep -a "Synchronizer 'global'" | sed -E 's/\{"@timestamp":"([^"]+)","message":"Attempting to push ([0-9]+) topology transactions.*List\((.*)\)\)","logger_name":"[^:]*:participant=(sv[0-9])Participant.*/\1 \4 \3/; s/TxHash\(SHA-256:([0-9a-f]{12})[0-9a-f.]*\)?/\1/g'
for h in a5075e4e3ca5 25629cd7f880; do zcat canton-simtime.clog.gz | grep -a "$h" | grep -a -oE 'active = [^)]{0,140}' | awk '!s[$0]++' | head -1 | sed -E 's/1220([0-9a-f]{8})[0-9a-f]{60}/1220\1/g'; done
```
```
2026-11-13T10:57:53.433Z sv2 a5075e4e3ca5
2026-11-13T10:57:53.482Z sv1 a5075e4e3ca5
2026-11-13T10:57:53.684Z sv3 a5075e4e3ca5
2026-11-13T10:57:53.979Z sv3 25629cd7f880
2026-11-13T10:57:54.166Z sv2 25629cd7f880
2026-11-13T10:57:54.171Z sv1 25629cd7f880
2026-11-13T10:57:54.304Z sv4 25629cd7f880
active = Seq(SEQ::sv1::1220a6e562ca..., SEQ::sv3::1220bea75468..., SEQ::sv4::122061999da1..., SEQ::sv2::122054bd1009...
active = Seq(MED::sv1::1220d9aeed4c..., MED::sv3::12205ec3e030..., MED::sv4::122070e78df7..., MED::sv2::1220c94b03b2...
```
a5075e4e3ca5 is the SequencerSynchronizerState that makes the topology 4 nodes; 25629cd7f880 is the
MediatorSynchronizerState (serial 4) adding MED::sv2.

What each outbox got back (sv3 accepted; sv2, sv1, sv4 never get a response and their flush cycle stays open):
```
zcat canton-simtime.clog.gz | grep -a -E 'T10:5[789]|T11:0[0-3]' | grep -a -E 'QueueBasedSynchronizerOutbox:participant=sv[0-9]Participant/psid=global' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"[^:]*:participant=(sv[0-9])[^"]*".*"level":"([A-Z]+)".*/ | \1 \2/' | awk '$1>="2026-11-13T10:57:54.2"' | grep -v 'Attempting to push' | head -9
```
```
2026-11-13T10:57:54.206Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:25629cd7f880...))) | sv3 DEBUG
2026-11-13T10:57:54.248Z Marked flush as done. Current queue size: 0. IsClosing: false | sv3 DEBUG
2026-11-13T10:57:54.302Z Invoked flush with queue size 1 | sv4 DEBUG
2026-11-13T10:59:46.368Z Invoked flush with queue size 2 | sv2 DEBUG
2026-11-13T10:59:46.368Z Another flush cycle is currently ongoing | sv2 DEBUG
2026-11-13T10:59:53.506Z Invoked flush with queue size 2 | sv1 DEBUG
2026-11-13T10:59:53.506Z Another flush cycle is currently ongoing | sv1 DEBUG
2026-11-13T10:59:55.405Z Invoked flush with queue size 2 | sv4 DEBUG
2026-11-13T10:59:55.405Z Another flush cycle is currently ongoing | sv4 DEBUG
```

## 5. sv1's and sv2's sends: one local batch 86 ms before the switch, never ordered

Trace of sv2's push (trace id 00fd355208144ff3244406cae9a59ca2; sv1's is 146a69cbaa888f946a64d5f79a703ca7, message
id 349d7de8-8ce7-4dea-bd77-08791fa09031, same steps 3 ms later):
```
zcat canton-simtime.clog.gz | grep -a 00fd355208144ff3244406cae9a59ca2 | grep -a -E 'sends request with id|max sequencing time|Mempool accepting|Created batch' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ | \1 \2/; s/1220([0-9a-f]{8})[0-9a-f]{60}/1220\1/g' | cut -c1-330
```
```
2026-11-13T10:57:54.172Z 'PAR::sv2::1220d715a4e2...' sends request with id 'a823a40a-816b-4144-b1aa-6b1078ccbfc4' of size 795 bytes with 1 envelopes to ALL=1. | c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1 INFO
2026-11-13T10:57:54.173Z Request to send submission with id a823a40a-816b-4144-b1aa-6b1078ccbfc4 with max sequencing time 1970-01-01T03:07:21Z from PAR::sv2::1220d715a4e2... to Set(All).  | c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::12208eda30e7::37-0 DEBUG
2026-11-13T10:57:54.174Z Mempool accepting client request with tag 'send' of size 792 | c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12208eda30e7::37-0 DEBUG
2026-11-13T10:57:54.181Z Created batch pekko_actor from traceIds: [00fd355208144ff3244406cae9a59ca2,146a69cbaa888f946a64d5f79a703ca7] | c.d.c.s.s.b.b.b.p.PekkoModuleSystem$PekkoActorContext:sequencer=globalSequencerSv1/psid=global-domain::12208eda30e7::37-0 DEBUG
```
The batch and everything logged about it afterwards (2 lines after 10:57:54.186, the last at 10:57:54.269):
```
zcat canton-simtime.clog.gz | grep -a 'T10:57:54.182' | grep -a 'LocalBatchCreated' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-330
zcat canton-simtime.clog.gz | grep -a '1220a18a029611d3' | awk -F'"@timestamp":"' '{split($2,a,"\""); if (a[1] > "2026-11-13T10:57:54.186") print}' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-250
zcat canton-simtime.clog.gz | grep -a -E 'a823a40a-816b-4144-b1aa-6b1078ccbfc4|349d7de8-8ce7-4dea-bd77-08791fa09031' | grep -a -c 'Deliver'
```
```
2026-11-13T10:57:54.182Z Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(1220a18a029611d3286782b6291dec94a691adde86b10ad05371ba3b9d94392715bb) with reference epoch 91 for batch received from local mempool containing 2 requests (tag=send, messageId=a823a40a-816b-4144-b1aa-
2026-11-13T10:57:54.268Z Availability.LocalDissemination.LocalBatchesStoredSigned: local batches List(Traced(BatchId(1220a18a029611d3286782b6291dec94a691adde86b10ad05371ba3b9d94392715bb))(TraceContext(trace id=df304da27aa20185dfc4284b257c3e1e, W3C context=W3CT
2026-11-13T10:57:54.269Z Availability.LocalDissemination.LocalBatchesStoredSigned: disseminating BatchId(1220a18a029611d3286782b6291dec94a691adde86b10ad05371ba3b9d94392715bb) to SEQ::sv3::1220bea75468587ed239bc88fc1804e3c3a5188d9f57e8cc113973b39669958a7a0a, SE
0
```
Max sequencing time is sim time 03:07:21 against an estimated sequencing time of 03:07:01.253, so the two sends
stay possibly-sequenced and the outboxes never close their flush (section 4).

## 6. sv2 waits on its mediator; the next suites inherit the unreset state

```
zcat canton_network_test.clog.gz | grep -a -E 'T10:5[6-9]|T11:0[0-3]' | grep -a -E "\"message\":\"(Got 'user sv[0-9]_validator_user|Waiting until Mediator has been granted|Gave up waiting until Mediator|sv2Validator app initialization: Wait for user failed)" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"([^"]*)".*/ | \1/' | cut -c1-200
```
```
2026-11-13T10:56:35.221Z Got 'user sv1_validator_user-730cdb17': () | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=730cdb17/validator=sv1Validator
2026-11-13T10:57:58.688Z Waiting until Mediator has been granted unlimited traffic | o.l.s.s.LocalSynchronizerNode:TimeBasedTestNetPreviewIntegrationTest/config=730cdb17/SV=sv2
2026-11-13T10:57:58.730Z Waiting until Mediator has been granted unlimited traffic | o.l.s.s.LocalSynchronizerNode:TimeBasedTestNetPreviewIntegrationTest/config=730cdb17/SV=sv3
2026-11-13T10:58:00.187Z Waiting until Mediator has been granted unlimited traffic | o.l.s.s.LocalSynchronizerNode:TimeBasedTestNetPreviewIntegrationTest/config=730cdb17/SV=sv4
2026-11-13T10:58:22.444Z Got 'user sv3_validator_user-730cdb17': () | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=730cdb17/validator=sv3Validator
2026-11-13T10:58:23.664Z Got 'user sv4_validator_user-730cdb17': () | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=730cdb17/validator=sv4Validator
2026-11-13T11:03:24.230Z sv2Validator app initialization: Wait for user failed | o.l.s.v.ValidatorApp:TimeBasedTestNetPreviewIntegrationTest/config=730cdb17/validator=sv2Validator
2026-11-13T11:03:24.346Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down | o.l.s.s.LocalSynchronizerNode:TimeBasedTestNetPreviewIntegrationTest/config=730cdb
```
sv3 and sv4 get past the wait (their validator users appear); sv2 never does. Here the setup timeout (11:03:24.207) came before sv2Validator's `Get user` retries ran out, so the JVM survived
and the report exists (in 11410 the JVM exited first).

Later suites:
```
zcat canton_network_test.clog.gz | grep -a 'T11:04:24.835' | grep -a -E 'Request failed for remote participant|Failed creating environment' | sed -E 's/\\n */ /g' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":.*//; s/1220([0-9a-f]{8})[0-9a-f]{60}/1220\1/g' | cut -c1-400
zcat canton_network_test.clog.gz | grep -a 'T11:14:52.421' | grep -a 'Check whether Party DSO' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220([0-9a-f]{8})[0-9a-f]{60}/1220\1/g' | cut -c1-260
```
```
2026-11-13T11:04:24.835Z Request failed for remote participant for `sv1Validator``. GrpcServerError: INTERNAL/Task proposeAndAuthorize-wait-for-effective did not complete within 1 minute. Request: Propose(BaseWriteRequest(Some(3.7.2)),Right(VettedPackages(participantId = PAR::sv1::1220cf8c8e61..., packages = \"\")),List(),Replace,None,false,ForceFlags(Set()),Synchronizer(Left(global-domain::12208e
2026-11-13T11:04:24.835Z Failed creating environment: Creating test console (EnvironmentSetup.scala:173)
2026-11-13T11:14:52.421Z The operation 'Check whether Party DSO-7ff12598-7ff12598::12208eda30e7... is allocated on PAR::sv1::1220cf8c8e61... and establish it if needed' failed with a retryable error (full stack trace omitted): INVALID_ARGUMENT: An error occurred. Please contact the operator and inquire about the request 1e47aac3fcb07def
```
ValidatorFaucetCapZero fails in `Creating test console`: the sv1 participant's unvetting proposal does not become
effective within 1 min (sv1's global outbox is still in the open flush cycle, section 4: `Another flush cycle is
currently ongoing` for sv1 up to 11:03:24.786). The two suites after it run the setup for 5 min each and fail with sv1
unable to establish the DSO party, as in the earlier setup-step hits (11324, 11332, 11399).

## Verdict

Duplicate of 11255, simulated time, 1 -> 4 step: of the four SV signatures on the MED::sv2 MediatorSynchronizerState
add, one is sequenced, two sit in a batch created 86 ms before the switch that is never ordered (the 11326 variant),
one is rejected with `P2P connectivity is not ready`. Zero `Failed to send result`, zero `currently blacklisted`,
`blacklisted nodes = List()`. Flake in Canton BFT ordering; no splice change beyond what 11255 tracks. Setup-step
hit, so three more suites fail.

Not verified: why the batch gets no dissemination acks after the switch (the newcomers' availability modules were not
examined); the INVALID_ARGUMENT text of the DSO party allocation in the last suite (participant log not read).
