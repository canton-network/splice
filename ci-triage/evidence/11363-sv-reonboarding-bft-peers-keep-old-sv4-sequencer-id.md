# 11363 - SvReonboardingIntegrationTest: re-onboarded sv4 BFT sequencer never authenticated by its peers, sv4Reonboard init over the 5 min startAllSync wait (run 43031077112)

new (not 11347). Post-merge CI on main, sha 644b3ba97a (#8424), job `key-download (0)`, tests 8 passed, 1 failed.
The failing wait is the second `startAllSync` of the test (line 446, sv4Reonboard), not the first one that failed in
11347 (sv1). sv4Reonboard's DAR uploads took 30 s; the time went into `Waiting until Mediator has been granted
unlimited traffic` (14:19:05.970 to the shutdown at 14:22:54.923), which reads the traffic state from sv4's own
newly onboarded BFT sequencer. That sequencer never left its onboarding state transfer from epoch 81, because the
peer sequencers sv1 to sv3 kept their P2P endpoint `localhost:27410` bound to the OLD sv4 sequencer id
(`SEQ::sv4SvReonboarding::..06d6`): each time they authenticated the new node (`SEQ::sv4ReonboardSvReonboarding::..d4da`),
a leftover connection actor for the old id re-associated the endpoint with the old id about 100 ms later (868 flips
each way on sv1, 14:19:07.150 to 14:22:47.834), so they dropped every message to the new node as `unknown ...
(possibly unauthenticated as of yet)`. The old sv4 had reconnected inbound 0.5 s after the SVs removed its endpoint
(14:17:21.395) and before its Canton process was stopped (14:17:27.286); the new sv4 process reuses P2P port 27410.
Same symptom as 10011 (ciperiodic sv re-onboard, "other SVs still keep the old sequencer id"), closed as fixed in
Canton 3.6 (DACH-NY/canton#35711, splice#7264, 2026-09-11). Teardown closed cleanly this time: no family H cascade.

- Run: https://github.com/canton-network/splice/actions/runs/43031077112, main 644b3ba97a ("Pause faucet coupon
  automation in ScanEventHistoryIntegrationTest (#8424)"), job 130558753553 `ci / scala_test_key_download / key-download (0)`, attempt 1,
  runner self-hosted-k8s-x-large-runner-4fq9j.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: Canton (BFT orderer P2P connection state); test (reuses the P2P port of the offboarded sv4 sequencer).

## 1. Job and failing assertion

```
RUN=43031077112; export NAME='key-download (0)'
for p in 1 2 3 4 5; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name) \(.started_at) \(.completed_at) runner=\(.runner_name)"'
```
```
130558753553 attempt=1 ci / scala_test_key_download / key-download (0) 2026-11-11T14:01:56Z 2026-11-11T14:36:26Z runner=self-hosted-k8s-x-large-runner-4fq9j
```

```
L=log/inbox-1111b/130558753553.log
grep -anE '\*\*\* FAILED|ABORTED|Tests: succeeded|contains problems|Run completed' $L | cut -c1-220
sed -n '13613,13640p' $L | cut -c29-260
```
```
13613:2026-11-11T14:22:55.4319890Z [info] - reonboard SV with new party id and recover amulet via new regular validator *** FAILED ***
13741:2026-11-11T14:35:41.7033797Z [info] Run completed in 24 minutes, 20 seconds.
13744:2026-11-11T14:35:41.7037189Z [info] Tests: succeeded 8, failed 1, canceled 0, ignored 0, pending 0
13748:2026-11-11T14:35:41.7039876Z [info] - reonboard SV with new party id and recover amulet via new regular validator *** FAILED ***
 [info] - reonboard SV with new party id and recover amulet via new regular validator *** FAILED ***
 [info]   java.lang.IllegalStateException: Condition never became true within 5 minutes
 [info]   at com.digitalasset.canton.console.ConsoleMacros$utils$.retry_until_true(ConsoleMacros.scala:151)
 [info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization(SpliceInstanceReference.scala:199)
 [info]   at org.lfdecentralizedtrust.splice.console.HttpAppReference.waitForInitialization$(SpliceInstanceReference.scala:194)
 [info]   at org.lfdecentralizedtrust.splice.console.SvAppReference.waitForInitialization(SvAppReference.scala:51)
 [info]   at org.lfdecentralizedtrust.splice.integration.tests.SpliceTests$TestCommon.$anonfun$startAllSync$2(SpliceTests.scala:448)
 ...
 [info]   at org.lfdecentralizedtrust.splice.integration.tests.SvReonboardingIntegrationTest.startAllSync(SvReonboardingIntegrationTest.scala:54)
 [info]   at org.lfdecentralizedtrust.splice.integration.tests.SvReonboardingIntegrationTest.$anonfun$new$20(SvReonboardingIntegrationTest.scala:446)
 ...
 [info]   at org.lfdecentralizedtrust.splice.integration.tests.SvReonboardingIntegrationTest.$anonfun$new$2(SvReonboardingIntegrationTest.scala:428)
```

Line 446 is the second `startAllSync`, for the re-onboarded sv4 (11347 failed at the first one, line 241 at its sha, for sv1):

```
git show 644b3ba97ab4c3cf188eea599a0e53ef3e2cf6ad:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvReonboardingIntegrationTest.scala | sed -n '443,447p'
```
```
        startAllSync(
          sv4ReonboardBackend,
          sv4ReonboardValidatorBackend,
          validatorLocalBackend,
        )
```

## 2. Test timeline: the first phase passed, sv4Reonboard timed out

Artifact `logs-key-download-0` (id 13864453000) to `log/11363/logs-key-download-0/`.

```
cd log/11363/logs-key-download-0
zcat canton_network_test.clog.gz | grep -a 'SvReonboardingIntegrationTest' | grep -aE '(Running|Finished|Failed) clue|Starting test|Test failed|Test succeeded|Timeout while waiting' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,140}).*/\1 \2/' | head -30
```
```
2026-11-11T14:11:22.200Z Starting test suite 'SvReonboardingIntegrationTest'...
2026-11-11T14:11:29.020Z Running clue: Starting external Canton process sv123-reonboarding with List(canton.features.enable-repair-commands=yes)
...
2026-11-11T14:11:29.062Z Running clue: Using external Canton process sv4-reonboarding
2026-11-11T14:16:54.973Z Running clue: Offboard SV4
2026-11-11T14:16:54.974Z Running clue: (act) SV1 create a vote request to remove sv4
...
2026-11-11T14:17:26.559Z Finished clue: Offboard SV4
2026-11-11T14:17:26.559Z Running clue: Stop SV4
2026-11-11T14:17:26.734Z Finished clue: Stop SV4
2026-11-11T14:17:26.734Z Finished clue: Using external Canton process sv4-reonboarding
2026-11-11T14:17:26.734Z Running clue: Destroying external Canton process sv4-reonboarding
2026-11-11T14:17:27.841Z Finished clue: Destroying external Canton process sv4-reonboarding
2026-11-11T14:17:27.844Z Running clue: Starting external Canton process sv4-reonboarding-new with List(canton.features.enable-repair-commands=yes)
2026-11-11T14:17:27.863Z Finished clue: Starting external Canton process sv4-reonboarding-new with List(canton.features.enable-repair-commands=yes)
2026-11-11T14:17:27.863Z Running clue: Using external Canton process sv4-reonboarding-new
2026-11-11T14:22:45.884Z Timeout while waiting for initialization of sv4Reonboard
2026-11-11T14:22:45.885Z Failed clue: Using external Canton process sv4-reonboarding-new
...
2026-11-11T14:22:55.014Z Test failed: 'SvReonboardingIntegrationTest/reonboard SV with new party id and recover amulet via new regular validator', message: Condition
```

## 3. sv4Reonboard init: everything done by 14:19:06 except the mediator traffic wait

```
zcat canton_network_test.clog.gz | grep -a 'config=3601e0e7/SV=sv4Reonboard"' | grep -aE 'app initialization: [A-Z]' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*/\1 \2/' | awk '!seen[substr($0,26)]++'
```
```
2026-11-11T14:17:43.677Z sv4Reonboard app initialization: Starting initialization
...
2026-11-11T14:17:51.600Z sv4Reonboard app initialization: JoiningNodeInitializer joining Dso with key started
2026-11-11T14:22:54.924Z sv4Reonboard app initialization: JoiningNodeInitializer joining Dso with key failed
```

Inside `joining Dso with key` (filtered for the step lines):

```
zcat canton_network_test.clog.gz | grep -a 'config=3601e0e7/SV=sv4Reonboard"' | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T14:17:56" && t<"2026-11-11T14:22:56") print}' | grep -avE 'Request \(tid|HTTP GET /api/sv/status|queued action|FetchTime|Detected an error|is not stale|Checking whether the task|Attempting to close|Successfully closed|Token refresh|Refreshing auth|[Ii]ndex |Completed processing with outcome: (Created|Confirmed)|Processing\\nConfirm|Processing\\nExecute|Starting trigger|HTTP client|Now retrying|Retrying on same' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-260 | awk '{k=substr($0,26,45); if(!(k in s)){s[k]=1; print}}'
# (selected lines shown; the full output lists every distinct step)
```
```
2026-11-11T14:17:57.588Z Vetting packages based on state from sponsor", INFO
2026-11-11T14:17:57.613Z Sending upload dar for splice-wallet-0.1.26", INFO
2026-11-11T14:18:29.713Z Packages vetting completed", INFO
2026-11-11T14:18:29.783Z Requesting to be onboarded via the sponsor SV", INFO
2026-11-11T14:18:36.746Z DSO party is now hosted in the candidate SV participant PAR::sv4ReonboardSvReonboarding::12203a9838f5...", INFO
2026-11-11T14:18:48.383Z Waiting until the DsoRules and AmuletRules are visible", INFO
2026-11-11T14:18:55.527Z Adding sequencer SequencerToOnboard(synchronizerId = global-domain::12201d8c3371..., sequencerId = SEQ::sv4ReonboardSvReonboarding::122081561882...)", INFO
2026-11-11T14:18:56.492Z Success: Add sequencer SEQ::sv4ReonboardSvReonboarding::122081561882...", INFO
2026-11-11T14:18:56.994Z Success: Add mediator MED::sv4ReonboardSvReonboarding::1220ce14b5f3...", INFO
2026-11-11T14:19:02.363Z Onboarded sequencer SEQ::sv4ReonboardSvReonboarding::122081561882...", INFO
2026-11-11T14:19:02.363Z Initializing sequencer SEQ::sv4ReonboardSvReonboarding::122081561882...", INFO
2026-11-11T14:19:05.969Z Onboarding mediator", INFO
2026-11-11T14:19:05.970Z Waiting until Mediator has been granted unlimited traffic", INFO
2026-11-11T14:19:05.982Z The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable error (full stack trace omitted): NOT_FOUND: No traffic state found for member MED::sv4ReonboardSvReonboarding::1220ce14b5f3...\nstatusCo
2026-11-11T14:19:06.781Z Adding bft peers. Current peers [List()]. Adding: [List(PlainTextP2PEndpoint(localhost,27110), PlainTextP2PEndpoint(localhost,27210), PlainTextP2PEndpoint(localhost,27310))]", INFO
2026-11-11T14:22:47.022Z The operation 'pollingTriggerTask' failed with a transient error (full stack trace omitted):\ncategory=Some(TransientServerFailure)\nErrorInfoDetail(ABORTED_DUE_TO_SHUTDOWN,...
2026-11-11T14:22:54.923Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
```

DAR uploads were not the cost here (30 s; the same 22 repeats as in 11347 and 11299):

```
zcat canton_network_test.clog.gz | grep -a 'config=3601e0e7/SV=sv4Reonboard"' | grep -a 'Sending upload dar for' | sed -E 's/.*"@timestamp":"([^"]+)".*Sending upload dar for ([^"]+)".*/\1 \2/' | awk '{n++; c[$2]++; if(n==1) f=$1; l=$1} END{for(k in c){d++; if(c[k]>1) r+=c[k]-1} print "uploads", n, "distinct", d, "repeats", r+0, "first", f, "last", l}'
```
```
uploads 87 distinct 65 repeats 22 first 2026-11-11T14:17:57.613Z last 2026-11-11T14:18:27.694Z
```

The wait (apps/sv LocalSynchronizerNode.initializeLocalMediator) reads the mediator's traffic state from the SV's own
sequencer:

```
git show 644b3ba97ab4c3cf188eea599a0e53ef3e2cf6ad:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/LocalSynchronizerNode.scala | sed -n '250,258p'
```
```
      mediatorId <- mediatorAdminConnection.getMediatorId
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "mediator_unlimited_traffic",
        "Mediator has been granted unlimited traffic",
        sequencerAdminConnection
          .getSequencerTrafficControlState(
            mediatorId
          )
```

63 failed checks, 14:19:05.982 to 14:22:51.117:

```
zcat canton_network_test.clog.gz | grep -a 'config=3601e0e7/SV=sv4Reonboard"' | grep -a 'granted unlimited traffic' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-300 | awk '{print substr($0,1,24), substr($0,26,140)}' | awk '{k=substr($0,26,100); c[k]++; if(!(k in f)) f[k]=$1; l[k]=$1} END{for(k in c) print c[k], f[k], l[k], k}'
```
```
63 2026-11-11T14:19:05.982Z 2026-11-11T14:22:51.117Z The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable er
```

## 4. The grant happened on sv1 to sv3; only sv4's own sequencer never saw it

```
zcat canton_network_test.clog.gz | grep -a 'config=3601e0e7' | grep -a 'Updated traffic limit for MED::sv4Reonboard' | sed -E 's/.*"@timestamp":"([^"]+)","message":"([^"]{0,150}).*SV=([A-Za-z0-9]+)".*/\1 \3 \2/' | sed -E 's/1220[0-9a-f]{8,60}/../g'
```
```
2026-11-11T14:18:58.105Z sv2 Completed processing with outcome: Updated traffic limit for MED::sv4ReonboardSvReonboarding::..... to NonNegativeLong.maxValue
2026-11-11T14:18:58.329Z sv1 Completed processing with outcome: Updated traffic limit for MED::sv4ReonboardSvReonboarding::..... to NonNegativeLong.maxValue
2026-11-11T14:18:58.362Z sv3 Completed processing with outcome: Updated traffic limit for MED::sv4ReonboardSvReonboarding::..... to NonNegativeLong.maxValue
```

`SvOnboardingUnlimitedTrafficTrigger` skips per SV (each SV asks its own sequencer); sv1 to sv3 saw the new mediator
after 2 skips, sv4Reonboard's sequencer never did:

```
zcat canton_network_test.clog.gz | grep -a 'does not yet have a traffic state' | grep -a 'config=3601e0e7' | sed -E 's/.*"@timestamp":"([^"]{16})[^"]*","message":"Member ([A-Z]+::[A-Za-z0-9]+)::.*SvOnboardingUnlimitedTrafficTrigger:[^"]*SV=([A-Za-z0-9]+)".*/\1 \3 \2/' | awk '{print substr($1,1,15)"x", $2, $3}' | sort | uniq -c
```
```
    117 2026-11-11T14:1x sv1 MED::sv2SvReonboarding
      2 2026-11-11T14:1x sv1 MED::sv3SvReonboarding
      2 2026-11-11T14:1x sv1 MED::sv4ReonboardSvReonboarding
     17 2026-11-11T14:1x sv1 MED::sv4SvReonboarding
    111 2026-11-11T14:1x sv2 MED::sv2SvReonboarding
      2 2026-11-11T14:1x sv2 MED::sv4ReonboardSvReonboarding
     10 2026-11-11T14:1x sv2 MED::sv4SvReonboarding
    112 2026-11-11T14:1x sv3 MED::sv2SvReonboarding
      5 2026-11-11T14:1x sv3 MED::sv3SvReonboarding
      2 2026-11-11T14:1x sv3 MED::sv4ReonboardSvReonboarding
     10 2026-11-11T14:1x sv3 MED::sv4SvReonboarding
    106 2026-11-11T14:1x sv4 MED::sv2SvReonboarding
      4 2026-11-11T14:1x sv4 MED::sv4SvReonboarding
     54 2026-11-11T14:1x sv4Reonboard MED::sv4ReonboardSvReonboarding
    164 2026-11-11T14:2x sv4Reonboard MED::sv4ReonboardSvReonboarding
```

## 5. The new sv4 sequencer is stuck in onboarding state transfer: no authenticated peer

New sv4 Canton (`canton-standalone-sv4-reonboarding-new.clog.gz`) has no WARN or ERROR apart from the validator
user lookups (`Could not resolve is_deactivated status for user 'sv4_validator_user-3601e0e7'`):

```
zcat canton-standalone-sv4-reonboarding-new.clog.gz | grep -aE '"level":"(WARN|ERROR)"' | grep -av 'Could not resolve is_deactivated' | wc -l
```
```
0
```

Sequencer onboarding (INFO, distinct messages, count and last time):

```
zcat canton-standalone-sv4-reonboarding-new.clog.gz | grep -a 'sequencer=sv4StandaloneSequencer' | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T14:19:03.6" && t<"2026-11-11T14:23") print}' | grep -avE 'Storing topology|Persisted topology|ActiveRequestCounter' | grep -aE '"level":"(INFO|WARN|ERROR)"' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | sed -E 's/\[c\.d\.c\.(\S*):[^]]*\]$/[\1]/' | cut -c1-250 | awk '{k=substr($0,26,50); c[k]++; if(!(k in f)) {f[k]=$0; o[++n]=k}; l[k]=substr($0,1,24)} END{for(i=1;i<=n;i++) print c[o[i]], "last", l[o[i]], f[o[i]]}'
# (selected lines; logger suffixes trimmed to ...)
```
```
1 last 2026-11-11T14:19:05.624Z 2026-11-11T14:19:05.624Z Started P2P gRPC server on port 27410",...
1 last 2026-11-11T14:19:05.625Z 2026-11-11T14:19:05.625Z Starting consensus module (genesis=false, maxNodesContemporarilyAuthenticated=1, p2pEndpointThresholdForConsensusStart=3)",...
1 last 2026-11-11T14:19:05.693Z 2026-11-11T14:19:05.693Z Detected new node onboarding from epoch 81 for sequencer snapshot additional info = ...
1 last 2026-11-11T14:19:05.699Z 2026-11-11T14:19:05.699Z Starting Onboarding state transfer from epoch 81",...
53 last 2026-11-11T14:22:46.887Z 2026-11-11T14:19:05.793Z Not enough authenticated and whitelisted nodes available (1 requested, 1 remaining) among List(SEQ::sv1SvReonboarding::..e4a0, SEQ::sv3SvReonboarding::..8e34, SEQ::sv2SvReonboarding::..1bdf) nor None to send message of type `
1526 last 2026-11-11T14:22:47.977Z 2026-11-11T14:19:05.980Z Using Some(2026-11-11T14:18:55.986943Z) to fetch traffic state with selector LatestSafe",...
```

```
zcat canton-standalone-sv4-reonboarding-new.clog.gz | grep -a 'sv4StandaloneSequencer' | grep -aE 'requesting block transfer|Retrying block transfer|fully transferred|Completed state transfer|Starting state transfer|New epoch [0-9]+ has started' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//' | cut -c1-140 | awk '{k=substr($0,26,40); c[k]++; if(!(k in f)) {f[k]=$0; o[++n]=k}; l[k]=substr($0,1,24)} END{for(i=1;i<=n;i++) print c[o[i]], "last", l[o[i]], f[o[i]]}'
```
```
1 last 2026-11-11T14:19:05.761Z 2026-11-11T14:19:05.761Z Starting state transfer from epoch 81",
55 last 2026-11-11T14:22:46.886Z 2026-11-11T14:19:09.807Z Retrying block transfer request for epoch 81",
```

No completed state transfer and no new epoch on the new sv4 sequencer, so it never processed the block with the
mediator's traffic purchase (sequenced on sv1 at 14:18:57.973, `Updating traffic purchased entry: TrafficPurchased(member = MED::sv4ReonboardSvReonboarding::1220ce14b5f3..., ...)`).

## 6. Peers: ordering topology 3 -> 4 at epoch 82, the new node blacklisted from epoch 83

```
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -aE 'New epoch [0-9]+ has started' | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T14:16:30" && t<"2026-11-11T14:23") print}' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/SvReonboarding::[0-9a-f.]+//g; s/\\n */ /g' | grep -aoE '^[^ ]+ New epoch [0-9]+ has started.{0,10}|leaders = [^)]*\)|size = [0-9]+|blacklist[^)]*\)' | paste -sd' ' | sed 's/ 2026-/\n2026-/g' | cut -c1-120
```
```
2026-11-11T14:17:25.241Z New epoch 62 has started with lead blacklisted nodes = List() size = 4
2026-11-11T14:17:32.207Z New epoch 63 has started with lead blacklisted nodes = List() size = 3
...
2026-11-11T14:18:55.133Z New epoch 81 has started with lead blacklisted nodes = List() size = 3
2026-11-11T14:18:59.282Z New epoch 82 has started with lead blacklisted nodes = List() size = 4
2026-11-11T14:19:04.570Z New epoch 83 has started with lead blacklisted nodes = List(SEQ::sv4Reonboard) size = 4
...
2026-11-11T14:22:42.364Z New epoch 121 has started with lead blacklisted nodes = List(SEQ::sv4Reonboard) size = 4
2026-11-11T14:22:47.955Z New epoch 122 has started with lead blacklisted nodes = List(SEQ::sv4Reonboard) size = 4
```

(size 4 -> 3 at epoch 63: old sv4 offboarded; 3 -> 4 at epoch 82: new sv4 onboarded.)

## 7. Root: the peers keep `localhost:27410` bound to the old sv4 sequencer id

Both sv4 Canton processes use P2P port 27410:

```
for f in canton-standalone-sv4-reonboarding.clog.gz canton-standalone-sv4-reonboarding-new.clog.gz; do zcat $f | grep -a 'Started P2P gRPC server on port' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]+)".*/\1 \2/'; done
zcat canton-standalone-sv4-reonboarding.clog.gz | tail -1 | cut -c1-110
```
```
2026-11-11T14:14:48.788Z Started P2P gRPC server on port 27410
2026-11-11T14:19:05.624Z Started P2P gRPC server on port 27410
{"@timestamp":"2026-11-11T14:17:27.286Z","message":"Shutting down logger. Bye bye.","logger_name":"c.d.canton.Cant
```

On sv1, after the offboarding the operator (sv1 SV app) removed endpoint 27410 at 14:17:21.395, but the old sv4
sequencer, still running, reconnected inbound 0.5 s later and was authenticated again under the old id, which created a
connection-managing actor for `NodeId(SEQ::sv4SvReonboarding::..06d6, localhost:27410)`:

```
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T14:17:20" && t<"2026-11-11T14:23") print}' | grep -aE '27410' | grep -avE 'Known connections|P2P connection state|Sending PBFT|Dropping network|New P2P network status|P2PConnectivityUpdate' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/UUID/g' | cut -c1-330 | awk '{k=substr($0,26,90); c[k]++; if(!(k in f)) {f[k]=$0; o[++n]=k}; l[k]=substr($0,1,24)} END{for(i=1;i<=n;i++) print c[o[i]], "last", l[o[i]], f[o[i]]}'
# (selected lines)
```
```
1 last 2026-11-11T14:17:21.395Z 2026-11-11T14:17:21.395Z Removing P2P endpoint P2PUrl(\"http://localhost:27410\") as requested by operator", INFO
1 last 2026-11-11T14:17:21.403Z 2026-11-11T14:17:21.403Z Disconnecting P2P endpoint P2PUrl(\"http://localhost:27410\") ('SEQ::sv4SvReonboarding::..06d6')", INFO
1 last 2026-11-11T14:17:21.459Z 2026-11-11T14:17:21.459Z Closing network ref 1488125384 for Left(P2PUrl(\"http://localhost:27410\")) as part of connection shutdown (as requested)", INFO
3 last 2026-11-11T14:22:48.149Z 2026-11-11T14:17:21.478Z Disconnected P2P endpoint P2PUrl(\"http://localhost:27410\") is not configured on this node, so not reconnecting to it", INFO
2 last 2026-11-11T14:19:07.138Z 2026-11-11T14:17:21.892Z Found endpoint header and adding it to the gRPC server context: P2PUrl(\"http://localhost:27410\")", INFO
1 last 2026-11-11T14:17:21.904Z 2026-11-11T14:17:21.904Z Authenticated node SEQ::sv4SvReonboarding::..06d6 at P2PUrl(\"http://localhost:27410\"), marking P2P endpoint (if known) as connected and ensuring connectivity to it", INFO
1 last 2026-11-11T14:17:21.904Z 2026-11-11T14:17:21.904Z Creating new network ref for 'NodeId(SEQ::sv4SvReonboarding::..06d6,Some(PlainTextP2PEndpoint(localhost,27410)))'", INFO
```

The same on all three peers (removal by the operator, re-authentication of the old id, then repeated authentication of
the new id and dropped messages to it):

```
for s in sv1 sv2 sv3; do echo "== $s"; zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a "${s}StandaloneSequencer" | grep -aE 'Authenticated node SEQ::sv4|Associating P2P endpoint Some\(P2PUrl\(\\"http://localhost:27410|Dropping network message to unknown SEQ::sv4Reonboard|Removing P2P endpoint P2PUrl\(\\"http://localhost:27410|Adding P2P endpoint.*27410' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/"logger_name.*//' | cut -c1-200 | awk '{k=substr($0,26,75); c[k]++; if(!(k in f)) {f[k]=$0; o[++n]=k}; l[k]=substr($0,1,24)} END{for(i=1;i<=n;i++) print c[o[i]], "last", l[o[i]], f[o[i]]}'; done
# (selected lines)
```
```
== sv1
2 last 2026-11-11T14:17:21.904Z 2026-11-11T14:14:49.638Z Authenticated node SEQ::sv4SvReonboarding::..06d6 at P2PUrl(\"http://localhost:27410\"), ...
1 last 2026-11-11T14:17:21.395Z 2026-11-11T14:17:21.395Z Removing P2P endpoint P2PUrl(\"http://localhost:27410\") as requested by operator",
390 last 2026-11-11T14:22:52.294Z 2026-11-11T14:18:59.358Z Dropping network message to unknown SEQ::sv4ReonboardSvReonboarding::..d4da (possibly unauthenticated as of yet)",
766 last 2026-11-11T14:22:47.827Z 2026-11-11T14:19:07.150Z Authenticated node SEQ::sv4ReonboardSvReonboarding::..d4da at P2PUrl(\"http://localhost:27410\"), ...
== sv2
3 last 2026-11-11T14:17:21.650Z 2026-11-11T14:14:53.564Z Authenticated node SEQ::sv4SvReonboarding::..06d6 at P2PUrl(\"http://localhost:27410\"), ...
1 last 2026-11-11T14:17:21.395Z 2026-11-11T14:17:21.395Z Removing P2P endpoint P2PUrl(\"http://localhost:27410\") as requested by operator",
384 last 2026-11-11T14:22:52.297Z 2026-11-11T14:18:59.286Z Dropping network message to unknown SEQ::sv4ReonboardSvReonboarding::..d4da (possibly unauthenticated as of yet)",
779 last 2026-11-11T14:22:48.061Z 2026-11-11T14:19:06.923Z Authenticated node SEQ::sv4ReonboardSvReonboarding::..d4da at P2PUrl(\"http://localhost:27410\"), ...
== sv3
3 last 2026-11-11T14:17:22.183Z 2026-11-11T14:14:52.132Z Authenticated node SEQ::sv4SvReonboarding::..06d6 at P2PUrl(\"http://localhost:27410\"), ...
1 last 2026-11-11T14:17:21.463Z 2026-11-11T14:17:21.463Z Removing P2P endpoint P2PUrl(\"http://localhost:27410\") as requested by operator",
391 last 2026-11-11T14:22:52.291Z 2026-11-11T14:18:59.359Z Dropping network message to unknown SEQ::sv4ReonboardSvReonboarding::..d4da (possibly unauthenticated as of yet)",
70 last 2026-11-11T14:19:28.351Z 2026-11-11T14:19:07.250Z Authenticated node SEQ::sv4ReonboardSvReonboarding::..d4da at P2PUrl(\"http://localhost:27410\"), ...
```

One cycle on sv1: the new node is authenticated and the endpoint re-associated to it at 14:19:07.150; 105 ms later
a send through the old node's actor flips it back to the old id:

```
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | awk -F'"@timestamp":"' '{t=substr($2,1,23); if(t>="2026-11-11T14:19:07.140" && t<"2026-11-11T14:19:07.300") print}' | grep -aE '27410|sv4Reonboard|sv4SvReonboarding' | grep -avE 'Sending PBFT|Dropping network|P2PConnectivityUpdate|Known connections|P2P connection state|New P2P network status|channel|challenge|authenticate with|token' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/ \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-330
# (selected lines)
```
```
2026-11-11T14:19:07.149Z Found sequencer ID SEQ::sv4ReonboardSvReonboarding::..d4da in the context of the incoming connection", INFO
2026-11-11T14:19:07.150Z Associating P2P endpoint P2PUrl(\"http://localhost:27410\") to BFT node ID SEQ::sv4ReonboardSvReonboarding::..d4da: Endpoint P2PUrl(\"http://localhost:27410\") was previously associated with SEQ::sv4SvReonboarding::..06d6, changing to SEQ::sv4ReonboardSvReonboarding::..d4da", INFO
2026-11-11T14:19:07.150Z Authenticated node SEQ::sv4ReonboardSvReonboarding::..d4da at P2PUrl(\"http://localhost:27410\"), marking P2P endpoint (if known) as connected and ensuring connectivity to it", INFO
2026-11-11T14:19:07.151Z P2P endpoint PlainTextP2PEndpoint(localhost,27410) did not exist in the configured endpoints store, so it was not associated with SEQ::sv4ReonboardSvReonboarding::..d4da", INFO
2026-11-11T14:19:07.255Z Associating P2P endpoint P2PUrl(\"http://localhost:27410\") to BFT node ID SEQ::sv4SvReonboarding::..06d6: Endpoint P2PUrl(\"http://localhost:27410\") was previously associated with SEQ::sv4ReonboardSvReonboarding::..d4da, changing to SEQ::sv4SvReonboarding::..06d6", INFO
2026-11-11T14:19:07.255Z Requested a send but no sender found for NodeId(SEQ::sv4SvReonboarding::..06d6,Some(PlainTextP2PEndpoint(localhost,27410))); if the endpoint is known, ensuring an outgoing connection is established or being established asynchronously", DEBUG
2026-11-11T14:19:07.256Z Connection-managing actor pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-6b137eed-1d8e-4a4f-b476-f7c47befb9c0 couldn't yet obtain connection for `Send`, retrying it in 6257 milliseconds, attempt 2 out of 5", DEBUG
```

Flip count on sv1:

```
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -a 'was previously associated with' | grep -a 27410 | sed -E 's/.*"@timestamp":"([^"]+)".*changing to SEQ::(sv4[A-Za-z]+)::.*/\1 -> \2/' | awk '{c[$3]++; if(!($3 in f)) f[$3]=$1; l[$3]=$1} END{for(k in c) print c[k], "changes to", k, "first", f[k], "last", l[k]}'
```
```
868 changes to sv4SvReonboarding first 2026-11-11T14:19:07.255Z last 2026-11-11T14:22:47.834Z
868 changes to sv4ReonboardSvReonboarding first 2026-11-11T14:19:07.150Z last 2026-11-11T14:22:47.827Z
```

## 8. Duplicate check and history

Not 11347: there sv1 (founding SV) init ran over the first `startAllSync`, dominated by 190 s of DAR uploads, and
the teardown cascade followed. Here the first phase passed, DAR uploads took 30 s, and the teardown closed cleanly
(8 later tests passed).

Searches:

```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "<string>"' --jq '.items[] | "\(.number) \(.state) \(.created_at[0:10]) \(.title[0:120])"'
```
```
## "sv4Reonboard"
8829 closed 2026-06-18 BootstrapPackageConfigIntegrationTest: No wallet found for validator party splitwell    -> other test
3436 closed 2025-04-02 `SvReonboardingIntegrationTest` remote participant after reonboarding not up as quickly as expected   -> pre-BFT, not this
## "Mediator has been granted unlimited traffic"
11323 closed 2026-11-10 GHA Run 42759795825 : job simtime (4)          -> family B rejected-send (MED proposal lost), not this
10076 closed 2026-09-09 SvOnboardingViaNonFoundingSvIntegrationTest    -> 10044 family (fixed canton#35600)
10054 closed 2026-09-07 SvInitializationIntegrationTest: sv3 fails to initialized because Mediator has No traffic state -> 10044 family
10044 closed 2026-09-04 Newly onboarded BFT sequencer before membership takes effect is mistaken for off-boarded node -> other mechanism (fixed)
10049 closed 2026-09-04 BftScanConnectionIntegrationTest               -> 10044 family
3093 closed 2025-03-13 sequencer seems to get stuck in time awaiter    -> old
## "was previously associated with"
(none)
## "SvReonboardingIntegrationTest"
11347 open 2026-11-11 (sv1 init, teardown cascade)                      -> not this, see above
10487 9745 10144 8829 7924 7808 7898 7935 7626 7572 5538 3436 3440 closed -> checkErrors lines, topology, package view; none is this
## "Dropping network message to unknown"
10087 closed 2026-09-10 sv runbook fails to re-onboard, while other svs get the request -> closed as dup of 10011
```

10087 and its parent 10011 are this mechanism on the ciperiodic SV re-onboard runbook:

```
gh api repos/DACH-NY/cn-test-failures/issues/10011 --jq '"#\(.number) \(.state)/\(.state_reason) closed=\(.closed_at) assignees=\([.assignees[].login]|join(","))\n\(.title)"'
gh api "repos/DACH-NY/cn-test-failures/issues/10011/comments?per_page=100" --jq '.[]|"-- \(.user.login) \(.created_at)\n\(.body[0:400])"'
```
```
#10011 closed/completed closed=2026-09-11T08:54:05Z assignees=martinflorian-da
SV Reonboarding test fails to establish CantonBFT p2p connections
-- moritzkiefer-da 2026-09-07T07:18:34Z
What happens in this test is that DA-Helm-Test-Node (namespace `sv`) reonboards so a completely new sequencer id but under the same url. It starts with sequencer id `SEQ::DA-Helm-Test-Node::12207964aa95` and then reonboards under `SEQ::DA-Helm-Test-Node::12201d8ab160`.
...
-- martinflorian-da 2026-09-07T13:30:45Z
... on ciperiodic we offboard the SV first via on-ledger vote; on CILR we just kill the old one and the new one overwrites old entries while re-onboarding
-- moritzkiefer-da 2026-09-11T08:54:05Z
This should now be fixed in Canton 3.6 in https://github.com/DACH-NY/canton/pull/35711 which we upgraded to in https://github.com/canton-network/splice/pull/7264
```

Frequency: key-download (0) on post-merge main since 2026-11-09, 46 jobs in 44 runs (two re-run attempts), 2 failures (11347, this one):

```
for r in $(awk '{print $1}' log/11373/runs.txt); do for p in 1 2 3 4 5 6; do o=$(gh api "repos/canton-network/splice/actions/runs/$r/jobs?filter=all&per_page=40&page=$p"); echo "$o" | jq -r --arg r $r '.jobs[] | select(.name | test(" / (frontend-wall-clock-time \\(2\\)|key-download \\(0\\))$")) | "\($r) \(.id) a=\(.run_attempt) \(.conclusion) \(.started_at) \(.name | sub(".* / ";""))"'; [ "$(echo "$o" | jq '.jobs|length')" -lt 40 ] && break; done; done > log/11373/jobs-fwct2-kd0.txt
# log/11373/runs.txt: post-merge main runs created since 2026-11-09 (workflow 163795441, branch main, 44 runs)
grep 'key-download' log/11373/jobs-fwct2-kd0.txt | awk '{c[$4]++} END{for(k in c) print c[k], k}'
```
```
44 success
2 failure
```

## Verdict

New, not a duplicate of 11347 (different node, different wait, no DAR-upload cost, no teardown cascade). Canton BFT
P2P bug, same symptom as 10011 (closed fixed in Canton 3.6, DACH-NY/canton#35711): when an offboarded sequencer
reconnects after its endpoint was removed, the peers keep a connection actor for its old id, and a re-onboarded
sequencer with a new id on the same endpoint is never usable for them. The splice-side trigger is the test order:
the SVs remove sv4's endpoint after the offboard vote (14:17:21.4) while the old sv4 sequencer still runs until the
`Stop SV4`/destroy clue (14:17:26.7 to 14:17:27.3), and the new sv4 Canton reuses port 27410.

Fix: Canton (BFT orderer `P2PGrpcConnectionState`: a removed endpoint's re-authenticated node should not keep or
regain the endpoint association once another node id authenticates on it). Test mitigation: stop the old sv4 nodes
before the offboard vote is executed, or give the sv4-reonboarding-new Canton a different sequencer P2P port.
Rare: first hit in 46 key-download (0) jobs since 2026-11-09.

Not verified: whether DACH-NY/canton#35711 is in 3.7.3-snapshot.20261109 (no access to DACH-NY/canton) and whether
this is a gap in that fix or a regression; why sv1 keeps sending to the old id after it left the ordering topology
at epoch 63.
