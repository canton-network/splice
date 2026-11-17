# 11528 - FeaturedAppActivityMarkerIntegrationTest "Reward collection should work for featured app activity markers": submission hits bobValidator's sequencer-connection reconnect, SUBMISSION_SYNCHRONIZER_NOT_READY not retried (run 43962551422)

New (test infrastructure race, family I). main 213565d133, job `wall-clock-time (4)`, canton
3.7.4-snapshot.20261112.20915.0.v9e248620, 39 tests passed, 1 failed, no checkErrors findings. The test submits 20
`FeaturedAppRight_CreateActivityMarker` commands through the console on aliceParticipant and bobParticipant. At
22:16:53.801 bobValidator's ReconcileSequencerConnectionsTrigger saw sv4's sequencer for the first time (scan
`availableAfter` 22:16:53.129563), modified the synchronizer connection and bounced it (bobParticipant disconnected
22:16:53.845-22:16:53.956). A submission as bob received at 22:16:53.832 was routed at 22:16:53.846 and rejected with
`SUBMISSION_SYNCHRONIZER_NOT_READY`. The console retry policy for command submissions (splice patch in
`EnvironmentSetup.scala`, added for this same test by splice#2816) force-retries NOT_CONNECTED_TO_SYNCHRONIZER and
NOT_CONNECTED_TO_ANY_SYNCHRONIZER but not this code, so it logged `Retrying: false` and the command failed. Same
trigger bounce as 11238 / 11394, different symptom. Fix: add the code to the retry list.

- Run: https://github.com/canton-network/splice/actions/runs/43962551422, main 213565d133 ("resolve stale TODOs for
  closed canton-network-internal issues (#8517)"), job 133633989884 attempt 1
  `ci / scala_test_wall_clock_time / wall-clock-time (4)`.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620 (nix/canton-sources.json at the sha).
- Component: test infrastructure (console retry policy,
  `canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala`).

## 0. Issue and job resolution

Issue body, verbatim:

```
gh api /repos/DACH-NY/cn-test-failures/issues/11528 | jq -r '"#\(.number) [\(.state)] \(.title) \(.created_at)\n\(.body)"' | grep -v -E '^Actor:|^\s*$'
```
```
#11528 [open] GHA Run 43962551422 : job wall-clock-time (4) in scala_test Failed :fire: 2026-11-16T22:20:19Z
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43962551422).
Job: wall-clock-time (4) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [213565d](https://github.com/canton-network/splice/commit/213565d1334da7178f1937fd854d0a841afaface) resolve stale TODOs for closed canton-network-internal issues (#8517)
```

Job id (exact-name match) and run:
```
RUN=43962551422; export NAME='wall-clock-time (4)'; gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name) \(.started_at) \(.completed_at)"'
gh api repos/canton-network/splice/actions/runs/$RUN --jq '"\(.name) \(.head_branch) \(.head_sha) \(.run_attempt) \(.created_at) \(.conclusion)"'
```
```
133633989884 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (4) 2026-11-16T21:43:17Z 2026-11-16T22:20:28Z
CI post-merge to main or release-line branches main 213565d1334da7178f1937fd854d0a841afaface 1 2026-11-16T21:41:04Z failure
```

Canton pin:
```
git show 213565d1334da7178f1937fd854d0a841afaface:nix/canton-sources.json | grep -m1 version
```
```
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 1. Failing assertion

One test failed, a console command failure, no checkErrors section.
```
gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/133633989884/logs > log/11528/job.log
sed -E 's/\x1b\[[0-9;]*m//g' log/11528/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
```
##[error]Error: failed to run script step (id ad390640-e207-11f1-a620-b9b124809c18): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] *** 1 TEST FAILED ***
[info] - Reward collection should work for featured app activity markers *** FAILED ***
[info] Run completed in 29 minutes, 1 second.
[info] Tests: succeeded 39, failed 1, canceled 0, ignored 0, pending 0
```

The failing command, from the test log (artifact `logs-wall-clock-time-4`): a ledger API submission as bob on
bobParticipant refused with SUBMISSION_SYNCHRONIZER_NOT_READY inside the clue `(act) Create activity markers`.
```
T=log/11528/logs-wall-clock-time-4/canton_network_test.clog.gz
zcat $T | grep -a -E '"@timestamp":"2026-11-16T22:16:(2[4-9]|[345][0-9])' > log/11528/window.jsonl
grep -a -E '"level":"(WARN|ERROR)"' log/11528/window.jsonl | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g; s/  +/ /g' | cut -c1-900
```
```
2026-11-16T22:16:53.906Z Request failed for remote participant for `bobValidator`, with admin token. GrpcRequestRefusedByServer: NOT_FOUND/SUBMISSION_SYNCHRONIZER_NOT_READY(11,d30a512c): Trying to submit to a disconnected or not configured synchronizer. Request: SubmitAndWaitTransaction(actAs = bob__wallet__user-1e8fcfe1__tc0::122090109abd..., readAs = Seq(), commandId = '', workflowId = '', submissionId = '', deduplicationPeriod = None(), commands = ...) DecodedCantonError( code = 'SUBMISSION_SYNCHRONIZER_NOT_READY', category = InvalidGivenCurrentSystemStateResourceMissing, cause = \"Trying to submit to a disconnected or not configured synchronizer.\", traceId = 'd30a512c141ae4a831082e17ab19dbac', context = Seq('participant=>bobParticipant', 'synchronizerId=>global-domain::1220471827ca...::37-0', 'definite_answer=>false') ) Command ConsoleEnvironment.run$ invoked from SpliceConsoleEnvir
2026-11-16T22:16:53.906Z Failed clue: (act) Create activity markers",[o.l.s.i.t.FeaturedAppActivityMarkerIntegrationTest:FeaturedAppActivityMarkerIntegrationTest] ERROR
2026-11-16T22:16:53.914Z Test failed: 'FeaturedAppActivityMarkerIntegrationTest/Reward collection should work for featured app activity markers', message: Command execution failed., location: SeeStackDepthException",[c.d.c.LogReporter:reporter=scala-test] ERROR
```

## 2. Which test was running

```
grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" log/11528/window.jsonl | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
zcat log/11528/logs-wall-clock-time-4/canton_network_test.clog.gz | grep -a -m1 "Starting test suite 'FeaturedAppActivityMarkerIntegrationTest'" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /'
```
```
2026-11-16T22:16:24.816Z Starting 'FeaturedAppActivityMarkerIntegrationTest/Reward collection should work for featured app activity markers'...",
2026-11-16T22:16:53.914Z Test failed: 'FeaturedAppActivityMarkerIntegrationTest/Reward collection should work for featured app activity markers', message: Command execution failed., location: SeeStack
2026-11-16T22:13:51.492Z Starting test suite 'FeaturedAppActivityMarkerIntegrationTest'...",
```

## 3. The console retry policy saw the error and declined to retry it

```
grep -a 'Got canton error' log/11528/window.jsonl | grep -a 'SUBMISSION_SYNCHRONIZER_NOT_READY' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/(Trying to submit to a disconnected or not configured synchronizer\.).*(\. Retrying: [a-z]+).*/\1 [...]\2/'
```
```
2026-11-16T22:16:53.903Z Got canton error Some(DecodedCantonError(GenericErrorCode(SUBMISSION_SYNCHRONIZER_NOT_READY,InvalidGivenCurrentSystemStateResourceMissing),Trying to submit to a disconnected or not configured synchronizer. [...]. Retrying: false
```

The policy at the run's sha force-retries four codes; SubmissionSynchronizerNotReady is not one of them and its
category (InvalidGivenCurrentSystemStateResourceMissing) is not retryable. The file is the splice copy that is compiled
into the tests, so the citation is the code that ran.
```
git show 213565d1334da7178f1937fd854d0a841afaface:canton/community/integration-testing/src/main/scala/com/digitalasset/canton/integration/EnvironmentSetup.scala | sed -n '191,209p'
```
```
          val shouldRetry = decodedCantonError.exists { err =>
            val retryableErrorCodes = Seq(
              // Normally "not connected to synchronizer" errors are not retriable because they require operator intervention.
              // In canton network, synchronizer connections are managed by the sv app automation
              // and transient disconnects are normal.
              SyncServiceInjectionError.NotConnectedToAnySynchronizer,
              SyncServiceInjectionError.NotConnectedToSynchronizer,
              TransactionRoutingError.TopologyErrors.UnknownContractSynchronizers,
              TransactionRoutingError.TopologyErrors.UnknownInformees,
            )
            val forceRetry = retryableErrorCodes.exists(retryableErroCode =>
              err.code == retryableErroCode.code || err.code.id == retryableErroCode.id // some errors are wrapped in a GenericErrorCode so they lose the type information
            )
            if (forceRetry) {
              true
            } else {
              err.isRetryable
            }
          }
```

## 4. Who disconnected bobParticipant: bobValidator's ReconcileSequencerConnectionsTrigger

Scan serves the sequencers of sv2-sv4 only after their `availableAfter`; sv4's passed at 22:16:53.129563, sv3's at
22:16:54.85076.
```
grep -a 'validator=bobValidator' log/11528/window.jsonl | grep -a 'dso-sequencers): Received response with entity' | head -1 | python3 -I -c "
import sys,json
d=json.loads(sys.stdin.readline()); m=d['message']; j=json.loads(m[m.index('{'):])
for ds in j['domainSequencers']:
    for s in ds['sequencers']: print(d['@timestamp'], s.get('svName'), s.get('url'), s.get('availableAfter'))"
```
```
2026-11-16T22:16:24.511Z Digital-Asset-2 http://localhost:5108 2026-11-16T22:14:31.934577Z
2026-11-16T22:16:24.511Z Digital-Asset-Eng-2 http://localhost:5208 2026-11-16T22:17:17.036635Z
2026-11-16T22:16:24.511Z Digital-Asset-Eng-3 http://localhost:5308 2026-11-16T22:16:54.85076Z
2026-11-16T22:16:24.511Z Digital-Asset-Eng-4 http://localhost:5408 2026-11-16T22:16:53.129563Z
```

The trigger's poll at 22:16:53.801 is the first that sees sv4's sequencer; it modifies the connection config and
reconnects (disconnect 53.844-53.856, reconnect 53.856-53.975). The same happens for sv3 at 22:16:55.5.
```
grep -a 'validator=bobValidator' log/11528/window.jsonl | grep -a -E '"@timestamp":"2026-11-16T22:16:5[2-6]' | grep -a -E 'SynchronizerConnector|modifying sequencers connections|reconnect to the synchronizer|(Modify|Disconnect|Reconnect)Synchronizer to 127.0.0.1:5602: (sending request|succeeded)' | python3 -I -c "
import sys,json,re
for l in sys.stdin:
    d=json.loads(l); m=d['message']
    al=sorted(set(re.findall(r\"Sequencer '(SEQ::\w+)\", m)))
    print(d['@timestamp'][11:], d['logger_name'].split(':')[0].split('.')[-1], re.sub(r'\s+',' ',m)[:190], al if al else '')"
```
```
22:16:52.396Z SynchronizerConnector For synchronizer Synchronizer 'global' at time 2026-11-16T22:16:52.091074Z using Vector(GrpcSequencerConnection( sequencerAlias = Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f ['SEQ::sv1']
22:16:52.957Z SynchronizerConnector For synchronizer Synchronizer 'global' at time 2026-11-16T22:16:52.442445Z using Vector(GrpcSequencerConnection( sequencerAlias = Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f ['SEQ::sv1']
22:16:53.801Z SynchronizerConnector For synchronizer Synchronizer 'global' at time 2026-11-16T22:16:53.683453Z using Vector(GrpcSequencerConnection( sequencerAlias = Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f ['SEQ::sv1', 'SEQ::sv4']
22:16:53.808Z ReconcileSequencerConnectionsTrigger modifying sequencers connections to SequencerConnections( connections = Map( Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f9cc8a51ede24c52085aafa3e29793' -> GrpcSequencerConnec ['SEQ::sv1', 'SEQ::sv4']
22:16:53.810Z ApiClientRequestLogger Request (tid:71b4caacede30dd2e550d3377b353e1d) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer to 127.0.0.1:5602: sending request ModifySynch 
22:16:53.844Z ApiClientRequestLogger Request (tid:71b4caacede30dd2e550d3377b353e1d) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer to 127.0.0.1:5602: succeeded(OK) 
22:16:53.844Z ParticipantAdminConnection reconnect to the synchronizer Synchronizer 'global' for new sequencer configuration to take effect 
22:16:53.844Z ApiClientRequestLogger Request (tid:d688da4157a6454213fac03772c77cab) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer to 127.0.0.1:5602: sending request Disconn 
22:16:53.856Z ApiClientRequestLogger Request (tid:d688da4157a6454213fac03772c77cab) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer to 127.0.0.1:5602: succeeded(OK) 
22:16:53.856Z ApiClientRequestLogger Request (tid:d625021e165e6dc5b55bd933c6a96fcb) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizer to 127.0.0.1:5602: sending request Reconnec 
22:16:53.975Z ApiClientRequestLogger Request (tid:d625021e165e6dc5b55bd933c6a96fcb) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizer to 127.0.0.1:5602: succeeded(OK) 
22:16:54.759Z SynchronizerConnector For synchronizer Synchronizer 'global' at time 2026-11-16T22:16:54.361703Z using Vector(GrpcSequencerConnection( sequencerAlias = Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f ['SEQ::sv1', 'SEQ::sv4']
22:16:55.537Z SynchronizerConnector For synchronizer Synchronizer 'global' at time 2026-11-16T22:16:55.364973Z using Vector(GrpcSequencerConnection( sequencerAlias = Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f ['SEQ::sv1', 'SEQ::sv3', 'SEQ::sv4']
22:16:55.545Z ReconcileSequencerConnectionsTrigger modifying sequencers connections to SequencerConnections( connections = Map( Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f9cc8a51ede24c52085aafa3e29793' -> GrpcSequencerConnec ['SEQ::sv1', 'SEQ::sv3', 'SEQ::sv4']
22:16:55.547Z ApiClientRequestLogger Request (tid:b66b4764af9b0f27812d8c62f4f4d571) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer to 127.0.0.1:5602: sending request ModifySynch 
22:16:55.631Z ApiClientRequestLogger Request (tid:b66b4764af9b0f27812d8c62f4f4d571) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer to 127.0.0.1:5602: succeeded(OK) 
22:16:55.631Z ParticipantAdminConnection reconnect to the synchronizer Synchronizer 'global' for new sequencer configuration to take effect 
22:16:55.631Z ApiClientRequestLogger Request (tid:44ef252b1290c23eaa9deab45fbd112b) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer to 127.0.0.1:5602: sending request Disconn 
22:16:55.706Z ApiClientRequestLogger Request (tid:44ef252b1290c23eaa9deab45fbd112b) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer to 127.0.0.1:5602: succeeded(OK) 
22:16:55.706Z ApiClientRequestLogger Request (tid:6b856e7f4acca88845f2a481e3236788) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizer to 127.0.0.1:5602: sending request Reconnec 
22:16:55.859Z ApiClientRequestLogger Request (tid:6b856e7f4acca88845f2a481e3236788) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizer to 127.0.0.1:5602: succeeded(OK) 
22:16:56.822Z SynchronizerConnector For synchronizer Synchronizer 'global' at time 2026-11-16T22:16:56.404284Z using Vector(GrpcSequencerConnection( sequencerAlias = Sequencer 'SEQ::sv1::12205191a403a17d50d4dadd522d10b2adc2b9f ['SEQ::sv1', 'SEQ::sv3', 'SEQ::sv4']
```

## 5. The race on bobParticipant

The submission (trace d30a512c) is received at 53.832, computes its routing state at 53.834 while the synchronizer is
still connected, and is routed at 53.846, 1 ms after the disconnect started at 53.845.
```
zcat log/11528/logs-wall-clock-time-4/canton.clog.gz | grep -a 'participant=bobParticipant' | grep -a -E '"@timestamp":"2026-11-16T22:16:53\.(8|9)' | grep -a -E "d30a512c|Disconnecting from Synchronizer|Disconnected from Synchronizer|Connected to synchronizer and starting|ModifySynchronizer.*(received a message|succeeded)|Routing the transaction|Received submit-transaction" | grep -a -v -E 'Cache hit|TAPS|Topology queried|Synchronizers with|Checking whether|auth claims|Registering handle|Submitted commands are|: completed' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/\\n/ /g; s/  +/ /g; s/c\.d\.c\.[a-z.]*\.//' | cut -c1-250
```
```
2026-11-16T22:16:53.812Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:55298: received a message ModifySynchronizerRequest( global-domain::..a911::37-0, SynchronizerConnect
2026-11-16T22:16:53.832Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:45894: received a message SubmitAndWaitForTransactionRequest( Commands( \"\", CantonConsole, 82df8196-11f9-4ab1-9b71-455cf0ef911c, C
2026-11-16T22:16:53.833Z Phase 1 started: Submitting 1 command(s) for interpretation on behalf of bob__wallet__user-1e8fcfe1__tc0::122090109abd....",[CommandSubmissionServiceImpl:participant=bobParticipant] INFO
2026-11-16T22:16:53.834Z Routing state contains connected synchronizers splitwell::12207ff566cf...::36-0, global-domain::1220471827ca...::37-0 and topology splitwell::12207ff566cf...::36-0 at 2026-11-16T22:16:51.501628Z, global-domain::1220471827ca..
2026-11-16T22:16:53.843Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:55298: succeeded(OK)",[ApiRequestLogger:participant=bobParticipant] DEBUG
2026-11-16T22:16:53.844Z Choosing the synchronizer as single-synchronizer workflow for 82df8196-11f9-4ab1-9b71-455cf0ef911c",[TransactionRoutingProcessor:participant=bobParticipant] DEBUG
2026-11-16T22:16:53.845Z Received submit-transaction 82df8196-11f9-4ab1-9b71-455cf0ef911c from ledger-api server",[CantonSyncService:participant=bobParticipant] DEBUG
2026-11-16T22:16:53.845Z Disconnecting from Synchronizer 'global'",[SynchronizerConnectionsManager:participant=bobParticipant] INFO
2026-11-16T22:16:53.846Z Routing the transaction to synchronizer global-domain::1220471827ca...::37-0",[TransactionRoutingProcessor:participant=bobParticipant] DEBUG
2026-11-16T22:16:53.847Z SUBMISSION_SYNCHRONIZER_NOT_READY(11,d30a512c): Trying to submit to a disconnected or not configured synchronizer.",[CantonSyncService:participant=bobParticipant] INFO
2026-11-16T22:16:53.848Z Rejected: Submission failed with error SUBMISSION_SYNCHRONIZER_NOT_READY(11,d30a512c): Trying to submit to a disconnected or not configured synchronizer.",[CommandSubmissionServiceImpl:participant=bobParticipant] INFO
2026-11-16T22:16:53.848Z Request com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction by grpc:/127.0.0.1:45894: failed with NOT_FOUND/SUBMISSION_SYNCHRONIZER_NOT_READY(11,d30a512c): Trying to submit to a disconnected or not configured sy
2026-11-16T22:16:53.855Z Disconnected from Synchronizer 'global'",[SynchronizerConnectionsManager:participant=bobParticipant] INFO
2026-11-16T22:16:53.956Z Connected to synchronizer and starting synchronisation: Synchronizer 'global'",[SynchronizerConnectionsManager:participant=bobParticipant] INFO
```

Disconnects of bobParticipant from `global` over the whole shard (the participant is shared by all suites): three
bounces in this suite's environment, 22:16:53.845 (sv4's sequencer, hit the test), 22:16:55.632 (sv3's) and
22:17:21.916 (sv2's, `availableAfter` 22:17:17.036635, after the test had failed).
```
zcat log/11528/logs-wall-clock-time-4/canton.clog.gz | grep -a 'participant=bobParticipant' | grep -a "Disconnecting from Synchronizer 'global'" | sed -E 's/\{"@timestamp":"([^"]{23}).*/\1/'
```
```
2026-11-16T21:53:58.753
2026-11-16T22:08:16.076
2026-11-16T22:11:16.073
2026-11-16T22:16:53.845
2026-11-16T22:16:55.632
2026-11-16T22:17:21.916
2026-11-16T22:17:49.066
2026-11-16T22:19:11.527
```

## Verdict

New; family I (test reads or submits once while a validator's ReconcileSequencerConnectionsTrigger bounces the
synchronizer connection for a sequencer that just passed its `availableAfter`). Flake: the window is the ~110 ms
between `Disconnecting from Synchronizer` and `Connected to synchronizer` on a participant, up to three times per
4-SV environment, and the test submits 10 commands per participant in a row. No earlier tracker hit for the error code
(`gh search issues --repo DACH-NY/cn-test-failures SUBMISSION_SYNCHRONIZER_NOT_READY` returned nothing on 2026-11-17).

Fix location: test infrastructure. Branch `pperek/fix-11528-retry-submission-synchronizer-not-ready` (06fad84f08, off
main 69b922dca8, pushed 2026-11-17, no PR yet) adds `TransactionRoutingError.ConfigurationErrors.SubmissionSynchronizerNotReady` to
`retryableErrorCodes` in `EnvironmentSetup.scala`, next to the two NotConnected codes. The match is by error code id,
which is how the logged `GenericErrorCode(SUBMISSION_SYNCHRONIZER_NOT_READY, ...)` would be recognised. It covers every
console command submission in every suite, not only this test.

Not verified: the fix is not compiled and not run; that the retry's backoff outlasts the ~110 ms reconnect was not
measured; why this Canton version reports SUBMISSION_SYNCHRONIZER_NOT_READY here instead of
NOT_CONNECTED_TO_SYNCHRONIZER (routing chose the synchronizer from a routing state taken 11 ms before the disconnect)
was not traced in the Canton jar.
