# 10276 - resource-intensive (1) checkErrors: splitwellUpgradeSequencer "Sequencer is unhealthy, so disconnecting all members. Can't connect to database" during a 7.5 s server-wide stall of the runner's Postgres (run 37267521986)

New ref, no catalogued family matches. Scheduled "Integration tests against mainnet Daml version" on main 4a7f355b17,
job `resource-intensive (1)`, canton 3.6.0-snapshot.20261001.20345.0.v85a9270a. The only test that ran
(SvReonboardingIntegrationTest, 1 succeeded, 0 failed) passed; checkErrors flagged one canton WARN at 05:36:29.826 while
the test was still building its environment (initDso against the standalone sv123 canton). The chain is the Canton
sequencer health path: splitwellUpgradeSequencer's 5 s DB health check started at 05:36:21.794, found its idle pool
connections already closed (05:36:26.804), could not get a new one in time (05:36:27.808 `DB_CONNECTION_LOST`, already
ignored by `canton_log.ignore.txt:167`), and 2 s later `SequencerRuntime` turned the storage failure into the flagged WARN
and dropped its members; the storage was Ok again at 05:36:32.811. The cause is not this sequencer: three reference
sequencers in two separate JVMs, on three different databases, had their pending writes stuck from 05:36:22.0 and
finished them in the same millisecond four times (05:36:26.860, 29.574, 35.029, 35.820); the sbt JVM's sv1 app shows the
same 21 s stall, and the standalone participants processed a 05:36:22 batch 20-27 s late. One shared Postgres stopped
completing commits for everyone. It is not family L at that time (no `insert block` retries before 05:37:53). The Postgres
server log is not collected, so what stalled it (I/O, checkpoint, autovacuum) is not shown. Flake, runner infrastructure;
no test-side fix.

- Run: https://github.com/canton-network/splice/actions/runs/37267521986, main 4a7f355b17 (scheduled, "Integration tests
  against mainnet Daml version"), job 111627797282 `build / scala_test_resource_intensive / resource-intensive (1)`.
  Only failed job in the run.
- Runtime canton: 3.6.0-snapshot.20261001.20345.0.v85a9270a (`git show 4a7f355b17:nix/canton-sources.json | grep -m1 '"version"'`).
- Component: infra (runner Postgres service container shared by all canton processes and the apps).
- Artifact: `logs-resource-intensive-1` in `log/10276/logs-resource-intensive-1/` (also `-runner`, `-runner-temp`: no
  Postgres logs in either).

## 1. Classification: all tests passed, one WARN in canton_before_shutdown.clog

```
gh api repos/canton-network/splice/actions/jobs/111627797282 --jq '"\(.conclusion) \(.started_at) \(.completed_at) \(.runner_name)"'
sed -E 's/\x1b\[[0-9;]*m//g' log/10276/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|Checking log/' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++'
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10276/job.log | awk '/^Found problems in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-330
```
```
failure 2026-10-05T05:25:03Z 2026-10-05T05:41:25Z self-hosted-k8s-x-large-mlgvl-runner-zkprk
[info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
Checking log/canton_before_shutdown.clog while ignoring: project/ignore-patterns/canton_log.ignore.txt project/ignore-patterns/canton_log_bft.ignore.txt
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-10-05T05:36:29.826Z","message":"Sequencer is unhealthy, so disconnecting all members. Can't connect to database","logger_name":"c.d.c.s.s.SequencerRuntime:sequencer=splitwellUpgradeSequencer/psid=splitwellUpgrade::1220f8fb1b86::35-0","thread_name":"canton-env-ec-89","level":"WARN","trace-id":"751b3032ed3c36
Total: 1 lines with problems.
```
The same incident's other lines were already ignored:
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10276/job.log | awk '/^Found ignored entries in /{p=1} p; /^Total: [0-9]+ lines with ignored/{p=0}' | sed -E 's/^\*\*\*"@timestamp":"([^"]+)","message":"(.{0,150}).*/\1 \2/' | cut -c1-200
```
```
Found ignored entries in log/canton_before_shutdown.clog:
2026-10-05T05:36:27.808Z DB_CONNECTION_LOST(13,1fffe391): Database health check failed to establish a valid connection: slick-splitwellUpgradeSequencer-9 - Connection is not a
2026-10-05T05:36:30.381Z Request failed for server-splitwellUpgradeSequencer-0. Is the server running? Did you configure the server address as 0.0.0.0? Are you using the right
2026-10-05T05:36:30.383Z Failed to acknowledge clean timestamp (usually because sequencer is down): ConnectionError(TransportError(Request failed for server-splitwellUpgradeSe
Total: 3 lines with ignored entries.
```

## 2. Which test was running: SvReonboardingIntegrationTest environment setup

The shard's `testOnly` names SvReonboardingIntegrationTest and BootstrapPackageConfigIntegrationTest; only the first
ran a test. 05:36:29 is between "Using external Canton process sv4-reonboarding" (05:34:14) and the first test clue
"Offboard SV4" (05:38:05), i.e. the four SVs were still initializing against the standalone sv123 canton.
```
zcat log/10276/logs-resource-intensive-1/canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '|Running clue: (Using external Canton process|Offboard SV4)" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-150
```
```
2026-10-05T05:34:06.612Z Starting test suite 'SvReonboardingIntegrationTest'...",
2026-10-05T05:34:08.915Z Starting 'SvReonboardingIntegrationTest/reonboard SV with new party id and recover amulet via new regular validator'...",
2026-10-05T05:34:14.217Z Running clue: Using external Canton process sv123-reonboarding",
2026-10-05T05:34:14.240Z Running clue: Using external Canton process sv4-reonboarding",
2026-10-05T05:38:05.942Z Running clue: Offboard SV4",
2026-10-05T05:38:37.447Z Running clue: Using external Canton process sv4-reonboarding-new",
2026-10-05T05:40:26.535Z Test succeeded: 'SvReonboardingIntegrationTest/reonboard SV with new party id and recover amulet via new regular validator'",
2026-10-05T05:40:28.909Z Starting test suite 'BootstrapPackageConfigIntegrationTest'...",
```

## 3. The sequencer health chain on splitwellUpgradeSequencer (05:36:21.794 to 05:36:32.811)

The `db-connection-check` queued at 05:36:21.794 (`Running queued action: db-connection-check`,
`c.d.c.t.WallClock:sequencer=splitwellUpgradeSequencer`) found an idle connection already closed on the client side,
then timed out after 5012 ms with the pool NOT exhausted (`active=4, idle=3, waiting=0`); 10139's identical WARN pair
came from pool exhaustion (`active=8, idle=0`), this one did not.
```
zcat log/10276/logs-resource-intensive-1/canton_before_shutdown.clog.gz | grep -a -E 'zaxxer|DbStorageSingle|SequencerRuntime' | grep -a 'splitwellUpgradeSequencer' | grep -a -E 'T05:36:(2[0-9]|3[0-3])' | grep -a -E 'Failed to validate|DB_CONNECTION_LOST\(13,1|now in state|unhealthy' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-250
```
```
2026-10-05T05:36:26.804Z slick-splitwellUpgradeSequencer-9 - Failed to validate connection org.postgresql.jdbc.PgConnection@3acb3527 (This connection has been closed.). Possibly consider using a shorter maxLifetime value.",[c.zaxxer.hikari.pool.PoolB
2026-10-05T05:36:27.808Z DB_CONNECTION_LOST(13,1fffe391): Database health check failed to establish a valid connection: slick-splitwellUpgradeSequencer-9 - Connection is not available, request timed out after 5012ms (total=7, active=4, idle=3, waitin
2026-10-05T05:36:27.809Z 'db-storage' is now in state Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-splitwellUpgradeSequencer-9 - Connection is not available, request timed out after 
2026-10-05T05:36:29.826Z Sequencer is unhealthy, so disconnecting all members. Can't connect to database",[c.d.c.s.s.SequencerRuntime:sequencer=splitwellUpgradeSequencer/psid=splitwellUpgrade::1220f8fb1b86::35-0] WARN
2026-10-05T05:36:32.811Z 'db-storage' is now in state Ok(). Previous state was Failed(\n  error = DB_CONNECTION_LOST(13,0): Database health check failed to establish a valid connection: slick-splitwellUpgradeSequencer-9 - Connection is not available,
```
The WARN is emitted for every non-active sequencer health state, with the storage detail appended; in the jar that ran
it is `SequencerRuntime$$anon$1.poke` (`SequencerRuntime.scala` lines 206-209, LineNumberTable), and the detail
string comes from `BlockSequencer`:
```
V=3.6.0-snapshot.20261001.20345.0.v85a9270a; curl -sSLo canton-$V.tgz https://www.canton.io/releases/canton-open-source-$V.tar.gz
tar -xzf canton-$V.tgz --wildcards '*/lib/canton-open-source-*.jar'; J=$(ls */lib/canton-open-source-*.jar)
python3 -c "import zipfile,sys;z=zipfile.ZipFile(sys.argv[1]);print([n for n in z.namelist() if n.endswith('.class') and b'so disconnecting all members' in z.read(n)]);print([n for n in z.namelist() if n.endswith('.class') and b\"Can't connect to database\" in z.read(n)])" $J
javap -p -c -l -cp $J 'com.digitalasset.canton.synchronizer.sequencer.SequencerRuntime$$anon$1' | grep -E 'isActive|isClosing|disconnectAllMembers|line 20[6-9]'
```
```
['com/digitalasset/canton/synchronizer/sequencer/SequencerRuntime$$anon$1.class']
['com/digitalasset/canton/synchronizer/sequencer/block/BlockSequencer.class']
        17: invokevirtual #43                 // Method com/digitalasset/canton/synchronizer/sequencer/admin/data/SequencerHealthStatus.isActive:()Z
        27: invokevirtual #46                 // Method com/digitalasset/canton/synchronizer/sequencer/SequencerRuntime.isClosing:()Z
        line 206: 0
        line 207: 16
        line 208: 33
        line 209: 71
        line 208: 88
        line 209: 0
         8: invokevirtual #229                // Method com/digitalasset/canton/synchronizer/sequencing/service/GrpcSequencerService.disconnectAllMembers:(Lcom/digitalasset/canton/tracing/TraceContext;)V
         2: invokevirtual #240                // Method com/digitalasset/canton/synchronizer/sequencing/service/channel/GrpcSequencerChannelService.disconnectAllMembers:(Lcom/digitalasset/canton/tracing/TraceContext;)V
```
So any DB_CONNECTION_LOST on a sequencer's storage that lasts until the next sequencer health poke produces this WARN.

## 4. The stall is server-wide: three sequencers, two JVMs, three databases, same-millisecond completions

Reference-driver writes (`Storing an ordered request` -> `Stored batch of requests`) take 5-450 ms before the stall. From
05:36:22.0 the pending writes of splitwellSequencer and splitwellUpgradeSequencer (main canton JVM, databases
`sequencer_driver_splitwell`, `sequencer_driver_splitwell_upgrade`, `simple-topology-canton.conf:172,176`) and
sv1StandaloneSequencer (standalone sv123 JVM, `sequencer_driver_<suffix>` from `StandaloneCanton.scala:97`) all hang and
then complete in the same millisecond, four times in a row. Two processes that share nothing but the Postgres server
cannot synchronize like that on their own; the server released all waiting commits at once.
```
D=log/10276/logs-resource-intensive-1; for f in canton_before_shutdown canton-standalone-sv123-reonboarding; do zcat $D/$f.clog.gz | grep -a -E 'Storing an ordered request|Stored batch of requests' | grep -a -E 'T05:36:(2[1-9]|3[0-5])' | sed -E 's/\{"@timestamp":"([^"]+)","message":"(Storing an ordered request|Stored batch of requests).*sequencer=([A-Za-z0-9]+).*/\1 \3 \2/'; done | sort -k1,1 -s
```
```
2026-10-05T05:36:21.077Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:21.360Z splitwellUpgradeSequencer Stored batch of requests
2026-10-05T05:36:21.361Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:21.366Z splitwellUpgradeSequencer Stored batch of requests
2026-10-05T05:36:21.477Z splitwellSequencer Storing an ordered request
2026-10-05T05:36:21.697Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:21.899Z splitwellSequencer Stored batch of requests
2026-10-05T05:36:21.945Z splitwellUpgradeSequencer Stored batch of requests
2026-10-05T05:36:22.000Z splitwellSequencer Storing an ordered request
2026-10-05T05:36:22.069Z sv1StandaloneSequencer Storing an ordered request
2026-10-05T05:36:22.208Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:26.860Z splitwellUpgradeSequencer Stored batch of requests
2026-10-05T05:36:26.860Z splitwellSequencer Stored batch of requests
2026-10-05T05:36:26.860Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:26.860Z splitwellSequencer Storing an ordered request
2026-10-05T05:36:26.860Z sv1StandaloneSequencer Stored batch of requests
2026-10-05T05:36:26.860Z sv1StandaloneSequencer Storing an ordered request
2026-10-05T05:36:29.574Z splitwellSequencer Stored batch of requests
2026-10-05T05:36:29.574Z splitwellUpgradeSequencer Stored batch of requests
2026-10-05T05:36:29.574Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:29.574Z splitwellSequencer Storing an ordered request
2026-10-05T05:36:29.574Z sv1StandaloneSequencer Stored batch of requests
2026-10-05T05:36:29.575Z sv1StandaloneSequencer Storing an ordered request
2026-10-05T05:36:35.029Z splitwellSequencer Stored batch of requests
2026-10-05T05:36:35.029Z splitwellUpgradeSequencer Stored batch of requests
2026-10-05T05:36:35.029Z splitwellSequencer Storing an ordered request
2026-10-05T05:36:35.029Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:35.029Z sv1StandaloneSequencer Stored batch of requests
2026-10-05T05:36:35.030Z sv1StandaloneSequencer Storing an ordered request
2026-10-05T05:36:35.820Z splitwellUpgradeSequencer Stored batch of requests
2026-10-05T05:36:35.820Z splitwellSequencer Stored batch of requests
2026-10-05T05:36:35.820Z sv1StandaloneSequencer Stored batch of requests
2026-10-05T05:36:35.821Z splitwellUpgradeSequencer Storing an ordered request
2026-10-05T05:36:35.821Z splitwellSequencer Storing an ordered request
2026-10-05T05:36:35.821Z sv1StandaloneSequencer Storing an ordered request
```
The splitwellUpgradeSequencer health check (05:36:21.794 + 5 s) fell entirely inside the first stall
(05:36:22.0-05:36:26.860).

## 5. The third process (sbt test JVM) and the standalone nodes saw the same stall

sv1's DomainTimeIngestionTrigger (apps, sbt JVM) normally completes within 0.2-3 s; the task scheduled at 05:36:21.127
completed 21 s later. The standalone participants processed a 05:36:22 batch 20-27 s late.
```
D=log/10276/logs-resource-intensive-1
zcat $D/canton_network_test.clog.gz | grep -a 'DomainTimeIngestionTrigger:SvReonboardingIntegrationTest/config=4972d029/SV=sv1"' | grep -a 'Completed processing' | grep -a -E 'T05:36:(1[5-9]|[2-5][0-9])' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Completed processing PeriodicTask\(for = ([^)]+)\).*/\1 completed task scheduled for \2/'
zcat $D/canton-standalone-sv123-reonboarding.clog.gz $D/canton-standalone-sv4-reonboarding.clog.gz | grep -a 'Detected late processing' | grep -a -E 'T05:3[67]' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Detected late processing \(or clock skew\) of batch with timestamp = ([^;]+); delta = ([^ ]+) .*"logger_name":"[^:]*:(participant|mediator)=([A-Za-z0-9]+).*/\1 \5 batch=\2 delta=\3/' | sort
```
```
2026-10-05T05:36:16.170Z completed task scheduled for 2026-10-05T05:36:15.951517Z
2026-10-05T05:36:19.059Z completed task scheduled for 2026-10-05T05:36:17.086113Z
2026-10-05T05:36:20.026Z completed task scheduled for 2026-10-05T05:36:20.007317Z
2026-10-05T05:36:42.311Z completed task scheduled for 2026-10-05T05:36:21.126746Z
2026-10-05T05:36:51.251Z completed task scheduled for 2026-10-05T05:36:43.400591Z
2026-10-05T05:36:54.396Z completed task scheduled for 2026-10-05T05:36:52.280762Z
2026-10-05T05:37:00.026Z completed task scheduled for 2026-10-05T05:36:55.394556Z
2026-10-05T05:37:01.131Z completed task scheduled for 2026-10-05T05:37:01.127072Z
2026-10-05T05:36:42.309Z sv1StandaloneParticipant batch=2026-10-05T05:36:22.052084Z delta=PT20.257434S
2026-10-05T05:36:47.224Z sv2StandaloneParticipant batch=2026-10-05T05:36:22.077316Z delta=PT25.147545S
2026-10-05T05:36:47.225Z sv4StandaloneParticipant batch=2026-10-05T05:36:22.077317Z delta=PT25.147771S
2026-10-05T05:36:49.392Z sv3StandaloneParticipant batch=2026-10-05T05:36:22.077318Z delta=PT27.315454S
2026-10-05T05:36:49.874Z sv1StandaloneMediator batch=2026-10-05T05:36:28.345853Z delta=PT21.528964S
```
The standalone global sequencer went through the identical chain 10 s later; this job does not run checkErrors on
the standalone logs (section 1 shows only `log/canton_before_shutdown.clog` being checked), so it did not fail:
```
zcat log/10276/logs-resource-intensive-1/canton-standalone-sv123-reonboarding.clog.gz | grep -a -E 'Sequencer is unhealthy|DB_CONNECTION_LOST\(13,c' | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-230
```
```
2026-10-05T05:36:34.842Z DB_CONNECTION_LOST(13,c489440e): Database health check failed to establish a valid connection: slick-sv1StandaloneSequencer-4 - Connection is not available, request timed out after 5004ms (total=6, active=
2026-10-05T05:36:39.588Z Sequencer is unhealthy, so disconnecting all members. Can't connect to database",[c.d.c.s.s.SequencerRuntime:sequencer=sv1StandaloneSequencer/psid=global-domain::1220fa5ff2fe::35-0] WARN
```

## 6. Not family L at the flagged time

Family L (reference sequencer `insert block` 40001 storm) does occur in this shard, but only from 05:37:53, 84 s after
the flagged WARN, and never in the main canton process that logged it:
```
D=log/10276/logs-resource-intensive-1; for f in canton_before_shutdown canton-standalone-sv123-reonboarding canton-standalone-sv4-reonboarding; do printf '%s total=%s first=%s\n' $f "$(zcat $D/$f.clog.gz | grep -a -c "The operation 'insert block' has failed")" "$(zcat $D/$f.clog.gz | grep -a -m1 "The operation 'insert block' has failed" | cut -c15-38)"; done
```
```
canton_before_shutdown total=0 first=
canton-standalone-sv123-reonboarding total=158 first="2026-10-05T05:37:53.156
canton-standalone-sv4-reonboarding total=4 first="2026-10-05T05:38:00.973
```

## 7. Existing ignore covers the first line of the chain, not this one

`DB_CONNECTION_LOST ... Connection is not available, request timed out` is ignored in both the canton and the test log
since #3036 (350bc7c131, 2025-10-31, "Sometimes the CI executor seems to take a break ... see #9388"). The sequencer's
follow-up WARN is not, so any such stall that hits a sequencer for longer than one health poke fails the shard.
```
git show 4a7f355b17:project/ignore-patterns/canton_log.ignore.txt | sed -n 166,167p
git grep -n -E 'unhealthy|Can.t connect to database' 4a7f355b17 -- project/ignore-patterns/ | wc -l
```
```
# Sometimes the CI executor seems to take a break, which leads to spurious failed DB activeness checks, see #9388
DB_CONNECTION_LOST.*Database health check failed to establish a valid connection.*Connection is not available, request timed out
0
```

## 8. A second unhealthy sequencer that checkErrors never looked at

checkErrors checks the log files one after another and stops at the first one with problems (`sys.error`, build.sbt:2305
at 4a7f355b17). Order: `canton` before/after shutdown, `canton-simtime`, `canton-missing-signatures`, every
`canton-standalone-*`, then `canton_network_test`. This job failed on the first file, so the standalone logs were never
checked: the job log has a single `Checking` line.

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10276/job.log | grep -a -n -E '^Checking log/|^Found problems in|contains problems'
11748:Checking log/canton_before_shutdown.clog while ignoring: project/ignore-patterns/canton_log.ignore.txt project/ignore-patterns/canton_log_bft.ignore.txt
11755:Found problems in log/canton_before_shutdown.clog:
11759:[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
11779:[error] (checkErrors) log/canton_before_shutdown.clog contains problems.

$ git show 4a7f355b17:build.sbt | sed -n '2297,2307p;2337,2361p'
  def checkLogs(logFileName: String, ignorePatterns: Seq[String]): Unit = {
    val ignorePatternsFilenames = ignorePatterns.map(ignorePatternsFilename)
    val cmd =
      Seq(
        ".github/actions/scripts/check-logs.sh",
        logFileName,
      ) ++ ignorePatternsFilenames
    if (cmd.! != 0) {
      sys.error(s"$logFileName contains problems.")
    }
  }
    checkLogs(logFileBefore, beforeIgnorePatterns)
    checkLogs(logFileAfter, afterIgnorePatterns)
  }

  splitAndCheckCantonLogFile("canton", usesSimtime = false)
  splitAndCheckCantonLogFile("canton-simtime", usesSimtime = true)
  splitAndCheckCantonLogFile("canton-missing-signatures", usesSimtime = false)
  import better.files._
  val dir = File("log/")
  if (dir.exists())
    dir
      .glob("canton-standalone-*.clog")
      .map(_.nameWithoutExtension)
      .map(_.stripSuffix("_before_shutdown"))
      .map(_.stripSuffix("_after_shutdown"))
      .toList
      .distinct
      .foreach { name =>
        splitAndCheckCantonLogFile(
          name,
          usesSimtime = false,
        )
      }

  checkLogs("log/canton_network_test.clog", Seq("canton_network_test_log"))
```

The standalone sv123 canton (a separate process from the main canton) logged the same chain on sv1StandaloneSequencer
5-10 s after the flagged line. Unlike splitwellUpgradeSequencer (active=4, idle=3, section 3), this pool was
exhausted, consistent with connections held by stuck queries on a stalled server:

```
$ zcat log/10276/logs-resource-intensive-1/canton-standalone-sv123-reonboarding.clog.gz | grep -a -E '05:36:(34.842|39.588)Z' | grep -a sv1StandaloneSequencer | sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-330
2026-10-05T05:36:34.842Z DB_CONNECTION_LOST(13,c489440e): Database health check failed to establish a valid connection: slick-sv1StandaloneSequencer-4 - Connection is not available, request timed out after 5004ms (total=6, active=6, idle=0, waiting=2)",[c.d.c.r.DbStorageSingle:sequencer=sv1StandaloneSequencer] WARN
2026-10-05T05:36:39.588Z Sequencer is unhealthy, so disconnecting all members. Can't connect to database",[c.d.c.s.s.SequencerRuntime:sequencer=sv1StandaloneSequencer/psid=global-domain::1220fa5ff2fe::35-0] WARN
```

Rerunning the check the way checkErrors would have (before-shutdown half, same ignore files as build.sbt:2333 at the
run's sha) flags the second WARN and ignores the DB_CONNECTION_LOST line (`canton_log.ignore.txt:167`):

```
$ T=log/10276/recheck; mkdir -p $T; zcat log/10276/logs-resource-intensive-1/canton-standalone-sv123-reonboarding.clog.gz > $T/canton-standalone-sv123-reonboarding.clog
$ .github/actions/scripts/split-canton-logs.sh $T/canton-standalone-sv123-reonboarding.clog $T/b.clog $T/a.clog
$ .github/actions/scripts/check-logs.sh $T/b.clog project/ignore-patterns/canton_log.ignore.txt project/ignore-patterns/canton-standalone-sv123-reonboarding.ignore.txt project/ignore-patterns/canton_log_bft.ignore.txt | grep -v 'ignore this line' | cut -c1-260 | tail -8
Checking log/10276/recheck/b.clog while ignoring: project/ignore-patterns/canton_log.ignore.txt project/ignore-patterns/canton-standalone-sv123-reonboarding.ignore.txt project/ignore-patterns/canton_log_bft.ignore.txt
Found ignored entries in log/10276/recheck/b.clog:
Total: 139 lines with ignored entries.

Found problems in log/10276/recheck/b.clog:
{"@timestamp":"2026-10-05T05:36:39.588Z","message":"Sequencer is unhealthy, so disconnecting all members. Can't connect to database","logger_name":"c.d.c.s.s.SequencerRuntime:sequencer=sv1StandaloneSequencer/psid=global-domain::1220fa5ff2fe::35-0","thread_name
Total: 1 lines with problems.
```

Two consequences: a fourth sequencer, in a third canton process, confirms the server-wide stall; and a checkErrors
failure shows only the first failing file, so a single flagged line does not mean the shard had a single problem.

## Verdict

New ref, not a duplicate of a catalogued family. Same class as 10176's runner Postgres commit-latency outlier (a
runner-level Postgres stall), surfacing here through the sequencer health path instead of a trigger pause. The WARN pair
also appears in 10139, but there it was pool exhaustion from family L; here the pool had idle capacity and family L
starts only at 05:37:53. A second unhealthy-sequencer WARN (sv1StandaloneSequencer, 05:36:39.588, pool exhausted) would
also have failed the job, but checkErrors stops at the first failing file (section 8). Flake, infra; resolution: rerun.

Fix location: runner infrastructure (the Postgres service container shared by every canton process and the apps). No
test-side fix branch: the test did nothing wrong. Two follow-ups, both left to the owners:
- Collect the Postgres server log in the scala_test jobs (the `postgres` service container output, or `log_checkpoints` /
  `log_autovacuum_min_duration` / `log_min_duration_statement` in `postgres_init_args`), so the next stall can be
  attributed to I/O, a checkpoint or autovacuum. Without it this ref cannot be taken further.
- Ignore option, deliberately NOT written: the consistent extension of #3036 would be
  `Sequencer is unhealthy, so disconnecting all members. Can't connect to database` next to `canton_log.ignore.txt:167`.
  It would also hide the same WARN when it comes from family L pool exhaustion (10139), so it should be the owners'
  decision, not a triage default.

Not verified: what stalled the Postgres server (no server log, runner metrics not checked); why the idle pool
connections were already closed on the client side (`This connection has been closed.` without a preceding I/O error in
the logs); whether the stall's start at 05:36:22.0 coincides with a specific DB-heavy step (DAR uploads by sv2-sv4 run
continuously from 05:36:00 and were heavier at 05:37:0x without a stall).
