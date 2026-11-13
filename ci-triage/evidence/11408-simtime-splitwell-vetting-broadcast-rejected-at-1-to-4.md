# 11408 - simtime (0): splitwellValidator VettedPackages broadcast rejected 1.2 s after the 1 -> 4 switch, splitwell_provider never created, providerSplitwellBackend exits the JVM (run 43411023137)

DUPLICATE of 11255 (rejected-send shape, topology broadcast of a validator). Main 20054fa63d, job simtime (0), Canton
3.7.3. In the setup of TrafficBasedRewardsSvAppTimeBasedIntegrationTest the global ordering topology goes from 1 to
4 nodes at 10:57:13.587. splitwellValidator is in its `Vet packages` init step; its participant broadcasts a
VettedPackages replacement on the global synchronizer at 10:57:14.800 and sv1's sequencer rejects it at
10:57:14.810 with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. The participant
ignores the error (`may still be possibly sequenced`); the broadcast counts as pending until max sequencing time
00:00:20 on a sim clock that stays at about 00:00:01, so the outbox flush never closes (`Another flush cycle is
currently ongoing` at 10:59:18.804) and the validator never gets past `Vet packages`. It never creates
`splitwell_provider`, and providerSplitwellBackend, waiting for that user since 10:53:21.628, exits the JVM at
11:01:02 (`Wait for user failed`, PERMISSION_DENIED = `UserNotFound(splitwell_provider-95fce1be)`). No ScalaTest
report. sv3's own broadcast was rejected the same way 40 ms after the switch.

- Run: https://github.com/canton-network/splice/actions/runs/43411023137, main 20054fa63d ("Less aggressivle ramp
  down of the party allocator on failures (#8471)"), job 131837486258 `ci / scala_test_sim_time / simtime (0)`,
  attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at 20054fa63d).
- Component: Canton BFT ordering (P2P connectivity below the dissemination quorum right after a topology change)
  under simulated time.

`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.
Artifact `logs-simtime-0` (14031350330) in `log/11408/logs-simtime-0/`; commands run there unless they name `log/11408/job.log`.

## 1. Job: JVM exit in the first suite's setup, no report

Classification grep of the job log.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]|app initialization: Initialization failed|^\S+ Caused by|Attempt 1 failed' | sed -E 's/^[^Z]*Z //' | cut -c1-200
```
```
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 5e2954be35af9451761916ef6f55b0ba with tid 5e2954be35af9451761
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id 55c8b270-df50-11f1-be03-8541bd627d80): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

The participant's reason for the PERMISSION_DENIED of that tid.
```
zcat canton-simtime.clog.gz | grep -a 5e2954be35af9451761916ef6f55b0ba | grep -a UserNotFound | head -1 | grep -a -oE '"@timestamp":"[^"]+","message":"[^"]{0,200}'
```
```
"@timestamp":"2026-11-13T11:01:02.021Z","message":"PERMISSION_DENIED(7,5e2954be): Could not resolve is_deactivated status for user 'splitwell_provider-95fce1be' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-95fce1be)'
```

## 2. Suite timeline

Only the first suite started; its environment setup never finished.
```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |creating environment: Running setup|has been reset" | sed -E "$F" | sed -E 's/"logger_name.*//' | cut -c1-160
```
```
2026-11-13T10:53:10.604Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T10:53:56.263Z Starting creating environment: Running setup",[o.l.s.i.t.TrafficBasedRewardsSvAppTimeBasedIntegrationTest:TrafficBasedRewardsSvAppTimeB
```

## 3. providerSplitwellBackend waits for a user that splitwellValidator, stuck in Vet packages, never creates

Init steps of both apps.
```
zcat canton_network_test.clog.gz | grep -a -E 'providerSplitwellBackend app initialization' | grep -a -E 'Wait for user (started|failed)' | sed -E "$F" | cut -c1-200; zcat canton_network_test.clog.gz | grep -a 'splitwellValidator app initialization' | grep -a -E 'started|finished|failed' | sed -E "$F" | cut -c1-170 | tail -2
```
```
2026-11-13T10:53:21.628Z providerSplitwellBackend app initialization: Wait for user started",[o.l.s.s.SplitwellApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=95fce1be/splitwell=providerSp
2026-11-13T11:01:02.023Z providerSplitwellBackend app initialization: Wait for user failed",[o.l.s.s.SplitwellApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=95fce1be/splitwell=providerSpl
2026-11-13T11:01:02.023Z providerSplitwellBackend app initialization: Initialize app failed",[o.l.s.s.SplitwellApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=95fce1be/splitwell=providerSp
2026-11-13T11:01:02.023Z providerSplitwellBackend app initialization: Initialization failed",[o.l.s.s.SplitwellApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=95fce1be/splitwell=providerSp
2026-11-13T10:56:37.366Z splitwellValidator app initialization: Ensuring extra domains registered finished after PT1M10.402106958S",[o.l.s.v.ValidatorApp:TrafficBasedRewa
2026-11-13T10:56:37.367Z splitwellValidator app initialization: Vet packages started",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=95fce1
```

## 4. 11255 confirming grep: rejections right after the switch to 4 orderers, no lost verdicts, no blacklisting

Ordering topology sizes on the global synchronizer, P2P rejections, and the counts that rule out the lost-verdict and breaker shapes.
```
zcat canton-simtime.clog.gz | awk '{ if(/Failed to send result/) b++; if(/currently blacklisted/) bl++; if(/New epoch .*size = [0-9]/ && !/splitwell/){match($0,/"@timestamp":"[^"]+"/); last=substr($0,RSTART+14,24); match($0,/size = [0-9]+/); sz=substr($0,RSTART,RLENGTH); if(sz!=psz){print "epoch " last " " sz; psz=sz}} if(/Rejecting submission request by/ && /P2P connectivity is not ready/){match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+14,24); match($0,/Rejecting submission request by [^ ]+/); print "reject " t " " substr($0,RSTART,70)} } END{print "Failed to send result: " b+0 "; currently blacklisted: " bl+0}'
```
```
epoch 2026-11-13T10:54:35.961Z size = 1
epoch 2026-11-13T10:57:13.587Z size = 4
reject 2026-11-13T10:57:13.627Z Rejecting submission request by Some(PAR::sv3::12209320bafb...) with S
reject 2026-11-13T10:57:14.810Z Rejecting submission request by Some(PAR::splitwellValidator::1220a38d
Failed to send result: 0; currently blacklisted: 0
```

The size-4 epoch.
```
zcat canton-simtime.clog.gz | grep -a 'New epoch' | grep -a 'size = 4' | grep -a -v splitwell | head -1 | sed -E "$F" | cut -c1-175
```
```
2026-11-13T10:57:13.587Z New epoch 15 has started with leaders = List(SEQ::sv4::..7040, SEQ::sv1::..81c1, SEQ::sv2::..581f, SEQ::sv3::..425f)and blacklisted nodes = List(); or
```

Sends and rejections at sv1's sequencer in 10:57:13-14 (ALL=1 are topology broadcasts).
```
zcat canton-simtime.clog.gz | grep -a -E 'T10:57:1[34]' | grep -a -E 'PAR::(splitwellValidator|sv[1-4])' | grep -a -E 'sends request with id|Rejecting submission' | grep -a globalSequencerSv1 | grep -a -E 'ALL=1|Rejecting' | sed -E "$F" | cut -c1-300
```
```
2026-11-13T10:57:13.346Z 'PAR::sv1::12203ff7e7e0...' sends request with id 'bf1efd47-2bd4-47d6-9555-d3903fbee21f' of size 786 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:57:13.367Z 'PAR::sv4::12202074cfae...' sends request with id '0157f227-ee52-4abd-b940-d65536f32b6e' of size 795 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:57:13.546Z 'PAR::sv2::122009c7d12a...' sends request with id '725f2887-9120-442e-9ddd-fb54b7de463b' of size 915 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:57:13.571Z 'PAR::sv3::12209320bafb...' sends request with id 'dcaf4867-8f59-4a86-b167-064fc814819c' of size 1027 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:57:13.627Z Rejecting submission request by Some(PAR::sv3::12209320bafb...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequ
2026-11-13T10:57:14.807Z 'PAR::splitwellValidator::1220a38d2cfd...' sends request with id 'dcd7cfe9-afe1-4b60-b71f-cb6a455e4d06' of size 4403 bytes with 1 envelopes to ALL=1.",[c.d.c.s.s.s.GrpcSequencerService:sequencer=globalSequencerSv1] INFO
2026-11-13T10:57:14.810Z Rejecting submission request by Some(PAR::splitwellValidator::1220a38d2cfd...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.s.GrpcSequencerService:seque
```

## 5. The rejected send is splitwellValidator's VettedPackages broadcast; the outbox stays open

Outbox of splitwellParticipant on global at 10:57:14 and the rejected message dcd7cfe9.
```
zcat canton-simtime.clog.gz | grep -a 'participant=splitwellParticipant' | grep -a 'T10:57:14' | grep -a -E 'Attempting to build, sign, and Replace VettedPackages|Attempting to push 1 topology|Broadcasting topology transaction|possibly sequenced' | grep -a 'global-domain' | sed -E "$F" | cut -c1-235; zcat canton-simtime.clog.gz | grep -a 'dcd7cfe9-afe1-4b60-b71f-cb6a455e4d06' | grep -a -E 'max sequencing time|Rejecting submission request \(' | sed -E "$F" | cut -c1-300
```
```
2026-11-13T10:57:14.581Z Attempting to build, sign, and Replace VettedPackages(\n  participantId = PAR::splitwellValidator::1220a38d2cfd...,\n  packages = VettedPackage(packageId = 6f8e6085f576..., unbounded),VettedPackage(packageId = 
2026-11-13T10:57:14.800Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:bc03a7584f3a...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domai
2026-11-13T10:57:14.800Z Broadcasting topology transaction: List(TxHash(SHA-256:bc03a7584f3a...))",[c.d.c.c.s.SequencerBasedRegisterTopologyTransactionHandle:participant=splitwellParticipant/psid=global-domain::1220b80aa2c6::37-0] DEBU
2026-11-13T10:57:14.808Z Request to send submission with id dcd7cfe9-afe1-4b60-b71f-cb6a455e4d06 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::1220a38d2cfd... to Set(All). ",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::1220b80aa2c6::37-0]
2026-11-13T10:57:14.810Z Rejecting submission request (dcd7cfe9-afe1-4b60-b71f-cb6a455e4d06) synchronously: SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)",[c.d.c.s.s.b.BlockSequencer:sequencer=gl
```

The participant treats the rejected send as possibly sequenced.
```
zcat canton-simtime.clog.gz | grep -a 'dcd7cfe9-afe1-4b60-b71f-cb6a455e4d06' | grep -a 'may still be possibly sequenced' | sed -E "$F" | cut -c1-190
```
```
2026-11-13T10:57:14.817Z Send [dcd7cfe9-afe1-4b60-b71f-cb6a455e4d06] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for
```

Later flush attempts of that outbox.
```
zcat canton-simtime.clog.gz | grep -a 'participant=splitwellParticipant' | grep -a 'global-domain' | grep -a -E 'Invoked flush with queue size|Another flush cycle' | grep -a -E 'T10:5[89]|T11:0' | sed -E "$F" | cut -c1-200
```
```
2026-11-13T10:59:18.804Z Invoked flush with queue size 2",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220b80aa2c6::37-0] DEBUG
2026-11-13T10:59:18.804Z Another flush cycle is currently ongoing",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=splitwellParticipant/psid=global-domain::1220b80aa2c6::37-0] DEBUG
```

Last global sequencing time before the exit.
```
zcat canton-simtime.clog.gz | grep -a 'globalSequencerSv1' | grep -a -oE '"@timestamp":"2026-11-13T1[01]:[05][0-9][^"]*".*validated to: Deliver' | tail -1 | grep -oE '"@timestamp":"[^"]+"|at 1970-[^ ]+'
```
```
"@timestamp":"2026-11-13T10:59:43.716Z"
at 1970-01-01T00:00:01.263Z
```

## Verdict

Duplicate of 11255 (open), rejected-send shape at the 1 -> 4 step: `blacklisted nodes = List()`, zero
`Failed to send result`, zero `currently blacklisted`, rejections with `authenticated = 1 < dissemination quorum = 2`
0.04 s (sv3) and 1.2 s (splitwellValidator) after the size-4 epoch. Same end state as 11326 (splitwellValidator DAR
vetting on global lost at the switch, splitwell_provider never created), here by rejection instead of a batch that
was never ordered. Flake on Canton BFT ordering under sim time; fix belongs with 11255. Not verified: why sv1's P2P
connectivity was still below the quorum 1.2 s after the switch; what the rejected sv3 broadcast contained.
