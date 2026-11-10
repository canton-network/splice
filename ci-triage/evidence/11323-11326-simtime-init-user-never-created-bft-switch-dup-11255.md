# 11323, 11326 - simtime setup: app exits on `Wait for user failed` (PERMISSION_DENIED = UserNotFound) after a topology transaction is lost at the 1 to 4 BFT switch (runs 42759795825, 42780003623)

DUPLICATE of 11255 (family B), "sends rejected or batches never ordered at the 1 to 4 switch" shape, the part splice#8356 does not
address. Both shards have no test report: during setup of the first suite an app waits for its ledger API user, gets 120
times `PERMISSION_DENIED` (the participant's answer for `UserNotFound`), gives up after 7 min 15 s and exits the JVM
(family H evidence loss). The user is never created because its creator waits for a topology transaction that was submitted
in the half second around the switch of the global ordering topology from 1 to 4 sequencers and never reached the
synchronizer: rejected (`P2P connectivity is not ready`) or put into a batch that was discarded or never disseminated.
The senders ignore the error as possibly sequenced and (per the 11255 analysis) wait for max sequencing time 00:00:20, which the sim clock
(about 00:00:02) never reaches.
- 11323 (SvTimeBasedRoundMgmtIntegrationTest, scheduled workflow "Integration tests against mainnet Daml version"): the
  MediatorSynchronizerState proposal adding MED::sv2 (threshold 2) is lost (3 sends rejected, sv2's own send in a batch whose
  dissemination was dropped), so the sv2 SV app waits forever for traffic for MED::sv2, never runs `Initialize validator`,
  and sv2Validator never gets `sv2_validator_user`.
- 11326 (TrafficBasedRewardsSvAppTimeBasedIntegrationTest): splitwellValidator's VettedPackages transaction for the
  splitwell DAR on the global synchronizer is accepted 0.3 s before the switch, then its batch proposal is discarded
  (`received message after closing`) and its dissemination dropped, so `Set up app instance splitwell` never
  reaches the creation of `splitwell_provider`, and providerSplitwellBackend gives up. New consumer of the same shape
  (validator DAR vetting instead of SV mediator onboarding).

- Runs: https://github.com/canton-network/splice/actions/runs/42759795825 (main 885cd84668, "Upgrade Canton to
  3.7.3-snapshot.20261109.20894.0.ve6e6c252 (#8381)", schedule), job 129643413966 `simtime (4)`;
  https://github.com/canton-network/splice/actions/runs/42780003623 (main e5741cdd9f, "Remove CometBFT backup/restore
  support from node backup scripts (#8392)"), job 129707831287 `simtime (0)`.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (both).
- Component: Canton BFT orderer during sequencer onboarding, under simulated time (test environment).

## 1. Job, canton pin, classification

Job ids (recipe 1; one failed job each, attempt 1):
```
for p in "42759795825 simtime (4)" "42780003623 simtime (0)"; do set -- $p; export NAME="${p#* }"; gh api --paginate "repos/canton-network/splice/actions/runs/$1/jobs?filter=all&per_page=100" --jq ".jobs[] | select((.name == env.NAME or (.name | endswith(\" / \" + env.NAME))) and .conclusion == \"failure\") | \"$1 \(.id) attempt=\(.run_attempt) \(.name)\""; done
```
```
42759795825 129643413966 attempt=1 build / scala_test_sim_time / simtime (4)
42780003623 129707831287 attempt=1 ci / scala_test_sim_time / simtime (0)
```

Runtime Canton at both shas:
```
for s in 885cd84668 e5741cdd9f; do echo "$s $(git show $s:nix/canton-sources.json | grep -m1 "\"version\"")"; done
```
```
885cd84668   "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
e5741cdd9f   "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

Neither job log has a test summary; the JVM exits during app initialization (job logs fetched with `gh api repos/canton-network/splice/actions/jobs/<job id>/logs > log/<ref>/job.log`):
```
for n in 11323 11326; do echo "== $n"; sed -E "s/\x1b\[[0-9;]*m//g" log/$n/job.log | grep -a -c -E "Tests: succeeded|Run completed"; sed -E "s/\x1b\[[0-9;]*m//g" log/$n/job.log | grep -a -E "Initialization failed, so exiting|Wait for user failed|PERMISSION_DENIED|##\[error\]Process completed" | cut -c30-250; done
```
```
== 11323
0
sv2Validator app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv2Validator app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request a62659d4f00e0527c4523bc19efa1e42 with tid a62659d4f00e0527c4523bc19efa1e42
##[error]Process completed with exit code 1.
== 11326
0
providerSplitwellBackend app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: providerSplitwellBackend app initialization: Wait for user failed
Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 4d0e781fdf3a00cc123386bd1d6fcebe with tid 4d0e781fdf3a00cc123386bd1d6fcebe
##[error]Process completed with exit code 1.
```

## 2. Suite and the waiting app

Each shard ran its first suite; the test log is `canton_network_test.clog.gz` from the artifact (`gh run download <run> --repo canton-network/splice -n logs-simtime-<n> -D log/<ref>/logs-simtime-<n>`):
```
for r in "11323 logs-simtime-4" "11326 logs-simtime-0"; do set -- $r; echo "== $1"; zcat log/$1/$2/canton_network_test.clog.gz | jq -r "select(.message|test(\"^Starting test suite\")) | \"\(.[\"@timestamp\"][11:23]) \(.message)\""; done
```
```
== 11323
05:35:05.536 Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...
== 11326
08:19:36.138 Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
```

The app waits for its ledger API user from start to give-up (120 retries, about 7 min 15 s):
```
for r in "11323 logs-simtime-4 sv2Validator sv2_validator_user" "11326 logs-simtime-0 providerSplitwellBackend splitwell_provider"; do set -- $r; echo "== $1"; zcat log/$1/$2/canton_network_test.clog.gz | jq -r "select(.message|test(\"^$3 app initialization: Wait for user (started|failed)|Get user $4.*Giving up\")) | \"\(.[\"@timestamp\"][11:23]) \(.level) \(.logger_name|split(\"/\")|last) | \(.message|.[0:170])\""; done
```
```
== 11323
05:35:17.354 DEBUG validator=sv2Validator | sv2Validator app initialization: Wait for user started
05:42:32.152 INFO validator=sv2Validator | The operation 'Get user sv2_validator_user-b520bd32' has failed with an exception. Total maximum number of retries 120 exceeded. Giving up. 
05:42:32.153 INFO validator=sv2Validator | sv2Validator app initialization: Wait for user failed
== 11326
08:19:53.063 DEBUG splitwell=providerSplitwellBackend | providerSplitwellBackend app initialization: Wait for user started
08:27:30.952 INFO splitwell=providerSplitwellBackend | The operation 'Get user splitwell_provider-da0212c4' has failed with an exception. Total maximum number of retries 120 exceeded. Giving up. 
08:27:30.953 INFO splitwell=providerSplitwellBackend | providerSplitwellBackend app initialization: Wait for user failed
```

The PERMISSION_DENIED is how the participant reports a missing user: the AuthInterceptor WARN for the request id in the exit stack says `UserNotFound`. It is the same answer for all 121 GetUser calls of each wait (first and last shown, then the count):
```
for r in "11323 logs-simtime-4 sv2_validator_user" "11326 logs-simtime-0 splitwell_provider"; do set -- $r; echo "== $1"; zcat log/$1/$2/canton-simtime.clog.gz | grep -a "UserNotFound($3" | jq -r "\"\(.[\"@timestamp\"][11:23]) \(.level) \(.logger_name) | \(.message)\"" | sed -n "1p;\$p"; echo "count: $(zcat log/$1/$2/canton-simtime.clog.gz | grep -a -c "UserNotFound($3")"; done
```
```
== 11323
05:35:17.364 WARN c.d.c.a.AuthInterceptor:participant=sv2Participant | PERMISSION_DENIED(7,54c15006): Could not resolve is_deactivated status for user 'sv2_validator_user-b520bd32' and identity_provider_id 'Default' due to 'UserNotFound(sv2_validator_user-b520bd32)'
05:42:32.151 WARN c.d.c.a.AuthInterceptor:participant=sv2Participant | PERMISSION_DENIED(7,a62659d4): Could not resolve is_deactivated status for user 'sv2_validator_user-b520bd32' and identity_provider_id 'Default' due to 'UserNotFound(sv2_validator_user-b520bd32)'
count: 121
== 11326
08:19:53.074 WARN c.d.c.a.AuthInterceptor:participant=splitwellParticipant | PERMISSION_DENIED(7,b595c3f2): Could not resolve is_deactivated status for user 'splitwell_provider-da0212c4' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-da0212c4)'
08:27:30.951 WARN c.d.c.a.AuthInterceptor:participant=splitwellParticipant | PERMISSION_DENIED(7,4d0e781f): Could not resolve is_deactivated status for user 'splitwell_provider-da0212c4' and identity_provider_id 'Default' due to 'UserNotFound(splitwell_provider-da0212c4)'
count: 121
```

## 3. Who creates the missing user, and where that creator is stuck

11323: the joining SV app creates `sv2_validator_user` in `Initialize validator`, after joining the DSO (885cd84668):
```
git show 885cd84668:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/SvApp.scala | sed -n "353p;358p"
```
```
        // We create the validator user only after the DSO party migration and DAR uploads have completed. This avoids two issues:
        appInitStep("Initialize validator") {
```

11323: sv3 and sv4 finished joining and `Initialize validator` at 05:38:33-34; sv2 never did:
```
zcat log/11323/logs-simtime-4/canton_network_test.clog.gz | jq -r "select(.message|test(\"^sv[1-4] app initialization: (JoiningNodeInitializer joining Dso with key|SV1Initializer bootstrapping Dso|Initialize validator) (started|finished)\")) | \"\(.[\"@timestamp\"][11:23]) | \(.message|.[0:110])\""
```
```
05:35:48.853 | sv4 app initialization: JoiningNodeInitializer joining Dso with key started
05:35:48.853 | sv2 app initialization: JoiningNodeInitializer joining Dso with key started
05:35:48.853 | sv3 app initialization: JoiningNodeInitializer joining Dso with key started
05:35:48.854 | sv1 app initialization: SV1Initializer bootstrapping Dso started
05:36:22.173 | sv1 app initialization: SV1Initializer bootstrapping Dso finished after PT33.319674334S
05:36:22.202 | sv1 app initialization: Initialize validator started
05:36:23.270 | sv1 app initialization: Initialize validator finished after PT1.067982468S
05:38:33.547 | sv4 app initialization: JoiningNodeInitializer joining Dso with key finished after PT2M44.693587953S
05:38:33.558 | sv3 app initialization: JoiningNodeInitializer joining Dso with key finished after PT2M44.704432703S
05:38:33.565 | sv4 app initialization: Initialize validator started
05:38:33.576 | sv3 app initialization: Initialize validator started
05:38:34.661 | sv4 app initialization: Initialize validator finished after PT1.096224919S
05:38:34.869 | sv3 app initialization: Initialize validator finished after PT1.292998752S
```

11323: the sv2 SV app join is stuck in mediator onboarding from 05:37:30 to the end (first, second and last line of the wait, then its line count):
```
zcat log/11323/logs-simtime-4/canton_network_test.clog.gz | jq -r "select((.logger_name|test(\"SV=sv2\")) and (.message|test(\"Mediator has been granted unlimited traffic\"))) | \"\(.[\"@timestamp\"][11:23]) \(.message|.[0:200])\"" | awk "NR<=2{print} {l=\$0;c++} END{print l; print c\" lines\"}"
```
```
05:37:30.580 Waiting until Mediator has been granted unlimited traffic
05:37:30.584 The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable error (full stack trace omitted): NOT_FOUND: No traffic state found for member MED::sv2::1220d896173c.
05:42:29.275 The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an exception. Retrying after a number of 85 failures, and after 3722 milliseconds. 
258 lines
```

11326: the splitwell provider user is the service user of the splitwell app instance; the validator app creates it in `Set up app instance splitwell`, after uploading and vetting the instance DARs on all connected synchronizers (e5741cdd9f):
```
git show e5741cdd9f:apps/validator/src/main/scala/org/lfdecentralizedtrust/splice/validator/ValidatorApp.scala | sed -n "415,428p"
```
```
    logger.info(s"Attempting to setup app $name...")
    for {
      _ <- MonadUtil.sequentialTraverse_(instance.dars)(dar =>
        participantAdminConnection.uploadDarFileWithVettingOnAllConnectedSynchronizers(
          dar,
          RetryFor.WaitingOnInitDependency,
        )
      )
      party <- storeWithIngestion
        .connection(SpliceLedgerConnectionPriority.Medium)
        .getOrAllocateParty(
          instance.serviceUser,
          Seq(new User.Right.CanReadAs(validatorParty.toProtoPrimitive)),
          participantAdminConnection,
```

11326: splitwellValidator entered that step at 08:23:15 and never finished it; every other app finished init by 08:23:48 (last init line per app):
```
zcat log/11326/logs-simtime-0/canton_network_test.clog.gz | jq -r "select(.message|test(\"app initialization: .*(started|finished|failed)|Initialization failed\")) | select(.message|test(\"create ledger client\")|not) | \"\(.[\"@timestamp\"][11:23]) \(.level) \(.logger_name|split(\"/\")|last|.[0:30]) | \(.message|.[0:140])\"" | awk "{last[\$3]=\$0} END{for(k in last) print last[k]}" | sort
```
```
08:21:30.645 INFO scan=sv1Scan | sv1Scan app initialization: Initialize app finished after PT1M25.511119634S
08:21:31.053 INFO SV=sv1 | sv1 app initialization: Initialize app finished after PT1M31.939372105S
08:22:09.490 INFO validator=sv1Validator | sv1Validator app initialization: Initialize app finished after PT2M23.997367826S
08:22:55.086 INFO validator=aliceValidator | aliceValidator app initialization: Initialize app finished after PT3M5.019229051S
08:23:02.880 INFO scan=sv3Scan | sv3Scan app initialization: Initialize app finished after PT2M44.364375794S
08:23:04.387 INFO validator=bobValidator | bobValidator app initialization: Initialize app finished after PT3M8.321232003S
08:23:08.380 INFO scan=sv2Scan | sv2Scan app initialization: Initialize app finished after PT2M52.875381748S
08:23:11.959 INFO scan=sv4Scan | sv4Scan app initialization: Initialize app finished after PT2M59.467418244S
08:23:15.074 DEBUG validator=splitwellValidator | splitwellValidator app initialization: Set up app instance splitwell started
08:23:40.558 INFO SV=sv3 | sv3 app initialization: Initialize app finished after PT3M35.075538692S
08:23:42.198 INFO SV=sv2 | sv2 app initialization: Initialize app finished after PT3M55.129117126S
08:23:42.879 INFO SV=sv4 | sv4 app initialization: Initialize app finished after PT3M36.594926339S
08:23:44.196 INFO validator=sv3Validator | sv3Validator app initialization: Initialize app finished after PT3M34.57642998S
08:23:44.528 INFO validator=sv2Validator | sv2Validator app initialization: Initialize app finished after PT3M22.995006367S
08:23:48.173 INFO validator=sv4Validator | sv4Validator app initialization: Initialize app finished after PT3M42.29255601S
08:27:30.954 ERROR splitwell=providerSplitwellBac | providerSplitwellBackend app initialization: Initialization failed
```

11326: inside that step (from 08:23:18), the splitwell DAR vetting succeeded on `splitwell` and `splitwellUpgrade` but never became visible on the global synchronizer (vetting messages, deduplicated):
```
zcat log/11326/logs-simtime-0/canton_network_test.clog.gz | jq -r "select((.logger_name|test(\"validator=splitwellValidator\")) and .[\"@timestamp\"] >= \"2026-11-10T08:23:18\" and (.message|test(\"^(Ensuring that dars|Success: dars|The operation .*(Giving up|failed with a retryable))\"))) | \"\(.[\"@timestamp\"][11:23]) | \(.message|gsub(\"[0-9a-f]{64}\";\"H\")|gsub(\"\\\\s+\";\" \")|.[0:150])\"" | awk "{k=substr(\$0,14); if(!(k in s)){s[k]=1; print}}"
```
```
08:23:18.548 | Ensuring that dars List(H) are vetted on splitwell::1220a4aef4fa... from None
08:23:18.595 | The operation 'check established dars List(H) are vetted on splitwell::1220a4aef4fa... from None' failed with a retryable error (full stack trace omit
08:23:19.127 | Success: dars List(H) are vetted on splitwell::1220a4aef4fa... from None
08:23:19.134 | Ensuring that dars List(H) are vetted on splitwellUpgrade::12208d4935ac... from None
08:23:19.173 | The operation 'check established dars List(H) are vetted on splitwellUpgrade::12208d4935ac... from None' failed with a retryable error (full stack tra
08:23:19.711 | Success: dars List(H) are vetted on splitwellUpgrade::12208d4935ac... from None
08:23:19.718 | Ensuring that dars List(H) are vetted on global-domain::12207de1a3a8... from None
08:23:19.758 | The operation 'check established dars List(H) are vetted on global-domain::12207de1a3a8... from None' failed with a retryable error (full stack trace 
08:25:15.082 | The operation 'update ingestion service' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded af
08:25:19.858 | The operation 'check established dars List(H) are vetted on global-domain::12207de1a3a8... from None' has failed with an exception. Total maximum numb
08:25:19.858 | The operation 'dars List(H) are vetted on global-domain::12207de1a3a8... from None' failed with a retryable error (full stack trace omitted): FAILED_P
```

## 4. The topology transaction each creator waits for never reaches the global synchronizer

Global ordering topology on globalSequencerSv1: 1 node until the switch to 4 (11323 epoch 12 at 05:37:26.551, 11326 epoch 14 at 08:23:20.068); blacklisting starts only later:
```
for r in "11323 logs-simtime-4 2026-11-10T05:37:0 2026-11-10T05:38:2" "11326 logs-simtime-0 2026-11-10T08:22:5 2026-11-10T08:23:5"; do set -- $r; echo "== $1"; zcat log/$1/$2/canton-simtime.clog.gz | grep -a "New epoch [0-9]* has started" | jq -r "select((.logger_name|test(\"globalSequencerSv1/\")) and .[\"@timestamp\"] >= \"$3\" and .[\"@timestamp\"] <= \"$4\") | .[\"@timestamp\"][11:23] + \" epoch \" + (.message|capture(\"New epoch (?<e>[0-9]+)\").e) + \" size=\" + (.message|capture(\"size = (?<s>[0-9]+)\").s) + \" blacklisted=\" + (.message|capture(\"blacklisted nodes = List\\\\((?<b>[^)]*)\\\\)\").b|gsub(\"::[0-9a-f]+\";\"\"))"; done
```
```
== 11323
05:37:04.867 epoch 9 size=1 blacklisted=
05:37:12.225 epoch 10 size=1 blacklisted=
05:37:19.682 epoch 11 size=1 blacklisted=
05:37:26.551 epoch 12 size=4 blacklisted=
05:37:46.938 epoch 13 size=4 blacklisted=SEQ::sv1
05:38:01.634 epoch 14 size=4 blacklisted=SEQ::sv1
05:38:16.653 epoch 15 size=4 blacklisted=SEQ::sv1
== 11326
08:22:56.817 epoch 11 size=1 blacklisted=
08:23:04.213 epoch 12 size=1 blacklisted=
08:23:12.221 epoch 13 size=1 blacklisted=
08:23:20.068 epoch 14 size=4 blacklisted=
08:23:34.594 epoch 15 size=4 blacklisted=SEQ::sv4
08:23:45.375 epoch 16 size=4 blacklisted=SEQ::sv4
```

11323: the proposal that adds MED::sv2 (threshold 2) was submitted by all four SV apps between 05:37:26.481 and .945, around the switch:
```
zcat log/11323/logs-simtime-4/canton_network_test.clog.gz | jq -r "select(.[\"@timestamp\"] >= \"2026-11-10T05:37:26.4\" and .[\"@timestamp\"] <= \"2026-11-10T05:37:27\" and (.message|test(\"Submitted proposal MediatorSynchronizerState\"))) | \"\(.[\"@timestamp\"][11:23]) \(.logger_name|capture(\"SV=(?<n>sv[0-9])\").n) | \(.message|gsub(\"\\\\s+\";\" \")|.[0:330])\""
```
```
05:37:26.481 sv2 | Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::122041540925..., group = 0, threshold = 2, active = Seq(MED::sv1::1220391de3f9..., MED::sv3::1220d415c3f9..., MED::sv4::1220fd1146b4..., MED::sv2::1220d896173c...) ) for Add mediator MED::sv2::1220d896173c..., waiting until the proposal gets accepted
05:37:26.560 sv1 | Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::122041540925..., group = 0, threshold = 2, active = Seq(MED::sv1::1220391de3f9..., MED::sv3::1220d415c3f9..., MED::sv4::1220fd1146b4..., MED::sv2::1220d896173c...) ) for Add mediator MED::sv2::1220d896173c..., waiting until the proposal gets accepted
05:37:26.569 sv4 | Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::122041540925..., group = 0, threshold = 2, active = Seq(MED::sv1::1220391de3f9..., MED::sv3::1220d415c3f9..., MED::sv4::1220fd1146b4..., MED::sv2::1220d896173c...) ) for Add mediator MED::sv2::1220d896173c..., waiting until the proposal gets accepted
05:37:26.945 sv3 | Submitted proposal MediatorSynchronizerState( synchronizerId = global-domain::122041540925..., group = 0, threshold = 2, active = Seq(MED::sv1::1220391de3f9..., MED::sv3::1220d415c3f9..., MED::sv4::1220fd1146b4..., MED::sv2::1220d896173c...) ) for Add mediator MED::sv2::1220d896173c..., waiting until the proposal gets accepted
```

11323: the four participants broadcast that transaction (TxHash db11b81f3e51); sv1, sv4 and sv3 get a transport error back:
```
zcat log/11323/logs-simtime-4/canton-simtime.clog.gz | awk "/\"@timestamp\":\"2026-11-10T05:37:2[6-9]/" | grep -a -E "participant=sv[1-4]Participant" | grep -a -E "Broadcasting topology|responded the following|returned an error" | jq -r "select(.[\"trace-id\"]|test(\"^(ac5ffbc9|d06b31f1|a3d5dfb5|5e10e5bf)\")) | \"\(.[\"@timestamp\"][11:23]) \(.logger_name|capture(\"participant=(?<n>[A-Za-z0-9]+)\").n) trace=\(.[\"trace-id\"]|.[0:8]) | \(.message|gsub(\"\\\\s+\";\" \")|.[0:150])\""
```
```
05:37:26.480 sv2Participant trace=ac5ffbc9 | Broadcasting topology transaction: List(TxHash(SHA-256:db11b81f3e51...))
05:37:26.559 sv1Participant trace=d06b31f1 | Broadcasting topology transaction: List(TxHash(SHA-256:db11b81f3e51...))
05:37:26.568 sv4Participant trace=a3d5dfb5 | Broadcasting topology transaction: List(TxHash(SHA-256:db11b81f3e51...))
05:37:26.629 sv1Participant trace=d06b31f1 | Send [36d41ca6-744a-4750-ab49-8bcddff4f6fc] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Req
05:37:26.629 sv4Participant trace=a3d5dfb5 | Send [dd8369d6-369f-4b32-a1d4-b7c203223c1a] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Req
05:37:26.944 sv3Participant trace=5e10e5bf | Broadcasting topology transaction: List(TxHash(SHA-256:db11b81f3e51...))
05:37:26.961 sv3Participant trace=5e10e5bf | Send [4a2f97da-ff85-4eb8-b486-363ddcfa45cc] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Req
```

11323, sequencer side (same traces): sv2 arrived 63 ms before the switch and was accepted; sv1, sv4 and sv3 arrived after it and were rejected; max sequencing time 00:00:20:
```
zcat log/11323/logs-simtime-4/canton-simtime.clog.gz | awk "/\"@timestamp\":\"2026-11-10T05:37:2[5-9]/" | grep -a -E "Rejecting submission request by|Request to send submission" | jq -r "select(.[\"trace-id\"]|test(\"^(ac5ffbc9|d06b31f1|a3d5dfb5|5e10e5bf)\")) | \"\(.[\"@timestamp\"][11:23]) \(.level) \(.logger_name|capture(\"(?<n>sequencer=[A-Za-z0-9]+)\").n) | \(.message|gsub(\"\\\\s+\";\" \")|gsub(\"1220[0-9a-f]{60}\";\"..\")|.[0:230])\""
```
```
05:37:26.488 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 630e55b9-55d9-474c-bbf7-81a2648c7b2a with max sequencing time 1970-01-01T00:00:20Z from PAR::sv2::1220bcdefaae... to Set(All). 
05:37:26.566 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 36d41ca6-744a-4750-ab49-8bcddff4f6fc with max sequencing time 1970-01-01T00:00:20Z from PAR::sv1::1220475c4a8a... to Set(All). 
05:37:26.575 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id dd8369d6-369f-4b32-a1d4-b7c203223c1a with max sequencing time 1970-01-01T00:00:20Z from PAR::sv4::122076e2f33c... to Set(All). 
05:37:26.600 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv1::1220475c4a8a...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
05:37:26.600 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv4::122076e2f33c...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
05:37:26.953 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 4a2f97da-ff85-4eb8-b486-363ddcfa45cc with max sequencing time 1970-01-01T00:00:20Z from PAR::sv3::12206c652c25... to Set(All). 
05:37:26.954 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv3::12206c652c25...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
```

11323: sv2 accepted submission went into a batch with reference epoch 12, created as the epoch started; its dissemination to the three newcomers was dropped (unauthenticated), and the batch has no later log line:
```
F=log/11323/logs-simtime-4/canton-simtime.clog.gz; zcat $F | grep -a "\"trace-id\":\"ac5ffbc9" | grep -a -E "LocalBatchCreated|disseminating|Dropping network message" | jq -r "\"\(.[\"@timestamp\"][11:23]) \(.logger_name|capture(\"(?<n>sequencer=[A-Za-z0-9]+)\").n) | \(.message|gsub(\"\\\\s+\";\" \")|gsub(\"1220[0-9a-f]{60}\";\"..\")|.[0:200])\""; echo "lines naming the batch: $(zcat $F | grep -a -c "ac7c)")"
```
```
05:37:26.550 sequencer=globalSequencerSv1 | Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..ac7c) with reference epoch 12 for batch received from local mempool containing 1 requests (tag=send, messageId=630e55b9-55
05:37:26.553 sequencer=globalSequencerSv1 | Availability.LocalDissemination.LocalBatchesStoredSigned: disseminating BatchId(..ac7c) to SEQ::sv3::..f80f, SEQ::sv4::..5eaf, SEQ::sv2::..6cbd
05:37:26.636 sequencer=globalSequencerSv1 | Dropping network message to unknown SEQ::sv2::..6cbd (possibly unauthenticated as of yet)
05:37:26.636 sequencer=globalSequencerSv1 | Dropping network message to unknown SEQ::sv3::..f80f (possibly unauthenticated as of yet)
05:37:26.637 sequencer=globalSequencerSv1 | Dropping network message to unknown SEQ::sv4::..5eaf (possibly unauthenticated as of yet)
lines naming the batch: 5
```

11323: none of the four submissions is ever delivered (`validated to: Deliver` count 0), and authorization details for the transaction are logged only by the four SV participants, never by a sequencer or mediator (line count per node):
```
F=log/11323/logs-simtime-4/canton-simtime.clog.gz; for id in 630e55b9-55d9-474c-bbf7-81a2648c7b2a 36d41ca6-744a-4750-ab49-8bcddff4f6fc dd8369d6-369f-4b32-a1d4-b7c203223c1a 4a2f97da-ff85-4eb8-b486-363ddcfa45cc; do echo "== $id validated=$(zcat $F | grep -a -F $id | grep -a -c "validated to: Deliver") last=$(zcat $F | grep -a -F $id | tail -1 | jq -r "\"\(.[\"@timestamp\"][11:23]) \(.logger_name|capture(\"(?<n>(sequencer|participant)=[A-Za-z0-9]+)\").n)\"")"; done; zcat $F | grep -a "Authorization details for MediatorSynchronizerState=TxHash(SHA-256:db11b81f3e51" | jq -r ".logger_name|capture(\"(?<n>(participant|sequencer|mediator)=[A-Za-z0-9]+)\").n" | awk "{c[\$1]++} END{for(k in c) print k, c[k]}"
```
```
== 630e55b9-55d9-474c-bbf7-81a2648c7b2a validated=0 last=05:37:26.550 sequencer=globalSequencerSv1
== 36d41ca6-744a-4750-ab49-8bcddff4f6fc validated=0 last=05:37:26.633 participant=sv1Participant
== dd8369d6-369f-4b32-a1d4-b7c203223c1a validated=0 last=05:37:26.633 participant=sv4Participant
== 4a2f97da-ff85-4eb8-b486-363ddcfa45cc validated=0 last=05:37:26.972 participant=sv3Participant
participant=sv1Participant 3
participant=sv4Participant 3
participant=sv3Participant 3
participant=sv2Participant 3
```

11326: splitwellParticipant broadcast its VettedPackages transaction (TxHash d5e4fd1b195f) to the global synchronizer at 08:23:19.749, 0.3 s before the switch; globalSequencerSv1 accepted it, put it in a batch with reference epoch 13 as that epoch was closing, discarded the batch proposal, and dropped the dissemination to the three newcomers:
```
zcat log/11326/logs-simtime-0/canton-simtime.clog.gz | grep -a "\"trace-id\":\"5d3bb149" | grep -a -E "Broadcasting topology|Request to send submission|Estimated sequencing time|LocalBatchCreated|received message after closing|Dropping network message" | jq -r "\"\(.[\"@timestamp\"][11:23]) \(.logger_name|capture(\"(?<n>(sequencer|participant)=[A-Za-z0-9]+)\").n) | \(.message|gsub(\"\\\\s+\";\" \")|gsub(\"1220[0-9a-f]{60}\";\"..\")|.[0:200])\""
```
```
08:23:19.749 participant=splitwellParticipant | Broadcasting topology transaction: List(TxHash(SHA-256:d5e4fd1b195f...))
08:23:19.757 sequencer=globalSequencerSv1 | Request to send submission with id aafcf3c6-a893-43ad-a8db-3046f58b6a39 with max sequencing time 1970-01-01T00:00:20Z from PAR::splitwellValidator::1220743d98f9... to Set(All). 
08:23:19.758 sequencer=globalSequencerSv1 | Estimated sequencing time 1970-01-01T00:00:00.209Z for submission with id aafcf3c6-a893-43ad-a8db-3046f58b6a39
08:23:20.043 sequencer=globalSequencerSv1 | Availability.LocalDissemination.LocalBatchCreated: created batch ID BatchId(..612a) with reference epoch 13 for batch received from local mempool containing 1 requests (tag=send, messageId=aafcf3c6-a8
08:23:20.047 sequencer=globalSequencerSv1 | At epoch 13 received message after closing, so discarding LocalAvailability(ProposalCreated(209,OrderingBlock(List(ProofOfAvailability(BatchId(..612a),List(AvailabilityAck( from = "SEQ::sv1::..961e", 
08:23:20.169 sequencer=globalSequencerSv1 | Dropping network message to unknown SEQ::sv2::..7c0d (possibly unauthenticated as of yet)
08:23:20.169 sequencer=globalSequencerSv1 | Dropping network message to unknown SEQ::sv3::..5097 (possibly unauthenticated as of yet)
08:23:20.169 sequencer=globalSequencerSv1 | Dropping network message to unknown SEQ::sv4::..650d (possibly unauthenticated as of yet)
```

11326: the submission is never delivered, the batch has no later line, and only splitwellParticipant ever logs authorization details for the transaction:
```
F=log/11326/logs-simtime-0/canton-simtime.clog.gz; echo "validated=$(zcat $F | grep -a -F aafcf3c6-a893-43ad-a8db-3046f58b6a39 | grep -a -c "validated to: Deliver")"; echo "last batch line: $(zcat $F | grep -a "612a)" | tail -1 | jq -r ".[\"@timestamp\"][11:23]")"; zcat $F | grep -a "Authorization details for VettedPackages=TxHash(SHA-256:d5e4fd1b195f" | jq -r "\"\(.[\"@timestamp\"][11:23]) \(.logger_name|capture(\"(?<n>(participant|sequencer|mediator)=[A-Za-z0-9]+)\").n)\""
```
```
validated=0
last batch line: 08:23:20.068
08:23:19.746 participant=splitwellParticipant
08:25:20.094 participant=splitwellParticipant
08:27:10.597 participant=splitwellParticipant
```

## 5. Why nothing retries: the senders wait for a max sequencing time the sim clock never reaches

The participants ignore the send errors as `may still be possibly sequenced` (section 4) and their outbox flush cycle for the global synchronizer stays open: the re-proposals at 05:39 and 05:41 (11323) and the vetting retries at 08:25 and 08:27 (11326) only log `Another flush cycle is currently ongoing` (count per participant after the switch). Per the 11255 analysis such a send counts as possibly sequenced until its max sequencing time (1970-01-01T00:00:20Z here, section 4); that rule was not re-checked against the 3.7.3 jar for this packet.
```
for r in "11323 logs-simtime-4 2026-11-10T05:37:27" "11326 logs-simtime-0 2026-11-10T08:23:21"; do set -- $r; echo "== $1"; zcat log/$1/$2/canton-simtime.clog.gz | grep -a "Another flush cycle is currently ongoing" | jq -r "select(.[\"@timestamp\"] > \"$3\" and (.logger_name|test(\"psid=global\"))) | .logger_name|capture(\"participant=(?<n>[A-Za-z0-9]+)\").n" | awk "{c[\$1]++} END{for(k in c) print k, c[k]}"; done
```
```
== 11323
sv3Participant 2
sv2Participant 2
sv1Participant 2
sv4Participant 2
== 11326
splitwellParticipant 2
```

The simulated clock is still about 2 s after epoch start when the apps give up (last estimate on globalSequencerSv1 before the exit):
```
for r in "11323 logs-simtime-4 2026-11-10T05:42:00" "11326 logs-simtime-0 2026-11-10T08:27:00"; do set -- $r; echo "== $1"; zcat log/$1/$2/canton-simtime.clog.gz | grep -a "Estimated sequencing time" | jq -r "select(.[\"@timestamp\"] > \"$3\" and (.logger_name|test(\"globalSequencerSv1/\"))) | \"\(.[\"@timestamp\"][11:23]) \(.message|.[0:80])\"" | tail -1; done
```
```
== 11323
05:42:27.981 Estimated sequencing time 1970-01-01T00:00:02.182Z for submission with id d2056c
== 11326
08:27:23.182 Estimated sequencing time 1970-01-01T00:00:01.882Z for submission with id 54cd90
```

## 6. Duplicate check

Tracker search for the exit message (all states); none of the hits is this mechanism, since all predate DA BFT in all tests (#8234, 2026-11-06) or are cluster deployments:
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "Wait for user failed"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:100])"'
```
```
#9519 closed/duplicate 2026-08-07 ciperiodic deployment fails because of "scan-app app initialization: Wait for user failed"
#5894 closed/completed 2025-10-10 sv app initialization: Wait for user failed
#6659 closed/not_planned 2025-12-10 splitwell UserNotFound Gave up getting 'user ekPlYxilradhEnpWdS80WfW63z1nHvKy@clients' in cimain dep
#6720 closed/duplicate 2026-01-07 providerSplitwellBackend app initialization: Initialization failed, gave up on wait for user
#6100 closed/not_planned 2025-10-22 Fail resetting decentralized namespace because global sync is unknown
#8044 closed/duplicate 2026-04-10 WalletAuth0FrontendIntegrationTest/A wallet UI with a backend configured to accept auth0 tokens shou
#3093 closed/completed 2025-03-13 sequencer seems to get stuck in time awaiter shortly after initialization
#7498 closed/completed 2026-03-11 ciperiodic: canton-network stack failed to update: Error from server (NotFound): stacks.pulumi.com "
#5895 closed/completed 2025-10-10 Timeout while waiting for initialization of validator_backend
#3891 closed/not_planned 2025-04-29 future timeout in wallet frontend test
#4437 closed/completed 2025-06-04 WalletFrontendTimeBasedIntegrationTest - future timeout when runnning clearWebDrivers
```

Parent 11255 state and the shape list in its 2026-11-09 comment (the group these two join):
```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"#\(.number) \(.state) assignees=\([.assignees[].login]|join(",")) \(.title)"'; gh api repos/DACH-NY/cn-test-failures/issues/11255/comments --jq '.[1].body' | grep -E '^- (Lost verdict|Sends rejected)'
```
```
#11255 open assignees=rautenrieth-da Timeouts due to changing bft ordering topology
- Lost verdict, a mediator result refused by a blacklisted sequencer (addressed by canton-network/splice#8356): #11258, #11275, #11284, #11287, #11306, #11308.
- Sends rejected or batches never ordered at the 1 to 4 switch, no blacklisting involved (not addressed): the description above, #11241, #11242, #11282, #11283, #11288, #11289.
```

splice#8356 (blacklisting off in sim-time tests) is still open, and these failures do not involve blacklisting anyway (section 4: blacklisting starts after the loss):
```
gh api repos/canton-network/splice/pulls/8356 --jq '"#\(.number) \(.state) merged=\(.merged_at) \(.title)"'
```
```
#8356 open merged=null Disable BFT sequencer blacklisting in integration tests
```

Per hit: #9519, #6659, #7498 are cluster deployments; #5894 (2025-10), #6720 (2026-01, providerSplitwellBackend) are
`Wait for user` exits from before DA BFT in all tests and before the 1 to 4 onboarding pattern of 11255; #6100,
#8044, #3093, #3891, #4437, #5895 match the search words but are other failures (title). None is the same failure.
Family B confirming grep (known-families.md, rejected-send shape): `Rejecting submission request by` with `P2P
connectivity is not ready` within 0.41 s after `New epoch N ... size = 4` (11323: 05:37:26.551 to .600 and .954,
section 4) and no blacklisting before the loss (section 4). 11326 has no rejection but the batch-never-ordered variant
the 11255 comment names (section 4: `received message after closing, so discarding` 21 ms before the switch).

## Verdict

Duplicate of 11255 (family B, rejected-send / batch-never-ordered shape at the 1 to 4 switch under simulated time),
both refs. Flake caused by the test environment's BFT onboarding pattern, not by the apps that exit; the PERMISSION_DENIED
is only the participant's masking of `UserNotFound` for a user that is never created. Not caused by the Canton bump to
3.7.3-snapshot (#8381): the same shape hit on 3.7.2 (11288, 11289). Fix location as in 11255 (owner rautenrieth-da):
avoid the 1 to 4 step during setup or let the senders retry before max sequencing time; splice#8356 (open) does not
cover it. No splice change for these two refs.

Not verified: that the participant keeps waiting until max sequencing time (taken from the 11255 analysis, not re-read
in the 3.7.3 jar); why globalSequencerSv1 in 11323 never disseminated batch ac7c again after the newcomers
authenticated (no later log line for it).
