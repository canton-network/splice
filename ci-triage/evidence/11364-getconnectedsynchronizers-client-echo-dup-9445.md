# 11364 - SvOnboardingAddlIntegrationTest checkErrors: sv2Scan echoes the GetConnectedSynchronizers INTERNAL of a reconnecting sv2Participant (run 43031077112)

DUPLICATE of 9445 (open, assigned carlpulley-da), new shape: the client side. main 644b3ba ("Pause faucet coupon
automation in ScanEventHistoryIntegrationTest (#8424)"), job wall-clock-time (5), attempt 1, Canton
3.7.3-snapshot.20261109.20894.0.ve6e6c252. All 27 tests passed; checkErrors flagged three lines of sv2Scan at
14:32:20.926-.930 in the first test of SvOnboardingAddlIntegrationTest: the ApiClientRequestLogger ERROR
`StateService/GetConnectedSynchronizers to 127.0.0.1:5201: failed with INTERNAL/An error occurred...` and the
DomainIngestionService WARN (`processTaskWithRetry failed with a non-retryable error`) and ERROR (`Skipping processing
of PeriodicTask`). Mechanism: sv4 sequencer became visible to sv2Validator at 14:32:20.865, its
ReconcileSequencerConnectionsTrigger modified the synchronizer connection and disconnected / reconnected sv2Participant
(DisconnectSynchronizer 14:32:20.920-.940); the sv2Scan poll of GetConnectedSynchronizers failed at 14:32:20.921, while
the topology client was being closed, and the participant answered `INTERNAL/Failed retrieving SynchronizerTopologyClient`
(the 9445 race). That participant line is ignored since splice#7334 and was printed as ignored here; the client echo in
the app log, masked as `An error occurred. Please contact the operator`, has no ignore, so it failed the job. Same
client shape once before: 6016 (2025-10-21, aliceValidator, closed not planned).

- Run: https://github.com/canton-network/splice/actions/runs/43031077112, main 644b3ba97a ("Pause faucet coupon
  automation in ScanEventHistoryIntegrationTest (#8424)"), job 130559764394 `ci / scala_test_wall_clock_time /
  wall-clock-time (5)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: Canton participant (GetConnectedSynchronizers during disconnect); splice scan app logging.

## 0. Issue and job resolution

```
gh api repos/DACH-NY/cn-test-failures/issues/11364 --jq '"\(.state) \(.title)\n\(.body)"'
```
```
open GHA Run 43031077112 : job wall-clock-time (5) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43031077112).
Job: wall-clock-time (5) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [644b3ba](https://github.com/canton-network/splice/commit/644b3ba97ab4c3cf188eea599a0e53ef3e2cf6ad) Pause faucet coupon automation in ScanEventHistoryIntegrationTest (#8424)
Actor: rautenrieth-da

```

```
RUN=43031077112; export NAME='wall-clock-time (5)'; for p in 1 2 3 4 5; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'; gh api repos/canton-network/splice/actions/jobs/130559764394 --jq '"runner=\(.runner_name) started=\(.started_at) completed=\(.completed_at)"'; git show 644b3ba97ab4c3cf188eea599a0e53ef3e2cf6ad:nix/canton-sources.json | grep -m1 version
```
```
130559764394 attempt=1 ci / scala_test_wall_clock_time / wall-clock-time (5)
runner=self-hosted-k8s-large-runner-j2wd8 started=2026-11-11T13:58:57Z completed=2026-11-11T14:44:16Z
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

## 1. Flagged lines

Job log (ANSI stripped) at `log/inbox-1111b/130559764394.log`. Three lines without the ignore suffix, all from sv2Scan in SvOnboardingAddlIntegrationTest.

```
L=log/inbox-1111b/130559764394.log; grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Run completed' $L | sed -E 's/^[^Z]*Z //' | sort -u; sed -E 's/^[^Z]*Z //' $L | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line in' | cut -c1-420
```
```
[error] (checkErrors) log/canton_network_test.clog contains problems.
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[info] All tests passed.
[info] Run completed in 29 minutes, 11 seconds.
[info] Tests: succeeded 27, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-11T14:32:20.926Z","message":"Request (tid:0d9ce1cbbbd174ce7ba8b869f1b8fdc0) com.daml.ledger.api.v2.StateService/GetConnectedSynchronizers to 127.0.0.1:5201: failed with INTERNAL/An error occurred. Please contact the operator and inquire about the request <no-correlation-id> with tid <no-tid>\n  Trailers: Metadata(content-type=application/grpc,grpc-status-details-bin=CA0SckFuIGVycm9yIG9jY3Vycm
***"@timestamp":"2026-11-11T14:32:20.928Z","message":"The operation 'processTaskWithRetry' failed with a non-retryable error, not retrying:\ncategory=None\nstatusCode=INTERNAL\ndescription=An error occurred. Please contact the operator and inquire about the request <no-correlation-id> with tid <no-tid>","logger_name":"o.l.s.a.DomainIngestionService:SvOnboardingAddlIntegrationTest/config=bdc19633/scan=sv2Scan","thread
***"@timestamp":"2026-11-11T14:32:20.930Z","message":"Skipping processing of \nPeriodicTask(for = 2026-11-11T14:32:20.917747Z)\ndue to unexpected failure","logger_name":"o.l.s.a.DomainIngestionService:SvOnboardingAddlIntegrationTest/config=bdc19633/scan=sv2Scan","thread_name":"SvOnboardingAddlIntegrationTest-bdc19633-env-ec-5181","level":"ERROR","stack_trace":"io.grpc.StatusRuntimeException: INTERNAL: An error occurr
Total: 3 lines with problems.
```

## 2. Suite timeline

Artifact `logs-wall-clock-time-5` (id 13866635211) in `log/11364/logs-wall-clock-time-5/`. The flagged lines fall into the first test of SvOnboardingAddlIntegrationTest.

```
zcat log/11364/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'T14:3[0-4]' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-11-11T14:30:20.055Z Test succeeded: 'BootstrapPackageConfigDarUploadWithUnsupportedVersionsIntegrationTest/Bootstrap with specific versions and check that no newer D
2026-11-11T14:30:26.968Z Starting test suite 'SvOnboardingAddlIntegrationTest'...",
2026-11-11T14:30:26.969Z Starting 'SvOnboardingAddlIntegrationTest/SVs can onboard new SVs'...",
2026-11-11T14:32:43.463Z Test succeeded: 'SvOnboardingAddlIntegrationTest/SVs can onboard new SVs'",
2026-11-11T14:32:43.463Z Starting 'SvOnboardingAddlIntegrationTest/SV onboarding status is reported correctly for `unknown` and `confirmed` states'...",
2026-11-11T14:32:54.567Z Test succeeded: 'SvOnboardingAddlIntegrationTest/SV onboarding status is reported correctly for `unknown` and `confirmed` states'",
2026-11-11T14:32:54.567Z Starting 'SvOnboardingAddlIntegrationTest/fail to submit command with actAs = dso if there are more than 1 SV onboarded'...",
2026-11-11T14:33:36.042Z Test succeeded: 'SvOnboardingAddlIntegrationTest/fail to submit command with actAs = dso if there are more than 1 SV onboarded'",
2026-11-11T14:33:36.051Z Starting test suite 'BootstrapTest'...",
2026-11-11T14:33:36.052Z Starting 'BootstrapTest/Bootstrap script should pass'...",
```

## 3. Participant side: the same request fails with INTERNAL on sv2Participant while it disconnects

The participant logs the real reason (`Failed retrieving SynchronizerTopologyClient`), in the middle of closing the connected synchronizer; the client only sees the masked INTERNAL.

```
zcat log/11364/logs-wall-clock-time-5/canton_before_shutdown.clog.gz | grep -a -E '"@timestamp":"2026-11-11T14:32:20\.9[0-4]' | grep -a 'participant=sv2Participant' | grep -a -E 'GetConnectedSynchronizers.*failed|SynchronizerConnectionsManager|Successfully closed .com.digitalasset.canton.topology.client|Attempting to close .com.digitalasset.canton.topology.client|ModifySynchronizer.*completed|DisconnectSynchronizer.*(received|completed)|ReconnectSynchronizer.*received' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-250
```
```
2026-11-11T14:32:20.917Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer by grpc:/127.0.0.1:58372: completed",[c.d.c.l.a.ApiRequestLogger:participant=sv2Participant] DEBUG
2026-11-11T14:32:20.920Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer by grpc:/127.0.0.1:58372: received a message DisconnectSynchronizerRequest(global)",[c.d.c.l.a.ApiRequestLogger:part
2026-11-11T14:32:20.920Z Disconnecting from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO
2026-11-11T14:32:20.920Z Disconnecting connected synchronizer global-domain::1220ebaec8c0...::37-0",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO
2026-11-11T14:32:20.920Z Attempting to close 'ConnectedSynchronizer(synchronizerId=global-domain::1220ebaec8c0...::37-0, participantId=PAR::sv2::1220930bd074...)'...",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] DEBUG
2026-11-11T14:32:20.921Z Attempting to close 'com.digitalasset.canton.topology.client.WriteThroughCacheSynchronizerTopologyClient@4aa42182'...",[c.d.c.p.s.ConnectedSynchronizer:participant=sv2Participant/psid=global-domain::1220ebaec8c0::37-0] DEBUG
2026-11-11T14:32:20.922Z Attempting to close 'com.digitalasset.canton.topology.client.StoreBasedSynchronizerTopologyClient@1da72345'...",[c.d.c.t.c.WriteThroughCacheSynchronizerTopologyClient:participant=sv2Participant/psid=global-domain::1220ebaec8c
2026-11-11T14:32:20.922Z Successfully closed 'com.digitalasset.canton.topology.client.StoreBasedSynchronizerTopologyClient@1da72345'.",[c.d.c.t.c.WriteThroughCacheSynchronizerTopologyClient:participant=sv2Participant/psid=global-domain::1220ebaec8c0:
2026-11-11T14:32:20.921Z Request com.daml.ledger.api.v2.StateService/GetConnectedSynchronizers by grpc:/127.0.0.1:49126: failed with INTERNAL/Failed retrieving SynchronizerTopologyClient for synchronizer `global-domain::1220ebaec8c0...::37-0` with al
2026-11-11T14:32:20.922Z Successfully closed 'com.digitalasset.canton.topology.client.WriteThroughCacheSynchronizerTopologyClient@4aa42182'.",[c.d.c.p.s.ConnectedSynchronizer:participant=sv2Participant/psid=global-domain::1220ebaec8c0::37-0] DEBUG
2026-11-11T14:32:20.923Z Attempting to close 'com.digitalasset.canton.topology.client.WriteThroughCacheSynchronizerTopologyClient@4aa42182'...",[c.d.c.c.SynchronizerCryptoClient:participant=sv2Participant/psid=global-domain::1220ebaec8c0::37-0] DEBUG
2026-11-11T14:32:20.923Z Attempting to close 'com.digitalasset.canton.topology.client.StoreBasedSynchronizerTopologyClient@1da72345'...",[c.d.c.t.c.WriteThroughCacheSynchronizerTopologyClient:participant=sv2Participant/psid=global-domain::1220ebaec8c
2026-11-11T14:32:20.923Z Successfully closed 'com.digitalasset.canton.topology.client.StoreBasedSynchronizerTopologyClient@1da72345'.",[c.d.c.t.c.WriteThroughCacheSynchronizerTopologyClient:participant=sv2Participant/psid=global-domain::1220ebaec8c0:
2026-11-11T14:32:20.923Z Successfully closed 'com.digitalasset.canton.topology.client.WriteThroughCacheSynchronizerTopologyClient@4aa42182'.",[c.d.c.c.SynchronizerCryptoClient:participant=sv2Participant/psid=global-domain::1220ebaec8c0::37-0] DEBUG
2026-11-11T14:32:20.940Z Successfully closed 'ConnectedSynchronizer(synchronizerId=global-domain::1220ebaec8c0...::37-0, participantId=PAR::sv2::1220930bd074...)'.",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] DEBUG
2026-11-11T14:32:20.940Z Disconnected from Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] INFO
2026-11-11T14:32:20.940Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer by grpc:/127.0.0.1:58372: completed",[c.d.c.l.a.ApiRequestLogger:participant=sv2Participant] DEBUG
2026-11-11T14:32:20.941Z Request com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizer by grpc:/127.0.0.1:58372: received a message ReconnectSynchronizerRequest(global, false)",[c.d.c.l.a.ApiRequestLogger
2026-11-11T14:32:20.941Z Trying to connect PAR::sv2::1220930bd074... to Synchronizer 'global'",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] DEBUG
2026-11-11T14:32:20.942Z About to connect to synchronizer: global",[c.d.c.p.s.SynchronizerConnectionsManager:participant=sv2Participant] DEBUG
2026-11-11T14:32:20.942Z Connecting to synchronizer with id global-domain::..8fe8::37-0 config: SynchronizerConnectionConfig(\n  synchronizer = Synchronizer 'global',\n  physicalSynchronizerId = global-domain::1220ebaec8c0...::37-0,\n  sequencerConne
```

## 4. Who disconnected sv2Participant: the sv2 validator reconciles sequencer connections after sv4 sequencer appears

The SynchronizerConnector of sv2Validator lists 3 sequencers until 14:32:19.862 and 4 (sv4 added) at 14:32:20.865; ReconcileSequencerConnectionsTrigger modifies the connection and ParticipantAdminConnection reconnects the synchronizer.

```
zcat log/11364/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-11T14:32:(1[5-9]|20)' | grep -a 'SynchronizerConnector:SvOnboardingAddlIntegrationTest/config=bdc19633/validator=sv2Validator' | grep -a 'For synchronizer' | awk '{ts=substr($0,16,24); n=gsub(/endpoints = /,"&"); print ts, "sequencers=" n}'
```
```
2026-11-11T14:32:15.440Z sequencers=3
2026-11-11T14:32:16.623Z sequencers=3
2026-11-11T14:32:17.892Z sequencers=3
2026-11-11T14:32:18.862Z sequencers=3
2026-11-11T14:32:19.862Z sequencers=3
2026-11-11T14:32:20.865Z sequencers=4
```

The added sequencer is sv4:

```
zcat log/11364/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-11T14:32:(1[89]|20)' | grep -a 'SynchronizerConnector:SvOnboardingAddlIntegrationTest/config=bdc19633/validator=sv2Validator' | grep -a 'For synchronizer' | awk '{ts=substr($0,16,24); s=""; while (match($0,/sequencerId = SEQ::sv[0-9]/)) { s=s" "substr($0,RSTART+19,3); $0=substr($0,RSTART+RLENGTH)}; print ts, s}'
```
```
2026-11-11T14:32:18.862Z  sv1 sv2 sv3
2026-11-11T14:32:19.862Z  sv1 sv2 sv3
2026-11-11T14:32:20.865Z  sv1 sv2 sv3 sv4
```

```
zcat log/11364/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-11T14:32:20\.(87|91)' | grep -a -E 'modifying sequencers|reconnect to the synchronizer' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/(SEQ::sv1::..e48e.).*(\[o\.l)/\1 ... \2/' | cut -c1-260
```
```
2026-11-11T14:32:20.871Z modifying sequencers connections to SequencerConnections(\n  connections = Map(\n    Sequencer 'SEQ::sv1::122033eb491c83301a5016da4878fec1f23e557800bdd5f9e886c29c7f4eb53ee48e' -> GrpcSequencerConnection(\n      sequencerAlias = Sequenc
2026-11-11T14:32:20.918Z reconnect to the synchronizer Synchronizer 'global' for new sequencer configuration to take effect",[o.l.s.e.ParticipantAdminConnection:SvOnboardingAddlIntegrationTest/config=bdc19633/validator=sv2Validator] INFO
```

## 5. The existing workaround ignores only the participant-side line

canton-network/splice#7334 (f3adbc39e1, 2026-09-16) added a canton_log ignore for the participant line; nothing in the app-log ignore file covers the client echo (ApiClientRequestLogger ERROR, DomainIngestionService WARN and ERROR). The job log shows the participant line printed as ignored.

```
S=644b3ba97a; git grep -n -E 'DomainIngestionService|GetConnectedSynchronizers|contact the operator' $S -- project/ignore-patterns | cut -c1-200; git log --format='%h %ad %s' --date=short -S'GetConnectedSynchronizers' $S -- project/ignore-patterns
```
```
644b3ba97a:project/ignore-patterns/canton_log.ignore.txt:218:# GetConnectedSynchronizers call for one synchronizer that is mid-reconnect.
644b3ba97a:project/ignore-patterns/canton_log.ignore.txt:219:StateService/GetConnectedSynchronizers.*INTERNAL/Failed retrieving SynchronizerTopologyClient
f3adbc39e1 2026-09-16 [ci] Ignore INTERNAL from GetConnectedSynchronizers mid-reconnect, see cn-test-failures#9445 (#7334)
```

```
grep -a 'Failed retrieving SynchronizerTopologyClient' log/inbox-1111b/130559764394.log | sed -E 's/^[^Z]*Z //' | grep -a -o -E '^.{0,60}|.{0,45}$'
```
```
***"@timestamp":"2026-11-11T14:32:20.921Z","message":"Reques
*** (ignore this line in check-sbt-output.sh)
```

## 6. Why the scan app logs WARN/ERROR: no retry for INTERNAL in a quiet periodic trigger

DomainIngestionService is a PeriodicTaskTrigger (polls getConnectedDomains); the masked INTERNAL is classified non-retryable, so the task is skipped with an ERROR. The next poll is the retry.

```
git show 644b3ba97a:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/automation/DomainIngestionService.scala | sed -n '14,38p'
```
```
class DomainIngestionService(
    sink: SynchronizerStore.IngestionSink,
    connection: SpliceLedgerConnection,
    context: TriggerContext,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends PeriodicTaskTrigger(
      // We're effectively overriding the more global `pollingInterval` here
      context.config.domainIngestionPollingInterval,
      context.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop),
      quiet = true,
    ) {

  override protected def extraMetricLabels = Seq("party" -> sink.ingestionFilter.toString)

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      domainResults <-
        connection.getConnectedDomains(sink.ingestionFilter)
      optChangeSummary <- sink.ingestConnectedDomains(domainResults)
    } yield optChangeSummary match {
      case Some(changeSummary) => TaskSuccess(show"Ingested domain store changes $changeSummary")
      case None => TaskNoop
    }
```

```
zcat log/11364/logs-wall-clock-time-5/canton_network_test.clog.gz | grep -a -E '"@timestamp":"2026-11-11T14:32:2[0-1]' | grep -a 'DomainIngestionService:SvOnboardingAddlIntegrationTest/config=bdc19633/scan=sv2Scan' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/\2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
```
2026-11-11T14:32:20.926Z Checking whether the task is stale, as its processing failed with ",INFO
2026-11-11T14:32:20.926Z Task that failed with following exception is not stale: INTERNAL: An error occurred. Please contact the operator and inquire about the request <n
2026-11-11T14:32:20.928Z The operation 'processTaskWithRetry' failed with a non-retryable error, not retrying:\ncategory=None\nstatusCode=INTERNAL\ndescription=An error o
2026-11-11T14:32:20.929Z Detected an error.",INFO
2026-11-11T14:32:20.929Z The operation 'processTaskWithRetry' has failed with an exception. Maximum number of retries 0 exceeded. Giving up. ",INFO
2026-11-11T14:32:20.930Z Skipping processing of \nPeriodicTask(for = 2026-11-11T14:32:20.917747Z)\ndue to unexpected failure",ERROR
```

## 7. Duplicate search

The parent thread (9445): mechanism and the earlier hits, all on the participant line.

~~~
gh api repos/DACH-NY/cn-test-failures/issues/9445/comments --jq '.[] | "-- \(.user.login) \(.created_at)\n\(.body)"' | cut -c1-160 | head -30
~~~
~~~
-- ray-roestenburg-da 2026-07-31T13:24:20Z
`GetConnectedSynchronizers` fails the whole request with INTERNAL when one synchronizer is
mid-reconnect and its topology client is momentarily absent.

**Seen on main:**
- [run 30622772001](https://github.com/canton-network/splice/actions/runs/30622772001/job/91131149282)
  `wall-clock-time (0)`, 2026-07-31 ??? all 21 tests passed, `checkErrors` failed the job on one line
- [run 33370318165](https://github.com/canton-network/splice/actions/runs/33370318165/job/99420657393)
  `frontend-wall-clock-time (2)`, 2026-08-31 ??? same, one line

{"@timestamp":"2026-07-31T10:25:56.322Z","level":"ERROR",
 "logger_name":"c.d.c.l.a.ApiRequestLogger:participant=sv3Participant",
 "message":"Request com.daml.ledger.api.v2.StateService/GetConnectedSynchronizers by
   grpc:/127.0.0.1:44748: failed with INTERNAL/Failed retrieving SynchronizerTopologyClient for
   synchronizer global-domain::12208ef8fe76...::35-0 with alias Synchronizer 'global'"}

`SynchronizerRegistryHelpers`, (re)connecting:

```scala
_ = cryptoApiProvider.removeAndClose(psid)
_ = cryptoApiProvider.ips.add(topologyClient)
```

removeAndClose closes and removes the ips entry,
so between those two statements ips.forSynchronizer(psid) returns None.

A concurrent GetConnectedSynchronizers still sees the synchronizer in readySynchronizers and getSnapshot turns the missing client into a thrown exception.

-- ray-roestenburg-da 2026-09-15T15:54:41Z
@carlpulley-da could you also look at this? An Internal error was reported. 
~~~


```
for q in '"Failed retrieving SynchronizerTopologyClient"' 'GetConnectedSynchronizers' '"inquire about the request" "GetConnectedSynchronizers"'; do echo "== $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q" --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) assignees=\([.assignees[].login]|join(",")) \(.title[0:90])"'; done
```
```
== "Failed retrieving SynchronizerTopologyClient"
#9445 open/- 2026-07-31 assignees=carlpulley-da GetConnectedSynchronizers returns INTERNAL during a synchronizer reconnect
#9794 closed/duplicate 2026-08-31 assignees= GHA Run 33370318165 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
== GetConnectedSynchronizers
#9445 open/- 2026-07-31 assignees=carlpulley-da GetConnectedSynchronizers returns INTERNAL during a synchronizer reconnect
#10153 closed/duplicate 2026-09-16 assignees= GHA Run 35113435367 : job wall-clock-time (0) in scala_test Failed :fire:
#9794 closed/duplicate 2026-08-31 assignees= GHA Run 33370318165 : job frontend-wall-clock-time (2) in scala_test Failed :fire:
#9793 closed/completed 2026-08-31 assignees=ray-roestenburg-da Failed UnclaimedSvRewardsScriptIntegrationTest: had size 4 instead of expected size 3 sv1 
#6016 closed/not_planned 2025-10-21 assignees= SvFrontendIntegrationTest: aliceParticipant fails to get connected synchronizers
== "inquire about the request" "GetConnectedSynchronizers"
#6016 closed/not_planned 2025-10-21 assignees= SvFrontendIntegrationTest: aliceParticipant fails to get connected synchronizers
```

Bodies of the two hits that need a closer look: 10153 matched only through the commit title of #7334 and was closed as a duplicate of an off-boarding issue; 6016 is the same client-side shape on aliceValidator.

```
for r in 10153 6016; do gh api repos/DACH-NY/cn-test-failures/issues/$r --jq '"#\(.number) \(.state)/\(.state_reason)\n\(.body)"' | grep -a -E '^#|Commit:|GetConnectedSynchronizers' | cut -c1-240; gh api repos/DACH-NY/cn-test-failures/issues/$r/comments --jq '.[] | "-- \(.user.login): \(.body)"' | head -2 | cut -c1-160; done
```
```
#10153 closed/duplicate
Commit: [f3adbc3](https://github.com/canton-network/splice/commit/f3adbc39e1a4d2c78a9a5ff5fcff7b8a7ef6b4ec) [ci] Ignore INTERNAL from GetConnectedSynchronizers mid-reconnect, see cn-test-failures#9445 (#7334)
-- ray-roestenburg-da: DUPLICATE of 10094 (run 34477382133, canton 3.5.15) and same family as 10048 / 10137. This is the first
occurrence recorded on main with canton 3.6.
#6016 closed/not_planned
2025-10-21T09:26:48.954 [???] ERROR - o.l.s.a.a.c.ApiClientRequestLogger:SvFrontendIntegrationTest/config=170c1930/validator=aliceValidator (0168a2e17248f4e4562cf0ea02d253b6-DomainIngestionService--6eb1dc8d6b973d99) - Request (tid:d596d6c21
Commit: [ebd1cb7](https://github.com/hyperledger-labs/splice/commit/ebd1cb713e8cdbdd1d911677bd685ddbaf45b73b) Bump vulnerable HappyDOM version (#2673)
```

```
gh api repos/DACH-NY/cn-test-failures/issues/6016 --jq .body | grep -a -o -E '(ApiClientRequestLogger|DomainIngestionService):[^ ]*|GetConnectedSynchronizers to [^ ]+ failed with INTERNAL/An error occurred[^<]*|Skipping processing of'
```
```
ApiClientRequestLogger:SvFrontendIntegrationTest/config=170c1930/validator=aliceValidator
GetConnectedSynchronizers to 127.0.0.1:5501: failed with INTERNAL/An error occurred. Please contact the operator and inquire about the request 
DomainIngestionService:SvFrontendIntegrationTest/config=170c1930/validator=aliceValidator
Skipping processing of
```

Hits, one line each:
- 9445: parent; same participant line `Failed retrieving SynchronizerTopologyClient`, open, assigned carlpulley-da.
- 9794: closed duplicate; the second run listed in the 9445 comment (frontend-wall-clock-time (2), 2026-08-31).
- 10153: run on the #7334 commit, matched via its commit title; closed as duplicate of 10094 (off-boarding family); not this failure.
- 9793: UnclaimedSvRewardsScriptIntegrationTest size assertion; not this failure.
- 6016: same client-side shape (aliceValidator DomainIngestionService, GetConnectedSynchronizers INTERNAL masked as
  `An error occurred`), 2025-10-21, closed not planned.

## Verdict

- Duplicate of 9445 (Canton: GetConnectedSynchronizers fails the whole request with INTERNAL while a synchronizer
  is being disconnected). Trigger here: the sv2 validator ReconcileSequencerConnectionsTrigger reconnects sv2Participant
  when sv4 sequencer appears during SvOnboardingAddlIntegrationTest; any reconnect while a scan or validator polls
  can hit it.
- New shape: the client-side lines in canton_network_test.clog. splice#7334 ignored only the participant line
  (canton_log.ignore.txt:219), so the workaround does not cover this. 2 client-side hits in about 13 months (6016,
  11364): rare.
- Fix location: Canton (9445, carlpulley-da). If a splice-side mitigation is wanted until then, it belongs to the
  9445 owner decision: either an app-log ignore for the masked INTERNAL from GetConnectedSynchronizers (weak, the
  masked text carries no reason, so it would hide any INTERNAL on that call) or treating that INTERNAL as transient in
  the DomainIngestionService poll (the next poll is the retry). No change proposed here.
- Action: close 11364 as a duplicate of 9445. Optional note on 9445 that the ignore misses the client echo (the
  parent has an assignee, so only if the user wants the owner to see the new shape).
- Not verified: whether the next sv2Scan poll succeeded (quiet trigger, no success line at INFO).
- Lesson: none (Canton bug; ignore-pattern gap is specific to this workaround).
