# 11289 - simtime (3) SvTimeBasedRoundMgmtIntegrationTest setup: MED::sv4 proposal rejected at the BFT 1 -> 4 switch, sv4Validator JVM exit (run 42618450792)

DUPLICATE of 11255 (BFT ordering-topology onboarding window), rejected-send shape (as 11255 itself and 11288). Post-merge
main 38a37d9680, canton 3.7.2, 2026-11-09. No test report: the first suite of the shard,
SvTimeBasedRoundMgmtIntegrationTest "round management", was still in environment setup (initDso) after 7m26s when
sv4Validator gave up waiting for `sv4_validator_user` and called `sys.exit(1)` in the shared sbt JVM. Mechanism: at the
switch of the global ordering topology from 1 to 4 sequencers (epoch 14, 12:55:05.241) the MediatorSynchronizerState
proposal adding MED::sv4 (TxHash 914d918db795) was broadcast by all four SV participants; sv1's broadcast went out
0.2 s before the switch and was sequenced, the broadcasts of sv3, sv4 and sv2 (12:55:05.262 to .435) were rejected
with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`. The participants treat those
sends as possibly in flight until max sequencing time 00:00:20 on the sim clock, which stood at 00:00:02.081 at the
end, so their outbox flush cycles never completed and every re-proposal (12:57, 12:59) only hit `Another flush cycle
is currently ongoing`. The proposal never got more than sv1's signature on the synchronizer, MED::sv4 was never added,
the sv4 SV app waited from 12:55:09 for its mediator's traffic state, never reached `Initialize validator` (which
creates `sv4_validator_user`), and sv4Validator's 120 retries of `Get user` ran out at 12:59:41.

- Run: https://github.com/canton-network/splice/actions/runs/42618450792, main 38a37d9680 ("Double encode parentheses
  in Cloud Armor alert log links (#8368)"), job 129164505315 `ci / scala_test_sim_time / simtime (3)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: Canton BFT orderer onboarding window (trigger) plus simtime test setup (nothing advances the sim clock
  during setup, so possibly-in-flight sends never time out).

## 1. Job, canton pin, classification

Issue `Job: simtime (3) in scala_test`; one failed job in the run:
```
RUN=42618450792; export NAME="simtime (3)"
gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
git show 38a37d968049d92130d4eb3a6a467ef7310066af:nix/canton-sources.json | grep -m1 version
```
```
129164505315 attempt=1 ci / scala_test_sim_time / simtime (3)
  "version": "3.7.2",
```
Job log (`gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/129164505315/logs > log/11289/job.log`):
no `Tests:` summary, no `FAILED`; the test is still running when sv4Validator exits:
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11289/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]|Initialization failed|still running|Wait for user' | sed -E 's/^[^Z]*Z //' | cut -c1-300 | tail -6
```
```
[info] *** Test still running after 7 minutes, 26 seconds: suite name: SvTimeBasedRoundMgmtIntegrationTest, test name: round management. 
sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
##[error]Error: failed to run script step (id 48e6cdb0-dc3c-11f1-b199-4f877b8f33bd): Error: step failed with return code 1
##[error]Process completed with exit code 1.
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
```
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11289/job.log | grep -a -n -A12 'Initialization failed, so exiting' | sed -n '1p;11,13p' | cut -c1-230
```
```
13635:2026-11-09T12:59:41.6227828Z sv4Validator app initialization: Initialization failed, so exiting; check the application logs for details
13645-2026-11-09T12:59:41.6239210Z Caused by: java.lang.RuntimeException: sv4Validator app initialization: Wait for user failed
13646-2026-11-09T12:59:41.6240074Z 	... 8 more
13647-2026-11-09T12:59:41.6241343Z Caused by: io.grpc.StatusRuntimeException: PERMISSION_DENIED: An error occurred. Please contact the operator and inquire about the request 0f8c66cb6386dc5fbb06ace50b914cb3 with tid 0f8c66cb6386dc
```
Suites of the shard (the other five never ran):
```
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/11289/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```
```
ExternallySignedTxsTimeBasedIntegrationTest
FollowAmuletConversionRateFeedTimeBasedIntegrationTest
SvTimeBasedOnboardingIntegrationTest
SvTimeBasedRewardCouponMissingPartyIntegrationTest
SvTimeBasedRoundMgmtIntegrationTest
WalletTxLogTimeBasedIntegrationTest
```
Evidence loss as in family H (`NodeBase` `sys.exit(1)` on init failure); here the step failed right after the exit.

## 2. Suite timeline: who waited for what

Artifact `logs-simtime-3` (id 13675982545, created 12:59:58, attempt 1):
`gh run download 42618450792 --repo canton-network/splice -n logs-simtime-3 -D log/11289/logs-simtime-3`.
The test log starts at 12:52:06 and SvTimeBasedRoundMgmtIntegrationTest is the first suite. Validator `Wait for user`:
sv1 after 1m20s, sv2 and sv3 after about 3m6s, sv4 failed after 7m21s:
```
T=log/11289/logs-simtime-3/canton_network_test.clog.gz
zcat $T | jq -r 'select(.message|test("^Starting test suite|^Test (succeeded|failed)|Timeout while waiting|Initialization failed|Wait for user")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|.[0:60]) | \(.message|gsub("\\s+";" ")|.[0:220])"' | grep -E 'Starting test suite|Validator app'
```
```
12:52:09.467 INFO c.d.c.LogReporter:reporter=scala-test | Starting test suite 'SvTimeBasedRoundMgmtIntegrationTest'...
12:52:19.743 DEBUG o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv3Validator app initialization: Wait for user started
12:52:20.109 DEBUG o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv4Validator app initialization: Wait for user started
12:52:20.473 DEBUG o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv1Validator app initialization: Wait for user started
12:52:20.831 DEBUG o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv2Validator app initialization: Wait for user started
12:53:41.255 INFO o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv1Validator app initialization: Wait for user finished after PT1M20.781821535S
12:55:26.276 INFO o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv2Validator app initialization: Wait for user finished after PT3M5.444786852S
12:55:27.492 INFO o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv3Validator app initialization: Wait for user finished after PT3M7.748720475S
12:59:41.114 INFO o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv4Validator app initialization: Wait for user failed
12:59:41.114 ERROR o.l.s.v.ValidatorApp:SvTimeBasedRoundMgmtIntegrationTest/con | sv4Validator app initialization: Initialization failed
```
sv4Validator gave up after the 120-retry limit of `Get user`:
```
zcat $T | jq -r 'select(.logger_name|test("sv4Validator")) | select(.["@timestamp"] > "2026-11-09T12:59:41") | select(.level!="DEBUG") | "\(.["@timestamp"][11:23]) \(.level) | \(.message|gsub("\\s+";" ")|.[0:160])"'
```
```
12:59:41.109 INFO | Now retrying operation 'Get user sv4_validator_user-9d6c71e7'. 
12:59:41.112 INFO | Request (tid:0f8c66cb6386dc5fbb06ace50b914cb3) com.daml.ledger.api.v2.admin.UserManagementService/GetUser to 127.0.0.1:15401: failed with PERMISSION_DENIED/An e
12:59:41.112 INFO | The operation 'Get user sv4_validator_user-9d6c71e7' has failed with an exception. Total maximum number of retries 120 exceeded. Giving up. 
12:59:41.113 ERROR | Gave up getting 'user sv4_validator_user-9d6c71e7'
12:59:41.114 INFO | sv4Validator app initialization: Wait for user failed
12:59:41.114 INFO | sv4Validator app initialization: Initialize app failed
12:59:41.114 ERROR | sv4Validator app initialization: Initialization failed
```
The PERMISSION_DENIED is the participant's way of saying the user does not exist:
```
F=log/11289/logs-simtime-3/canton-simtime.clog.gz
zcat $F | grep -a -F '0f8c66cb6386dc5fbb06ace50b914cb3' | jq -r 'select(.level=="WARN") | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|split(":")[0]|split(".")[-1]) | \(.message|gsub("\\s+";" ")|.[0:220])"'
```
```
12:59:41.112 WARN AuthInterceptor | PERMISSION_DENIED(7,0f8c66cb): Could not resolve is_deactivated status for user 'sv4_validator_user-9d6c71e7' and identity_provider_id 'Default' due to 'UserNotFound(sv4_validator_user-9d6c71e7)'
```

## 3. The SV app creates the validator user only after joining the DSO

At 38a37d9680 the joining SV app runs `Initialize validator` (which provisions the validator user) after
`joinDsoAndOnboardNodes()`:
```
S=38a37d968049d92130d4eb3a6a467ef7310066af
git show $S:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/SvApp.scala | sed -n 352,358p
git show $S:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/onboarding/joining/JoiningNodeInitializer.scala | sed -n 142,143p
```
```
      (_, _, isDevNet, _, _, _) <- (
        // We create the validator user only after the DSO party migration and DAR uploads have completed. This avoids two issues:
        // 1. The ValidatorLicense has both the DSO and the SV as a stakeholder.
        //    That can cause problems during the DSO party migration because the contract is imported there
        //    but could also be imported through the stream of the SV party. By only creating the validator user here
        //    we ensure that the party migration has been completed before the contract is created.
        appInitStep("Initialize validator") {
    // We need to connect to the domain here because otherwise we create a circular dependency
    // with the validator app: The validator app waits for its user to be provisioned (which happens in createValidatorUser)
```
sv2 and sv3 finished joining and `Initialize validator` at 12:55:22-24; sv4 never did:
```
zcat $T | jq -r 'select(.message|test("^sv[1-4] app initialization: (JoiningNodeInitializer joining Dso with key|SV1Initializer bootstrapping Dso|Initialize validator) finished")) | "\(.["@timestamp"][11:23]) | \(.message|.[0:110])"'
```
```
12:53:39.082 | sv1 app initialization: SV1Initializer bootstrapping Dso finished after PT41.355790463S
12:53:40.096 | sv1 app initialization: Initialize validator finished after PT0.933378037S
12:55:22.888 | sv3 app initialization: JoiningNodeInitializer joining Dso with key finished after PT2M25.162614037S
12:55:23.261 | sv2 app initialization: JoiningNodeInitializer joining Dso with key finished after PT2M25.535637141S
12:55:24.148 | sv3 app initialization: Initialize validator finished after PT1.247283997S
12:55:24.681 | sv2 app initialization: Initialize validator finished after PT1.395366515S
```
The sv4 SV app's join is stuck in mediator onboarding from 12:55:09 to the end:
```
zcat $T | jq -r 'select(.logger_name|test("/SV=sv4($|/)")) | select(.logger_name|test("LocalSynchronizerNode")) | select(.level!="DEBUG") | select(.["@timestamp"] > "2026-11-09T12:55:09") | "\(.["@timestamp"][11:23]) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:170])"' | awk 'NR<=4{print} {l=$0} END{print "...last:"; print l}'
```
```
12:55:09.391 | Attempting to get 'mediator status'
12:55:09.393 | Got 'mediator status': Left(NotInitialized(active = true, waitingFor = Initialization, version = 3.7.2))
12:55:09.393 | Onboarding mediator
12:55:09.394 | Waiting until Mediator has been granted unlimited traffic
...last:
12:59:37.944 | The operation 'Check whether Mediator has been granted unlimited traffic' has failed with an exception. Retrying after a number of 76 failures, and after 3192 millisecond
```
In the same window (sv4 SV app, non-DEBUG, consecutive identical messages counted; excerpt): the mediator has no
traffic state because it was never added, and `check established Add mediator MED::sv4` gave up twice:
```
zcat $T | jq -r 'select(.["@timestamp"] > "2026-11-09T12:55:01") | select(.logger_name|test("/SV=sv4($|/)")) | select(.level!="DEBUG") | select(.message|test("Processing Tick|no-op|PeriodicTask|Retrieving tasks|polling loop|Sending upload dar|FetchTime|processTaskWithRetry|stale|Index |ConfirmAction|Confirmed action|Completed processing|Detected an error|Now retrying")|not) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|sub(".*\\.";"")|sub(":.*";"")) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:200])"' | awk '{m=substr($0,index($0,"|")); k=m; gsub(/[0-9]+/,"N",k); if(!(k in f)){f[k]=$1; o[++n]=k; s[k]=$0} l[k]=$1; c[k]++} END{for(i=1;i<=n;i++){k=o[i]; print f[k]"-"l[k], c[k], substr(s[k], 14)}}' | cut -c1-260 | grep -E 'Add mediator MED::sv4|traffic'
```
(excerpt: 7 of 14 lines, the retry-scheduling lines omitted)
```
12:55:05.228-12:55:05.228 1 INFO ParticipantAdminConnection | Ensuring that Add mediator MED::sv4::12205f508079...
12:55:05.243-12:59:03.876 3 INFO ParticipantAdminConnection | No proposal found for Add mediator MED::sv4::12205f508079..., submitting new one.
12:55:05.276-12:59:38.848 83 INFO ParticipantAdminConnection | The operation 'check established Add mediator MED::sv4::12205f508079...' failed with a retryable error (full stack trace omitted): FAILED_PRECONDITION: Condition is not yet observed. Proposed: Medi
12:55:09.394-12:55:09.394 1 INFO LocalSynchronizerNode | Waiting until Mediator has been granted unlimited traffic
12:55:09.399-12:59:37.944 77 INFO LocalSynchronizerNode | The operation 'Check whether Mediator has been granted unlimited traffic' failed with a retryable error (full stack trace omitted): NOT_FOUND: No traffic state found for member MED::sv4::12205f508079.
12:55:09.450-12:59:40.275 265 INFO SvOnboardingUnlimitedTrafficTrigger | Member MED::sv4::12205f508079... does not yet have a traffic state, skipping
12:57:01.391-12:59:03.625 2 INFO ParticipantAdminConnection | The operation 'check established Add mediator MED::sv4::12205f508079...' has failed with an exception. Total maximum number of retries 35 exceeded. Giving up. 
```

## 4. Ordering topology: 1 -> 4 at epoch 14, sv4 blacklisted in epochs 15-17

globalSequencerSv1, consecutive epochs with the same leaders/blacklist/size collapsed:
```
zcat $F | jq -r 'select(.["@timestamp"] > "2026-11-09T12:52") | select(.logger_name|test("globalSequencerSv1")) | select(.message|test("^New epoch")) | "\(.["@timestamp"][11:23]) \(.message|capture("New epoch (?<e>[0-9]+)").e) leaders=\([.message|capture("leaders = List\\((?<l>[^)]*)\\)").l|scan("SEQ::(sv[0-9])")[0]]|join(",")) bl=\([.message|capture("blacklisted nodes = List\\((?<l>[^)]*)\\)").l|scan("SEQ::(sv[0-9])")[0]]|join(",")) size=\(.message|capture("size = (?<s>[0-9]+)").s // "?")"' | awk '{k=$3" "$4" "$5; if(k!=prev){print; prev=k}}' | head -6
```
```
12:53:00.132 0 leaders=sv1 bl= size=1
12:55:05.241 14 leaders=sv3,sv4,sv1,sv2 bl= size=4
12:55:21.386 15 leaders=sv1,sv2,sv3 bl=sv4 size=4
12:55:33.195 16 leaders=sv2,sv3,sv1 bl=sv4 size=4
12:55:48.123 17 leaders=sv3,sv1,sv2 bl=sv4 size=4
12:56:02.975 18 leaders=sv3,sv4,sv1,sv2 bl= size=4
```
Without `head -6` the table continues with one line per epoch: epochs 18 to 38 (12:56:02 to 12:59:45) all have size 4
and no blacklist (leaders rotate, so the collapse keeps each). sv1 is alone in its first epoch at size 4
(full line of epoch 14 and the quorum message just before it):
```
zcat $F | awk '/"@timestamp":"2026-11-09T12:55:0[4-6]/' | grep -a -E 'New epoch 14 |below weak quorum' | jq -r '"\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>sequencer=[A-Za-z0-9]+)").n // "-") | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:300])"'
```
```
12:55:05.234 INFO sequencer=globalSequencerSv1 | Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated
12:55:05.241 INFO sequencer=globalSequencerSv1 | New epoch 14 has started with leaders = List(SEQ::sv3::..326d, SEQ::sv4::..43e1, SEQ::sv1::..2183, SEQ::sv2::..cc39)and blacklisted nodes = List(); ordering topology = OrderingTopology( activationTime = 1970-01-01T00:00:00.200001Z, size = 4, weakQuorum = 2, strongQuorum = 3, nodesTopologyInfo = Map(
```

## 5. The MED::sv4 proposal: sv1's broadcast sequenced, the other three rejected

The four SV apps submit the same proposal (MediatorSynchronizerState with sv1, sv3, sv2, sv4):
```
zcat $T | jq -r 'select(.["@timestamp"] > "2026-11-09T12:55:04" and .["@timestamp"] < "2026-11-09T12:59:42") | select(.message|test("Submitted proposal MediatorSynchronizerState")) | "\(.["@timestamp"][11:23]) \(.logger_name|capture("SV=(?<n>sv[0-9])").n // "-")"'
```
```
12:55:05.013 sv1
12:55:05.237 sv3
12:55:05.270 sv4
12:55:05.426 sv2
12:57:00.316 sv2
12:57:01.633 sv4
12:57:06.951 sv3
12:59:03.883 sv2
12:59:03.898 sv4
12:59:12.460 sv3
```
Outbox broadcasts of that transaction (TxHash 914d918db795) by the participants:
```
zcat $F | awk '/"@timestamp":"2026-11-09T12:55:0[5-9]/' | grep -a -E 'participant=sv[1-4]Participant' | grep -a -E 'Broadcasting topology|responded the following|returned an error' | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|capture("participant=(?<n>[A-Za-z0-9]+)").n) trace=\(.["trace-id"]|.[0:8]) | \(.message|gsub("\\s+";" ")|.[0:150])"'
```
```
12:55:05.012 sv1Participant trace=b81d1ce4 | Broadcasting topology transaction: List(TxHash(SHA-256:914d918db795...))
12:55:05.196 sv1Participant trace=b81d1ce4 | Synchronizer 'global' responded the following for the given topology transactions: List((Accepted,TxHash(SHA-256:914d918db795...)))
12:55:05.236 sv3Participant trace=e29ba77d | Broadcasting topology transaction: List(TxHash(SHA-256:914d918db795...))
12:55:05.270 sv4Participant trace=2797b74b | Broadcasting topology transaction: List(TxHash(SHA-256:914d918db795...))
12:55:05.286 sv3Participant trace=e29ba77d | Send [ca67079b-602a-4f72-9fe2-0624de07ec2a] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Req
12:55:05.289 sv4Participant trace=2797b74b | Send [898c84c6-36e5-4682-8cfb-dc62ea943697] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Req
12:55:05.425 sv2Participant trace=8005cc0a | Broadcasting topology transaction: List(TxHash(SHA-256:914d918db795...))
12:55:05.444 sv2Participant trace=8005cc0a | Send [21097881-7e1a-4c45-8622-fb661e2231e4] returned an error however may still be possibly sequenced so we are ignoring the error: TransportError(Req
```
The sequencer side (same traces): sv1's request arrived at 12:55:05.020, before the switch; the others after it were
rejected; max sequencing time 00:00:20:
```
zcat $F | awk '/"@timestamp":"2026-11-09T12:55:0[4-6]/' | grep -a -E 'Rejecting submission request by|Request to send submission' | jq -r 'select(.["trace-id"]|test("^(b81d1ce4|e29ba77d|2797b74b|8005cc0a)")) | "\(.["@timestamp"][11:23]) \(.level) \(.logger_name|capture("(?<n>sequencer=[A-Za-z0-9]+)").n) | \(.message|gsub("\\s+";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:230])"'
```
```
12:55:05.020 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 0c5f2e9d-0249-47ea-a1e8-c31bfa26a2b2 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv1::1220d3b4a409... to Set(All). 
12:55:05.245 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id ca67079b-602a-4f72-9fe2-0624de07ec2a with max sequencing time 1970-01-01T00:00:20Z from PAR::sv3::1220eff4d486... to Set(All). 
12:55:05.262 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv3::1220eff4d486...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
12:55:05.278 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 898c84c6-36e5-4682-8cfb-dc62ea943697 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv4::12201cad2a86... to Set(All). 
12:55:05.280 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv4::12201cad2a86...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
12:55:05.433 DEBUG sequencer=globalSequencerSv1 | Request to send submission with id 21097881-7e1a-4c45-8622-fb661e2231e4 with max sequencing time 1970-01-01T00:00:20Z from PAR::sv2::1220c972f3fb... to Set(All). 
12:55:05.435 INFO sequencer=globalSequencerSv1 | Rejecting submission request by Some(PAR::sv2::1220c972f3fb...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2), rejecting)
```
sv1's submission was ordered (block 199); the three rejected ones never reach any sequencer again (their last log
lines are the participants' own connection restarts):
```
for id in 0c5f2e9d-0249-47ea-a1e8-c31bfa26a2b2 ca67079b-602a-4f72-9fe2-0624de07ec2a 898c84c6-36e5-4682-8cfb-dc62ea943697 21097881-7e1a-4c45-8622-fb661e2231e4; do echo "== $id validated=$(zcat $F | grep -a -F $id | grep -a -c 'validated to: Deliver') last=$(zcat $F | grep -a -F $id | tail -1 | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<n>(sequencer|participant)=[A-Za-z0-9]+)").n)"')"; done
```
```
== 0c5f2e9d-0249-47ea-a1e8-c31bfa26a2b2 validated=4 last=12:55:14.533 sequencer=globalSequencerSv3
== ca67079b-602a-4f72-9fe2-0624de07ec2a validated=0 last=12:55:05.290 participant=sv3Participant
== 898c84c6-36e5-4682-8cfb-dc62ea943697 validated=0 last=12:55:05.299 participant=sv4Participant
== 21097881-7e1a-4c45-8622-fb661e2231e4 validated=0 last=12:55:05.455 participant=sv2Participant
```
So the synchronizer nodes only ever see sv1's key on the proposal; the second keys stay in the local stores of
sv2, sv3, sv4 (one line per node and time, `All keys used for authorization`):
```
zcat $F | grep -a 'Authorization details for MediatorSynchronizerState=TxHash(SHA-256:914d918db795' | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<n>(participant|sequencer|mediator)=[A-Za-z0-9]+)").n // "-") | \(.message|gsub("\\s+";" ")|gsub("(?<a>1220[0-9a-f]{8})[0-9a-f]{56}";"\(.a)..."))"' | sed -E 's/^(\S+ \S+) \| .*All keys used for authorization: (.*)$/\1 | \2/'
echo "fully authorized: $(zcat $F | grep -a '914d918db795' | grep -a -c -i 'fully authorized')"
```
```
12:55:05.009 participant=sv1Participant | Set(1220d3b4a409...)
12:55:05.195 sequencer=globalSequencerSv1 | Set(1220d3b4a409...)
12:55:05.200 mediator=globalMediatorSv1 | Set(1220d3b4a409...)
12:55:05.201 participant=sv3Participant | Set(1220d3b4a409...)
12:55:05.202 participant=sv1Participant | Set(1220d3b4a409...)
12:55:05.202 participant=sv4Participant | Set(1220d3b4a409...)
12:55:05.203 participant=sv2Participant | Set(1220d3b4a409...)
12:55:05.232 participant=sv3Participant | Set(1220d3b4a409..., 1220eff4d486...)
12:55:05.266 participant=sv4Participant | Set(12201cad2a86..., 1220d3b4a409...)
12:55:05.421 participant=sv2Participant | Set(1220c972f3fb..., 1220d3b4a409...)
12:55:13.864 sequencer=globalSequencerSv4 | Set(1220d3b4a409...)
12:55:14.287 sequencer=globalSequencerSv2 | Set(1220d3b4a409...)
12:55:14.553 sequencer=globalSequencerSv3 | Set(1220d3b4a409...)
12:55:15.625 mediator=globalMediatorSv3 | Set(1220d3b4a409...)
12:55:18.684 mediator=globalMediatorSv2 | Set(1220d3b4a409...)
12:57:00.311 participant=sv2Participant | Set(1220c972f3fb..., 1220d3b4a409...)
12:57:01.629 participant=sv4Participant | Set(12201cad2a86..., 1220d3b4a409...)
12:57:06.947 participant=sv3Participant | Set(1220d3b4a409..., 1220eff4d486...)
12:59:03.878 participant=sv2Participant | Set(1220c972f3fb..., 1220d3b4a409...)
12:59:03.895 participant=sv4Participant | Set(12201cad2a86..., 1220d3b4a409...)
12:59:12.457 participant=sv3Participant | Set(1220d3b4a409..., 1220eff4d486...)
fully authorized: 0
```
The DSO namespace needs at least two owners' signatures (threshold 2 with 3 owners, 3 with 4):
```
zcat $F | awk '/"@timestamp":"2026-11-09T12:5[3-5]/' | grep -a -o 'DecentralizedNamespaceDefinition([^"]\{0,300\}' | grep -a -o 'threshold = [0-9]*, owners = Seq([^)]*)' | sed -E 's/1220[0-9a-f]{8}[0-9a-f]*/1220/g; s/1220\.\.\./1220/g' | awk '{c[$0]++} END{for(k in c) print c[k], k}'
```
```
94 threshold = 2, owners = Seq(1220, 1220)
141 threshold = 2, owners = Seq(1220, 1220, 1220)
178 threshold = 3, owners = Seq(1220, 1220, 1220, 1220)
```

## 6. Why the re-proposals never left: outbox flush cycles stuck under sim time

Last completed flush cycle per SV participant: sv2, sv3, sv4 never complete one after the rejected sends at
12:55:05; every later flush finds the old cycle still open:
```
zcat $F | awk '/"@timestamp":"2026-11-09T12:5[2-9]/' | grep -a -E 'Another flush cycle|Invoked flush with queue size|Marked flush as done' | jq -r '"\(.["@timestamp"][11:23]) \(.logger_name|capture("(?<n>(participant|sequencer|mediator)=[A-Za-z0-9]+)").n // "-") \(.message|gsub("[0-9]+";"N")|.[0:60])"' | awk '{k=$2" "$3" "$4" "$5" "$6" "$7" "$8; if(!(k in f)) f[k]=$1; l[k]=$1; c[k]++} END{for(k in c) print f[k]"-"l[k], c[k], k}' | sort -k3 | grep participant
```
```
12:54:54.355-12:55:01.843 3 participant=sv1Participant Another flush cycle is currently ongoing
12:53:01.933-12:55:05.011 23 participant=sv1Participant Invoked flush with queue size N
12:53:01.933-12:55:05.222 20 participant=sv1Participant Marked flush as done. Current queue
12:54:54.658-12:59:03.881 5 participant=sv2Participant Another flush cycle is currently ongoing
12:53:42.776-12:59:03.881 25 participant=sv2Participant Invoked flush with queue size N
12:53:42.776-12:55:04.919 19 participant=sv2Participant Marked flush as done. Current queue
12:54:56.391-12:59:12.458 6 participant=sv3Participant Another flush cycle is currently ongoing
12:53:42.237-12:59:12.458 25 participant=sv3Participant Invoked flush with queue size N
12:53:42.237-12:55:04.863 18 participant=sv3Participant Marked flush as done. Current queue
12:54:59.491-12:59:03.897 9 participant=sv4Participant Another flush cycle is currently ongoing
12:53:46.629-12:59:03.897 26 participant=sv4Participant Invoked flush with queue size N
12:53:46.629-12:55:04.555 16 participant=sv4Participant Marked flush as done. Current queue
```
The rejected sends count as possibly sequenced until max sequencing time 00:00:20; the sim clock never got near it
(latest sequenced submission before the exit) and the test never advanced it during setup:
```
zcat $F | awk '/"@timestamp":"2026-11-09T12:5[5-9]/' | grep -a 'validated to: Deliver' | jq -r '"\(.["@timestamp"][11:23]) \(.message|capture(" at (?<t>1970-[^ ]+) validated").t)"' | awk '$1<"12:59:42" {if($2>m){m=$2; l=$0}} END{print "latest sequenced submission before 12:59:42:", l}'
zcat $T | grep -a -i 'advanc' | jq -r '.message[0:110]' | sort | uniq -c
```
```
latest sequenced submission before 12:59:42: 12:59:40.175 1970-01-01T00:00:02.081Z
      3 Starting trigger org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
      3 Starting trigger polling loop, ConfigSummary(pollingInterval = 1 second, pollingJitter = 0.2, maxNumSilentPoll
```

## 7. Alternatives ruled out

- Lost-verdict shape (11306, 11308, 11287): no mediator send refused, no blacklisting refusal, and all four SV
  participant indexers kept publishing to the end of the canton log:
  ```
  zcat $F | tail -1 | jq -r '.["@timestamp"]'
  zcat $F | awk '/"@timestamp":"2026-11-09T12:5[2-9]/' | grep -a -c -E 'Failed to send result|currently blacklisted'
  zcat $F | grep -a -E 'ParallelIndexerSubscription|InMemoryStateUpdater' | jq -r '"\(.logger_name|capture("participant=(?<n>[A-Za-z0-9]+)").n // "-") \(.["@timestamp"][11:19])"' 2>/dev/null | awk '$2>"12:52" {last[$1]=$2; c[$1]++} END{for(k in c) print k, last[k], c[k]}' | sort
  ```
  ```
  2026-11-09T12:59:47.366Z
  0
  sv1Participant 12:59:40 845
  sv2Participant 12:59:40 676
  sv3Participant 12:59:40 685
  sv4Participant 12:59:40 593
  ```
  sv4's blacklisting in epochs 15-17 (section 4) came after the rejection and had no effect here, so
  canton-network/splice#8356 (disable blacklisting in tests, open) would not have helped this run.
- A splice regression in #8368: the commit only touches the Grafana Cloud Armor alert config, not apps or tests; the
  same shape hit 11288 on bd9953a523 earlier the same day.
  ```
  git show --stat --format='%h %s' 38a37d968049d92130d4eb3a6a467ef7310066af | cat
  ```
  ```
  38a37d9680 Double encode parentheses in Cloud Armor alert log links (#8368)

   .../grafana-alerting.kubernetes_core_v1_ConfigMap.json        |  2 +-
   .../observability/grafana-alerting/cloud-armor_alerts.yaml    | 11 +++++++----
   2 files changed, 8 insertions(+), 5 deletions(-)
  ```
- Slow but healthy: the stuck state is a concrete never-completing step (proposal with one signature, outbox cycles
  never closing), not a timeout that was merely too short.

## 8. Duplicate check

```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"\(.state) comments=\(.comments) assignees=\([.assignees[].login]|join(",")) \(.title)"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "sv4_validator_user"' --jq '.items[] | "\(.number) \(.state) \(.created_at[0:10]) \(.title[0:90])"'
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "SvTimeBasedRoundMgmtIntegrationTest"' --jq '.items[] | "\(.number) \(.state) \(.created_at[0:10]) \(.title[0:110])"' | head -3
gh pr view 8356 -R canton-network/splice --json state,mergedAt,title --jq '"\(.state) \(.mergedAt) \(.title)"'
```
```
open comments=1 assignees=rautenrieth-da Timeouts due to changing bft ordering topology
11255 open 2026-11-06 Timeouts due to changing bft ordering topology
8276 closed 2026-05-06 simtime failures in recent post merge tests
10271 closed 2026-10-02 SvTimeBasedRoundMgmtIntegrationTest "doubled tickDuration": IssuingMiningRound 1 not created within the 20s ch
11287 closed 2026-11-09 GHA Run 42591855865 : job simtime (3) in scala_test Failed :fire:
9836 closed 2026-09-01 SvTimeBasedRoundMgmtIntegrationTest: "message":"Consensus not reached." log/canton_network_test.clog contains 
OPEN null Disable BFT sequencer blacklisting in integration tests
```
- 11255: the body comment describes exactly this: the topology broadcasts adding the sv4 mediator rejected right after
  the switch to 4 nodes with `P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`, treated as
  possibly in flight until 00:00:20 on a sim clock that stood at 00:00:01.7, outboxes blocked (`Another flush cycle is
  currently ongoing`), sv4Validator exits the JVM waiting for `sv4_validator_user`. Same shape, same parent.
- 8276: May 2026, before the BFT orderer onboarding; not the same failure.
- 11287: lost-verdict shape in the same suite (sv3Validator installWalletForUser), closed as dup of 11255; not this
  shape but the same parent.
- 10271, 9836: round-management assertions and Consensus-not-reached in this suite, older and unrelated.
- 11288 (evidence/11287-11288-...): rejected-send shape, MED::sv4 proposal rejected at the 1 -> 4 switch, sv4Validator
  exit during TrafficBasedRewardsSvAppTimeBasedIntegrationTest setup; this run matches it hop for hop. Difference:
  here sv1's broadcast went out 0.2 s before the switch and was sequenced, so the proposal reached the synchronizer
  with one signature instead of none.

## Verdict

- Duplicate of 11255, rejected-send shape (as 11288). Flake; Canton BFT onboarding window under simulated time; no
  splice change beyond the options discussed on 11255. Understanding of 11255 unchanged, so no parent comment.
- Action: close 11289 as duplicate of 11255 (closing moves it to Closed).
- Verified: every hop from the 1 -> 4 switch to the JVM exit, with timestamps (sections 2 to 6); no lost verdict.
- Not verified: why the sv1 sequencer had only itself authenticated at 12:55:05 (P2P authentication of the three
  new peers, which the sv4 SV app only added at 12:55:09-12:55:11; not analysed); why sv4 is the blacklisted node in
  epochs 15-17.
