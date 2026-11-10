# 11329 - BootstrapPackageConfigIntegrationTest: close vote commits 27.6 s after the 45 s check while all SV and validator participants upload 29-35 DARs each (dup 11313) (run 42790498517)

Duplicate of 11313, with the cause of its stall. Post-merge main 6c0fee0505 ("add back unused cometbft config
(#8390)"), job `resource-intensive (0)`, canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252, 3 of 4 tests passed. Same
check and same shape as 11313: the DsoRules_CloseVoteRequest was sent at 09:51:25.7 to 26.6 and sv4's completed at
09:52:17.182, 27.6 s after the check gave up (09:51:49.594). Ordering topology size 4 and unchanged activation time
throughout, so not 11255. New in this packet, and also present in the 11313 run: from the moment the vote request
with the future package config appears, the vetting triggers of sv1, sv3, sv4 (sv2's is paused by the test) and of
the three validators upload every intermediate DAR version to their participants (35 distinct DARs per SV, 29 per
validator, 192 uploads in total, batches of 5) from 09:51:04 to 09:52:01. All of these participants and the
synchronizer nodes run in one Canton JVM; its log rate dropped from about 10,000 lines/s to 52 to 372 lines/s for
much of 09:51:05 to 09:51:44, every BFT segment of epoch 49 hit its local timeout, and epochs 50 to 52 ran with sv4,
then sv3, then sv2 blacklisted and view changes on their segments. In the 11313 run the same 192 uploads start at
17:13:10, exactly the onset of its unexplained Canton stall (17:13:11). The upload burst is part of the test flow, so
the 45 s budget does not cover the test's own load on a slow runner.

- Run: https://github.com/canton-network/splice/actions/runs/42790498517, main 6c0fee0505 ("add back unused
  cometbft config (#8390)"), job 129742730691 `ci / scala_test_resource_intensive / resource-intensive (0)`,
  attempt 1 (artifact `logs-resource-intensive-0`, created 2026-11-10T09:58:42Z).
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test budget; load is the test's own DAR vetting burst in the shared Canton JVM.

## 1. Issue, job mapping and failing assertion

Issue body (verbatim, `gh api repos/DACH-NY/cn-test-failures/issues/11329 --jq .body`):
```
[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/42790498517).
Job: resource-intensive (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [6c0fee0](https://github.com/canton-network/splice/commit/6c0fee0505b654bd27ffadddf32c1b0a913feaf2) add back unused cometbft config (#8390)
Actor: moritzkiefer-da
```

```
$ RUN=42790498517; export NAME='resource-intensive (0)'
$ gh api --paginate "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=100" --jq '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
129742730691 attempt=1 ci / scala_test_resource_intensive / resource-intensive (0)

$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11329/job.log | grep -a -A1 'upgrade to latest \*\*\* FAILED' | head -2
[info] - Bootstrap with specific versions and then upgrade to latest *** FAILED ***
[info]   Check observe AmuletRules with upgraded config for sv1-3 accept vote request for upgraded packages "0.1.[19]" was not equal to "0.1.[25]" (BootstrapPackageConfigIntegrationTest.scala:291)

$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11329/job.log | grep -a -E 'Tests: succeeded'
[info] Tests: succeeded 3, failed 1, canceled 0, ignored 0, pending 0

$ git show 6c0fee0505:nix/canton-sources.json | grep -m1 version
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
```

## 2. The check and its budget (unchanged since 11313)

Vote request expires 19 s after creation; CloseVoteRequestTrigger then closes it; the check waits 45 s after the
last vote. The 45 s come from #5214 (2026-04-24, 30 s to 45 s, after the April hit 8179 of the same check).

```
$ git show 6c0fee0505:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BootstrapPackageConfigIntegrationTest.scala | sed -n '215,217p;274,293p'
    val expiration = new RelTime(19_000_000)
    val scheduledTime =
      Instant.now().plus(expiration.microseconds, ChronoUnit.MICROS).plus(1, ChronoUnit.SECONDS)
      actAndCheck(timeUntilSuccess = 45.seconds)(
        s"sv1-3 accept vote request for upgraded packages",
        Seq(sv1Backend, sv2Backend, sv3Backend).map(sv =>
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              isAccepted = true,
              "url",
              "description",
            )
          }
        ),
      )(
        "observe AmuletRules with upgraded config",
        _ => {
          val newAmuletRules = sv1Backend.getDsoInfo().amuletRules

          newAmuletRules.payload.configSchedule.initialValue.packageConfig.amulet shouldBe DarResources.amulet.latest.metadata.version
            .toString()
        },

$ git log --format='%h %ad %s' --date=short 6c0fee0505 -L274,276:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BootstrapPackageConfigIntegrationTest.scala | grep -E '^[0-9a-f]{10} ' | head -1
bb45b8f7cc 2026-04-24 Bump timeUntilSuccess in BootstrapPackageConfigIntegrationTest (#5214)

$ git show bb45b8f7cc | grep -E '^[-+] ' | head -2
-      actAndCheck(timeUntilSuccess = 30.seconds)(
+      actAndCheck(timeUntilSuccess = 45.seconds)(
```

## 3. Test timeline and the close transaction

Check from 09:51:04.220 to 09:51:49.594. The trigger picked up the expired request at 09:51:24.9 (readyAt
09:51:19); the four SVs submitted DsoRules_CloseVoteRequest at 09:51:25.671 to 26.597; sv4's succeeded at
09:52:17.182, the others lost the race (LOCAL_VERDICT_LOCKED_CONTRACTS).

```
$ A=log/11329/logs-resource-intensive-0
$ zcat $A/canton_network_test.clog.gz | grep -a 'BootstrapPackageConfigIntegrationTest:BootstrapPackageConfigIntegrationTest' | grep -aE '(Running|Finished|Failed) clue: \((act|check)\) (Creating vote|sv1-3 accept|observe Amulet)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]*)".*/\1 \2/'
2026-11-10T09:50:59.862Z Running clue: (act) Creating vote request for upgraded packages
2026-11-10T09:51:01.224Z Finished clue: (act) Creating vote request for upgraded packages
2026-11-10T09:51:01.286Z Running clue: (act) sv1-3 accept vote request for upgraded packages
2026-11-10T09:51:04.220Z Finished clue: (act) sv1-3 accept vote request for upgraded packages
2026-11-10T09:51:04.220Z Running clue: (check) observe AmuletRules with upgraded config
2026-11-10T09:51:49.594Z Failed clue: (check) observe AmuletRules with upgraded config

$ zcat $A/canton_network_test.clog.gz | grep -a 'CloseVoteRequestTrigger' | grep -a -E 'Processing\\nReadyTask' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-80 | head -5
2026-11-10T09:51:24.887Z Processing\nReadyTask(\n  readyAt = 2026-11-10T09:51:19
2026-11-10T09:51:25.487Z Processing\nReadyTask(\n  readyAt = 2026-11-10T09:51:20
2026-11-10T09:51:25.576Z Processing\nReadyTask(\n  readyAt = 2026-11-10T09:51:20
2026-11-10T09:51:25.815Z Processing\nReadyTask(\n  readyAt = 2026-11-10T09:51:20
2026-11-10T09:57:56.607Z Processing\nReadyTask(\n  readyAt = 2026-11-10T09:57:51

$ zcat $A/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-10T09:51:25" && t<="2026-11-10T09:51:27"' | grep -a 'SubmitAndWaitForTransaction' | grep -a 'sending request' | jq -r '"\(."@timestamp"[11:23]) \(.message|[scan("DsoRules_[A-Za-z]+")]|unique|join(","))"'
09:51:25.671 DsoRules_CloseVoteRequest
09:51:26.096 DsoRules_CloseVoteRequest
09:51:26.490 DsoRules_CloseVoteRequest
09:51:26.597 DsoRules_CloseVoteRequest

$ zcat $A/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-10T09:51:20" && t<="2026-11-10T09:53:30"' | grep -a 'SubmitAndWaitForTransaction' | grep -a -E 'sending request|succeeded|failed with' | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|capture("SV=(?<s>sv[0-9])")?.s // .logger_name[-40:]) \(."trace-id"[0:8]) \(.message|gsub("\\n";" ")|.[0:200])"'
09:51:25.671 sv4 4caaed6a Request (tid:b42ebb950b7b5264db9fafb7dec83b57) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: sending request SubmitAndWaitForTransactionRequest(   Commands(
09:51:26.096 sv3 2a52bc73 Request (tid:f5ca53c630349d7c0e1f8bbb0816d4aa) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: sending request SubmitAndWaitForTransactionRequest(   Commands(
09:51:26.490 sv2 9c210018 Request (tid:5ac87bb382a92dcbd28a0b80ba2195ea) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: sending request SubmitAndWaitForTransactionRequest(   Commands(
09:51:26.597 sv1 f581883a Request (tid:037dfe291c0bf92fb4f60d19c1da8e9b) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: sending request SubmitAndWaitForTransactionRequest(   Commands(
09:52:17.105 sv1 f581883a Request (tid:037dfe291c0bf92fb4f60d19c1da8e9b) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: failed with ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS(2,037dfe29): Reje
09:52:17.182 sv4 4caaed6a Request (tid:b42ebb950b7b5264db9fafb7dec83b57) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: succeeded(OK)
09:52:17.198 sv3 2a52bc73 Request (tid:f5ca53c630349d7c0e1f8bbb0816d4aa) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS(2,f5ca53c6): Reje
09:52:17.387 sv2 9c210018 Request (tid:5ac87bb382a92dcbd28a0b80ba2195ea) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: failed with ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS(2,5ac87bb3): Reje
```

## 4. Where the 51 s went (sv4's close request, request 2026-11-10T09:51:26.789874Z)

Ordering took 4.4 s (sent 09:51:27.507, output block 2951 of epoch 50 at 09:51:31.9 to 32.4), delivery to the
mediators another 4 s (09:51:36.4 to 37.4). The participants sent their confirmations at 09:51:37.5 to 38.1; the
mediators received them 15 to 21 s later (09:51:53.0 to 09:51:59.5) and finalized Approve at 09:51:53.6 to 09:52:00.1.
sv3's mediator could not send its result (its sequencer was blacklisted, section 5), sv4's and sv1's went out at
09:52:15.5 and 09:52:15.6, and all four participants finalized at 09:52:15.7 to 15.8. Same picture as 11313 section 4.

```
$ zcat $A/canton.clog.gz | grep -a -E 'b59e8f98-f2d3-46b6-9d2a-5e4636a02311|2026-11-10T09:51:26.789874Z' | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|split(":")[1]? // ""|split("/")[0]) \(.message|gsub("\\n";" ")|gsub("  +";" ")|.[0:150])"' | grep -a -E 'sends request with id|BFT ordering request at index|Phase 2: Registered|Phase 4: Sending|Phase 5: Received|Phase 6|Failed to send result|Sent result|Finalizing Transaction|timed out'
09:51:27.507 sequencer=globalSequencerSv1 'PAR::sv4::1220d4dff4d1...' sends request with id 'b59e8f98-f2d3-46b6-9d2a-5e4636a02311' of size 5646 bytes with 3 envelopes to MGR=1, PAR=4.
09:51:31.899 sequencer=globalSequencerSv2 BFT ordering request at index 18 in output block (epochNumber=50, blockNumber=2951) with message ID b59e8f98-f2d3-46b6-9d2a-5e4636a02311 may change or
09:51:31.984 sequencer=globalSequencerSv1 BFT ordering request at index 18 in output block (epochNumber=50, blockNumber=2951) with message ID b59e8f98-f2d3-46b6-9d2a-5e4636a02311 may change or
09:51:32.069 sequencer=globalSequencerSv4 BFT ordering request at index 18 in output block (epochNumber=50, blockNumber=2951) with message ID b59e8f98-f2d3-46b6-9d2a-5e4636a02311 may change or
09:51:32.381 sequencer=globalSequencerSv3 BFT ordering request at index 18 in output block (epochNumber=50, blockNumber=2951) with message ID b59e8f98-f2d3-46b6-9d2a-5e4636a02311 may change or
09:51:36.401 mediator=globalMediatorSv3 Phase 2: Registered request=2026-11-10T09:51:26.789874Z from submittingParticipant=PAR::sv4::1220d4dff4d1... with 1 view(s).
09:51:36.880 mediator=globalMediatorSv4 Phase 2: Registered request=2026-11-10T09:51:26.789874Z from submittingParticipant=PAR::sv4::1220d4dff4d1... with 1 view(s).
09:51:37.106 mediator=globalMediatorSv2 Phase 2: Registered request=2026-11-10T09:51:26.789874Z from submittingParticipant=PAR::sv4::1220d4dff4d1... with 1 view(s).
09:51:37.407 mediator=globalMediatorSv1 Phase 2: Registered request=2026-11-10T09:51:26.789874Z from submittingParticipant=PAR::sv4::1220d4dff4d1... with 1 view(s).
09:51:37.502 participant=sv1Participant Phase 4: Sending for request=2026-11-10T09:51:26.789874Z with msgId=3eac8676-c1e0-4ff1-bcb5-8a1010769e49 approved=1, rejected=0, abstained=0
09:51:37.589 participant=sv3Participant Phase 4: Sending for request=2026-11-10T09:51:26.789874Z with msgId=cb594440-846d-410b-a7f6-827c1c4d4c27 approved=1, rejected=0, abstained=0
09:51:37.625 participant=sv2Participant Phase 4: Sending for request=2026-11-10T09:51:26.789874Z with msgId=e3d75a46-7e3b-4c5d-acf8-1519b62d29bd approved=1, rejected=0, abstained=0
09:51:38.137 participant=sv4Participant Phase 4: Sending for request=2026-11-10T09:51:26.789874Z with msgId=cc00c7c5-a753-47f7-b307-d49914203444 approved=1, rejected=0, abstained=0
09:51:53.006 mediator=globalMediatorSv2 Phase 5: Received 1 response(s) for request=2026-11-10T09:51:26.789874Z.
...
09:51:53.596 mediator=globalMediatorSv2 Phase 6: Finalized request=RequestId(2026-11-10T09:51:26.789874Z) with verdict Approve at 2026-11-10T09:52:26.789874Z
...
09:51:53.612 mediator=globalMediatorSv3 Phase 6: Finalized request=RequestId(2026-11-10T09:51:26.789874Z) with verdict Approve at 2026-11-10T09:52:26.789874Z
...
09:51:56.896 mediator=globalMediatorSv1 Phase 6: Finalized request=RequestId(2026-11-10T09:51:26.789874Z) with verdict Approve at 2026-11-10T09:52:26.789874Z
...
09:51:59.510 mediator=globalMediatorSv4 Phase 5: Received 1 response(s) for request=2026-11-10T09:51:26.789874Z.
09:52:00.083 mediator=globalMediatorSv4 Phase 6: Finalized request=RequestId(2026-11-10T09:51:26.789874Z) with verdict Approve at 2026-11-10T09:52:26.789874Z
09:52:01.573 mediator=globalMediatorSv3 Failed to send result to sequencer for request 2026-11-10T09:51:26.789874Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequen
09:52:15.491 mediator=globalMediatorSv4 Sent result for request 2026-11-10T09:51:26.789874Z
09:52:15.627 mediator=globalMediatorSv1 Sent result for request 2026-11-10T09:51:26.789874Z
09:52:15.686 participant=sv3Participant Finalizing Transaction request=2026-11-10T09:51:26.789874Z.
09:52:15.706 participant=sv4Participant Finalizing Transaction request=2026-11-10T09:51:26.789874Z.
09:52:15.710 participant=sv1Participant Finalizing Transaction request=2026-11-10T09:51:26.789874Z.
09:52:15.790 participant=sv2Participant Finalizing Transaction request=2026-11-10T09:51:26.789874Z.
09:52:29.988 mediator=globalMediatorSv2 Sequencing result message timed out asynchronously for request 2026-11-10T09:51:26.789874Z

$ zcat $A/canton.clog.gz | grep -a 'Failed to send result to sequencer for request 2026-11-10T09:51:26.789874Z' | jq -r '"\(."@timestamp"[11:23]) \(.message|gsub("\\n";" ")|gsub("  +";" ")|.[0:400])"'
09:52:01.573 Failed to send result to sequencer for request 2026-11-10T09:51:26.789874Z RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,b42ebb95): Mempool received client request but this node is currently blacklisted, rejecting Request: send-async-versioned/97c104bf-5f78-4c72-aa21-c231550bb978 DecodedCantonError( code
```

## 5. BFT: segment timeouts from 09:51:16, rotating blacklist, ordering topology unchanged

Epochs 41 to 48 took about 4 s each. Epoch 49 (09:51:04.0, 24.7 s) ran into local timeouts on all four segments (09:51:16.8
to 09:51:24.0); epochs 50, 51 and 52 started with sv4, sv3 and sv2 blacklisted and took 23, 24 and 29 s, with view
changes on their segments. Size 4 and activation time 09:48:48.932022Z throughout (the next topology change is at
09:52:52, after the close).

```
$ zcat $A/canton.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'New epoch [0-9]+ has started' | jq -r '"\(."@timestamp"[11:23]) \(.message|gsub("\\n";" ")|gsub("::1220[0-9a-f]+";"")|capture("(?<x>New epoch.*size = [0-9]+)").x)"' | awk '$1>="09:50:30" && $1<="09:53:00"'
09:50:31.400 New epoch 41 has started with leaders = List(SEQ::sv2, SEQ::sv3, SEQ::sv4, SEQ::sv1)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:48:48.932022Z,   size = 4
...
09:51:00.113 New epoch 48 has started with leaders = List(SEQ::sv1, SEQ::sv2, SEQ::sv3, SEQ::sv4)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:48:48.932022Z,   size = 4
09:51:04.042 New epoch 49 has started with leaders = List(SEQ::sv2, SEQ::sv3, SEQ::sv4, SEQ::sv1)and blacklisted nodes = List(); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:48:48.932022Z,   size = 4
09:51:28.769 New epoch 50 has started with leaders = List(SEQ::sv3, SEQ::sv1, SEQ::sv2)and blacklisted nodes = List(SEQ::sv4); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:48:48.932022Z,   size = 4
09:51:51.399 New epoch 51 has started with leaders = List(SEQ::sv1, SEQ::sv2, SEQ::sv4)and blacklisted nodes = List(SEQ::sv3); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:48:48.932022Z,   size = 4
09:52:15.346 New epoch 52 has started with leaders = List(SEQ::sv3, SEQ::sv4, SEQ::sv1)and blacklisted nodes = List(SEQ::sv2); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:48:48.932022Z,   size = 4
09:52:44.773 New epoch 53 has started with leaders = List(SEQ::sv4, SEQ::sv1, SEQ::sv3)and blacklisted nodes = List(SEQ::sv2); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:48:48.932022Z,   size = 4
09:52:52.677 New epoch 54 has started with leaders = List(SEQ::sv1, SEQ::sv3, SEQ::sv4)and blacklisted nodes = List(SEQ::sv2); ordering topology = OrderingTopology(   activationTime = 2026-11-10T09:52:52.254042Z,   size = 4

$ zcat $A/canton.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | awk -F'"@timestamp":"' '{t=substr($2,12,12)} t>="09:50:50" && t<="09:52:30"' | grep -a -E 'moving from view number|View change completed' | jq -r '"\(."@timestamp"[11:23]) \(.message|gsub("\\n";" ")|gsub("::1220[0-9a-f]+";"")|.[0:150])"'
09:51:16.811 Segment 0 starting at block number 2870 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.
09:51:17.294 View change completed; metadata: (epochNumber=49, blockNumber=2870), view: 1
09:51:23.467 Segment 1 starting at block number 2871 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv4.
09:51:23.900 Segment 3 starting at block number 2873 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv2.
09:51:24.011 Segment 2 starting at block number 2872 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.
09:51:24.641 View change completed; metadata: (epochNumber=49, blockNumber=2871), view: 1
09:51:24.995 View change completed; metadata: (epochNumber=49, blockNumber=2872), view: 1
09:51:25.581 View change completed; metadata: (epochNumber=49, blockNumber=2873), view: 1
09:51:42.596 Segment 0 starting at block number 2950 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.
09:51:44.811 Segment 1 starting at block number 2951 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv2.
09:51:45.115 View change completed; metadata: (epochNumber=50, blockNumber=2950), view: 1
09:51:45.470 Segment 2 starting at block number 2952 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.
09:51:46.180 View change completed; metadata: (epochNumber=50, blockNumber=2952), view: 1
09:51:46.405 View change completed; metadata: (epochNumber=50, blockNumber=2951), view: 1
09:51:59.669 Segment 1 starting at block number 3031 moving from view number 0 to 1 after reaching weak quorum. New leader is SEQ::sv4.
09:52:00.471 Segment 0 starting at block number 3030 moving from view number 0 to 1 after reaching weak quorum. New leader is SEQ::sv2.
09:52:03.082 View change completed; metadata: (epochNumber=51, blockNumber=3031), view: 1
09:52:04.587 View change completed; metadata: (epochNumber=51, blockNumber=3030), view: 1
09:52:07.083 Segment 2 starting at block number 3032 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.
09:52:10.300 Segment 1 starting at block number 3031 moving from view number 1 to 2 after reaching local timeout. New leader is SEQ::sv1.
09:52:12.178 View change completed; metadata: (epochNumber=51, blockNumber=3032), view: 1
09:52:12.577 View change completed; metadata: (epochNumber=51, blockNumber=3031), view: 2
09:52:12.683 Segment 0 starting at block number 3030 moving from view number 1 to 2 after reaching weak quorum. New leader is SEQ::sv4.
09:52:15.029 View change completed; metadata: (epochNumber=51, blockNumber=3030), view: 2
```

## 6. The Canton JVM log rate collapses at 09:51:05

Lines per second in canton.clog (all synchronizer nodes and participants, one JVM) against the test log (separate
JVM). Canton: about 10,000 to 11,500 lines/s until 09:51:03, then mostly 52 to 372 lines/s with short bursts until
09:51:44; the test JVM keeps about half its rate. Same signature as 11313 section 6 (17:13:11 to 17:13:18).

```
$ for f in canton.clog.gz canton_network_test.clog.gz; do echo "== $f"; zcat $A/$f | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="09:51:00" && t<="09:52:20" {c[t]++} END{for(t in c) print t, c[t]}' | sort | tr '\n' ' '; echo; done
== canton.clog.gz
09:51:00 11202 09:51:01 10003 09:51:02 11515 09:51:03 10033 09:51:04 4693 09:51:05 182 09:51:06 4791 09:51:07 238 09:51:08 114 09:51:09 166 09:51:10 289 09:51:11 3025 09:51:12 372 09:51:13 3316 09:51:14 2703 09:51:15 52 09:51:16 1155 09:51:17 4504 09:51:18 3348 09:51:19 57 09:51:20 231 09:51:21 364 09:51:22 1980 09:51:23 1498 09:51:24 3633 09:51:25 2711 09:51:26 3772 09:51:27 4347 09:51:28 3407 09:51:29 851 09:51:30 718 09:51:31 2103 09:51:32 1528 09:51:33 1007 09:51:34 703 09:51:35 2589 09:51:36 3589 09:51:37 2954 09:51:38 922 09:51:39 1219 09:51:40 419 09:51:41 92 09:51:42 401 09:51:43 285 09:51:44 757 09:51:45 3491 09:51:46 6737 09:51:47 5082 09:51:48 4161 09:51:49 3794 09:51:50 4019 09:51:51 4434 09:51:52 4455 09:51:53 4916 09:51:54 653 09:51:55 384 09:51:56 1209 09:51:57 1537 09:51:58 1541 09:51:59 1754 09:52:00 1543 09:52:01 1064 09:52:02 827 09:52:03 1337 09:52:04 2327 09:52:05 1545 09:52:06 1130 09:52:07 2111 09:52:08 1609 09:52:09 1714 09:52:10 2064 09:52:11 2958 09:52:12 4960 09:52:13 6510 09:52:14 8690 09:52:15 11957 09:52:16 3201 09:52:17 3341 09:52:18 3730 09:52:19 3005 09:52:20 2058 
== canton_network_test.clog.gz
09:51:00 953 09:51:01 1219 09:51:02 1238 09:51:03 1254 09:51:04 1238 09:51:05 596 09:51:06 1118 09:51:07 599 09:51:08 506 09:51:09 506 09:51:10 507 09:51:11 1115 09:51:12 583 09:51:13 1127 09:51:14 925 09:51:15 580 09:51:16 714 09:51:17 931 09:51:18 779 09:51:19 526 09:51:20 535 09:51:21 545 09:51:22 722 09:51:23 677 09:51:24 974 09:51:25 730 09:51:26 738 09:51:27 856 09:51:28 692 09:51:29 674 09:51:30 633 09:51:31 999 09:51:32 753 09:51:33 691 09:51:34 590 09:51:35 877 09:51:36 908 09:51:37 952 09:51:38 695 09:51:39 782 09:51:40 620 09:51:41 522 09:51:42 541 09:51:43 538 09:51:44 573 09:51:45 1028 09:51:46 1033 09:51:47 725 09:51:48 614 09:51:49 827 09:51:50 938 09:51:51 1271 09:51:52 988 09:51:53 950 09:51:54 774 09:51:55 596 09:51:56 629 09:51:57 756 09:51:58 735 09:51:59 784 09:52:00 713 09:52:01 724 09:52:02 647 09:52:03 585 09:52:04 578 09:52:05 647 09:52:06 601 09:52:07 642 09:52:08 632 09:52:09 640 09:52:10 698 09:52:11 850 09:52:12 1403 09:52:13 971 09:52:14 957 09:52:15 1001 09:52:16 821 09:52:17 1185 09:52:18 942 09:52:19 864 09:52:20 826 
```

## 7. What loads the Canton JVM: the vetting triggers upload every intermediate DAR version

At 09:51:05 sv1's SvPackageVettingTrigger first sees a second schedule entry (09:51:19.846759Z, the vote request's
scheduled config) and starts vetting the newer packages; it uploads them in batches of 5, each batch taking 4 to 7 s.
The validators' ValidatorPackageVettingTrigger and the other SVs (sv2's trigger is paused by the test) do the same.

```
$ zcat $A/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,12)} t>="09:51:00" && t<="09:52:00"' | grep -a 'SV=sv1' | grep -a -E 'PackageVetting|ParticipantAdminConnection' | jq -r '"\(."@timestamp"[11:23]) \(.level) \(.logger_name|split(":")[0]|split(".")|last) \(.message|gsub("\\n";" ")|.[0:330])"'
...
09:51:03.973 INFO PackageVetting Vetting for schedule List((2026-11-10T09:47:36.655882Z,List((splice-wallet-payments,0.1.19), (splice-amulet-name-service,0.1.20), (splice-dso-governance,0.1.25), (splice-wallet,0.1.20), (splice-amulet,0.1.19), (splice-validator-lifecycle,0.1.5)))) from amulet rules AmuletConfigSchedule(org.lfdecentralizedtrust.splice.codegen.jav
...
09:51:05.002 INFO SvPackageVettingTrigger Running package vetting as the input has changed from Set(global-domain::122044df80bb..., ContractId(009764c2f76acad02e84b51d2f18a684c60750d78280c4ff9809cc0e8ecb8d0cb9ca121220869680614aa5619e2b372e918b7d20113fb5a1e7dcd1de075ac62e69d7382058), ContractId(006d50ccd37bacd783b778e2a87a09a3a08c8df56633c260d848e3df61ff0f87acca121220f65
09:51:05.002 INFO PackageVetting Vetting for schedule List((2026-11-10T09:47:36.655882Z,List((splice-wallet-payments,0.1.19), (splice-wallet,0.1.20), (splice-amulet-name-service,0.1.20), (splice-dso-governance,0.1.25), (splice-amulet,0.1.19), (splice-validator-lifecycle,0.1.5))), (2026-11-10T09:51:19.846759Z,List((splice-wallet-payments,0.1.22), (splice-dso-gov

$ zcat $A/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,12)} t>="09:51:06" && t<="09:51:30"' | grep -a 'SV=sv1' | grep -a -E 'PackageVetting|ParticipantAdminConnection|UploadDar|AuthorizeVetted|Authorize ' | grep -av 'Not running package unvetting' | jq -r '"\(."@timestamp"[11:23]) \(.level) \(.logger_name|split(":")[0]|split(".")|last) \(.message|gsub("\\n";" ")|gsub("  +";" ")|.[0:230])"' | grep -av 'received a message' | grep -a -E 'Sending upload|succeeded' | head -12
09:51:06.473 INFO ParticipantAdminConnection Sending upload dar for splice-wallet-payments-0.1.22
09:51:06.475 INFO ParticipantAdminConnection Sending upload dar for splice-dso-governance-0.1.29
09:51:06.478 INFO ParticipantAdminConnection Sending upload dar for splice-dso-governance-0.1.31
09:51:06.480 INFO ParticipantAdminConnection Sending upload dar for splice-dso-governance-0.1.27
09:51:06.482 INFO ParticipantAdminConnection Sending upload dar for splice-wallet-payments-0.1.25
09:51:10.929 DEBUG ApiClientRequestLogger Request (tid:76d5f4db1fe979e506ceb1e271d60016) com.digitalasset.canton.admin.participant.v30.PackageService/UploadDar to 127.0.0.1:5102: succeeded(OK)
09:51:11.084 DEBUG ApiClientRequestLogger Request (tid:fa6f89b4fe40381c0fb94366dc6be38c) com.digitalasset.canton.admin.participant.v30.PackageService/UploadDar to 127.0.0.1:5102: succeeded(OK)
09:51:11.382 DEBUG ApiClientRequestLogger Request (tid:0ff84b65701cbf65830b89599ce7c755) com.digitalasset.canton.admin.participant.v30.PackageService/UploadDar to 127.0.0.1:5102: succeeded(OK)
09:51:13.426 DEBUG ApiClientRequestLogger Request (tid:49071657747974bba5766cc327826341) com.digitalasset.canton.admin.participant.v30.PackageService/UploadDar to 127.0.0.1:5102: succeeded(OK)
09:51:13.599 DEBUG ApiClientRequestLogger Request (tid:76dee2e56733b69cec984ddc94a215c3) com.digitalasset.canton.admin.participant.v30.PackageService/UploadDar to 127.0.0.1:5102: succeeded(OK)
09:51:13.601 INFO ParticipantAdminConnection Sending upload dar for splice-amulet-name-service-0.1.24
09:51:13.603 INFO ParticipantAdminConnection Sending upload dar for splice-amulet-0.1.21
```


Admin and ledger API calls from the apps between 09:51:00 and 09:52:20, by service method (top 20):

```
$ zcat $A/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,12)} t>="09:51:00" && t<="09:52:20"' | grep -a 'sending request' | jq -r '.message|capture("v[0-9]+\\.(?<s>[A-Za-z]+/[A-Za-z]+) to")?.s // empty' | awk '{c[$0]++} END{for(k in c) print c[k], k}' | sort -rn | head -25
668 SequencerAdministrationService/TrafficControlState
448 SequencerStatusService/SequencerStatus
344 TopologyManagerReadService/ListPartyToParticipant
332 SynchronizerTimeService/FetchTime
287 TopologyManagerReadService/ListDecentralizedNamespaceDefinition
280 TopologyManagerReadService/ListSequencerSynchronizerState
224 SequencerBftAdministrationService/ListConfiguredEndpoints
192 PackageService/UploadDar
170 TopologyManagerReadService/ListMediatorSynchronizerState
167 SynchronizerConnectivityService/ListRegisteredSynchronizers
164 TopologyManagerReadService/ListSynchronizerParametersState
153 TopologyManagerReadService/ListSequencingParametersState
151 TrafficControlService/TrafficControlState
127 TopologyManagerReadService/ListVettedPackages
84 StateService/GetConnectedSynchronizers
29 PackageService/ListDars
12 UpdateService/GetUpdates
9 TopologyManagerWriteService/Authorize
7 CommandService/SubmitAndWaitForTransaction
6 CommandService/SubmitAndWait
```

Per node: every upload is a distinct DAR (no repeats), 35 per SV, 29 per validator, 192 in all, from 09:51:04 to
09:52:01:

```
$ zcat $A/canton_network_test.clog.gz | grep -a 'PackageService/UploadDar' | grep -a 'sending request' | jq -r '"\(."@timestamp"[11:19]) \(.logger_name|capture("/(?<a>(SV|validator)=[A-Za-z0-9]+)")?.a // "test")"' | awk '$1>="09:51:00" && $1<="09:52:30" {c[$2]++; if(!f[$2]) f[$2]=$1; l[$2]=$1} END{for(k in c) print k, c[k], f[k], l[k]}' | sort
SV=sv1 35 09:51:06 09:51:53
SV=sv3 35 09:51:06 09:52:01
SV=sv4 35 09:51:04 09:51:53
validator=aliceValidator 29 09:51:04 09:51:37
validator=bobValidator 29 09:51:04 09:51:39
validator=splitwellValidator 29 09:51:04 09:51:38

$ zcat $A/canton_network_test.clog.gz | grep -a 'Sending upload dar for' | jq -r '"\(."@timestamp"[11:19]) \(.logger_name|capture("/(?<a>(SV|validator)=[A-Za-z0-9]+)")?.a // "test") \(.message|sub("Sending upload dar for ";""))"' | awk '$1>="09:51:00" && $1<="09:52:30" {n[$2]++; if(!s[$2" "$3]++) d[$2]++} END{for(k in n) print k, "uploads="n[k], "distinct="d[k]}' | sort
SV=sv1 uploads=35 distinct=35
SV=sv3 uploads=35 distinct=35
SV=sv4 uploads=35 distinct=35
validator=aliceValidator uploads=29 distinct=29
validator=bobValidator uploads=29 distinct=29
validator=splitwellValidator uploads=29 distinct=29
```

(3 x 35 + 3 x 29 = 192, the `PackageService/UploadDar` count above.)

The same burst in the 11313 run (artifact `log/11313/logs-resource-intensive-0`, run 42658360509), starting at
17:13:10 to 17:13:13, the onset of the stall that packet left unexplained (canton.clog 4131 lines at 17:13:10, 53 at
17:13:11):

```
$ A=log/11313/logs-resource-intensive-0; zcat $A/canton_network_test.clog.gz | grep -a 'PackageService/UploadDar' | grep -a 'sending request' | jq -r '"\(."@timestamp"[11:19]) \(.logger_name|capture("/(?<a>(SV|validator)=[A-Za-z0-9]+)")?.a // "test")"' | awk '$1>="17:12:30" && $1<="17:14:30" {c[$2]++; if(!f[$2]) f[$2]=$1; l[$2]=$1} END{for(k in c) print k, c[k], f[k], l[k]}' | sort
SV=sv1 35 17:13:13 17:14:13
SV=sv3 35 17:13:10 17:14:12
SV=sv4 35 17:13:10 17:14:08
validator=aliceValidator 29 17:13:10 17:13:53
validator=bobValidator 29 17:13:13 17:13:46
validator=splitwellValidator 29 17:13:10 17:13:53
```

## 8. Duplicate check and frequency

Tracker search for the suite: the only open hit is 11313 (same check, same shape); 8179 (April, same check, 30 s
budget, fixed by #5214 raising it to 45 s); 11189 and 11191 were the reference sequencer storm (family L, cannot recur
on DA BFT). The other hits are different failures of the same suite (titles below).

```
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "BootstrapPackageConfigIntegrationTest"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:120])"'
#11313 open/- 2026-11-09 BootstrapPackageConfigIntegrationTest: close vote commits after the 45 s check, BFT view changes and blacklisting withou
#11255 open/- 2026-11-06 Timeouts due to changing bft ordering topology
#10474 closed/completed 2026-10-20 WalletNoDevNetIntegrationTest: checkErrors on SEQUENCER_NOT_ENOUGH_TRAFFIC_CREDIT WARNs, the preAppStart traffic reset t
#10473 closed/completed 2026-10-20 WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest: tap returns HTTP 429 SUBMISSION_ALREADY_IN_FLIGHT, sequencer s
#10676 closed/completed 2026-10-27 BootstrapPackageConfigIntegrationTest: validator unvets new packages after config vote executes (stale cached AmuletRule
#10686 closed/not_planned 2026-10-27 GHA Run 40520298067 : job resource-intensive (1) in scala_test Failed :fire:
#10357 closed/completed 2026-10-16 BootstrapPackageConfigIntegrationTest fails at "Bootstrap with specific versions and then upgrade to latest" with "The O
#9598 closed/completed 2026-08-11 BootstrapPackageConfigIntegrationTest fails at "Bootstrap with specific versions and then upgrade to latest" with "The O
#10145 closed/duplicate 2026-09-16 GetPreferredPackages failed with INTERNAL/An error occurred: GHA Run 35076492327
#10144 closed/duplicate 2026-09-16 job resource-intensive (1) - INTERNAL on GetPreferredPackages while the SV app uploads DARs: package metadata view seen 
#9639 closed/completed 2026-08-13 BootstrapPackageConfigIntegrationTest : Tests fail due to non-ignored warning "GetPreferredPackages to 127.0.0.1:5301: f
#8179 closed/completed 2026-04-24 BootstrapPackageConfigIntegrationTest: Bootstrap with specific versions and then upgrade to latest Check observe AmuletR
...
```

Same check, different cause, not counted: 11255 (topology change window; here size 4 and unchanged activation), 10676
(unvetting after execution), 10357 and 9598 ("The O..." operation errors), 10144/10145/9639 (GetPreferredPackages).

resource-intensive (0) on main push runs of the post-merge workflow created since 2026-11-06 (#8234, DA BFT in all
tests; first page of jobs per run): 2 failures in 32 completed, 11313 and this run.

```
$ gh api "repos/canton-network/splice/actions/runs?branch=main&event=push&created=>=2026-11-06&per_page=100" --jq '.workflow_runs[] | select(.name|test("post-merge")) | .id' > log/11329/runs.txt
$ for r in $(cat log/11329/runs.txt); do gh api "repos/canton-network/splice/actions/runs/$r/jobs?per_page=100" --jq ".jobs[] | select(.name|endswith(\"/ resource-intensive (0)\")) | \"$r \(.conclusion) \(.started_at)\"" 2>/dev/null; done | sort -k3 > log/11329/ri0.txt; awk '{print $2}' log/11329/ri0.txt | sort | uniq -c; grep -v success log/11329/ri0.txt
      2 failure
      1 null
     30 success
42658360509 failure 2026-11-09T16:48:50Z
42790498517 failure 2026-11-10T09:26:51Z
42823122392 null 2026-11-10T13:18:20Z
```

## Verdict

Duplicate of 11313, flake, test budget. The close vote's confirmations and mediator results crawl through a BFT
ordering that is in segment timeouts and a rotating blacklist, and the transaction commits 27.6 s after the 45 s
check. New fact for 11313: the load that starts it is the test's own. When the vote request carrying the latest
package config appears, the vetting triggers of six SV and validator apps (sv2's paused) upload 29 to 35 DARs each,
192 uploads into the one Canton JVM that also runs the four sequencers and mediators, between 09:51:04 and 09:52:01
(11313: 17:13:10 to 17:14:13). The Canton log rate collapses within a second of the first uploads in both runs.
The burst follows from the test flow, so it happens in every run; whether it trips the ordering depends on runner
speed (2 of 32 since 11-06).

Fix (test, as proposed on 11313): `actAndCheck(timeUntilSuccess = 90.seconds)` at
BootstrapPackageConfigIntegrationTest.scala:274; the uploads end about 57 s after the votes and the close commits
about 13 s after the ordering recovers, so 45 s cannot cover a slow run. No assignee: the budget line is from #5214
(April), the load is inherent to the test.

Not verified: that the DAR uploads (rather than something coinciding with them) are what stalls the Canton JVM: no
CPU, GC or thread data in the artifacts; the correlation is the onset to the second in two runs. The burst on a
passing run was not checked (no passing artifact downloaded), so its duration there is unknown.

## Addendum (main thread, 2026-11-10): Canton log rate at the onset, 11313 run

```
zcat log/11313/logs-resource-intensive-0/canton.clog.gz | awk '/"@timestamp":"2026-11-09T17:13:(09|10|11)/{print substr($0,15,22)}' | cut -c1-21 | uniq -c
```
```
  10025 "2026-11-09T17:13:09.
   4131 "2026-11-09T17:13:10.
     53 "2026-11-09T17:13:11.
```
