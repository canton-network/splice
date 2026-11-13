# 11411 - simtime (1): MED::sv4 MediatorSynchronizerState pushes of sv3, sv2, sv1 rejected after the 1 -> 4 switch, sv4's outbox stuck on its SequencerSynchronizerState push; sv4Validator exits the JVM (run 43433803626)

DUPLICATE of 11255 (rejected-send shape). Main ec67414f5d, job simtime (1), Canton 3.7.3. No ScalaTest report: in the
setup of NonZeroRoundBootstrapBftTimeBasedIntegrationTest (second suite of the shard; all 9 tests of
GovernanceLockTimeBased before it passed) the global ordering topology goes from 1 to 4 nodes at 13:48:33.487.
sv1's sequencer rejects the pushes of the MediatorSynchronizerState proposal that adds MED::sv4 (tx bf0c6a701c38)
from sv3 (13:48:33.613), sv2 (13:48:33.748) and sv1 (13:48:34.792) with `P2P connectivity is not ready
(authenticated = 1 < dissemination quorum = 2)`. sv4 never pushes it: its push of the serial-4 SequencerSynchronizerState
(e6709a39559a) at 13:48:32.897, 0.59 s before the switch, never gets a response, so its flush cycle stays open. sv4's
SV app retries `Check whether Mediator has been granted unlimited traffic` until 13:53:38, never creates
sv4_validator_user, and sv4Validator gives up `Get user` at 13:53:40 and exits the JVM (family H). Same shape as
11410 (there MED::sv2).

- Run: https://github.com/canton-network/splice/actions/runs/43433803626, main ec67414f5d ("Order featured app rights
  for a provider by creation time (#8475)"), job 131913988904 `ci / scala_test_sim_time / simtime (1)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at ec67414f5d).
- Component: Canton BFT ordering under simulated time.

## 1. Job log: no test summary, JVM exit from sv4Validator init

Recipe 2 finds no `Tests:` line and no `FAILED`; the only exception in the job log is the sv4Validator init failure.
```
sed -E 's/\x1b\[[0-9;]*m//g' job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' job.log | grep -a -E '^(java|Caused by|io\.grpc)' | cut -c1-200
```
```
##[error]Error: failed to run script step (id 0be37e80-df67-11f1-a3fd-2b087b4d58b8): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
java.lang.RuntimeException: sv4Validator app initialization: Initialize app failed
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 5453c2250f2836be66d7f4fc8b059b43 with tid 5453c2250f2836be66d
```

## 2. Suite timeline

GovernanceLockTimeBased passes all its tests with a namespace reset after each; NonZeroRoundBootstrapBftTimeBased
starts its setup at 13:46:36 and the log ends at 13:53:40 without a result.
```
zcat logs-simtime-1/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Running setup" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-140 | tail -6; zcat logs-simtime-1/canton_network_test.clog.gz | tail -1 | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-110
```
```
2026-11-13T13:45:05.217Z Test succeeded: 'GovernanceLockTimeBasedIntegrationTest/rejected and withdrawn transfers leave the lock unchanged, 
2026-11-13T13:45:18.647Z Starting creating environment: Running setup",
2026-11-13T13:45:34.248Z Finished creating environment: Running setup",
2026-11-13T13:46:02.675Z Test succeeded: 'GovernanceLockTimeBasedIntegrationTest/lock transfer accept context fails if a source LockedAmulet
2026-11-13T13:46:02.745Z Starting test suite 'NonZeroRoundBootstrapBftTimeBasedIntegrationTest'...",
2026-11-13T13:46:36.016Z Starting creating environment: Running setup",
2026-11-13T13:53:40.174Z Request fetch remote time has succeeded for remote clock server.","logger_name":"c.d.
```

## 3. Ordering topology 1 -> 4 at 13:48:33.487, no blacklisting

First epoch of each topology size in the setup window; the size-4 epoch has an empty blacklist.
```
zcat logs-simtime-1/canton-simtime.clog.gz | grep -a -E 'New epoch [0-9]+ has started' | grep -a 'T13:4[5-9]' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/leaders = .*(and blacklisted nodes = List\([^)]*\)).*size = ([0-9]+).*/\1 size=\2/; s/"logger_name":"[^:]*:sequencer=([^/"]*).*/ \1/' | awk '{if(!($NF in s)){s[$NF]=1; print}}' | cut -c1-160
```
```
2026-11-13T13:45:02.439Z New epoch 127 has started with and blacklisted nodes = List() size=1
2026-11-13T13:48:33.487Z New epoch 72 has started with and blacklisted nodes = List() size=4
```

Rules out the lost-verdict and breaker shapes of 11255:
```
zcat logs-simtime-1/canton-simtime.clog.gz | awk '/P2P connectivity is not ready/{a++} /Failed to send result/{b++} /currently blacklisted/{c++} END{print "p2p-not-ready="a+0, "failed-send-result="b+0, "currently-blacklisted="c+0}'
```
```
p2p-not-ready=84 failed-send-result=0 currently-blacklisted=0
```

## 4. Rejected submissions

All three rejections are by sv1's sequencer, 0.13 to 1.3 s after the switch.
```
zcat logs-simtime-1/canton-simtime.clog.gz | grep -a 'Rejecting submission request by' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+\.\.\./::../; s/ with SequencerDeliverError.*message = ([^)]*\)).*sequencer=([^/"]*).*/ | \1 | \2/' | cut -c1-200
```
```
2026-11-13T13:48:33.613Z Rejecting submission request by Some(PAR::sv3::..) | P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2) | globalSequencerSv1
2026-11-13T13:48:33.748Z Rejecting submission request by Some(PAR::sv2::..) | P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2) | globalSequencerSv1
2026-11-13T13:48:34.792Z Rejecting submission request by Some(PAR::sv1::..) | P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2) | globalSequencerSv1
```

## 5. What the rejected sends carried

The SV participants' topology outboxes around the switch. bf0c6a701c38 is pushed by sv3, sv2 and sv1 at
13:48:33.598, 33.740 and 34.785, each 7-15 ms before one of the rejections in section 4. sv4 pushes e6709a39559a at
13:48:32.897 and its next flush at 13:48:33.639 finds the cycle still open.
```
zcat logs-simtime-1/canton-simtime.clog.gz | grep -a -E 'T13:48:3[0-6]' | grep -a -E 'QueueBasedSynchronizerOutbox:participant=sv[0-9]Participant/psid=global' | grep -a -E 'Attempting to push|responded the following|Another flush cycle' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name":"[^:]*:participant=(sv[0-9])[^"]*".*"level":"([A-Z]+)".*/ | \1 \2/' | awk '$1>="2026-11-13T13:48:32.5"' | cut -c1-200
```
```
2026-11-13T13:48:32.539Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:e6709a39559a...)) | sv2 DEBUG
2026-11-13T13:48:32.897Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:e6709a39559a...)) | sv4 DEBUG
2026-11-13T13:48:33.430Z Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:e6709a39559a...))) | sv2 DEBUG
2026-11-13T13:48:33.598Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:bf0c6a701c38...)) | sv3 DEBUG
2026-11-13T13:48:33.639Z Another flush cycle is currently ongoing | sv4 DEBUG
2026-11-13T13:48:33.740Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:bf0c6a701c38...)) | sv2 DEBUG
2026-11-13T13:48:34.785Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:bf0c6a701c38...)) | sv1 DEBUG
```

The two transactions:
```
for h in bf0c6a701c38 e6709a39559a; do echo "$h:"; zcat logs-simtime-1/canton-simtime.clog.gz | grep -a "$h" | grep -a -oE '(MediatorSynchronizerState|SequencerSynchronizerState)\([^"]{0,900}serial = [0-9]+' | head -1 | sed -E 's/::1220[0-9a-f]+(\.\.\.)?/::../g; s/\\n */ /g'; done
```
```
bf0c6a701c38:
MediatorSynchronizerState( synchronizerId = global-domain::.., group = 0, threshold = 2, active = Seq(MED::sv1::.., MED::sv2::.., MED::sv3::.., MED::sv4::..) ), serial = 4
e6709a39559a:
SequencerSynchronizerState( synchronizerId = global-domain::.., threshold = 2, active = Seq(SEQ::sv1::.., SEQ::sv2::.., SEQ::sv3::.., SEQ::sv4::..) ), serial = 4
```

Responses per participant to the two transactions until the end of the log: none for bf0c6a701c38, and for
e6709a39559a none to sv4 (sv1, sv2, sv3 got theirs before the switch, section 5 and the lines above it).
```
for h in bf0c6a701c38 e6709a39559a; do for p in sv1 sv2 sv3 sv4; do echo "$h $p responses: $(zcat logs-simtime-1/canton-simtime.clog.gz | grep -a "QueueBasedSynchronizerOutbox:participant=${p}Participant/psid=global" | grep -a -c "responded the following.*$h")"; done; done
```
```
bf0c6a701c38 sv1 responses: 0
bf0c6a701c38 sv2 responses: 0
bf0c6a701c38 sv3 responses: 0
bf0c6a701c38 sv4 responses: 0
e6709a39559a sv1 responses: 1
e6709a39559a sv2 responses: 1
e6709a39559a sv3 responses: 1
e6709a39559a sv4 responses: 0
```

## 6. sv4 waits on the mediator traffic grant until the JVM exit

sv4's SV app (collapsed: count, first, last) retries the unlimited-traffic check from 13:48:41 to 13:53:38 and
its `Add mediator MED::sv4` proposal is never observed; sv4Validator gives up waiting for its user.
```
zcat logs-simtime-1/canton_network_test.clog.gz | grep -a -E 'config=7e0d0926/(SV|validator)=sv4[^/"]*"' | grep -a -E "unlimited traffic'|Add mediator MED::sv4.*failed with a retryable|Gave up getting|Initialization failed" | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/::1220[0-9a-f]+\.\.\./::../g; s/","logger_name".*//' | awk '{k=substr($0,26,50); c[k]++; l[k]=$0; if(!(k in f)){f[k]=$0; o[++n]=k}} END{for(i=1;i<=n;i++){print c[o[i]]"x "f[o[i]]; if(c[o[i]]>1) print "   last "l[o[i]]}}' | cut -c1-170
```
```
94x 2026-11-13T13:48:33.646Z The operation 'check established Add mediator MED::sv4::..' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Co
   last 2026-11-13T13:53:40.080Z The operation 'check established Add mediator MED::sv4::..' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION
172x 2026-11-13T13:48:41.023Z The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable error (full stack trace omitted): NOT_FOUN
   last 2026-11-13T13:53:38.607Z The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an exception. Retrying after a number of 85 fai
85x 2026-11-13T13:48:41.224Z Now retrying operation 'Check whether Mediator has been granted unlimited traffic'. 
   last 2026-11-13T13:53:38.556Z Now retrying operation 'Check whether Mediator has been granted unlimited traffic'. 
2x 2026-11-13T13:50:32.656Z The operation 'Add mediator MED::sv4::..' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet 
   last 2026-11-13T13:52:32.286Z The operation 'Add mediator MED::sv4::..' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not
1x 2026-11-13T13:53:40.167Z Gave up getting 'user sv4_validator_user-7e0d0926'
1x 2026-11-13T13:53:40.168Z sv4Validator app initialization: Initialization failed
```

## Verdict

Duplicate of 11255, rejected-send shape, simulated time, 1 -> 4 step: three of the four SV signatures on the MED::sv4
MediatorSynchronizerState add are rejected with `P2P connectivity is not ready` 0.13 to 1.3 s after the switch, the
fourth is never sent because sv4's outbox waits on its SequencerSynchronizerState push sent 0.59 s before the switch
and never answered. Zero `Failed to send result`, zero `currently blacklisted`, `blacklisted nodes = List()`. Flake in
Canton BFT ordering; no splice change beyond what 11255 tracks. The JVM exit ended the shard, so the remaining four
suites did not run. Not related to #8475 (featured app right ordering in the scan store).

Not verified: why sv4's pre-switch push gets no response.
