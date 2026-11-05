# 11228 - Sv1BootstrapProtocolVersion36IntegrationTest: standalone Canton not reachable within 60 s

The suite's first clue polls the admin API of every standalone Canton node for up to 60 s. The sv1 participant's
admin API came up 46 s after the test started polling (14:17:51.467), and the test still saw `Connection refused`
until its deadline at 14:18:06. First hit; the suite is new (10-29, #8125) and runs in the general wall-clock-time
shards. The same 60 s pattern in ManualStartIntegrationTest failed the same way in 2025 (5079), which was fixed by
moving that suite to the resource-intensive set (splice #1665).

- Run 42035938841, main fc8bacfc18bf, job 127274571403 `wall-clock-time (2)`, attempt 1. Canton 3.7.2.
- Artifact logs-wall-clock-time-2 (13424008364) in `log/11228/art/`.
- Component: test.

## 1. The failure

```
sed -E 's/^[0-9T:.-]+Z //; s/\x1b\[[0-9;]*m//g' log/11228/job.log | grep -a -A1 'protocol version 36 and sv2 joins \*\*\* FAILED'
```
```
[info] - sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins *** FAILED ***
[info]   The future returned an exception of type: io.grpc.StatusRuntimeException, with message: UNAVAILABLE: io exception. (Sv1BootstrapProtocolVersion36IntegrationTest.scala:98)
```
Line 98 is in clue "Canton nodes are running but have no identity":
`eventually(timeUntilSuccess = 60.seconds) { connection.getIdOption().futureValue ... }`, over the participants
first, then sequencers and mediators. It failed on the first connection, sv1's participant (port 27102).

## 2. Test side (canton_network_test.clog)

```
zcat art/canton_network_test.clog.gz | grep -a Sv1BootstrapProtocolVersion36 | jq -r '...' | grep -v DEBUG
```
```
14:17:05.456 Starting 'Sv1BootstrapProtocolVersion36IntegrationTest/sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins'...
14:17:06.292 Request (no caller tid) com.digitalasset.canton.topology.admin.v30.IdentityInitializationService/GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
...  (every ~5 s)
14:17:50.470 ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
14:17:55.471 ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
14:18:00.473 ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
14:18:05.475 ... GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception
14:18:06.278 Failed clue: Canton nodes are running but have no identity
```
Every failure's stack trace is `finishConnect(..) failed with error(-111): Connection refused: /127.0.0.1:27102`,
including the three after the server was up (section 3).

## 3. Canton side (canton-standalone-sv1-bootstrap-pv36.clog)

```
zcat art/canton-standalone-sv1-bootstrap-pv36.clog.gz | jq -r '...' | grep -a 'Starting admin-api services'
```
```
14:17:07.347 Starting Canton version 3.7.2
14:17:25.462 Starting admin-api services ... sequencer=sv1StandaloneSequencer   (sv2, sv3 the same ms)
14:17:34.669 Starting admin-api services ... mediator=sv1StandaloneMediator     (sv2, sv3 the same ms)
14:17:51.447 Starting admin-api services ... participant=sv1StandaloneParticipant (sv2, sv3 at 14:17:51.376)
14:17:51.467 Succeeded startup stage: Stage Admin API              (participant=sv1StandaloneParticipant)
14:17:51.469 Startup succeeded up to stage Init node id, waiting for external input
14:18:06.465 Shutdown complete.
```
The node types start one after the other (sequencers, mediators, participants). The participant only began its
DB migrations at 14:17:35.755 and they took 14.7 s:
```
14:17:35.756 Setting up database schemas                          (participant=sv1StandaloneParticipant)
14:17:45.868 Task db-migration still not completed after 10 seconds. Continue waiting...
14:17:50.469 Applied 29 migrations successfully
```
No WARN or ERROR in that log. Canton was healthy, just slow: 44 s from process start to the participant admin API.

## 4. Why the last 15 s did not help (inferred, not verified)

The admin API was listening from 14:17:51.467, yet the GetId calls at 14:17:55, 14:18:00 and 14:18:05 still failed
with `Connection refused`, each within 1 ms. The test reuses one ParticipantAdminConnection (one gRPC channel) for
the whole `eventually`. A grpc-java channel that keeps failing to connect goes into TRANSIENT_FAILURE with exponential
reconnect backoff (1 s, x1.6, jitter 0.2) and fails calls fast with the last connect error in between. From the
first attempt at 14:17:06.3, the attempts fall at about +1, +2.6, +5.2, +9.3, +15.9, +26.4, +43.2 s (14:17:49.5)
and then +70 s (about 14:18:16), after the deadline. Not checked: the channel's backoff settings in
ClientChannelBuilder, and channel-state logs (none at this level).

## 5. Duplicates and history

- No issue names the suite (`gh search issues -R DACH-NY/cn-test-failures Sv1BootstrapProtocolVersion36`: none);
  nothing in INDEX.tsv or known-families.md.
- The suite was added on 2026-10-29 by moritzkiefer-da (b4fc190853, #8125; lines 94-101 unchanged since). It is in
  the general wall-clock-time set (`test-full-class-names.log`), not in
  `test-full-class-names-resource-intensive.log`.
- Same clue and comment ("Long timeout because Canton is slow to start up") in ManualStartIntegrationTest.scala:183.
  5079 (2025-07, `UNAVAILABLE: io exception` connecting to the sv2 participant in ManualStartIntegrationTest) was
  closed by splice #1665 "Add ManualStartIntegrationTest to resource-intensive set"; it is in that set today.

## Verdict

Test flake, first hit, rare. Starting a full standalone Canton (3 sequencers, 3 mediators, 3 participants, all with
migrations) on a busy wall-clock-time runner took 44 s to the participant admin API, and the gRPC reconnect backoff
on the reused channel pushed the first successful call past the 60 s budget.

Fix (test only), one of:
- move Sv1BootstrapProtocolVersion36IntegrationTest to the resource-intensive set (build.sbt, as #1665 did for
  ManualStartIntegrationTest), or
- give the startup check a longer budget (for example 120 s), which also covers the reconnect backoff.

Owner: moritzkiefer-da (author of the suite, #8125).

## Correction 2026-11-05 (main thread)

Startup did not exceed the budget: the sv1 participant listened on 27102 from 14:17:51.447 (45 s into the 60 s
`eventually`), and the polls at 14:17:55.471, 14:18:00.473 and 14:18:05.475 failed with `UNAVAILABLE/io exception` in
the same millisecond as `sending request`, with no request logged by the participant. The connections are built before
`withCantonSvNodes` (lines 78-81), so the channel has failed since 14:17:06. Likely gRPC reconnect backoff (not shown in
the logs). Fix: build the connections after Canton is up or use a fresh one per poll; moving the suite to
resource-intensive only shortens startup.
```
grep -aE 'GetId to 127.0.0.1:27102' log/11228/test.tsv | awk -F'\t' '$1>="14:17:50"'
zcat log/11228/art/canton-standalone-sv1-bootstrap-pv36.clog.gz | jq -r 'select(.logger_name|test("sv1StandaloneParticipant")) | ...' | grep -aE 'admin-api|Stage Admin API'
```
