# 11405 - simtime (0): all splice apps of the test JVM stall in the RemoteClock fetch 12 s after a 10 min sim-time advance; test fails, next suite never starts, sbt killed at 40 min (run 43402323878)

NEW, not 11255. Main 635294d6e6, job simtime (0), Canton 3.7.3. TrafficBasedRewardsSvAppTimeBasedIntegrationTest:
the environment setup finishes (09:50:14.671, 4 orderers since 09:49:49.162, no P2P rejection, no blacklisting). The
test advances the sim clock by 10 min 10 s at 09:50:28.320. At 09:50:40.615 the last `fetch remote time` round trip
of a splice app completes in the test JVM; from then on every app's RemoteClock (17 clocks: SV, scan, validator and
splitwell apps plus the test's own) waits in `fetching remote time`, on the remoteclock scheduler threads and on 66
threads of the shared execution context (`env-ec`). The Canton JVM is healthy: it answers every `CurrentTime`
request it receives, the ordering layer keeps producing epochs until the job ends, the participants are idle. The
apps send nothing after 09:50:41 (last delivered submission on global at 09:50:41.6, sim time 00:10:10.097), sv1Scan
does not answer the test's `GetSortedOpenAndIssuingMiningRounds` within 1 min (09:51:42.506), the check `waiting for
open and issuing round automation` fails, the environment teardown fails in `LedgerApiStoreIntegrityChecker`
(`Future timed out after [60 seconds]`, the reported failure, 09:52:42.540), the next suite stays queued in the
`ConcurrentEnvironmentLimiter`, and sbt is killed at its 40 min limit (10:25:08, exit 124). Every 2 min the waits
log `Task fetching remote time did not complete within 2 minutes`, a handful of new fetches is sent and answered by
the participant, and the apps stay stalled; the first fetch successes after the stall are logged at 10:02.

- Run: https://github.com/canton-network/splice/actions/runs/43402323878, main 635294d6e6
  ("SvReonboardingIntegrationTest: Give the re-onboarded SV4 sequencer its own P2P port (#8465)"), job 131808362458
  `ci / scala_test_sim_time / simtime (0)`, attempt 1.
- Runtime canton: 3.7.3 (`nix/canton-sources.json` at 635294d6e6).
- Component: test JVM (splice apps on the shared test execution context, Canton `RemoteClock` client of the sim
  clock). Why the fetches stop completing is not established.

`F` throughout: `s/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g`.
Artifact `logs-simtime-0` (14027939445) in `log/11405/logs-simtime-0/`; commands run there unless they name `log/11405/job.log`.

## 1. Job: one failed test, then the sbt timeout

Classification grep of the job log.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a -E 'FAILED \*\*\*|Future timed out|LedgerApiStoreIntegrityChecker.scala|EnvironmentSetup.destroyEnvironment\(|Tests: succeeded|Run completed|Killed SBT|Attempt 1 failed' | grep -v 'ignore this line' | sed -E 's/^([^ ]+T)([0-9:]+)\.[0-9]+Z /\2 /' | cut -c1-200
```
```
      echo "Killed SBT after timeout 40m"
09:38:36       echo "Killed SBT after timeout 40m"
      echo "Killed SBT after timeout 40m"
09:45:06       echo "Killed SBT after timeout 40m"
09:52:42 [info] - Enable, disable of dryRunVersion/mintingVersion take effect at round closure *** FAILED ***
09:52:42 [info]   java.util.concurrent.TimeoutException: Future timed out after [60 seconds]
09:52:42 [info]   at com.digitalasset.canton.integration.LedgerApiStoreIntegrityChecker.$anonfun$verifyParticipantLapiIntegrity$15(LedgerApiStoreIntegrityChecker.scala:235)
09:52:42 [info]   at com.digitalasset.canton.integration.LedgerApiStoreIntegrityChecker.timeIt(LedgerApiStoreIntegrityChecker.scala:25)
09:52:42 [info]   at com.digitalasset.canton.integration.LedgerApiStoreIntegrityChecker.verifyParticipantLapiIntegrity(LedgerApiStoreIntegrityChecker.scala:228)
09:52:42 [info]   at com.digitalasset.canton.integration.EnvironmentSetup.destroyEnvironment(EnvironmentSetup.scala:312)
10:25:08 Killed SBT after timeout 40m
10:25:08 Attempt 1 failed with exit code 124, retrying
```

The test still running when sbt is killed is the first test of the next suite.
```
sed -E 's/\x1b\[[0-9;]*m//g' ../job.log | grep -a 'Test still running' | tail -1 | sed -E 's/^([^ ]+T)([0-9:]+)\.[0-9]+Z /\2 /' | cut -c1-220
```
```
10:24:43 [info] *** Test still running after 32 minutes, 0 seconds: suite name: TimeBasedTreasuryIntegrationTest, test name: automatically merge transfer inputs when the automation is triggered. 
```

## 2. Timeline of the test

Suite, setup, time advance, failed check, failure, next suite queued.
```
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |creating environment: Running setup|advancing time by|Failed clue|did not complete within 1 minute|TimeBasedTreasuryIntegrationTest: New => Queued|has been reset" | sed -E "$F" | sed -E 's/\\\\n.*//; s/ Stack traces:.*//' | cut -c1-250
```
```
2026-11-13T09:45:43.996Z Starting test suite 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T09:46:29.840Z Starting creating environment: Running setup",[o.l.s.i.t.TrafficBasedRewardsSvAppTimeBasedIntegrationTest:TrafficBasedRewardsSvAppTimeBasedIntegrationTest] DEBUG
2026-11-13T09:50:14.671Z Finished creating environment: Running setup",[o.l.s.i.t.TrafficBasedRewardsSvAppTimeBasedIntegrationTest:TrafficBasedRewardsSvAppTimeBasedIntegrationTest] DEBUG
2026-11-13T09:50:28.320Z advancing time by PT10M10S to 1970-01-01T00:10:10Z",[o.l.s.i.t.TrafficBasedRewardsSvAppTimeBasedIntegrationTest:TrafficBasedRewardsSvAppTimeBasedIntegrationTest] INFO
2026-11-13T09:51:42.506Z Task Running on sv1Scan command GetSortedOpenAndIssuingMiningRounds(List(),List()) against NetworkAppClientConfig(http://127.0.0.1:5012,None,Some(KeepAliveClientConfig(40 seconds,15 seconds,false,30 minutes))) did not complet
2026-11-13T09:51:42.524Z Failed clue: (check) waiting for open and issuing round automation (should create OpenMiningRound 3, should advance IssuingMiningRounds List()",[o.l.s.i.t.TrafficBasedRewardsSvAppTimeBasedIntegrationTest:TrafficBasedRewardsSv
2026-11-13T09:52:42.540Z Test failed: 'TrafficBasedRewardsSvAppTimeBasedIntegrationTest/Enable, disable of dryRunVersion/mintingVersion take effect at round closure', message: Future timed out after [60 seconds], location: SeeStackDepthException",[c.
2026-11-13T09:52:42.619Z Starting test suite 'TimeBasedTreasuryIntegrationTest'...",[c.d.c.LogReporter:reporter=scala-test] INFO
2026-11-13T09:52:42.620Z org.lfdecentralizedtrust.splice.integration.tests.TimeBasedTreasuryIntegrationTest: New => Queued after 0s (2 pending)",[c.d.c.i.ConcurrentEnvironmentLimiter$] DEBUG
```

## 3. The test JVM goes quiet at 09:50:41

Lines of the test log per 10 s.
```
zcat canton_network_test.clog.gz | grep -a -E 'T09:5[0-2]' | awk '{match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+25,7); c[t]++} END{for(k in c) print k"0", c[k]}' | sort | tr '\n' ' '
```
```
09:50:00 21782 09:50:10 22049 09:50:20 23759 09:50:30 31468 09:50:40 2611 09:50:50 31 09:51:00 30 09:51:10 24 09:51:20 23 09:51:30 23 09:51:40 28 09:51:50 21 09:52:00 22 09:52:10 21 09:52:20 21 09:52:30 21 09:52:40 60 09:52:50 5
```

No WARN or ERROR in the test log in the 6 s before the stall, and no FutureSupervisor stall report (rules out the host stall of 11395).
```
zcat canton_network_test.clog.gz | grep -a -E 'T09:50:(3[5-9]|4)' | grep -a -c -E '"level":"(WARN|ERROR)"'; zcat canton_network_test.clog.gz | grep -a -c 'Supervising futures itself takes longer'
```
```
0
0
```

## 4. RemoteClock: fetches are sent, the successes stop at 09:50:40.615

RemoteClock client lines per second around the stall (sent / succeeded).
```
zcat canton_network_test.clog.gz | grep -a '"logger_name":"c.d.c.t.RemoteClock' | grep -a -E 'T09:50:(3[89]|4[0-3])' | awk '{match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+25,8); if(/Sending request fetch/) s[t]++; if(/has succeeded/) o[t]++; m[t]=1} END{for(k in m) print k, "sent="s[k]+0, "succeeded="o[k]+0}' | sort
```
```
09:50:38 sent=525 succeeded=525
09:50:39 sent=577 succeeded=576
09:50:40 sent=422 succeeded=416
09:50:41 sent=1 succeeded=0
```

The last successes and the sends after them (logger suffix and thread).
```
zcat canton_network_test.clog.gz | grep -a '"logger_name":"c.d.c.t.RemoteClock' | grep -a -E 'T09:50:4[01]' | grep -a -E 'Sending request fetch|has succeeded' | grep -oE '"@timestamp":"[^"]+","message":"[^"]{0,90}|RemoteClock:[^"]*|"thread_name":"[^"]+"' | paste - - - | sed -E 's/"@timestamp":"//; s/","message":"/ /; s/TrafficBasedRewardsSvAppTimeBasedIntegrationTest(\/config=37af4769)?//g; s/"thread_name":"-37af4769-//; s/"$//' | awk '/has succeeded/{last=NR} {l[NR]=$0} END{for(i=last-1;i<=NR;i++) print l[i]}'
```
```
2026-11-13T09:50:40.615Z Request fetch remote time has succeeded for remote clock server. | RemoteClock:/SV=sv1 | env-ec-1321
2026-11-13T09:50:40.615Z Request fetch remote time has succeeded for remote clock server. | RemoteClock:/SV=sv4 | env-ec-1321
2026-11-13T09:50:40.615Z Sending request fetch remote time to remote clock server. | RemoteClock:/SV=sv1 | env-ec-874
2026-11-13T09:50:40.615Z Sending request fetch remote time to remote clock server. | RemoteClock:/SV=sv4 | env-ec-1331
2026-11-13T09:50:40.768Z Sending request fetch remote time to remote clock server. | RemoteClock:/SV=sv3 | sv3-remoteclock-0
2026-11-13T09:50:40.768Z Sending request fetch remote time to remote clock server. | RemoteClock:/validator=sv3Validator | sv3Validator-remoteclock-0
2026-11-13T09:50:40.769Z Sending request fetch remote time to remote clock server. | RemoteClock:/validator=splitwellValidator | splitwellValidator-remoteclock-0
2026-11-13T09:50:40.771Z Sending request fetch remote time to remote clock server. | RemoteClock:/SV=sv2 | sv2-remoteclock-0
2026-11-13T09:50:40.866Z Sending request fetch remote time to remote clock server. | RemoteClock:/splitwell=providerSplitwellBackend | providerSplitwellBackend-remoteclock-0
2026-11-13T09:50:40.883Z Sending request fetch remote time to remote clock server. | RemoteClock:/validator=bobValidator | bobValidator-remoteclock-0
2026-11-13T09:50:40.885Z Sending request fetch remote time to remote clock server. | RemoteClock:/scan=sv4Scan | sv4Scan-remoteclock-0
2026-11-13T09:50:40.889Z Sending request fetch remote time to remote clock server. | RemoteClock:/validator=sv1Validator | sv1Validator-remoteclock-0
2026-11-13T09:50:40.890Z Sending request fetch remote time to remote clock server. | RemoteClock:/validator=sv2Validator | sv2Validator-remoteclock-0
2026-11-13T09:50:40.912Z Sending request fetch remote time to remote clock server. | RemoteClock:/validator=sv4Validator | sv4Validator-remoteclock-0
2026-11-13T09:50:40.913Z Sending request fetch remote time to remote clock server. | RemoteClock:/scan=sv1Scan | sv1Scan-remoteclock-0
2026-11-13T09:50:40.976Z Sending request fetch remote time to remote clock server. | RemoteClock:/validator=aliceValidator | aliceValidator-remoteclock-0
2026-11-13T09:50:40.977Z Sending request fetch remote time to remote clock server. | RemoteClock:/SV=sv4 | sv4-remoteclock-0
2026-11-13T09:50:40.992Z Sending request fetch remote time to remote clock server. | RemoteClock:/scan=sv2Scan | sv2Scan-remoteclock-0
2026-11-13T09:50:40.994Z Sending request fetch remote time to remote clock server. | RemoteClock:/SV=sv1 | sv1-remoteclock-0
2026-11-13T09:50:40.995Z Sending request fetch remote time to remote clock server. | RemoteClock:/scan=sv3Scan | sv3Scan-remoteclock-0
2026-11-13T09:50:41.109Z Sending request fetch remote time to remote clock server. | RemoteClock: | remoteclock-0
```

Per minute after the stall: fetches sent, succeeded, and 2 min warnings (first 14 minutes with any RemoteClock line).
```
zcat canton_network_test.clog.gz | grep -a '"logger_name":"c.d.c.t.RemoteClock' | grep -a -E 'T09:5[0-9]|T10:0' | awk '{match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+25,8); if(t<"09:50:41") next; k=substr(t,1,5); if(/Sending request fetch/) s[k]++; if(/has succeeded/) o[k]++; if(/did not complete within/) w[k]++; m[k]=1} END{for(k in m) print k, "sent="s[k]+0, "succeeded="o[k]+0, "2min-warn="w[k]+0}' | sort | head -14
```
```
09:50 sent=1 succeeded=0 2min-warn=0
09:51 sent=0 succeeded=0 2min-warn=0
09:52 sent=5 succeeded=0 2min-warn=21
09:53 sent=0 succeeded=0 2min-warn=0
09:54 sent=6 succeeded=0 2min-warn=5
09:55 sent=0 succeeded=0 2min-warn=0
09:56 sent=5 succeeded=0 2min-warn=6
09:57 sent=0 succeeded=0 2min-warn=0
09:58 sent=6 succeeded=0 2min-warn=5
09:59 sent=0 succeeded=0 2min-warn=0
10:00 sent=7 succeeded=0 2min-warn=6
10:01 sent=0 succeeded=0 2min-warn=0
10:02 sent=9 succeeded=12 2min-warn=7
10:03 sent=0 succeeded=0 2min-warn=0
```

First wait report and first 2 min warning.
```
zcat canton_network_test.clog.gz | grep -a -m1 'Task fetching remote time still not completed after 10' | sed -E "$F" | cut -c1-200; zcat canton_network_test.clog.gz | grep -a -m1 'Task fetching remote time did not complete within 2 minutes' | sed -E 's/ Stack traces:.*"logger_name/","logger_name/' | sed -E "$F" | cut -c1-200
```
```
2026-11-13T09:50:50.614Z Task fetching remote time still not completed after 10 seconds. Continue waiting...",[c.d.c.t.RemoteClock:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=37af4769/SV=s
2026-11-13T09:52:40.656Z Task fetching remote time did not complete within 2 minutes.",[c.d.c.t.RemoteClock:TrafficBasedRewardsSvAppTimeBasedIntegrationTest/config=37af4769/SV=sv4] WARN
```

Who waits at the 10 s mark: lines per thread group, distinct threads in total, distinct env-ec threads.
```
zcat canton_network_test.clog.gz | grep -a 'Task fetching remote time still not completed after 10' | grep -oE '"thread_name":"[^"]+"' | sed -E 's/.*37af4769-//; s/-[0-9]+"$//; s/^(sv[1-4]|sv[1-4]Scan|sv[1-4]Validator|aliceValidator|bobValidator|splitwellValidator|providerSplitwellBackend)-remoteclock/app-remoteclock/' | sort | uniq -c; zcat canton_network_test.clog.gz | grep -a 'Task fetching remote time still not completed after 10' | grep -oE '"thread_name":"[^"]+"' | sort -u | wc -l; zcat canton_network_test.clog.gz | grep -a 'Task fetching remote time still not completed after 10' | grep -oE 'env-ec-[0-9]+' | sort -u | wc -l
```
```
     32 app-remoteclock
    281 env-ec
      2 remoteclock
83
66
```

Size of the execution context (parallelism) and the highest env-ec thread number seen before the stall.
```
zcat canton_network_test.clog.gz | grep -a -m1 'as number of threads from' | grep -oE '"message":"[^"]{0,120}'; zcat canton_network_test.clog.gz | grep -a -E 'T09:4[0-9]|T09:50' | grep -oE 'env-ec-[0-9]+' | sed 's/env-ec-//' | sort -n -u | tail -1
```
```
"message":"Deriving 8 as number of threads from 'sys.runtime.availableProcessors()'. Please use '-Dscala.concurrent.context.numThre
1339
```

The thread dump in the first 2 min warning: the frames of one waiting remoteclock thread, then all dumped threads by kind and state (script: read the JSON line, split the message into threads, count by name; it lists scheduler threads only, no env-ec thread).
```
zcat canton_network_test.clog.gz | grep -a -m1 'Task fetching remote time did not complete' | python3 -I ci-triage/evidence/11405-thread-dump.py
```
```
Thread[#293,TrafficBasedRewardsSvAppTimeBasedIntegrationTest-37af4769-sv1-remoteclock-0,5,main] is-daemon=true state=TIMED_WAITING
scala.concurrent.Await$.ready(package.scala:125)
com.digitalasset.canton.config.RefinedNonNegativeDuration$.$anonfun$noisyAwaitResult$1(RefinedNonNegativeDuration.scala:193)
com.digitalasset.canton.config.RefinedNonNegativeDuration$.$anonfun$noisyAwaitResult$5(RefinedNonNegativeDuration.scala:208)
com.digitalasset.canton.config.RefinedNonNegativeDuration$.noisyAwaitResultForTesting(RefinedNonNegativeDuration.scala:283)
com.digitalasset.canton.config.RefinedNonNegativeDuration$.com$digitalasset$canton$config$RefinedNonNegativeDuration$$noisyAwaitResult(RefinedNonNegat
com.digitalasset.canton.time.RemoteClock.$anonfun$getCurrentRemoteTime$1(Clock.scala:669)
com.digitalasset.canton.time.RemoteClock$$Lambda/0x00007fff3a42b7f0.apply(Unknown Source)
1 delay-util-0 WAITING
17 remoteclock TIMED_WAITING
16 wallclock TIMED_WAITING
429 wallclock WAITING
```

## 5. The Canton JVM is healthy and idle

Participant side of the clock: CurrentTime requests received and answered per 10 s, 09:50:20-09:53 (all participants).
```
zcat canton-simtime.clog.gz | grep -a -E 'T09:5[0-2]' | grep -a 'CurrentTime' | awk '{match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+25,7); if(/received a message CurrentTimeRequest/) r[t]++; if(/sending response CurrentTimeResponse/) o[t]++; m[t]=1} END{for(k in m) print k"0", "received="r[k]+0, "answered="o[k]+0}' | sort | sed -n '/09:50:2/,$p'
```
```
09:50:20 received=6294 answered=6294
09:50:30 received=5818 answered=5818
09:50:40 received=434 answered=434
09:52:40 received=5 answered=5
```

Submissions arriving at the global sequencers per 10 s, the last delivered one, and the last epoch of the run.
```
zcat canton-simtime.clog.gz | grep -a -E 'T09:5[01]' | grep -a 'sends request with id' | grep -a globalSequencer | awk '{match($0,/"@timestamp":"[^"]+"/); t=substr($0,RSTART+25,7); c[t]++} END{for(k in c) print k"0", c[k]}' | sort | tr '\n' ' '; echo; zcat canton-simtime.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | grep -a 'validated to: Deliver' | tail -1 | grep -oE '"@timestamp":"[^"]+"|At block [0-9]+|at 1970-[^ ]+' | tr '\n' ' '; echo; zcat canton-simtime.clog.gz | grep -a 'New epoch' | grep -a -v splitwell | grep -a globalSequencerSv1 | tail -1 | sed -E "$F" | cut -c1-80
```
```
09:50:00 30 09:50:10 24 09:50:20 111 09:50:30 144 09:50:40 13 
"@timestamp":"2026-11-13T09:50:41.653Z" At block 811 at 1970-01-01T00:10:10.097Z 
2026-11-13T10:25:07.483Z New epoch 203 has started with leaders = List(SEQ::sv4:
```

11255 confirming grep: none of its signatures.
```
zcat canton-simtime.clog.gz | awk '{ if(/Failed to send result/) b++; if(/currently blacklisted/) bl++; if(/New epoch .*size = [0-9]/ && !/splitwell/){match($0,/"@timestamp":"[^"]+"/); last=substr($0,RSTART+14,24); match($0,/size = [0-9]+/); sz=substr($0,RSTART,RLENGTH); if(sz!=psz){print "epoch " last " " sz; psz=sz}} if(/Rejecting submission request by/ && /P2P connectivity is not ready/) p++ } END{print "P2P connectivity rejections: " p+0 "; Failed to send result: " b+0 "; currently blacklisted: " bl+0}'
```
```
epoch 2026-11-13T09:47:02.953Z size = 1
epoch 2026-11-13T09:49:23.413Z size = 3
epoch 2026-11-13T09:49:49.162Z size = 4
P2P connectivity rejections: 0; Failed to send result: 0; currently blacklisted: 0
```

Distinct WARN/ERROR of the Canton log, without the UserNotFound and API-logging lines.
```
zcat canton-simtime.clog.gz | grep -a -E '"level":"(WARN|ERROR)"' | grep -a -v -E 'UserNotFound|detailed API event logging' | sed -E "$F" | awk '{k=substr($0,26,70)} c[k]++<1' | cut -c1-250
```
```
2026-11-13T09:50:28.716Z Detected late processing (or clock skew) of batch with timestamp = 1970-01-01T00:00:00.712001Z; delta = PT10M9.287999S after sequencing (> threshold = PT20S)",[c.d.c.s.m.Mediator:mediator=globalMediatorSv2/psid=global-domain:
```

## 6. Earlier issues with this signature

Tracker search (run from the repo).
```
gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "fetching remote time"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:110])"'; gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue LedgerApiStoreIntegrityChecker' --jq '.items | length'
```
```
#5549 closed/not_planned 2025-09-15 `Task fetching remote time did not complete within 2 minutes` in time-based integration test
#5094 closed/completed 2025-07-22 many timeouts within the same CI run
1
```

5549 (2025-09-15, closed not planned with "this is 3w old, closing") shows the same message during an environment
setup of SvTimeBasedRewardCouponIntegrationTest, without analysis. 5094 (2025, "many timeouts within the same CI
run") and the one LedgerApiStoreIntegrityChecker hit (8031, closed not planned, "GrpcServiceUnavailable:
UNAVAILABLE/Network closed for unknown reason") were judged by title only. None is an open parent, and
`known-families.md` and `INDEX.tsv` have no entry for `fetching remote time` or `RemoteClock` (1494, cited in the
11299 packet, is a 2024 remote clock failure, closed).

## Verdict

New, first hit of this shape in the current catalogue; not a duplicate of 11255 (no topology step in flight: 4
orderers for 39 s, zero P2P rejections, zero blacklisting, Canton answers and stays idle). The stall is inside the
test JVM: after the 10 min 10 s sim-time advance at 09:50:28.320, the RemoteClock fetches of all splice apps stop
completing at 09:50:40.615 although the participants answer each request, and at least 66 threads of the shared
execution context plus all remoteclock scheduler threads sit in the synchronous `fetching remote time` wait. That
the response callbacks need the same exhausted execution context is the likely mechanism but is NOT shown: the
thread dump in the warning does not include the env-ec threads, and the pool limit was not read from the 3.7.3 jar.
Also not verified: what made this run different from the passing ones (the fetch rate was about 600 per second on
env-ec threads before the stall as well); whether the 12 s between advance and stall carried a burst of triggers.
Flake or real: one hit, cannot say. Fix location: test infrastructure (how the splice apps read the sim clock:
Canton `RemoteClock.now` blocks the calling thread for a gRPC round trip) or Canton; no fix proposed. Secondary:
after such a stall the suite cannot tear down and the remaining suites of the shard are lost to the 40 min sbt limit.
