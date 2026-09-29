# 10235 - wall-clock-time (9): sv1Participant's reconnect after sv1Validator re-registers the global synchronizer under a new sequencer alias never completes; every later SV init on the shared Canton times out, then a sanity-check teardown failure leaks the environment and 5 suites die on :25000 (run 36544106177)

NEW (Canton participant reconnect hang) plus a family H teardown-leak cascade. main e5b10c8d8f, the first hour on
Canton 3.6.0-snapshot.20260928.20326.0.v5616afeb (#7512, merged 08:31Z; this run's commit 08:39Z). In
Ans4SvsIntegrationTest's environment start, sv1Validator's init finds sv1Participant registered with the sequencer
alias `DefaultSequencer`, rewrites the config to alias `SEQ::sv1` (same endpoint localhost:5108), disconnects and
reconnects at 08:57:00.5. The reconnect stops after "Ensured ... persistent state" (08:57:01.684) and never reaches
the trust-certificate check; the participant stays disconnected until the log ends (09:23:36), every later connect
attempt queues behind it. Consequences: sv3's onboarding gets NOT_CONNECTED_TO_SYNCHRONIZER from its sponsor, sv1
cannot bootstrap in the next three fresh environments (5-minute init timeouts), and SvOnboardingConfigIntegrationTest's
teardown throws in UpdateHistorySanityCheckPlugin on a started-but-uninitialized sv2Scan, which skips
`environment.close()` and takes the Prometheus port from the last five suites.

- Run: https://github.com/canton-network/splice/actions/runs/36544106177, main e5b10c8d8f ("[ci] Bump @sigstore/verify
  from 3.1.0 to 3.1.1 in /cluster/pulumi"), job 109326655201 `ci / scala_test_wall_clock_time / wall-clock-time (9)`.
- Runtime canton: 3.6.0-snapshot.20260928.20326.0.v5616afeb (`nix/canton-sources.json` at e5b10c8d8f).
- Component: Canton participant (synchronizer reconnect), cascade through the test harness.
- `Tests: succeeded 5, failed 6`, `*** 4 SUITES ABORTED ***`.

## 1. Failures in order: 4 setup timeouts, 1 teardown failure, then the port cascade

```
zcat log/10235/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" \
  | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200 | sed -n '13,32p'
```
```
2026-09-29T08:56:19.257Z Starting test suite 'Ans4SvsIntegrationTest'...",
2026-09-29T08:56:19.260Z Starting 'Ans4SvsIntegrationTest/ans should terminated subscriptions are archived'...",
2026-09-29T09:02:10.062Z Test failed: 'Ans4SvsIntegrationTest/ans should terminated subscriptions are archived', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:220)
2026-09-29T09:02:10.078Z Starting test suite 'AutomationControlIntegrationTest'...",
2026-09-29T09:02:10.079Z Starting 'AutomationControlIntegrationTest/report correct rounds as round automation is controlled'...",
2026-09-29T09:07:33.796Z Test failed: 'AutomationControlIntegrationTest/report correct rounds as round automation is controlled', message: Creating environment failed at step Running setup (Environmen
2026-09-29T09:07:33.799Z Starting 'AutomationControlIntegrationTest/merge amulets as automation is controlled'...",
2026-09-29T09:12:57.240Z Test failed: 'AutomationControlIntegrationTest/merge amulets as automation is controlled', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:2
2026-09-29T09:12:57.253Z Starting test suite 'ExpiryWithIgnoredAmuletVersionIntegrationTest'...",
2026-09-29T09:12:57.253Z Starting 'ExpiryWithIgnoredAmuletVersionIntegrationTest/Expiry triggers skip parties whose preferred amulet package version is ignored'...",
2026-09-29T09:18:25.207Z Test failed: 'ExpiryWithIgnoredAmuletVersionIntegrationTest/Expiry triggers skip parties whose preferred amulet package version is ignored', message: Creating environment fail
2026-09-29T09:18:25.215Z Starting test suite 'SvOnboardingConfigIntegrationTest'...",
2026-09-29T09:18:25.215Z Starting 'SvOnboardingConfigIntegrationTest/start previously onboarded participant without onboarding config'...",
2026-09-29T09:23:29.308Z Test failed: 'SvOnboardingConfigIntegrationTest/start previously onboarded participant without onboarding config', message: The app state of sv2Scan is currently not accessibl
2026-09-29T09:23:29.311Z Starting 'SvOnboardingConfigIntegrationTest/An onboarded SV can initialize even if its onboarding sponsor is down'...",
2026-09-29T09:23:29.320Z Test failed: 'SvOnboardingConfigIntegrationTest/An onboarded SV can initialize even if its onboarding sponsor is down', message: Creating environment failed at step Creating f
2026-09-29T09:23:29.332Z Starting test suite 'TokenStandardV2TransferIntegrationTest'...",
2026-09-29T09:23:29.666Z Starting test suite 'WalletSweepToEndUserIntegrationTest'...",
2026-09-29T09:23:29.951Z Starting test suite 'RecoverExternalPartyIntegrationTest'...",
2026-09-29T09:23:30.169Z Starting test suite 'DowngradeSvPackagesIntegrationTest'...",
```

Which app each environment waited on:

```
zcat log/10235/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a -E 'Timeout while waiting for initialization of' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-09-29T09:01:52.458Z Timeout while waiting for initialization of sv3", [o.l.s.c.SvAppBackendReference:Ans4SvsIntegrationTest/config=bd90591e/app=sv3] ERROR
2026-09-29T09:07:23.735Z Timeout while waiting for initialization of sv1", [o.l.s.c.SvAppBackendReference:AutomationControlIntegrationTest/config=4df67cde/app=sv1] ERROR
2026-09-29T09:12:47.183Z Timeout while waiting for initialization of sv1", [o.l.s.c.SvAppBackendReference:AutomationControlIntegrationTest/config=5966f170/app=sv1] ERROR
2026-09-29T09:18:15.151Z Timeout while waiting for initialization of sv1", [o.l.s.c.SvAppBackendReference:ExpiryWithIgnoredAmuletVersionIntegrationTest/config=4d5dfa21/app=sv1] ERROR
2026-09-29T09:23:29.307Z Timeout while waiting for initialization of sv1Scan", [o.l.s.c.SvAppBackendReference:SvOnboardingConfigIntegrationTest/config=3f756743/app=sv1Scan] ERROR
```

sv1, the founding SV, failing to initialize in three fresh environments points at state shared across suites: the
Canton nodes started once for the shard.

## 2. sv3 (Ans4Svs) is refused by its sponsor: sv1's participant is not connected

```
F='s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /'
zcat log/10235/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a 'config=bd90591e/SV=sv3' | grep -a -E 'T08:5[7-9]:|T09:0[0-2]:' \
  | grep -a -E 'JoiningNodeInitializer|onboard|Onboard|WARN|ERROR' | grep -a -v -E 'Sql|upload dar' | sed -E "$F" \
  | sed -E 's/\[([a-z]\.)+([A-Za-z]+):[^]]*\]/[\2]/' | cut -c12-260 | awk '$1>"08:57:33.89"' | awk '{k=substr($0,14,55)} {c[k]++} c[k]<=2' | grep -a -E 'Starting onboarding|Requesting to be|NOT_CONNECTED|failed", ' | head -5
```
```
08:57:33.891Z The DSO party is not authorized to our participant. Starting onboarding with DSO party migration.", [JoiningNodeInitializer] INFO
08:57:33.948Z Requesting to be onboarded via the sponsor SV", [JoiningNodeInitializer] INFO
08:57:40.828Z HTTP client (POST /api/sv/v0/onboard/sv/start): Received response with entity data: {\n  \"error\" : \"NOT_CONNECTED_TO_SYNCHRONIZER(9,0): This participant is not connected to synchronizer global-domain::1220d672b9be6563bcc683f38aa094f
08:57:49.220Z HTTP client (POST /api/sv/v0/onboard/sv/start): Received response with entity data: {\n  \"error\" : \"NOT_CONNECTED_TO_SYNCHRONIZER(9,0): This participant is not connected to synchronizer global-domain::1220d672b9be6563bcc683f38aa094f
08:57:49.220Z Retrying on same error kind transient error (request infinite retries) for HttpCommandException/HTTP 409 Conflict POST at '/api/sv/v0/onboard/sv/start' on 127.0.0.1:5114. Command failed, message: NOT_CONNECTED_TO_SYNCHRONIZER(9,0): Thi
```

(`127.0.0.1:5114` is sv1's SV app; the retries continue with the same error until the 5-minute budget ends.)

## 3. What disconnected sv1Participant: sv1Validator's init rewrites the sequencer alias and reconnects

```
zcat log/10235/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a -E 'T08:5(6:59|7:0[0-1])' \
  | grep -a -E 'SynchronizerConnectivityService/(Modify|Reconnect|Disconnect|Connect)' | grep -a -E 'sending request|failed|received' \
  | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ [\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c12-230 | head -5
zcat log/10235/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a -F '77be2f974448f43d4b32942bde51b6da' | python3 -c '
import sys,json
for l in sys.stdin:
    j=json.loads(l); lg=j["logger_name"].split(":")[0].split(".")[-1]
    if j["@timestamp"]<"2026-09-29T08:57:00.141" or lg in ("ApiClientRequestLogger","HttpRequestLogger") or "HTTP client" in j["message"]: continue
    print(j["@timestamp"][11:23], j["level"], lg, "|", j["message"][:200].replace("\n"," "))' | head -8
```
```
08:57:00.147Z Request (tid:caba52654667fc54ac6cc6571358ebaa) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer to 127.0.0.1:5102: sending request ModifySynchronizerRequest(
08:57:00.509Z Request (tid:caba52654667fc54ac6cc6571358ebaa) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ModifySynchronizer to 127.0.0.1:5102: received a message ModifySynchronizerRespo
08:57:00.509Z Request (tid:790b4d4295fee7c009e8532b2f04c77f) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer to 127.0.0.1:5102: sending request DisconnectSynchronizer
08:57:00.524Z Request (tid:790b4d4295fee7c009e8532b2f04c77f) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/DisconnectSynchronizer to 127.0.0.1:5102: received a message DisconnectSynchroni
08:57:00.525Z Request (tid:994caa0d56259424314d4335a34c2b85) com.digitalasset.canton.admin.participant.v30.SynchronizerConnectivityService/ReconnectSynchronizer to 127.0.0.1:5102: sending request ReconnectSynchronizerRe
08:57:00.142 DEBUG SynchronizerConnector | For synchronizer Synchronizer 'global' at time 2026-09-29T08:57:00.136704Z using Vector(GrpcSequencerConnection(sequencerAlias = Sequencer 'SEQ::sv1::1220968d2ebc88a1cef031c1be953a560a787b4d8f7894857e
08:57:00.143 INFO SynchronizerConnector | Got 'non-empty sequencer connections from scan': SequencerConnections(   connections = Sequencer 'SEQ::sv1::1220968d2ebc88a1cef031c1be953a560a787b4d8f7894857ec4d24717b054ad5aff' -> GrpcSequencerConnec
08:57:00.143 INFO SynchronizerConnector | Ensuring domain Synchronizer 'global' registered with config SynchronizerConnectionConfig(   synchronizer = Synchronizer 'global',   physicalSynchronizerId = global-domain::1220d672b9be...::36-0,   se
08:57:00.143 INFO ParticipantAdminConnection | Ensuring that participant registered Synchronizer 'global' with config SynchronizerConnectionConfig(   synchronizer = Synchronizer 'global',   physicalSynchronizerId = global-domain::1220d672b9be...::
08:57:00.147 INFO ParticipantAdminConnection | Updating to new synchronizer connection config for synchronizer Synchronizer 'global'. Old config: RegisteredSynchronizer(SynchronizerConnectionConfig(   synchronizer = Synchronizer 'global',   sequen
08:57:00.509 INFO ParticipantAdminConnection | reconnect to the synchronizer Synchronizer 'global' for new sequencer configuration to take effect
08:59:00.524 INFO ParticipantAdminConnection | The operation 'participant is connected to Synchronizer 'global'' failed with a retryable error (full stack trace omitted): CANCELLED: RST_STREAM closed stream. HTTP/2 error code: CANCEL statusCode=CA
08:59:00.524 INFO ParticipantAdminConnection | Detected an error.
```

Whose request it was, and the same switch in the other direction earlier in this shard (TestTokenV2Settlement,
SEQ::sv1 -> DefaultSequencer), which reconnected in ~50 ms:

```
zcat log/10235/logs-wall-clock-time-9/canton_network_test.clog.gz | grep -a -F 'tid:caba52654667fc54ac6cc6571358ebaa' | head -1 \
  | python3 -c 'import sys,json; j=json.loads(sys.stdin.readline()); print(j["logger_name"], j.get("trace-id"))'
zcat log/10235/logs-wall-clock-time-9/canton.clog.gz | grep -a 'participant=sv1Participant' | grep -a -E 'T08:54:3[12]\.' \
  | grep -a -E "Disconnecting from Synchronizer|Trying to connect PAR|subscription-sequencer-connection-(DefaultSequencer|SEQ).*is now in state Ok|Replacing configuration" \
  | python3 -c '
import sys,json
for l in sys.stdin:
    j=json.loads(l); print(j["@timestamp"][11:23], j["message"][:110].replace("\n"," "))'
```
```
o.l.s.a.a.c.ApiClientRequestLogger:Ans4SvsIntegrationTest/config=bd90591e/validator=sv1Validator 77be2f974448f43d4b32942bde51b6da
08:54:31.761 Replacing configuration for (Synchronizer 'global', global-domain::1220d672b9be6563bcc683f38aa094fdefc3f2e3e6b
08:54:31.764 Disconnecting from Synchronizer 'global'
08:54:31.772 Trying to connect PAR::sv1::1220f20b8158... to Synchronizer 'global'
08:54:31.816 'subscription-sequencer-connection-DefaultSequencer-0' is now in state Ok(). Previous state was Failed().
08:54:31.822 Trying to connect PAR::sv1::1220f20b8158... to Synchronizer 'global'
```

## 4. The participant side: the connect stops after the persistent state, once and for all

The reconnect's own steps on sv1Participant (DEBUG), from "Trying to connect" to the last line it ever logs:

```
zcat log/10235/logs-wall-clock-time-9/canton.clog.gz | grep -a 'participant=sv1Participant' | grep -a -E 'T08:57:0[0-2]\.' \
  | grep -a -E 'SynchronizerConnectionsManager|GrpcSynchronizerRegistry|ConnectionValidationLimiter' | python3 -c '
import sys,json
for l in sys.stdin:
    j=json.loads(l); lg=j["logger_name"].split(":")[0].split(".")[-1]
    if j["@timestamp"]<"2026-09-29T08:57:00.524" or "close" in j["message"]: continue
    print(j["@timestamp"][11:23], j["level"][0], lg, "|", j["message"][:120].replace("\n"," "))' | head -12
```
```
08:57:00.524 I SynchronizerConnectionsManager | Disconnected from Synchronizer 'global'
08:57:00.781 D SynchronizerConnectionsManager | Trying to connect PAR::sv1::1220f20b8158... to Synchronizer 'global'
08:57:00.782 D SynchronizerConnectionsManager | About to connect to synchronizer: global
08:57:00.782 D SynchronizerConnectionsManager | Connecting to synchronizer with id global-domain::1220d672b9be6563bcc683f38aa094fdefc3f2e3e6b6f53add37c
08:57:00.793 D ConnectionValidationLimiter | Entering state Validating
08:57:00.793 D ConnectionValidationLimiter | Starting validation
08:57:00.893 D ConnectionValidationLimiter | Validation completed with Success(Outcome(()))
08:57:00.893 D ConnectionValidationLimiter | Entering state Idle
08:57:00.893 D GrpcSynchronizerRegistry | Connection pool initialized: Map(Sequencer 'SEQ::sv1::1220968d2ebc88a1cef031c1be953a560a787b4d8f7
08:57:00.894 D GrpcSynchronizerRegistry | Crypto handshake validated against crypto config
08:57:00.894 D GrpcSynchronizerRegistry | Synchronizer 'global' maps to global-domain::1220d672b9be...::36-0 after handshake
08:57:01.684 D GrpcSynchronizerRegistry | Ensured global-domain::1220d672b9be...::36-0 persistent state
```

In the successful 08:54:31 reconnect, the next lines are, 2 ms later:

```
zcat log/10235/logs-wall-clock-time-9/canton.clog.gz | grep -a 'participant=sv1Participant' | grep -a -E 'T08:54:31\.78' | python3 -c '
import sys,json
for l in sys.stdin:
    j=json.loads(l); lg=j["logger_name"].split(":")[0].split(".")[-1]
    print(j["@timestamp"][11:23], lg, "|", j["message"][:110].replace("\n"," "))' | grep -a -E 'persistent state|SynchronizerTrustCertificate|topology client with known' | head -3
```
```
08:54:31.782 GrpcSynchronizerRegistry | Ensured global-domain::1220d672b9be...::36-0 persistent state
08:54:31.784 DbTopologyStore | Querying transactions as of 9999-12-31T23:59:59.999999Z for types Set(SynchronizerTrustCertificate) with filters for uids sv1::1220f20b8158...; op Replace
08:54:31.785 StoreBasedSynchronizerTopologyClient | Updating the topology client with known timestamps from the topology store
```

After 08:57:01.684 the participant logs nothing from the connect path (only Ledger API/admin reads and store
inspections for the app's queries), and the old connection's shutdown was complete (90 of 90 `Attempting to
close` matched by `Successfully closed` between 08:57:00.4 and 08:57:02.0, same as 89/89 at 08:54:31). Every later
attempt stops even earlier, without "About to connect", i.e. queued behind the first:

```
zcat log/10235/logs-wall-clock-time-9/canton.clog.gz | grep -a 'participant=sv1Participant' \
  | grep -a -E 'Trying to connect PAR::sv1|Crypto handshake validated|persistent state|About to connect' | python3 -c '
import sys,json
for l in sys.stdin:
    j=json.loads(l)
    if j["@timestamp"]>="2026-09-29T08:56:50": print(j["@timestamp"][11:23], j["message"][:75].replace("\n"," "))' | sed -n '3,9p'
zcat log/10235/logs-wall-clock-time-9/canton.clog.gz | grep -a 'participant=sv1Participant' | grep -a -c 'Trying to connect PAR::sv1'
zcat log/10235/logs-wall-clock-time-9/canton.clog.gz | tail -1 | grep -oE '"@timestamp":"[^"]*"'
```
```
08:57:00.782 About to connect to synchronizer: global
08:57:00.894 Crypto handshake validated against crypto config
08:57:01.684 Ensured global-domain::1220d672b9be...::36-0 persistent state
08:59:00.625 Trying to connect PAR::sv1::1220f20b8158... to Synchronizer 'global'
09:01:00.806 Trying to connect PAR::sv1::1220f20b8158... to Synchronizer 'global'
09:02:24.805 Trying to connect PAR::sv1::1220f20b8158... to Synchronizer 'global'
09:04:24.907 Trying to connect PAR::sv1::1220f20b8158... to Synchronizer 'global'
```
(total `Trying to connect` lines in the run and the last log timestamp; 14 more attempts after 08:59, each followed by
`connect to Synchronizer 'global' has not completed after ~10 s`, to 09:22:38.)
```
28
"@timestamp":"2026-09-29T09:23:36.789Z"
```

## 5. Where it stops in Canton 3.6.0-snapshot.20260928.20326.0.v5616afeb (jar, not `canton/`)

```
J=/nix/store/kf2xqld0ima833ghw579msyz62c2ywmm-canton/lib/canton-open-source-3.6.0-snapshot.20260928.20326.0.v5616afeb.jar
unzip -o -q $J 'com/digitalasset/canton/participant/synchronizer/SynchronizerRegistryHelpers*' 'com/digitalasset/canton/participant/topology/ParticipantTopologyDispatcher*'
javap -p -c -l -cp . com.digitalasset.canton.participant.synchronizer.SynchronizerRegistryHelpers > helpers.javap
javap -p -c -l -cp . com.digitalasset.canton.participant.topology.ParticipantTopologyDispatcher > dispatcher.javap
```

`SynchronizerRegistryHelpers.getSynchronizerHandle` chains `$anonfun$getSynchronizerHandle$1`
(`SyncPersistentStateManager.lookupOrCreatePersistentState`), `$2`
(`SynchronizerRegistryHelpers$.copyTopologyStateFromLocalPredecessorIfNeeded`, a no-op without a predecessor), `$3`/`$4`
(`ParticipantTopologyDispatcher.trustSynchronizer`), then `TopologyComponentFactory.createTopologyClient` and
`SequencerClientFactory`. `trustSynchronizer`'s first store access is `alreadyTrustedInStore` on the authorized store,
which is the `SynchronizerTrustCertificate` query logged in the good reconnect. So the first attempt stops between
the persistent state and that query, and each later attempt blocks before "About to connect". The code path does not
log the wait; a thread dump of the participant would name it. Not established from the logs.

## 6. The tail is family H's teardown-leak cascade

SvOnboardingConfigIntegrationTest's first test fails in the sanity plugin's teardown on sv2Scan, the leaked
environment keeps logging, and every later environment fails to bind the Prometheus port:

```
T=log/10235/logs-wall-clock-time-9/canton_network_test.clog.gz
zcat $T | grep -a 'config=3f756743' | awk -F'"@timestamp":"' '{print substr($2,1,23)}' | awk '$0>"2026-09-29T09:23:29.4"' | sort | sed -n '1p;$p'
zcat $T | grep -a 'config=3f756743' | awk -F'"@timestamp":"' '{print substr($2,1,23)}' | awk '$0>"2026-09-29T09:23:29.4"' | wc -l
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10235/job.log | grep -a -E 'UpdateHistorySanityCheckPlugin.\$anonfun\$beforeEnvironmentDestroyed|java.net.BindException: Address already in use|Could not create Prometheus HTTP server' | sort | uniq -c | cut -c1-160
```
```
2026-09-29T09:23:29.445
2026-09-29T09:23:31.324
42
     10 [info]   Cause: java.io.UncheckedIOException: Could not create Prometheus HTTP server
     10 [info]   Cause: java.net.BindException: Address already in use
      2 [info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.$anonfun$beforeEnvironmentDestroyed$1(UpdateHistorySanity
      2 [info]   at org.lfdecentralizedtrust.splice.integration.plugins.UpdateHistorySanityCheckPlugin.$anonfun$beforeEnvironmentDestroyed$4(UpdateHistorySanity
```

Why sv2Scan passed the plugin's "initialized" filter: in splice the bootstrap's `isInitialized` only means a node
object exists; the console's `is_initialized` (vendored `canton/` copy, not checked against the jar) delegates to it.

```
git show origin/main:apps/common/src/main/scala/org/lfdecentralizedtrust/splice/environment/NodeBootstrapBase.scala | grep -n 'def isInitialized'
git show origin/main:canton/community/app-base/src/main/scala/com/digitalasset/canton/console/InstanceReference.scala | grep 'def is_initialized'
```
```
41:  def isInitialized: Boolean
153:  def isInitialized: Boolean = ref.get().isDefined
  def is_initialized: Boolean = nodes.getRunning(name).exists(_.isInitialized)
```

## Verdict

- NEW Canton-side hang: a participant reconnect after a sequencer-alias-only config change (`ModifySynchronizer` +
  disconnect + reconnect by `ParticipantAdminConnection` on behalf of sv1Validator's `SynchronizerConnector`) never
  completes on 3.6.0-snapshot.20260928.20326.0.v5616afeb. Not in the catalogue. Flake-shaped (the same switch in the
  other direction worked 2.5 min earlier in the same JVM), but it is the first hour on a new Canton pin: watch for it.
  Owner: Canton participant team; evidence in sections 4-5.
- Everything after is a consequence on the shared Canton: sv3 onboarding (NOT_CONNECTED_TO_SYNCHRONIZER), sv1 init
  timeouts in the next three environments.
- The last five suites are family H's teardown-leak cascade (10176 shape): SvOnboardingConfigIntegrationTest's
  `UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` filters scans by `is_initialized`, which in splice only
  means the node object exists (`NodeBootstrapBase.isInitialized = ref.get().isDefined`), so the started-but-never-
  initialized sv2Scan passes and `scan.automation` throws; `environment.close()` is skipped and :25000 stays bound.
  Confirming grep (family H): the leaked environment `config=3f756743` logs 42 lines after its failure at 09:23:29.308 (section 6).
- Fix (test-side, cascade only): branch `s11/fix-10235-sanity-check-skip-uninitialized-scans` (b63ea6493f, off
  origin/main 802b9faea0) filters by `is_initialized && Try(scan.appState).isSuccess`. 3 lines.
  Verified: `apps-app/Test/scalafmtCheck` passes; merges cleanly with `ray/fix-10176-sanity-check-pause-timeout`.
  NOT compiled or run. The Canton hang itself has no splice fix; the Canton-side EnvironmentSetup fix from 10176
  would also stop the cascade.
- Not verified: what the first connect waits on (needs a thread dump or Canton source at that snapshot); whether the
  hang is new in 20260928 (no other run on this pin checked); whether sv1Validator re-registering under a new alias
  on every 4-SV start is intended.
