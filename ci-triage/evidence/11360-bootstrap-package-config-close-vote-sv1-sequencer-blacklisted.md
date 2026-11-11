# 11360 - BootstrapPackageConfigIntegrationTest: close vote confirmations wait 38 s in the blacklisted sv1 sequencer, mediator timeout, close commits 30 s after the 45 s check (dup 11313) (run 43030349815)

Duplicate of 11313 (third main hit after 11313 and 11329). Post-merge main 2fc780a883 ("Pause
CloseVoteRequestTrigger on all SVs in the SvFrontendIntegrationTest expiring proposal step (#8417)"), job
`resource-intensive (0)`, canton 3.7.3-snapshot.20261109.20894.0.ve6e6c252, 3 of 4 tests passed. Same check
(BootstrapPackageConfigIntegrationTest.scala:291, 45 s budget at line 274) and the same trigger as 11329: the
vetting triggers of sv1, sv3, sv4 and the three validators upload 192 DARs from 14:01:21, the second the votes
finish, and the canton.clog line rate falls from about 10,000/s to 134/s at 14:01:23. Ordering topology size 4 and
unchanged activation (13:59:07.943046Z) through the failure, so not 11255. The detail differs from 11313/11329: all
four SV participants submit through globalSequencerSv1; its segment of epoch 51 timed out and sv1 was blacklisted
in epochs 52 to 54 (14:02:04.826 to 14:02:34.090). The confirmations for the first close attempts, sent to sv1's
sequencer at 14:01:55 to 56, were ordered only at 14:02:34.165, so all mediators rejected the close with
MEDIATOR_SAYS_TX_TIMED_OUT at 14:02:08; the retries were refused with SEQUENCER_OVERLOADED (blacklisted) until sv1
returned, and sv2's retry sent at 14:02:34.546 succeeded at 14:02:36.039, 29.6 s after the check failed at
14:02:06.481. Fix PR splice#8412 (90 s) is still open, so the sha does not contain it.

- Run: https://github.com/canton-network/splice/actions/runs/43030349815, main 2fc780a883 (#8417), job 130555891102
  `ci / scala_test_resource_intensive / resource-intensive (0)`, attempt 1 (artifact `logs-resource-intensive-0`,
  created 2026-11-11T14:07:39Z).
- Runtime canton: 3.7.3-snapshot.20261109.20894.0.ve6e6c252.
- Component: test (budget of the check); load from the test's own package vetting; BFT ordering in the shared
  Canton JVM.

## 1. Issue, job mapping and failing assertion

Issue body (verbatim):

```
$ gh api /repos/DACH-NY/cn-test-failures/issues/11360 --jq '"#\(.number) [\(.state)] \(.title)\n\(.body)"'
#11360 [open] GHA Run 43030349815 : job resource-intensive (0) in scala_test Failed :fire:

[GitHub Actions Run](https://github.com/canton-network/splice/actions/runs/43030349815).
Job: resource-intensive (0) in scala_test
Branch: [refs/heads/main](https://github.com/canton-network/splice/tree/refs/heads/main)
Workflow: CI post-merge to main or release-line branches
Commit: [2fc780a](https://github.com/canton-network/splice/commit/2fc780a883cbad8279b34400ee5999a2d1b681ff) Pause CloseVoteRequestTrigger on all SVs in the SvFrontendIntegrationTest expiring proposal step (#8417)
Actor: rautenrieth-da
```

No comments on the issue. Job resolution (exactly one match) and run:

```
$ RUN=43030349815; export NAME='resource-intensive (0)'; for p in 1 2 3; do gh api "repos/canton-network/splice/actions/runs/$RUN/jobs?filter=all&per_page=40&page=$p"; done | jq -r '.jobs[] | select((.name == env.NAME or (.name | endswith(" / " + env.NAME))) and .conclusion == "failure") | "\(.id) attempt=\(.run_attempt) \(.name)"'
130555891102 attempt=1 ci / scala_test_resource_intensive / resource-intensive (0)
$ gh api repos/canton-network/splice/actions/runs/$RUN --jq '"\(.name) \(.head_branch) \(.head_sha[0:10]) \(.created_at) \(.conclusion) attempts=\(.run_attempt)"'
CI post-merge to main or release-line branches main 2fc780a883 2026-11-11T13:34:29Z failure attempts=1
```

Job log (ANSI stripped, `log/inbox-1111b/130555891102.log`):

```
$ grep -a -E 'FAILED \*\*\*|Tests: succeeded|Run completed' $L | sed -E 's/^[^Z]*Z //' | sort -u
[info] *** 1 TEST FAILED ***
[info] - Bootstrap with specific versions and then upgrade to latest *** FAILED ***
[info] Run completed in 17 minutes, 44 seconds.
[info] Tests: succeeded 3, failed 1, canceled 0, ignored 0, pending 0
$ grep -a -A4 'FAILED \*\*\*' $L | sed -E 's/^[^Z]*Z //' | head -5
[info] - Bootstrap with specific versions and then upgrade to latest *** FAILED ***
[info]   Check observe AmuletRules with upgraded config for sv1-3 accept vote request for upgraded packages "0.1.[19]" was not equal to "0.1.[25]" (BootstrapPackageConfigIntegrationTest.scala:291)
[info]   org.scalatest.exceptions.TestFailedException:
[info]   at org.scalatest.matchers.MatchersHelper$.indicateFailure(MatchersHelper.scala:392)
[info]   at org.scalatest.matchers.should.Matchers$AnyShouldWrapper.shouldBe(Matchers.scala:7539)
```

## 2. The check, its budget and the canton pin at the run's sha

45 s budget at line 274, 19 s vote expiry at line 215, assertion at 291 (same lines as in 11313 and 11329). Fix PR
splice#8412 (90 s) is open and not merged, so the sha cannot contain it.

```
$ git show 2fc780a883:apps/app/src/test/scala/org/lfdecentralizedtrust/splice/integration/tests/BootstrapPackageConfigIntegrationTest.scala | grep -n -E 'timeUntilSuccess|RelTime\(19|observe AmuletRules|packageConfig.amulet shouldBe'
215:    val expiration = new RelTime(19_000_000)
274:      actAndCheck(timeUntilSuccess = 45.seconds)(
287:        "observe AmuletRules with upgraded config",
291:          newAmuletRules.payload.configSchedule.initialValue.packageConfig.amulet shouldBe DarResources.amulet.latest.metadata.version
345:      eventually(timeUntilSuccess = 2.minutes) {
446:        "observe AmuletRules with upgraded config",
$ git show 2fc780a883:nix/canton-sources.json | grep -m1 version
  "version": "3.7.3-snapshot.20261109.20894.0.ve6e6c252",
$ gh api /repos/canton-network/splice/pulls/8412 --jq '"#\(.number) \(.state) merged=\(.merged) \(.merged_at) \(.title) by \(.user.login)"'
#8412 open merged=false null Allow 90s for the BootstrapPackageConfig vote acceptance check by stephencompall-DA
```

## 3. Test timeline and the close transaction

Votes done 14:01:21.464, check failed 14:02:06.481. The vote request expired at about 14:01:37 (19 s);
CloseVoteRequestTrigger picked it up at 14:01:42.988. All four SVs sent DsoRules_CloseVoteRequest at 14:01:43.9 to
44.6; all four were rejected at 14:02:09.5 with MEDIATOR_SAYS_TX_TIMED_OUT; the retries from 14:02:15 to 14:02:33
were rejected with SEQUENCER_BACKPRESSURE; sv2's retry sent at 14:02:34.546 succeeded at 14:02:36.039, 29.6 s after
the check gave up.

```
$ A=log/11360/logs-resource-intensive-0
$ zcat $A/canton_network_test.clog.gz | grep -a 'BootstrapPackageConfigIntegrationTest:BootstrapPackageConfigIntegrationTest' | grep -aE '(Running|Finished|Failed) clue: \((act|check)\) (Creating vote|sv1-3 accept|observe Amulet)' | sed -E 's/\{"@timestamp":"([^"]+)","message":"([^"]*)".*/\1 \2/'
2026-11-11T14:01:17.468Z Running clue: (act) Creating vote request for upgraded packages
2026-11-11T14:01:18.588Z Finished clue: (act) Creating vote request for upgraded packages
2026-11-11T14:01:18.656Z Running clue: (act) sv1-3 accept vote request for upgraded packages
2026-11-11T14:01:21.464Z Finished clue: (act) sv1-3 accept vote request for upgraded packages
2026-11-11T14:01:21.464Z Running clue: (check) observe AmuletRules with upgraded config
2026-11-11T14:02:06.481Z Failed clue: (check) observe AmuletRules with upgraded config

$ zcat $A/canton_network_test.clog.gz | grep -a 'CloseVoteRequestTrigger' | grep -a -E 'Processing\\nReadyTask' | sed -E 's/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-80 | head -6
2026-11-11T14:01:42.988Z Processing\nReadyTask(\n  readyAt = 2026-11-11T14:01:37
2026-11-11T14:01:43.059Z Processing\nReadyTask(\n  readyAt = 2026-11-11T14:01:38
2026-11-11T14:01:43.236Z Processing\nReadyTask(\n  readyAt = 2026-11-11T14:01:38
2026-11-11T14:01:43.531Z Processing\nReadyTask(\n  readyAt = 2026-11-11T14:01:38
2026-11-11T14:06:49.094Z Processing\nReadyTask(\n  readyAt = 2026-11-11T14:06:44
2026-11-11T14:06:49.248Z Processing\nReadyTask(\n  readyAt = 2026-11-11T14:06:44
```

Close submissions per SV (the last column is the DsoRules choice in a sent request; failure messages cut at 200
characters by the command):

```
$ zcat $A/canton_network_test.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,1,23)} t>="2026-11-11T14:01:38" && t<="2026-11-11T14:03:30"' | grep -a 'SubmitAndWaitForTransaction' | grep -a -E 'sending request|succeeded|failed with' | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|capture("SV=(?<s>sv[0-9])")?.s // .logger_name[-40:]) \(."trace-id"[0:8]) \(.message|gsub("\\n";" ")|.[0:200]) \(.message|[scan("DsoRules_[A-Za-z]+")]|unique|join(","))"' | cut -c1-260
14:01:43.946 sv3 da663a3d Request (tid:3ce6f39669c3e04dedf346339f85cbfc) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:01:43.953 sv1 fc941bd7 Request (tid:e4fc0b3ac28c5912af87282c62103d3f) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:01:44.555 sv2 fa41619d Request (tid:c52b3ec94011ce6dbbd647024053f6b2) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:01:44.634 sv4 3aaf4d69 Request (tid:9fafd4226fd6582737821d596e67c493) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:02:09.532 sv3 da663a3d Request (tid:3ce6f39669c3e04dedf346339f85cbfc) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with ABORTED/MEDIATOR_SAYS_TX_TIMED_OUT(2,0): Rejected transa 
14:02:09.535 sv4 3aaf4d69 Request (tid:9fafd4226fd6582737821d596e67c493) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: failed with ABORTED/MEDIATOR_SAYS_TX_TIMED_OUT(2,0): Rejected transa 
14:02:09.537 sv2 fa41619d Request (tid:c52b3ec94011ce6dbbd647024053f6b2) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: failed with ABORTED/MEDIATOR_SAYS_TX_TIMED_OUT(2,0): Rejected transa 
14:02:09.540 sv1 fc941bd7 Request (tid:e4fc0b3ac28c5912af87282c62103d3f) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: failed with ABORTED/MEDIATOR_SAYS_TX_TIMED_OUT(2,0): Rejected transa 
14:02:14.963 sv3 da663a3d Request (tid:5eed08b8e8defdd4a2c8efabe20aa2d2) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:02:15.156 sv4 3aaf4d69 Request (tid:5b1ddaf9fe1514b2b0c4a74700896339) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:02:15.166 sv2 fa41619d Request (tid:78189854fd87ba204f255045ba883b00) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:02:15.168 sv1 fc941bd7 Request (tid:2671c213b05b6758683ace1e27719acd) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:02:15.454 sv3 da663a3d Request (tid:5eed08b8e8defdd4a2c8efabe20aa2d2) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with ABORTED/SEQUENCER_BACKPRESSURE(2,5eed08b8): The sequence 
14:02:15.641 sv2 fa41619d Request (tid:78189854fd87ba204f255045ba883b00) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: failed with ABORTED/SEQUENCER_BACKPRESSURE(2,78189854): The sequence 
14:02:15.646 sv4 3aaf4d69 Request (tid:5b1ddaf9fe1514b2b0c4a74700896339) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: failed with ABORTED/SEQUENCER_BACKPRESSURE(2,5b1ddaf9): The sequence 
14:02:15.729 sv1 fc941bd7 Request (tid:2671c213b05b6758683ace1e27719acd) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5101: failed with ABORTED/SEQUENCER_BACKPRESSURE(2,2671c213): The sequence 
...
14:02:33.540 sv4 3aaf4d69 Request (tid:4b6e4dfef8f233efb66de3d6a2ff6a50) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:02:33.681 sv4 3aaf4d69 Request (tid:4b6e4dfef8f233efb66de3d6a2ff6a50) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5401: failed with ABORTED/SEQUENCER_BACKPRESSURE(2,4b6e4dfe): The sequence 
14:02:34.546 sv2 fa41619d Request (tid:af66be1b49d8d5c5ff6aa90fa2e780ca) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: sending request SubmitAndWaitForTransactionRequest(   Commands(      DsoRules_CloseVoteRequest
14:02:36.039 sv2 fa41619d Request (tid:af66be1b49d8d5c5ff6aa90fa2e780ca) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: succeeded(OK)
```

(The `...` stands for 12 lines of further retries sent and failed with SEQUENCER_BACKPRESSURE at 14:02:23.145 to 14:02:32.157.)

Full text of the two rejection kinds (sv3):

```
$ zcat $A/canton_network_test.clog.gz | grep -a -E 'tid:(3ce6f39669c3e04dedf346339f85cbfc|5eed08b8e8defdd4a2c8efabe20aa2d2)\) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed' | jq -r '"\(."@timestamp"[11:23]) \(.message|gsub("\\n";" ")|gsub("  +";" ")|.[0:600])"' | cut -c1-330
14:02:09.532 Request (tid:3ce6f39669c3e04dedf346339f85cbfc) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with ABORTED/MEDIATOR_SAYS_TX_TIMED_OUT(2,0): Rejected transaction as the mediator did not receive sufficient confirmations within the expected timeframe. Trailers: Metadata(content-type=applicatio
14:02:15.454 Request (tid:5eed08b8e8defdd4a2c8efabe20aa2d2) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5301: failed with ABORTED/SEQUENCER_BACKPRESSURE(2,5eed08b8): The sequencer is overloaded. Trailers: Metadata(content-type=application/grpc,grpc-status-details-bin=CAoSQFNFUVVFTkNFUl9CQUNLUFJFU1NVUkUoMiw1ZWVkMDhiOCk6IFRoZSBzZXF1ZW5jZXIgaXMgb3Zlcm
```

## 4. sv3's first close request: confirmations sent at 14:01:54 to 55, never received, mediator timeout

sv3's request (sequenced as 2026-11-11T14:01:37.739409Z) reached the mediators at 14:01:54.0 to 55.4; all four
participants sent Approve confirmations at 14:01:54.535 to 14:01:55.666. No mediator logged `Phase 5` (received a
response) for this request; they timed out at 14:02:08.1 to 14:02:08.8 and rejected with MEDIATOR_SAYS_TX_TIMED_OUT.

```
$ zcat $A/canton.clog.gz | grep -a '3ce6f39669c3e04dedf346339f85cbfc' | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|split(":")[1]? // ""|split("/")[0]) \(.message|gsub("\\n";" ")|gsub("  +";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:180])"' | grep -a -E 'Phase 1 completed|sends request with id|Phase 2: Registered'
14:01:46.229 participant=sv3Participant Phase 1 completed: Submitting 3 envelopes for Transaction request, submitters digital-asset-eng-3-b283cdbe::1220354b6638..., command-id a6d163e1-70a9-4598-bd16-d7729e1d658a
14:01:46.243 sequencer=globalSequencerSv1 'PAR::sv3::1220354b6638...' sends request with id '1c5690d3-72b0-470e-8fca-baad29031648' of size 5628 bytes with 3 envelopes to MGR=1, PAR=4.
14:01:54.033 mediator=globalMediatorSv4 Phase 2: Registered request=2026-11-11T14:01:37.739409Z from submittingParticipant=PAR::sv3::1220354b6638... with 1 view(s).
14:01:54.034 mediator=globalMediatorSv2 Phase 2: Registered request=2026-11-11T14:01:37.739409Z from submittingParticipant=PAR::sv3::1220354b6638... with 1 view(s).
14:01:54.133 mediator=globalMediatorSv3 Phase 2: Registered request=2026-11-11T14:01:37.739409Z from submittingParticipant=PAR::sv3::1220354b6638... with 1 view(s).
14:01:55.439 mediator=globalMediatorSv1 Phase 2: Registered request=2026-11-11T14:01:37.739409Z from submittingParticipant=PAR::sv3::1220354b6638... with 1 view(s).

$ zcat $A/canton.clog.gz | grep -a '2026-11-11T14:01:37.739409Z' | grep -a -c 'Phase 5'
0
$ zcat $A/canton.clog.gz | grep -a '2026-11-11T14:01:37.739409Z' | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|split(":")[1]? // ""|split("/")[0]) \(.message|gsub("\\n";" ")|gsub("  +";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:200])"' | grep -a -E 'Phase 4: Sending|Phase 5|Phase 6|timed out|Timeout|Failed to send|Sent result|Finalizing|Rejected' | head -40
14:01:54.535 participant=sv2Participant Phase 4: Sending for request=2026-11-11T14:01:37.739409Z with msgId=7062d89a-8248-4ab4-95a6-2d864c5616aa approved=1, rejected=0, abstained=0
14:01:55.476 participant=sv1Participant Phase 4: Sending for request=2026-11-11T14:01:37.739409Z with msgId=6720845a-9a26-4304-8a6f-4fd01d621e6f approved=1, rejected=0, abstained=0
14:01:55.613 participant=sv4Participant Phase 4: Sending for request=2026-11-11T14:01:37.739409Z with msgId=c147fc25-5526-475d-ad7a-ac931db27edd approved=1, rejected=0, abstained=0
14:01:55.666 participant=sv3Participant Phase 4: Sending for request=2026-11-11T14:01:37.739409Z with msgId=b695d074-7fd9-415a-b41e-f88abe89260e approved=1, rejected=0, abstained=0
14:02:08.109 mediator=globalMediatorSv3 Phase 6: Request 2026-11-11T14:01:37.739409Z: Timeout in state Right(Map(ViewPosition("") -> ViewState( quorumsState = Seq( Quorum(confirmers = Map(DSO-b283cdbe-b283cdbe::12203ef5c974... -> 1, digital
14:02:08.118 mediator=globalMediatorSv3 Phase 6: Finalized request=RequestId(2026-11-11T14:01:37.739409Z) with verdict MediatorReject( DecodedCantonError( code = 'MEDIATOR_SAYS_TX_TIMED_OUT', category = ContentionOnSharedResources, cause = 
14:02:08.213 mediator=globalMediatorSv2 Phase 6: Request 2026-11-11T14:01:37.739409Z: Timeout in state Right(Map(ViewPosition("") -> ViewState( quorumsState = Seq( Quorum(confirmers = Map(DSO-b283cdbe-b283cdbe::12203ef5c974... -> 1, digital
14:02:08.223 mediator=globalMediatorSv2 Phase 6: Finalized request=RequestId(2026-11-11T14:01:37.739409Z) with verdict MediatorReject( DecodedCantonError( code = 'MEDIATOR_SAYS_TX_TIMED_OUT', category = ContentionOnSharedResources, cause = 
14:02:08.271 mediator=globalMediatorSv3 Sent result for request 2026-11-11T14:01:37.739409Z
14:02:08.651 participant=sv1Participant Response message for request [RequestId(2026-11-11T14:01:37.739409Z)] timed out at 2026-11-11T14:02:08.234842Z
14:02:08.651 participant=sv3Participant Response message for request [RequestId(2026-11-11T14:01:37.739409Z)] timed out at 2026-11-11T14:02:08.234842Z
14:02:08.651 participant=sv4Participant Response message for request [RequestId(2026-11-11T14:01:37.739409Z)] timed out at 2026-11-11T14:02:08.234842Z
14:02:08.656 participant=sv2Participant Response message for request [RequestId(2026-11-11T14:01:37.739409Z)] timed out at 2026-11-11T14:02:08.234842Z
14:02:08.757 mediator=globalMediatorSv4 Phase 6: Request 2026-11-11T14:01:37.739409Z: Timeout in state Right(Map(ViewPosition("") -> ViewState( quorumsState = Seq( Quorum(confirmers = Map(DSO-b283cdbe-b283cdbe::12203ef5c974... -> 1, digital
14:02:08.762 mediator=globalMediatorSv4 Phase 6: Finalized request=RequestId(2026-11-11T14:01:37.739409Z) with verdict MediatorReject( DecodedCantonError( code = 'MEDIATOR_SAYS_TX_TIMED_OUT', category = ContentionOnSharedResources, cause = 
14:02:09.260 mediator=globalMediatorSv4 Sent result for request 2026-11-11T14:01:37.739409Z
14:02:09.336 participant=sv3Participant Request is finalized as rejected. DecodedCantonError( code = 'MEDIATOR_SAYS_TX_TIMED_OUT', category = ContentionOnSharedResources, cause = "Rejected transaction as the mediator did not receive suffici
...
14:02:34.346 mediator=globalMediatorSv1 Phase 6: Request 2026-11-11T14:01:37.739409Z: Timeout in state Right(Map(ViewPosition("") -> ViewState( quorumsState = Seq( Quorum(confirmers = Map(DSO-b283cdbe-b283cdbe::12203ef5c974... -> 1, digital
14:02:34.355 mediator=globalMediatorSv1 Phase 6: Finalized request=RequestId(2026-11-11T14:01:37.739409Z) with verdict MediatorReject( DecodedCantonError( code = 'MEDIATOR_SAYS_TX_TIMED_OUT', category = ContentionOnSharedResources, cause =
```

(The `...` stands for 10 lines: the `finalized as rejected` of sv4, sv2 and sv1, the `Finalizing Transaction` of all four
participants and the wrap-up, publication and storage of the rejection by sv3.)

## 5. Where the confirmations waited: globalSequencerSv1, 38 s from submission to ordering

All four confirmations were submitted to globalSequencerSv1 at 14:01:55.2 to 56.2 and ordered in block 3221 of
epoch 55 at 14:02:34.165, 26 s after the mediators had given up.

```
$ for m in 7062d89a-8248-4ab4-95a6-2d864c5616aa 6720845a-9a26-4304-8a6f-4fd01d621e6f c147fc25-5526-475d-ad7a-ac931db27edd b695d074-7fd9-415a-b41e-f88abe89260e; do zcat $A/canton.clog.gz | grep -a "$m" | jq -r '"\(."@timestamp"[11:23]) \(.logger_name|split(":")[1]? // ""|split("/")[0]) \(.message|gsub("\\n";" ")|gsub("  +";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:220])"' | grep -a -E 'sends request|BFT ordering request at index|refused|Refused|failed|overload|blacklisted|BACKPRESSURE' | awk '!s[$2" "substr($3,1,10)]++' | head -6; echo; done
14:01:55.245 sequencer=globalSequencerSv1 'PAR::sv2::122013fae424...' sends request with id '7062d89a-8248-4ab4-95a6-2d864c5616aa' of size 737 bytes with 1 envelopes to MGR=1.
14:02:34.165 sequencer=globalSequencerSv1 BFT ordering request at index 9 in output block (epochNumber=55, blockNumber=3221) with message ID 7062d89a-8248-4ab4-95a6-2d864c5616aa may change ordering topology: false
14:02:34.168 sequencer=globalSequencerSv4 BFT ordering request at index 9 in output block (epochNumber=55, blockNumber=3221) with message ID 7062d89a-8248-4ab4-95a6-2d864c5616aa may change ordering topology: false
14:02:34.169 sequencer=globalSequencerSv3 BFT ordering request at index 9 in output block (epochNumber=55, blockNumber=3221) with message ID 7062d89a-8248-4ab4-95a6-2d864c5616aa may change ordering topology: false
14:02:34.169 sequencer=globalSequencerSv2 BFT ordering request at index 9 in output block (epochNumber=55, blockNumber=3221) with message ID 7062d89a-8248-4ab4-95a6-2d864c5616aa may change ordering topology: false

14:01:55.662 sequencer=globalSequencerSv1 'PAR::sv1::1220c3b091fb...' sends request with id '6720845a-9a26-4304-8a6f-4fd01d621e6f' of size 734 bytes with 1 envelopes to MGR=1.
14:02:34.165 sequencer=globalSequencerSv1 BFT ordering request at index 10 in output block (epochNumber=55, blockNumber=3221) with message ID 6720845a-9a26-4304-8a6f-4fd01d621e6f may change ordering topology: false
...

14:01:56.238 sequencer=globalSequencerSv1 'PAR::sv4::1220aabb41ac...' sends request with id 'c147fc25-5526-475d-ad7a-ac931db27edd' of size 730 bytes with 1 envelopes to MGR=1.
14:02:34.166 sequencer=globalSequencerSv1 BFT ordering request at index 15 in output block (epochNumber=55, blockNumber=3221) with message ID c147fc25-5526-475d-ad7a-ac931db27edd may change ordering topology: false
...

14:01:56.241 sequencer=globalSequencerSv1 'PAR::sv3::1220354b6638...' sends request with id 'b695d074-7fd9-415a-b41e-f88abe89260e' of size 763 bytes with 1 envelopes to MGR=1.
14:02:34.166 sequencer=globalSequencerSv1 BFT ordering request at index 16 in output block (epochNumber=55, blockNumber=3221) with message ID b695d074-7fd9-415a-b41e-f88abe89260e may change ordering topology: false
...
```

(Each `...` stands for the same output-block line from the other three sequencers, 14:02:34.169 to .171.)

Every submission of the four SV participants in the window went through globalSequencerSv1:

```
$ zcat $A/canton.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="14:01:40" && t<="14:02:40"' | grep -a "sends request with id" | jq -r '"\(.logger_name|capture("sequencer=(?<s>[A-Za-z0-9]+)")?.s // "-") \(.message|capture("^.(?<p>PAR::sv[0-9])")?.p // "other")"' | sort | uniq -c
     30 globalSequencerSv1 PAR::sv1
     26 globalSequencerSv1 PAR::sv2
     39 globalSequencerSv1 PAR::sv3
     28 globalSequencerSv1 PAR::sv4
     63 globalSequencerSv1 other
     12 globalSequencerSv2 other
     12 globalSequencerSv3 other
     23 globalSequencerSv4 other
      6 splitwellSequencer other
      5 splitwellUpgradeSequencer other
```

## 6. BFT: segment timeouts from 14:01:27, sv4 then sv1 blacklisted, ordering topology unchanged

Epoch 50 had local timeouts on three segments (14:01:27.2 to 14:01:45.8), epoch 51 started with sv4 blacklisted and
one segment timed out at 14:01:53.674; epochs 52 to 54 (14:02:04.826 to 14:02:34.090) ran with sv1 blacklisted.
Epoch 55 started at 14:02:34.090 with an empty blacklist, and block 3221 with the queued confirmations was output
75 ms later (section 5).

```
$ zcat $A/canton.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="14:01:15" && t<="14:02:50"' | grep -a 'globalSequencerSv1' | grep -a -E 'after reaching local timeout|New epoch [0-9]+ has started' | jq -r '"\(."@timestamp"[11:23]) \(.message|gsub("\\n";" ")|gsub("1220[0-9a-f]{60}";"..")|gsub("  +";" ")|sub("; ordering topology.*";"")|.[0:200])"'
14:01:15.095 New epoch 49 has started with leaders = List(SEQ::sv2::..0a59, SEQ::sv3::..debf, SEQ::sv4::..96c0, SEQ::sv1::..0094)and blacklisted nodes = List()
14:01:19.221 New epoch 50 has started with leaders = List(SEQ::sv3::..debf, SEQ::sv4::..96c0, SEQ::sv1::..0094, SEQ::sv2::..0a59)and blacklisted nodes = List()
14:01:27.158 Segment 0 starting at block number 2820 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv4::..96c0.
14:01:27.231 Segment 3 starting at block number 2823 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv3::..debf.
14:01:45.837 Segment 1 starting at block number 2821 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv1::..0094.
14:01:48.629 New epoch 51 has started with leaders = List(SEQ::sv1::..0094, SEQ::sv2::..0a59, SEQ::sv3::..debf)and blacklisted nodes = List(SEQ::sv4::..96c0)
14:01:53.674 Segment 0 starting at block number 2900 moving from view number 0 to 1 after reaching local timeout. New leader is SEQ::sv2::..0a59.
14:02:04.826 New epoch 52 has started with leaders = List(SEQ::sv3::..debf, SEQ::sv4::..96c0, SEQ::sv2::..0a59)and blacklisted nodes = List(SEQ::sv1::..0094)
14:02:13.433 New epoch 53 has started with leaders = List(SEQ::sv4::..96c0, SEQ::sv2::..0a59, SEQ::sv3::..debf)and blacklisted nodes = List(SEQ::sv1::..0094)
14:02:23.441 New epoch 54 has started with leaders = List(SEQ::sv2::..0a59, SEQ::sv3::..debf, SEQ::sv4::..96c0)and blacklisted nodes = List(SEQ::sv1::..0094)
14:02:34.090 New epoch 55 has started with leaders = List(SEQ::sv4::..96c0, SEQ::sv1::..0094, SEQ::sv2::..0a59, SEQ::sv3::..debf)and blacklisted nodes = List()
14:02:38.416 New epoch 56 has started with leaders = List(SEQ::sv1::..0094, SEQ::sv2::..0a59, SEQ::sv3::..debf, SEQ::sv4::..96c0)and blacklisted nodes = List()
14:02:44.370 New epoch 57 has started with leaders = List(SEQ::sv2::..0a59, SEQ::sv3::..debf, SEQ::sv4::..96c0, SEQ::sv1::..0094)and blacklisted nodes = List()
14:02:50.229 New epoch 58 has started with leaders = List(SEQ::sv3::..debf, SEQ::sv4::..96c0, SEQ::sv1::..0094, SEQ::sv2::..0a59)and blacklisted nodes = List()

$ zcat $A/canton.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="14:01:15" && t<="14:02:50"' | grep -a 'globalSequencerSv1' | grep -a -E 'New epoch [0-9]+ has started' | jq -r '.message|gsub("\\n";" ")|gsub("  +";" ")|capture("activationTime = (?<a>[^,]*), size = (?<s>[0-9]+)")|"\(.a) size=\(.s)"' | sort | uniq -c
      8 2026-11-11T13:59:07.943046Z size=4
      2 2026-11-11T14:02:43.962852Z size=4
```

(The new activation 14:02:43.962852Z applies from epoch 57, after the check had failed and the close had committed.)

The retries were refused because sv1's sequencer was blacklisted; the participant maps the refusal to
SEQUENCER_BACKPRESSURE:

```
$ zcat $A/canton.clog.gz | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="14:02:14" && t<="14:02:16"' | grep -a 'participant=sv3Participant' | grep -a -E 'BACKPRESSURE|blacklisted|overloaded' | jq -r '"\(."@timestamp"[11:23]) \(.level) \(.message|gsub("\\n";" ")|gsub("  +";" ")|gsub("1220[0-9a-f]{60}";"..")|.[0:400])"' | head -5 | cut -c1-260
14:02:15.151 INFO Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,68638ca4): Mempool received client request but this node is currently blacklisted, rejecting Request: send-async-versioned/72a5a7f6-e1fb-4d1c-9793-83fb82ebf5d7 DecodedCantonError( code = '
14:02:15.152 DEBUG Send request with message id 72a5a7f6-e1fb-4d1c-9793-83fb82ebf5d7 was refused by SEQ::sv1::12208e8aed19... because it is overloaded
14:02:15.152 DEBUG Cancelling the pending send as submission failed synchronously with refused error: SEQUENCER_OVERLOADED(2,0): Mempool received client request but this node is currently blacklisted, rejecting
14:02:15.152 WARN Failed to submit transaction due to Error(RequestRefused( SendAsyncErrorGrpc( Request failed for server-DefaultSequencer-0. GrpcRequestRefusedByServer: ABORTED/SEQUENCER_OVERLOADED(2,68638ca4): Mempool received client request but this node is currently blacklisted, rejecting Request: send-async-versioned/72a5a7f6-e1fb-4d1c-9793-83fb82ebf5d7 DecodedCantonError( code = 'SEQUENCER_OVERLOADED', catego
14:02:15.152 INFO SEQUENCER_BACKPRESSURE(2,68638ca4): The sequencer is overloaded.
```

## 7. Onset: the vetting-trigger DAR upload burst and the Canton log rate collapse at 14:01:22

Same as 11329 section 7: 35 DAR uploads per SV (sv1, sv3, sv4) and 29 per validator, 192 in all, from 14:01:21 (the
second the votes finished) to 14:02:00. canton.clog falls from 10,300 lines/s (14:01:20) to 1327 and 134 lines/s at
14:01:22 to 23 and stays mostly under 3,000/s until 14:02:33; the test JVM keeps about half its rate.

```
$ zcat $A/canton_network_test.clog.gz | grep -a 'PackageService/UploadDar' | grep -a 'sending request' | jq -r '"\(."@timestamp"[11:19]) \(.logger_name|capture("/(?<a>(SV|validator)=[A-Za-z0-9]+)")?.a // "test")"' | awk '$1>="14:00:30" && $1<="14:03:30" {c[$2]++; if(!f[$2]) f[$2]=$1; l[$2]=$1} END{for(k in c) print k, c[k], f[k], l[k]}' | sort
SV=sv1 35 14:01:21 14:01:56
SV=sv3 35 14:01:22 14:01:57
SV=sv4 35 14:01:24 14:02:00
validator=aliceValidator 29 14:01:21 14:01:47
validator=bobValidator 29 14:01:24 14:01:55
validator=splitwellValidator 29 14:01:24 14:01:50

$ for f in canton.clog.gz canton_network_test.clog.gz; do echo "== $f"; zcat $A/$f | awk -F'"@timestamp":"' '{t=substr($2,12,8)} t>="14:01:15" && t<="14:02:40" {c[t]++} END{for(t in c) print t, c[t]}' | sort | tr '\n' ' '; echo; done
== canton.clog.gz
14:01:15 12483 14:01:16 10125 14:01:17 11180 14:01:18 10439 14:01:19 10431 14:01:20 10300 14:01:21 7384 14:01:22 1327 14:01:23 134 14:01:24 1718 14:01:25 347 14:01:26 655 14:01:27 4712 14:01:28 2812 14:01:29 129 14:01:30 218 14:01:31 348 14:01:32 2610 14:01:33 2760 14:01:34 662 14:01:35 1648 14:01:36 447 14:01:37 1368 14:01:38 1324 14:01:39 190 14:01:40 2697 14:01:41 1885 14:01:42 612 14:01:43 533 14:01:44 542 14:01:45 818 14:01:46 1868 14:01:47 1302 14:01:48 1033 14:01:49 1130 14:01:50 1587 14:01:51 1112 14:01:52 521 14:01:53 3644 14:01:54 2698 14:01:55 1760 14:01:56 2152 14:01:57 8712 14:01:58 2393 14:01:59 3313 14:02:00 4305 14:02:01 3598 14:02:02 3792 14:02:03 3529 14:02:04 4879 14:02:05 4298 14:02:06 4209 14:02:07 4884 14:02:08 7739 14:02:09 5151 14:02:10 3542 14:02:11 2958 14:02:12 3512 14:02:13 2927 14:02:14 2935 14:02:15 4307 14:02:16 3821 14:02:17 4344 14:02:18 4765 14:02:19 4353 14:02:20 3080 14:02:21 2933 14:02:22 3570 14:02:23 2154 14:02:24 3091 14:02:25 3375 14:02:26 4302 14:02:27 3817 14:02:28 4301 14:02:29 2884 14:02:30 3207 14:02:31 3385 14:02:32 3055 14:02:33 4017 14:02:34 12848 14:02:35 9629 14:02:36 8829 14:02:37 10202 14:02:38 9331 14:02:39 7138 14:02:40 6335 
== canton_network_test.clog.gz
14:01:15 1104 14:01:16 1253 14:01:17 1164 14:01:18 1282 14:01:19 1216 14:01:20 1220 14:01:21 1309 14:01:22 770 14:01:23 542 14:01:24 995 14:01:25 621 14:01:26 720 14:01:27 1232 14:01:28 713 14:01:29 538 14:01:30 511 14:01:31 632 14:01:32 1058 14:01:33 1046 14:01:34 653 14:01:35 838 14:01:36 625 14:01:37 763 14:01:38 737 14:01:39 541 14:01:40 1008 14:01:41 985 14:01:42 801 14:01:43 675 14:01:44 664 14:01:45 703 14:01:46 966 14:01:47 754 14:01:48 697 14:01:49 647 14:01:50 807 14:01:51 753 14:01:52 638 14:01:53 1147 14:01:54 676 14:01:55 886 14:01:56 782 14:01:57 1042 14:01:58 963 14:01:59 1147 14:02:00 998 14:02:01 932 14:02:02 957 14:02:03 917 14:02:04 873 14:02:05 886 14:02:06 1138 14:02:07 1465 14:02:08 1012 14:02:09 1055 14:02:10 836 14:02:11 882 14:02:12 934 14:02:13 877 14:02:14 825 14:02:15 904 14:02:16 959 14:02:17 928 14:02:18 890 14:02:19 849 14:02:20 888 14:02:21 881 14:02:22 851 14:02:23 888 14:02:24 874 14:02:25 950 14:02:26 1210 14:02:27 1210 14:02:28 876 14:02:29 851 14:02:30 895 14:02:31 960 14:02:32 863 14:02:33 923 14:02:34 3535 14:02:35 1174 14:02:36 2241 14:02:37 331 14:02:38 9 14:02:39 69 14:02:40 127 
```

## 8. Duplicate check and frequency

Tracker search for the suite: the open parent is 11313 (same check, same BFT stall without topology change,
assigned to stephencompall-DA, fix PR splice#8412 open). 11329 was closed as its duplicate. The other hits are
different failures of the same suite: 11255 (topology change window; here size 4 and unchanged until after the
failure), 10676/10686 (unvetting after execution, fixed), 10357/9598 ("The O..." operation errors), 10144/10145/9639
(GetPreferredPackages), 8179 (April, 30 s budget, fixed by #5214), older ones by title.

```
$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "BootstrapPackageConfigIntegrationTest"' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:120])"' | head -12
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

$ gh api -X GET search/issues -f q='repo:DACH-NY/cn-test-failures is:issue "resource-intensive (0)" created:>=2026-11-04' --jq '.items[] | "#\(.number) \(.state)/\(.state_reason // "-") \(.created_at[0:10]) \(.title[0:120])"'
#11360 open/- 2026-11-11 GHA Run 43030349815 : job resource-intensive (0) in scala_test Failed :fire:
#11313 open/- 2026-11-09 BootstrapPackageConfigIntegrationTest: close vote commits after the 45 s check, BFT view changes and blacklisting withou
#11329 closed/duplicate 2026-11-10 GHA Run 42790498517 : job resource-intensive (0) in scala_test Failed :fire:
#11191 closed/duplicate 2026-11-04 GHA Run 41881231793 : job resource-intensive (0) in scala_test Failed :fire:
#11189 closed/duplicate 2026-11-04 GHA Run 41847691151 : job resource-intensive (0) in scala_test Failed :fire:
#11223 closed/completed 2026-11-05 nix setup fails, GitHub 504 downloading the lnav release zip
```

Same failure, counted: 11313, 11329, 11360 (all after #8234, DA BFT). 11189 and 11191 are the same check under the
reference sequencer storm (family L, before #8234), not counted. 11223 is nix setup.

resource-intensive (0) on main push runs of the post-merge workflow created since 2026-11-06 (DA BFT in all tests
since #8234): 3 failures in 55 runs (first run 2026-11-06T10:00:32Z, last 2026-11-11T18:30:00Z), and the three
failed runs are those of 11313 (42658360509), 11329 (42790498517) and this issue. Every job completed.

```
$ for p in 1 2 3 4 5 6 7 8; do for i in 1 2 3; do out=$(gh api "repos/canton-network/splice/actions/runs?branch=main&event=push&created=>=2026-11-06&per_page=40&page=$p" 2>/dev/null) && break; sleep 3; done; echo "$out" | jq -r '.workflow_runs[] | select(.name|test("post-merge")) | "\(.id) \(.head_sha[0:10]) \(.created_at)"'; done | sort -u > log/11360/runs.txt; wc -l < log/11360/runs.txt
55
$ # per run: pages of jobs?per_page=40&page=N (3 retries each) until the job ending in "/ resource-intensive (0)" is found, printing "run sha job conclusion started_at" to log/11360/ri0.txt
$ awk '{print $4}' log/11360/ri0.txt | sort | uniq -c; grep -v ' success ' log/11360/ri0.txt
      3 failure
     52 success
42658360509 91715b25c0 129303308680 failure 2026-11-09T16:48:50Z
42790498517 6c0fee0505 129742730691 failure 2026-11-10T09:26:51Z
43030349815 2fc780a883 130555891102 failure 2026-11-11T13:38:48Z
```

## Verdict

Duplicate of 11313 (third main hit, after 11329), flake, test budget. The test's own vetting burst (192 DAR uploads
into the shared Canton JVM from 14:01:21) starts a BFT slowdown with an unchanged ordering topology; this time sv1's
sequencer, which carries every SV participant's submissions, was blacklisted for 30 s (epochs 52 to 54), so the
close vote's confirmations sat 38 s in its mempool, the mediators timed out the first close at 14:02:08 and the
retries were refused until sv1 returned. The close committed at 14:02:36.039, 29.6 s after the 45 s check
(14:02:06.481), 74.6 s after the votes finished: inside the 90 s of PR splice#8412.

Fix: unchanged, merge splice#8412 (`actAndCheck(timeUntilSuccess = 90.seconds)` at
BootstrapPackageConfigIntegrationTest.scala:274; stephencompall-DA, already assigned on 11313). Margin: 74.6 s here,
about 73 s in 11329 (votes 09:51:04.2, commit 09:52:17.2), so 90 s covers all three hits but not by much; a longer
blacklist would exceed it.

Not verified: what stalls the Canton JVM at 14:01:22 (no CPU or GC data; the onset matches the first DAR uploads to
the second, as in 11313 and 11329). Why the four SV participants all submit through globalSequencerSv1 (connection
config not checked); only their submissions in the window were counted. No passing run's artifact was examined.
