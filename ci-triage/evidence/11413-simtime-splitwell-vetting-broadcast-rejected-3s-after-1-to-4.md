# 11413 - simtime (0): splitwellValidator VettedPackages broadcast rejected 3.1 s after the 1 -> 4 switch, splitwell_provider never created, providerSplitwellBackend exits the JVM (run 43457213815)

DUPLICATE of 11255 (rejected-send shape, topology broadcast of a validator; same end state as 11408 and 11326).
Main 09c8608e46, job simtime (0), Canton 3.7.3. In the setup of TrafficBasedRewardsSvAppTimeBasedIntegrationTest the
global ordering topology goes from 1 to 4 nodes at 16:05:43.732 (epoch 12, `blacklisted nodes = List()`). In the
following 3 s sv1's sequencer rejects five SetTrafficPurchased top-ups (retried; the SVs and their validators
initialize by 16:06:09) and, at 16:05:46.812, the splitwellValidator participant's VettedPackages broadcast on the
global synchronizer with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. The
participant ignores the error (`may still be possibly sequenced`); the broadcast counts as pending until max
sequencing time 00:00:20 on a sim clock at about 00:00:00.180, so the outbox flush never closes (`Another flush cycle
is currently ongoing` at 16:07:47 and 16:09:49) and the validator retries `check established dars ... are vetted`
(`Condition is not yet observed`) until 16:10:18. It never creates `splitwell_provider`; providerSplitwellBackend
gives up on that user at 16:10:19.227 and exits the JVM (`Wait for user failed`, PERMISSION_DENIED =
`UserNotFound(splitwell_provider-6df4f0f4)`). No ScalaTest report. No lost verdict: zero `Failed to send result`
and zero `currently blacklisted`.

- Run: https://github.com/canton-network/splice/actions/runs/43457213815, main 09c8608e46 ("Support initializing the
  party to participant metrics for millions of entries (#8478)"), job 131994865947
  `ci / scala_test_sim_time / simtime (0)`, attempt 1. The same run also failed wall-clock-time (1) (11414).
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at 09c8608e46).
- Component: Canton BFT ordering (P2P connectivity below the dissemination quorum right after a topology change)
  under simulated time.

`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.
Artifact `logs-simtime-0` (14055916610) in `log/11413/logs-simtime-0/`; commands run there unless they name
`log/11413/job.log`. `T=canton_network_test.clog.gz`, `C=canton-simtime.clog.gz`.

## 1. Job: JVM exit in the first suite, no report

Job id resolution (recipe 1, exact name match, one failed job of this name).
```
export NAME='simtime (0)'; gh api --paginate "repos/canton-network/splice/actions/runs/43457213815/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
```
```
131994865947 attempt=1 ci / scala_test_sim_time / simtime (0)
```
Classification grep of the job log: no `Tests:` summary, the console stack is the splitwell app's init failure.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed|##\[error\]|app initialization: Initialization failed|^\S+ Caused by|Attempt 1 failed' | sed -E 's/^[^Z]*Z //' | cut -c1-230
```
```
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 08f7aadc47f9a555b9a1ba0a1ecd1a4f with tid 08f7aadc47f9a555b9a1ba0a1ecd1a4f
Attempt 1 failed with exit code 1, retrying
##[error]Error: failed to run script step (id 86cc3f60-df7b-11f1-9175-f1e15be0ae54): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```

## 2. Suite timeline: only the first suite started

```
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |has been reset" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat $T | tail -1 | cut -c1-200
```
```
2026-11-13T16:02:23.407Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",
{"@timestamp":"2026-11-13T16:10:19.231Z","message":"Request fetch remote time has succeeded for remote clock server.","logger_name":"c.d.c.t.RemoteClock:TrafficBasedRewardsSvAppTimeBasedIntegrationTes
```
The log ends 3 ms after the splitwell ERROR (section 6): the JVM exited, no reset, no later suite.

## 3. Ordering topology 1 -> 4 at 16:05:43.732, no blacklisting

```
zcat $C | grep -a -E 'New epoch 1[12] has started' | grep -a 'globalSequencerSv1' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name".*//' | grep -a -oE '^[^ ]+ New epoch [0-9]+|blacklisted nodes = List\([^)]*\)|size = [0-9]+'
zcat $C | grep -a -c 'Failed to send result'; zcat $C | grep -a -c 'currently blacklisted'
```
```
2026-11-13T16:05:35.634Z New epoch 11
blacklisted nodes = List()
size = 1
2026-11-13T16:05:43.732Z New epoch 12
blacklisted nodes = List()
size = 4
0
0
```

## 4. sv1's sequencer rejects sends for 3 s after the switch

All `P2P connectivity` rejections of the run: five SetTrafficPurchased top-ups (sequencer admin API,
TRAFFIC_CONTROL_TOP_UP_SUBMISSION_FAILED) and one participant send, splitwellValidator's, at 16:05:46.813 (3.08 s
after the switch).
```
zcat $C | grep -a 'P2P connectivity is not ready' | grep -a -v 'synchronously' | grep -a -E 'MempoolModule|TrafficPurchasedSubmissionHandler|GrpcSequencerService' | sed -E "$F" | cut -c1-250
```
```
2026-11-13T16:05:44.237Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12203933a242::37-0] INFO
2026-11-13T16:05:44.256Z TRAFFIC_CONTROL_TOP_UP_SUBMISSION_FAILED(11,2265718e): RequestRefused(SendAsyncErrorDirect(P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting))",[c.d.c.s.t.TrafficPurchasedSubmissionHandle
2026-11-13T16:05:44.472Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12203933a242::37-0] INFO
2026-11-13T16:05:44.473Z TRAFFIC_CONTROL_TOP_UP_SUBMISSION_FAILED(11,403d45a5): RequestRefused(SendAsyncErrorDirect(P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting))",[c.d.c.s.t.TrafficPurchasedSubmissionHandle
2026-11-13T16:05:44.742Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12203933a242::37-0] INFO
2026-11-13T16:05:44.743Z TRAFFIC_CONTROL_TOP_UP_SUBMISSION_FAILED(11,d978507d): RequestRefused(SendAsyncErrorDirect(P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting))",[c.d.c.s.t.TrafficPurchasedSubmissionHandle
2026-11-13T16:05:45.433Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12203933a242::37-0] INFO
2026-11-13T16:05:45.434Z TRAFFIC_CONTROL_TOP_UP_SUBMISSION_FAILED(11,2ce8665f): RequestRefused(SendAsyncErrorDirect(P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting))",[c.d.c.s.t.TrafficPurchasedSubmissionHandle
2026-11-13T16:05:46.375Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12203933a242::37-0] INFO
2026-11-13T16:05:46.376Z TRAFFIC_CONTROL_TOP_UP_SUBMISSION_FAILED(11,85120b2c): RequestRefused(SendAsyncErrorDirect(P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting))",[c.d.c.s.t.TrafficPurchasedSubmissionHandle
2026-11-13T16:05:46.812Z P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12203933a242::37-0] INFO
2026-11-13T16:05:46.813Z Rejecting submission request by Some(PAR::splitwellValidator::1220e22d98ff...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), r
```
The top-ups were retried and the SVs completed: sv3, sv4, sv2 and their validators initialize by 16:06:09;
splitwellValidator never does.
```
zcat $T | grep -a -E 'app initialization: Initialization complete' | sed -E "$F" | sed -E 's/\[[^]]*(validator|sv|scan|splitwell)=([A-Za-z0-9]+)\]/[\2]/' | cut -c1-80
```
```
2026-11-13T16:04:17.845Z sv1Scan app initialization: Initialization complete, ru
2026-11-13T16:04:18.076Z sv1 app initialization: Initialization complete, runnin
2026-11-13T16:04:21.422Z sv1Validator app initialization: Initialization complet
2026-11-13T16:05:21.542Z bobValidator app initialization: Initialization complet
2026-11-13T16:05:21.910Z aliceValidator app initialization: Initialization compl
2026-11-13T16:05:31.993Z sv3Scan app initialization: Initialization complete, ru
2026-11-13T16:05:32.708Z sv4Scan app initialization: Initialization complete, ru
2026-11-13T16:05:36.322Z sv2Scan app initialization: Initialization complete, ru
2026-11-13T16:06:00.929Z sv3 app initialization: Initialization complete, runnin
2026-11-13T16:06:01.638Z sv4 app initialization: Initialization complete, runnin
2026-11-13T16:06:04.221Z sv2 app initialization: Initialization complete, runnin
2026-11-13T16:06:05.271Z sv3Validator app initialization: Initialization complet
2026-11-13T16:06:06.691Z sv4Validator app initialization: Initialization complet
2026-11-13T16:06:09.478Z sv2Validator app initialization: Initialization complet
```

## 5. The rejected send is splitwellValidator's VettedPackages broadcast; it is never answered

The rejected message `bed7c3ea` is a broadcast (`to Set(All)`) with max sequencing time 00:00:20 against an
estimated sequencing time of 00:00:00.180; its trace id 2cfd007a ties it to the outbox push of TxHash 6c7e5d631458,
a VettedPackages transaction of splitwellValidator enqueued at 16:05:46.801.
```
zcat $C | grep -a 'bed7c3ea-f229-4743-b22f-2dba223f2392' | grep -a -E 'Request to send|Estimated sequencing' | sed -E "$F" | cut -c1-230
zcat $C | grep -a -m1 'Sending message ID bed7c3ea' | grep -a -oE '"trace-id":"[^"]*"'
zcat $C | grep -a '2cfd007a571ee303a3b36f3b5f3d6433' | sed -E "$F" | grep -a -E 'Attempting to push|Broadcasting|possibly sequenced' | cut -c1-200
zcat $C | grep -a 'splitwellParticipant/psid=global' | grep -a 'Enqueued topology transactions' | grep -a '"@timestamp":"2026-11-13T16:05:46' | sed -E "$F" | cut -c1-200
```
```
2026-11-13T16:05:46.810Z Request to send submission with id bed7c3ea-f229-4743-b22f-2dba223f2392 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::1220e22d98ff... to Set(All). ",[c.d.c.s.s.b.BlockSequence
2026-11-13T16:05:46.810Z Estimated sequencing time 1970-01-01T00:00:00.180Z for submission with id bed7c3ea-f229-4743-b22f-2dba223f2392",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::12203933a242::37
"trace-id":"2cfd007a571ee303a3b36f3b5f3d6433"
2026-11-13T16:05:46.803Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:6c7e5d631458...))",[c.d.c.t.QueueBasedSynchronizerOutbox:participant=spl
2026-11-13T16:05:46.803Z Broadcasting topology transaction: List(TxHash(SHA-256:6c7e5d631458...))",[c.d.c.c.s.SequencerBasedRegisterTopologyTransactionHandle:participant=splitwellParticipant/psid=glob
2026-11-13T16:05:46.835Z Send [bed7c3ea-f229-4743-b22f-2dba223f2392] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Request failed for server-De
2026-11-13T16:05:46.801Z Enqueued topology transactions:\nValidatedTopologyTransaction(\n  SignedTopologyTransaction(\n    TopologyTransaction(\n      VettedPackages(\n        participantId = PAR::spl
```
That push is the last one of splitwellParticipant on global; it never gets a response, and later flushes find the
cycle still open.
```
zcat $C | grep -a 'splitwellParticipant/psid=global' | grep -a -E 'Attempting to push|responded the following' | awk '$0 >= "{\"@timestamp\":\"2026-11-13T16:05:40"' | sed -E "$F" | cut -c1-160
zcat $C | grep -a -c 'responded the following.*6c7e5d631458'
zcat $C | grep -a 'splitwellParticipant/psid=global' | grep -a 'Another flush cycle is currently ongoing' | sed -E "$F" | cut -c1-80
```
```
2026-11-13T16:05:46.803Z Attempting to push 1 topology transactions to Synchronizer 'global', specifically: List(TxHash(SHA-256:6c7e5d631458...))",[c.d.c.t.Queu
0
2026-11-13T16:07:47.315Z Another flush cycle is currently ongoing",[c.d.c.t.Queu
2026-11-13T16:09:49.712Z Another flush cycle is currently ongoing",[c.d.c.t.Queu
```

## 6. splitwellValidator stays in the vetting check; providerSplitwellBackend exits the JVM

The validator's `check established dars ... are vetted` retries run from 16:05:39 to 16:10:18 (190 `Condition is not
yet observed`); providerSplitwellBackend's `Get user` for `splitwell_provider` is refused because the user does not
exist, and the app gives up 1 s later.
```
zcat $T | grep -a 'validator=splitwellValidator' | grep -a 'check established dars' | sed -E "$F" | cut -c1-120 | sed -n '1p;$p'
zcat $T | grep -a 'validator=splitwellValidator' | grep -a -c 'Condition is not yet observed'
zcat $T | grep -a '"level":"ERROR"' | sed -E "$F" | cut -c1-200
zcat $C | grep -a '08f7aadc47f9a555b9a1ba0a1ecd1a4f' | grep -a 'UserNotFound' | sed -E "$F" | cut -c1-200
```
```
2026-11-13T16:05:39.332Z The operation 'check established dars List(5838793a7f11fbc16159065094823fb662120184ede48922dff0
2026-11-13T16:10:18.131Z The operation 'check established dars List(4c380e09c99b2c5cb938f6cce2a92c03f8941ca6f7af7df7c737
190
2026-11-13T16:10:19.227Z Gave up getting 'user splitwell_provider-6df4f0f4'",[o.l.s.s.SplitwellApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=6df4f0f4/splitwell=providerSplitwellBackend] 
2026-11-13T16:10:19.228Z providerSplitwellBackend app initialization: Initialization failed",[o.l.s.s.SplitwellApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=6df4f0f4/splitwell=providerSp
2026-11-13T16:10:19.219Z PERMISSION_DENIED(7,08f7aadc): Could not resolve is_deactivated status for user 'splitwell_provider-6df4f0f4' and identity_provider_id 'Default' due to 'UserNotFound(splitwell
```

## Verdict

Duplicate of 11255, rejected-send shape (a validator's topology broadcast rejected by sv1's sequencer with `P2P
connectivity is not ready` after the 1 -> 4 ordering-topology step under simulated time). Same end state as 11408
(main 20054fa63d) and 11326: VettedPackages broadcast rejected, outbox flush never closes, splitwell_provider never
created, providerSplitwellBackend JVM exit. Difference: the rejection came 3.08 s after the switch (11408: 1.2 s), so
the 0.3 s window of the original confirming grep is too narrow again (as noted for 11398 and 11410). Flake from a
Canton BFT ordering behaviour under simulated time; fix belongs with the 11255 owner (rautenrieth-da), no separate
fix. Not verified: which DAR the final `check established dars` waits on is in the rejected VettedPackages
transaction (the enqueued-transaction log line is truncated); the conclusion rests on the push being the last and
unanswered one on global.
