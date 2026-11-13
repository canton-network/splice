# 11363 - SvReonboardingIntegrationTest: re-onboarded sv4 BFT sequencer never authenticated by its peers, sv4Reonboard init over the 5 min startAllSync wait (run 43031077112)

new (not 11347). Post-merge CI on main, sha 644b3ba97a (#8424), job `key-download (0)`, tests 8 passed, 1 failed.
The failing wait is the second `startAllSync` of the test (line 446, sv4Reonboard), not the first one that failed in
11347 (sv1). sv4Reonboard's DAR uploads took 30 s; the time went into `Waiting until Mediator has been granted
unlimited traffic` (14:19:05.970 to the shutdown at 14:22:54.923), which reads the traffic state from sv4's own
newly onboarded BFT sequencer. That sequencer never left its onboarding state transfer from epoch 81, because the
peer sequencers sv1 to sv3 kept their P2P endpoint `localhost:27410` bound to the OLD sv4 sequencer id
(`SEQ::sv4SvReonboarding::..06d6`): each time they authenticated the new node (`SEQ::sv4ReonboardSvReonboarding::..d4da`),
a leftover connection actor for the old id re-associated the endpoint with the old id about 100 ms later (868 flips
each way on sv1, 14:19:07.150 to 14:22:47.834; 865 on sv2 until 14:22:48.081; 74 on sv3 until 14:19:28.369, after
which sv3 kept the endpoint on the old id), so they dropped every message to the new node as `unknown ...
(possibly unauthenticated as of yet)`. The old sv4 had reconnected inbound 0.5 s after the SVs removed its endpoint
(14:17:21.395) and before its Canton process was stopped (14:17:27.286); the new sv4 process reuses P2P port 27410.
Same symptom as 10011 (ciperiodic sv re-onboard; its duplicate 10087: "the other SVs still keep the old sequencer
id"), closed 2026-09-11 with "This should now be fixed in Canton 3.6" (DACH-NY/canton#35711, splice#7264). Teardown closed cleanly this time: no family H cascade.

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
git show 644b3ba97ab4c3cf188eea599a0e53ef3e2cf6ad:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/LocalSynchronizerNode.scala | sed -n '251,258p'
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
63 2026-11-11T14:19:05.982Z 2026-11-11T14:22:51.117Z The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an excepti
1 2026-11-11T14:19:05.970Z 2026-11-11T14:19:05.970Z Waiting until Mediator has been granted unlimited traffic", INFO
63 2026-11-11T14:19:05.982Z 2026-11-11T14:22:51.117Z The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable er
1 2026-11-11T14:22:54.923Z 2026-11-11T14:22:54.923Z Gave up waiting until Mediator has been granted unlimited traffic, as we are shutting down", INFO
62 2026-11-11T14:19:06.183Z 2026-11-11T14:22:51.116Z Now retrying operation 'Check whether Mediator has been granted unlimited traffic'. ", INFO
```
(awk prints the keys in hash order; each failed check logs two lines.)

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
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -aE 'New epoch [0-9]+ has started' | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T14:16:30" && t<"2026-11-11T14:23") print}' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/SvReonboarding::[0-9a-f.]+//g; s/\\n */ /g' | grep -aoE '^[^ ]+ New epoch [0-9]+ has started.{0,10}|leaders = [^)]*\)|size = [0-9]+|blacklist[^)]*\)' | paste -sd' ' | sed 's/ 2026-/\n2026-/g' | sed 's/ blacklistConfig.*//' | cut -c1-120
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

Flip counts on all three peers, and what sv3 did after its flips stopped:

```
for s in sv1 sv2 sv3; do echo "-- $s"; zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a "${s}StandaloneSequencer" | grep -a 'was previously associated with' | grep -a 27410 | sed -E 's/.*"@timestamp":"([^"]+)".*changing to SEQ::(sv4[A-Za-z]+)::.*/\1 -> \2/' | awk '{c[$3]++; if(!($3 in f)) f[$3]=$1; l[$3]=$1} END{for(k in c) print c[k], "changes to", k, "first", f[k], "last", l[k]}'; done
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv3StandaloneSequencer' | grep -a 'already associated with SEQ::sv4SvReonboarding' | grep -a 27410 | awk -F'"@timestamp":"' '{t=substr($2,1,23); if(t>="2026-11-11T14:19:28.300") {n++; if(n==1) f=t; l=t}} END{print n, "first", f, "last", l}'
```
```
-- sv1
868 changes to sv4SvReonboarding first 2026-11-11T14:19:07.255Z last 2026-11-11T14:22:47.834Z
868 changes to sv4ReonboardSvReonboarding first 2026-11-11T14:19:07.150Z last 2026-11-11T14:22:47.827Z
-- sv2
865 changes to sv4SvReonboarding first 2026-11-11T14:19:06.976Z last 2026-11-11T14:22:48.081Z
865 changes to sv4ReonboardSvReonboarding first 2026-11-11T14:19:06.922Z last 2026-11-11T14:22:48.060Z
-- sv3
74 changes to sv4SvReonboarding first 2026-11-11T14:19:07.251Z last 2026-11-11T14:19:28.369Z
74 changes to sv4ReonboardSvReonboarding first 2026-11-11T14:19:07.249Z last 2026-11-11T14:19:28.350Z
32705 first 2026-11-11T14:19:28.339 last 2026-11-11T14:22:48.118
```

So sv3 stopped flipping at 14:19:28.369 but left the endpoint on the old id (`Endpoint P2PUrl(\"http://localhost:27410\")
already associated with SEQ::sv4SvReonboarding::..06d6, no change`, DEBUG, 32705 times) and kept dropping messages to
the new node until 14:22:52.291 (section above).

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

10087 and its parent 10011 are this mechanism on the ciperiodic SV re-onboard runbook. 10087 was closed (completed,
2026-09-11T08:08:15Z) after this comment:

```
gh api repos/DACH-NY/cn-test-failures/issues/10087 --jq '.state_reason, .closed_at'
gh api repos/DACH-NY/cn-test-failures/issues/10087/comments --jq '.[] | select(.user.login=="moritzkiefer-da") | .body' | head -3
```
```
completed
2026-09-11T08:08:15Z
this just looks like a duplicate of https://github.com/DACH-NY/cn-test-failures/issues/10011

It's the same pattern where the other SVs still keep the old sequencer id.
```

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
# log/11373/runs.txt: post-merge main runs created 2026-11-09 to 2026-11-11T22:30Z (workflow 163795441, branch main, 44 runs):
# gh api --paginate "repos/canton-network/splice/actions/workflows/163795441/runs?branch=main&created=2026-11-09..2026-11-12T23:59:59Z&per_page=100" --jq '.workflow_runs[] | "\(.id) \(.created_at) \(.head_sha[0:10]) \(.conclusion) \(.run_attempt)"' | awk '$2<"2026-11-11T22:30"' > log/11373/runs.txt
# (re-run 2026-11-12 by the verifier: same 44 runs, 46 key-download (0) jobs, same counts)
grep 'key-download' log/11373/jobs-fwct2-kd0.txt | awk '{c[$4]++} END{for(k in c) print c[k], k}'
```
```
44 success
2 failure
```

## 9. Canton source at the version that ran (3.7.3-snapshot.20261109 = DACH-NY/canton e6e6c2526f): a teardown race leaks the old id's network ref

The earlier fix is in the run's Canton: #35575 (3.5) fixed the re-onboarding race, #35710 forward-ported it to main
on 2026-09-10 (merge 22360dd655), and the run's commit e6e6c2526f (release line 3.7) is 580 commits ahead of it and 0
behind:

```
# cwd: log/11363
gh api repos/DACH-NY/canton/pulls/35575 --jq '"#\(.number) \(.title) merged=\(.merged_at) base=\(.base.ref)"'; gh api repos/DACH-NY/canton/pulls/35710 --jq '"#\(.number) \(.title) merged=\(.merged_at) sha=\(.merge_commit_sha[0:10]) base=\(.base.ref)"'; gh api repos/DACH-NY/canton/commits/e6e6c2526f0e3ca71b6dcd5e353e303281feeade --jq '"\(.sha[0:10]) \(.commit.committer.date) \(.commit.message|split("\n")[0][0:60])"'; gh api repos/DACH-NY/canton/compare/22360dd65503063962c1c304b65cc64a064386f8...e6e6c2526f0e3ca71b6dcd5e353e303281feeade --jq '"compare 22360dd655...e6e6c2526f: status=\(.status) ahead_by=\(.ahead_by) behind_by=\(.behind_by)"'
```
```
#35575 [3.5] CantonBFT, p2p: fix re-onboarding race merged=2026-09-09T19:30:43Z base=release-line-3.5
#35710 [main forward port] CantonBFT: don't `onNext` if not `isReady` + fix re-onboarding race merged=2026-09-10T09:55:03Z sha=22360dd655 base=main
e6e6c2526f 2026-11-09T16:05:05Z [3.7] CNI-7843 Remove instantiations of `DirectExecutionCont
compare 22360dd655...e6e6c2526f: status=ahead ahead_by=580 behind_by=0
```

Sources fetched at e6e6c2526f (saved under `log/11363/canton-e6e6c252/`, gitignored); all line numbers below are at
that commit, Canton 3.7.3-snapshot.20261109:

```
# cwd: log/11363
P=community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering; for f in bindings/p2p/grpc/P2PGrpcConnectionState.scala bindings/p2p/grpc/P2PGrpcConnectionManager.scala bindings/p2p/grpc/PekkoP2PGrpcNetworking.scala bindings/p2p/grpc/P2PGrpcBftOrderingService.scala core/modules/p2p/P2PNetworkOutModule.scala docs/P2P.md; do gh api "repos/DACH-NY/canton/contents/$P/$f?ref=e6e6c2526f0e3ca71b6dcd5e353e303281feeade" -H 'Accept: application/vnd.github.raw' > canton-e6e6c252/$(basename $f); done; wc -l canton-e6e6c252/* | sed 's/^ *//'
```
```
658 canton-e6e6c252/P2P.md
58 canton-e6e6c252/P2PGrpcBftOrderingService.scala
2221 canton-e6e6c252/P2PGrpcConnectionManager.scala
1086 canton-e6e6c252/P2PGrpcConnectionState.scala
991 canton-e6e6c252/P2PNetworkOutModule.scala
624 canton-e6e6c252/PekkoP2PGrpcNetworking.scala
5638 total
```

### 9.1 What the earlier fix established for incoming connections

When an incoming connection ends, its endpoint association must be removed and its network ref closed, so that the
ref neither re-asserts the association nor re-dials (design doc and code comment at e6e6c2526f, Canton
3.7.3-snapshot.20261109; the doc's arrow characters are shown as `->` and `<->`):

```
# cwd: log/11363/canton-e6e6c252
awk 'NR>=401 && NR<=407 {printf "P2P.md:%d:%s\n", NR, $0}' P2P.md | sed 's/\xe2\x86\x92/->/g; s/\xe2\x86\x94/<->/g'
```
```
P2P.md:401:- **Remote-initiated, incoming** (`onError`/`onCompleted` on an incoming connection's receiver) ->
P2P.md:402:  `shutdownIncomingConnectionDueToRemoteCompletion`: completes the sender and fully clears the
P2P.md:403:  active connection state (`shutdownAndCleanupActiveConnection` -> `clearActiveConnectionState`),
P2P.md:404:  removing the network-ref associations and **closing** the network ref. Incoming connections aren't
P2P.md:405:  managed by this node, so there is nothing to re-establish at the transport level (the peer re-dials
P2P.md:406:  if needed), and a retained ref would wrongly re-assert the transient node<->endpoint association or
P2P.md:407:  keep a dead stream addressable. The endpoint->node-id mapping is also cleared.
```

That cleanup, and a send failure on the same connection, go through two different paths. The incoming-teardown path
looks the connection up by its peer sender (`clearActiveConnectionState`, keyed by `peerSenderToBftNodeId`) and only
then removes the associations and closes the ref. The send-failure path (`handleSendFailure`) removes the sender but
deliberately keeps the association and the ref, because it assumes a managed connection that will be re-established
(Canton 3.7.3-snapshot.20261109, e6e6c2526f):

```
# cwd: log/11363/canton-e6e6c252
awk 'NR>=158 && NR<=163 || NR>=181 && NR<=187 {printf "P2PGrpcConnectionManager.scala:%d:%s\n", NR, $0}' P2PGrpcConnectionManager.scala; awk 'NR>=272 && NR<=289 {printf "P2PGrpcConnectionManager.scala:%d:%s\n", NR, $0}' P2PGrpcConnectionManager.scala; awk 'NR>=1423 && NR<=1438 {printf "P2PGrpcConnectionManager.scala:%d:%s\n", NR, $0}' P2PGrpcConnectionManager.scala
```
```
P2PGrpcConnectionManager.scala:158:  def getPeerSenderOrStartConnection(
P2PGrpcConnectionManager.scala:159:      p2pAddress: P2PAddress
P2PGrpcConnectionManager.scala:160:  )(implicit traceContext: TraceContext): Option[PeerSender] =
P2PGrpcConnectionManager.scala:161:    if (!isClosing) {
P2PGrpcConnectionManager.scala:162:      p2pGrpcConnectionState.associateP2PEndpointIdToBftNodeId(p2pAddress).toOption.flatMap { _ =>
P2PGrpcConnectionManager.scala:163:        val maybeP2PEndpoint = p2pAddress.maybeP2PEndpoint
P2PGrpcConnectionManager.scala:181:          case _ =>
P2PGrpcConnectionManager.scala:182:            logger.debug(
P2PGrpcConnectionManager.scala:183:              s"Requested a send but no sender found for $p2pAddress; if the endpoint is known, " +
P2PGrpcConnectionManager.scala:184:                "ensuring an outgoing connection is established or being established asynchronously"
P2PGrpcConnectionManager.scala:185:            )
P2PGrpcConnectionManager.scala:186:            maybeP2PEndpoint.foreach(connectIfNeeded(_).discard)
P2PGrpcConnectionManager.scala:187:            None
P2PGrpcConnectionManager.scala:272:  // Called by the peer receiver of an incoming connection on error and on completion,
P2PGrpcConnectionManager.scala:273:  //  which also occurs in case of duplicate connection.
P2PGrpcConnectionManager.scala:274:  //  Since incoming connections are not managed by the operator of this node and
P2PGrpcConnectionManager.scala:275:  //  node ID <-> endpoint associations are transient, the latter must be removed and
P2PGrpcConnectionManager.scala:276:  //  the network ref, which for incoming connections is associated to the BFT node ID but
P2PGrpcConnectionManager.scala:277:  //  typically also to the endpoint, must also be closed, so that it neither re-asserts
P2PGrpcConnectionManager.scala:278:  //  the ID <-> endpoint association nor re-establishes the connection.
P2PGrpcConnectionManager.scala:279:  private def shutdownIncomingConnectionDueToRemoteCompletion(
P2PGrpcConnectionManager.scala:280:      peerSender: PeerSender
P2PGrpcConnectionManager.scala:281:  )(implicit traceContext: TraceContext): Unit = {
P2PGrpcConnectionManager.scala:282:    logger.info(
P2PGrpcConnectionManager.scala:283:      s"Shutting down (active or duplicate) incoming connection with peer sender $peerSender"
P2PGrpcConnectionManager.scala:284:    )
P2PGrpcConnectionManager.scala:285:    completeGrpcStreamObserver(peerSender, logger)
P2PGrpcConnectionManager.scala:286:    unregisterServerPeerTransportMetrics(peerSender)
P2PGrpcConnectionManager.scala:287:    p2pGrpcConnectionState
P2PGrpcConnectionManager.scala:288:      .shutdownAndCleanupActiveConnectionAndReturnEndpointIds(peerSender)
P2PGrpcConnectionManager.scala:289:      .foreach(notifyEndpointDisconnection)
P2PGrpcConnectionManager.scala:1423:  private[grpc] def handleSendFailure(
P2PGrpcConnectionManager.scala:1424:      p2pAddressId: P2PAddress.Id,
P2PGrpcConnectionManager.scala:1425:      peerSender: PeerSender,
P2PGrpcConnectionManager.scala:1426:      exception: Exception,
P2PGrpcConnectionManager.scala:1427:  )(implicit traceContext: TraceContext): Unit = {
P2PGrpcConnectionManager.scala:1428:    // Failing the stream in case of an actual onNext exception
P2PGrpcConnectionManager.scala:1429:    //  is required by the gRPC streaming API
P2PGrpcConnectionManager.scala:1430:    failGrpcStreamObserver(peerSender, exception, logger)
P2PGrpcConnectionManager.scala:1431:    // gRPC requires onError to be the last event, so the connection must be invalidated even though
P2PGrpcConnectionManager.scala:1432:    //  the send operation will be retried.
P2PGrpcConnectionManager.scala:1433:    shutdownConnection(
P2PGrpcConnectionManager.scala:1434:      p2pAddressId,
P2PGrpcConnectionManager.scala:1435:      clearNetworkRefAssociations = false,
P2PGrpcConnectionManager.scala:1436:      closeNetworkRefs = false,
P2PGrpcConnectionManager.scala:1437:    )
P2PGrpcConnectionManager.scala:1438:  }
```
```
# cwd: log/11363/canton-e6e6c252
awk 'NR>=864 && NR<=877 || NR>=884 && NR<=892 {printf "P2PGrpcConnectionState.scala:%d:%s\n", NR, $0}' P2PGrpcConnectionState.scala; awk 'NR>=464 && NR<=472 || NR>=771 && NR<=779 {printf "P2PGrpcConnectionState.scala:%d:%s\n", NR, $0}' P2PGrpcConnectionState.scala
```
```
P2PGrpcConnectionState.scala:864:    // Completely clear the state for a connection with a sender (i.e., active); this is used to clean up
P2PGrpcConnectionState.scala:865:    //  incoming P2P connections that are closed by the counterparty.
P2PGrpcConnectionState.scala:866:    def clearActiveConnectionState(
P2PGrpcConnectionState.scala:867:        peerSender: PeerSender
P2PGrpcConnectionState.scala:868:    ): (
P2PGrpcConnectionState.scala:869:        State,
P2PGrpcConnectionState.scala:870:        ResultWithLogs[
P2PGrpcConnectionState.scala:871:          (State, State, Option[P2PNetworkRef[BftOrderingMessage]], Seq[P2PEndpoint.Id])
P2PGrpcConnectionState.scala:872:        ],
P2PGrpcConnectionState.scala:873:    ) =
P2PGrpcConnectionState.scala:874:      peerSenderToBftNodeId
P2PGrpcConnectionState.scala:875:        .get(peerSender)
P2PGrpcConnectionState.scala:876:        .fold {
P2PGrpcConnectionState.scala:877:          this -> ResultWithLogs(
P2PGrpcConnectionState.scala:884:            Level.DEBUG -> (() =>
P2PGrpcConnectionState.scala:885:              s"Not removing connection state for $peerSender because it does not exist yet " +
P2PGrpcConnectionState.scala:886:                "(or possibly removed as duplicate)"
P2PGrpcConnectionState.scala:887:            ),
P2PGrpcConnectionState.scala:888:          )
P2PGrpcConnectionState.scala:889:        } { bftNodeId =>
P2PGrpcConnectionState.scala:890:          val ResultWithLogs((updatedState1, networkRefO), logs1*) =
P2PGrpcConnectionState.scala:891:            cleanupNetworkRef(bftNodeId, clearNetworkRefAssociations = true, closeNetworkRef = true)
P2PGrpcConnectionState.scala:892:          val (updatedState2, ResultWithLogs((_, _, endpointIds), logs2*)) =
P2PGrpcConnectionState.scala:464:              case Some(previousBftNodeId) =>
P2PGrpcConnectionState.scala:465:                if (previousBftNodeId == bftNodeId) {
P2PGrpcConnectionState.scala:466:                  annotation =
P2PGrpcConnectionState.scala:467:                    s"Endpoint $p2pEndpointId already associated with $previousBftNodeId, no change"
P2PGrpcConnectionState.scala:468:                } else {
P2PGrpcConnectionState.scala:469:                  annotation =
P2PGrpcConnectionState.scala:470:                    s"Endpoint $p2pEndpointId was previously associated with $previousBftNodeId, changing to $bftNodeId"
P2PGrpcConnectionState.scala:471:                  logLevel = Level.INFO
P2PGrpcConnectionState.scala:472:                  result = Right(true)
P2PGrpcConnectionState.scala:771:      // Prioritize the network ref already associated to the node ID (potentially an incoming connection), if any.
P2PGrpcConnectionState.scala:772:      val maybeExistingNetworkRefAssociatedToNodeIdOrElseEndpoint =
P2PGrpcConnectionState.scala:773:        bftNodeIdToNetworkRef
P2PGrpcConnectionState.scala:774:          .get(bftNodeId)
P2PGrpcConnectionState.scala:775:          .orElse(
P2PGrpcConnectionState.scala:776:            p2pEndpointIds.view
P2PGrpcConnectionState.scala:777:              .flatMap(p2pEndpointIdToNetworkRef.get)
P2PGrpcConnectionState.scala:778:              .headOption
P2PGrpcConnectionState.scala:779:          )
```

So if the send-failure path removes the sender first, the incoming-teardown path finds no sender
(`P2PGrpcConnectionState.scala:874-886`) and does nothing: the association and the ref survive, against the rule above.

### 9.2 That race ran on all three peers at 14:17:27, when the old sv4 sequencer stopped

On each peer, the old sv4's incoming stream was cancelled (old sv4 Canton stopping), the incoming-teardown path
started, then a send of the old id's connection-managing actor failed on the same, already cancelled sender,
the send-failure path removed the sender (`Right(<old id>)`, keep associations, keep ref), and 4 to 11 ms
later the incoming-teardown path found nothing to clean up:

```
# cwd: log/11363/logs-key-download-0
for s in sv1 sv2 sv3; do echo "== $s"; zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a "${s}StandaloneSequencer" | awk -F'"@timestamp":"' '{t=substr($2,1,19); if(t>="2026-11-11T14:17:27" && t<"2026-11-11T14:17:28") print}' | grep -a -E 'Received error \(CANCELLED[^)]*\) from .<unknown \(incoming|Shutting down \(active or duplicate\) incoming|failed sending message|Shutting down asynchronously any incoming or outgoing connection to Right\(SEQ::sv4SvReonboarding|Not removing connection state for PeerSender' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//; s/1220[0-9a-f]{60}/../g; s/pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-[0-9a-f-]{36}/<actor 27410 old id>/; s/failed sending message .*/failed sending message .../' | cut -c1-190; done
```
```
== sv1
2026-11-11T14:17:27.195Z Received error (CANCELLED: client cancelled) from '<unknown (incoming connection, receiver: 941509528)>', invalidating connection and shutting down the gRPC channel"
2026-11-11T14:17:27.195Z Shutting down (active or duplicate) incoming connection with peer sender PeerSender(855227083)",
2026-11-11T14:17:27.197Z Connection-managing actor <actor 27410 old id> failed sending message ...
2026-11-11T14:17:27.198Z Shutting down asynchronously any incoming or outgoing connection to Right(SEQ::sv4SvReonboarding::..06d6)",
2026-11-11T14:17:27.209Z Shutting down and cleaning up active connection of sender PeerSender(855227083): Not removing connection state for PeerSender(855227083) because it does not exist ye
== sv2
2026-11-11T14:17:27.195Z Received error (CANCELLED: client cancelled) from '<unknown (incoming connection, receiver: 760727462)>', invalidating connection and shutting down the gRPC channel"
2026-11-11T14:17:27.196Z Shutting down (active or duplicate) incoming connection with peer sender PeerSender(990921772)",
2026-11-11T14:17:27.198Z Connection-managing actor <actor 27410 old id> failed sending message ...
2026-11-11T14:17:27.199Z Shutting down asynchronously any incoming or outgoing connection to Right(SEQ::sv4SvReonboarding::..06d6)",
2026-11-11T14:17:27.207Z Shutting down and cleaning up active connection of sender PeerSender(990921772): Not removing connection state for PeerSender(990921772) because it does not exist ye
== sv3
2026-11-11T14:17:27.195Z Received error (CANCELLED: client cancelled) from '<unknown (incoming connection, receiver: 843279243)>', invalidating connection and shutting down the gRPC channel"
2026-11-11T14:17:27.196Z Shutting down (active or duplicate) incoming connection with peer sender PeerSender(1220727430)",
2026-11-11T14:17:27.203Z Connection-managing actor <actor 27410 old id> failed sending message ...
2026-11-11T14:17:27.203Z Shutting down asynchronously any incoming or outgoing connection to Right(SEQ::sv4SvReonboarding::..06d6)",
2026-11-11T14:17:27.207Z Shutting down and cleaning up active connection of sender PeerSender(1220727430): Not removing connection state for PeerSender(1220727430) because it does not exist 
```

State on sv1 before and after the send-failure teardown (`false, false` = keep associations, keep ref; peer-sender
maps elided by the sed; `A6b13` = the old id's actor `...-6b137eed-...`): the endpoint stays bound to the old id and
the old ref stays registered for both the old id and the endpoint, although the operator had removed the endpoint at
14:17:21.395 (section 7):

```
# cwd: log/11363/logs-key-download-0
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -a -E '2026-11-11T14:17:27\.199Z.*Relevant P2P connection state before and after .shutdownConnectionAndReturnPeerSender|2026-11-11T14:19:07\.150Z.*Relevant P2P connection state before and after .associateP2PEndpointIdToBftNodeId' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//; s/1220[0-9a-f]{60}/../g; s/\\n */ /g; s/pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-6b137eed-1d8e-4a4f-b476-f7c47befb9c0/A6b13/g; s/bftNodeIdToPeerSender = [^,]*, peerSenderToBftNodeId = [^,]*, //g; s/PekkoP2PNetworkRef\(A6b13\)/ref(A6b13)/g' | fold -w 118
```
```
2026-11-11T14:17:27.199Z Relevant P2P connection state before and after `shutdownConnectionAndReturnPeerSender(Right(S
EQ::sv4SvReonboarding::..06d6), false, false)`: BeforeAndAfter( before = State( p2pEndpointIdToBftNodeId = P2PUrl(\"ht
tp://localhost:27410\") -> \"SEQ::sv4SvReonboarding::..06d6\", bftNodeIdToNetworkRef = \"SEQ::sv4SvReonboarding::..06d
6\" -> ref(A6b13), p2pEndpointIdToNetworkRef = P2PUrl(\"http://localhost:27410\") -> ref(A6b13) ), after = State( p2pE
ndpointIdToBftNodeId = P2PUrl(\"http://localhost:27410\") -> \"SEQ::sv4SvReonboarding::..06d6\", bftNodeIdToNetworkRef
 = \"SEQ::sv4SvReonboarding::..06d6\" -> ref(A6b13), p2pEndpointIdToNetworkRef = P2PUrl(\"http://localhost:27410\") ->
 ref(A6b13) ) )",
2026-11-11T14:19:07.150Z Relevant P2P connection state before and after `associateP2PEndpointIdToBftNodeId(P2PUrl(\"ht
tp://localhost:27410\"), SEQ::sv4ReonboardSvReonboarding::..d4da)`: BeforeAndAfter( before = State( p2pEndpointIdToBft
NodeId = P2PUrl(\"http://localhost:27410\") -> \"SEQ::sv4SvReonboarding::..06d6\", bftNodeIdToNetworkRef = Map(), p2pE
ndpointIdToNetworkRef = P2PUrl(\"http://localhost:27410\") -> ref(A6b13) ), after = State( p2pEndpointIdToBftNodeId = 
P2PUrl(\"http://localhost:27410\") -> \"SEQ::sv4ReonboardSvReonboarding::..d4da\", bftNodeIdToNetworkRef = \"SEQ::sv4R
eonboardSvReonboarding::..d4da\" -> ref(A6b13), p2pEndpointIdToNetworkRef = P2PUrl(\"http://localhost:27410\") -> ref(
A6b13) ) )",
```

The leaked ref is the actor created when the old node re-authenticated inbound at 14:17:21.904 (section 7); the
actor of its earlier connection was correctly closed by the operator removal at 14:17:21.46. The leaked actor lived
until the shutdown and kept dialing the removed endpoint (`P2PGrpcConnectionManager.scala:186`: a missing sender
triggers `connectIfNeeded` for the actor's endpoint, configured or not):

```
# cwd: log/11363/logs-key-download-0
for u in 3036bc71 6b137eed; do zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -a "27410-plaintext-SEQ::sv4SvReonboarding::[0-9a-f.]*-$u" | grep -aoE '"@timestamp":"[^"]+' | cut -c14- | sed -n '1p;$p' | paste -sd' ' | sed "s/^/actor $u first,last: /"; done; zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -a 'Closing connection-managing actor pekko-p2p-grpc-connection-managing-actor-localhost-27410' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//; s/1220[0-9a-f]{60}/../g'; zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -a 'when attempting to start outgoing connection: Disconnected (not in state) -> Connecting' | grep -a 27410 | grep -aoE '"@timestamp":"[^"]+' | cut -c14- | awk '{n++; if(n==1) f=$0; l=$0} END{print "outgoing dial attempts to 27410:", n, "first", f, "last", l}'
```
```
actor 3036bc71 first,last: "2026-11-11T14:14:49.638Z "2026-11-11T14:17:21.465Z
actor 6b137eed first,last: "2026-11-11T14:17:21.904Z "2026-11-11T14:22:48.148Z
2026-11-11T14:17:21.461Z Closing connection-managing actor pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-3036bc71-2ef8-44fa-9995-8c496a4176a9",
2026-11-11T14:17:21.465Z Closing connection-managing actor pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-3036bc71-2ef8-44fa-9995-8c496a4176a9",
2026-11-11T14:22:48.147Z Closing connection-managing actor pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-6b137eed-1d8e-4a4f-b476-f7c47befb9c0",
2026-11-11T14:22:48.148Z Closing connection-managing actor pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-6b137eed-1d8e-4a4f-b476-f7c47befb9c0",
outgoing dial attempts to 27410: 795 first "2026-11-11T14:17:27.212Z last "2026-11-11T14:22:47.834Z
```

### 9.3 The new sv4 id inherits the leaked ref, and every send through it re-binds the endpoint to the old id

When the new sv4 node authenticates (`P2PNetworkOutModule.scala:202-210` -> `ensureSendingEnabledTo`), the endpoint is
re-associated to the new id (allowed, `P2PGrpcConnectionState.scala:464-472`), and `consolidateNetworkRefs` gives the
new id the ref already registered for the endpoint, i.e. the leaked old-id actor
(`P2PGrpcConnectionState.scala:771-779`); `ensureSendingEnabledTo` then does not create a ref of its own
(Canton 3.7.3-snapshot.20261109, e6e6c2526f):

```
# cwd: log/11363/canton-e6e6c252
awk 'NR>=202 && NR<=210 || NR>=797 && NR<=810 {printf "P2PNetworkOutModule.scala:%d:%s\n", NR, $0}' P2PNetworkOutModule.scala; awk 'NR>=308 && NR<=312 {printf "PekkoP2PGrpcNetworking.scala:%d:%s\n", NR, $0}' PekkoP2PGrpcNetworking.scala
```
```
P2PNetworkOutModule.scala:202:      case P2PNetworkOut.Network.Authenticated(bftNodeId, maybeP2PEndpoint) =>
P2PNetworkOutModule.scala:203:        val maybeP2PEndpointId = maybeP2PEndpoint.map(_.id)
P2PNetworkOutModule.scala:204:        val p2pEndpointIdString = maybeP2PEndpointId.map(_.toString).getOrElse("<unknown>")
P2PNetworkOutModule.scala:205:        logger.info(
P2PNetworkOutModule.scala:206:          s"Authenticated node $bftNodeId at $p2pEndpointIdString, marking P2P endpoint (if known) as connected " +
P2PNetworkOutModule.scala:207:            "and ensuring connectivity to it"
P2PNetworkOutModule.scala:208:        )
P2PNetworkOutModule.scala:209:        maybeP2PEndpointId.foreach(connectedP2PEndpointIds.add(_).discard)
P2PNetworkOutModule.scala:210:        ensureSendingEnabledTo(P2PAddress.NodeId(bftNodeId, maybeP2PEndpoint))
P2PNetworkOutModule.scala:797:      p2pAddress: P2PAddress
P2PNetworkOutModule.scala:798:  )(implicit
P2PNetworkOutModule.scala:799:      context: E#ActorContextT[P2PNetworkOut.Message],
P2PNetworkOutModule.scala:800:      traceContext: TraceContext,
P2PNetworkOutModule.scala:801:  ): Unit =
P2PNetworkOutModule.scala:802:    p2pConnectionState.associateP2PEndpointIdToBftNodeId(p2pAddress).foreach { _ =>
P2PNetworkOutModule.scala:803:      p2pConnectionState.addNetworkRefIfMissing(p2pAddress.id) { () =>
P2PNetworkOutModule.scala:804:        logger.info(
P2PNetworkOutModule.scala:805:          s"Not creating new network ref for '$p2pAddress' as it already exists"
P2PNetworkOutModule.scala:806:        )
P2PNetworkOutModule.scala:807:      } { () =>
P2PNetworkOutModule.scala:808:        logger.info(s"Creating new network ref for '$p2pAddress'")
P2PNetworkOutModule.scala:809:        p2pNetworkManager.createNetworkRef(context, p2pAddress)
P2PNetworkOutModule.scala:810:      }
PekkoP2PGrpcNetworking.scala:308:        implicit val traceContext: TraceContext = message.traceContext
PekkoP2PGrpcNetworking.scala:309:
PekkoP2PGrpcNetworking.scala:310:        connectionManager.getPeerSenderOrStartConnection(p2pAddress) match {
PekkoP2PGrpcNetworking.scala:311:
PekkoP2PGrpcNetworking.scala:312:          case Some(peerSender) =>
```

The 14:19:07.150 state line in the output of 9.2 shows exactly this (`bftNodeIdToNetworkRef = <new id> -> ref(A6b13)`),
and 1 ms later the module confirms it does not create a ref for the new id. A send to the new id now goes to the old
id's actor, whose fixed address is `NodeId(<old id>, localhost:27410)`; on every attempt the actor calls
`getPeerSenderOrStartConnection` with that address (`PekkoP2PGrpcNetworking.scala:310`), which first re-associates the
endpoint to the old id (`P2PGrpcConnectionManager.scala:162`) and then finds no sender for the old id, so the message
is retried and finally dropped; the new node's own sender (`PeerSender(230968034)`, section 7) is never used:

```
# cwd: log/11363/logs-key-download-0
zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a 'sv1StandaloneSequencer' | grep -a -E '2026-11-11T14:19:07\.(15[01]|25[56])Z' | grep -a -E 'Not creating new network ref|Requested a send but no sender found|couldn.t yet obtain connection for .Send.' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/"logger_name.*//; s/1220[0-9a-f]{60}/../g; s/pekko-p2p-grpc-connection-managing-actor-localhost-27410-plaintext-SEQ::sv4SvReonboarding::..06d6-6b137eed-1d8e-4a4f-b476-f7c47befb9c0/<actor 27410 old id, 6b137eed>/' | cut -c1-250
```
```
2026-11-11T14:19:07.151Z Not creating new network ref for 'NodeId(SEQ::sv4ReonboardSvReonboarding::..d4da,Some(PlainTextP2PEndpoint(localhost,27410)))' as it already exists",
2026-11-11T14:19:07.255Z Requested a send but no sender found for NodeId(SEQ::sv4SvReonboarding::..06d6,Some(PlainTextP2PEndpoint(localhost,27410))); if the endpoint is known, ensuring an outgoing connection is established or being established asynch
2026-11-11T14:19:07.256Z Connection-managing actor <actor 27410 old id, 6b137eed> couldn't yet obtain connection for `Send`, retrying it in 6257 milliseconds, attempt 2 out of 5",
```

The volume of sends through the old id's actor matches this: after the old node stopped, only the retries of messages
queued for it run out (the old id left the ordering topology at epoch 63, section 6); from 14:19, when the new id
shares the actor, it jumps to the traffic for a live peer:

```
# cwd: log/11363/logs-key-download-0
for s in sv1 sv3; do echo "== $s requests to send to the old id, per minute"; zcat canton-standalone-sv123-reonboarding.clog.gz | grep -a "${s}StandaloneSequencer" | grep -a 'Requested a send but no sender found for NodeId(SEQ::sv4SvReonboarding' | grep -aoE '"@timestamp":"[^"]{16}' | cut -c25- | uniq -c | awk '{printf "%s=%s ", $2, $1}'; echo; done
```
```
== sv1 requests to send to the old id, per minute
T14:17=399 T14:18=165 T14:19=5598 T14:20=10277 T14:21=10225 T14:22=8347 
== sv3 requests to send to the old id, per minute
T14:17=389 T14:18=160 T14:19=5518 T14:20=10188 T14:21=10244 T14:22=8204 
```

### 9.4 Gap in the earlier fix, not a regression

The two code paths are the same at the forward-port commit 22360dd655 and at e6e6c2526f: `handleSendFailure` keeps
associations and refs, and `clearActiveConnectionState` is keyed by the peer sender:

```
# cwd: log/11363
mkdir -p canton-22360dd655; P=community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering/bindings/p2p/grpc; for f in P2PGrpcConnectionManager.scala P2PGrpcConnectionState.scala; do gh api "repos/DACH-NY/canton/contents/$P/$f?ref=22360dd65503063962c1c304b65cc64a064386f8" -H 'Accept: application/vnd.github.raw' > canton-22360dd655/$f; done; for d in canton-22360dd655 canton-e6e6c252; do echo "== $d"; grep -n -A13 'private\[grpc\] def handleSendFailure' $d/P2PGrpcConnectionManager.scala | grep -E 'clearNetworkRefAssociations|closeNetworkRefs'; n=$(grep -n 'def clearActiveConnectionState' $d/P2PGrpcConnectionState.scala | cut -d: -f1); sed -n "$n,$((n+12))p" $d/P2PGrpcConnectionState.scala | grep -n -E 'def clearActiveConnectionState|peerSenderToBftNodeId|\.get\(peerSender\)' | sed "s/^/  (+offset from line $n) /"; done
```
```
== canton-22360dd655
1274-      clearNetworkRefAssociations = false,
1275-      closeNetworkRefs = false,
  (+offset from line 840) 1:    def clearActiveConnectionState(
  (+offset from line 840) 9:      peerSenderToBftNodeId
  (+offset from line 840) 10:        .get(peerSender)
== canton-e6e6c252
1435-      clearNetworkRefAssociations = false,
1436-      closeNetworkRefs = false,
  (+offset from line 866) 1:    def clearActiveConnectionState(
  (+offset from line 866) 9:      peerSenderToBftNodeId
  (+offset from line 866) 10:        .get(peerSender)
```

Commits on these four files between the forward port (2026-09-10) and e6e6c2526f; none changes either path (titles
below; 2481825e6e is the 3.7 backport that makes an operator endpoint removal also drop the in-memory association,
which worked here at 14:17:21.4, section 7):

```
# cwd: log/11363
P=community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering; for f in bindings/p2p/grpc/P2PGrpcConnectionState.scala bindings/p2p/grpc/P2PGrpcConnectionManager.scala bindings/p2p/grpc/PekkoP2PGrpcNetworking.scala core/modules/p2p/P2PNetworkOutModule.scala; do echo "== $(basename $f)"; gh api "repos/DACH-NY/canton/commits?sha=e6e6c2526f0e3ca71b6dcd5e353e303281feeade&path=$P/$f&since=2026-09-10T09:55:03Z&per_page=50" --jq '.[] | "\(.sha[0:10]) \(.commit.committer.date[0:10]) \(.commit.message|split("\n")[0][0:105])"'; done
```
```
== P2PGrpcConnectionState.scala
2481825e6e 2026-10-27 [3.7 backport] CantonBFT, p2p: ensure that, when the operator removes an endpoint, the in-memory associat
33aa14d159 2026-10-16 [3.7 backport] CantonBFT, P2P: fix the connection direction reporting in metrics and admin (#36840)
b16fc970ff 2026-09-23 [main forward port] CantonBFT, p2p: fix network ref creation off actor thread (#36085) (#36136)
59aff642a0 2026-09-17 [main forward port] CantonBFT, p2p: avoid identity equivocation warnings and self-connects when the node 
01f64b3cc6 2026-09-11 Use companion pretty instances in community/synchronizer (#35779)
== P2PGrpcConnectionManager.scala
aa87f1131f 2026-10-19 [3.7 backport] Propagate client-created trace ids to gRPC server handlers (#36875)
33aa14d159 2026-10-16 [3.7 backport] CantonBFT, P2P: fix the connection direction reporting in metrics and admin (#36840)
f2c2bae878 2026-10-02 [main forward port] CantonBFT, p2p, transport metrics: add transport (i.e. HTTP/2- and TCP-level) metrics
59aff642a0 2026-09-17 [main forward port] CantonBFT, p2p: avoid identity equivocation warnings and self-connects when the node 
9932202424 2026-09-16 [main forward port] CantonBFT, p2p, flow control: set default that works for state transfer and fix disab
01f64b3cc6 2026-09-11 Use companion pretty instances in community/synchronizer (#35779)
== PekkoP2PGrpcNetworking.scala
8e9912c9c1 2026-10-09 CantonBFT, benchmark: support losing messages (#36635)
b16fc970ff 2026-09-23 [main forward port] CantonBFT, p2p: fix network ref creation off actor thread (#36085) (#36136)
== P2PNetworkOutModule.scala
3573d1b5fd 2026-10-28 [3.7 forward port] CantonBFT: improve serialization performance and log of oversized gRPC messages (#3722
2481825e6e 2026-10-27 [3.7 backport] CantonBFT, p2p: ensure that, when the operator removes an endpoint, the in-memory associat
```

Not verified: the diffs of these commits were not read line by line, only the two paths above compared; why sv3
stopped flipping at 14:19:28 and kept the old id; when exactly the peers log `Dropping network message to unknown
<new id>` (no ref for the new id at that moment) rather than routing through the old actor.

Canton fix (owner: Canton BFT P2P): make the incoming-teardown cleanup independent of which path removes the sender
first, e.g. in `handleSendFailure` treat an incoming sender like `shutdownIncomingConnectionDueToRemoteCompletion`
(clear associations, close the ref), or let `clearActiveConnectionState` fall back to the node id the sender was bound
to when the sender is already gone. Defence in depth: `consolidateNetworkRefs` should not hand a new node id a ref that
was created for a different node id (`P2PGrpcConnectionState.scala:771-779`).

## Verdict

New, not a duplicate of 11347 (different node, different wait, no DAR-upload cost, no teardown cascade). Canton BFT
P2P bug, same symptom as 10011 (closed 2026-09-11 as "should now be fixed in Canton 3.6", DACH-NY/canton#35711): when an offboarded sequencer
reconnects after its endpoint was removed, the peers keep a connection actor for its old id, and a re-onboarded
sequencer with a new id on the same endpoint is never usable for them. The splice-side trigger is the test order:
the SVs remove sv4's endpoint after the offboard vote (14:17:21.4) while the old sv4 sequencer still runs until the
`Stop SV4`/destroy clue (14:17:26.7 to 14:17:27.3), and the new sv4 Canton reuses port 27410.

Fix: Canton BFT P2P (section 9): make the cleanup of an incoming connection independent of whether the send-failure
path (`handleSendFailure`, `P2PGrpcConnectionManager.scala:1423-1438` at e6e6c2526f) removed its sender first, and do
not let `consolidateNetworkRefs` give a new node id a ref created for another node id. Test mitigation: stop the old sv4 nodes
before the offboard vote is executed, or give the sv4-reonboarding-new Canton a different sequencer P2P port.
Rare: first hit in 46 key-download (0) jobs since 2026-11-09.

Established in section 9 (DACH-NY/canton read access from 2026-11-12): the earlier fix (#35575, main #35710) is in
the run's Canton, and this is a gap in it, not a regression. When the old sv4 sequencer stopped (14:17:27.195), a
consensus send on its cancelled incoming connection failed first, and the send-failure teardown removed the sender but
kept the endpoint association and the old id's network ref; the incoming-connection teardown then found no sender and
did nothing. The leaked ref was later handed to the new sv4 id for the same endpoint, and each send through it
re-binds the endpoint to the old id. This also answers why sv1 keeps sending to the old id: the sends are for the new
id. Not verified: see the end of section 9.

Possibly related (not verified): 11379, CircleCI deploy_lsu_operator on ciperiodic-lsu (pipeline 88832, job 531400),
where per martinflorian-da the SV re-onboarding after the LSU "got stuck around initializing the sequencer". It would be
this mechanism if the GCP logs (project da-cn-ci, cluster cn-ciperiodicnet, 2026-11-12 01:12 to 01:33 UTC) show, on the
re-onboarded SV's sequencer, `Starting Onboarding state transfer from epoch N` followed only by `Retrying block transfer
request for epoch N` and `Not enough authenticated and whitelisted nodes available`, and on the other SVs' sequencers
`Associating P2P endpoint ... was previously associated with SEQ::<old id>, changing to ...` alternating between the
old and the new sequencer id of that SV, plus `Dropping network message to unknown SEQ::<new id> (possibly
unauthenticated as of yet)`. If the re-onboarded sequencer after the LSU uses a different P2P endpoint than the old one,
or the old sequencer was gone before its endpoint was removed, the mechanism here does not apply.

## 10. Source permalinks in the issue comment (DACH-NY/canton e6e6c2526f0e3ca71b6dcd5e353e303281feeade, 3.7.3-snapshot.20261109)

Local copies in `log/11363/canton-e6e6c252/` are byte-identical to the contents API at that ref (`cmp`, 2026-11-12:
`same P2P.md`, `same P2PGrpcConnectionState.scala`, `same P2PGrpcConnectionManager.scala`, `same PekkoP2PGrpcNetworking.scala`).
Linked ranges, all under `community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering/`:
- `bindings/p2p/grpc/P2PGrpcConnectionManager.scala#L1423-L1438` (`handleSendFailure`, `clearNetworkRefAssociations = false`, `closeNetworkRefs = false`)
- `bindings/p2p/grpc/P2PGrpcConnectionManager.scala#L272-L290` (comment and `shutdownIncomingConnectionDueToRemoteCompletion`)
- `bindings/p2p/grpc/P2PGrpcConnectionState.scala#L864-L891` (`clearActiveConnectionState`, the `Not removing connection state` branch, then `cleanupNetworkRef(..., closeNetworkRef = true)`)
- `docs/P2P.md?plain=1#L401-L407` (Remote-initiated, incoming: clear the state and close the network ref)
- `bindings/p2p/grpc/P2PGrpcConnectionState.scala#L762-L779` (`consolidateNetworkRefs`, prefers the ref already registered for the node id or endpoint)
- `bindings/p2p/grpc/PekkoP2PGrpcNetworking.scala#L310` (`connectionManager.getPeerSenderOrStartConnection(p2pAddress) match`)
- `bindings/p2p/grpc/P2PGrpcConnectionManager.scala#L158-L189` (`getPeerSenderOrStartConnection`: line 162 `associateP2PEndpointIdToBftNodeId(p2pAddress)`, line 186 `connectIfNeeded`)
Each path returns `file` from `gh api repos/DACH-NY/canton/contents/<path>?ref=e6e6c2526f0e...` (2026-11-12).
