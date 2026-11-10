# 11334 - TrafficBasedRewardsSvAppTimeBasedIntegrationTest setup: sv1 low-priority circuit breaker trips on SEQUENCER_BACKPRESSURE from its blacklisted BFT sequencer (run 42794742288)

DUPLICATE of 11255 (family B, 1 -> 4 ordering topology step during setup). main 6dbbda48ca (#8379), job 129757264511
`simtime (3)`, canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252. All 5 tests passed; checkErrors flagged one WARN:
`Circuit breaker low-priority-connection tripped after 20 failures` on sv1 at 10:12:10.446, during environment setup of
the first suite. At 10:11:05 the global ordering topology went from 1 to 4 sequencers in one epoch while only sv1 was
authenticated; epoch 15 segments view-changed and sv1 was blacklisted for epochs 16 to 18 (10:11:27.092 to 10:12:10.657).
bobValidator's onboarding submission (sv1 public API, low-priority connection, no dedup) stayed in sv1's mempool from
10:11:20 and was ordered only in epoch 19; the validator's HTTP call timed out after 38 s and its retries made sv1 submit
again, and the blacklisted sv1 sequencer rejected each of those as overloaded: 20 rejections in 12 s tripped the
breaker (test max-failures 20) 0.2 s before sv1 left the blacklist. Setup then completed.

- Run: https://github.com/canton-network/splice/actions/runs/42794742288, main 6dbbda48ca ("Tolerate sequencer id clashes in the sequencer connector aliases (#8379)"), job 129757264511 `simtime (3)`, attempt 1.
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252 (`git show 6dbbda48ca:nix/canton-sources.json`).
- Component: Canton BFT orderer (blacklisting after the 1 -> 4 step), test setup (one-step onboarding of 4 sequencers).
- Artifact: `logs-simtime-3` (id 13754979090, created 2026-11-10T10:32:49Z), downloaded to `log/11334/logs-simtime-3/`.
  Canton window extracted once: `zcat canton-simtime_before_shutdown.clog.gz | awk '/"@timestamp":"2026-11-10T10:(0[7-9]|1[0-3])/' > log/11334/canton-1007-1013.jsonl`.

## 1. Flagged lines

The only flagged line without the ignore suffix; the tests themselves passed.
```
sed 's/\x1b\[[0-9;]*m//g' log/11334/job.log | grep -a '@timestamp' | grep -av 'ignore this line' | cut -c30-290
sed 's/\x1b\[[0-9;]*m//g' log/11334/job.log | grep -aE 'Tests: succeeded|contains problems' | cut -c30-
```
```
***"@timestamp":"2026-11-10T10:12:10.446Z","message":"Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure","logger_name":"o.l.s.u.SpliceCircuitBreaker:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=6ac1f5af/SV=sv1"
[info] Tests: succeeded 5, failed 0, canceled 0, ignored 0, pending 0
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[error] (checkErrors) log/canton_network_test.clog contains problems.
```

## 2. Which test was running

The WARN is inside "Running setup" of the first suite (10:07:51.578 to 10:12:23.151), before the first sim-clock
advance; no time jump is involved (rules out family AF).
```
zcat log/11334/logs-simtime-3/canton_network_test.clog.gz | grep -a -E '"message":"(Starting test suite|Starting creating environment: Running setup|Finished creating environment: Running setup|advancing time by)' | jq -r '[.["@timestamp"][11:23], .message[0:110]] | join(" ")' | head -6
```
```
10:07:06.167 Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...
10:07:51.578 Starting creating environment: Running setup
10:12:23.151 Finished creating environment: Running setup
10:12:34.516 advancing time by PT10M10S to 1970-01-01T00:10:10Z
10:12:46.747 advancing time by PT10M10S to 1970-01-01T00:20:20Z
10:12:57.152 advancing time by PT10M10S to 1970-01-01T00:30:30Z
```

## 3. Ordering topology and blacklist on globalSequencerSv1

1 -> 4 in one epoch (15), then sv1 blacklisted for epochs 16, 17, 18; back in epoch 19 at 10:12:10.657.
```
grep -a 'New epoch 1[4-9] ' log/11334/canton-1007-1013.jsonl | grep -a 'sequencer=globalSequencerSv1' | jq -r '[.["@timestamp"][11:23], (.message|gsub("\n";" ")|gsub("::1220[0-9a-f]{64}";"")|capture("(?<a>New epoch [0-9]+).*leaders = (?<l>List\\([^)]*\\)).*blacklisted nodes = (?<b>List\\([^)]*\\)).*size = (?<s>[0-9]+)") | "\(.a) leaders=\(.l) blacklisted=\(.b) size=\(.s)")] | join(" ")'
```
```
10:10:57.841 New epoch 14 leaders=List(SEQ::sv1) blacklisted=List() size=1
10:11:05.301 New epoch 15 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List() size=4
10:11:27.092 New epoch 16 leaders=List(SEQ::sv3, SEQ::sv4, SEQ::sv2) blacklisted=List(SEQ::sv1) size=4
10:11:41.254 New epoch 17 leaders=List(SEQ::sv4, SEQ::sv2, SEQ::sv3) blacklisted=List(SEQ::sv1) size=4
10:11:55.972 New epoch 18 leaders=List(SEQ::sv2, SEQ::sv3, SEQ::sv4) blacklisted=List(SEQ::sv1) size=4
10:12:10.657 New epoch 19 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List() size=4
```

## 4. Family B confirming grep: sv1 alone authenticated at the switch, then view changes

`below weak quorum` 11 ms before epoch 15 starts, and all four epoch-15 segments time out locally 5 s later.
```
awk '/"@timestamp":"2026-11-10T10:11:(0[5-9]|1[0-1])/' log/11334/canton-1007-1013.jsonl | grep -a 'sequencer=globalSequencerSv1' | grep -aE 'below weak quorum|moving from view number' | jq -r '[.["@timestamp"][11:23], .level, (.logger_name|split(":")[0]|split(".")|last), (.message|gsub("\n";" ")|gsub("::1220[0-9a-f]{64}";"")|.[0:200])] | join(" ")'
```
```
10:11:05.290 INFO P2PNetworkOutModule Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated
10:11:09.608 INFO P2PNetworkOutModule Authenticated P2P nodes count (including this node) 1 is currently below weak quorum size 2, dissemination and ordering may not be able to proceed until more nodes are authenticated
10:11:10.311 INFO SegmentState Segment 1 starting at block number 221 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv2.
10:11:10.311 INFO SegmentState Segment 2 starting at block number 222 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.
10:11:10.311 INFO SegmentState Segment 0 starting at block number 220 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.
10:11:10.311 INFO SegmentState Segment 3 starting at block number 223 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv4.
```

## 5. The submitter: bobValidator onboarding through the sv1 public API

The first onboard request (port 39260) arrived 10:11:20.388 and was answered 200 only at 10:12:12.436; the validator's
client gave up after 38 s and retried (51726 at 10:11:58.620, 36860 at 10:12:07.220); the retries ended in 429; the
fourth request at 10:12:15.916 found the validator onboarded and returned 200 in 3 ms. bobValidator initialization went
on, and setup finished at 10:12:23.151 (section 2).
```
zcat log/11334/logs-simtime-3/canton_network_test.clog.gz | awk '/"@timestamp":"2026-11-10T10:1(1:[2-5]|2:[01])/' | grep -a 'onboard/validator' | grep -aE 'received request|Responding with status|timeout after|HTTP request took' | jq -r '[.["@timestamp"][11:23], .level, (.logger_name|split(":")|.[0]|split(".")|last), (.logger_name|split("/")|last), (.message|gsub("\n";" ")|.[0:150])] | join(" ")'
```
```
10:11:20.388 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:39260): received request.
10:11:58.406 WARN HttpErrorHandler SV=sv1 Request to http://127.0.0.1:5114/api/sv/v0/onboard/validator (POST) resulted in a timeout after 38 seconds.
10:11:58.409 DEBUG ValidatorApp validator=bobValidator HTTP client (POST /api/sv/v0/onboard/validator): HTTP request took 38022 ms to complete. Received response with status code: 503 Service Unavailable
10:11:58.620 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:51726): received request.
10:12:06.876 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:51726): Responding with status code: 429 Too Many Requests
10:12:06.876 DEBUG ValidatorApp validator=bobValidator HTTP client (POST /api/sv/v0/onboard/validator): HTTP request took 8257 ms to complete. Received response with status code: 429 Too Many Requests
10:12:07.220 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:36860): received request.
10:12:12.436 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:39260): Responding with status code: 200 OK
10:12:15.250 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:36860): Responding with status code: 429 Too Many Requests
10:12:15.251 DEBUG ValidatorApp validator=bobValidator HTTP client (POST /api/sv/v0/onboard/validator): HTTP request took 8033 ms to complete. Received response with status code: 429 Too Many Requests
10:12:15.916 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:36892): received request.
10:12:15.919 DEBUG HttpRequestLogger SV=sv1 HTTP POST /api/sv/v0/onboard/validator from (127.0.0.1:36892): Responding with status code: 200 OK
10:12:15.920 DEBUG ValidatorApp validator=bobValidator HTTP client (POST /api/sv/v0/onboard/validator): HTTP request took 4 ms to complete. Received response with status code: 200 OK
```

## 6. The first submission waited in sv1's mempool until sv1 left the blacklist

Sent to globalSequencerSv1 at 10:11:20.443 (before epoch 16), ordered at 10:12:11.014 in epoch 19, block 541, the first
epoch without sv1 on the blacklist.
```
grep -a '127606b0-dbc0-4533-ba87-5cd41ca9bd47' log/11334/canton-1007-1013.jsonl | grep -aE 'Mempool accepting|max sequencing time|may change ordering topology' | grep -a 'globalSequencerSv1' | jq -r '[.["@timestamp"][11:23], (.logger_name|split(":")|.[0]|split(".")|last), (.message|gsub("\n";" ")|gsub("::1220[0-9a-f]{64}";"")|.[0:200])] | join(" ")'
```
```
10:11:20.443 BlockSequencer Request to send submission with id 127606b0-dbc0-4533-ba87-5cd41ca9bd47 with max sequencing time 1970-01-01T00:01:00Z from PAR::sv1::12207a58b543... to HashSet(MemberRecipient(PAR::sv2::122072315790..
10:12:11.014 OutputModule BFT ordering request at index 5 in output block (epochNumber=19, blockNumber=541) with message ID 127606b0-dbc0-4533-ba87-5cd41ca9bd47 may change ordering topology: false
```
(The message id is taken from sv1Participant's `Sending message ID 127606b0-... to sequencer SEQ::sv1` at 10:11:20.440 for
the SubmitAndWait with tid 0f2bdcb56a2dbe72e56a8556c1bc2b6b, which the SV app logs as `succeeded(OK)` at 10:12:12.422,
section 8.)

## 7. The 20 failures: blacklisted sv1 mempool rejections of the retried onboarding

All 20 SEQUENCER_BACKPRESSURE failures are sv1 SV app SubmitAndWait calls without a trigger span (HTTP handler), and
globalSequencerSv1 logged exactly 20 blacklist rejections in the same 12 s, nothing else in the window.
```
T=log/11334/logs-simtime-3/canton_network_test.clog.gz
zcat $T | grep -a 'SEQUENCER_BACKPRESSURE' | jq -r 'select(.logger_name|startswith("o.l.s.a.a.c.ApiClientRequestLogger")) | .["@timestamp"][11:23]' | awk '{n++; t[n]=$1} END{print n" SubmitAndWait failures with SEQUENCER_BACKPRESSURE, first "t[1]", last "t[n]}'
grep -a 'currently blacklisted, rejecting' log/11334/canton-1007-1013.jsonl | grep -a 'MempoolModule' | jq -r '[.["@timestamp"][11:23], (.logger_name|capture("sequencer=(?<s>[A-Za-z0-9]+)").s)] | join(" ")' | awk '{c[$2]++; if(!f[$2]) f[$2]=$1; l[$2]=$1} END{for(k in c) print k, c[k], "mempool rejections, first", f[k], "last", l[k]}'
zcat $T | grep -a 'SEQUENCER_BACKPRESSURE' | jq -r 'select(.logger_name|startswith("o.l.s.a.a.c.ApiClientRequestLogger")) | ."span-name" // "-"' | sort | uniq -c
zcat $T | awk '/"@timestamp":"2026-11-10T10:1[0-3]/' | grep -a 'SEQUENCER_BACKPRESSURE' | jq -r 'select(.logger_name|startswith("o.l.s.a.a.c.ApiClientRequestLogger")) | .logger_name|split("/")|last' | sort | uniq -c
```
```
20 SubmitAndWait failures with SEQUENCER_BACKPRESSURE, first 10:11:58.722, last 10:12:10.445
globalSequencerSv1 20 mempool rejections, first 10:11:58.671 last 10:12:10.427
     20 -
     20 SV=sv1
```
The handler log names the rejecting sequencer (one handler line, shortened with `...`):
```
10:12:09.611 INFO o.l.s.s.a.h.HttpSvPublicHandler The operation 'onboard validator via DsoRules' failed with a retryable error (full stack trace omitted): category=Some(ContentionOnSharedResources) ErrorInfoDetail(SEQUENCER_BACKPRESSURE,HashMap(participant -> sv1Participant, reason -> SendAsyncErrorGrpc(   Request failed for server-DefaultSequencer-0.   GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,a12da213): Mempool received client ...
```
(from `jq -r '[.["@timestamp"][11:23], .level, (.logger_name|split(":")[0]), (.message|gsub("\n";" ")|.[0:400])] | join(" ")' log/11334/bp.jsonl | tail -6`,
where `bp.jsonl` is `zcat canton_network_test.clog.gz | grep -a SEQUENCER_BACKPRESSURE`; the reason text continues
`Mempool received client request but this node is currently blacklisted, rejecting', 'sequencer=>globalSequencerSv1'`,
`grep -a -o 'Mempool received client[^"]\{0,300\}' log/11334/bp.jsonl | sort -u | head -3`.)

## 8. Breaker trip, the first submission completes, the retry gives up

```
zcat $T | awk '/"@timestamp":"2026-11-10T10:12:1[0-6]/' | grep -a 'SV=sv1' | grep -aE 'tripped|Total maximum number of retries|0f2bdcb56a2dbe72e56a8556c1bc2b6b.*succeeded' | jq -r '[.["@timestamp"][11:23], .level, (.logger_name|split(":")|.[0]|split(".")|last), (.message|gsub("\n";" ")|.[0:170])] | join(" ")'
```
```
10:12:10.446 WARN SpliceCircuitBreaker Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure
10:12:12.422 DEBUG ApiClientRequestLogger Request (tid:0f2bdcb56a2dbe72e56a8556c1bc2b6b) com.daml.ledger.api.v2.CommandService/SubmitAndWait to 127.0.0.1:15101: succeeded(OK)
10:12:15.250 INFO HttpSvPublicHandler The operation 'onboard validator via DsoRules' has failed with an exception. Total maximum number of retries 12 exceeded. Giving up. 
```
WARNs in 10:11 to 10:12 in the test log: the HTTP timeout (ignored by the shard's patterns, not flagged in section 1) and
the breaker WARN.
```
zcat $T | grep -a '"level":"WARN"' | awk '/"@timestamp":"2026-11-10T10:1[12]/' | jq -r '[.["@timestamp"][11:23], (.logger_name|split(":")|.[0]|split(".")|last), (.logger_name|split("/")|last), .message[0:120]] | join(" ")'
```
```
10:11:58.406 HttpErrorHandler SV=sv1 Request to http://127.0.0.1:5114/api/sv/v0/onboard/validator (POST) resulted in a timeout after 38 seconds.
10:12:10.446 SpliceCircuitBreaker SV=sv1 Circuit breaker low-priority-connection tripped after 20 failures. Attaching last failure
```

## 9. Code: why one HTTP request can produce many low-priority failures (splice 6dbbda48ca)

The handler retries the onboarding with `retryForClientCalls`; the submission goes through the SV's low-priority
connection without command dedup, so a retried HTTP request is a new submission (rejected by the mempool, not
`SUBMISSION_ALREADY_IN_FLIGHT`). Tests set the SV breaker to 20 failures.
```
F=apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/admin/http/HttpSvPublicHandler.scala
git show 6dbbda48ca:$F | awk 'NR>=137 && NR<=142 || NR>=810 && NR<=815 {printf "%d: %s\n", NR, $0}'
git show 6dbbda48ca:apps/app/src/test/resources/include/svs/_sv.conf | awk 'NR>=52 && NR<=60 {printf "%d: %s\n", NR, $0}'
```
```
137:                           // We retry here because this mutates the AmuletRules and rounds contracts,
138:                           // which can lead to races.
139:                           _ <- retryProvider.retryForClientCalls(
140:                             "onboard_validator",
141:                             "onboard validator via DsoRules",
142:                             onboardValidator(
810:       _ <- dsoStoreWithIngestion
811:         .connection(SpliceLedgerConnectionPriority.Low)
812:         .submit(Seq(svParty), Seq(dsoParty), cmds)
813:         .withSynchronizerId(dsoRules.domain)
814:         .noDedup // No command-dedup required, as the ValidatorOnboarding contract is archived
815:         .yieldUnit()
52:     circuit-breakers {
53:       # increase the max-failures in tests as things can be flaky
54:       medium-priority {
55:         max-failures = 20
56:       }
57:       low-priority {
58:         max-failures = 20
59:       }
60:     }
```

## 10. Duplicate check

Families: not AF (no time advance before the WARN, section 2; AF is medium-priority LOCAL_VERDICT_TIMEOUT); not AI
(no SUBMISSION_ALREADY_IN_FLIGHT, the onboarding is `noDedup`); B confirmed (sections 3, 4). 11255 ("Timeouts due to
changing bft ordering topology", open, rautenrieth-da) is the parent for B under the post-#8234 setup; its 2026-11-08
comment by rautenrieth-da says the 1 -> 4 growth "under wall clock ... usually only trips a circuit breaker or a client
timeout"; this is that outcome in a simtime shard where setup still completed.
```
gh api repos/DACH-NY/cn-test-failures/issues/11255 --jq '"#\(.number) [\(.state)] \(.title) assignees=\([.assignees[].login]|join(","))"'
for q in '"low-priority-connection tripped"' '"onboard validator via DsoRules"' '"currently blacklisted"'; do echo "## $q"; gh api -X GET search/issues -f q="repo:DACH-NY/cn-test-failures is:issue $q" --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"'; done
gh pr view 8356 -R canton-network/splice --json state,title --jq '"\(.state) \(.title)"'
```
```
#11255 [open] Timeouts due to changing bft ordering topology assignees=rautenrieth-da
## "low-priority-connection tripped"
#8243 closed/duplicate 2026-05-01 circuit breaker trips in LSU test
#8414 closed/completed 2026-05-13 LsuIntegrationTest: `sequencerUrlSet` misses a sequencer
#6502 closed/completed 2025-11-13 ManualSignatureIntegrationTest : Canton shuts down in the middle of the test
#5610 closed/completed 2025-09-18 Validator1PreflightIntegrationTest fails at "test splitwell group creation and payment against validator"
#5534 closed/not_planned 2025-09-12 Splitwell test failed in preflight on cimain
#5516 closed/completed 2025-09-09 wallet onboard in preflight
## "onboard validator via DsoRules"
#7430 closed/completed 2026-03-07 splitwell fails to initialize because sv1 is NOT_CONNECTED_TO_ANY_SYNCHRONIZER
#7616 closed/completed 2026-03-19 NOT_CONNECTED_TO_SYNCHRONIZER bobValidator app initialization: Initialization failed, at Ensure validator is o
#4940 closed/not_planned 2025-07-09 SvOnboardingIntegrationTest - onboard validator via DsoRules - Package-name 'splice-dso-governance' appearing 
## "currently blacklisted"
#11164 open/- 2026-11-03 WalletSurviveCantonRestartIntegrationTest: first onboarding times out (HTTP 503) while the sv1 BFT sequencer i
#11313 open/- 2026-11-09 BootstrapPackageConfigIntegrationTest: close vote commits after the 45 s check, BFT view changes and blacklist
#10227 closed/not_planned 2026-09-25 DistributedDomainIntegrationTest fails to onboard alice
#10046 closed/completed 2026-09-04 SvStateManagementIntegrationTest: gives up waiting for vote expiry because expiration command was stuck inside
#10057 closed/completed 2026-09-07 SV runbook fails to startup due to being blacklisted
#9355 closed/completed 2026-07-22 Pipeline 75428 : workflow deploy_sv_reonboard Failed :fire:
#9339 closed/completed 2026-07-21 Pipeline 75334 : workflow deploy_sv_reonboard Failed :fire:
#10303 closed/completed 2026-10-07 Warn log: SEQ signed with X but it cannot be verified in the currently known dissemination topology
OPEN Disable BFT sequencer blacklisting in integration tests
```
Per hit: 8243, 8414 (LSU test breaker, May, before BFT in all tests), 6502, 5610, 5534, 5516 (preflight / shutdown,
2025): not this failure. 7430, 7616 (NOT_CONNECTED_TO_SYNCHRONIZER), 4940 (package name): not this failure. 11164
(wall clock, sv1 blacklisted without a topology change, onboarding HTTP 503): same rejection path, different trigger
(no 1 -> 4 step), not a duplicate. 11313 (view changes with topology size 4 throughout): not this failure. 10227, 10046,
10057, 9355, 9339, 10303: older blacklisting incidents, not this setup step. None is the same failure, so the count is
this one hit of the breaker shape under 11255.

## Verdict

Duplicate of 11255 (family B): the 1 -> 4 ordering-topology step during initDso leaves sv1 alone authenticated, its
epoch-15 segments view-change and the peers blacklist sv1 for 3 epochs; meanwhile sv1's own clients get
`SEQUENCER_OVERLOADED ... currently blacklisted` and the SV's low-priority breaker counts 20 of them from one retried
validator-onboarding HTTP call. Flake, not a splice bug; the WARN is the only symptom here (setup completed).
Fix belongs with 11255: canton-network/splice#8356 (OPEN, "Disable BFT sequencer blacklisting in integration tests")
removes exactly this rejection path; sequential sequencer onboarding (11255 discussion) removes the trigger. No ignore
pattern: the breaker WARN is the signal for this family on shards that do not wedge.
Not verified: why the batch with message 127606b0 was not ordered by another leader in epochs 16 to 18 (only that it
was ordered in epoch 19, section 6); why the BFT blacklisting rejects clients of the blacklisted node as overloaded
(Canton 3.7.3-snapshot behaviour, not checked against the jar).
