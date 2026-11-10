# 11301 11302 - AppUpgradeIntegrationTest: base-version SV nodes need more than the 5 min initial wait (runs 42637895044, 42639073931)

NEW (not a duplicate of 9440 / 11188). Two consecutive post-merge main runs on 2026-11-09, job `app-upgrade (0)`,
Canton 3.7.2. In both, the four bundled base-version (0.10.1) processes started by AppUpgradeIntegrationTest are
still initializing when the test's first `eventually(5.minute)` (AppUpgradeIntegrationTest.scala:157) gives up:
every init phase progresses, nothing hangs, but each phase is 1.3x to 1.9x slower than in the 11-04 reference run,
and the test has almost no margin (11302 missed by about 5 s: sv3Validator was in its last init steps). 11302
reports the assertion `false was not true sv3 wallet initialized`. 11301 lost its report: the timeout killed the
bundled sv3 app right after its sequencer had been added to the BFT ordering topology (size 3, strong quorum 3, sv3
first leader), ordering made no further epoch until the log ends 82 s later, so the teardown's decentralized
namespace reset never became effective and `ResetTopologyStatePlugin` called `sys.exit(1)` (family H, with the
11255 newcomer-stall shape as the reason the reset could not complete). Flake with no timing margin; fix in the
test (longer initial wait).

- 11301: https://github.com/canton-network/splice/actions/runs/42637895044, main 7d3d5f17da ("Handle duplicate
  sequencer ids in scan (#8365)"), job 129231297282 `ci / scala_test_app_upgrade / app-upgrade (0)`, attempt 1.
- 11302: https://github.com/canton-network/splice/actions/runs/42639073931, main fbc7ad2481 ("Make ACS stores
  track a single synchronizer (#8100)"), job 129235550728 `ci / scala_test_app_upgrade / app-upgrade (0)`, attempt 1.
- Runtime canton: 3.7.2 at both shas (`git show <sha>:nix/canton-sources.json | grep -m1 version`).
- Component: test (AppUpgradeIntegrationTest initial wait); report loss: test plugin (ResetTopologyStatePlugin).

## 1. Failing assertion / evidence loss

11302 fails the assertion at line 162; 11301 has no ScalaTest summary, the sbt step exits 1 after the test ran
more than 6.5 min.

```
for r in 11301 11302; do echo "== $r"; sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$r/job.log | grep -a -E 'Test still running after (5 minutes, 28|6 minutes, 28)|be upgradeable \*\*\* FAILED|false was not true|Tests: succeeded|Attempt 1 failed|^Exceeded maximum retries' | sort -u | cut -c1-160; done
```
```
== 11301
Attempt 1 failed with exit code 1, retrying
Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
[info] *** Test still running after 5 minutes, 28 seconds: suite name: AppUpgradeIntegrationTest, test name: A set of Splice apps should be upgradeable. 
[info] *** Test still running after 6 minutes, 28 seconds: suite name: AppUpgradeIntegrationTest, test name: A set of Splice apps should be upgradeable. 
== 11302
Attempt 1 failed with exit code 1, retrying
Exceeded maximum retries (1 / 0), no more attempts << parameters.cmd_name >>
[info]   false was not true sv3 wallet initialized (AppUpgradeIntegrationTest.scala:162)
[info] *** Test still running after 5 minutes, 28 seconds: suite name: AppUpgradeIntegrationTest, test name: A set of Splice apps should be upgradeable. 
[info] - be upgradeable *** FAILED ***
[info] - should be upgradeable *** FAILED ***
[info] Tests: succeeded 0, failed 1, canceled 0, ignored 0, pending 0
```

The failing wait (lines 157-170 at fbc7ad2481; the same block at 7d3d5f17da):

```
git show fbc7ad2481:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/AppUpgradeIntegrationTest.scala | sed -n 157,170p
```
```
          eventually(5.minute) {
            Seq("sv1", "sv2", "sv3").foreach(sv => {
              sv_client(s"${sv}Client").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} SV app initialized"
              wc(s"${sv}Wallet").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} wallet initialized"
            })
            forAll(Seq("bob", "splitwell")) { validator =>
              vc(s"${validator}ValidatorClient").httpHealth.successOption
                .exists(_.active) should be(true)
            }
          }
```

## 2. Test timeline (test log)

The test body starts its bundled processes at "Running setup"; 11302 fails 5 min 40 s later; 11301 instead ends
with the teardown reset ERROR and no `Test failed` line (log ends at that ERROR).

```
for r in 11301 11302; do echo "== $r"; zcat log/$r/logs-app-upgrade-0/canton_network_test.clog.gz | grep -a -E "Starting 'AppUpgrade|Starting creating environment: Running setup|Test failed: |Failed to reset decentralized namespace|All required proposals to reset SV namespace|decentralized namespace has been reset" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200; zcat log/$r/logs-app-upgrade-0/canton_network_test.clog.gz | tail -1 | cut -c1-40; done
```
```
== 11301
2026-11-09T15:12:22.026Z Starting 'AppUpgradeIntegrationTest/A set of Splice apps should be upgradeable'...",
2026-11-09T15:12:27.949Z Starting creating environment: Running setup",
2026-11-09T15:18:16.706Z All required proposals to reset SV namespace submitted, waiting for it to be effective",
2026-11-09T15:19:16.720Z Failed to reset decentralized namespace",
{"@timestamp":"2026-11-09T15:19:16.720Z"
== 11302
2026-11-09T15:19:20.560Z Starting 'AppUpgradeIntegrationTest/A set of Splice apps should be upgradeable'...",
2026-11-09T15:19:26.629Z Starting creating environment: Running setup",
2026-11-09T15:25:06.022Z All required proposals to reset SV namespace submitted, waiting for it to be effective",
2026-11-09T15:25:06.659Z decentralized namespace has been reset",
2026-11-09T15:25:06.840Z Test failed: 'AppUpgradeIntegrationTest/A set of Splice apps should be upgradeable', message: false was not true sv3 wallet initialized, location: SeeStackDepthException",
{"@timestamp":"2026-11-09T15:25:08.144Z"
```

## 3. Init phases of the bundled base-version nodes: progressing, but past the deadline

Artifacts: `gh run download <run> --repo canton-network/splice -n logs-app-upgrade-0 -D log/<ref>/logs-app-upgrade-0`.
The bundled apps log to `splice-node-forUpgrade-<node>.clog.gz`. Every phase finishes; none is stuck. In 11302 the
wait expired about 15:25:05 while sv3Validator was in its last init steps (15:25:04.316); in 11301 the processes
were stopped at 15:18:03 to 15:18:15 while sv3 was still joining the DSO (its last phase 15:14:36) and sv2Validator
was still initializing.

```
for r in 11301 11302; do echo "== $r"; for n in sv1 sv2 sv3; do zcat log/$r/logs-app-upgrade-0/splice-node-forUpgrade-$n-node.clog.gz | grep -a -E '"message":"(sv[123]|sv[123]Validator) app initialization: ([^"]*(Starting initialization|Wait for user finished|Get participant ID finished|bootstrapping Dso finished|joining Dso with key finished|Initialize app finished)|Ensuring user primary party is allocated finished)' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",$//' | cut -c12-23,25-150 | awk '{k=$2" "$4" "$5" "$6" "$7; if(!(k in s)){s[k]=1; print}}'; done; done
```
```
== 11301
15:12:45.962 sv1 app initialization: Starting initialization
15:12:47.764 sv1 app initialization: Wait for user finished after PT0.016626006S
15:12:50.242 sv1Validator app initialization: Starting initialization
15:14:16.270 sv1 app initialization: Get participant ID finished after PT0.016484474S
15:15:26.595 sv1 app initialization: SV1Initializer bootstrapping Dso finished after PT1M10.259438545S
15:15:29.094 sv1 app initialization: Initialize app finished after PT2M42.584682514S
15:15:30.454 sv1Validator app initialization: Wait for user finished after PT2M40.179326233S
15:16:26.167 sv1Validator app initialization: Ensuring user primary party is allocated finished after PT0.000707618S
15:16:52.138 sv1Validator app initialization: Initialize app finished after PT4M1.889355862S
15:12:55.245 sv2 app initialization: Starting initialization
15:12:56.068 sv2Validator app initialization: Starting initialization
15:12:56.978 sv2 app initialization: Wait for user finished after PT0.041213215S
15:14:21.808 sv2 app initialization: Get participant ID finished after PT0.023246065S
15:17:53.654 sv2 app initialization: JoiningNodeInitializer joining Dso with key finished after PT3M31.789964491S
15:17:55.043 sv2 app initialization: Initialize app finished after PT4M59.170059044S
15:17:58.658 sv2Validator app initialization: Wait for user finished after PT5M2.070316274S
15:13:09.936 sv3 app initialization: Starting initialization
15:13:11.164 sv3 app initialization: Wait for user finished after PT0.022136336S
15:13:13.246 sv3Validator app initialization: Starting initialization
15:14:36.934 sv3 app initialization: Get participant ID finished after PT0.052876702S
== 11302
15:19:44.616 sv1Validator app initialization: Starting initialization
15:19:47.597 sv1 app initialization: Starting initialization
15:19:48.045 sv1 app initialization: Wait for user finished after PT0.034487035S
15:21:00.763 sv1 app initialization: Get participant ID finished after PT0.01228912S
15:21:59.764 sv1 app initialization: SV1Initializer bootstrapping Dso finished after PT58.962724325S
15:22:01.066 sv1 app initialization: Initialize app finished after PT2M13.44744117S
15:22:02.798 sv1Validator app initialization: Wait for user finished after PT2M17.976802378S
15:22:33.572 sv1Validator app initialization: Ensuring user primary party is allocated finished after PT0.000737425S
15:23:21.156 sv1Validator app initialization: Initialize app finished after PT3M36.472043535S
15:19:50.565 sv2 app initialization: Starting initialization
15:19:51.378 sv2Validator app initialization: Starting initialization
15:19:52.692 sv2 app initialization: Wait for user finished after PT0.047215112S
15:21:03.013 sv2 app initialization: Get participant ID finished after PT0.010039911S
15:24:26.343 sv2 app initialization: JoiningNodeInitializer joining Dso with key finished after PT3M23.293490135S
15:24:27.304 sv2 app initialization: Initialize app finished after PT4M35.962265064S
15:24:30.517 sv2Validator app initialization: Wait for user finished after PT4M38.347320044S
15:24:48.083 sv2Validator app initialization: Ensuring user primary party is allocated finished after PT0.000532325S
15:24:50.008 sv2Validator app initialization: Initialize app finished after PT4M58.614910181S
15:20:02.344 sv3Validator app initialization: Starting initialization
15:20:05.187 sv3 app initialization: Starting initialization
15:20:05.912 sv3 app initialization: Wait for user finished after PT0.018298474S
15:21:15.866 sv3 app initialization: Get participant ID finished after PT0.012819679S
15:24:41.047 sv3 app initialization: JoiningNodeInitializer joining Dso with key finished after PT3M25.148268636S
15:24:42.288 sv3 app initialization: Initialize app finished after PT4M37.03718773S
15:24:46.387 sv3Validator app initialization: Wait for user finished after PT4M43.811631602S
15:25:04.316 sv3Validator app initialization: Ensuring user primary party is allocated finished after PT0.000986427S
```

The last health poll of 11302: sv3's SV app was active, the sv3 wallet status call took 9.6 s and was the last
check before the deadline.

```
zcat log/11302/logs-app-upgrade-0/canton_network_test.clog.gz | grep -a -E 'Running on sv3(Client|Wallet) command GetHealth|status code' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/".*//' | cut -c12-130 | tail -4
```
```
15:24:54.021Z Running on sv3Client command GetHealthStatus(/api/sv,org.lfdecentralizedtrust.splice.console.HttpAppRefer
15:24:54.079Z HTTP client (GET /api/sv/status): HTTP request took 55 ms to complete. Received response with status code
15:24:54.082Z Running on sv3Wallet command GetHealthStatus(/api/validator,org.lfdecentralizedtrust.splice.console.HttpA
15:25:03.719Z HTTP client (GET /api/validator/status): HTTP request took 9632 ms to complete. Received response with st
```

## 4. Same phases on the 11-04 reference run: 1.3x to 1.9x faster

The only app-upgrade artifact left from a run before these two is the 11188 run 41845069664 (main 1fb3d07f8c,
2026-11-04; it failed later in the test, after the init phase; passing runs upload no artifact).

```
gh run download 41845069664 --repo canton-network/splice -n logs-app-upgrade-0 -D log/11302/base-11188
for n in sv1 sv2 sv3; do zcat log/11302/base-11188/splice-node-forUpgrade-$n-node.clog.gz | grep -a -E '"message":"(sv[123]) app initialization: [^"]*(Starting initialization|Wait for user finished|Get participant ID finished|bootstrapping Dso finished)' | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/",$//' | cut -c12-23,25-150 | awk '{k=$2" "$4" "$5" "$6" "$7; if(!(k in s)){s[k]=1; print}}'; done
```
```
14:59:11.974 sv1 app initialization: Starting initialization
14:59:13.305 sv1 app initialization: Wait for user finished after PT0.067911985S
15:00:06.941 sv1 app initialization: Get participant ID finished after PT0.011414259S
15:00:58.793 sv1 app initialization: SV1Initializer bootstrapping Dso finished after PT51.770657511S
14:59:22.946 sv2 app initialization: Starting initialization
14:59:23.452 sv2 app initialization: Wait for user finished after PT0.015623683S
15:00:18.112 sv2 app initialization: Get participant ID finished after PT0.012270789S
14:59:30.096 sv3 app initialization: Starting initialization
14:59:30.599 sv3 app initialization: Wait for user finished after PT0.033276352S
15:00:27.527 sv3 app initialization: Get participant ID finished after PT0.012127186S
```

The silent step between "Wait for user" and "Get participant ID" (no SV-app log line in between) takes 53 to 57 s
on 11-04, 70 to 72 s in 11302 and 85 to 89 s in 11301; the sv1 DSO bootstrap 52 s, 59 s and 70 s. The sv2 package
vetting step of the join (88 DAR uploads) takes 56 s, 99 s and 105 s:

```
for d in log/11302/base-11188 log/11301/logs-app-upgrade-0 log/11302/logs-app-upgrade-0; do echo "== $d"; zcat $d/splice-node-forUpgrade-sv2-node.clog.gz | grep -a 'SV=sv2"' | grep -a -E '"message":"(Vetting packages based on state from sponsor|Sending upload dar for)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/".*//' | cut -c12-90 | awk 'NR==1{print} {l=$0} END{print l, "(" NR " lines)"}'; done
```
```
== log/11302/base-11188
15:01:04.429Z Vetting packages based on state from sponsor
15:02:00.397Z Sending upload dar for splice-api-reward-assignment-v1-1.0.0 (88 lines)
== log/11301/logs-app-upgrade-0
15:15:34.818Z Vetting packages based on state from sponsor
15:17:13.895Z Sending upload dar for splice-api-reward-assignment-v1-1.0.0 (88 lines)
== log/11302/logs-app-upgrade-0
15:22:04.379Z Vetting packages based on state from sponsor
15:23:49.461Z Sending upload dar for splice-api-reward-assignment-v1-1.0.0 (88 lines)
```

## 5. No BFT ordering stall during the init phase

No `P2P connectivity is not ready` rejection in either run; sv2 and sv3 sequencers are initialized from their
onboarding state within seconds, after the SV joins were already running for minutes. The init is slow everywhere,
not blocked on ordering.

```
for r in 11301 11302; do echo "== $r"; C=log/$r/logs-app-upgrade-0/canton.clog.gz; echo "P2P not ready: $(zcat $C | grep -a -c 'P2P connectivity is not ready')"; zcat $C | grep -a 'InitializeSequencerFromOnboardingStateV2Response(true)' | sed -E 's/\{"@timestamp":"([^"]+)".*sequencer=(globalSequencerSv[0-9]).*/\1 \2 onboarding state initialized/'; done
```
```
== 11301
P2P not ready: 0
2026-11-09T15:17:45.481Z globalSequencerSv2 onboarding state initialized
2026-11-09T15:18:03.046Z globalSequencerSv3 onboarding state initialized
== 11302
P2P not ready: 0
2026-11-09T15:24:18.498Z globalSequencerSv2 onboarding state initialized
2026-11-09T15:24:32.366Z globalSequencerSv3 onboarding state initialized
```

## 6. 11301 report loss: ordering stalls at size 3, reset never effective, sys.exit

The sv1 sequencer's last epoch starts at 15:18:00.355 with ordering topology size 3 and strong quorum 3 (earlier
epochs every 4 to 10 s); sv3, whose sequencer was being initialized at that moment, is the first leader. No later epoch
until the canton log ends at 15:19:22.

```
C=log/11301/logs-app-upgrade-0/canton.clog.gz; zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a -E 'New epoch [0-9]+ has started' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(New epoch [0-9]+ has started).*size = ([0-9]+),\\n  weakQuorum = ([0-9]+),\\n  strongQuorum = ([0-9]+).*/\1 \2 size=\3 strongQuorum=\5/' | tail -6; echo "log end: $(zcat $C | tail -1 | cut -c15-38)"
zcat $C | grep -a 'sequencer=globalSequencerSv1' | grep -a 'New epoch 41 has started' | cut -c1-110
```
```
2026-11-09T15:17:30.708Z New epoch 36 has started size=1 strongQuorum=1
2026-11-09T15:17:35.747Z New epoch 37 has started size=1 strongQuorum=1
2026-11-09T15:17:40.055Z New epoch 38 has started size=2 strongQuorum=2
2026-11-09T15:17:50.400Z New epoch 39 has started size=2 strongQuorum=2
2026-11-09T15:17:55.193Z New epoch 40 has started size=2 strongQuorum=2
2026-11-09T15:18:00.355Z New epoch 41 has started size=3 strongQuorum=3
log end: "2026-11-09T15:19:22.313
{"@timestamp":"2026-11-09T15:18:00.355Z","message":"New epoch 41 has started with leaders = List(SEQ::sv3::122
```

The teardown proposals at 15:18:16.706 (section 2) therefore never became effective; the plugin gave up after its
1 minute wait and exits the JVM (code at 7d3d5f17da):

```
git show 7d3d5f17da:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/plugins/ResetTopologyStatePlugin.scala | sed -n 105,108p
```
```
        case e: Throwable =>
          logger.error(s"Failed to reset $topologyType", e)
          sys.exit(1)
      }
```

That is family H's `ResetTopologyStatePlugin` `sys.exit(1)` (no report, checkErrors skipped). Why sv3's
orderer did not take part after its SV app was stopped at 15:18:03 was not examined further; it is the newcomer
stall shape of 11255 (epoch led by a node that has not joined yet).

## 7. History: 2 failures of this shape in 35 app-upgrade runs on main since 11-04

`log/11302/hist/jobs-clean.txt` lists the `app-upgrade (0)` job of every completed post-merge main run (workflow
163795441) since 2026-11-04; per job, setup = job start to test start (first `Test still running after 1 minute,
28 seconds` minus 88 s), test = ScalaTest `Run completed in`.

```
H=log/11302/hist; while read RUN JOB C T S; do f=$H/$JOB.log; [ "$JOB" = 129231297282 ] && f=log/11301/job.log; [ "$JOB" = 129235550728 ] && f=log/11302/job.log; [ -s $f ] || gh api repos/canton-network/splice/actions/jobs/$JOB/logs > $f 2>/dev/null; RC=$(sed -E 's/\x1b\[[0-9;]*m//g' $f | grep -a -m1 -oE 'Run completed in [^.]*' | sed 's/Run completed in //'); L=$(sed -E 's/\x1b\[[0-9;]*m//g' $f | grep -a -m1 'Test still running after 1 minute, 28 seconds' | cut -c1-19); P=$(python3 -c "
from datetime import datetime as d
L='$L'
print(int((d.fromisoformat(L)-d.fromisoformat('$T'[:19])).total_seconds()-88) if L else '-')"); echo "$T $S $RUN $C setup=${P}s test=[$RC]"; done < $H/jobs-clean.txt
```
```
2026-11-04T07:14:27Z 7307892450 41790532527 success setup=945s test=[7 minutes, 15 seconds]
2026-11-04T09:10:04Z 9f85d76eb9 41804975439 success setup=-s test=[7 minutes, 24 seconds]
2026-11-04T10:09:35Z b5e6a1ba55 41812123603 success setup=958s test=[8 minutes, 5 seconds]
2026-11-04T11:01:06Z 4ab8bd88e8 41817635289 success setup=912s test=[7 minutes, 36 seconds]
2026-11-04T12:49:57Z c8d542043d 41830449246 success setup=976s test=[8 minutes, 21 seconds]
2026-11-04T14:11:13Z cf81b9067b 41840258688 success setup=981s test=[8 minutes, 20 seconds]
2026-11-04T14:41:24Z 83157dad9c 41844793457 success setup=851s test=[7 minutes, 23 seconds]
2026-11-04T14:41:26Z 1fb3d07f8c 41845069664 failure setup=1047s test=[]
2026-11-04T14:41:36Z a22f7adb43 41847691151 success setup=-s test=[8 minutes, 48 seconds]
2026-11-04T15:18:40Z 3fcac48d1c 41853829439 success setup=1189s test=[8 minutes, 20 seconds]
2026-11-05T18:26:11Z 06c280b33d 42083624555 success setup=890s test=[7 minutes, 40 seconds]
2026-11-05T20:50:13Z 64cbca6d3e 42106471025 success setup=928s test=[7 minutes, 50 seconds]
2026-11-05T21:24:56Z 5c8e6ffcfa 42109599444 success setup=1125s test=[8 minutes, 39 seconds]
2026-11-05T22:44:07Z 01436df75f 42123783876 success setup=1626s test=[8 minutes, 15 seconds]
2026-11-06T10:29:34Z 9f88d4ce64 42199308951 success setup=1021s test=[7 minutes, 56 seconds]
2026-11-06T10:46:55Z 04c342f6fd 42205124002 success setup=1167s test=[8 minutes, 19 seconds]
2026-11-06T11:21:23Z d2badce083 42209420702 success setup=-s test=[7 minutes, 25 seconds]
2026-11-06T12:02:26Z 97df2c4399 42214429624 success setup=1078s test=[8 minutes, 14 seconds]
2026-11-06T13:38:38Z b77153c658 42226645323 success setup=1445s test=[9 minutes, 58 seconds]
2026-11-06T14:02:54Z c218905f75 42230239729 success setup=1180s test=[9 minutes, 30 seconds]
2026-11-06T16:07:06Z 6f7a5f83bc 42248944117 success setup=1465s test=[10 minutes, 9 seconds]
2026-11-06T16:33:14Z d8537ea52b 42253265180 success setup=1244s test=[8 minutes, 2 seconds]
2026-11-06T16:36:57Z 1add1813e3 42253557708 success setup=1549s test=[9 minutes, 57 seconds]
2026-11-06T17:06:03Z 4476e19765 42258277175 success setup=1000s test=[7 minutes, 57 seconds]
2026-11-06T20:31:22Z 0b2ccec235 42288740264 success setup=1200s test=[8 minutes, 58 seconds]
2026-11-09T09:17:31Z 9dafafbece 42591855865 success setup=1151s test=[8 minutes, 39 seconds]
2026-11-09T11:26:49Z bd9953a523 42608940134 success setup=1146s test=[8 minutes, 35 seconds]
2026-11-09T12:39:33Z 38a37d9680 42618450792 success setup=1113s test=[8 minutes, 30 seconds]
2026-11-09T12:55:53Z ec65e809c6 42620710625 success setup=1343s test=[8 minutes, 49 seconds]
2026-11-09T13:27:23Z ab36803c8a 42623223930 success setup=1173s test=[8 minutes, 27 seconds]
2026-11-09T14:27:22Z dbcb0c14ca 42634636103 success setup=1066s test=[8 minutes, 18 seconds]
2026-11-09T14:47:10Z 7d3d5f17da 42637895044 failure setup=1512s test=[]
2026-11-09T14:55:33Z fbc7ad2481 42639073931 failure setup=1428s test=[5 minutes, 49 seconds]
2026-11-09T14:56:59Z 92705421fd 42639148146 success setup=1225s test=[9 minutes, 6 seconds]
2026-11-09T15:36:47Z 5dc875a64f 42646231899 success setup=1127s test=[8 minutes, 24 seconds]
```

The 11-04 failure (run 41845069664) is 11188, the 9440 reconnect hang after the init phase, not this shape. Since
`Use DA BFT in all tests (#8234)` (b77153c658, 11-06 13:38), the passing runs take 8:02 to 10:09 (before: 7:15 to
8:48), so the BFT onboarding of sv2 and sv3 likely adds to the same init phase; how much of the initial 5 minutes a
passing run uses is not visible (no artifacts on success). The two failing jobs also had slower setups (1512 s,
1428 s) than most runs that day, on different runners (`self-hosted-k8s-large-runner-cc5m5`, `-lqh5n`), which fits
a slow node but does not prove it.

Commits touching the test (neither failure is caused by its sha: #8365 does not touch the bundled nodes, and #8100
only adds splitwell config lines for the bobSplitwellValidators process, which is not the one late):

```
git log --format='%h %ad %s' --date=short -3 fbc7ad2481 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/AppUpgradeIntegrationTest.scala
```
```
fbc7ad2481 2026-11-09 Make ACS stores track a single synchronizer (#8100)
b77153c658 2026-11-06 Use DA BFT in all tests (#8234)
0c8c834609 2026-10-23 Daml: compatibility mode for SV and FA locking (#7839)
```

## 7a. Not 9440 / 11188

9440 / 11188: after the sv1 app replaces its participant's sequencer connection, the participant disconnects and
the reconnect hangs after `Ensured ... persistent state` (never reaches `Building sequencer client factory`). Here
the same swaps happen (the validator and SV apps switching connections during init), but every disconnect is
followed by a completed reconnect within seconds (e.g. sv1Participant in 11301: disconnected 15:16:09.844, client
factory built 15:16:17.949), so no participant is stuck; the `has not completed after` lines are the 10 s progress
DEBUG of connects that then complete.

```
for r in 11301 11302; do echo "== $r"; zcat log/$r/logs-app-upgrade-0/canton.clog.gz | grep -a -E 'Replacing configuration for \(Synchronizer|Disconnected from Synchronizer|connect to Synchronizer .* has not completed|Successfully connected to synchronizer|Connected to Synchronizer' | python3 -c "
import sys,json
for l in sys.stdin:
    try: d=json.loads(l)
    except: continue
    print(d['@timestamp'][11:23], d['message'][:70].replace('\n',' '), '|', d['logger_name'].split(':')[-1][:40])"; done
```
```
== 11301
15:16:03.966 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv1Participant
15:16:09.844 Disconnected from Synchronizer 'global' | participant=sv1Participant
15:17:24.236 Disconnected from Synchronizer 'global' | participant=sv2Participant
15:17:28.451 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv2Participant
15:17:33.655 Disconnected from Synchronizer 'global' | participant=sv3Participant
15:17:39.079 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv3Participant
== 11302
15:22:27.652 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv1Participant
15:22:28.126 Disconnected from Synchronizer 'global' | participant=sv1Participant
15:23:57.546 Disconnected from Synchronizer 'global' | participant=sv2Participant
15:23:57.744 Disconnected from Synchronizer 'global' | participant=sv3Participant
15:23:58.469 Disconnected from Synchronizer 'global' | participant=sv3Participant
15:24:01.771 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv2Participant
15:24:04.551 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv3Participant
15:24:47.877 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv2Participant
15:24:47.902 Disconnected from Synchronizer 'global' | participant=sv2Participant
15:25:04.156 Replacing configuration for (Synchronizer 'global', global-domain::122 | participant=sv3Participant
15:25:04.172 Disconnected from Synchronizer 'global' | participant=sv3Participant
```

```
for r in 11301 11302; do echo "== $r"; C=log/$r/logs-app-upgrade-0/canton.clog.gz; echo "has not completed: $(zcat $C | grep -a -c 'has not completed after')"; zcat $C | grep -a -E 'Building sequencer client factory for global-domain' | python3 -c "
import sys,json
for l in sys.stdin:
    try: d=json.loads(l)
    except: continue
    print(d['@timestamp'][11:23], d['message'][:45], '|', d['logger_name'].split(':')[-1][:30])"; done
```
```
== 11301
has not completed: 12
15:14:20.551 Building sequencer client factory for global- | participant=sv1Participant
15:15:31.039 Building sequencer client factory for global- | participant=splitwellParticipa
15:15:31.403 Building sequencer client factory for global- | participant=sv2Participant
15:15:32.764 Building sequencer client factory for global- | participant=bobParticipant
15:15:34.237 Building sequencer client factory for global- | participant=sv3Participant
15:16:17.949 Building sequencer client factory for global- | participant=sv1Participant
15:17:26.276 Building sequencer client factory for global- | participant=sv2Participant
15:17:36.978 Building sequencer client factory for global- | participant=sv3Participant
== 11302
has not completed: 18
15:21:04.086 Building sequencer client factory for global- | participant=sv1Participant
15:22:01.296 Building sequencer client factory for global- | participant=sv2Participant
15:22:03.256 Building sequencer client factory for global- | participant=sv3Participant
15:22:03.355 Building sequencer client factory for global- | participant=splitwellParticipa
15:22:03.361 Building sequencer client factory for global- | participant=bobParticipant
15:22:28.175 Building sequencer client factory for global- | participant=sv1Participant
15:23:57.828 Building sequencer client factory for global- | participant=sv3Participant
15:23:58.576 Building sequencer client factory for global- | participant=sv2Participant
15:24:00.087 Building sequencer client factory for global- | participant=sv3Participant
15:24:47.966 Building sequencer client factory for global- | participant=sv2Participant
15:25:04.206 Building sequencer client factory for global- | participant=sv3Participant
```

## Verdict

New: base-version node initialization in AppUpgradeIntegrationTest overruns the 5 min initial wait on slow runs
(2 of 35 runs on main since 11-04, consecutive, 2026-11-09 14:47 and 14:55). Flake, test-side. Not a duplicate of
9440. 11301's lost report is family H (`ResetTopologyStatePlugin` `sys.exit(1)`), triggered because the timeout
stopped sv3 just after its sequencer joined the ordering topology (11255 newcomer stall shape).

Fix: AppUpgradeIntegrationTest.scala:157, raise `eventually(5.minute)` to `eventually(10.minutes)`; the bundled
base-version apps start four JVMs, bootstrap the DSO and onboard two SVs with BFT sequencer onboarding, and the
observed phases add up to about 5 min on a slow runner. No clear owner (the line dates from the 2024 import).
Not verified: which phase passing runs spend their time in (no artifacts on success), runner node load, and why
sv3's orderer did not progress after 15:18:00 in 11301.
