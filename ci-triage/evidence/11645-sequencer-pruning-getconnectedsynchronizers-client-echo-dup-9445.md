# 11645 - SequencerPruningIntegrationTest checkErrors: sv2Validator echoes the GetConnectedSynchronizers INTERNAL of a reconnecting sv2Participant (run 44462610608)

DUPLICATE of 9445 (open, assigned carlpulley-da), client-side shape, same as 11364. main 1eafba3113 ("add-column
migration for BigQuery staging->production (#8521)"), nightly workflow "Integration tests against mainnet Daml
version", job wall-clock-time (8), attempt 1, Canton 3.7.4-snapshot.20261112.20915.0.v9e248620. All 24 tests passed;
checkErrors flagged three lines of sv2Validator at 05:32:56.323-.325 in SequencerPruningIntegrationTest: the
ApiClientRequestLogger ERROR `StateService/GetConnectedSynchronizers to 127.0.0.1:5201: failed with INTERNAL/An error
occurred...` and the DomainIngestionService WARN (`processTaskWithRetry failed with a non-retryable error`) and ERROR
(`Skipping processing of PeriodicTask`). Mechanism: the sv2 sequencer became visible to sv2Validator at 05:32:56.279,
its ReconcileSequencerConnectionsTrigger modified the synchronizer connection and disconnected / reconnected
sv2Participant (DisconnectSynchronizer 05:32:56.317-.327); the DomainIngestionService poll of sv2Validator
(tid 0e8e81d9...) reached the participant at 05:32:56.318, while the connected synchronizer was being closed, and the
participant answered `INTERNAL/Failed retrieving SynchronizerTopologyClient` (the 9445 race). That participant line is
ignored since splice#7334 and was printed as ignored here; the client echo in the app log has no ignore, so it failed
the job. Not related to family AH (no later suite failed at setup).

- Run: https://github.com/canton-network/splice/actions/runs/44462610608, main 1eafba3113 ("add-column migration for
  BigQuery staging->production (#8521)"), job 135353669325 `build / scala_test_wall_clock_time / wall-clock-time (8)`,
  attempt 1, the only failed job of the run.
- Runtime canton: 3.7.4-snapshot.20261112.20915.0.v9e248620.
- Component: Canton participant (GetConnectedSynchronizers during disconnect); splice app logging of the echo.

## 0. Issue and job resolution

```
gh run view 44462610608 -R canton-network/splice --json headSha,headBranch,event,createdAt,conclusion,workflowName,jobs --jq '{headSha,headBranch,event,createdAt,conclusion,workflowName, failed:[.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{name,databaseId,conclusion,startedAt,completedAt}]}'
```
```
{"conclusion":"failure","createdAt":"2026-11-19T05:04:28Z","event":"schedule","failed":[{"completedAt":"2026-11-19T05:49:25Z","conclusion":"failure","databaseId":135353669325,"name":"build / scala_test_wall_clock_time / wall-clock-time (8)","startedAt":"2026-11-19T05:08:37Z"}],"headBranch":"main","headSha":"1eafba3113507146897f7b6f275e6379b0d3eb16","workflowName":"Integration tests against mainnet Daml version"}
```

```
gh api repos/canton-network/splice/actions/jobs/135353669325 --jq '"runner=\(.runner_name) attempt=\(.run_attempt) started=\(.started_at) completed=\(.completed_at)"'; git show 1eafba3113507146897f7b6f275e6379b0d3eb16:nix/canton-sources.json | grep -m1 version
```
```
runner=self-hosted-k8s-large-runner-dcdv2 attempt=1 started=2026-11-19T05:08:37Z completed=2026-11-19T05:49:25Z
  "version": "3.7.4-snapshot.20261112.20915.0.v9e248620",
```

## 1. Flagged lines

Job log at `log/11645/job.log` (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/135353669325/logs`).
Three lines without the ignore suffix, all from sv2Validator in SequencerPruningIntegrationTest.

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11645/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' | sed -E 's/^[^Z]*Z //' | sort -u; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11645/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-420
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 27 minutes, 26 seconds.
[info] Tests: succeeded 24, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-19T05:32:56.323Z","message":"Request (tid:0e8e81d950a16c1fd7fe6840f05e2ee1) com.daml.ledger.api.v2.StateService/GetConnectedSynchronizers to 127.0.0.1:5201: failed with INTERNAL/An error occurred. Please contact the operator and inquire about the request <no-correlation-id> with tid <no-tid>\n  Trailers: Metadata(content-type=application/grpc,grpc-status-details-bin=CA0SckFuIGVycm9yIG9jY3Vycm
***"@timestamp":"2026-11-19T05:32:56.325Z","message":"The operation 'processTaskWithRetry' failed with a non-retryable error, not retrying:\ncategory=None\nstatusCode=INTERNAL\ndescription=An error occurred. Please contact the operator and inquire about the request <no-correlation-id> with tid <no-tid>","logger_name":"o.l.s.a.DomainIngestionService:SequencerPruningIntegrationTest/config=57612c9d/validator=sv2Validator/
***"@timestamp":"2026-11-19T05:32:56.325Z","message":"Skipping processing of \nPeriodicTask(for = 2026-11-19T05:32:56.315841Z)\ndue to unexpected failure","logger_name":"o.l.s.a.DomainIngestionService:SequencerPruningIntegrationTest/config=57612c9d/validator=sv2Validator/endUserParty=digital-asset-eng-2-57612c9d::1220a8ec755c","thread_name":"SequencerPruningIntegrationTest-57612c9d-env-ec-5046","level":"ERROR","stack_
Total: 3 lines with problems.
```

## 2. Suite timeline

Artifact `logs-wall-clock-time-8` (id 14502133600) in `log/11645/logs-wall-clock-time-8/`. The flagged lines fall into
the only test of SequencerPruningIntegrationTest, 2 min after its start (environment bootstrap, sv2 onboarding).

```
D=log/11645/logs-wall-clock-time-8; zcat $D/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'T05:3[0-5]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-11-19T05:30:51.313Z Test succeeded: 'ExternalPartySetupProposalIntegrationTest/TransferPreapprovals get expired by SV automation'",
2026-11-19T05:30:51.377Z Starting test suite 'SequencerPruningIntegrationTest'...",
2026-11-19T05:30:51.377Z Starting 'SequencerPruningIntegrationTest/sequencer can be pruned even if a participant is down'...",
2026-11-19T05:35:46.234Z Test succeeded: 'SequencerPruningIntegrationTest/sequencer can be pruned even if a participant is down'",
2026-11-19T05:35:46.244Z Starting test suite 'Sv1BootstrapProtocolVersion36IntegrationTest'...",
2026-11-19T05:35:47.858Z Starting 'Sv1BootstrapProtocolVersion36IntegrationTest/sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins'...",
```

## 3. Confirming grep of the family: the same request fails on sv2Participant while it disconnects

The participant logs the real reason (`Failed retrieving SynchronizerTopologyClient`) 1 ms after
`DisconnectSynchronizer ... received`, between `Attempting to close 'ConnectedSynchronizer...` and `Successfully
closed`; the client only sees the masked INTERNAL 5 ms later.

```
D=log/11645/logs-wall-clock-time-8; zcat $D/canton_before_shutdown.clog.gz | grep -a -E '"@timestamp":"2026-11-19T05:32:56\.(2[5-9]|3[0-9])' | grep -a 'participant=sv2Participant' | grep -a -E 'GetConnectedSynchronizers.*failed|SynchronizerConnectionsManager|ModifySynchronizer.*completed|DisconnectSynchronizer.*(received|completed)|ReconnectSynchronizer.*received' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*"trace-id":"([0-9a-f]+)".*/[\1] \2 trace=\3/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-330
```
```
2026-11-19T05:32:56.315Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:49020: completed",[c.d.c.l.a.ApiRequestLogger:participant=sv2Participant] DEBUG trace=678e8e9fc1b2b84e4367a69036a06aa7
2026-11-19T05:32:56.317Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer by grpc:/127.0.0.1:49020: received a message DisconnectSynchronizerRequest(global)",[c.d.c.l.a.ApiRequestLogger:participant=sv2Participant] DEBUG trace=a8ead58415d0a35fbe9ce3d723d97371
2026-11-19T05:32:56.317Z Disconnecting from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO trace=a8ead58415d0a35fbe9ce3d723d97371
2026-11-19T05:32:56.317Z Disconnecting connected synchronizer global-domain::1220cdd2ee37...::36-0",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO trace=a8ead58415d0a35fbe9ce3d723d97371
2026-11-19T05:32:56.317Z Attempting to close 'ConnectedSynchronizer(synchronizerId=global-domain::1220cdd2ee37...::36-0, participantId=PAR::sv2::1220a8ec755c...)'...","logger_name":"c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant","thread_name":"canton-env-ec-63","level":"DEBUG"}
2026-11-19T05:32:56.318Z Request com.daml.ledger.api.v2.StateService/GetConnectedSynchronizers by grpc:/127.0.0.1:34974: failed with INTERNAL/Failed retrieving SynchronizerTopologyClient for synchronizer `global-domain::1220cdd2ee37...::36-0` with alias Synchronizer 'global'",[c.d.c.l.a.ApiRequestLogger:participant=sv2Participan
2026-11-19T05:32:56.326Z Successfully closed 'ConnectedSynchronizer(synchronizerId=global-domain::1220cdd2ee37...::36-0, participantId=PAR::sv2::1220a8ec755c...)'.","logger_name":"c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant","thread_name":"canton-env-ec-63","level":"DEBUG"}
2026-11-19T05:32:56.326Z Disconnected from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO trace=a8ead58415d0a35fbe9ce3d723d97371
2026-11-19T05:32:56.327Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer by grpc:/127.0.0.1:49020: completed",[c.d.c.l.a.ApiRequestLogger:participant=sv2Participant] DEBUG trace=a8ead58415d0a35fbe9ce3d723d97371
2026-11-19T05:32:56.328Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizer by grpc:/127.0.0.1:49020: received a message ReconnectSynchronizerRequest(global, false)",[c.d.c.l.a.ApiRequestLogger:participant=sv2Participant] DEBUG trace=95793582a55da28f56fdd0a9e06480df
2026-11-19T05:32:56.329Z Trying to connect PAR::sv2::1220a8ec755c... to Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] DEBUG trace=95793582a55da28f56fdd0a9e06480df
2026-11-19T05:32:56.329Z About to connect to synchronizer: global",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] DEBUG trace=95793582a55da28f56fdd0a9e06480df
2026-11-19T05:32:56.329Z Connecting to synchronizer with id global-domain::..d3c2::36-0 config: SynchronizerConnectionConfig(\n  synchronizer = Synchronizer 'global',\n  physicalSynchronizerId = global-domain::1220cdd2ee37...::36-0,\n  sequencerConnections = SequencerConnections(\n    connections = Map(\n      Sequencer 'SEQ::sv
2026-11-19T05:32:56.378Z Registering id global-domain::1220cdd2ee37...::36-0 for synchronizer with alias Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] DEBUG trace=95793582a55da28f56fdd0a9e06480df
2026-11-19T05:32:56.396Z Connected to synchronizer and starting synchronisation: Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO trace=95793582a55da28f56fdd0a9e06480df
```

The participant line carries the trace id that the client line prints as `tid:` (0e8e81d950a16c1fd7fe6840f05e2ee1):

```
D=log/11645/logs-wall-clock-time-8; zcat $D/canton_before_shutdown.clog.gz | grep -a '"@timestamp":"2026-11-19T05:32:56' | grep -a 'Failed retrieving SynchronizerTopologyClient' | grep -a -o -E '"@timestamp":"[^"]+"|"level":"[A-Z]+"|"trace-id":"[0-9a-f]+"' | tr '\n' ' '
```
```
"@timestamp":"2026-11-19T05:32:56.318Z" "level":"ERROR" "trace-id":"0e8e81d950a16c1fd7fe6840f05e2ee1" 
```

## 4. Who disconnected sv2Participant: sv2Validator reconciles sequencer connections after the sv2 sequencer appears

The SynchronizerConnector of sv2Validator lists only sv1 until 05:32:55.119 and sv1 + sv2 at 05:32:56.279;
ReconcileSequencerConnectionsTrigger modifies the connection and ParticipantAdminConnection reconnects the
synchronizer.

```
D=log/11645/logs-wall-clock-time-8; zcat $D/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-19T05:32:5[4-6]' | grep -a 'SynchronizerConnector:SequencerPruningIntegrationTest/config=57612c9d/validator=sv2Validator' | grep -a 'For synchronizer' | awk '{ts=substr($0,16,24); s=""; while (match($0,/sequencerId = SEQ::sv[0-9]/)) { s=s" "substr($0,RSTART+19,3); $0=substr($0,RSTART+RLENGTH)}; print ts, s}'
```
```
2026-11-19T05:32:54.178Z  sv1
2026-11-19T05:32:55.119Z  sv1
2026-11-19T05:32:56.279Z  sv1 sv2
```

```
D=log/11645/logs-wall-clock-time-8; zcat $D/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-19T05:32:5[5-6]' | grep -a -E 'modifying sequencers|reconnect to the synchronizer' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/(SEQ::sv1::[0-9a-f]{8})[^[]*(\[o\.l)/\1 ... \2/' | cut -c1-300
```
```
2026-11-19T05:32:56.284Z modifying sequencers connections to SequencerConnections(\n  connections = Map(\n    Sequencer 'SEQ::sv1::12206ff1 ... [o.l.s.v.a.ReconcileSequencerConnectionsTrigger:SequencerPruningIntegrationTest/config=57612c9d/validator=sv2Validator] INFO
2026-11-19T05:32:56.316Z reconnect to the synchronizer Synchronizer 'global' for new sequencer configuration to take effect",[o.l.s.e.ParticipantAdminConnection:SequencerPruningIntegrationTest/config=57612c9d/validator=sv2Validator] INFO
```

## 5. The existing workaround ignores only the participant-side line

splice#7334 ignores the participant line in canton_log.ignore.txt; nothing covers the client echo. The job log prints
the participant line as ignored.

```
git grep -n -E 'GetConnectedSynchronizers|contact the operator' 1eafba3113 -- project/ignore-patterns | cut -c1-200
```
```
1eafba3113:project/ignore-patterns/canton_log.ignore.txt:214:# GetConnectedSynchronizers call for one synchronizer that is mid-reconnect.
1eafba3113:project/ignore-patterns/canton_log.ignore.txt:215:StateService/GetConnectedSynchronizers.*INTERNAL/Failed retrieving SynchronizerTopologyClient
```

```
grep -a 'Failed retrieving SynchronizerTopologyClient' log/11645/job.log | sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' | grep -a -o -E '^.{0,60}|.{0,45}$'
```
```
***"@timestamp":"2026-11-19T05:32:56.318Z","message":"Reques
*** (ignore this line in check-sbt-output.sh)
```

## 6. Duplicate search

```
gh issue view 9445 -R DACH-NY/cn-test-failures --json title,state,stateReason,closedAt,createdAt
```
```
{"closedAt":null,"createdAt":"2026-07-31T10:48:51Z","state":"OPEN","stateReason":"","title":"GetConnectedSynchronizers returns INTERNAL during a synchronizer reconnect"}
```

```
for q in '"Failed retrieving SynchronizerTopologyClient"' 'GetConnectedSynchronizers'; do echo "== $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q" --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) assignees=\([.assignees[].login]|join(",")) \(.title[0:90])"'; done
```
```
== "Failed retrieving SynchronizerTopologyClient"
#9445 open/- 2026-07-31 assignees=carlpulley-da GetConnectedSynchronizers returns INTERNAL during a synchronizer reconnect
#9794 closed/duplicate 2026-08-31 assignees= GHA Run 33370318165 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
== GetConnectedSynchronizers
#9445 open/- 2026-07-31 assignees=carlpulley-da GetConnectedSynchronizers returns INTERNAL during a synchronizer reconnect
#11514 closed/completed 2026-11-16 assignees=pawelperek-da BootstrapTest: unexpected WARN "Waiting for domain Synchronizer 'global' to be connected h
#11364 closed/duplicate 2026-11-11 assignees= GHA Run 43031077112 : job wall-clock-time (5) in scala_test Failed :fire:
#10153 closed/duplicate 2026-09-16 assignees= GHA Run 35113435367 : job wall-clock-time (0) in scala_test Failed :fire:
#9794 closed/duplicate 2026-08-31 assignees= GHA Run 33370318165 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
#9793 closed/completed 2026-08-31 assignees=ray-roestenburg-da Failed UnclaimedSvRewardsScriptIntegrationTest: had size 4 instead of expected size 3 sv1 
#6016 closed/not_planned 2025-10-21 assignees= SvFrontendIntegrationTest: aliceParticipant fails to get connected synchronizers
```

- 9445: parent, open, not a duplicate of anything; 9794 and 11364 were closed as its duplicates.
- 11364 (2026-11-11): same client-side shape on sv2Scan, packet `11364-getconnectedsynchronizers-client-echo-dup-9445.md`
  (it also covers why DomainIngestionService logs WARN/ERROR: quiet PeriodicTaskTrigger, INTERNAL not retryable, the
  next poll is the retry).
- 6016 (2025-10): same client-side shape on aliceValidator, closed not planned.
- 11514, 10153, 9793: other failures (see the 11364 packet for 10153 and 9793; 11514 is a BootstrapTest WARN).

## Verdict

- Duplicate of 9445 (Canton: GetConnectedSynchronizers fails the whole request with INTERNAL while a synchronizer is
  being disconnected). Trigger here: sv2Validator ReconcileSequencerConnectionsTrigger reconnects sv2Participant when
  the sv2 sequencer appears during the SequencerPruningIntegrationTest bootstrap; a DomainIngestionService poll (1 s
  interval) landed 1 ms into the 10 ms disconnect.
- Flake, client-side shape: third hit (6016, 11364, 11645), second in 8 days. The first 11364 packet called it rare;
  two hits in 8 days on Canton 3.7.3 / 3.7.4 snapshots is worth a note to the 9445 owner.
- Fix location: Canton (9445). A splice-side mitigation (app-log ignore for the masked INTERNAL on
  GetConnectedSynchronizers, or treating it as transient in DomainIngestionService) is a production / ignore-pattern
  decision for the 9445 owner; none made here, no fix branch.
- Action: close 11645 as a duplicate of 9445.
- Not verified: whether the next sv2Validator poll succeeded (quiet trigger, no success line at INFO); the test passed.
- Lesson: none (Canton bug).
