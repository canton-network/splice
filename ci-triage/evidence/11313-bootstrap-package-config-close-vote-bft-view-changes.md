# 11313 - BootstrapPackageConfigIntegrationTest: close vote finalized 26 s after the 45 s check, BFT view changes and sv4 blacklisted (run 42658360509)

New (not a duplicate). Post-merge main 91715b25c0 ("set temp thresholds (#8376)"), job `resource-intensive (0)`,
canton 3.7.2, 3 of 4 tests passed. Same check as 11189 and 11191 (`observe AmuletRules with upgraded config`,
BootstrapPackageConfigIntegrationTest.scala:291, 45 s), which were the reference sequencer retry storm (family L).
That cannot recur here: since #8234 all tests run DA BFT. This time the DsoRules_CloseVoteRequest was sent at
17:13:31.1 and completed at 17:14:21.6, 26 s after the check gave up (17:13:55.3). The ordering topology was stable
(size 4 since 17:10:34), so it is not the 11255 onboarding window: the Canton process logged at under 3 % of its
usual rate from 17:13:11 to 17:13:18, every segment of epoch 51 hit its local timeout at 17:13:19, sv4 was
blacklisted for epoch 52, and epoch 52 needed view changes on all three remaining segments (17:13:46, 17:14:06), so
the confirmations took 12 to 14 s to reach the mediators and the second mediator result was only sent at 17:14:20.2.
What stalled the Canton process is open (no resource metrics in the artifacts).

- Run: https://github.com/canton-network/splice/actions/runs/42658360509, main 91715b25c0 ("set temp thresholds
  (#8376)"), job 129303308680 `ci / scala_test_resource_intensive / resource-intensive (0)`, attempt 1.
- Runtime canton: 3.7.2.
- Component: test budget; Canton BFT orderer under load (cause of the stall open).

## 1. Failing assertion

```
$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11313/job.log | grep -a -A1 'upgrade to latest \*\*\* FAILED' | head -2
[info] - Bootstrap with specific versions and then upgrade to latest *** FAILED ***
[info]   Check observe AmuletRules with upgraded config for sv1-3 accept vote request for upgraded packages "0.1.[19]" was not equal to "0.1.[25]" (BootstrapPackageConfigIntegrationTest.scala:291)

$ sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/11313/job.log | grep -a -E 'Tests: succeeded'
[info] Tests: succeeded 3, failed 1, canceled 0, ignored 0, pending 0

$ git show 91715b25c0:nix/canton-sources.json | grep -m1 version
  "version": "3.7.2",

```

## 2. The check and its budget

The vote request expires 19 s after creation (`expiration`), CloseVoteRequestTrigger then closes it; the check waits
45 s from the last vote. The config change only shows once the close transaction is committed.

```
$ git show 91715b25c0:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BootstrapPackageConfigIntegrationTest.scala | sed -n '215,217p;274,293p'
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

```

## 3. Test timeline and the close transaction

Check from 17:13:10.342 to 17:13:55.344. The trigger picked up the expired request at 17:13:29.9 (readyAt
17:13:24.86) and the four SVs submitted the close at 17:13:31.1 to 31.3; sv3's succeeded at 17:14:21.631, the others
lost the race (LOCAL_VERDICT_LOCKED_CONTRACTS).

```
$ zcat log/11313/logs-resource-intensive-0/canton_network_test.clog.gz | grep -a 'BootstrapPackageConfigIntegrationTest:BootstrapPackageConfigIntegrationTest' | grep -aE '(Running|Finished|Failed) clue: \((act|check)\) (Creating vote|sv1-3 accept|observe AmuletRules with up)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /; s/","logger_name.*//'
2026-11-09T17:13:04.619Z Running clue: (act) Creating vote request for upgraded packages
2026-11-09T17:13:06.134Z Finished clue: (act) Creating vote request for upgraded packages
2026-11-09T17:13:06.158Z Running clue: (act) sv1-3 accept vote request for upgraded packages
2026-11-09T17:13:10.342Z Finished clue: (act) sv1-3 accept vote request for upgraded packages
2026-11-09T17:13:10.342Z Running clue: (check) observe AmuletRules with upgraded config
2026-11-09T17:13:55.344Z Failed clue: (check) observe AmuletRules with upgraded config

$ zcat log/11313/logs-resource-intensive-0/canton_network_test.clog.gz | grep -a 'CloseVoteRequestTrigger' | grep -a -E 'Processing\\nReadyTask' | head -1 | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-80
2026-11-09T17:13:29.917Z Processing\nReadyTask(\n  readyAt = 2026-11-09T17:13:24

$ zcat log/11313/logs-resource-intensive-0/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-09T17:13:29" && t<="2026-11-09T17:14:30"' | grep -a 'SubmitAndWaitForTransaction' | grep -a -E 'sending request|succeeded|failed with' | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|sub(".*/SV=";"")) tid:\(.message|capture("tid:(?<t>[0-9a-f]{8})").t) \(.message|if test("sending request") then "sent " + ((capture("(?<c>DsoRules_[A-Za-z]+)")? // {c:"?"}).c) elif test("succeeded") then "succeeded(OK)" else (capture("failed with (?<e>[A-Z_/]+)").e) end)"'
17:13:31.127 sv2 tid:f38a695a sent DsoRules_CloseVoteRequest
17:13:31.130 sv3 tid:377da38e sent DsoRules_CloseVoteRequest
17:13:31.349 sv4 tid:b9bcfbc3 sent DsoRules_CloseVoteRequest
17:13:31.349 sv1 tid:a3383d04 sent DsoRules_CloseVoteRequest
17:14:21.140 sv1 tid:a3383d04 ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
17:14:21.316 sv4 tid:b9bcfbc3 ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
17:14:21.619 sv2 tid:f38a695a ABORTED/LOCAL_VERDICT_LOCKED_CONTRACTS
17:14:21.631 sv3 tid:377da38e succeeded(OK)

```

## 4. Where the 50 s went (sv3's close request, sequenced at 17:13:34.633871Z)

Ordering of the request itself took 2.8 s (sent 17:13:33.1, block 2987 output at 17:13:35.9, at sv2 only at
17:13:39.4). The participants sent their confirmations at 17:13:39.7 to 39.9; the mediators received them 12 to 14 s
later (17:13:51.4 to 53.4) and finalized with Approve at 17:13:53.1 to 54.1. sv4's mediator could not send its result
(its sequencer was blacklisted, section 5), sv1's was sent at 17:14:02.2 and sv2's at 17:14:20.2, and all four
participants finalized 0.2 to 0.4 s after the second result. sv3's mediator result timed out at 17:14:37.

```
$ zcat log/11313/logs-resource-intensive-0/canton.clog.gz | grep -a -E 'a70eb144-b915-4e06-8bed-6df884409dea|2026-11-09T17:13:34.633871Z' | grep -a -E 'sends request with id|BFT ordering request at index|Phase 2: Registered|Phase 4: Sending|Phase 5: Received|Phase 6: Finalized|Sent result for request 2026-11-09T17:13:34.633871Z|Failed to send result to sequencer for request 2026-11-09T17:13:34.633871Z|timed out asynchronously for request 2026-11-09T17:13:34.633871Z|Finalizing Transaction' | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|capture("(sequencer|mediator|participant)=(?<n>[A-Za-z0-9]+)").n) \(.message|gsub("\\n";" ")|gsub("::1220[0-9a-f]+";"")|.[0:100])"' | awk '{k=$2" "$3" "$4} !(k in s){s[k]=1; print}'
17:13:33.120 globalSequencerSv1 'PAR::sv3...' sends request with id 'a70eb144-b915-4e06-8bed-6df884409dea' of size 5643 bytes with 3
17:13:35.940 globalSequencerSv1 BFT ordering request at index 6 in output block (epochNumber=52, blockNumber=2987) with message ID a
17:13:36.032 globalSequencerSv3 BFT ordering request at index 6 in output block (epochNumber=52, blockNumber=2987) with message ID a
17:13:39.228 globalSequencerSv4 BFT ordering request at index 6 in output block (epochNumber=52, blockNumber=2987) with message ID a
17:13:39.313 globalMediatorSv3 Phase 2: Registered request=2026-11-09T17:13:34.633871Z from submittingParticipant=PAR::sv3... with 
17:13:39.445 globalSequencerSv2 BFT ordering request at index 6 in output block (epochNumber=52, blockNumber=2987) with message ID a
17:13:39.511 globalMediatorSv1 Phase 2: Registered request=2026-11-09T17:13:34.633871Z from submittingParticipant=PAR::sv3... with 
17:13:39.712 sv2Participant Phase 4: Sending for request=2026-11-09T17:13:34.633871Z with msgId=1b5576b6-4f4e-41e7-8896-d14a831f
17:13:39.824 sv1Participant Phase 4: Sending for request=2026-11-09T17:13:34.633871Z with msgId=c5b4d75c-531d-42df-84dc-3c908582
17:13:39.825 sv4Participant Phase 4: Sending for request=2026-11-09T17:13:34.633871Z with msgId=4932d695-c6af-472c-a253-5e1554a8
17:13:39.920 sv3Participant Phase 4: Sending for request=2026-11-09T17:13:34.633871Z with msgId=caa8bc69-a273-4143-8860-2b26334f
17:13:40.648 globalMediatorSv4 Phase 2: Registered request=2026-11-09T17:13:34.633871Z from submittingParticipant=PAR::sv3... with 
17:13:42.944 globalMediatorSv2 Phase 2: Registered request=2026-11-09T17:13:34.633871Z from submittingParticipant=PAR::sv3... with 
17:13:51.442 globalMediatorSv3 Phase 5: Received 1 response(s) for request=2026-11-09T17:13:34.633871Z.
17:13:51.932 globalMediatorSv1 Phase 5: Received 1 response(s) for request=2026-11-09T17:13:34.633871Z.
17:13:53.107 globalMediatorSv1 Phase 6: Finalized request=RequestId(2026-11-09T17:13:34.633871Z) with verdict Approve at 2026-11-09
17:13:53.118 globalMediatorSv2 Phase 5: Received 1 response(s) for request=2026-11-09T17:13:34.633871Z.
17:13:53.215 globalMediatorSv3 Phase 6: Finalized request=RequestId(2026-11-09T17:13:34.633871Z) with verdict Approve at 2026-11-09
17:13:53.354 globalMediatorSv2 Phase 6: Finalized request=RequestId(2026-11-09T17:13:34.633871Z) with verdict Approve at 2026-11-09
17:13:53.410 globalMediatorSv4 Phase 5: Received 1 response(s) for request=2026-11-09T17:13:34.633871Z.
17:13:54.148 globalMediatorSv4 Phase 6: Finalized request=RequestId(2026-11-09T17:13:34.633871Z) with verdict Approve at 2026-11-09
17:13:55.014 globalMediatorSv4 Failed to send result to sequencer for request 2026-11-09T17:13:34.633871Z RequestRefused(   SendAsy
17:14:02.228 globalMediatorSv1 Sent result for request 2026-11-09T17:13:34.633871Z
17:14:20.219 globalMediatorSv2 Sent result for request 2026-11-09T17:13:34.633871Z
17:14:20.425 sv4Participant Finalizing Transaction request=2026-11-09T17:13:34.633871Z.
17:14:20.448 sv1Participant Finalizing Transaction request=2026-11-09T17:13:34.633871Z.
17:14:20.509 sv3Participant Finalizing Transaction request=2026-11-09T17:13:34.633871Z.
17:14:20.659 sv2Participant Finalizing Transaction request=2026-11-09T17:13:34.633871Z.
17:14:37.409 globalMediatorSv3 Sequencing result message timed out asynchronously for request 2026-11-09T17:13:34.633871Z

```

## 5. BFT: view changes on every segment, sv4 then sv3 blacklisted, ordering topology unchanged

Epoch 51 started 17:13:07.9 with four leaders. sv4's segment ordered block 2934 at 17:13:10.4 and 2938 at 17:13:13.4,
then all four segments reached their local timeout at 17:13:19.1 and changed view. Epoch 52 (17:13:24.5) excluded
sv4 as blacklisted and needed view changes on all three remaining segments (sv2's and sv3's at 17:13:46.5 to 47.0,
sv1's at 17:14:06.8 to 17:14:10.5), so it lasted 52 s. Size 4 in all four epochs.

```
$ zcat log/11313/logs-resource-intensive-0/canton.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | grep -a -E 'New epoch (50|51|52|53) has started' | jq -r '"\(."@timestamp"[11:23]) \(.message|gsub("\\n";" ")|gsub("::1220[0-9a-f]+";"")|capture("(?<x>New epoch [0-9]+ has started with leaders = List\\([^)]*\\)and blacklisted nodes = List\\([^)]*\\))").x) \(.message|capture("(?<s>size = [0-9]+)").s)"'
17:13:03.440 New epoch 50 has started with leaders = List(SEQ::sv3, SEQ::sv4, SEQ::sv1, SEQ::sv2)and blacklisted nodes = List() size = 4
17:13:07.922 New epoch 51 has started with leaders = List(SEQ::sv4, SEQ::sv1, SEQ::sv2, SEQ::sv3)and blacklisted nodes = List() size = 4
17:13:24.545 New epoch 52 has started with leaders = List(SEQ::sv2, SEQ::sv3, SEQ::sv1)and blacklisted nodes = List(SEQ::sv4) size = 4
17:14:16.747 New epoch 53 has started with leaders = List(SEQ::sv4, SEQ::sv1, SEQ::sv2)and blacklisted nodes = List(SEQ::sv3) size = 4

$ zcat log/11313/logs-resource-intensive-0/canton.clog.gz | grep -a 'sequencer=globalSequencerSv1/' | awk -F'"@timestamp":"' '{t=substr($2,12,12)} t>="17:13:08" && t<="17:14:20"' | grep -a -E 'moving from view number|View change completed|OrderedBlockStored: DB stored block w/ \(epochNumber=51, blockNumber=(2934|2938)\)' | jq -r '"\(."@timestamp"[11:23]) \(.message|gsub("\\n";" ")|gsub("::1220[0-9a-f]+";"")|.[0:150])"'
17:13:10.413 ConsensusSegment.Internal.OrderedBlockStored: DB stored block w/ (epochNumber=51, blockNumber=2934), view number 0 and batches List()
17:13:13.436 ConsensusSegment.Internal.OrderedBlockStored: DB stored block w/ (epochNumber=51, blockNumber=2938), view number 0 and batches List()
17:13:19.145 Segment 3 starting at block number 2893 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv4.
17:13:19.148 Segment 2 starting at block number 2892 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.
17:13:19.154 Segment 0 starting at block number 2890 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1.
17:13:19.157 Segment 1 starting at block number 2891 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv2.
17:13:19.341 View change completed; metadata: (epochNumber=51, blockNumber=2890), view: 1
17:13:21.244 View change completed; metadata: (epochNumber=51, blockNumber=2893), view: 1
17:13:21.352 View change completed; metadata: (epochNumber=51, blockNumber=2891), view: 1
17:13:21.329 View change completed; metadata: (epochNumber=51, blockNumber=2892), view: 1
17:13:46.544 Segment 0 starting at block number 2970 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3.
17:13:46.714 Segment 1 starting at block number 2971 moving from view number 0 to 1 after reaching weak quorum. New leader is SEQ::sv1.
17:13:46.720 View change completed; metadata: (epochNumber=52, blockNumber=2971), view: 1
17:13:47.040 View change completed; metadata: (epochNumber=52, blockNumber=2970), view: 1
17:14:06.811 Segment 2 starting at block number 2972 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv2.
17:14:10.512 View change completed; metadata: (epochNumber=52, blockNumber=2972), view: 1

```

## 6. The Canton process nearly stopped logging from 17:13:11 to 17:13:18

Lines per second in canton.clog (all synchronizer nodes and participants, one JVM) against the test log (separate
JVM). The Canton JVM dropped from about 10,000 lines/s to 46 to 305 lines/s (one burst at 17:13:13) while the test
JVM kept about half its rate; no gap over 1 s, so not a full stop. `canton.out.gz` and the runner artifact hold no GC
or resource data, so the cause (GC, CPU starvation on the runner, DB) is open.

```
$ for f in canton.clog.gz canton_network_test.clog.gz; do echo "== $f"; zcat log/11313/logs-resource-intensive-0/$f | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="17:13:08" && t<="17:13:22" {c[t]++} END{for(t in c) print t, c[t]}' | sort | tr '\n' ' '; echo; done
== canton.clog.gz
17:13:08 9606 17:13:09 10025 17:13:10 4131 17:13:11 53 17:13:12 691 17:13:13 2895 17:13:14 275 17:13:15 54 17:13:16 276 17:13:17 46 17:13:18 305 17:13:19 1674 17:13:20 1994 17:13:21 3157 17:13:22 2216 
== canton_network_test.clog.gz
17:13:08 1169 17:13:09 1181 17:13:10 1157 17:13:11 573 17:13:12 534 17:13:13 1296 17:13:14 590 17:13:15 508 17:13:16 581 17:13:17 506 17:13:18 557 17:13:19 903 17:13:20 926 17:13:21 797 17:13:22 717 

```

## 7. Frequency

resource-intensive (0) on main push runs of the post-merge workflow created since 2026-11-06 (#8234, DA BFT in all
tests): 1 failure (this run) in 22. The earlier hits of the same check, 11189 and 11191 (2026-11-04), were the
reference sequencer `insert block` storm (closed as duplicates of 11160).

```
$ awk '{print $5}' /tmp/claude-1000/-Users-stephencompall-ide-splice/194c8385-dd2b-4eb1-948c-0e2cdfae6615/scratchpad/g9/ri0.txt | sort | uniq -c; grep -v success /tmp/claude-1000/-Users-stephencompall-ide-splice/194c8385-dd2b-4eb1-948c-0e2cdfae6615/scratchpad/g9/ri0.txt; head -1 /tmp/claude-1000/-Users-stephencompall-ide-splice/194c8385-dd2b-4eb1-948c-0e2cdfae6615/scratchpad/g9/ri0.txt; tail -1 /tmp/claude-1000/-Users-stephencompall-ide-splice/194c8385-dd2b-4eb1-948c-0e2cdfae6615/scratchpad/g9/ri0.txt
      1 failure
     21 success
42658360509 91715b25c0 2026-11-09T16:47:50Z 129303308680 failure
42658360509 91715b25c0 2026-11-09T16:47:50Z 129303308680 failure
42199308951 9f88d4ce64 2026-11-06T10:00:32Z 127830914254 success

```

## Verdict

New, flake. Not 11255: no ordering topology change; not family L: DA BFT. A short stall of the whole Canton process
made every BFT segment time out, the following view changes and the blacklisting of sv4 (then sv3) kept ordering
slow for about a minute, and the close vote transaction committed 26 s after the 45 s check. Related shape without
topology change: 11305 (slow BFT ordering 15:21 to 15:26 on simtime (4) the same day, cause open).

Fix (test): the check has now failed three times in a week for two different ordering slowdowns; give it room for
one slow ordering minute, e.g. `actAndCheck(timeUntilSuccess = 90.seconds)` at
BootstrapPackageConfigIntegrationTest.scala:274. No assignee (no PR introduced this; the line predates #8234).

Not verified: what stalled the Canton JVM 17:13:11 to 17:13:18 (no GC log or runner metrics); why sv2's mediator
sent its result only at 17:14:20.2, 27 s after it finalized (likely its send waiting on the epoch 52 view changes, not
shown); the mediator result threshold of 2 is inferred from the participants finalizing right after the second result.
