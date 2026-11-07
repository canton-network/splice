# 11257 - wall-clock-time (1): checkErrors WARN, sv1 onboard/sv/sequencer request of sv2 times out after 38 s during the BFT 1 -> 2 step (run 42248944117)

DUPLICATE of 10165 (family B, signature 2). main 6f7a5f83bc, job `wall-clock-time (1)`, canton 3.7.2. All 30 tests
passed; checkErrors flagged one WARN from sv1's HttpErrorHandler in ConfigurationProvidedBftScanConnectionIntegrationTest:
`Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.`
sv2 and sv4 asked sv1 to onboard their sequencers 200 ms apart (16:36:10.101 and 16:36:10.304). sv4's request
completed in 7.7 s; the ordering topology became {sv1, sv4} at epoch 166 (16:36:16.576) while only sv1 was
authenticated (`1 is currently below strong quorum size 2`), and that epoch took until 16:36:49.021, so sv2's
request could not complete within 38 s. Blacklisting was not involved (`blacklisted nodes = List()` through epoch
169; sv4 was blacklisted from 16:37:14, after the WARN), so splice#8356 would not prevent it. wall-clock-time
shards ran the Canton BFT sequencer before #8234 already, so this is the long-standing family, not a new effect of
the switch.

- Run: https://github.com/canton-network/splice/actions/runs/42248944117, main 6f7a5f83bc, job 127999163398 `wall-clock-time (1)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: Canton BFT onboarding (family B); splice SV app handler waits synchronously.

## 1. Flagged line



```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11257/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v '^Total' | cut -c1-300; sed -E 's/\x1b\[[0-9;]*m//g' log/11257/job.log | grep -a -o 'Tests: succeeded [0-9]*, failed [0-9]*'
```

```
Found problems in log/canton_network_test.clog:
***"@timestamp":"2026-11-06T16:36:48.120Z","message":"Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.","logger_name":"o.l.s.a.h.HttpErrorHandler:ConfigurationProvidedBftScanConnectionIntegrationTest/config=fb38e8ec/SV=sv1","thread_name":
Tests: succeeded 30, failed 0
```

## 2. Requests and suite

Both onboarding requests, their outcomes, and the running suite.

```
zcat log/11257/logs-wall-clock-time-1/canton_network_test.clog.gz | jq -r 'select(.["@timestamp"] > "2026-11-06T16:34" and .["@timestamp"] < "2026-11-06T16:38:10") | select(.message|test("onboard/sv/sequencer.*(received request|Responding|took|timeout)|^Starting test suite|^Test succeeded")) | "\(.["@timestamp"]) \(.level) \(.logger_name|split("/")|.[-1]|.[0:12]) | \(.message|gsub("\n";" ")|.[0:150])"'
```

```
2026-11-06T16:34:14.609Z INFO c.d.c.LogRep | Test succeeded: 'AnsIntegrationTest/ans should an SV's ANS entry can be seen via scan api'
2026-11-06T16:34:14.699Z INFO c.d.c.LogRep | Starting test suite 'ConfigurationProvidedBftScanConnectionIntegrationTest'...
2026-11-06T16:36:10.101Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:52706): received request.
2026-11-06T16:36:10.304Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:52716): received request.
2026-11-06T16:36:17.969Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:52716): Responding with status code: 200 OK
2026-11-06T16:36:17.974Z DEBUG SV=sv4 | HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 7671 ms to complete. Received response with status code: 200 OK
2026-11-06T16:36:48.120Z WARN SV=sv1 | Request to http://127.0.0.1:5114/api/sv/v0/onboard/sv/sequencer (POST) resulted in a timeout after 38 seconds.
2026-11-06T16:36:48.125Z DEBUG SV=sv2 | HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 38026 ms to complete. Received response with status code: 503 Service Unavailabl
2026-11-06T16:36:48.334Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:44298): received request.
2026-11-06T16:36:51.109Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:44314): received request.
2026-11-06T16:36:59.309Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:52706): Responding with status code: 429 Too Many Requests
2026-11-06T16:36:59.309Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:52706): Responding with entity data: {   "error" : "SERVER_OVERLOADED(2,0): Reached the limi
2026-11-06T16:36:59.314Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:44298): Responding with status code: 429 Too Many Requests
2026-11-06T16:36:59.314Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:44298): Responding with entity data: {   "error" : "SERVER_OVERLOADED(2,0): Reached the limi
2026-11-06T16:36:59.315Z DEBUG SV=sv2 | HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 10983 ms to complete. Received response with status code: 429 Too Many Requests
2026-11-06T16:36:59.376Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:44314): Responding with status code: 200 OK
2026-11-06T16:36:59.378Z DEBUG SV=sv3 | HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 8273 ms to complete. Received response with status code: 200 OK
2026-11-06T16:36:59.541Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:34828): received request.
2026-11-06T16:36:59.715Z DEBUG SV=sv1 | HTTP POST /api/sv/v0/onboard/sv/sequencer from (127.0.0.1:34828): Responding with status code: 200 OK
2026-11-06T16:36:59.717Z DEBUG SV=sv2 | HTTP client (POST /api/sv/v0/onboard/sv/sequencer): HTTP request took 177 ms to complete. Received response with status code: 200 OK
2026-11-06T16:38:08.990Z INFO c.d.c.LogRep | Test succeeded: 'ConfigurationProvidedBftScanConnectionIntegrationTest/simple threshold normal case validator onboarding succeeds'
```

## 3. globalSequencerSv1 epochs: 1 -> 2 below strong quorum for 33 s, no blacklist until 16:37:14



```
zcat log/11257/logs-wall-clock-time-1/canton_before_shutdown.clog.gz | grep -a -E 'New epoch [0-9]+ has started|below strong quorum' | jq -r 'select(.["@timestamp"] > "2026-11-06T16:36:00" and .["@timestamp"] < "2026-11-06T16:37:20") | select(.logger_name|test("globalSequencerSv1")) | "\(.["@timestamp"]) | \(.message|gsub("\n";" ")|gsub("::1220[0-9a-f]{60}";"")|.[0:150])"'
```

```
2026-11-06T16:36:02.729Z | New epoch 164 has started with leaders = List(SEQ::sv1338b)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activationTime = 20
2026-11-06T16:36:09.034Z | New epoch 165 has started with leaders = List(SEQ::sv1338b)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activationTime = 20
2026-11-06T16:36:16.550Z | Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 2, ordering may not be able to proceed until more nodes ar
2026-11-06T16:36:16.576Z | New epoch 166 has started with leaders = List(SEQ::sv1338b, SEQ::sv4db1d)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activ
2026-11-06T16:36:44.013Z | Authenticated P2P nodes count (including this node) 1 is currently below strong quorum size 2, ordering may not be able to proceed until more nodes ar
2026-11-06T16:36:49.021Z | New epoch 167 has started with leaders = List(SEQ::sv4db1d, SEQ::sv1338b)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activ
2026-11-06T16:36:54.547Z | New epoch 168 has started with leaders = List(SEQ::sv1338b, SEQ::sv4db1d)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activ
2026-11-06T16:36:59.069Z | Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes ar
2026-11-06T16:36:59.070Z | New epoch 169 has started with leaders = List(SEQ::sv22dec, SEQ::sv34ff1, SEQ::sv4db1d, SEQ::sv1338b)and blacklisted nodes = List(); ordering topology
2026-11-06T16:37:05.175Z | Authenticated P2P nodes count (including this node) 2 is currently below strong quorum size 3, ordering may not be able to proceed until more nodes ar
2026-11-06T16:37:14.650Z | New epoch 170 has started with leaders = List(SEQ::sv34ff1, SEQ::sv1338b, SEQ::sv22dec)and blacklisted nodes = List(SEQ::sv4db1d); ordering topology =
```

## 4. Parent



```
gh api repos/DACH-NY/cn-test-failures/issues/10165 --jq '"\(.number) \(.state) \(.title)"'
```

```
10165 open sv1's /api/sv/v0/onboard/sv/sequencer times out after 38 s because the sv4 sequencer-add topology transaction cannot be ordered during the 1 -> 3 BFT onboarding step (run 35223275137)
```

## 5. wall-clock-time ran BFT before #8234

Catalogue note (family L) from the earlier CI config check.

```
grep -n 'wall-clock-time (`-we`' ci-triage/known-families.md
```

```
303:  wall-clock-time (`-we`, SPLICE_USE_CANTON_BFT_SEQUENCER=1) runs Canton BFT and has no `insert block`.
```

## Verdict

Duplicate of 10165 (family B, signature 2): an SV sequencer onboarding request to sv1 waits through a BFT epoch that
cannot reach strong quorum because the newly added sequencer is not yet authenticated, and the 38 s HTTP request
timeout fires. Canton/BFT root cause; the splice-side options are listed under family B (serialise onboardings, a
non-blocking handler or a custom timeout for onboardSvSequencer). Not blacklisting; splice#8356 does not help.
