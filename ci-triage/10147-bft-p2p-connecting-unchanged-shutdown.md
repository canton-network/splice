# 10147 - [UNEXPECTED] BFT P2P state transition "Connecting (unchanged)" while the standalone DR canton is destroyed (run 35082230264)

Post-merge CI on main, sha 743a6ec124 "configurable waf alerts threhsold (#7333)", 2026-09-16T09:56Z.
Canton runtime 3.6.0-snapshot.20260910.20260.0.v90621933. One failed job so far (run still in progress at
triage time). Commands verified against the artifacts in log/10147/.

## Categorization

- Test(s) affected: none asserted-failed. The shard runs one suite, RollForwardLsuDRIntegrationTest, and
  its single test "roll forward LSU DR" passed. The WARN was emitted 211 ms after the test destroyed the
  standalone canton process `roll-forward-lsu-dr` (test teardown, inside `withCantonSvNodes`).
- Failure type: checkErrors WARN (un-allowlisted line in
  canton-standalone-roll-forward-lsu-dr_after_shutdown.clog, i.e. in the shutdown half of the log).
- Component: Canton BFT orderer P2P layer (`P2PGrpcConnectionManager` / `P2PNetworkOutModule`), race
  between the automatic reconnect to a peer sequencer that is shutting down in the same JVM and the
  shutdown of that same connection. Splice side: no shutdown ignore pattern for this class of line.
- Flake vs real: flake (timing). Four BFT sequencers (sv1-sv4Standalone) live in one process and are closed
  sequentially; sv2's orderer closed first, sv4 reconnected to it twice within 5 ms while its own
  connection to sv2 was being torn down, and one connect worker found the state already replaced.

## Setup

```
TMPDIR=<roomy>/ghtmp gh run download 35082230264 --repo canton-network/splice -n logs-roll-forward-lsu-0 -D dl
cd dl
# canton-standalone-roll-forward-lsu-dr_{before,after}_shutdown.clog.gz = the BFT standalone canton used AFTER
#   the disaster (4 sequencers + 4 mediators in one JVM); *-before-dr_* = the non-BFT one used before it
# canton_network_test.clog.gz = harness + splice apps
# job-104749000327.log = full GHA job console (gh api repos/canton-network/splice/actions/jobs/104749000327/logs)
```

## 1. Failed job

```
gh run view 35082230264 --repo canton-network/splice --json status,conclusion,jobs \
  --jq '"status=\(.status) conclusion=\(.conclusion)", (.jobs[] | select(.conclusion=="failure") | "\(.databaseId)  \(.name)")'
```
```
status=in_progress conclusion=
104749000327  ci / scala_test_roll_forward_lsu / roll-forward-lsu (0)
```

## 2. checkErrors problem line as flagged, and all tests passed

```
grep -anE 'Tests: succeeded|All tests passed|Found problems|lines with problems|Checking log/canton-standalone-roll-forward-lsu-dr_after|\(checkErrors\) log|Process completed with exit code' \
  job-104749000327.log | cut -c1-260
```
```
11046:2026-09-16T10:10:24.2522845Z [info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
11047:2026-09-16T10:10:24.2523303Z [info] All tests passed.
11680:2026-09-16T10:10:47.9173812Z Checking log/canton-standalone-roll-forward-lsu-dr_after_shutdown.clog while ignoring: project/ignore-patterns/canton_log.ignore.txt project/ignore-patterns/canton_log_bft.ignore.txt project/ignore-patterns/canton_log_shutdow
11683:2026-09-16T10:10:47.9176367Z Found problems in log/canton-standalone-roll-forward-lsu-dr_after_shutdown.clog:
11685:2026-09-16T10:10:47.9182275Z Total: 1 lines with problems.
11710:2026-09-16T10:10:47.9197826Z [error] (checkErrors) log/canton-standalone-roll-forward-lsu-dr_after_shutdown.clog contains problems.
11715:2026-09-16T10:10:48.9378965Z ##[error]Process completed with exit code 1.
```

```
zcat canton_network_test.clog.gz | grep -a "Starting test suite" | grep -aoE "suite '[^']+'" | sort | uniq -c
echo "succeeded: $(zcat canton_network_test.clog.gz | grep -ac 'Test succeeded:')  failed: $(zcat canton_network_test.clog.gz | grep -ac 'Test failed:')"
for f in canton-standalone-roll-forward-lsu-dr_before_shutdown canton-standalone-roll-forward-lsu-dr_after_shutdown \
  canton-standalone-roll-forward-lsu-before-dr_before_shutdown canton-standalone-roll-forward-lsu-before-dr_after_shutdown \
  canton_before_shutdown; do echo "$f: $(zcat $f.clog.gz | grep -ac 'UNEXPECTED')"; done
```
```
      1 suite 'RollForwardLsuDRIntegrationTest'
succeeded: 1  failed: 0
canton-standalone-roll-forward-lsu-dr_before_shutdown: 0
canton-standalone-roll-forward-lsu-dr_after_shutdown: 1
canton-standalone-roll-forward-lsu-before-dr_before_shutdown: 0
canton-standalone-roll-forward-lsu-before-dr_after_shutdown: 0
canton_before_shutdown: 0
```

The only other checkErrors hit in the job is an ignored one in the main canton log (`ABORTED/SEQUENCER_OVERLOADED
... P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)` at 10:10:00.003, matched by
canton_log.ignore.txt:192). The harness log (`canton_network_test.clog`) was never checked in this job because
checkErrors aborts at the first failing file (`grep -c 'Checking log/canton_network_test.clog' job-*.log` = 0).

## 3. The full WARN line

```
zcat canton-standalone-roll-forward-lsu-dr_after_shutdown.clog.gz | grep -a 'UNEXPECTED' | sed -E 's/[0-9a-f]{16,}/<HASH>/g'
```
```
{"@timestamp":"2026-09-16T10:10:10.186Z","message":"[UNEXPECTED] State transition for P2PUrl(\"http://localhost:28210\") when attempting to connect over gRPC channel with worker: Connecting (unchanged)","logger_name":"c.d.c.s.s.b.b.b.p.g.P2PGrpcConnectionManager:sequencer=sv4StandaloneSequencer/psid=global-domain::1220255b40a7::35-2","thread_name":"canton-env-ec-40","level":"WARN","span-id":"<HASH>","span-parent-id":"<HASH>","trace-id":"<HASH>","span-name":"BFTOrderer.Availability.ProposeBlock"}
```

Emitter: sv4StandaloneSequencer's BFT orderer P2P connection manager, on psid `global-domain::...::35-2` (the
post-DR synchronizer, serial 2), from within the availability module's `ProposeBlock` span, i.e. sv4 was
trying to disseminate a block to peer `localhost:28210` when this happened.

## 4. Canton runtime version, and the string verified against the jar that ran

```
zcat canton-standalone-roll-forward-lsu-dr_before_shutdown.clog.gz | grep -aoE 'Canton version [0-9][^" ]*' | head -1
cd <splice>; git show 743a6ec124:nix/canton-sources.json | grep -oE '"(version|oss_sha256)": *"[^"]+"'; cat canton/VERSION
```
```
Canton version 3.6.0-snapshot.20260910.20260.0.v90621933
"version": "3.6.0-snapshot.20260910.20260.0.v90621933"
"oss_sha256": "sha256:06h7q93xrgy3nzrjljr6gcvcz4ld237rnhvhp3s5208i4sblasi8"
3.5.7-SNAPSHOT
```

The vendored `canton/` tree is 3.5.7-SNAPSHOT and is NOT what ran; the /nix/store jars in this sandbox are
3.5.5 and 3.5.10, also not what ran. The real jar was fetched and hash-checked:

```
V=3.6.0-snapshot.20260910.20260.0.v90621933
curl -sSLo canton.tgz "https://www.canton.io/releases/canton-open-source-$V.tar.gz"
nix-hash --type sha256 --flat --base32 canton.tgz
tar -xzf canton.tgz --wildcards "*/lib/canton-open-source-*.jar"; JAR=$(ls */lib/canton-open-source-*.jar)
python3 - "$JAR" <<'EOF'
import sys, zipfile
z=zipfile.ZipFile(sys.argv[1])
names=[n for n in z.namelist() if n.endswith('.class') and 'bftordering' in n]
for needle in [b'when attempting to connect over gRPC channel with worker', b'(unchanged)', b'UNEXPECTED]',
               b'is still configured on this node, ensuring an outgoing connection']:
    print(needle.decode(), '->', [n.split('/')[-1] for n in names if needle in z.read(n)])
EOF
```
```
06h7q93xrgy3nzrjljr6gcvcz4ld237rnhvhp3s5208i4sblasi8
when attempting to connect over gRPC channel with worker -> ['P2PGrpcConnectionManager.class']
(unchanged) -> ['P2PGrpcConnectionManager$State.class']
UNEXPECTED] -> ['Miscellaneous$ResultWithLogs.class', 'NamedLoggingUtils.class']
is still configured on this node, ensuring an outgoing connection -> ['P2PNetworkOutModule.class']
```

The hash equals `oss_sha256` at the failing sha, so all javap citations below are against the version that ran.

## 5. What "after_shutdown" means, and which ignore sets applied

The split marker is Canton's own "Shutting down..." line; everything from it onward goes to `_after_shutdown`:

```
cd <splice>; grep -n 'SHUTDOWN_MESSAGE_PATTERN=' .github/actions/scripts/split-canton-logs.sh
zcat canton-standalone-roll-forward-lsu-dr_after_shutdown.clog.gz | head -1
zcat canton-standalone-roll-forward-lsu-dr_after_shutdown.clog.gz | grep -aoE '^\{"@timestamp":"[^"]*"' | sed -n '$p'; zcat canton-standalone-roll-forward-lsu-dr_after_shutdown.clog.gz | wc -l
```
```
19:SHUTDOWN_MESSAGE_PATTERN='"message":"Shutting down\.\.\.","logger_name":"c\.d\..*\.Canton.*App$"'
{"@timestamp":"2026-09-16T10:10:09.975Z","message":"Shutting down...","logger_name":"c.d.canton.CantonCommunityApp$","thread_name":"Thread-0","level":"INFO"}
{"@timestamp":"2026-09-16T10:10:15.282Z"
6140
```

So the flagged line (10:10:10.186) is 211 ms into the shutdown of the standalone dr canton, which finished at
10:10:15.282. build.sbt picks the pattern sets as follows (standalone files get the same treatment as the main
canton log; a per-instance file is added only if `project/ignore-patterns/<logName>.ignore.txt` exists):

```
grep -nE 'val logSpecificIgnores =|if \(File\(ignorePatternsFilename|val bftIgnore =|val beforeIgnorePatterns =|Seq\("canton_log"\) \+\+|val afterIgnorePatterns =|\+\+ Seq\("canton_log_shutdown_extra"\)|glob\("canton-standalone' build.sbt
ls project/ignore-patterns/ | grep -iE 'roll|lsu' || echo "no roll-forward-lsu specific ignore file"
ls project/ignore-patterns/ | grep -c '^canton-standalone-'
```
```
2293:    val logSpecificIgnores =
2294:      if (File(ignorePatternsFilename(logName)).exists()) Seq(logName) else Seq.empty
2296:    val bftIgnore = Seq("canton_log_bft")
2299:    val beforeIgnorePatterns =
2300:      Seq("canton_log") ++ simtimeIgnorePatterns ++ logSpecificIgnores ++ bftIgnore
2301:    val afterIgnorePatterns =
2302:      beforeIgnorePatterns ++ Seq("canton_log_shutdown_extra") ++ logSpecificIgnores
2315:      .glob("canton-standalone-*.clog")
no roll-forward-lsu specific ignore file
12
```

The job console (section 2, line 11680) confirms the after file was checked with exactly
`canton_log + canton_log_bft + canton_log_shutdown_extra`. Twelve other standalone instances have a
`canton-standalone-<name>.ignore.txt`; `roll-forward-lsu-dr` and `roll-forward-lsu-before-dr` have none.

## 6. Where in the test this happens: the teardown of the post-DR standalone canton

```
zcat canton_network_test.clog.gz | grep -aE 'Starting test suite|Test succeeded|external Canton process|Start nodes before DR|Stop old apps before DR|wait for upgrade time|new nodes are initialized|Alice can tap|sv and scan app can be restarted|stop apps manually' \
  | jq -r '"\(.["@timestamp"]) \(.message | gsub("\n";" ") | .[0:100])"' | awk '!seen[substr($0,25,60)]++'
```
```
2026-09-16T10:05:43.443Z Starting test suite 'RollForwardLsuDRIntegrationTest'...
2026-09-16T10:05:51.558Z Running clue: Starting external Canton process roll-forward-lsu-before-dr with List(canton.sequencers.
2026-09-16T10:05:51.590Z Running clue: Start nodes before DR
2026-09-16T10:08:25.067Z Finished clue: Start nodes before DR
2026-09-16T10:08:29.042Z Running clue: Stop old apps before DR
2026-09-16T10:08:29.178Z Finished clue: Stop old apps before DR
2026-09-16T10:08:29.242Z Running clue: Starting external Canton process roll-forward-lsu-dr with List(canton.sequencers.sv1Sta
2026-09-16T10:08:29.263Z Running clue: wait for upgrade time 2026-09-16T10:09:48.416444Z
2026-09-16T10:09:48.416Z Finished clue: wait for upgrade time 2026-09-16T10:09:48.416444Z
2026-09-16T10:10:06.570Z Running clue: new nodes are initialized
2026-09-16T10:10:07.233Z Finished clue: new nodes are initialized
2026-09-16T10:10:07.748Z Running clue: Alice can tap
2026-09-16T10:10:08.685Z Finished clue: Alice can tap
2026-09-16T10:10:08.685Z Running clue: sv and scan app can be restarted
2026-09-16T10:10:09.743Z Finished clue: sv and scan app can be restarted
2026-09-16T10:10:09.744Z Running clue: stop apps manually to prevent errors from the synchronizer being force stopped
2026-09-16T10:10:09.973Z Finished clue: stop apps manually to prevent errors from the synchronizer being force stopped
2026-09-16T10:10:09.973Z Running clue: Destroying external Canton process roll-forward-lsu-dr
2026-09-16T10:10:15.714Z Finished clue: Destroying external Canton process roll-forward-lsu-dr
2026-09-16T10:10:16.406Z Test succeeded: 'RollForwardLsuDRIntegrationTest/roll forward LSU DR'
```

The test (apps/app/src/test/scala/.../RollForwardLsuDRIntegrationTest.scala, identical at 743a6ec124 except for a
6-line PV tweak from #7234) runs two standalone cantons in sequence: `roll-forward-lsu-before-dr`
(`enableBftSequencer = false`, the "broken" synchronizer) and, nested inside it, `roll-forward-lsu-dr`
(`enableBftSequencer = true`, `portsRange = Some(28)`, the successor synchronizer serial 2 with sv1-sv4
standalone sequencers and mediators in ONE JVM). The last clue stops all sv*Local/scan*Local apps and
disconnects all participants, then the `withCantonSvNodes` block exits and ProcessTestUtil destroys the process:

```
cd <splice>; grep -n -A3 'def destroyAndWait' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/ProcessTestUtil.scala
grep -n -B1 -A2 'Destroying external Canton process' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/ProcessTestUtil.scala
grep -nE 'logSuffix = "roll-forward-lsu-dr"|enableBftSequencer = true|portsRange = Some\(28\)|clue\("stop apps manually' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala
```
```
17:    def destroyAndWait(): Unit = {
18-      process.destroy()
19-      process.waitFor()
20-    }
77-    )((resource: Process) => {
78:      clue(s"Destroying external Canton process $logSuffix") {
79-        resource.destroyAndWait()
80-      }
258:        enableBftSequencer = true,
259:        logSuffix = "roll-forward-lsu-dr",
264:        portsRange = Some(28),
475:        clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
```

`process.destroy()` (SIGTERM) at 10:10:09.973 -> Canton's "Shutting down..." at 10:10:09.975 -> WARN at
10:10:10.186 -> process gone at 10:10:15.714. The test itself does not stop individual sequencers; Canton's
environment close decides the node order (section 8).

## 7. P2PUrl localhost:28210 is sv2StandaloneSequencer's BFT P2P endpoint

```
cd <splice>; sed -n '100,110p' apps/app/src/test/scala/org/lfdecentralizedtrust/splice/util/StandaloneCanton.scala | grep -n 'BFT_PORT'
grep -n 'SV2_SEQUENCER_BFT_PORT' apps/app/src/test/resources/standalone-sequencers-sv123-extra-enable-bft.conf
zcat canton-standalone-roll-forward-lsu-dr_before_shutdown.clog.gz | grep -a 'successfully bound P2P endpoint' \
  | jq -r '"\(.["@timestamp"]) \(.logger_name | sub("/psid=.*";"")) :: \(.message)"'
```
```
9:          s"SV${i}_SEQUENCER_BFT_PORT" -> (range * 1000 + i * 100 + 10).toString,
22:    sv2StandaloneSequencer.sequencer.config.initial-network.server-endpoint.port = ${?SV2_SEQUENCER_BFT_PORT}
23:    sv2StandaloneSequencer.sequencer.config.initial-network.server-endpoint.external-port = ${?SV2_SEQUENCER_BFT_PORT}
2026-09-16T10:09:54.192Z c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=sv3StandaloneSequencer :: successfully bound P2P endpoint 0.0.0.0:28310
2026-09-16T10:09:54.192Z c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=sv4StandaloneSequencer :: successfully bound P2P endpoint 0.0.0.0:28410
2026-09-16T10:09:54.192Z c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=sv2StandaloneSequencer :: successfully bound P2P endpoint 0.0.0.0:28210
2026-09-16T10:09:54.192Z c.d.c.s.s.b.b.b.c.s.BftBlockOrderer:sequencer=sv1StandaloneSequencer :: successfully bound P2P endpoint 0.0.0.0:28110
```

28*1000 + 2*100 + 10 = 28210 = sv2. The four BFT sequencers only bound their P2P endpoints at 10:09:54.192
(after the upgrade time passed), i.e. the P2P mesh existed for under 16 s before the process was destroyed.

## 8. Node shutdown order inside the dr process: sv2's orderer closes first, sv4's 71 ms later

```
zcat canton-standalone-roll-forward-lsu-dr_after_shutdown.clog.gz | grep -a 'BftBlockOrderer\|PekkoP2PGrpcNetworkManager' | grep -aiE 'shutdown|Closing P2P' \
  | jq -r '"\(.["@timestamp"]) \(.logger_name | sub("^c\\.d\\.c\\.s\\.s\\.b\\.b\\.b\\.";"") | sub("/psid=.*";"")) :: \(.message | .[0:60])"' | awk '{k=$2; if(!seen[k]++) print}'
zcat canton-standalone-roll-forward-lsu-dr_after_shutdown.clog.gz | grep -a 'Closing connection to P2PUrl' \
  | jq -r '"\(.["@timestamp"]) \(.logger_name)"' | sed -E 's/.*sequencer=//; s#/psid=.*##' | awk '!seen[$2]++'
```
```
2026-09-16T10:10:10.170Z c.s.BftBlockOrderer:sequencer=sv2StandaloneSequencer :: Beginning async BFT block orderer shutdown
2026-09-16T10:10:10.174Z p.g.PekkoP2PGrpcNetworking$PekkoP2PGrpcNetworkManager:sequencer=sv2StandaloneSequencer :: Closing P2P gRPC network manager
2026-09-16T10:10:10.241Z c.s.BftBlockOrderer:sequencer=sv4StandaloneSequencer :: Beginning async BFT block orderer shutdown
2026-09-16T10:10:15.219Z c.s.BftBlockOrderer:sequencer=sv1StandaloneSequencer :: Beginning async BFT block orderer shutdown
2026-09-16T10:10:15.246Z c.s.BftBlockOrderer:sequencer=sv3StandaloneSequencer :: Beginning async BFT block orderer shutdown
2026-09-16T10:10:10.175Z sv2StandaloneSequencer
2026-09-16T10:10:15.189Z sv4StandaloneSequencer
2026-09-16T10:10:15.246Z sv3StandaloneSequencer
```

Mediators close first (sv2, sv3, sv4, sv1 between 10:10:09.987 and 10:10:10.141), then sequencers. At
10:10:10.186 sv2's P2P manager had been closing for 12 ms and sv4's own orderer shutdown had not started yet
(it starts at .241; its P2P connections are only closed at 15.189). sv4 was therefore a live BFT node reacting
to a peer that vanished.

## 9. sv4's connection state machine for peer 28210, before and after the split

```
for f in canton-standalone-roll-forward-lsu-dr_before_shutdown canton-standalone-roll-forward-lsu-dr_after_shutdown; do echo "-- $f"
  zcat $f.clog.gz | grep -a 'sequencer=sv4StandaloneSequencer' | grep -a 'State transition for P2PUrl(\\"http://localhost:28210' \
  | jq -r '"\(.["@timestamp"]) \(.level) \(.message)"' | sed -E 's/ManagedChannelOrphanWrapper\{delegate=ManagedChannelImpl\{logId=([0-9]+), target=localhost:28210\}\}/ch#\1/g' | cut -c1-250; done
```
```
-- canton-standalone-roll-forward-lsu-dr_before_shutdown
2026-09-16T10:09:59.687Z INFO State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection: Disconnected (not in state) -> Connecting
2026-09-16T10:09:59.688Z INFO State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection after creating a channel: Connecting -> ConnectingOnChannel(ch = ch#127, cwO = None(), acO = Some(1339700327)))
2026-09-16T10:09:59.740Z INFO State transition for P2PUrl("http://localhost:28210") when attempting to connect over gRPC channel with worker: ConnectingOnChannel(ch = ch#127, cwO = None(), acO = Some(1339700327)) -> ConnectingOnChannel(ch = ch#127, c
2026-09-16T10:10:00.058Z INFO [Connect worker for channel ch#127 w/authctx Some(1339700327)]: State transition for P2PUrl("http://localhost:28210") when attempting to complete outgoing connection (or its disconnection, if requested): DisconnectingFro
-- canton-standalone-roll-forward-lsu-dr_after_shutdown
2026-09-16T10:10:10.181Z INFO State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection: Disconnected (not in state) -> Connecting
2026-09-16T10:10:10.182Z INFO State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection after creating a channel: Connecting -> ConnectingOnChannel(ch = ch#262, cwO = None(), acO = Some(751183898)))
2026-09-16T10:10:10.186Z WARN [UNEXPECTED] State transition for P2PUrl("http://localhost:28210") when attempting to connect over gRPC channel with worker: Connecting (unchanged)
2026-09-16T10:10:10.186Z INFO State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection: Disconnected (not in state) -> Connecting
2026-09-16T10:10:10.190Z DEBUG State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection after creating a channel: Disconnected (not in state, unchanged)
2026-09-16T10:10:15.188Z DEBUG State transition for P2PUrl("http://localhost:28210") after connect worker failure: Disconnected (not in state) (unchanged)
```

Before the shutdown the happy path is: Connecting -> ConnectingOnChannel(ch, cwO=None) -> "with worker"
records the worker (cwO=Some) -> connected. In the shutdown half the "with worker" step for ch#262 finds
`Connecting` instead of `ConnectingOnChannel(ch#262, cwO=None)`.

## 10. The race: three threads on sv4 within 6 ms

```
zcat canton-standalone-roll-forward-lsu-dr_after_shutdown.clog.gz | grep -a 'sequencer=sv4StandaloneSequencer' | grep -a '28210' \
  | jq -r 'select(.["@timestamp"] >= "2026-09-16T10:10:10.170" and .["@timestamp"] <= "2026-09-16T10:10:10.210") | "\(.["@timestamp"] | .[11:23]) \(.level) \(.thread_name) span=\(.["span-name"] // "-") :: \(.message)"' \
  | sed -E 's/[0-9a-f]{16,}/<HASH>/g; s/ManagedChannelOrphanWrapper\{delegate=ManagedChannelImpl\{logId=([0-9]+), target=localhost:28210\}\}/ch#\1/g; s/sv4StandaloneSequencer-global-domain__[^ ]*grpc-executor-context-479/dp2p-server-exec-479/; s/pekko-p2p-grpc-connection-managing-actor-localhost-28210-plaintext-unknown-bft-node-id-/actor-/; s/\[com\.digitalasset\.canton\.[^]]*\]: //' \
  | grep -E 'State transition (for|when)|Shutting down asynchronously any incoming|is still configured|starting a connect worker|Sending Close message|is now (dis)?connected|Removed connection state|Creating new network ref|Channel shutdown invoked' | cut -c1-235
```
```
10:10:10.180 DEBUG canton-env-ec-67 span=BFTOrderer.Availability.ProposeBlock :: State transition when potentially shutting down outgoing connection for P2PUrl("http://localhost:28210") (onlyIfNotFullyConnected: true): Disconnected (no
10:10:10.180 DEBUG dp2p-server-exec-479 span=- :: Shutting down and cleaning up active connection of sender PeerSender(1661839360): Removed connection state for PeerSender(1661839360) <-> SEQ::sv2::<HASH> and cleaned up its association
10:10:10.181 DEBUG dp2p-server-exec-479 span=- :: Sending Close message to connection managing actor for ref PekkoP2PNetworkRef(actor-8aaa0f26-286b-44a4-994d-d576a0f78fd1)
10:10:10.181 INFO canton-env-ec-67 span=BFTOrderer.Availability.ProposeBlock :: State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection: Disconnected (not in state) -> Connecting
10:10:10.182 INFO canton-env-ec-67 span=BFTOrderer.Availability.ProposeBlock :: State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection after creating a channel: Connecting -> ConnectingOnChan
10:10:10.182 INFO canton-env-ec-43 span=- :: P2P endpoint P2PUrl("http://localhost:28210") is now disconnected
10:10:10.184 INFO canton-env-ec-43 span=- :: Disconnected P2P endpoint P2PUrl("http://localhost:28210") is still configured on this node, ensuring an outgoing connection to it
10:10:10.184 INFO canton-env-ec-43 span=- :: Creating new network ref for 'Endpoint(PlainTextP2PEndpoint(localhost,28210))'
10:10:10.184 INFO canton-env-ec-67 span=- :: Shutting down asynchronously any incoming or outgoing connection to Left(P2PUrl("http://localhost:28210"))
10:10:10.185 INFO canton-env-ec-67 span=- :: State transition when potentially shutting down outgoing connection for P2PUrl("http://localhost:28210") (onlyIfNotFullyConnected: false): ConnectingOnChannel(ch = ch#262, cwO = None(), acO
10:10:10.185 INFO canton-env-ec-40 span=BFTOrderer.Availability.ProposeBlock :: Created a gRPC channel ch#262 to P2PUrl("http://localhost:28210"), starting a connect worker
10:10:10.186 WARN canton-env-ec-40 span=BFTOrderer.Availability.ProposeBlock :: [UNEXPECTED] State transition for P2PUrl("http://localhost:28210") when attempting to connect over gRPC channel with worker: Connecting (unchanged)
10:10:10.186 INFO canton-env-ec-43 span=- :: State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection: Disconnected (not in state) -> Connecting
10:10:10.187 INFO canton-env-ec-40 span=BFTOrderer.Availability.ProposeBlock :: P2P endpoint P2PUrl("http://localhost:28210") is now connected
10:10:10.189 INFO canton-env-ec-67 span=- :: Shutting down asynchronously any incoming or outgoing connection to Left(P2PUrl("http://localhost:28210"))
10:10:10.189 INFO canton-env-ec-67 span=- :: State transition when potentially shutting down outgoing connection for P2PUrl("http://localhost:28210") (onlyIfNotFullyConnected: false): Connecting -> Disconnected (not in state)
10:10:10.190 DEBUG canton-env-ec-43 span=- :: State transition for P2PUrl("http://localhost:28210") when attempting to start outgoing connection after creating a channel: Disconnected (not in state, unchanged)
10:10:10.205 INFO canton-env-ec-43 span=BFTOrderer.Availability.ProposeBlock :: Received error (UNAVAILABLE: Channel shutdown invoked) from 'P2PUrl("http://localhost:28210")', invalidating connection and shutting down the gRPC channel
10:10:10.205 DEBUG canton-env-ec-43 span=BFTOrderer.Availability.ProposeBlock :: State transition when potentially shutting down outgoing connection for P2PUrl("http://localhost:28210") (onlyIfNotFullyConnected: false): Disconnected (n
```

Reading:

1. `.180-.181` sv2 closed its side of the mesh (section 8); sv4's incoming-stream handler
   (`dp2p-server-exec-479`) removes the PeerSender for SEQ::sv2 and sends `Close` to the connection-managing
   actor for 28210.
2. `.181-.182` ec-67 (availability, `ProposeBlock`) wants to send to sv2, finds no sender, starts reconnect #1:
   Disconnected -> Connecting -> ConnectingOnChannel(ch#262), and schedules a connect worker.
3. `.182-.184` ec-43 (`P2PNetworkOutModule`): "endpoint is now disconnected" ... "is still configured on this node,
   ensuring an outgoing connection" -> spawns a NEW connection-managing actor for 28210 (reconnect #2).
4. `.184-.185` ec-67 processes the `Close` from step 1: "Shutting down asynchronously any ... connection to
   28210", which transitions ConnectingOnChannel(ch#262) -> Disconnected (not in state) and shuts ch#262 down.
5. `.185-.186` ec-40, the connect worker for ch#262 from step 2, now runs and tries to record itself
   ("with worker"). Meanwhile ec-43's reconnect #2 has just set Disconnected -> Connecting (.186). The worker
   finds `Connecting` (a status that belongs to another attempt, with no channel) and Canton logs it as
   `[UNEXPECTED] ... Connecting (unchanged)` at WARN. The worker even reports "is now connected" at .187 on the
   already-closed channel and gets `UNAVAILABLE: Channel shutdown invoked` at .205.
6. `.189-.190` the actor stop from step 4 fires a second shutdown (Connecting -> Disconnected); reconnect #2
   finds the state gone and gives up; its retry fails at 10:10:15.188 when sv4's own P2P layer closes.

Only two reconnect attempts were made against 28210 during the whole 5.3 s shutdown, both in this window
(`Ensuring connection to P2PUrl("http://localhost:28210")` = 2, `Creating a gRPC channel to ...28210` = 2).

## 11. Why Canton tags exactly this transition as UNEXPECTED (runtime 3.6.0-snapshot.20260910, javap)

```
cd cls; P=com/digitalasset/canton/synchronizer/sequencer/block/bftordering
javap -p -c -l "$P/bindings/p2p/grpc/P2PGrpcConnectionManager\$State.class" > State.javap
S=State.javap; start=$(grep -n 'attemptTransitionToConnectingWithChannelAndWorker(' $S | head -1 | cut -d: -f1); end=$(grep -n 'attemptTransitionToRetryConnecting(' $S | head -1 | cut -d: -f1)
sed -n "${start},${end}p" $S | grep -oE 'instanceof .*P2POutgoingConnectionStatus\$[A-Za-z]+' | sed -E 's/instanceof +#[0-9]+ +\/\/ class .*P2POutgoingConnectionStatus/P2POutgoingConnectionStatus/' | uniq
sed -n "${start},${end}p" $S | grep -oE 'line [0-9]+' | sort -k2 -n | uniq | sed -n '1p;$p'
javap -p -c -l "$P/bindings/p2p/grpc/P2PGrpcConnectionManager.class" | grep -A14 'anonfun\$connect\$3(' | grep -m1 -E 'line [0-9]+: 0'
javap -v "$P/utils/Miscellaneous\$ResultWithLogs.class" | grep -E 'Utf8.*(UNEXPECTED|FATAL)' | cut -c1-80
```
```
P2POutgoingConnectionStatus$ConnectingOnChannel
P2POutgoingConnectionStatus$DisconnectingFromChannel
P2POutgoingConnectionStatus$ConnectedOnChannel
line 1606
line 1653
        line 401: 0
  #219 = Utf8               [FATAL] 
  #249 = Utf8               [UNEXPECTED] 
```

In the jar that ran, `State.attemptTransitionToConnectingWithChannelAndWorker` is
P2PGrpcConnectionManager.scala:1606-1653 (called from `connect` via `$anonfun$connect$3` at line 401): it
pattern-matches only `ConnectingOnChannel`, `DisconnectingFromChannel` and `ConnectedOnChannel` explicitly and
accepts only `ConnectingOnChannel(ch, cwO=None)` for the SAME channel; every other status (including the bare
`Connecting` seen here) is returned unchanged with a WARN-level log entry, and `Miscellaneous.ResultWithLogs`
prefixes every WARN entry with `[UNEXPECTED]` (and ERROR with `[FATAL]`). The vendored source (canton/VERSION
3.5.7-SNAPSHOT, `canton/community/synchronizer/.../p2p/grpc/P2PGrpcConnectionManager.scala:1281`, same method
shape, cited for readability only) makes the intent explicit:

```
cd <splice>; F=canton/community/synchronizer/src/main/scala/com/digitalasset/canton/synchronizer/sequencer/block/bftordering/bindings/p2p/grpc/P2PGrpcConnectionManager.scala
n=$(grep -n 'def attemptTransitionToConnectingWithChannelAndWorker' $F | cut -d: -f1); sed -n "$((n+12)),$((n+36))p" $F | grep -vE '^\s*$'
```
```
                case oldState @ P2POutgoingConnectionStatus.ConnectingOnChannel(ch, acO, cwO) =>
                  if (ch == channel && cwO.isEmpty) {
                    // Record the connect worker {
                    val newState =
                      P2POutgoingConnectionStatus.ConnectingOnChannel(
                        channel,
                        acO,
                        Some(connectWorker),
                      )
                    State(
                      UnlessShutdown.Outcome(p2pConnectionsStatus.updated(p2pEndpointId, newState))
                    ) -> ResultWithLogs((), Level.INFO -> (() => s"$oldState -> $newState"))
                  } else {
                    // A just-created channel could have been disconnected immediately due to connectivity issues,
                    //  and a new connection attempt with a new worker could already be running when we
                    //  try to progress the old one.
                    this -> ResultWithLogs((), Level.DEBUG -> (() => s"$oldState (unchanged)"))
                  }
                case oldState: P2POutgoingConnectionStatus.DisconnectingFromChannel =>
                  this -> ResultWithLogs((), Level.WARN -> (() => s"$oldState (unchanged)"))
                case oldState @ P2POutgoingConnectionStatus.Connecting =>
                  this -> ResultWithLogs((), Level.WARN -> (() => s"$oldState (unchanged)"))
                case oldState: P2POutgoingConnectionStatus.ConnectedOnChannel =>
                  this -> ResultWithLogs((), Level.WARN -> (() => s"$oldState (unchanged)"))
```

Canton already anticipates the "my channel was torn down and a newer attempt owns a DIFFERENT channel" race
and logs it at DEBUG, but only when the newer attempt has already reached `ConnectingOnChannel`. If the newer
attempt is still in the channel-less `Connecting` state (a window of ~4 ms here: ec-43 set Connecting at .186
and only tried to attach a channel at .190), the same race hits the `Connecting` branch and is WARN. That
branch treats `Connecting` as impossible for a worker to observe, which is only true if connection start and
shutdown are serialized; they are not (three executor threads above).

## 12. No ignore pattern covers it, and none ever did

```
cd <splice>; grep -rnE 'State transition|P2PGrpcConnectionManager|UNEXPECTED|unchanged' project/ignore-patterns/*.ignore.txt; echo "(no matches)"
grep -vE '^\s*(#|$)' project/ignore-patterns/canton_log_bft.ignore.txt
grep -nE 'P2P|p2p' project/ignore-patterns/canton_log.ignore.txt
git log --oneline -S 'State transition' -- project/ignore-patterns; git log --oneline -S 'P2PGrpcConnectionManager' -- project/ignore-patterns; echo "(both empty)"
git log --format='%h %ad %s' --date=short -8 -- project/ignore-patterns/canton_log_shutdown_extra.ignore.txt
git log --format='%h %ad %s' --date=short -3 -- project/ignore-patterns/canton_log_bft.ignore.txt
```
```
(no matches)
discarding expired batches
Waiting for new membership after epoch completion for.*seconds without receiving it from the output module
129:# TODO(#541) Remove once CantonBFT does no longer raise this spurious warning
130:failed to ping endpoint PlainTextP2PEndpoint.*UNAVAILABLE
192:P2P connectivity is not ready
(both empty)
51db5894b2 2026-01-12 enable new sequencer connection pools in ci tests (#3494)
723e6bc84c 2026-01-07 Upgrade canton to 3.4.10-snapshot.20260107.17477.0.vcbb731d2 (#3474)
77e7246b4a 2026-01-06 Enable the new sequencer connection pools in all the tests (#3409)
d6f8e9177f 2025-12-15 support non-newline-terminated ignore-list files (#3404)
ce504eda5f 2025-10-15 More log ignores
8fb7f06d32 2025-10-15 Extra post shutdown log ignore
0fc9f9c5dd 2024-11-06 Update Splice from CCI (#96)
3052b21184 2024-10-18 Update Splice from CCI (#70)
a7adcc5c0d 2026-08-21 Upgrade Canton to 3.6.0-snapshot.20260818.20026.0.v41046c3b (#6859)
0b01f7ff3c 2026-07-23 Log ignore bft warnings (#6531)
f0270a0a32 2026-03-18 lsu - dabft support for serials for p2p connections (#4390)
```

The shutdown-extra list is about ledger API / indexer / DB teardown noise and has not changed since January;
the BFT list has two entries; the only P2P ignores are the ping WARN (#541) and the dissemination-quorum
overload. Nothing in the repo history has ever dealt with `P2PGrpcConnectionManager` state-transition WARNs.

## 13. The harness WARN bursts are expected and already allowlisted

```
zcat canton_network_test.clog.gz | jq -r 'select((.level=="WARN" or .level=="ERROR") and .["@timestamp"] >= "2026-09-16T10:10:09" and .["@timestamp"] <= "2026-09-16T10:10:25") | "\(.["@timestamp"] | .[11:19]) \(.logger_name | sub("^o\\.l\\.s\\.";"") | sub("\\$.*";"")) :: \(.message | .[0:60])"' \
  | awk '{k=$2" "substr($0,index($0,"::"),60); c[k]++; if(!(k in f)) f[k]=$1; l[k]=$1} END{for(k in c) printf "%3d x %s..%s %s\n", c[k], f[k], l[k], k}' | sort -k3
zcat canton_network_test.clog.gz | grep -a 'RollForwardLsuTrigger' | grep -a 'consecutive transient failures' | jq -c 'select(.["@timestamp"] >= "2026-09-16T10:10:12") | {ts: .["@timestamp"], exc: ((.stack_trace // .exception // "") | .[0:130])}' | head -1
cd <splice>; grep -n 'consecutive transient failures' project/ignore-patterns/canton_network_test_log.ignore.txt; grep -n '5012' apps/app/src/test/resources/include/svs/sv1.conf | head -1
```
```
  3 x 10:10:12..10:10:20 v.a.ReconcileSequencerConnectionsTrigger:RollForwardLsuDRIntegrationTest/config=49198937/validator=aliceValidator :: Encountered 4 consecutive transient failures (polling int
  3 x 10:10:12..10:10:20 v.a.ValidatorPackageVettingTrigger:RollForwardLsuDRIntegrationTest/config=49198937/validator=aliceValidator :: Encountered 4 consecutive transient failures (polling int
  3 x 10:10:12..10:10:20 v.l.RollForwardLsuTrigger:RollForwardLsuDRIntegrationTest/config=49198937/validator=aliceValidator :: Encountered 4 consecutive transient failures (polling int
  3 x 10:10:13..10:10:21 v.a.TopupMemberTrafficTrigger:RollForwardLsuDRIntegrationTest/config=49198937/validator=aliceValidator :: Encountered 4 consecutive transient failures (polling int
{"ts":"2026-09-16T10:10:12.047Z","exc":"org.apache.pekko.stream.StreamTcpException: Tcp command [Connect(localhost/<unresolved>:5012,None,List(),Some(10 seconds),true)] failed because of java.net.ConnectException"}
80:Encountered 4 consecutive transient failures \(polling interval .*
51:    public-url = "http://localhost:5012"
```

All 12 WARNs come from aliceValidator's triggers failing to reach sv1's scan (port 5012) after the test stopped
all scan apps at 10:10:09.7-09.9 and disconnected alice's participant; aliceValidator itself is left running
until the environment closes. They are covered by canton_network_test_log.ignore.txt:80 and did not fail the
job (the harness log check never ran anyway, section 2).

## 14. Not a regression; no earlier occurrence found

```
cd <splice>; git log --format='%h %ad %s' --date=short -4 743a6ec124 -- apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/RollForwardLsuDRIntegrationTest.scala
git log --format='%h %ad %s' --date=short -2 743a6ec124 -- nix/canton-sources.json
grep -lE 'State transition|P2PGrpcConnectionManager|UNEXPECTED' ci-triage/*.md; echo "(no earlier packet)"
for id in $(gh run list --repo canton-network/splice --branch main --status failure --limit 15 --json databaseId --jq '.[].databaseId'); do
  gh run view $id --repo canton-network/splice --json jobs --jq ".jobs[] | select(.conclusion==\"failure\") | \"$id  \(.name)\""; done | grep -iE 'roll-forward|lsu'; echo "(none of the last 15 main failures had a roll-forward-lsu job fail)"
zcat canton_after_shutdown.clog.gz | wc -c
```
```
54a40fb710 2026-09-10 Set synchronizer limits in static sync parameters (#7234)
eed7b5be9d 2026-07-15 Add 30s to maxSequencerTime in LSU DR Test (#6424)
d68f9e7638 2026-06-29 Default to PV 35 (#6144)
6744eff97b 2026-06-18 Add 30s extra for canton to start for the lsu roll forward test (#6044)
d6e5120026 2026-09-11 Upgrade Canton to 3.6.0-snapshot.20260910.20260.0.v90621933 (#7264)
344612f556 2026-09-10 Upgrade Canton to 3.6.0-snapshot.20260909.20251.0.v0a9e6e25 (#7219)
(no earlier packet)
(none of the last 15 main failures had a roll-forward-lsu job fail)
0
```

The test and the Canton pin are both 5-6 days old and the roll-forward-lsu shard has been green on main since;
743a6ec124 itself touches WAF alert thresholds only. The BFT standalone in this test is short-lived (mesh up
for under 16 s, section 7) and is torn down by SIGTERM while sv4 is still actively proposing blocks, which is
what makes the shutdown race reachable here and not in the main canton (whose sequencers are idle at shutdown;
its `canton_after_shutdown.clog` is empty).

## Root cause / hypothesis

Proven (from logs and the runtime jar):
- The single flagged line is sv4StandaloneSequencer's `[UNEXPECTED] State transition for
  P2PUrl("http://localhost:28210") when attempting to connect over gRPC channel with worker: Connecting
  (unchanged)` at 10:10:10.186, 211 ms after the test destroyed the `roll-forward-lsu-dr` standalone canton
  (SIGTERM via `process.destroy()`), in the shutdown half of the log.
- 28210 is sv2StandaloneSequencer's BFT P2P port; sv2's orderer and P2P manager closed first (10:10:10.170/.174),
  sv4's orderer only started closing at .241 and its P2P layer at 15.189, so sv4 was live and reacting.
- Three sv4 threads raced on the 28210 connection entry within 6 ms: a `ProposeBlock`-driven reconnect (ch#262),
  the `P2PNetworkOutModule` "still configured, ensuring an outgoing connection" reconnect, and the shutdown of
  the connection triggered by sv2's `Close`. The connect worker for ch#262 ran after its state entry had been
  removed and re-created as `Connecting` by the second reconnect, and Canton's state machine logs that branch
  at WARN with the `[UNEXPECTED]` prefix (runtime P2PGrpcConnectionManager.scala:1606-1653 via `connect` :401;
  `Miscellaneous.ResultWithLogs`).
- All tests passed; no functional impact (the process was being destroyed; the stray worker got
  `UNAVAILABLE: Channel shutdown invoked` 19 ms later and everything closed cleanly by 10:10:15.282).
- No ignore pattern in `canton_log`, `canton_log_bft` or `canton_log_shutdown_extra` matches; there is no
  `canton-standalone-roll-forward-lsu-dr.ignore.txt`.

Inferred:
- This is a Canton BFT P2P state-machine log-level issue, not a splice test-ordering bug: Canton already
  downgrades the "my channel was superseded by a newer attempt" race to DEBUG when the newer attempt has a
  channel (`ConnectingOnChannel` with a different `ch`), but the same race in the few-ms window where the newer
  attempt is still channel-less (`Connecting`) is WARN. Nothing splice controls (node close order inside one
  Canton JVM, the auto-reconnect of `P2PNetworkOutModule`) can prevent a peer disconnect from overlapping a
  reconnect during SIGTERM.
- Recurrence is low but structural: it needs a multi-sequencer BFT JVM to be killed while it is proposing
  blocks. In splice CI that is exactly the two roll-forward-LSU standalone cantons with `enableBftSequencer =
  true` and any other `withCantonSvNodes(enableBftSequencer = true)` test.

## Duplicates / related

- No earlier ci-triage packet or cn-test-failures ref for `P2PGrpcConnectionManager` / `State transition` /
  `Connecting (unchanged)`; `git log -S` over project/ignore-patterns is empty for both strings.
- 10088-lsu-failures.md: same job family (roll-forward / LSU) but real failures (InvalidStaticSynchronizerParameters
  on canton 3.6; bob's init modifying the old psid). Different mechanism, no overlap beyond the shard.
- 10048-bft-deadlock.md: same Canton component (BFT orderer, canton 3.5.16) but a real ordering halt at epoch
  start with a not-yet-initialized leader, during the run. Here the P2P layer is behaving correctly and only
  the log level is wrong, at shutdown.
- Same failure mode (all tests pass, one un-allowlisted Canton WARN fails checkErrors): 10140 (SERVER_OVERLOADED),
  10094 (ack stall), 10084 (indexer reconnect WARN), 10121.
- Related precedent for "Canton BFT P2P emits a spurious WARN, splice allowlists it": canton_log.ignore.txt:129-130
  (`failed to ping endpoint PlainTextP2PEndpoint.*UNAVAILABLE`, TODO(#541)).

## Suggested next step / owner

1. Splice (small, unblocks CI): add a shutdown-scoped ignore to
   `project/ignore-patterns/canton_log_shutdown_extra.ignore.txt`, so it only applies to the `_after_shutdown`
   halves and never hides the same line during a run:
   `\[UNEXPECTED\] State transition for P2PUrl\(.*\) when attempting to connect over gRPC channel with worker: Connecting \(unchanged\)`
   Alternative with narrower blast radius: create `project/ignore-patterns/canton-standalone-roll-forward-lsu-dr.ignore.txt`
   with the same line (build.sbt picks it up automatically for that instance, before and after shutdown).
2. Canton (real fix): in `P2PGrpcConnectionManager.State.attemptTransitionToConnectingWithChannelAndWorker`
   (3.6.0-snapshot.20260910.20260.0.v90621933, P2PGrpcConnectionManager.scala:1606-1653), treat `Connecting` the
   same way as `ConnectingOnChannel` with a different channel: a superseded worker is a benign race, log at
   DEBUG/INFO (or suppress WARN while the manager is closing). File with the section 10 timeline; the
   `P2PNetworkOutModule` "is still configured on this node, ensuring an outgoing connection" path is the
   second writer.

Owner: splice CI / test infra for (1); Canton BFT (sequencer) team for (2).

## Summary

roll-forward-lsu(0), job 104749000327, canton 3.6.0-snapshot.20260910.20260.0.v90621933. The single test
passed; the job fails because checkErrors flags one WARN in the shutdown half of the BFT standalone canton
log: sv4StandaloneSequencer `[UNEXPECTED] State transition for P2PUrl("http://localhost:28210") ... Connecting
(unchanged)`, 211 ms after the test SIGTERMed the 4-sequencer JVM. sv2 (port 28210) closed first; sv4, still
proposing blocks, reconnected to it twice within 5 ms while sv2's Close tore the connection down, and the
connect worker of the first attempt found the state entry already replaced by the second. Canton logs that
branch at WARN although it already treats the sibling variant as a benign DEBUG race. Shutdown-time timing
flake, not a regression; allowlist it in canton_log_shutdown_extra (or a roll-forward-lsu-dr specific file)
and report the log level upstream.
