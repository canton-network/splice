# 11398 - simtime (1): splitwellValidator init stuck on rejected sends at the 1 -> 4 switch, then 4 suites fail sv1 init (run 43262479377)

DUPLICATE of 11255 (rejected-send shape plus the setup-failure blast radius). Main c9c875cfc5, job simtime (1),
Canton 3.7.3. In the setup of TrafficBasedRewardsSvAppTimeBasedIntegrationTest the global ordering topology goes to 4
nodes at 18:13:25.223; 1.3 s later sv1's sequencer rejects splitwellValidator's `installWalletForUser`
(WalletAppInstall for splitwell__provider) with `P2P connectivity is not ready (authenticated = 1 < dissemination
quorum = 2)`, and at 18:13:28.988 its `createSplitwellRules`. Both stay in flight until max sequencing time
1970-01-01T00:01:00Z on a sim clock still at 00:00:02.198 at 18:18, so the init retries get
`SUBMISSION_ALREADY_IN_FLIGHT` (69 times) until the 5 min validator init timeout at 18:18:56. The setup failure skips
ResetDecentralizedNamespace (0 `has been reset`), so the next four suites fail sv1 init with
`TOPOLOGY_UNAUTHORIZED_TRANSACTION` and `UserNotFound(sv1-...)`, each after 5 min, and sbt is killed at its 40 min
limit during the fifth (`Killed SBT after timeout 40m`), so no ScalaTest summary.

- Run: https://github.com/canton-network/splice/actions/runs/43262479377, main c9c875cfc5 ("Bump versions after
  0.10.2 cut + clear release notes (#8458)"), job 131352856821 `ci / scala_test_sim_time / simtime (1)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at c9c875cfc5).
- Component: Canton BFT ordering (P2P connectivity below dissemination quorum right after a topology change) under
  simulated time; splice test environment (setup failure skips the namespace reset).

## 1. Job: five setup failures, then the sbt timeout

```
sed -E 's/\x1b\[[0-9;]*m//g' log/11398/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u | cut -c1-250
```
```
##[error]Error: failed to run script step (id 22add550-dec4-11f1-a9a1-9bc637f9348d): Error: step failed with return code 1
##[error]Executing the custom container implementation failed. Please contact your self hosted runner administrator.
##[error]Process completed with exit code 1.
[info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
[info] - ExecuteFeaturedAppConfigUpdateInstructionTrigger applies the voted config update *** FAILED ***
[info] - allow calling tap, list the created amulets, and get the balance - locally and remotely *** FAILED ***
[info] - automatically merge transfer inputs when the automation is triggered *** FAILED ***
[info] - merge also amulet that should be expired *** FAILED ***
```
All five are `EnvironmentSetupException: Creating environment failed at step Running setup` with `Condition never
became true within 5 minutes` in `waitForInitialization` (first `ValidatorAppReference`, the others
`SvAppReference`). End of the job log:
```
sed -E 's/\x1b\[[0-9;]*m//g' log/11398/job.log | grep -a -v -E 'Z\s*$' | grep -a -B2 'Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>$' | head -3 | cut -c1-140
```
```
2026-11-12T18:49:03.0782980Z Killed SBT after timeout 40m
2026-11-12T18:49:03.0783656Z Attempt 1 failed with exit code 124, retrying
2026-11-12T18:49:03.0786826Z Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
```

## 2. Suite timeline

Artifact `logs-simtime-1` (13973177964) in `log/11398/logs-simtime-1/`.
```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Timeout while waiting for initialization|has been reset|Failed to reset decentralized" | sed -E "$F" | sed -E 's/"logger_name.*//' | cut -c1-200
```
```
2026-11-12T18:09:36.205Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-12T18:18:56.146Z Timeout while waiting for initialization of splitwellValidator",[o.l.s.c.ValidatorAppBackendReference:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=d2c96da3/app=spli
2026-11-12T18:18:56.465Z Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', message: Creating environment fai
2026-11-12T18:18:56.525Z Starting test suite 'TimeBasedTreasuryIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-12T18:24:13.323Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:TimeBasedTreasuryIntegrationTest/config=c9aeca9e/app=sv1] ERROR
2026-11-12T18:24:13.373Z Test failed: 'TimeBasedTreasuryIntegrationTest/automatically merge transfer inputs when the automation is triggered', message: Creating environment failed at step Running setu
2026-11-12T18:29:26.893Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:TimeBasedTreasuryIntegrationTest/config=8f70d50d/app=sv1] ERROR
2026-11-12T18:29:26.949Z Test failed: 'TimeBasedTreasuryIntegrationTest/allow calling tap, list the created amulets, and get the balance - locally and remotely', message: Creating environment failed a
2026-11-12T18:34:40.507Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:TimeBasedTreasuryIntegrationTest/config=7c73b518/app=sv1] ERROR
2026-11-12T18:34:40.551Z Test failed: 'TimeBasedTreasuryIntegrationTest/merge also amulet that should be expired', message: Creating environment failed at step Running setup (EnvironmentSetup.scala:23
2026-11-12T18:34:40.570Z Starting test suite 'WalletTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-12T18:39:54.348Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:WalletTimeBasedIntegrationTest/config=b2b8beda/app=sv1] ERROR
2026-11-12T18:39:54.413Z Starting test suite 'FeaturedAppConfigUpdateInstructionTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-12T18:45:08.351Z Timeout while waiting for initialization of sv1",[o.l.s.c.SvAppBackendReference:FeaturedAppConfigUpdateInstructionTimeBasedIntegrationTest/config=40907bbd/app=sv1] ERROR
2026-11-12T18:45:08.400Z Test failed: 'FeaturedAppConfigUpdateInstructionTimeBasedIntegrationTest/ExecuteFeaturedAppConfigUpdateInstructionTrigger applies the voted config update', message: Creating e
```
`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.

## 3. splitwellValidator stops in "Set up app instance splitwell"

```
zcat canton_network_test.clog.gz | grep -a 'splitwellValidator app initialization' | grep -a 'TrafficBasedRewards' | grep -a -E 'started|finished' | sed -E "$F" | cut -c1-140 | tail -4
```
```
2026-11-12T18:13:18.630Z splitwellValidator app initialization: Resolving domain migration id finished after PT0.003448709S",[o.l.s.v.Valida
2026-11-12T18:13:18.630Z splitwellValidator app initialization: Get DSO party id started",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTime
2026-11-12T18:13:18.638Z splitwellValidator app initialization: Get DSO party id finished after PT0.007374801S",[o.l.s.v.ValidatorApp:Traffi
2026-11-12T18:13:18.672Z splitwellValidator app initialization: Set up app instance splitwell started",[o.l.s.v.ValidatorApp:TrafficBasedRew
```

## 4. 11255 confirming grep: rejections right after the switch to 4 orderers, no lost verdicts

```
zcat canton-simtime.clog.gz | awk '/"@timestamp":"2026-11-12T18:(09|1[0-8])/{ if(/Failed to send result/) b++; if(/New epoch .*size = 4/){match($0,/"@timestamp":"[^"]+"/); last=substr($0,RSTART+13,24)} if(/Rejecting submission request by/ && /P2P connectivity is not ready/){match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+13,24); match($0,/Rejecting submission request by [^ ]+/); print "reject " t " last size=4 epoch " last " " substr($0,RSTART,58)} } END{print "Failed to send result: " b+0}' | sed -E 's/1220[0-9a-f]{60}/../g'
```
```
reject "2026-11-12T18:13:26.489 last size=4 epoch "2026-11-12T18:13:25.223 Rejecting submission request by Some(PAR::splitwellValidat
reject "2026-11-12T18:13:28.169 last size=4 epoch "2026-11-12T18:13:25.223 Rejecting submission request by Some(PAR::sv1::122025c3460
reject "2026-11-12T18:13:28.988 last size=4 epoch "2026-11-12T18:13:25.223 Rejecting submission request by Some(PAR::splitwellValidat
Failed to send result: 0
```
```
zcat canton-simtime.clog.gz | grep -a 'New epoch' | grep -a -E 'size = 4' | grep -a -v splitwell | head -1 | sed -E "$F" | cut -c1-120
```
```
2026-11-12T18:13:25.223Z New epoch 15 has started with leaders = List(SEQ::sv4::..b585, SEQ::sv1::..f203, SEQ::sv2::..8a
```
The rejection is 1.3 s after the switch, outside the 0.3 s of the catalogue's grep, with the same message (connectivity
still below the dissemination quorum). The splitwell provider's PartyToParticipant broadcast just before the switch
(message 41c8401c, 18:13:24.401) was sequenced (`validated to: Deliver` at 18:13:24.705), so this is not the
batch-never-ordered variant of 11326.

## 5. The rejected sends are the splitwell provider's wallet install and SplitwellRules, max sequencing time 00:01:00

```
zcat canton-simtime.clog.gz | grep -a -E 'T18:13:2[6-9]' | grep -a "PAR::splitwellValidator" | grep -a -E "sends request with id|Rejecting" | grep -a globalSequencer | sed -E "$F" | cut -c1-200
```
```
2026-11-12T18:13:26.483Z 'PAR::splitwellValidator::1220c2900965...' sends request with id 'd3b01518-2b6c-4fdc-8d7d-33e8abb23416' of size 3062 bytes with 3 envelopes to MGR=1, PAR=1.",[c.d.c.s.s.s.Grp
2026-11-12T18:13:26.489Z Rejecting submission request by Some(PAR::splitwellValidator::1220c2900965...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not re
2026-11-12T18:13:28.984Z 'PAR::splitwellValidator::1220c2900965...' sends request with id '0679c518-79bf-4448-8f2f-4a3c9e1e40b8' of size 2937 bytes with 3 envelopes to MGR=1, PAR=1.",[c.d.c.s.s.s.Grp
2026-11-12T18:13:28.988Z Rejecting submission request by Some(PAR::splitwellValidator::1220c2900965...) with SequencerDeliverError(code = SEQUENCER_UNAVAILABLE, message = P2P connectivity is not re
```
Per message, its trace id, then the submission lines of that trace:
```
for M in d3b01518-2b6c-4fdc-8d7d-33e8abb23416 0679c518-79bf-4448-8f2f-4a3c9e1e40b8; do T=$(zcat canton-simtime.clog.gz | grep -a "Sending message ID $M" | head -1 | grep -oE '"trace-id":"[0-9a-f]+"' | cut -d'"' -f4); echo "== $M trace=$T"; zcat canton-simtime.clog.gz | grep -a "$T" | grep -a -E 'Submitted commands are|is now in-flight|max sequencing time' | sed -E "$F" | sed -E 's/\\n */ /g' | cut -c1-200; done
```
```
== d3b01518-2b6c-4fdc-8d7d-33e8abb23416 trace=e3fe5abacae3293ce804e0ab8b826a52
2026-11-12T18:13:25.010Z Submitted commands are: 'create Splice.Wallet.Install:WalletAppInstall'",[c.d.c.p.a.s.c.CommandSubmissionServiceImpl:participant=splitwellParticipant] DEBUG
2026-11-12T18:13:26.463Z Command org.lfdecentralizedtrust.splice.validator.installWalletForUser_965edd543359f89a49f60bd446d9207b4248dfa2ee751753fa5731e5ce0918e0 is now in-flight.",[c.d.c.p.s.CantonSyncService:participan
2026-11-12T18:13:26.484Z Request to send submission with id d3b01518-2b6c-4fdc-8d7d-33e8abb23416 with max sequencing time 1970-01-01T00:01:00Z from PAR::splitwellValidator::1220c2900965... to Set(MediatorGrou
== 0679c518-79bf-4448-8f2f-4a3c9e1e40b8 trace=9dce579da9ef5cd6c5e809ff0105378b
2026-11-12T18:13:28.878Z Submitted commands are: 'create Splice.Splitwell:SplitwellRules'",[c.d.c.p.a.s.c.CommandSubmissionServiceImpl:participant=splitwellParticipant] DEBUG
2026-11-12T18:13:28.961Z Command org.lfdecentralizedtrust.splice.splitwell.createSplitwellRules_0e7092197a94563d24bb2068de12ad820cb741a39d14d25eb3bb1004fea14391 is now in-flight.",[c.d.c.p.s.CantonSyncService:participan
2026-11-12T18:13:28.985Z Request to send submission with id 0679c518-79bf-4448-8f2f-4a3c9e1e40b8 with max sequencing time 1970-01-01T00:01:00Z from PAR::splitwellValidator::1220c2900965... to Set(MediatorGrou
```

## 6. The sim clock never reaches 00:01:00; the retries find the command in flight

Last global sequencing time before the init timeout:
```
zcat canton-simtime.clog.gz | grep -a 'globalSequencerSv1' | grep -a -oE '"@timestamp":"2026-11-12T18:1[5-8][^"]*".*validated to: Deliver' | tail -1 | grep -oE '"@timestamp":"[^"]+"|at 1970-[^ ]+'
```
```
"@timestamp":"2026-11-12T18:18:00.766Z"
at 1970-01-01T00:00:02.198Z
```
```
zcat canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a 'TrafficBasedRewards' | grep -a -E 'installWalletForUser|SUBMISSION_ALREADY_IN_FLIGHT' | sed -E "$F" | cut -c1-200 | awk '{k=substr($0,25,60)} c[k]++<1' | head -5
zcat canton_network_test.clog.gz | grep -a 'validator=splitwellValidator' | grep -a 'config=d2c96da3' | grep -a -c SUBMISSION_ALREADY_IN_FLIGHT
```
```
2026-11-12T18:13:25.007Z Request (tid:e3fe5abacae3293ce804e0ab8b826a52) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15701: sending request SubmitAndWaitRequest(\n  Commands(\n
2026-11-12T18:15:25.009Z The operation 'installWalletForUser' failed with a retryable error (full stack trace omitted): DEADLINE_EXCEEDED: CallOptions deadline exceeded after 119.999871705s. Name resolution
2026-11-12T18:15:25.009Z Detected an error.",[o.l.s.v.ValidatorApp:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=d2c96da3/validator=splitwellValidator] INFO
2026-11-12T18:15:25.009Z The operation 'installWalletForUser' has failed with an exception. New kind of error: transient error (request infinite retries). Retrying after a number of 0 failures, and after
2026-11-12T18:15:25.275Z Retrying on same error kind transient error (request infinite retries) for StatusRuntimeException/ABORTED: SUBMISSION_ALREADY_IN_FLIGHT(2,a3c66861): The submission is already i
69
```

## 7. Blast radius: no namespace reset, sv1 init fails in every later suite

```
zcat canton_network_test.clog.gz | grep -a -c 'has been reset'
```
```
0
```
```
zcat canton-simtime.clog.gz | grep -a 'fec3714266babcb08d25b244945f3fcc\|T18:19:14.3' | grep -a -E 'TOPOLOGY_UNAUTHORIZED|UserNotFound' | grep -a -oE '"@timestamp":"[^"]+","message":"[^"]{0,200}' | sed -E 's/1220[0-9a-f]{60}/../g' | head -3
```
```
"@timestamp":"2026-11-12T18:19:01.240Z","message":"PERMISSION_DENIED(7,fec37142): Could not resolve is_deactivated status for user 'sv1-c9aeca9e' and identity_provider_id 'Default' due to 'UserNotFound(sv1-c9aeca9e)'
"@timestamp":"2026-11-12T18:19:14.314Z","message":"PERMISSION_DENIED(7,b458356f): Could not resolve is_deactivated status for user 'sv1_validator_user-c9aeca9e' and identity_provider_id 'Default' due to 'UserNotFound(sv1_validator_user-c9aeca9e)'
"@timestamp":"2026-11-12T18:19:14.388Z","message":"TOPOLOGY_UNAUTHORIZED_TRANSACTION(5,10575746): Topology transaction is missing authorizations by namespaces=Set(12207c2acc93...) and keys=Set()
```

## Verdict

Duplicate of 11255 (open, rautenrieth-da), rejected-send shape (like 11288 / 11289, here two transaction submissions
of splitwellValidator instead of an SV topology proposal) with the setup-failure blast radius (like 11332 / 11324).
Flake on Canton BFT ordering under sim time; fix belongs with 11255 (splice#8356 covers the lost-verdict and breaker
shapes, not rejected send). Not verified: why P2P connectivity was still below quorum 1.3 s after the switch (sv1
sequencer P2P state not traced).
