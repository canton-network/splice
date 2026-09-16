# 10140 - SERVER_OVERLOADED on DownloadTopologyStateForInitHash during participant connect burst (run 35067363744)

Post-merge CI on main, sha 8c20340d0d "Fix commitment health alerting (#7322)", 2026-09-16T07:12Z.
Canton runtime 3.6.0-snapshot.20260910.20260.0.v90621933. One failed job. Commands verified against
the artifacts in log/10140/.

## Categorization

- Test(s) affected: none asserted-failed. All 11 tests in the shard passed. The WARN was emitted during
  the environment start of the first suite, TrafficBasedRewardsSvAppTimeBasedIntegrationTest.
- Failure type: checkErrors WARN (un-allowlisted line in canton-simtime_before_shutdown.clog).
- Component: splice test config (apps/app/src/test/resources/include/sequencers.conf
  `public-api.limits.active`, limit 3) + Canton client-side WARN level for a back-pressure error.
- Flake vs real: flake. Timing-dependent: 4 participants connected to globalSequencerSv1 within 8 ms
  and the 4th was rejected by the concurrency limit of 3; it retried after 1 s and succeeded.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35067363744 --repo canton-network/splice -n logs-simtime-1 -D dl
cd dl
# canton-simtime_before_shutdown.clog.gz = node log DURING the run; canton_network_test.clog.gz = harness
# job-104700886275.log = full GHA job console (gh api repos/canton-network/splice/actions/jobs/104700886275/logs)
```

## 1. Failed job

```
gh run view 35067363744 --repo canton-network/splice --json jobs \
  --jq '.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)"'
```
```
104700886275  ci / scala_test_sim_time / simtime (1)
```

## 2. checkErrors problem line as flagged (from the job console)

```
grep -anE 'lines with ignored|Found problems|lines with problems|\(checkErrors\) log|Process completed with exit code' \
  job-104700886275.log | cut -c1-140
sed -n '12362p' job-104700886275.log | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | cut -c1-400
```
```
12359:2026-09-16T07:37:03.2635611Z Total: 745 lines with ignored entries.
12361:2026-09-16T07:37:10.9217032Z Found problems in log/canton-simtime_before_shutdown.clog:
12363:2026-09-16T07:37:10.9226110Z Total: 1 lines with problems.
12385:2026-09-16T07:37:10.9240037Z [error] (checkErrors) log/canton-simtime_before_shutdown.clog contains problems.
12390:2026-09-16T07:37:10.9332619Z ##[error]Process completed with exit code 1.
2026-09-16T07:37:10.9221560Z ***"@timestamp":"2026-09-16T07:22:01.313Z","message":"Request failed for server-DefaultSequencer-0.\n  GrpcRequestRefusedByServer: ABORTED/SERVER_OVERLOADED(2,568e035c): Reached the limit of concurrent requests for com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash. Please try again later\n  Request: download-topology-state-for-
```

No test failed in the shard:

```
zcat canton_network_test.clog.gz | grep -a "Starting test suite" | grep -aoE "suite '[^']+'" | sort | uniq -c
zcat canton_network_test.clog.gz | grep -ac 'Test succeeded:'
zcat canton_network_test.clog.gz | grep -ac 'Test failed:'
```
```
      1 suite 'FeaturedAppRightSwitchOverTimeBasedIntegrationTest'
      1 suite 'ScanWithGradualStartsTimeBasedIntegrationTest'
      1 suite 'SvExpiredRewardsCollectionTimeBasedIntegrationTest'
      1 suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'
      1 suite 'UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest'
      1 suite 'WalletTxLogTimeBasedIntegrationTest'
      1 suite 'WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest'
11
0
```

## 3. Canton runtime version

```
zcat canton-simtime_before_shutdown.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
```
```
Canton version 3.6.0-snapshot.20260910.20260.0.v90621933
```

The vendored `canton/` tree in the repo is 3.5.7-SNAPSHOT (`cat canton/VERSION`) and is NOT what ran.
Canton source citations below are marked with that version and are only used for config/field names,
which the runtime log confirms (section 6).

## 4. The full WARN line: aliceParticipant -> globalSequencerSv1

```
zcat canton-simtime_before_shutdown.clog.gz | grep -a 'SERVER_OVERLOADED' | grep -a '"level":"WARN"' \
  | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | cut -c1-2500
```
```
{"@timestamp":"2026-09-16T07:22:01.313Z","message":"Request failed for server-DefaultSequencer-0.\n  GrpcRequestRefusedByServer: ABORTED/SERVER_OVERLOADED(2,568e035c): Reached the limit of concurrent requests for com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash. Please try again later\n  Request: download-topology-state-for-init-hash\n  DecodedCantonError(\n  code = 'SERVER_OVERLOADED',\n  category = ContentionOnSharedResources,\n  cause = \"Reached the limit of concurrent requests for com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash. Please try again later\",\n  traceId = '<HASH>',\n  context = Seq('methodName=>com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash', 'remoteAddr=>redacted-ip', 'sequencer=>globalSequencerSv1')\n)","logger_name":"c.d.c.s.c.p.GrpcConnection:participant=aliceParticipant/synchronizerAlias=Synchronizer 'global'/pool=main/connection=DefaultSequencer-0","thread_name":"canton-env-ec-81","level":"WARN","trace-id":"<HASH>","span-id":"<HASH>"}
```

Caller: aliceParticipant (logger `GrpcConnection:participant=aliceParticipant/.../connection=DefaultSequencer-0`).
Rejecting server: globalSequencerSv1 (`sequencer=>globalSequencerSv1` in the error context). Method:
`SequencerService/DownloadTopologyStateForInitHash` (the hash pre-check that precedes
`DownloadTopologyStateForInit`). Error category ContentionOnSharedResources, message "Please try again later".

## 5. Sequencer side: exactly one rejection in the whole run, logged at INFO

```
zcat canton-simtime_before_shutdown.clog.gz | grep -a 'ActiveRequestCounterInterceptor' | grep -ac 'SERVER_OVERLOADED'
zcat canton-simtime_before_shutdown.clog.gz | grep -a 'ActiveRequestCounterInterceptor' | grep -a 'SERVER_OVERLOADED' \
  | sed -E 's/[0-9a-f]{16,}/<HASH>/g' | cut -c1-900
```
```
1
{"@timestamp":"2026-09-16T07:22:01.263Z","message":"SERVER_OVERLOADED(2,8dc3d664): Reached the limit of concurrent requests for com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash. Please try again later","logger_name":"c.d.c.n.g.r.ActiveRequestCounterInterceptor:sequencer=globalSequencerSv1","thread_name":"canton-env-ec-52","level":"INFO","trace-id":"<HASH>","methodName":"com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash","location":"ActiveRequestCounterInterceptor.scala:151","span-id":"<HASH>","error-code":"SERVER_OVERLOADED(2,8dc3d664)","span-name":"ActiveRequestCounterInterceptor","remoteAddr":"/127.0.0.1:58870","sequencer":"globalSequencerSv1"}
```

The server logs the rejection at INFO; only the client-side GrpcConnection line is WARN, so only the
client line trips checkErrors.

## 6. The limit is 3, set by splice test config and confirmed by the runtime at node start

```
zcat canton-simtime_before_shutdown.clog.gz | grep -a 'ActiveRequestCounterInterceptor' \
  | grep -a 'Setting limit of com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInit' \
  | grep -aoE '"@timestamp":"[^"]*"|DownloadTopologyStateForInit[A-Za-z]* to Some\([0-9]+\)|"logger_name":"[^"]*"' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' \
  | sed -E 's/"logger_name":"c.d.c.n.g.r.ActiveRequestCounterInterceptor://' | sort | uniq -c
```
```
      1 "@timestamp":"2026-09-16T07:17:41.090Z" DownloadTopologyStateForInit to Some(3) sequencer=globalSequencerSv1"
      1 "@timestamp":"2026-09-16T07:17:41.090Z" DownloadTopologyStateForInit to Some(3) sequencer=globalSequencerSv2"
      1 "@timestamp":"2026-09-16T07:17:41.090Z" DownloadTopologyStateForInit to Some(3) sequencer=globalSequencerSv3"
      1 "@timestamp":"2026-09-16T07:17:41.090Z" DownloadTopologyStateForInit to Some(3) sequencer=globalSequencerSv4"
      1 "@timestamp":"2026-09-16T07:17:41.090Z" DownloadTopologyStateForInit to Some(3) sequencer=splitwellSequencer"
      1 "@timestamp":"2026-09-16T07:17:41.090Z" DownloadTopologyStateForInit to Some(3) sequencer=splitwellUpgradeSequencer"
      1 "@timestamp":"2026-09-16T07:17:41.091Z" DownloadTopologyStateForInitHash to Some(3) sequencer=globalSequencerSv1"
      1 "@timestamp":"2026-09-16T07:17:41.091Z" DownloadTopologyStateForInitHash to Some(3) sequencer=globalSequencerSv2"
      1 "@timestamp":"2026-09-16T07:17:41.091Z" DownloadTopologyStateForInitHash to Some(3) sequencer=globalSequencerSv4"
      1 "@timestamp":"2026-09-16T07:17:41.091Z" DownloadTopologyStateForInitHash to Some(3) sequencer=splitwellSequencer"
      1 "@timestamp":"2026-09-16T07:17:41.092Z" DownloadTopologyStateForInitHash to Some(3) sequencer=globalSequencerSv3"
      1 "@timestamp":"2026-09-16T07:17:41.092Z" DownloadTopologyStateForInitHash to Some(3) sequencer=splitwellUpgradeSequencer"
```

The knob lives in splice's own test sequencer template (identical at the failing sha):

```
cd <splice>; sed -n '68,72p' apps/app/src/test/resources/include/sequencers.conf
git show 8c20340d0d:apps/app/src/test/resources/include/sequencers.conf | grep -nE 'DownloadTopologyStateForInit|limits.active'
```
```
  public-api.limits.active = {
    "com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInit" : 3,
    "com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash" : 3,
    "com.digitalasset.canton.sequencer.api.v30.SequencerService/Subscribe" : 1000,
  }
68:  public-api.limits.active = {
69:    "com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInit" : 3,
70:    "com.digitalasset.canton.sequencer.api.v30.SequencerService/DownloadTopologyStateForInitHash" : 3,
71:    "com.digitalasset.canton.sequencer.api.v30.SequencerService/Subscribe" : 1000,
73:  admin-api.limits.active {
```

History of the knob (it is old, not a recent change):

```
git log --format='%h %ad %s' --date=short -S 'DownloadTopologyStateForInit' -- apps/app/src/test/resources/include/sequencers.conf
git log --format='%h %ad %s' --date=short -S 'limits.active' -- apps/app/src/test/resources/include/sequencers.conf | head -1
grep -rn 'limits.active' apps/app/src/pack cluster apps/*/src/main/resources 2>/dev/null | grep -v node_modules | head
```
```
2d72d56607 2025-10-31 Upgrade Canton to 3.4.1-snapshot.20251031.17304.0.v3b378764 (#3021)
7b6467b48e 2025-06-30 Upgrade Canton and enable onboarding snapshot rate limits (#1253)
d676aeb96d 2025-11-03 [ci] Upgrade canton to 3.4.2-snapshot.20251031.17312.0.vaad02726 (#3039)
(no matches: the limit is test-only, not in the packaged/helm configs)
```

`DownloadTopologyStateForInit: 3` was introduced in #1253 (2025-06-30, as `parameters.sequencer-api-limits`),
the `...Hash: 3` twin was added in #3021 (2025-10-31), and the key was renamed to `limits.active` in #3039.
Canton config shape (vendored 3.5.7-SNAPSHOT, `canton/community/base/src/main/scala/com/digitalasset/canton/config/ServerConfig.scala:31`):
`ActiveRequestLimitsConfig(active: Map[String, NonNegativeInt], warnOnUndefinedLimits, throttleLoggingRatePerSecond)`.
The enforcing class is `networking/grpc/ratelimiting/ActiveRequestCounterInterceptor.scala`; the runtime log
above shows the same class at `ActiveRequestCounterInterceptor.scala:151` in 3.6.0-snapshot.20260910, so the
mechanism is present in the version that ran.

## 7. The burst: 4 participants hit globalSequencerSv1's hash endpoint within 8 ms

Client side, first hash fetch per participant in the window:

```
zcat canton-simtime_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T07:2(1:5[0-9]|2:0[0-5])' \
  | grep -a 'Downloading topology state for initialization, fetching hash' \
  | grep -aoE '"@timestamp":"[^"]*"|participant=[A-Za-z0-9]+' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g'
```
```
"@timestamp":"2026-09-16T07:21:56.860Z" participant=sv2Participant
"@timestamp":"2026-09-16T07:21:58.623Z" participant=bobParticipant
"@timestamp":"2026-09-16T07:22:01.237Z" participant=sv4Participant
"@timestamp":"2026-09-16T07:22:01.240Z" participant=splitwellParticipant
"@timestamp":"2026-09-16T07:22:01.241Z" participant=sv3Participant
"@timestamp":"2026-09-16T07:22:01.245Z" participant=aliceParticipant
"@timestamp":"2026-09-16T07:22:01.796Z" participant=bobParticipant
"@timestamp":"2026-09-16T07:22:02.319Z" participant=aliceParticipant
```

Server side on globalSequencerSv1 (ApiRequestLogger, DEBUG): three hash requests are admitted at
01.248-01.250 and complete at 01.252-01.253; alice's request (sent 01.246) is rejected at 01.263 and never
appears in ApiRequestLogger because the interceptor closes the call before the service sees it.

```
zcat canton-simtime_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T07:22:0[1-2]' \
  | grep -a 'ApiRequestLogger:sequencer=globalSequencerSv1' | grep -a 'DownloadTopologyStateForInitHash' \
  | grep -aoE '"@timestamp":"[^"]*"|DownloadTopologyStateForInitHash by grpc:/127.0.0.1:[0-9]+: (received a message|sending response|succeeded|completed)' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g'
```
```
"@timestamp":"2026-09-16T07:22:01.248Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58742: received a message
"@timestamp":"2026-09-16T07:22:01.249Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58786: received a message
"@timestamp":"2026-09-16T07:22:01.250Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58868: received a message
"@timestamp":"2026-09-16T07:22:01.252Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58742: sending response
"@timestamp":"2026-09-16T07:22:01.252Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58786: sending response
"@timestamp":"2026-09-16T07:22:01.252Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58742: succeeded
"@timestamp":"2026-09-16T07:22:01.252Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58786: succeeded
"@timestamp":"2026-09-16T07:22:01.252Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58786: completed
"@timestamp":"2026-09-16T07:22:01.253Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58742: completed
"@timestamp":"2026-09-16T07:22:01.253Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58868: sending response
"@timestamp":"2026-09-16T07:22:01.253Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58868: succeeded
"@timestamp":"2026-09-16T07:22:01.253Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58868: completed
"@timestamp":"2026-09-16T07:22:02.329Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58870: received a message
"@timestamp":"2026-09-16T07:22:02.337Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58870: sending response
"@timestamp":"2026-09-16T07:22:02.337Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58870: succeeded
"@timestamp":"2026-09-16T07:22:02.338Z" DownloadTopologyStateForInitHash by grpc:/127.0.0.1:58870: completed
```

Which members the three admitted slots served:

```
zcat canton-simtime_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T07:2(1:5[0-9]|2:0[0-9])' \
  | grep -a 'sequencer=globalSequencerSv1' | grep -a 'Computing initial topology state hash' \
  | grep -aoE '"@timestamp":"[^"]*"|for PAR::[A-Za-z0-9]+' | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g'
```
```
"@timestamp":"2026-09-16T07:21:56.865Z" for PAR::sv2
"@timestamp":"2026-09-16T07:21:58.629Z" for PAR::bobValidator
"@timestamp":"2026-09-16T07:22:01.250Z" for PAR::sv4
"@timestamp":"2026-09-16T07:22:01.250Z" for PAR::sv3
"@timestamp":"2026-09-16T07:22:01.251Z" for PAR::splitwellValidator
"@timestamp":"2026-09-16T07:22:02.333Z" for PAR::aliceValidator
```

sv4, sv3 and splitwellValidator took the 3 slots; aliceValidator was the 4th concurrent caller and was
refused. The remote port of the rejected call (58870, section 5) is the same port alice's successful
retry used at 07:22:02.329, tying the rejection to alice.

This is the only second in the whole run with more than one hash fetch:

```
zcat canton-simtime_before_shutdown.clog.gz | grep -a 'Downloading topology state for initialization, fetching hash' \
  | grep -aoE '"@timestamp":"[^"]{19}' | cut -c15- | sort | uniq -c | sort -rn | head -3
```
```
      5 2026-09-16T07:22:01
      1 2026-09-16T07:23:19
      1 2026-09-16T07:23:15
```

## 8. Who triggered the burst: all apps of the first suite starting at once

```
zcat canton_network_test.clog.gz | grep -aE "Starting test suite|Test succeeded" \
  | grep -aE '"@timestamp":"2026-09-16T07:2[0-6]' | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,150}' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | cut -c1-220
```
```
"@timestamp":"2026-09-16T07:20:16.714Z" "message":"Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
"@timestamp":"2026-09-16T07:26:33.727Z" "message":"Test succeeded: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure'
"@timestamp":"2026-09-16T07:26:33.765Z" "message":"Starting test suite 'WalletTxLogTimeBasedIntegrationTest'...
```

The harness started every SV and validator app together at 07:20:21 ("Starting all nodes"), and each
app connected its participant to the global synchronizer as its own onboarding reached that step:

```
zcat canton_network_test.clog.gz | grep -aE '"@timestamp":"2026-09-16T07:2(1:5[6-9]|2:0[0-2])' \
  | grep -aE 'SynchronizerConnectivityService/(RegisterSynchronizer|ReconnectSynchronizers|ConnectSynchronizer)' \
  | grep -av 'succeeded\|completed\|response' \
  | grep -aoE '"@timestamp":"[^"]*"|SynchronizerConnectivityService/[A-Za-z]+|config=[0-9a-f]+/[A-Za-z]+=[A-Za-z0-9]+' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | awk '!seen[$2 $3]++' | cut -c1-200
```
```
"@timestamp":"2026-09-16T07:21:56.282Z" SynchronizerConnectivityService/ReconnectSynchronizers config=90f99837/SV=sv2
"@timestamp":"2026-09-16T07:21:56.690Z" SynchronizerConnectivityService/RegisterSynchronizer config=90f99837/validator=bobValidator
"@timestamp":"2026-09-16T07:21:57.194Z" SynchronizerConnectivityService/ReconnectSynchronizers config=90f99837/SV=sv4
"@timestamp":"2026-09-16T07:21:57.391Z" SynchronizerConnectivityService/ReconnectSynchronizers config=90f99837/SV=sv3
"@timestamp":"2026-09-16T07:21:58.225Z" SynchronizerConnectivityService/RegisterSynchronizer config=90f99837/validator=aliceValidator
"@timestamp":"2026-09-16T07:21:58.437Z" SynchronizerConnectivityService/RegisterSynchronizer config=90f99837/validator=splitwellValidator
```

sv4, sv3, alice and splitwell all issued their connect within 07:21:57.2-07:21:58.4; their participants
reached the sequencer's hash endpoint ~3-4 s later, all within 07:22:01.237-01.245. All of them use
sv1's sequencer (`DefaultSequencer-0` = globalSequencerSv1) as their only initial connection.

## 9. The caller retried once and succeeded after 1.1 s

```
zcat canton-simtime_before_shutdown.clog.gz | grep -aE '"@timestamp":"2026-09-16T07:22:0[1-3]' \
  | grep -a 'participant=aliceParticipant' \
  | grep -aE 'Sending request download|Request failed for server|Retry has not been configured|Cannot reach threshold|Get hash for init topology state|has succeeded for server|Successfully downloaded' \
  | sed -E 's/[0-9a-f]{16,}/<HASH>/g' \
  | grep -aoE '"@timestamp":"[^"]*"|"message":"[^"]{0,110}|"logger_name":"c\.d\.c\.[a-z.]+\.[A-Za-z$]+|"level":"[A-Z]+"' \
  | paste -sd' ' | sed 's/"@timestamp"/\n"@timestamp"/g' | cut -c1-300
```
```
"@timestamp":"2026-09-16T07:22:01.246Z" "message":"Sending request download-topology-state-for-init-hash to server-DefaultSequencer-0. "logger_name":"c.d.c.s.c.p.GrpcConnection "level":"DEBUG"
"@timestamp":"2026-09-16T07:22:01.313Z" "message":"Request failed for server-DefaultSequencer-0.\n  GrpcRequestRefusedByServer: ABORTED/SERVER_OVERLOADED(2,568e0 "logger_name":"c.d.c.s.c.p.GrpcConnection "level":"WARN"
"@timestamp":"2026-09-16T07:22:01.314Z" "message":"Retry has not been configured for GrpcRequestRefusedByServer, giving up. "logger_name":"c.d.c.s.c.p.GrpcConnection "level":"DEBUG"
"@timestamp":"2026-09-16T07:22:01.315Z" "message":"Cannot reach threshold for init-topology-state-hash. Threshold = 1, failed results: TrieMap(SEQ::sv1::1220e656 "logger_name":"c.d.c.s.c.RichSequencerClientImpl "level":"INFO"
"@timestamp":"2026-09-16T07:22:01.316Z" "message":"The operation 'Get hash for init topology state' was not successful. New kind of error: no success error (requ "logger_name":"c.d.c.s.c.BftTopologyForInitDownloader$ "level":"INFO"
"@timestamp":"2026-09-16T07:22:02.319Z" "message":"Now retrying operation 'Get hash for init topology state'.  "logger_name":"c.d.c.s.c.BftTopologyForInitDownloader$ "level":"INFO"
"@timestamp":"2026-09-16T07:22:02.321Z" "message":"Sending request download-topology-state-for-init-hash to server-DefaultSequencer-0. "logger_name":"c.d.c.s.c.p.GrpcConnection "level":"DEBUG"
"@timestamp":"2026-09-16T07:22:02.341Z" "message":"Request download-topology-state-for-init-hash has succeeded for server-DefaultSequencer-0. "logger_name":"c.d.c.s.c.p.GrpcConnection "level":"DEBUG"
"@timestamp":"2026-09-16T07:22:02.418Z" "message":"Successfully downloaded topology state of 37 for init with hash matching expected SHA-256:801c70b8a287... "logger_name":"c.d.c.s.c.BftTopologyForInitDownloader$ "level":"INFO"
```

The low-level GrpcConnection has no retry for GrpcRequestRefusedByServer and logs WARN, but the
BftTopologyForInitDownloader above it retries with infinite retries after 1 s; the retry succeeded and
alice finished its topology init at 07:22:02.418, 1.17 s after the first attempt. No functional impact.

## 10. No ignore pattern covers it; precedent exists for a sibling overload code

```
cd <splice>
grep -rnE 'SERVER_OVERLOADED|limit of concurrent requests|GrpcRequestRefusedByServer|DownloadTopologyStateForInit' \
  project/ignore-patterns/canton_log.ignore.txt project/ignore-patterns/canton_log_simtime_extra.ignore.txt | cut -c1-200
git log --oneline -S 'SERVER_OVERLOADED' -- . ':!canton' | head
sed -n '62,63p' project/ignore-patterns/canton_log.ignore.txt
```
```
project/ignore-patterns/canton_log.ignore.txt:183:GrpcRequestRefusedByServer: FAILED_PRECONDITION/SEQUENCER_SUBMISSION_AFTER_UPGRADE_TIME
(git log: no commit outside canton/ has ever mentioned SERVER_OVERLOADED)
# Shows up during global domain migration test, when the domain is frozen
ABORTED/SEQUENCER_OVERLOADED.*Submission rate exceeds rate limit of 0/s
```

The only `GrpcRequestRefusedByServer` ignore is for the LSU-specific `SEQUENCER_SUBMISSION_AFTER_UPGRADE_TIME`
and does not match `ABORTED/SERVER_OVERLOADED`. checkErrors applies `canton_log` + `canton_log_simtime_extra`
to `log/canton-simtime_before_shutdown.clog` (build.sbt `splitAndCheckCantonLogFile("canton-simtime", usesSimtime = true)`,
lines ~2281-2310). `SERVER_OVERLOADED` (the gRPC active-request limiter) has never been dealt with in
splice; the related `SEQUENCER_OVERLOADED` (submission rate limiter) already has an ignore at line 63.

## 11. Not a regression from recent commits

```
git log --oneline -15 8c20340d0d | head -5
git log --oneline -3 8c20340d0d -- nix/canton-sources.json
git log --oneline d6e5120026..8c20340d0d | wc -l
git log --oneline -15 --name-only 8c20340d0d -- apps/app/src/test/resources/include/sequencers.conf
```
```
8c20340d0d Fix commitment health alerting (#7322)
b5d5645c56 Add traffic cost test for preapproved cross participants transfers (#7286)
fda19e6e49 feat(helm): make splice-validator resource names configurable (#7279)
b27ab3929d Switch istio chart repo (#7310)
2ddd01a05c fix copy-canton.sh exclusions; remove and exclude many unused canton/ subtrees (#7297)
d6e5120026 Upgrade Canton to 3.6.0-snapshot.20260910.20260.0.v90621933 (#7264)
344612f556 Upgrade Canton to 3.6.0-snapshot.20260909.20251.0.v0a9e6e25 (#7219)
4a3124ed50 Upgrade Canton to 3.6.0-snapshot.20260909.20247.0.v207c27f6 (#7210)
27
(no commit in the last 15 touched sequencers.conf)
```

None of the 15 commits leading to 8c20340d0d touch the sequencer test config or the test topology. The
Canton runtime (20260910 snapshot, #7264) is 27 commits old on main and has run many green simtime shards
since. The limit of 3 has been in place since 2025-06/2025-10.

## Root cause / hypothesis

Proven (from logs):
- The single flagged line is aliceParticipant's GrpcConnection WARN at 07:22:01.313 for
  `ABORTED/SERVER_OVERLOADED` from globalSequencerSv1 on `SequencerService/DownloadTopologyStateForInitHash`.
- globalSequencerSv1 enforces `limits.active = 3` for that method (runtime "Setting limit ... to Some(3)"),
  configured in `apps/app/src/test/resources/include/sequencers.conf:70`.
- sv4, sv3 and splitwellValidator held the 3 slots at 07:22:01.248-01.253; alice's request (sent 01.246)
  was the 4th and was refused at 01.263. It is the only rejection in the run and the only second with
  more than one hash fetch.
- The BftTopologyForInitDownloader retried after 1 s and succeeded; alice completed topology init at
  07:22:02.418. All 11 tests in the shard passed.
- No ignore pattern matches `SERVER_OVERLOADED`; nothing in splice has ever handled this code.

Inferred:
- The burst is timing luck of the standard simtime env start: 7 apps start together at 07:20:21 and 4
  of their participants happened to reach the sequencer within 8 ms. With 7 participants and a limit of
  3 on a single default sequencer this will recur at a low rate.
- The rejection is correct back-pressure behaviour ("Please try again later", category
  ContentionOnSharedResources) and the higher layer handles it; the WARN level at GrpcConnection is what
  turns an expected, handled condition into a CI failure. That level is Canton's choice, not splice's.

## Duplicates / related

- No earlier ci-triage packet or cn-test-failures ref known for `SERVER_OVERLOADED` /
  `DownloadTopologyStateForInitHash`; `git log -S SERVER_OVERLOADED` outside `canton/` is empty.
- Sibling: `ABORTED/SEQUENCER_OVERLOADED.*Submission rate exceeds rate limit of 0/s` already ignored
  (canton_log.ignore.txt:63) - same "overload is expected here" reasoning, different limiter.
- Same failure mode (all tests pass, checkErrors flags one Canton WARN): 10094 (ack stall), 10121,
  10084 (indexer reconnect WARN).

## Suggested next step / owner

Two options, both splice-side and small; pick one:

1. Raise `public-api.limits.active` for `DownloadTopologyStateForInit` and `DownloadTopologyStateForInitHash`
   in `apps/app/src/test/resources/include/sequencers.conf` from 3 to at least the number of participants in
   the standard topology (7). The limit is test-only (not in pack/helm) and was introduced to exercise
   the limiter (#1253); check with the author (Moritz Kiefer) whether a test still depends on the value 3
   before raising it.
2. Add to `project/ignore-patterns/canton_log.ignore.txt`, next to the SEQUENCER_OVERLOADED entry:
   `GrpcRequestRefusedByServer: ABORTED/SERVER_OVERLOADED.*Reached the limit of concurrent requests for .*DownloadTopologyStateForInit`
   (scoped to the topology-init endpoints so a real overload elsewhere still fails the build).

Upstream (Canton, optional): GrpcConnection logs `GrpcRequestRefusedByServer` at WARN even when the error
category is ContentionOnSharedResources and the caller (BftTopologyForInitDownloader) retries indefinitely;
INFO would be the fitting level for a handled back-pressure signal. Cite against
3.6.0-snapshot.20260910.20260.0.v90621933 when filing.

Owner: splice CI / test infra (option 1 or 2); Canton sequencer client team for the log level.

## Summary

simtime(1), job 104700886275, canton 3.6.0-snapshot.20260910.20260.0.v90621933. All 11 tests passed; the
job fails because checkErrors flags one WARN from aliceParticipant: globalSequencerSv1 refused its
`DownloadTopologyStateForInitHash` with `ABORTED/SERVER_OVERLOADED` because sv4, sv3 and splitwell were
already holding the 3 slots allowed by splice's test config (`limits.active = 3`, in place since 2025).
Alice retried after 1 s and succeeded (topology init done 1.17 s later). Timing flake of the standard
env start, not a regression; fix by raising the test-only limit or allowlisting this scoped WARN.
