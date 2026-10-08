# 10303 - sv4 BFT availability drops sv2's batch-fetch replies while sv4 is blacklisted after a staggered 1 -> 3 -> 4 onboarding (run 37646424017)

New signature in family B (BFT sequencer onboarding, blacklisting). main 8b37552a4e, job `wall-clock-time (2)`,
canton 3.6.1. All 23 tests pass; checkErrors flags 8 WARNs `AvailabilityModule:sequencer=globalSequencerSv4 Received a
message from 'SEQ::sv2' ... cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3),
dropping it` (16:39:54.629 - 16:40:30.794), during ValidatorSequencerConnectionIntegrationTest's 4-SV initDso. sv3/sv4
joined the ordering topology at epoch 76 (size 3) and sv2 at epoch 78 (size 4). sv4 was blacklisted in epochs 78-82.
Canton 3.6.1's AvailabilityModule refreshes its active membership only on consensus `CreateProposal` (or during state
transfer), and a blacklisted node never gets that message. So sv4 kept verifying availability messages against the
size-3 topology without sv2 until it led again in epoch 83 (16:40:36.588). In the same window sv4's output fetch used the
epoch's size-4 topology and asked sv2 for missing batches. sv2's `RemoteBatchDataFetched` replies were dropped with a
WARN, and sv3's copy of the same batch was accepted 1 ms later. No functional impact. Canton-side noise.

- Run: https://github.com/canton-network/splice/actions/runs/37646424017, main 8b37552a4e ("Add a defensive LIMIT to scan's listFeaturedAppRightsByProvider (#7671)"), job 112880313175 `ci / scala_test_wall_clock_time / wall-clock-time (2)`.
- Runtime canton: 3.6.1 (`git show 8b37552a4e:nix/canton-sources.json`, same pin as origin/main on 2026-10-08).
- Component: Canton BFT ordering, `AvailabilityModule` (sequencer). Not splice, not the test.
- Artifacts: `log/10303/job.log` (job log), `log/10303/logs-wall-clock-time-2/` (artifact `logs-wall-clock-time-2`).
- Commands run from the repo root. Section 8 needs the 3.6.1 jar under `log/canton-jars/` (recipes section 6) and `TMPDIR` set to a writable directory.

## 1. Flagged lines

The only problems are 8 WARNs from globalSequencerSv4's AvailabilityModule. The rest of the 734 lines are ignored entries.

```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10303/job.log | grep -a -E 'Tests: succeeded|All tests passed|contains problems\.$|^Total: ' | sort -u
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/10303/job.log | awk '/^Found problems in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | grep -v 'ignore this line' | sed -E 's/1220[0-9a-f]{60}/../g; s/(dissemination topology) Map\(.*\), dropping it/\1 Map(sv1, sv4, sv3), dropping it/; s/"thread_name".*"trace-id"/"trace-id"/' | cut -c1-420
```
```
Total: 734 lines with ignored entries.
Total: 8 lines with problems.
[error] (checkErrors) log/canton_before_shutdown.clog contains problems.
[error] java.lang.RuntimeException: log/canton_before_shutdown.clog contains problems.
[info] All tests passed.
[info] Tests: succeeded 23, failed 0, canceled 0, ignored 0, pending 0
Found problems in log/canton_before_shutdown.clog:
***"@timestamp":"2026-10-07T16:39:54.629Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"10f6ae758d07f44ee17ab67f7a58a221","span-id":"76db40e55424a166"
***"@timestamp":"2026-10-07T16:40:00.384Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"8a6fceb0e56a67f5814326cddbee285e","span-id":"25c8cf3db2a62e5d"
***"@timestamp":"2026-10-07T16:40:04.845Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"f4dd3776c73de893a94e1d907ff4e457","span-id":"70c632b1a31c9a7e"
***"@timestamp":"2026-10-07T16:40:10.514Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"7ced82fa362ff46bf06a2ecac728a243","span-id":"39a096d9a2c4888b"
***"@timestamp":"2026-10-07T16:40:14.987Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"f4dd3776c73de893a94e1d907ff4e457","span-id":"877ecd0233afbb8d"
***"@timestamp":"2026-10-07T16:40:20.593Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"7ced82fa362ff46bf06a2ecac728a243","span-id":"60c56122934a3ec3"
***"@timestamp":"2026-10-07T16:40:25.225Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"f4dd3776c73de893a94e1d907ff4e457","span-id":"96c4da74028ae792"
***"@timestamp":"2026-10-07T16:40:30.794Z","message":"Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it","logger_name":"c.d.c.s.s.b.b.c.m.a.AvailabilityModule:sequencer=globalSequencerSv4/psid=global-domain::12201de8c5f4::36-0","trace-id":"7ced82fa362ff46bf06a2ecac728a243","span-id":"41a9b9603a42013a"
Total: 8 lines with problems.
```

## 2. Which test was running

The WARN window, 16:39:48-16:40:35 (section 3), lies inside ValidatorSequencerConnectionIntegrationTest (16:37:46.880 -
16:41:20.470). The suite is `IntegrationTestWithIsolatedEnvironment` with `simpleTopology4Svs`, so `startAllSync` of
sv1-sv4 onboards sv2-sv4's sequencers.

```
cd log/10303/logs-wall-clock-time-2
zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a 'T16:(3[6-9]|4[0-2])' -E | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-200
```
```
2026-10-07T16:36:03.143Z Test succeeded: 'ExternalPartySetupProposalIntegrationTest/TransferPreapproval allows to transfer between externally signed parties'",
2026-10-07T16:36:03.144Z Starting 'ExternalPartySetupProposalIntegrationTest/TransferPreapprovals get renewed by validator automation'...",
2026-10-07T16:36:50.991Z Test succeeded: 'ExternalPartySetupProposalIntegrationTest/TransferPreapprovals get renewed by validator automation'",
2026-10-07T16:36:50.992Z Starting 'ExternalPartySetupProposalIntegrationTest/TransferPreapprovals get expired by SV automation'...",
2026-10-07T16:37:46.755Z Test succeeded: 'ExternalPartySetupProposalIntegrationTest/TransferPreapprovals get expired by SV automation'",
2026-10-07T16:37:46.880Z Starting test suite 'ValidatorSequencerConnectionIntegrationTest'...",
2026-10-07T16:37:46.885Z Starting 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers'...",
2026-10-07T16:41:20.470Z Test succeeded: 'ValidatorSequencerConnectionIntegrationTest/validator with 'svNames' set in config connects to specified sequencers and tracks URL changes of sequencers'",
2026-10-07T16:41:20.488Z Starting test suite 'BftScanConnectionIntegrationTest'...",
2026-10-07T16:41:20.489Z Starting 'BftScanConnectionIntegrationTest/init fast enough even if there are unavailable scans'...",
2026-10-07T16:42:05.515Z Test succeeded: 'BftScanConnectionIntegrationTest/init fast enough even if there are unavailable scans'",
2026-10-07T16:42:05.515Z Starting 'BftScanConnectionIntegrationTest/agree on failed HttpCommandException'...",
2026-10-07T16:42:50.730Z Test succeeded: 'BftScanConnectionIntegrationTest/agree on failed HttpCommandException'",
2026-10-07T16:42:50.730Z Starting 'BftScanConnectionIntegrationTest/validator onboarding and recovery succeed with internal config turned on'...",
```

## 3. All occurrences, by level

58 lines, all on globalSequencerSv4 and all about SEQ::sv2. There are 50 INFO and 8 WARN lines between 16:39:48 and
16:40:35. Section 8 shows which message types are logged at which level.

```
cd log/10303/logs-wall-clock-time-2
zcat canton_before_shutdown.clog.gz | grep -a 'cannot be verified in the currently known dissemination topology' | sed -E 's/\{"@timestamp":"([^"]+)","message":"Received a message from .(SEQ::[a-z0-9]+)::.*"logger_name":"[^"]*sequencer=([A-Za-z0-9]+)\/.*"level":"([A-Z]+)".*/\1 \4 from=\2 on=\3/' | awk '{print substr($1,12,8), $2, $3, $4}' | uniq -c
```
```
     17 16:39:48 INFO from=SEQ::sv2 on=globalSequencerSv4
     13 16:39:49 INFO from=SEQ::sv2 on=globalSequencerSv4
     10 16:39:50 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:39:52 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:39:54 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:39:54 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:00 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:00 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:04 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:04 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:10 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:10 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:14 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:14 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:20 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:20 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:25 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:25 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:30 INFO from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:30 WARN from=SEQ::sv2 on=globalSequencerSv4
      1 16:40:35 INFO from=SEQ::sv2 on=globalSequencerSv4
```

## 4. Staggered onboarding: sv3/sv4 at 16:39:08 (size 3), sv2 at 16:39:29 (size 4)

sv1 is the only sequencer until 16:39:08. sv3 and sv4 complete their state transfer into epoch 76 with the size-3 group,
and sv2 joins with the 16:39:29.280807 group (threshold 2). sv2 completes its state transfer into epoch 78 at
16:39:49.793. This is when the INFO drops start.

```
cd log/10303/logs-wall-clock-time-2
zcat canton_before_shutdown.clog.gz | grep -a -E 'T16:39:(0[5-9]|[1-4][0-9])' | grep -a -E 'Sequencer group queried successfully on snapshot|Completed state transfer, new epoch' | grep -a -E 'Sv[1-4]/' | sed -E 's/"logger_name":"[^"]*sequencer=([A-Za-z0-9]+)\/.*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/::1220[0-9a-f]{8}\.\.\.//g' | awk '!seen[substr($0,25)]++' | cut -c1-230
```
```
2026-10-07T16:39:08.502Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.355220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv1]
2026-10-07T16:39:13.301Z Sequencer group queried successfully on snapshot at 2026-10-07T16:30:04.135946Z: Some(SequencerGroup(List(SEQ::sv1),List(),1))", [globalSequencerSv3]
2026-10-07T16:39:13.313Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:07.304732Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv3]
2026-10-07T16:39:15.605Z Sequencer group queried successfully on snapshot at 2026-10-07T16:30:04.135946Z: Some(SequencerGroup(List(SEQ::sv1),List(),1))", [globalSequencerSv4]
2026-10-07T16:39:15.615Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:05.363721Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4),List(),1))", [globalSequencerSv4]
2026-10-07T16:39:18.289Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.355220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv3]
2026-10-07T16:39:18.324Z Completed state transfer, new epoch is 76, completing init", [globalSequencerSv3]
2026-10-07T16:39:20.172Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.355220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv4]
2026-10-07T16:39:20.194Z Completed state transfer, new epoch is 76, completing init", [globalSequencerSv4]
2026-10-07T16:39:22.578Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.475220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv1]
2026-10-07T16:39:22.582Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.475220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv4]
2026-10-07T16:39:22.591Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.475220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv3]
2026-10-07T16:39:38.330Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:29.280807Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3, SEQ::sv2),List(),2))", [globalSequencerSv4]
2026-10-07T16:39:38.373Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:29.280807Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3, SEQ::sv2),List(),2))", [globalSequencerSv1]
2026-10-07T16:39:38.385Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:29.280807Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3, SEQ::sv2),List(),2))", [globalSequencerSv3]
2026-10-07T16:39:44.413Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.475220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv2]
2026-10-07T16:39:44.526Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:08.355220Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3),List(),1))", [globalSequencerSv2]
2026-10-07T16:39:44.584Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:21.958195Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3, SEQ::sv2),List(),2))", [globalSequencerSv2]
2026-10-07T16:39:49.752Z Sequencer group queried successfully on snapshot at 2026-10-07T16:39:29.280807Z: Some(SequencerGroup(List(SEQ::sv1, SEQ::sv4, SEQ::sv3, SEQ::sv2),List(),2))", [globalSequencerSv2]
2026-10-07T16:39:49.793Z Completed state transfer, new epoch is 78, completing init", [globalSequencerSv2]
```

## 5. sv4 is blacklisted for epochs 78-82, exactly the WARN window

sv4's consensus switches to the size-4 topology at epoch 78 (16:39:38.349), but sv4 stays blacklisted until epoch 83
(16:40:36.584). The last drop is at 16:40:35.

```
cd log/10303/logs-wall-clock-time-2
zcat canton_before_shutdown.clog.gz | grep -a -E 'T16:(39|40)' | grep -a 'IssConsensusModule:sequencer=globalSequencerSv4' | grep -a -E 'New epoch [0-9]+ has started' | sed -E 's/\{"@timestamp":"([^"]+)","message":"New epoch ([0-9]+) has started with leaders = (List\([^)]*\)).?and blacklisted nodes = (List\([^)]*\)); ordering topology = OrderingTopology\(\\n *activationTime = ([^,]*),\\n *size = ([0-9]+).*/\1 epoch=\2 leaders=\3 blacklisted=\4 topoAt=\5 size=\6/; s/1220[0-9a-f]{60}/../g; s/::\.\.[0-9a-f]{4}//g' | head -8
```
```
2026-10-07T16:39:20.199Z epoch=76 leaders=List(SEQ::sv3, SEQ::sv4, SEQ::sv1) blacklisted=List() topoAt=2026-10-07T16:39:08.355220Z size=3
2026-10-07T16:39:22.609Z epoch=77 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv3) blacklisted=List() topoAt=2026-10-07T16:39:08.475220Z size=3
2026-10-07T16:39:38.349Z epoch=78 leaders=List(SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List(SEQ::sv4) topoAt=2026-10-07T16:39:29.280807Z size=4
2026-10-07T16:39:53.629Z epoch=79 leaders=List(SEQ::sv2, SEQ::sv3, SEQ::sv1) blacklisted=List(SEQ::sv4) topoAt=2026-10-07T16:39:29.280807Z size=4
2026-10-07T16:40:04.314Z epoch=80 leaders=List(SEQ::sv3, SEQ::sv1, SEQ::sv2) blacklisted=List(SEQ::sv4) topoAt=2026-10-07T16:39:29.280807Z size=4
2026-10-07T16:40:14.963Z epoch=81 leaders=List(SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List(SEQ::sv4) topoAt=2026-10-07T16:39:29.280807Z size=4
2026-10-07T16:40:25.882Z epoch=82 leaders=List(SEQ::sv2, SEQ::sv3, SEQ::sv1) blacklisted=List(SEQ::sv4) topoAt=2026-10-07T16:39:29.280807Z size=4
2026-10-07T16:40:36.584Z epoch=83 leaders=List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3) blacklisted=List() topoAt=2026-10-07T16:39:29.280807Z size=4
```

The blacklisting follows epoch 77. All three segments (2710 sv4, 2711 sv1, 2712 sv3) hit the local view-change timeout
at 16:39:34, 12 s into the epoch. This report does not establish why only sv4 was blacklisted.

```
cd log/10303/logs-wall-clock-time-2
zcat canton_before_shutdown.clog.gz | grep -a -E 'T16:39:3[3-5]' | grep -a -E 'moving from view number 0 to 1 after reaching local timeout' | sed -E 's/"logger_name":"[^"]*sequencer=([A-Za-z0-9]+)\/.*/ [\1]/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/::\.\.[0-9a-f]{4}//g'
```
```
2026-10-07T16:39:34.290Z Segment 2711 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.", [globalSequencerSv1]
2026-10-07T16:39:34.293Z Segment 2711 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.", [globalSequencerSv4]
2026-10-07T16:39:34.297Z Segment 2711 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.", [globalSequencerSv3]
2026-10-07T16:39:34.317Z Segment 2710 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.", [globalSequencerSv3]
2026-10-07T16:39:34.320Z Segment 2710 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.", [globalSequencerSv1]
2026-10-07T16:39:34.332Z Segment 2710 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.", [globalSequencerSv4]
2026-10-07T16:39:34.397Z Segment 2712 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv4.", [globalSequencerSv3]
2026-10-07T16:39:34.413Z Segment 2712 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv4.", [globalSequencerSv4]
2026-10-07T16:39:34.430Z Segment 2712 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv4.", [globalSequencerSv1]
```

## 6. sv4's availability membership stays at the size-3 topology until it is a leader again

The AvailabilityModule's active membership changes only at these points: once during state transfer (16:39:20.183), and
then on `Availability.Consensus.CreateProposal`, which consensus sends only to a node that leads a segment. Between
16:39:22.611 and 16:40:36.588 it held the 16:39:08.475220 size-3 topology (sv1, sv4, sv3), which is the `Map(...)` in
every flagged line. The 16:40:36.588 update is 4 ms after epoch 83 starts with sv4 as leader again, and no drops follow.

```
cd log/10303/logs-wall-clock-time-2
zcat canton_before_shutdown.clog.gz | grep -a 'AvailabilityModule:sequencer=globalSequencerSv4' | grep -a -E 'updating active ordering topology to' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^:]*):.*activationTime = ([^,]*),\\n *size = ([0-9]+).*/\1 \2 topoAt=\3 size=\4/' | head -4
```
```
2026-10-07T16:39:20.183Z Availability.Consensus.UpdateTopologyDuringStateTransfer topoAt=2026-10-07T16:39:08.355220Z size=3
2026-10-07T16:39:22.611Z Availability.Consensus.CreateProposal topoAt=2026-10-07T16:39:08.475220Z size=3
2026-10-07T16:40:36.588Z Availability.Consensus.CreateProposal topoAt=2026-10-07T16:39:29.280807Z size=4
2026-10-07T16:41:26.282Z Availability.Consensus.CreateProposal topoAt=2026-10-07T16:41:25.993496Z size=4
```

## 7. One WARN end to end: sv4 asks sv2 for a batch and then drops sv2's reply

16:40:00: sv2 disseminates batch 0a66, and sv4 drops it at INFO. sv2 still reaches the PoA quorum with sv1 and sv3, and
orders block 3092 in epoch 79. sv4's output path then fetches the missing batch from the PoA signers sv2 and sv3, because
it uses the epoch's size-4 topology. Both answer. sv2's `RemoteBatchDataFetched` is dropped with the flagged WARN at
16:40:00.384, and sv3's copy is validated and stored at 16:40:00.385. The block is output at once.

```
cd log/10303/logs-wall-clock-time-2
zcat canton_before_shutdown.clog.gz | grep -a -E 'T16:40:00\.3[4-9]' | grep -a 'globalSequencerSv4' | grep -a -E 'dissemination topology|0a66|FetchBatchesSingleWorkflowId \(not' | grep -a -v 'Asked to send' | sed -E 's/"logger_name":"[^"]*\.([A-Za-z]+):sequencer=([A-Za-z0-9]+)\/.*"level":"([A-Z]+)".*/ [\1 \2] \3/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g; s/(dissemination topology) Map\(.*\), dropping it/\1 Map(sv1, sv4, sv3), dropping it/' | cut -c1-250
```
```
2026-10-07T16:40:00.345Z Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it", [AvailabilityModule globalSequencerSv4] INFO
2026-10-07T16:40:00.360Z Availability.LocalOutputFetch.FetchedBlockDataFromStorage: fetching BatchId(..0a66) through remote nodes", [AvailabilityModule globalSequencerSv4] DEBUG
2026-10-07T16:40:00.361Z Sending message `AvailabilityMessage` to random authenticated node among List(SEQ::sv2::..829f, SEQ::sv3::..ba86) with workflow ID FetchBatchesSingleWorkflowId (not a retry), keeping blacklist Some(Map())","logger_name":"c.d.
2026-10-07T16:40:00.361Z DB stored pre-prepare w/ (epochNumber=79, blockNumber=3092) and batches List(BatchId(..0a66))", [IssSegmentModule globalSequencerSv4] DEBUG
2026-10-07T16:40:00.378Z ConsensusSegment.Internal.OrderedBlockStored: DB stored block w/ (epochNumber=79, blockNumber=3092), view number 0 and batches List(BatchId(..0a66))", [IssSegmentModule globalSequencerSv4] DEBUG
2026-10-07T16:40:00.381Z The newly ordered block (epochNumber=79, blockNumber=3092) contains batch IDs BatchId(..0a66)", [IssConsensusModule globalSequencerSv4] DEBUG
2026-10-07T16:40:00.383Z Output received from local consensus ordered block (mode = Consensus) with batch IDs List(BatchId(..0a66))", [OutputModule globalSequencerSv4] DEBUG
2026-10-07T16:40:00.384Z Received a message from 'SEQ::sv2::..829f' signed with '..1280' but it cannot be verified in the currently known dissemination topology Map(sv1, sv4, sv3), dropping it", [AvailabilityModule globalSequencerSv4] WARN
2026-10-07T16:40:00.385Z Availability.LocalOutputFetch.LocalFetchedBatchValidated: received BatchId(..0a66), persisting it", [AvailabilityModule globalSequencerSv4] DEBUG
2026-10-07T16:40:00.388Z Availability.LocalOutputFetch.FetchedBatchStored: BatchId(..0a66) was missing and is now persisted", [AvailabilityModule globalSequencerSv4] DEBUG
2026-10-07T16:40:00.389Z Output received completed block; epoch: 79, blockID: 3092, batchIDs: List(BatchId(..0a66))", [OutputModule globalSequencerSv4] DEBUG
```

Both peers received the fetch request, and both sent the batch:

```
cd log/10303/logs-wall-clock-time-2
zcat canton_before_shutdown.clog.gz | grep -a -E 'T16:40:00\.3[5-9]' | grep -a 'BatchId(1220[0-9a-f]*0a66)' | grep -a -E 'FetchRemoteBatchData received|had requested|LocalFetchedBatchValidated' | sed -E 's/"logger_name":"[^"]*\.([A-Za-z]+):sequencer=([A-Za-z0-9]+)\/.*"level":"([A-Z]+)".*/ [\1 \2] \3/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-215
```
```
2026-10-07T16:40:00.380Z Availability.RemoteOutputFetch.FetchRemoteBatchData received from SEQ::sv4::..abad first request for batch BatchId(..0a66), loading it", [AvailabilityModule globalSequencerSv2] DEBUG
2026-10-07T16:40:00.381Z Availability.RemoteOutputFetch.FetchRemoteBatchData received from SEQ::sv4::..abad first request for batch BatchId(..0a66), loading it", [AvailabilityModule globalSequencerSv3] DEBUG
2026-10-07T16:40:00.381Z Availability.LocalOutputFetch.AttemptedBatchDataLoadForNode: node 'SEQ::sv4::..abad' had requested BatchId(..0a66), sending it", [AvailabilityModule globalSequencerSv2] DEBUG
2026-10-07T16:40:00.382Z Availability.LocalOutputFetch.AttemptedBatchDataLoadForNode: node 'SEQ::sv4::..abad' had requested BatchId(..0a66), sending it", [AvailabilityModule globalSequencerSv3] DEBUG
2026-10-07T16:40:00.385Z Availability.LocalOutputFetch.LocalFetchedBatchValidated: received BatchId(..0a66), persisting it", [AvailabilityModule globalSequencerSv4] DEBUG
```

## 8. Canton 3.6.1 bytecode: WARN only for fetch replies, INFO for everything else; membership updates only from consensus

`AvailabilityModule.handleUnverifiedProtocolMessage` (AvailabilityModule.scala, 3.6.1) works as follows:
- A `RemoteBatchDataFetched` message from a sender that `OrderingTopology.contains` rejects is logged at WARN (lines
  192-211).
- Every other availability message from such a sender is logged at INFO (lines 242-245, after the
  `verifySignedMessage` branch).
- `updateActiveMembership` is called only from `handleConsensusMessage` and `handleProposalRequest`.

The INFO drops (sv2's dissemination of its own batches) are therefore silent. Only replies to sv4's own fetches, which
it addressed with the newer topology, produce a WARN.

```
cd log/canton-jars
C=com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.modules.availability.AvailabilityModule
javap -p -c -l -cp canton-open-source-3.6.1/lib/canton-open-source-3.6.1.jar $C 2>/dev/null > $TMPDIR/am.javap
awk '/private void handleUnverifiedProtocolMessage\(/{p=1} p&&/(instanceof|ifeq|ifne|OrderingTopology.contains|isWarnEnabled|isInfoEnabled|Logger\.(warn|info)|makeConcat|verifySignedMessage)/{sub(/^ +/,""); print} p&&/LineNumberTable/{exit}' $TMPDIR/am.javap | sed -E 's@com/digitalasset/canton/synchronizer/sequencer/block/bftordering/@@g; s@ +// @ // @' | cut -c1-170
awk '/private void handleUnverifiedProtocolMessage\(/{p=1} p&&/LineNumberTable/{q=1} q&&/line /{printf "%s%s ", $2, $3} q&&/LocalVariableTable/{print ""; exit}' $TMPDIR/am.javap
grep -n -E 'invoke(special|virtual) .*updateActiveMembership' $TMPDIR/am.javap | cut -d: -f1 | while read n; do awk -v l=$n 'NR<l && /^  [a-z].*\(/{m=$0} NR==l{sub(/\(.*/,"",m); sub(/^ +/,"",m); print "updateActiveMembership called from: " m; exit}' $TMPDIR/am.javap; done
```
```
11: instanceof    #242 // class framework/modules/Availability$RemoteOutputFetch$RemoteBatchDataFetched
14: ifeq          172
54: invokevirtual #1044 // Method framework/data/topology/OrderingTopology.contains:(Ljava/lang/String;)Z
57: ifne          159
67: invokeinterface #1056,  1 // InterfaceMethod org/slf4j/Logger.isWarnEnabled:()Z
72: ifeq          138
103: invokedynamic #1076,  0 // InvokeDynamic #4:makeConcatWithConstants:(Ljava/lang/String;Ljava/lang/String;Lscala/collection/immutable/Map;)Ljava/lang/String;
114: invokeinterface #1086,  2 // InterfaceMethod org/slf4j/Logger.warn:(Ljava/lang/String;)V
209: ifeq          326
224: ifeq          278
243: invokedynamic #1121,  0 // InvokeDynamic #5:makeConcatWithConstants:(Ljava/lang/String;)Ljava/lang/String;
296: invokeinterface #1131,  5 // InterfaceMethod core/integration/canton/crypto/CryptoProvider.verifySignedMessage:(Lframework/data/SignedMessage;Lcore/integration/canto
333: invokeinterface #1146,  1 // InterfaceMethod org/slf4j/Logger.isInfoEnabled:()Z
338: ifeq          403
369: invokedynamic #1076,  0 // InvokeDynamic #4:makeConcatWithConstants:(Ljava/lang/String;Ljava/lang/String;Lscala/collection/immutable/Map;)Ljava/lang/String;
380: invokeinterface #1149,  2 // InterfaceMethod org/slf4j/Logger.info:(Ljava/lang/String;)V
190:0 192:9 196:24 197:30 199:45 202:60 203:89 205:93 202:108 207:142 208:158 211:159 192:172 215:175 216:181 217:196 218:212 219:282 220:283 221:287 222:288 220:291 224:301 242:326 243:355 245:359 242:374 
updateActiveMembership called from: private void handleConsensusMessage
updateActiveMembership called from: private void handleProposalRequest
```

## Verdict

- Family B (BFT sequencer onboarding, blacklisting), new signature (4): WARN `AvailabilityModule ... cannot be verified
  in the currently known dissemination topology ..., dropping it` on a sequencer that onboarded in an earlier step and
  is blacklisted in the epoch that adds a later newcomer. Not a duplicate of the ack-stall signatures (1)-(3): no
  DEADLINE_EXCEEDED, and no stalled participant or mediator.
- Flake, no functional impact: every batch was obtained from another PoA signer within milliseconds, and the test passed.
- Fix location: Canton. The availability module should refresh its active membership on every new epoch, not only
  when it is asked for a proposal. Alternatively, a fetch reply could be verified against the topology the fetch was
  sent with. The output fetch and the reply verification use different topologies, so the WARN is a Canton
  inconsistency, not a misbehaving peer.
- Splice-side options, none taken:
  - Serialise SV sequencer onboarding (the existing family B mitigation). That might avoid the staggered 3 -> 4 step,
    but the blacklisting could still happen.
  - Add an ignore pattern. Rejected: the same WARN is the only signal for a real key or topology mismatch (for example
    a sequencer signing with a key that is not in the topology), and the precedents are 10010 and 10084.
- No branch. Rerun.
- Not verified:
  - Why the epoch-77 view change blacklisted only sv4.
  - Whether a newer Canton than 3.6.1 already refreshes the availability membership on new epochs.
  - Not checked against the Canton source repo; only the 3.6.1 jar bytecode was read.
