# 11406 - simtime (0): splitwellValidator confirmation response rejected at the 1 -> 4 switch, createSplitwellRules never gets a verdict, validator exits the JVM (run 43409630073)

DUPLICATE of 11255 (rejected-send shape, here the participant's own confirmation response). Main ee0c92e0fd, job
simtime (0), Canton 3.7.3. In the setup of TrafficBasedRewardsSvAppTimeBasedIntegrationTest the global ordering
topology goes from 1 to 4 nodes at 10:47:36.589. splitwellValidator's `createSplitwellRules` request was sequenced
0.04 s before (block 189), and its confirmation response to the mediator, sent 3 ms after the switch, is rejected by
sv1's sequencer with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. The participant
ignores the error (`may still be possibly sequenced`) and the request never gets a verdict on a sim clock that stays
at about 00:00:01. The validator's next command, `createValidatorRight` (submitted 10:47:53.258, sequenced
10:47:58.262), does not complete either; its call times out after 2 min, the retries get
`SUBMISSION_ALREADY_IN_FLIGHT` (63 lines), the low-priority circuit breaker trips at 10:50:32.161,
`createValidatorRight` gives up after 20 retries and splitwellValidator exits the JVM at 10:50:35 during the setup
of the first test. No ScalaTest report.

- Run: https://github.com/canton-network/splice/actions/runs/43409630073, main ee0c92e0fd ("Consolidate participant
  dashboards (#8470)"), job 131832827180 `ci / scala_test_sim_time / simtime (0)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at ee0c92e0fd).
- Component: Canton BFT ordering (P2P connectivity below the dissemination quorum right after a topology change)
  under simulated time.

`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.
Artifact `logs-simtime-0` (14030640123) in `log/11406/logs-simtime-0/`; commands run there unless they name `log/11406/job.log`.

## 1. Job: JVM exit in the first suite's setup, no report

Classification grep of the job log.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]|app initialization: Initialization failed|^\S+ Caused by|Attempt 1 failed' | sed -E 's/^[^Z]*Z //' | cut -c1-200
```
```
splitwellValidator app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: splitwellValidator app initialization: Initialize node failed
Caused by: java.lang.RuntimeException: splitwellValidator app initialization: Set up app instance splitwell failed
Caused by: io.grpc.StatusRuntimeException: ABORTED: Command submission aborted by circuit breaker due to too many successive failures, next attempt in 30s
Caused by: io.grpc.StatusRuntimeException: ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,7632b97f): The submission is already in-flight
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id f9ddecb0-df4e-11f1-a395-8ffa3216fc00): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

## 2. Suite timeline

Only the first suite started; its environment setup never finished and no namespace reset ran (the JVM exited).
```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |creating environment: Running setup|has been reset" | sed -E "$F" | sed -E 's/"logger_name.*//' | cut -c1-160
```
```
2026-11-13T10:43:28.131Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T10:44:20.489Z Starting creating environment: Running setup",[o.l.s.i.t.TrafficBasedRewardsSvAppTimeBasedIntegrationTest:TrafficBasedRewardsSvAppTimeB
```

## 3. splitwellValidator stops in "Set up app instance splitwell"

Last init steps of splitwellValidator, the breaker and the give-up.
```
zcat canton_network_test.clog.gz | grep -a 'splitwellValidator' | grep -a -E 'app initialization: .*(started|failed)|tripped after|Total maximum number of retries' | sed -E "$F" | cut -c1-200 | tail -7
```
```
2026-11-13T10:47:25.041Z splitwellValidator app initialization: Set up app instance splitwell started",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=318e573a/validator=
2026-11-13T10:50:32.161Z Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure",[o.l.s.u.SpliceCircuitBreaker:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/confi
2026-11-13T10:50:34.870Z The operation 'createValidatorRight' has failed with an exception. Total maximum number of retries 20 exceeded. Giving up. ",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTime
2026-11-13T10:50:34.871Z splitwellValidator app initialization: Set up app instance splitwell failed",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=318e573a/validator=s
2026-11-13T10:50:34.871Z splitwellValidator app initialization: Initialize node failed",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=318e573a/validator=splitwellValida
2026-11-13T10:50:34.871Z splitwellValidator app initialization: Initialize app failed",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=318e573a/validator=splitwellValidat
2026-11-13T10:50:34.872Z splitwellValidator app initialization: Initialization failed",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=318e573a/validator=splitwellValidat
```

Number of SUBMISSION_ALREADY_IN_FLIGHT lines of splitwellValidator.
```
zcat canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -c SUBMISSION_ALREADY_IN_FLIGHT
```
```
63
```

## 4. 11255 confirming grep: rejections right after the switch to 4 orderers, no lost verdicts, no blacklisting

Ordering topology sizes on the global synchronizer, P2P rejections, and the counts that rule out the lost-verdict and breaker shapes.
```
zcat canton-simtime.clog.gz | awk '{ if(/Failed to send result/) b++; if(/currently blacklisted/) bl++; if(/New epoch .*size = [0-9]/ && !/splitwell/){match($0,/"@timestamp":"[^"]+"/); last=substr($0,RSTART+14,24); match($0,/size = [0-9]+/); sz=substr($0,RSTART,RLENGTH); if(sz!=psz){print "epoch " last " " sz; psz=sz}} if(/Rejecting submission request by/ && /P2P connectivity is not ready/){match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+14,24); match($0,/Rejecting submission request by [^ ]+/); print "reject " t " " substr($0,RSTART,70)} } END{print "Failed to send result: " b+0 "; currently blacklisted: " bl+0}'
```
```
epoch 2026-11-13T10:45:03.468Z size = 1
epoch 2026-11-13T10:47:36.589Z size = 4
reject 2026-11-13T10:47:36.605Z Rejecting submission request by Some(PAR::splitwellValidator::12207f5c
reject 2026-11-13T10:47:36.893Z Rejecting submission request by Some(PAR::sv1::12204902fe29...) with S
Failed to send result: 0; currently blacklisted: 0
```

The size-4 epoch.
```
zcat canton-simtime.clog.gz | grep -a 'New epoch' | grep -a 'size = 4' | grep -a -v splitwell | head -1 | sed -E "$F" | cut -c1-175
```
```
2026-11-13T10:47:36.589Z New epoch 13 has started with leaders = List(SEQ::sv2::..a259, SEQ::sv3::..b01a, SEQ::sv4::..5049, SEQ::sv1::..6b6f)and blacklisted nodes = List(); or
```

Sends and rejections of splitwellValidator and sv1 at sv1's sequencer in 10:47:36.
```
zcat canton-simtime.clog.gz | grep -a -E 'T10:47:36' | grep -a -E 'PAR::(splitwellValidator|sv1)' | grep -a -E 'sends request with id|Rejecting submission' | grep -a globalSequencerSv1 | sed -E "$F" | cut -c1-330
```
```
2026-11-13T10:47:36.462Z 'PAR::splitwellValidator::12207f5c07ad...' sends request with id 'beddec6b-34be-4e01-b603-f7f303b80047' of size 2940 bytes with 3 envelopes to MGR=1, PAR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:47:36.592Z 'PAR::splitwellValidator::12207f5c07ad...' sends request with id 'b39cf14b-4a0c-4f5e-ab54-edc8d8d60478' of size 758 bytes with 1 envelopes to MGR=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:47:36.605Z Rejecting submission request by Some(PAR::splitwellValidator::12207f5c07ad...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:47:36.889Z 'PAR::sv1::12204902fe29...' sends request with id '4f2a7dc8-5316-472c-a176-6ae3535324a9' of size 5368 bytes with 3 envelopes to MGR=1, PAR=4.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:47:36.893Z Rejecting submission request by Some(PAR::sv1::12204902fe29...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
```

## 5. The rejected send is the confirmation response for createSplitwellRules

The request (message beddec6b) was sequenced before the switch; trace dbd2b913 is the createSplitwellRules submission.
```
zcat canton-simtime.clog.gz | grep -a -E 'dbd2b9138a88f949c430462a1ecc89db|beddec6b-34be-4e01-b603-f7f303b80047' | grep -a -E 'Submitted commands are|is now in-flight|max sequencing time|validated to: Deliver|Phase 2: Registered' | grep -a -E 'splitwellParticipant|globalSequencerSv1|globalMediatorSv1' | sed -E "$F" | sed -E 's/\\\\n */ /g' | cut -c1-300
```
```
2026-11-13T10:47:36.397Z Submitted commands are: 'create Splice.Splitwell:SplitwellRules'",[c.d.c.p.a.s.c.CommandSubmissionServiceImpl:participant=splitwellParticipant] DEBUG
2026-11-13T10:47:36.446Z Command org.lfdecentralizedtrust.splice.splitwell.createSplitwellRules_1dd34315d144ae09b32c152c46a00da72e3b99f051929c288d5d7f724f87372c is now in-flight.",[c.d.c.p.s.CantonSyncService:participant=splitwellParticipant] DEBUG
2026-11-13T10:47:36.463Z Request to send submission with id beddec6b-34be-4e01-b603-f7f303b80047 with max sequencing time 1970-01-01T00:01:00Z from PAR::splitwellValidator::12207f5c07ad... to Set(MediatorGroupRecipient(group = 0), MemberRecipient(PAR::splitwellValidator::12207f5c07ad...)). ",[c.d.c.
2026-11-13T10:47:36.551Z At block 189, the submission request beddec6b-34be-4e01-b603-f7f303b80047 at 1970-01-01T00:00:00.190Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:sequencer=globalSequencerSv1/psid=global-domain::1220231690d5::37-0] DEBUG
2026-11-13T10:47:36.572Z Phase 2: Registered request=1970-01-01T00:00:00.190Z from submittingParticipant=PAR::splitwellValidator::12207f5c07ad... with 1 view(s).",[c.d.c.s.m.ConfirmationRequestAndResponseProcessor:mediator=globalMediatorSv1/psid=global-domain::1220231690d5::37-0] INFO
2026-11-13T10:47:36.593Z Request to send submission with id b39cf14b-4a0c-4f5e-ab54-edc8d8d60478 with max sequencing time 1970-01-01T00:00:30.190Z from PAR::splitwellValidator::12207f5c07ad... to Set(MediatorGroupRecipient(group = 0)). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=
```

The response (message b39cf14b): sent, rejected synchronously, error ignored by the participant, no amplification.
```
zcat canton-simtime.clog.gz | grep -a 'b39cf14b-4a0c-4f5e-ab54-edc8d8d60478' | grep -a -E 'Phase 4: Sending|max sequencing time|Rejecting submission request \(|may still be possibly sequenced|Not scheduling amplification' | sed -E "$F" | cut -c1-330
```
```
2026-11-13T10:47:36.588Z Phase 4: Sending for request=1970-01-01T00:00:00.190Z with msgId=b39cf14b-4a0c-4f5e-ab54-edc8d8d60478 approved=1, rejected=0, abstained=0",[c.d.c.p.p.TransactionProcessor:participant=splitwellParticipant/psid=global-domain::1220231690d5::37-0] INFO
2026-11-13T10:47:36.593Z Request to send submission with id b39cf14b-4a0c-4f5e-ab54-edc8d8d60478 with max sequencing time 1970-01-01T00:00:30.190Z from PAR::splitwellValidator::12207f5c07ad... to Set(MediatorGroupRecipient(group = 0)). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220231690d5::3
2026-11-13T10:47:36.605Z Rejecting submission request (b39cf14b-4a0c-4f5e-ab54-edc8d8d60478) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-d
2026-11-13T10:47:36.625Z Send [b39cf14b-4a0c-4f5e-ab54-edc8d8d60478] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-DefaultSequencer-0. Is the server running? Did you configure the server address as 0.0.0.0? Are you using the right TLS settings? (d
2026-11-13T10:47:36.625Z Not scheduling amplification for message ID b39cf14b-4a0c-4f5e-ab54-edc8d8d60478",[c.d.c.s.c.RichSequencerClientImpl:participant=splitwellParticipant/psid=global-domain::1220231690d5::37-0] DEBUG
```

## 6. The sim clock does not advance; the retries find the command in flight

Last global sequencing time in the minute of the exit.
```
zcat canton-simtime.clog.gz | grep -a 'globalSequencerSv1' | grep -a -oE '"@timestamp":"2026-11-13T10:50[^"]*".*validated to: Deliver' | tail -1 | grep -oE '"@timestamp":"[^"]+"|at 1970-[^ ]+'
```
```
"@timestamp":"2026-11-13T10:50:10.949Z"
at 1970-01-01T00:00:01.276Z
```

The createValidatorRight call times out after 2 min, its retries are answered SUBMISSION_ALREADY_IN_FLIGHT, and the breaker trips.
```
zcat canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a -E 'T10:(49:5|50)' | grep -a -E "The operation 'createValidatorRight' failed with a retryable|SUBMISSION_ALREADY_IN_FLIGHT|tripped after" | sed -E "$F" | cut -c1-215 | awk 'NR<=2 || /tripped after/'
```
```
2026-11-13T10:49:53.259Z The operation 'createValidatorRight' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999898894s. Name resolution delay 0.
2026-11-13T10:49:53.389Z Request (tid:d4f13ac6bbbbdd08f67bee9008c4218f) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15701: failed with ABORTED/SUBMISSION_ALREADY_IN_FLIGHT(2,d4f13ac6): The submi
2026-11-13T10:50:32.161Z Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure",[o.l.s.u.SpliceCircuitBreaker:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=318e573a/vali
```

The createValidatorRight submission (message 240014ef) was sequenced; the pending createSplitwellRules request (00:00:00.190) shows no verdict line in the same window.
```
zcat canton-simtime.clog.gz | grep -a -E 'T10:47:5' | grep -a '240014ef-26fd-4469-895c-2e017460a1f9' | grep -a -E 'max sequencing time|validated to: Deliver' | grep -a globalSequencerSv1 | sed -E "$F" | cut -c1-250; zcat canton-simtime.clog.gz | grep -a 'request=1970-01-01T00:00:00.190Z' | grep -a -c -E 'Phase 5|Phase 6|Phase 7|Sending result|verdict'
```
```
2026-11-13T10:47:53.294Z Request to send submission with id 240014ef-26fd-4469-895c-2e017460a1f9 with max sequencing time 1970-01-01T00:01:00Z from PAR::splitwellValidator::12207f5c07ad... to Set(MediatorGroupRecipient(group = 0), MemberRecipient(PAR
2026-11-13T10:47:58.262Z At block 272, the submission request 240014ef-26fd-4469-895c-2e017460a1f9 at 1970-01-01T00:00:00.273010Z validated to: Deliver",[c.d.c.s.b.u.SequencedSubmissionsValidator:sequencer=globalSequencerSv1/psid=global-domain::12202
0
```

## Verdict

Duplicate of 11255 (open), rejected-send shape at the 1 -> 4 step: `blacklisted nodes = List()`, zero
`Failed to send result`, zero `currently blacklisted`, rejection with `authenticated = 1 < dissemination quorum = 2`
16 ms after the size-4 epoch. Variant: the rejected message is the submitting participant's confirmation response
(as recorded for 11380), not a topology broadcast; the request then waits for a verdict that cannot come before the
sim clock passes the decision time. Flake on Canton BFT ordering under sim time; fix belongs with 11255. Not
verified: why sv1's P2P connectivity was below the quorum after the switch; why createValidatorRight, sequenced 22 s
after the switch, got no completion (assumed to wait behind the request without a verdict, not traced).
