# 10273 - SvFrontendIntegrationTest "Offboard SV": sv2's vote never shows "Vote successfully updated!" because the castVote transaction waited 38 s in globalSequencerSv1's `insert block` 40001 retry chain (run 37066646506)

Family L (reference sequencer `insert block` SQLSTATE 40001 retry storm), fifth occurrence, and the same
single-sequencer shape as 10271, here in a wall-clock frontend shard. main 4a7f355b17, job
`frontend-wall-clock-time (2)`, 35 of 36 tests passed (2 ignored). In "Offboard SV", sv2 votes to accept through
the SV UI at 22:11:48.116, and the clue "the vote submission success message is shown" fails after 20 s at
22:12:08.119. On the backend, sv2's sv-app received `POST /api/sv/v0/admin/sv/votes` at 22:11:48.999 and submitted the
castVote command at 22:11:49.018. sv2Participant sent the confirmation request (`3ebeeee1`) to globalSequencerSv1 at
22:11:49.044. globalSequencerSv1's own `insert block` was in a 40001 retry chain from 22:11:43.688 to 22:12:27.239:
11 failures, backoff growing to 8.946 s. The other three sequencers, which backed off at most 1.224 s, kept
writing, and all four read blocks 780-1027 during the window. The request landed in block 1029 at 22:12:27.303.
The HTTP call had already timed out at the sv-app's 38 s limit (WARN at 22:12:27.012); the command itself succeeded
at 22:12:28.101.

- Run: https://github.com/canton-network/splice/actions/runs/37066646506, main 4a7f355b17 ("support eventual
  consistency for bulk storage staging (#7509)"), job 111037102736
  `ci / scala_test_frontend_wall_clock_time / frontend-wall-clock-time (2)`. Only failed job, one attempt.
- Runtime canton: 3.6.0-snapshot.20261001.20345.0.v85a9270a (`git show 4a7f355b17:nix/canton-sources.json | grep -m1 '"version"'`).
- Component: Canton reference sequencer / test infra (contention); not the SV UI.
- Artifact: `logs-frontend-wall-clock-time-2` in `log/10273/logs-frontend-wall-clock-time-2/`.

## 1. Failing assertion

```
$ sed 's/\x1b\[[0-9;]*[mJK]//g' log/10273/job.log | grep -a -E '\*\*\* FAILED|Check the vote submission|Tests: succeeded|\[error\] \s+org' | sed -E 's/^[^Z]*Z //' | awk '!s[$0]++' | cut -c1-260
[info] - should Offboard SV *** FAILED ***
[info]   Check the vote submission success message is shown for sv2 fills out and submits a vote to accept The partial function passed as the second parameter to inside was not defined at the value passed as the first parameter to inside, which was: None (SvFr
[info] Tests: succeeded 35, failed 1, canceled 0, ignored 2, pending 0
[info] - Offboard SV *** FAILED ***
[error] 	org.lfdecentralizedtrust.splice.integration.tests.SvFrontendIntegrationTest
```

`SvFrontendIntegrationTest.scala:459` is `inside(find(testId("vote-submission-success"))) { case Some(...) => ... }`
in `sv2CastVoteOnActionRequired`. The second `Offboard SV` line is the summary repeat. The `Creating proposal:
SRARC_OffboardSv` at 22:12:08.333 below is the next test, "Offboard SV with custom effective date", which passed.

## 2. Test timeline: the check ran its 20 s

```
$ zcat log/10273/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T22:11:39" and ."@timestamp" <= "2026-10-02T22:12:09" and (.logger_name|test("SvFrontendIntegrationTest(/config=[0-9a-f]+)?$")) and (.message|test("^(Running|Finished|Failed) clue: (Creating proposal|\\((act|check)\\) sv2|\\(check\\) the vote)"))) | "\(."@timestamp") \(.message)"'
2026-10-02T22:11:39.051Z Running clue: Creating proposal: SRARC_OffboardSv
2026-10-02T22:11:46.126Z Running clue: (act) sv2 operator can login and browse to the proposal details page
2026-10-02T22:11:47.701Z Finished clue: (act) sv2 operator can login and browse to the proposal details page
2026-10-02T22:11:47.701Z Running clue: (check) sv2 can see the vote form
2026-10-02T22:11:47.863Z Finished clue: (check) sv2 can see the vote form
2026-10-02T22:11:47.864Z Running clue: (act) sv2 fills out and submits a vote to accept
2026-10-02T22:11:48.116Z Finished clue: (act) sv2 fills out and submits a vote to accept
2026-10-02T22:11:48.116Z Running clue: (check) the vote submission success message is shown
2026-10-02T22:12:08.119Z Failed clue: (check) the vote submission success message is shown
2026-10-02T22:12:08.282Z Failed clue: Creating proposal: SRARC_OffboardSv
2026-10-02T22:12:08.333Z Running clue: Creating proposal: SRARC_OffboardSv
```

## 3. sv2's sv-app: the castVote command takes 39 s, the HTTP call times out at 38 s

```
$ zcat log/10273/logs-frontend-wall-clock-time-2/canton_network_test.clog.gz | jq -r 'select(."trace-id"=="7051c6c9e493dd62e2708f0302099da3" and (.message|test("received request|operation= castVote|SubmitAndWaitForTransaction to .*: (sending request|succeeded)|timeout after|Responding with"))) | "\(."@timestamp") \(.level) \(.message|sub(" from \\([^)]*\\)";"")|.[0:150])"'
2026-10-02T22:11:48.999Z DEBUG HTTP POST /api/sv/v0/admin/sv/votes: received request.
2026-10-02T22:11:49.000Z DEBUG HTTP Request no source UI header: service = svOperator, operation= castVote
2026-10-02T22:11:49.018Z DEBUG Request (tid:4d18b4e3e97e3fe3ae61a8f5bb990f32) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: sending request Su
2026-10-02T22:12:27.012Z WARN Request to http://localhost:5214/api/sv/v0/admin/sv/votes (POST) resulted in a timeout after 38 seconds.
2026-10-02T22:12:28.101Z DEBUG Request (tid:4d18b4e3e97e3fe3ae61a8f5bb990f32) com.daml.ledger.api.v2.CommandService/SubmitAndWaitForTransaction to 127.0.0.1:5201: succeeded(OK)
2026-10-02T22:12:28.142Z DEBUG HTTP POST /api/sv/v0/admin/sv/votes: Responding with status code: 201 Created
```

## 4. The confirmation request: sent to globalSequencerSv1 at 22:11:49.044, sequenced in block 1029 at 22:12:27

```
$ zcat log/10273/logs-frontend-wall-clock-time-2/canton.clog.gz | jq -r 'select((.message|test("3ebeeee1-25d7-4522-8ea7-02876d340b1d")) and (.logger_name|test("GrpcSequencerService:sequencer=globalSequencerSv1|SequencedSubmissionsValidator:sequencer=globalSequencerSv1/|RichSequencerClientImpl:participant=sv2Participant/")) and (.message|test("sends request|validated to|Sending message ID"))) | "\(."@timestamp") \(.logger_name|sub(":.*";"")|sub(".*\\.";"")) \(.message|.[0:150])"'
2026-10-02T22:11:49.043Z RichSequencerClientImpl Sending message ID 3ebeeee1-25d7-4522-8ea7-02876d340b1d to sequencer SEQ::sv1::12205039166f...
2026-10-02T22:11:49.044Z GrpcSequencerService 'PAR::sv2::1220d1505263...' sends request with id '3ebeeee1-25d7-4522-8ea7-02876d340b1d' of size 4997 bytes with 3 envelopes.
2026-10-02T22:12:27.303Z SequencedSubmissionsValidator At block 1029, the submission request 3ebeeee1-25d7-4522-8ea7-02876d340b1d at 2026-10-02T22:12:27.158429Z validated to: Deliver
```

## 5. globalSequencerSv1: one `insert block` chain covers the whole wait

```
$ zcat log/10273/logs-frontend-wall-clock-time-2/canton.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T22:11:43" and ."@timestamp" <= "2026-10-02T22:12:27.5" and (.logger_name|test("(DbStorageSingle|ReferenceSequencerDriver):sequencer=globalSequencerSv1/")) and (.message|test("insert block. has failed with an exception. Retrying after|SQL state: 40001|^Stored batch"))) | "\(."@timestamp") \(.message|gsub("\n";" ")|.[0:100])"' | uniq -c -f1
      2 2026-10-02T22:11:43.088Z Stored batch of requests
      1 2026-10-02T22:11:43.688Z Detected an SQLException. SQL state: 40001, error code: 0
      1 2026-10-02T22:11:43.965Z The operation 'insert block' has failed with an exception. Retrying after 0.05s. 
      1 2026-10-02T22:11:44.072Z The operation 'insert block' has failed with an exception. Retrying after 0.115s. 
      1 2026-10-02T22:11:44.274Z The operation 'insert block' has failed with an exception. Retrying after 0.349s. 
      1 2026-10-02T22:11:44.754Z The operation 'insert block' has failed with an exception. Retrying after 0.786s. 
      1 2026-10-02T22:11:46.714Z The operation 'insert block' has failed with an exception. Retrying after 1.561s. 
      1 2026-10-02T22:11:49.006Z The operation 'insert block' has failed with an exception. Retrying after 2.776s. 
      1 2026-10-02T22:11:51.795Z The operation 'insert block' has failed with an exception. Retrying after 4.186s. 
      1 2026-10-02T22:11:56.485Z The operation 'insert block' has failed with an exception. Retrying after 7.242s. 
      1 2026-10-02T22:12:03.879Z The operation 'insert block' has failed with an exception. Retrying after 6.634s. 
      1 2026-10-02T22:12:10.771Z The operation 'insert block' has failed with an exception. Retrying after 7.013s. 
      1 2026-10-02T22:12:18.292Z The operation 'insert block' has failed with an exception. Retrying after 8.946s. 
      4 2026-10-02T22:12:27.239Z Stored batch of requests
```

The request arrived at 22:11:49.044, during the chain, and was stored with the batch at 22:12:27.239, when the
chain ended.

## 6. Only sv1's writes were stuck; ordering continued

```
$ zcat log/10273/logs-frontend-wall-clock-time-2/canton.clog.gz | jq -r 'select(."@timestamp" >= "2026-10-02T22:11:43" and ."@timestamp" <= "2026-10-02T22:12:27.5" and (.logger_name|test("(DbStorageSingle|ReferenceSequencerDriver):sequencer=globalSequencerSv[1-4]/")) and (.message|test("insert block. has failed with an exception. Retrying after|^New blocks"))) | "\(.logger_name|capture("sequencer=(?<s>globalSequencerSv[0-9])").s) \(if (.message|test("^New blocks")) then "newblocks \(.message|capture("starting at height (?<h>[0-9]+)").h)" else "retry \(.message|capture("Retrying after (?<r>[0-9.]+)s").r)" end)"' | awk '$2=="retry"{n[$1]++; if($3+0>m[$1]) m[$1]=$3+0} $2=="newblocks"{b[$1]++; if(!($1 in lo)) lo[$1]=$3; hi[$1]=$3} END{for(s in b) printf "%s insert-block retries=%d max-backoff=%.3fs new-block batches=%d heights %s..%s\n", s, n[s], m[s], b[s], lo[s], hi[s]}' | sort
globalSequencerSv1 insert-block retries=11 max-backoff=8.946s new-block batches=144 heights 780..1027
globalSequencerSv2 insert-block retries=20 max-backoff=1.224s new-block batches=144 heights 780..1027
globalSequencerSv3 insert-block retries=22 max-backoff=0.570s new-block batches=144 heights 780..1027
globalSequencerSv4 insert-block retries=13 max-backoff=0.098s new-block batches=142 heights 780..1030
```

## Verdict

- Family L, fifth occurrence (10139, 10197, 10256, 10271). Like 10271, only globalSequencerSv1's own inserts stalled,
  and only submissions routed through sv1's sequencer were delayed. Both times sv1's backoff ran far past the others
  (8.946 s here versus at most 1.224 s; 5.612 s in 10271). Whether sv1's sequencer is systematically the loser of the
  serialization conflicts is not established from two runs.
- Flake; the cause is reference-sequencer contention on the shared Postgres (Canton / test infra).
- No fix branch. A longer test budget would not help: the stall (38 s for this request) reached the sv-app's 38 s
  HTTP request timeout, so the UI gets a timeout rather than a late success, and the timeout WARN alone would fail
  checkErrors.
- Not verified: the UI's rendering of the 38 s timeout (the sv2 browser log was not inspected).
