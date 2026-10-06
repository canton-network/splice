# 10289 - sv1Participant acknowledge-signed hangs 120 s after a mempool rejection because sv1 is blacklisted following the BFT 1 -> 4 step (run 37461645814)

Family B (umbrella 10165), signature (1), same trigger and same failure mode as 10279 / 10283, but a different
rejection branch: the ack was NOT sent in the quorum gap. main a901c0d918, job 112262881363
`ci / scala_test_wall_clock_time / wall-clock-time (6)`, canton 3.6.1. All 22 tests passed; checkErrors flagged one
WARN in `canton_before_shutdown.clog`: sv1Participant's periodic `acknowledge-signed` for clean timestamp
12:23:35.376115 to globalSequencerSv1 (:5108) gave up after 120 s at 12:26:43.448. Trigger: SvInitializationIntegrationTest
"start and restart cleanly" runs `initDso()` (`SvInitializationIntegrationTest.scala:37` at a901c0d918); sv2-sv4 call
`onboard/sv/sequencer` within 2.7 s and epoch 15 activates a 4-node ordering topology at 12:23:35.372116. sv1 is below
weak quorum from 12:23:35.666, then blacklisted for epochs 16-18 (12:23:57.672-12:25:06.913). The ack was sent at
12:24:43.444, 65 s after the P2P gap closed and inside the blacklist window; the mempool rejected it within 1 ms with
`this node is currently blacklisted, rejecting`, and the AcknowledgeSigned call stayed open until the client's 120 s
deadline. Flake, Canton-side; no fix branch.

How it relates to the other refs (the distinction matters for the tracker):
- Same trigger as 10165, 10279, 10283: an `initDso` with several joining SVs steps the BFT ordering topology 1 -> 4 in
  one epoch before the newcomers are P2P-authenticated, so sv1 loses quorum and then gets blacklisted.
- Same failure mode and symptom as 10279 / 10283 (and 10153 / 10161 / 10212 / 10225): a mempool rejection of an
  `acknowledge-signed` does not complete the gRPC call; the client logs `DEADLINE_EXCEEDED after 120 s`.
- Different from 10279 / 10283 in the rejection branch and timing: there the ack hit `P2P connectivity is not ready`
  inside the 3-4 s quorum gap; here it hit `currently blacklisted, rejecting` 68 s after the step. 10153's packet
  records both rejection kinds on the same sequencer, but its stuck ack was the P2P-gap kind.
- Different symptom from 10165 (an `onboard/sv/sequencer` HTTP 38 s timeout).
- Consequence: the exposure window is not the few seconds of quorum loss but the whole blacklist period (~69 s
  here, 438 rejections), so any client request routed to sv1's sequencer in that period can hang 120 s.

- Run: https://github.com/canton-network/splice/actions/runs/37461645814, main a901c0d918 ("[ci] Scan: bulk storage
  backfilling progress and verified copier (#7598)"), job 112262881363 `ci / scala_test_wall_clock_time / wall-clock-time (6)`.
  Only failed job in the run.
- Runtime canton: 3.6.1.
- Component: Canton BFT orderer (mempool rejection does not complete AcknowledgeSigned; ordering topology activated
  before P2P authentication). Test trigger: `initDso()` with 4 SVs.
- Artifact: `logs-wall-clock-time-6` in `log/10289/logs-wall-clock-time-6/`.

## 1. Classification: all tests passed, one flagged WARN, no split problem

```
$ gh run view 37461645814 --repo canton-network/splice --json headBranch,headSha,displayTitle,createdAt,status,jobs --jq '{b:.headBranch,sha:.headSha[0:10],t:.displayTitle,c:.createdAt,s:.status}, (.jobs[]|select(.conclusion!="success" and .conclusion!="skipped")|{id:.databaseId,name:.name,c:.conclusion})'
{"b":"main","c":"2026-10-06T12:12:42Z","s":"completed","sha":"a901c0d918","t":"[ci] Scan: bulk storage backfilling progress and verified copier (#7598)"}
{"c":"failure","id":112262881363,"name":"ci / scala_test_wall_clock_time / wall-clock-time (6)"}

$ gh api repos/canton-network/splice/actions/jobs/112262881363/logs > log/10289/job.log
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10289/job.log | grep -a -E 'Tests: succeeded|All tests passed|contains problems|We are running' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++'
We are running 10 tests in this batch:
[info] Tests: succeeded 22, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.

$ git show a901c0d918:nix/canton-sources.json | grep -m1 '"version"'
  "version": "3.6.1",
```

Ten suites in the batch (SvInitialization, SequencerPruning, BootstrapPackageConfigDarUpload,
ConfigurationProvidedBftScanConnection, SvDsoPartyManagement, AmuletExpiry, ScanHistoryBackfilling, WalletPayment,
MultiHostValidatorOperator, WalletTxLogWithSynchronizerFees), so not the zero-time split of 10285 / 10286 / 10288.

Flagged lines (only one, `Total: 1 lines with problems.`):

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10289/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -a -v 'ignore this line in check-sbt-output' | sed -E 's/1220[0-9a-f]{60}/../g' | cut -c1-420
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-10-06T12:26:43.448Z","message":"Request failed for server-SEQ::sv1::..-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999965020s. Name resolution delay 0.000000000 seconds. [closed=[], open=[[call_credentials_delay=9426ns, remote_addr=localhost/127.0.0.1:5108]]]\n  Request: acknowledge-signed/2026-10-06T12:23:35.376115Z","logger_name":"c.d.c.s.c.p.GrpcConnection:participant=sv1Participant/psid=global-domain::..::36-0/pool=main/connection=SEQ::sv1::..-0"
Total: 1 lines with problems.
```

## 2. Which test was running

```
$ T=log/10289/logs-wall-clock-time-6/canton_network_test.clog.gz
$ zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a -E 'T12:(1[89]|2[0-8])' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170 | head -3
2026-10-06T12:21:45.950Z Starting test suite 'SvInitializationIntegrationTest'...",
2026-10-06T12:21:45.967Z Starting 'SvInitializationIntegrationTest/start and restart cleanly'...",
2026-10-06T12:25:33.011Z Test succeeded: 'SvInitializationIntegrationTest/start and restart cleanly'",

$ git show a901c0d918:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvInitializationIntegrationTest.scala | grep -n -E 'initDso|"start and restart cleanly"' | head -2
36:  "start and restart cleanly" in { implicit env =>
37:    initDso()

$ zcat $T | grep -a 'T12:2[2-4]' | grep -a -E 'onboard/sv/(start|sequencer)' | grep -a 'received request' | sed -E 's/"logger_name":"([^"]*)".*/[\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-120
2026-10-06T12:23:16.694Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:44070): received request.",
2026-10-06T12:23:17.294Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:44076): received request.",
2026-10-06T12:23:18.062Z HTTP POST /api/sv/v0/onboard/sv/start from (127.0.0.1:44104): received request.",
2026-10-06T12:23:29.405Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:47002): received request.",
2026-10-06T12:23:29.508Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:47016): received request.",
2026-10-06T12:23:32.040Z HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:47028): received request.",
```

The three joining SVs reach sv1's `onboard/sv/sequencer` within 2.7 s, so all three newcomers enter in one topology change.

## 3. globalSequencerSv1: 1 -> 4 step, quorum gap, then sv1 blacklisted for epochs 16-18

The window was pre-filtered once into `log/10289/win.jsonl` (canton log, 12:22-12:27, family B markers):

```
$ zcat log/10289/logs-wall-clock-time-6/canton_before_shutdown.clog.gz | grep -a -E '"@timestamp":"2026-10-06T12:2[2-7]' | grep -a -E 'c3e72954e92b30247dcca479415f5a65|New epoch [0-9]+ has started|below weak quorum|P2P connectivity is not ready|onboard|Acknowledging clean timestamp|DbStorageSingle|LockCheckRejected|insert block' > log/10289/win.jsonl
$ grep -a 'sequencer=globalSequencerSv1' log/10289/win.jsonl | grep -a 'New epoch' | sed -E 's/1220[0-9a-f]{60}/../g; s/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started with leaders = List\(([^)]*)\)and blacklisted nodes = List\(([^)]*)\).*/\1 epoch=\2 leaders=[\3] blacklisted=[\4]/' | cut -c1-200 | sed -n '14,20p'
2026-10-06T12:23:22.110Z epoch=13 leaders=[SEQ::sv1::..784b] blacklisted=[]
2026-10-06T12:23:28.892Z epoch=14 leaders=[SEQ::sv1::..784b] blacklisted=[]
2026-10-06T12:23:35.679Z epoch=15 leaders=[SEQ::sv4::..0bdd, SEQ::sv1::..784b, SEQ::sv2::..9c96, SEQ::sv3::..1288] blacklisted=[]
2026-10-06T12:23:57.672Z epoch=16 leaders=[SEQ::sv3::..1288, SEQ::sv4::..0bdd, SEQ::sv2::..9c96] blacklisted=[SEQ::sv1::..784b]
2026-10-06T12:24:20.835Z epoch=17 leaders=[SEQ::sv4::..0bdd, SEQ::sv2::..9c96, SEQ::sv3::..1288] blacklisted=[SEQ::sv1::..784b]
2026-10-06T12:24:49.947Z epoch=18 leaders=[SEQ::sv2::..9c96, SEQ::sv3::..1288, SEQ::sv4::..0bdd] blacklisted=[SEQ::sv1::..784b]
2026-10-06T12:25:06.913Z epoch=19 leaders=[SEQ::sv4::..0bdd, SEQ::sv1::..784b, SEQ::sv2::..9c96, SEQ::sv3::..1288] blacklisted=[]

$ grep -a 'sequencer=globalSequencerSv1' log/10289/win.jsonl | grep -a -m1 'New epoch 15 has' | sed -E 's/1220[0-9a-f]{60}/../g' | grep -oE 'activationTime = [^,]+,|size = [0-9]+|weakQuorum = [0-9]+'
activationTime = 2026-10-06T12:23:35.372116Z,
size = 4
weakQuorum = 2

$ grep -a 'below weak quorum' log/10289/win.jsonl | grep -a -oE '"@timestamp":"[^"]+"|sequencer=[A-Za-z0-9]+' | paste - -
"@timestamp":"2026-10-06T12:23:35.666Z"	sequencer=globalSequencerSv1
"@timestamp":"2026-10-06T12:23:38.791Z"	sequencer=globalSequencerSv1
```

Rejections on globalSequencerSv1, by branch:

```
$ grep -a 'P2P connectivity is not ready' log/10289/win.jsonl | grep -a 'MempoolModule:sequencer=globalSequencerSv1' | grep -a -oE '"@timestamp":"[^"]+"' | sed -n '1p;$p'; grep -a 'P2P connectivity is not ready' log/10289/win.jsonl | grep -a -c 'MempoolModule:sequencer=globalSequencerSv1'
"@timestamp":"2026-10-06T12:23:35.729Z"
"@timestamp":"2026-10-06T12:23:38.528Z"
18

$ zcat log/10289/logs-wall-clock-time-6/canton_before_shutdown.clog.gz | grep -a 'currently blacklisted, rejecting' | grep -a 'sequencer=globalSequencerSv1' | grep -a -oE '"@timestamp":"[^"]+"' | sed -n '1p;$p'; zcat log/10289/logs-wall-clock-time-6/canton_before_shutdown.clog.gz | grep -a 'currently blacklisted, rejecting' | grep -a -c 'sequencer=globalSequencerSv1'
"@timestamp":"2026-10-06T12:23:58.819Z"
"@timestamp":"2026-10-06T12:25:05.725Z"
438
```

Two rejection periods: the P2P quorum gap (12:23:35.729-38.528, 18 rejections) and the blacklist period
(12:23:58.819-12:25:05.725, 438 rejections, epochs 16-18).

## 4. The stuck ack, end to end by trace id

```
$ zcat log/10289/logs-wall-clock-time-6/canton_before_shutdown.clog.gz | grep -a c3e72954e92b30247dcca479415f5a65 | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-230
2026-10-06T12:24:43.444Z Acknowledging clean timestamp: 2026-10-06T12:23:35.376115Z",[c.d.c.s.c.PeriodicAcknowledgements:participant=sv1Participant/psid=global-domain::12203fab990f::36-0] DEBUG
2026-10-06T12:24:43.444Z [acknowledge] requesting 1 connection(s) excluding Set() allowing only Set(sv1, sv3, sv2, sv4)",[c.d.c.s.c.p.SequencerConnectionPoolImpl:participant=sv1Participant/psid=global-domain::..c014::36-0/pool=main] DEBUG
2026-10-06T12:24:43.444Z [acknowledge] returning Set(sequencer-connection-SEQ::sv1::..784b-0)",[c.d.c.s.c.p.SequencerConnectionPoolImpl:participant=sv1Participant/psid=global-domain::..c014::36-0/pool=main] DEBUG
2026-10-06T12:24:43.445Z Sending request acknowledge-signed/2026-10-06T12:23:35.376115Z to server-SEQ::sv1::..784b-0.",[c.d.c.s.c.p.GrpcConnection:participant=sv1Participant/psid=global-domain::..c014::36-0/pool=main/connection=SEQ::sv1::..784b-0] DEBUG
2026-10-06T12:24:43.445Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:48366: received a message AcknowledgeSignedRequest(ByteString)",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
2026-10-06T12:24:43.445Z Request for member PAR::sv1::1220e9c6af8b... to acknowledge timestamp 2026-10-06T12:23:35.376115Z",[c.d.c.s.s.b.BlockSequencer:sequencer=globalSequencerSv1/psid=global-domain::12203fab990f::36-0] DEBUG
2026-10-06T12:24:43.446Z member PAR::sv1::1220e9c6af8b... acknowledging timestamp 2026-10-06T12:23:35.376115Z",[c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=globalSequencerSv1/psid=global-domain::12203fab990f::36-0] DEBUG
2026-10-06T12:24:43.446Z Mempool received client request but this node is currently blacklisted, rejecting",[c.d.c.s.s.b.b.c.m.m.MempoolModule:sequencer=globalSequencerSv1/psid=global-domain::12203fab990f::36-0] INFO
2026-10-06T12:26:43.444Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:48366: cancelled",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] INFO
2026-10-06T12:26:43.448Z Request failed for server-SEQ::sv1::..784b-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.999965020s. Name resolution delay 0.000000000 seconds. [closed=[], open=[[call_credentials_delay=9426ns, remot
2026-10-06T12:26:43.448Z Retry has not been configured for GrpcClientGaveUp, giving up.",[c.d.c.s.c.p.GrpcConnection:participant=sv1Participant/psid=global-domain::..c014::36-0/pool=main/connection=SEQ::sv1::..784b-0] DEBUG
2026-10-06T12:26:43.448Z Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-SEQ::sv1::..784b-0.\n  GrpcClientGaveUp: DEADLINE_EXCEEDED/CallOptions deadline exceeded after 119.9999
2026-10-06T12:26:43.528Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:48366: sending response AcknowledgeSignedResponse()",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
2026-10-06T12:26:43.528Z Request com.digitalasset.canton.sequencer.api.v30.SequencerService/AcknowledgeSigned by grpc:/127.0.0.1:48366: succeeded(OK)",[c.d.c.l.a.ApiRequestLogger:sequencer=globalSequencerSv1] DEBUG
```

Sent 12:24:43.445, rejected 12:24:43.446 (blacklist branch, 1 ms), server-side call cancelled by the client at
12:26:43.444, response written 84 ms after the cancel. The `Failed to acknowledge clean timestamp` companion line is
ignored by `canton_log.ignore.txt` (as in 10279); only the GrpcConnection line is flagged. The acked clean timestamp
is 4 ms after epoch 15's activation (12:23:35.372116); in 10279 it was 1 us before, in 10283 1.74 s before, so the
offset carries no signal. The client is a participant (sv1Participant), not the mediator as in 10279 / 10283.

## 5. Not a Postgres stall (10281 / 10276) and not family L

```
$ grep -a -c 'insert block' log/10289/win.jsonl
0
$ zcat log/10289/logs-wall-clock-time-6/canton_before_shutdown.clog.gz | grep -a 'LockCheckRejected' | grep -a -oE '"@timestamp":"2026-10-06T[0-9]{2}:[0-9]{2}' | cut -c25- | uniq -c | awk '{printf "%s:%s ", $2, $1} END {print ""}'
T12:18:4 T12:19:2 T12:22:1 T12:23:6 T12:24:8 T12:25:25 T12:26:45 T12:27:44 T12:28:22 T12:29:8 T12:30:4 T12:31:2 T12:33:2 T12:35:13 T12:36:21 T12:37:22 T12:38:13 T12:39:23 T12:40:27 T12:41:3 T12:42:3 T12:43:4
```

DB lock-check rejections are low (6 and 8 per minute) across the step and the ack (12:23-12:24); the later bumps
(12:25-12:28, 12:35-12:40) come after the ack was already rejected and are not examined here.

## 6. Log files checkErrors never reached

`checkErrors` stops at the first failing file (`build.sbt:2304-2305`), so the after-shutdown, standalone and
test logs were never checked in CI. Re-run here with `check-logs.sh`, `split-canton-logs.sh` and the ignore files
taken from a901c0d918 (same ignore sets as `build.sbt:2326-2361`; the two standalone instances have no
instance-specific ignore file):

```
$ S=<scratch>; git show a901c0d918:.github/actions/scripts/{check-logs.sh,io-utils.sh,split-canton-logs.sh} -> $S/.github/actions/scripts/; git show a901c0d918:project/ignore-patterns/* -> $S/project/ignore-patterns/
$ cd $S; I=project/ignore-patterns; for each standalone n: split-canton-logs.sh log/$n.clog log/${n}_before_shutdown.clog log/${n}_after_shutdown.clog;
  check-logs.sh <before> $I/canton_log.ignore.txt $I/canton_log_bft.ignore.txt; check-logs.sh <after> ... $I/canton_log_shutdown_extra.ignore.txt
  check-logs.sh log/canton_after_shutdown.clog $I/canton_log.ignore.txt $I/canton_log_bft.ignore.txt $I/canton_log_shutdown_extra.ignore.txt
  check-logs.sh log/canton_network_test.clog $I/canton_network_test_log.ignore.txt
rc=0 log/canton-standalone-boostrap-package-config-dar-upload_before_shutdown.clog
rc=0 log/canton-standalone-boostrap-package-config-dar-upload_after_shutdown.clog
rc=0 log/canton-standalone-stop-validator-before-pruning-sequencer_before_shutdown.clog
rc=0 log/canton-standalone-stop-validator-before-pruning-sequencer_after_shutdown.clog
rc=0 log/canton_after_shutdown.clog
rc=0 log/canton_network_test.clog
```

No hidden problems (canton_after_shutdown is empty; the dar-upload standalone has 39 ignored lines, the test log 31).

## Verdict

- Family B (umbrella 10165), signature (1). Same trigger (the 1 -> 4 BFT step in a 4-SV `initDso`) and same failure
  mode and symptom (rejected `acknowledge-signed` left open until the 120 s client deadline) as 10279 and 10283.
  Not the same rejection: here `currently blacklisted, rejecting` 68 s after the step, not `P2P connectivity is not
  ready` inside the quorum gap. The hanging-call defect therefore covers every mempool rejection branch, and the
  exposure window is the full blacklist period (~69 s), not only the 3 s quorum gap.
- New test trigger for this family: SvInitializationIntegrationTest "start and restart cleanly" (previous ack hits
  were ValidatorIntegrationTest). New client: sv1Participant on canton 3.6.1 (10283 was the mediator).
- Flake, Canton-side. Fix belongs in the Canton BFT orderer: complete the AcknowledgeSigned call on a mempool
  rejection (either branch), and/or do not activate the new ordering topology before newcomers are authenticated.
  Splice mitigation as in the family entry: serialise the SV sequencer onboardings in `initDso`. No ignore pattern:
  the WARN is the only signal for the hanging call. No fix branch.
- Not verified: why sv1Participant's clean timestamp was still 12:23:35.376115 at 12:24:43 (no events processed for
  68 s while its only sequencer connection pointed at the blacklisted sv1); whether the pool would have used another
  sequencer (it allowed sv1-sv4 but held only the SEQ::sv1 connection); the canton 3.6.1 source of the blacklist
  rejection path (no jar inspected).
