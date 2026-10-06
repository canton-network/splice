# 10280 - cometbft (0) checkErrors: two SVs' ReconcileCometBftNetworkConfigWithDsoRulesTrigger WARN `timed out waiting for tx to be included in a block` (broadcast_tx_commit 10 s) while CometBFT block production slowed to 2.4/3.6/7.6 s per block (run 37325190212)

New (family S). main f9bba5fea7, job `cometbft (0)`, canton 3.6.0-snapshot.20261001.20345.0.v85a9270a. All 11 tests pass
(CometBftClientIntegrationTest 5, SvCometBftIntegrationTest 6); checkErrors flags two WARNs in `canton_network_test.clog`.
At the end of "sv3 can switch to a new governance key set in its config", sv2, sv4 and sv1 each vote (via their own
CometBFT node) for the same change, sv3's node config at revision 1 with the new governance key. sv2's vote commits in
2.3 s. sv4's (14:41:07.201) and sv1's (14:41:07.488) `broadcast_tx_commit` calls get no result for 10.0 s and fail
with JSON-RPC -32603 `timed out waiting for tx to be included in a block` at 14:41:17.209 / 17.491. In that window the
chain is slow: heights 378, 379 and 380 take 2.413 s, 3.617 s and 7.631 s (0.2-1.0 s before and after), and Canton
sequencer submissions wait 4-9 s for ordering instead of about 1 s. The two votes never land. Sequencer txs sent to
cometbft1 120-350 ms after sv1's vote make it into block 379, but sv1's vote does not, so it left the mempool without
a commit event. The sv3 change still reaches revision 2 by 14:41:23.012, and the test passed at 14:41:10.264 because it
only checks the DSO-side key. Why consensus slowed cannot be shown: the CometBFT node logs (GH service containers on
the k8s runner) are not in the job log or any artifact. The WARN level is an app decision: `CometBftClient` maps
this HTTP-200 JSON-RPC error to gRPC INTERNAL, which `RetryProvider` treats as non-transient, so a single 10 s
commit timeout fails the shard.

- Run: https://github.com/canton-network/splice/actions/runs/37325190212, main f9bba5fea7 ("Give assertTickDurationOfIssuingRound 90s [ci] (#7618)"),
  job 111814423032 `ci / scala_test_with_cometbft / cometbft (0)`. The run was still in progress at triage time; this was its only failed job then.
- Runtime canton: 3.6.0-snapshot.20261001.20345.0.v85a9270a (`git show f9bba5fea7:nix/canton-sources.json | grep -m1 '"version"'`).
- CometBFT image: `splice-test-cometbft:0.3.12` (`.github/workflows/build.scala_test_with_cometbft.yml:82-110`), five service containers cometbft1-4 and cometbft2Local.
- Component: splice SV app (`apps/sv` CometBftClient error mapping) for the WARN level; CometBFT / ABCI app (canton drivers) for the slowdown, cause unknown.
- Artifact: `logs-cometbft-0` in `log/10280/logs-cometbft-0/` (plus `-runner`, `-runner-temp`, neither has CometBFT node output).

## 1. Flagged lines

Two WARNs, sv4 then sv1, 280 ms apart. Nothing else is flagged.

```
$ gh api repos/canton-network/splice/actions/jobs/111814423032/logs > log/10280/job.log
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10280/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -a -v 'ignore this line' | cut -c1-330
Found problems in log/canton_network_test.clog:
{"@timestamp":"2026-10-05T14:41:17.211Z","message":"The operation 'pollingTriggerTask' failed with a non-transient error, restarting after 1 second:\ncategory=None\nstatusCode=INTERNAL\ndescription={\"code\":-32603,\"message\":\"Internal error\",\"data\":\"timed out waiting for tx to be included in a block\"}","logger_name":"o.l
{"@timestamp":"2026-10-05T14:41:17.491Z","message":"The operation 'pollingTriggerTask' failed with a non-transient error, restarting after 1 second:\ncategory=None\nstatusCode=INTERNAL\ndescription={\"code\":-32603,\"message\":\"Internal error\",\"data\":\"timed out waiting for tx to be included in a block\"}","logger_name":"o.l
Total: 2 lines with problems.
$ sed -E 's/\x1b\[[0-9;]*m//g' log/10280/job.log | grep -a -E 'Tests: succeeded|contains problems' | sed -E 's/^[^Z]*Z //'
[info] Tests: succeeded 5, failed 0, canceled 0, ignored 0, pending 0
[info] Tests: succeeded 6, failed 0, canceled 0, ignored 0, pending 0
[error] java.lang.RuntimeException: log/canton_network_test.clog contains problems.
[error] (checkErrors) log/canton_network_test.clog contains problems.
```
The logger names (cut above) are `o.l.s.s.a.s.ReconcileCometBftNetworkConfigWithDsoRulesTrigger:SvCometBftIntegrationTest/config=fb2048dc/SV=sv4` and `.../SV=sv1`.

## 2. Which test was running

The WARNs fall in "sv2 can reonboard a different cometbft node" (14:41:10.264-14:41:28.890). The calls that time out
started at 14:41:07.2-07.5, inside the previous test "sv3 can switch to a new governance key set in its config",
which checks only `getCurrentGovernanceKey(sv3Backend)` (`SvCometBftIntegrationTest.scala:112-121`) and does not wait for
CometBFT to apply the change.

```
$ zcat log/10280/logs-cometbft-0/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): " | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | grep -a 'SvCometBft' | cut -c1-140
2026-10-05T14:38:21.053Z Starting test suite 'SvCometBftIntegrationTest'...",
2026-10-05T14:41:00.863Z Test succeeded: 'SvCometBftIntegrationTest/all nodes become validators'",
2026-10-05T14:41:00.889Z Test succeeded: 'SvCometBftIntegrationTest/sv4 uses a governance key from config'",
2026-10-05T14:41:10.264Z Test succeeded: 'SvCometBftIntegrationTest/sv3 can switch to a new governance key set in its config'",
2026-10-05T14:41:28.890Z Test succeeded: 'SvCometBftIntegrationTest/sv2 can reonboard a different cometbft node'",
2026-10-05T14:41:37.098Z Test succeeded: 'SvCometBftIntegrationTest/removed SV has its node removed'",
2026-10-05T14:41:37.175Z Test succeeded: 'SvCometBftIntegrationTest/Sv app should expose CometBFT RPC methods required for state sync'",
```

## 3. Every CometBFT network-config change in the suite: only the 14:41:07 round times out

Each SV app's `CometBftNode.submitChangeRequest` logs `Applying` before `broadcast_tx_commit` and `Applied` after it.
Earlier rounds commit in 40-770 ms. A vote that arrives after the change has been applied fails right away with the
ABCI app's `Wrong current config revision` (14:40:18, 14:40:36). In the 14:41:07 round only sv2's vote completes. sv4's and
sv1's get no result at all for 10.008 s and 10.003 s. The configured `timeout_broadcast_tx_commit` is 10 s
(`apps/sv/src/test/resources/cometbft/sv1/config/config.toml:170`, same in sv2-sv4; that the image uses these files is assumed, the 10.0 s gaps match).

```
$ zcat log/10280/logs-cometbft-0/canton_network_test.clog.gz | python3 -c '
import sys,json,re
for l in sys.stdin:
  try: j=json.loads(l)
  except: continue
  m=re.sub(r"\s+"," ",j["message"]); lg=j["logger_name"]
  if not re.search(r"\.(CometBftNode|CometBftClient):",lg): continue
  if not re.match(r"(Applying|Applied|Failed to call)",m): continue
  sv=re.search(r"SV=(\w+)",lg).group(1)
  t=re.search(r"SvNodeConfigChangeRequest\( ?\"([^\"]+)\", (\d+)L, Some\( ?SvNodeConfigChange\( ?(\w+)",m)
  print(j["@timestamp"][11:23], sv, m.split(" ")[0], "target=%s rev=%s %s"%t.groups() if t else m[m.find("Error"):][:120])
'
14:38:52.383 sv1 Applying target=Digital-Asset-2 rev=1 SetConfig
14:38:52.523 sv1 Applied target=Digital-Asset-2 rev=1 SetConfig
14:39:26.299 sv1 Applying target=svNode2 rev=1 DeleteConfig
14:39:26.338 sv1 Applied target=svNode2 rev=1 DeleteConfig
14:40:07.338 sv1 Applying target=Digital-Asset-Eng-2 rev=0 SetConfig
14:40:07.438 sv1 Applied target=Digital-Asset-Eng-2 rev=0 SetConfig
14:40:18.280 sv1 Applying target=Digital-Asset-Eng-3 rev=0 SetConfig
14:40:18.320 sv2 Applying target=Digital-Asset-Eng-3 rev=0 SetConfig
14:40:18.349 sv1 Applied target=Digital-Asset-Eng-3 rev=0 SetConfig
14:40:18.451 sv2 Failed Error(Wrong current config revision: 0, expected: 1)
14:40:35.405 sv1 Applying target=Digital-Asset-Eng-4 rev=0 SetConfig
14:40:35.698 sv3 Applying target=Digital-Asset-Eng-4 rev=0 SetConfig
14:40:35.757 sv2 Applying target=Digital-Asset-Eng-4 rev=0 SetConfig
14:40:36.179 sv1 Applied target=Digital-Asset-Eng-4 rev=0 SetConfig
14:40:36.339 sv2 Failed Error(Wrong current config revision: 0, expected: 1)
14:40:36.342 sv3 Failed Error(Wrong current config revision: 0, expected: 1)
14:41:06.931 sv2 Applying target=Digital-Asset-Eng-3 rev=1 SetConfig
14:41:07.201 sv4 Applying target=Digital-Asset-Eng-3 rev=1 SetConfig
14:41:07.488 sv1 Applying target=Digital-Asset-Eng-3 rev=1 SetConfig
14:41:09.261 sv2 Applied target=Digital-Asset-Eng-3 rev=1 SetConfig
14:41:17.209 sv4 Failed Error(200,CometBftJsonErrorResponse(2.0,Nested1(e3032e07-f868-4017-a632-47164d6b9df7),{ "code" : -32603, "message" : "In
14:41:17.491 sv1 Failed Error(200,CometBftJsonErrorResponse(2.0,Nested1(880c2be7-be65-49cf-9a18-b4633edcd637),{ "code" : -32603, "message" : "In
14:41:23.012 sv3Local Applying target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:23.013 sv1 Applying target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:23.014 sv4 Applying target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:23.012 sv2Local Applying target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:24.123 sv2Local Applied target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:24.123 sv1 Applied target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:24.123 sv3Local Applied target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:24.123 sv4 Applied target=Digital-Asset-Eng-2 rev=1 SetConfig
14:41:35.807 sv3Local Applying target=Digital-Asset-Eng-4 rev=1 DeleteConfig
14:41:35.857 sv2Local Applying target=Digital-Asset-Eng-4 rev=1 DeleteConfig
14:41:35.927 sv3Local Applied target=Digital-Asset-Eng-4 rev=1 DeleteConfig
14:41:35.940 sv1 Applying target=Digital-Asset-Eng-4 rev=1 DeleteConfig
14:41:36.089 sv1 Applied target=Digital-Asset-Eng-4 rev=1 DeleteConfig
14:41:36.089 sv2Local Applied target=Digital-Asset-Eng-4 rev=1 DeleteConfig
```
The change is sv3's own node config (same node key `bcd9239f...`, power 1) with the governance key rotated
`hhaOM/9z...` -> `vTrMu0oc...` (the 14:41:06.931 `Reconciling difference` summary). It does not change the validator set.

## 4. CometBFT block production slowed between heights 377 and 380

Every global sequencer logs `Block N with M transactions has been fetched` from its CometBFT node. The heights match
the node status the test reads (`latest_block_height":"370"` / `"371"` at 14:41:00). Blocks 378-380 take 2.4, 3.6
and 7.6 s, against 0.2-1.0 s before and after. All four sequencers see each block within 25-75 ms of each other
(globalSequencerSv1 shown), so the CometBFT nodes are in step with each other and the whole chain is slow.

```
$ zcat log/10280/logs-cometbft-0/canton_before_shutdown.clog.gz | grep -a 'globalSequencerSv1/' | grep -a 'has been fetched' | python3 -c '
import sys,json
from datetime import datetime
f=lambda t: datetime.strptime(t,"%Y-%m-%dT%H:%M:%S.%fZ"); prev=None
for l in sys.stdin:
  j=json.loads(l); ts=j["@timestamp"]
  if not ("2026-10-05T14:41:03"<=ts<="2026-10-05T14:41:24"): continue
  d="" if prev is None else "+%.3fs"%(f(ts)-f(prev)).total_seconds(); prev=ts
  print(ts[11:23], j["message"][:45], d)'
14:41:03.782 Block 373 with 2 transactions has been fetche 
14:41:04.788 Block 374 with 8 transactions has been fetche +1.006s
14:41:05.392 Block 375 with 8 transactions has been fetche +0.604s
14:41:06.297 Block 376 with 4 transactions has been fetche +0.905s
14:41:06.499 Block 377 with 4 transactions has been fetche +0.202s
14:41:08.912 Block 378 with 1 transactions has been fetche +2.413s
14:41:12.529 Block 379 with 6 transactions has been fetche +3.617s
14:41:20.160 Block 380 with 3 transactions has been fetche +7.631s
14:41:20.463 Block 381 with 13 transactions has been fetch +0.303s
14:41:20.766 Block 382 with 4 transactions has been fetche +0.303s
14:41:20.968 Block 383 with 1 transactions has been fetche +0.202s
14:41:21.371 Block 384 with 2 transactions has been fetche +0.403s
14:41:23.689 Block 385 with 1 transactions has been fetche +2.318s
14:41:23.891 Block 386 with 8 transactions has been fetche +0.202s
```
Sequencer ordering latency, from a sequencer's `broadcast_tx_async` to delivery on globalSequencerSv1, matched by
ordering request id. It is about 1 s before 14:41:07 and 4-9 s for everything sent between 14:41:07.6 and 14:41:17.1:

```
$ zcat log/10280/logs-cometbft-0/canton_before_shutdown.clog.gz | grep -a -E 'CometBft(Client|SequencerDriver):sequencer=globalSequencerSv[1-4]/' | python3 -c '
import sys,json,re
from datetime import datetime
f=lambda t: datetime.strptime(t,"%Y-%m-%dT%H:%M:%S.%fZ"); sent={}
for l in sys.stdin:
  j=json.loads(l); m=j["message"]; ts=j["@timestamp"]; seq=re.search(r"globalSequencerSv\d",j["logger_name"]).group(0)
  a=re.search(r"broadcast_tx_async` `send` ordering request ([0-9a-f-]{36})",m)
  if a: sent[a.group(1)]=(ts,seq); continue
  b=re.search(r"received sequencer tx message: SequencerTx\(([0-9a-f-]{36})",m)
  if b and seq=="globalSequencerSv1" and b.group(1) in sent:
    s=sent.pop(b.group(1))
    if "2026-10-05T14:41:04"<=s[0]<="2026-10-05T14:41:22": print(s[0][11:23],"->",ts[11:23],"%5.2fs"%(f(ts)-f(s[0])).total_seconds(),"via",s[1])'
14:41:05.309 -> 14:41:06.298  0.99s via globalSequencerSv1
14:41:05.454 -> 14:41:06.298  0.84s via globalSequencerSv3
14:41:05.461 -> 14:41:06.298  0.84s via globalSequencerSv3
14:41:05.392 -> 14:41:06.500  1.11s via globalSequencerSv4
14:41:05.400 -> 14:41:06.500  1.10s via globalSequencerSv4
14:41:05.433 -> 14:41:06.500  1.07s via globalSequencerSv2
14:41:05.428 -> 14:41:06.500  1.07s via globalSequencerSv1
14:41:07.607 -> 14:41:12.530  4.92s via globalSequencerSv1
14:41:07.756 -> 14:41:12.530  4.77s via globalSequencerSv1
14:41:07.826 -> 14:41:12.530  4.70s via globalSequencerSv1
14:41:07.843 -> 14:41:12.530  4.69s via globalSequencerSv1
14:41:11.294 -> 14:41:20.160  8.87s via globalSequencerSv1
14:41:14.846 -> 14:41:20.160  5.31s via globalSequencerSv3
14:41:14.782 -> 14:41:20.160  5.38s via globalSequencerSv4
14:41:14.807 -> 14:41:20.463  5.66s via globalSequencerSv2
14:41:16.427 -> 14:41:20.463  4.04s via globalSequencerSv2
14:41:16.501 -> 14:41:20.463  3.96s via globalSequencerSv4
14:41:14.805 -> 14:41:20.463  5.66s via globalSequencerSv1
14:41:15.438 -> 14:41:20.463  5.03s via globalSequencerSv1
14:41:16.355 -> 14:41:20.464  4.11s via globalSequencerSv3
14:41:16.216 -> 14:41:20.464  4.25s via globalSequencerSv1
14:41:16.228 -> 14:41:20.464  4.24s via globalSequencerSv1
14:41:16.545 -> 14:41:20.464  3.92s via globalSequencerSv1
14:41:17.114 -> 14:41:20.464  3.35s via globalSequencerSv1
14:41:20.210 -> 14:41:20.766  0.56s via globalSequencerSv1
14:41:20.210 -> 14:41:20.766  0.56s via globalSequencerSv1
14:41:20.210 -> 14:41:20.766  0.56s via globalSequencerSv1
14:41:20.210 -> 14:41:20.766  0.56s via globalSequencerSv1
14:41:20.745 -> 14:41:20.969  0.22s via globalSequencerSv3
14:41:20.770 -> 14:41:21.371  0.60s via globalSequencerSv4
14:41:20.837 -> 14:41:21.371  0.53s via globalSequencerSv1
```
The host was not stalled. The Postgres-backed reference sequencers (splitwell) kept producing blocks every 0.5-0.6 s
through the window, e.g. splitwellSequencer heights 820-827 at 14:41:04.308-14:41:07.428 (`zcat ... | grep -a -E 'height [0-9]+'`),
and the canton log carries 439-817 lines per second throughout 14:41:07-14:41:19.

## 5. sv4's and sv1's votes never reached a block, though later txs from the same node did

sv3Local polls the network config every ~1 s and prints the pending votes for sv3's config. sv2's vote is pending from
block 378 (seen at 14:41:09.895). After block 379 (14:41:12.529) the pending set is still only sv2's (14:41:12.813,
13.734, 14.763), so neither sv4's nor sv1's vote is in 378 or 379. The four PAR tick submissions that
globalSequencerSv1 broadcast to cometbft1 at 14:41:07.607-07.843 (section 4), 120-350 ms after sv1's vote went to
the same node at 14:41:07.488, are in block 379. sv1's vote was therefore no longer in cometbft1's mempool when 379 was
built, and `broadcast_tx_commit` gets no event for a tx that leaves the mempool without being committed. The change
reached revision 2 at some point between 14:41:14.763 and 14:41:23.012.

```
$ zcat log/10280/logs-cometbft-0/canton_network_test.clog.gz | python3 -c '
import sys,json,re
for l in sys.stdin:
  try: j=json.loads(l)
  except: continue
  ts=j["@timestamp"]
  if not ("2026-10-05T14:41:06"<=ts<="2026-10-05T14:41:24") or "CometBftNode:" not in j["logger_name"]: continue
  m=re.sub(r"\s+"," ",j["message"])
  if not re.match(r"(Skipping reconciling|Reconciling difference)",m): continue
  i=m.find("\"Digital-Asset-Eng-3\" -> SvNodeConfigState("); seg=m[i:]
  rev=re.match(r"\"Digital-Asset-Eng-3\" -> SvNodeConfigState\( (\d+)L",seg).group(1)
  end=seg.find("\"svNode2\"") if "\"svNode2\"" in seg else 2000
  pend=re.findall(r"\"([A-Za-z0-9-]+)\" -> SvNodeConfigPendingChange",seg[:end])
  print(ts[11:23], re.search(r"SV=(\w+)",j["logger_name"]).group(1), m.split(" ")[0], "Eng-3 rev="+rev, "pending votes="+str(pend))
'
14:41:06.782 sv3Local Skipping Eng-3 rev=1 pending votes=[]
14:41:06.931 sv2 Reconciling Eng-3 rev=1 pending votes=[]
14:41:07.201 sv4 Reconciling Eng-3 rev=1 pending votes=[]
14:41:07.488 sv1 Reconciling Eng-3 rev=1 pending votes=[]
14:41:07.850 sv3Local Skipping Eng-3 rev=1 pending votes=[]
14:41:08.807 sv3Local Skipping Eng-3 rev=1 pending votes=[]
14:41:09.895 sv3Local Skipping Eng-3 rev=1 pending votes=['Digital-Asset-Eng-2']
14:41:10.962 sv3Local Skipping Eng-3 rev=1 pending votes=['Digital-Asset-Eng-2']
14:41:11.899 sv3Local Skipping Eng-3 rev=1 pending votes=['Digital-Asset-Eng-2']
14:41:12.813 sv3Local Skipping Eng-3 rev=1 pending votes=['Digital-Asset-Eng-2']
14:41:13.734 sv3Local Skipping Eng-3 rev=1 pending votes=['Digital-Asset-Eng-2']
14:41:14.763 sv3Local Skipping Eng-3 rev=1 pending votes=['Digital-Asset-Eng-2']
14:41:23.012 sv2Local Reconciling Eng-3 rev=2 pending votes=[]
14:41:23.012 sv3Local Reconciling Eng-3 rev=2 pending votes=[]
14:41:23.012 sv1 Reconciling Eng-3 rev=2 pending votes=[]
14:41:23.014 sv4 Reconciling Eng-3 rev=2 pending votes=[]
```
Open: the four reconcile triggers (sv3Local, sv1, sv4, and sv2Local, which started at 14:41:11.060) log nothing from
14:41:14.763 to 14:41:23.012, and then all four log within 2 ms. The trigger logs nothing per poll when there is no
diff, so from this log alone "no diff" and "blocked in `readNetworkConfig`" (an `abci_query`) look the same. What
removed the two votes (a CheckTx recheck in the ABCI app after block 378 versus PrepareProposal filtering) and why
consensus slowed both need the CometBFT node logs and the ABCI app (canton drivers, closed source).

## 6. CometBFT node logs are not collected on this runner

The five CometBFT nodes are GitHub service containers. The job runs on `self-hosted-k8s-medium-*` through the k8s
container hook, which prints no service-container logs, and `upload_logs` does not collect them:

```
$ grep -a -c -E 'height=|consensus|Timed out.*round' log/10280/job.log
0
$ find log/10280/logs-cometbft-0 log/10280/logs-cometbft-0-runner -type f | xargs zgrep -l -a -E 'module=consensus|enterNewRound' 2>/dev/null | wc -l
0
```

## 7. Why one 10 s timeout is a WARN

`CometBftHttpRpcClient` uses `broadcast_tx_commit`. CometBFT returns its commit timeout as HTTP 200 with JSON-RPC
error -32603. `cometBftErrorToGrpcStatus` maps any `CometBftHttpError` it does not otherwise match to INTERNAL
(`apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/cometbft/CometBftClient.scala:123-124` at f9bba5fea7).
INTERNAL is not in `RetryProvider.retryableStatusCodes` (`RetryProvider.scala:534-545`, which has DEADLINE_EXCEEDED
at :542), so the trigger logs `non-transient` at WARN on the first failure. For transient failures `PollingTrigger`
stays at INFO and only WARNs once `numConsecutiveTransientFailures > maxNumSilentPollingRetries` (3)
(`PollingTrigger.scala:181`).

```
$ git show f9bba5fea7:apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/cometbft/CometBftClient.scala | sed -n '104,110p;123,124p'
  private def cometBftErrorToGrpcStatus(error: CometBftHttpRpcClient.CometBftError) = {
    error match {
      case CometBftHttpRpcClient.CometBftHttpError(_, error)
          if error.errMessage.contains("tx already exists in cache") =>
        Status.ABORTED.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(code, error) if code > 500 =>
        Status.UNAVAILABLE.withDescription(error.errMessage)
      case CometBftHttpRpcClient.CometBftHttpError(_, error) =>
        Status.INTERNAL.withDescription(error.errMessage)
```

## 8. History

There is no other failed `cometbft (N)` job among the 54 failed post-merge runs (workflow 163795441) from 2026-09-16
to 2026-10-05 (one jobs API call returned HTTP 500). There are no ignore patterns for this line, and nothing in
`known-families.md` covers it. The trigger, `CometBftClient` and the test have not changed in this area since
2026-03-05 (#4266, #4248), apart from #7523 touching `CometBftRequestSigner`.

```
$ gh api "repos/canton-network/splice/actions/workflows/163795441/runs?per_page=100&created=2026-09-16..2026-10-05" --paginate --jq '.workflow_runs[] | "\(.id) \(.created_at) \(.head_branch) \(.conclusion)"' > log/10280/hist/runs2.txt
$ for r in $(awk '$4=="failure"{print $1}' log/10280/hist/runs2.txt); do gh api "repos/canton-network/splice/actions/runs/$r/jobs?per_page=100&filter=latest" --paginate --jq ".jobs[] | select(.name|test(\"cometbft \\\\(\")) | select(.conclusion==\"failure\") | \"$r \(.id) \(.started_at) \(.name)\""; done
37325190212 111814423032 2026-10-05T14:31:09Z ci / scala_test_with_cometbft / cometbft (0)
{
  "message": "Server Error"
}
$ git grep -n -i -E 'cometbft|included in a block' f9bba5fea7 -- project/ignore-patterns/ | cut -c1-140
f9bba5fea7:project/ignore-patterns/canton_network_test_log.ignore.txt:105:# SV UI polls cometbft status endpoint even when there's no cometBFT (we have this in the full network docker compose test)
f9bba5fea7:project/ignore-patterns/canton_network_test_log.ignore.txt:106:.*CometBFT is not configured for this app.*
$ git log --format='%h %ad %s' --date=short -4 f9bba5fea7 -- apps/sv/src/main/scala/org/lfdecentralizedtrust/splice/sv/cometbft/ apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/SvCometBftIntegrationTest.scala
33b55cb609 2026-09-30 Bump Canton fork (#7523)
7a1990bd8f 2026-03-05 cometbft support for LSU  (#4266)
243d11016e 2026-03-05 Merge remote-tracking branch 'origin/main' into canton-3.5
a5a1f52394 2026-03-04 Clean-up cometbft node usage (#4248)
```

## Verdict

New family S. It is a flake for the test, which passes and whose reconciliation converges by 14:41:23. For the SV app
the problem is the log level, not a functional failure. The trigger: a 13.7 s CometBFT slowdown (heights 377 -> 380,
14:41:06.5-14:41:20.2) during which two governance votes left the mempool uncommitted, so their
`broadcast_tx_commit` timed out at 10 s. The cause of the slowdown is unknown: CometBFT node logs are not collected
(evidence loss, section 6). Fix locations:
- SV app (described, not written; owner of `apps/sv/.../cometbft`): in `CometBftClient.cometBftErrorToGrpcStatus`,
  map the commit timeout (`CometBftHttpError` whose message contains `timed out waiting for tx to be included in a
  block`) to DEADLINE_EXCEEDED, like the existing `tx already exists in cache` -> ABORTED case. The trigger re-reads the
  network config on its next poll anyway, and a persistent stall still WARNs after 3 consecutive failures
  (`PollingTrigger.scala:181`), so the signal stays. That is why this, and not an ignore pattern, is the fix: an ignore
  would also hide a CometBFT chain that stops for good.
- CI infra: collect `docker logs` / pod logs of the cometbft1-4 and cometbft2Local service containers in the cometbft
  job's `upload_logs`, so the next occurrence can be explained.
No fix branch: the only code change is in production (`apps/sv`). Not verified: the cause of the slowdown, the ABCI
app's mempool behaviour for a vote whose target revision has a pending vote by another SV, whether the CI image uses
the repo's `config.toml`, and whether the reconcile triggers were blocked or idle in 14:41:14.8-23.0.
