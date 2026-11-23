# Known flake families. Check these before any deep analysis.

Started on 2026-09-08 on the previous triage branch `ray/ci-triage-2026-09-15`, taken over unchanged on 2026-11-02.
Packet paths cited below and in issue comments resolve on this branch.

Format: signature to grep | confirming check | mechanism | parent ref and duplicates | fix state.

## Index

State as recorded in the family section, not rechecked against the tracker on 2026-11-02.

| Family | Subject | Parent / main refs | State |
|---|---|---|---|
| A | ACS_COMMITMENT_MISMATCH after a multi-host step | 10111 | fixed, PR 7435 (2026-09-21) |
| B | BFT 1 -> N sequencer onboarding, quorum loss, blacklisting | 10165 (umbrella), 10611 | open, Canton; fix PR splice#8363 open for 11255 (sequential SV sequencer onboarding in tests) |
| C | false off-boarding during onboarding state transfer | 10048, 10137, 10010 | fixed, canton#35600 |
| D | sim-time jump with a command in flight | 9740, 10170, 10173, 10154 | fixed on main, backports per E |
| E | missing backports to release lines | per backport | check first on release-line runs |
| F | frontend test infrastructure | 10156, 10141, 10145, 10157, 10143, 11187 | fixed on main (11187 by #8418) |
| G | tests pass, one log line fails checkErrors | 10438, 10084, 10140, 10144, 10147, 10612, 10606, 10676, 10495, 10142, 11155, 9445 | mixed, see section |
| H | evidence loss (sys.exit, teardown cascade) | 10137, 10139, 10183, 10088, 10180, 10176, 11347 | fix branches open |
| H2 | LSU: validator or SV app init against a non-active psid | 10088, 10174, 10180, 11404, 11682 | 10174 fixed, 10180 closed (no repro), 11404 open (second hit of variant C), 11682 open (third hit, SV app call site) |
| H3 | trigger pause timeout (TriggerTestUtil.scala:93) | 10175, 10176 | 10175 fixed |
| I | test races (venue, bulk storage, traffic, external party, sv4 sequencer, sequencer reconnect) | 8784, 10149, 10179, 10690, 10691, 10737 (dup 11531), 11238 | mixed |
| J | infra, no code change | many | see section |
| K | sim time: mediator pruning backoff after a 30 d jump | 10184 | PR 7440 open (2026-09-22) |
| L | reference sequencer `insert block` 40001 retry storm | 10139, 10197, 11160, 11189, 11191 | open; goes away with splice#8234 (all tests on DA BFT) |
| M | slow BFT confirmation vs 10 s client deadline | 10281 | fixed, #7909 |
| N | SV1 bootstrap below participant default PV (PV 36 nightly) | 10520 | fixed, #8065 / #8125 |
| O | AcsCommitmentSender close hang crashes participant (Canton 3.7) | 10729 | open, Canton |
| P | test race with the validator's own traffic top-up | 10731 | open, test |
| Q | release-line javac inference failure on codegen | 10728, 10735 | cause open |
| R | forced ACS snapshot 500 on advisory lock | 10740 (dup 10916) | open, OriolMunoz-da |
| S | environment close timeout on in-flight submission | 10742 | open |
| T | CI test credentials rotated 2026-10-29 | 10896, 10901 | resolved by the rotation |
| U | joining SV ACS import refused, topology offset (Canton 3.7) | 10907 (dups 11107, 11163, 11177, 11222) | fixed upstream in Canton, no splice action; close new hits as dups |
| V | PeriodicTopologySnapshot missing 'scopes' (pekko-connectors 1.3.0) | 10921 (dups 10923, 10891) | fixed on main, #8148 |
| W | ScanEventHistoryIntegrationTest pre-tap cursor race | 10912 | open, fix PR #8424 |
| X | PV 37 topology on a PV 36 synchronizer | 10934 (X1), 10927 (X2), 10937 (X3) | X1, X3 fixed; X2 open |
| Y | test tagged below the Daml packages it needs | 10930, 10933, 11353 | open, test tags |
| Z | ScanPartyMonitorScript verdict held back by /v0/events cap | 11128 (dups 11136, 11138, 11139, 11158) | open, stephencompall-DA; 5 hits 2026-11-02..03 |
| AA | LsuIntegrationTest sv4 faucet trigger ERROR during catch-up | 11104 | open, test |
| AB | BftScanConnection refresh WARN about stopped sv2Scan after suppression ended | 11140 | open, fix PR #8419 |
| AC | WalletMetricsTest trigger.completed lookup matches success + noop series | 10732 (dup 11143) | open, test fix proposed |
| AD | TBAR test reads per-round totals while a parallel backlog computation is still committing | 11144 | open, test fix proposed |
| AE | frontend login helper misses already-logged-in state on a slow page load | 11161 | open, test fix proposed |
| AF | sim-clock jump timeouts trip the SV medium-priority circuit breaker | 11147 (dups 11291, 11348, 11357; related 10475) | open, fix PR #8420 |
| AG | first Postgres connection > 5 s at an environment start, createDatabase retry WARN | 11141 (dups 10739, 11523, 11533, 11536, 11641) | open, test config fix proposed |
| AH | SequencerPruningIntegrationTest disables the shared participants at the global sequencer | 11165 | open, owner suggested martinflorian-da |
| AI | SUBMISSION_ALREADY_IN_FLIGHT trips the validator circuit breaker | 9752 (dups 10300, 11173; 11240 via B) | closed 9752, fix cni#2785 status unknown |
| AJ | scan `claimed by multiple SVs` WARN from sv2-4 scans after the suppression ended on sv1Scan | 11304 (dups 11307, 11309, 11316, 11327) | fixed, #8389 |
| AK | RollForwardLsuDR pre-DR SV onboarding past the 300 s sequencing cutoff (BFT since #8234) | 11303 (dups 11312, 11328, 11336, 11371, 11372, 11396, 11419) | open, test budget |
| AL | AppUpgradeIntegrationTest base-version nodes not initialized within the 5 min initial wait | 11301 (dup 11302) | open, fix PR #8414 |
| AM | SV init slower than a fixed wait; a quarter of the DAR uploads are repeats | 11299, 11347, 11373 (dups 11550, 11558, 11654) | 11299 fixed, #8467 (dedup + test timeout); 11347, 11373 open |
| AN | cimain SV backup snapshots a CometBFT volume on CantonBFT (backup script defaults enableBftSequencer to false) | 10332 (dups 11297 11298 11314 11315) | open, splice script fix |
| AP | ManualSignatureIntegrationTest validator OTK check gives up before or during alice's key rotation | 11232 (dup 11318) | fixed, #8391 |
| AQ | cilr weekly SV catch-up: orderer restored a day behind (cantonBft backup rule), monitor 999 s fallback claims it | 11194 (also 10843) | open, splice backup selection + cni monitor |
| AR | fixed wait for deadline-driven automation shorter than deadline + automation delay + a slow round trip | 11333 | open, fix PR #8411 |
| AS | SvFrontendIntegrationTest expiring proposal closed by another SV (CloseVoteRequestTrigger paused on sv1 only) | 11186 (dup 11340) | open, fix PR #8417 |
| AT | frontend test thread stalls minutes inside the only `eventually` attempt; stale first read rethrown after the deadline | 10918 (dups 10920, 11561) | open, stall origin: lead in 11561 (lazy package-signature load on the first console command), not proven; no fix proposed |
| AU | test retry budget starts before a restarted external Canton process is ready (withCanton does not wait) | 11346 | fix PR splice#8444 (5 min budget) |
| AV | LsuIntegrationTest test 2 restarts SV and scan nodes while test-1 validators keep calling scans; BFT call retries (12, about 7 s) run out | 11354 | fixed, #8469 |
| AW | re-onboarded SV sequencer with a new id on the same P2P endpoint: peers keep the old id via a leaked incoming-connection ref, newcomer never leaves state transfer (Canton BFT) | 11363 (same symptom as 10011) | 11363 fixed in the test, #8465 (own P2P port); Canton gap open |
| AX | freshly onboarded BFT sequencer refuses submissions while replaying old blocks; the SV app breaker outlasts the test wait | 11384 | open, test fix proposed |
| AY | SV joining after an LSU: SV app waits on the never-initialized legacy (pre-LSU) sequencer for its physical synchronizer id, init fails | 11379 | open, splice SV app or cluster config |
| AZ | cluster hostnames under one ingress NXDOMAIN while cert-manager renews the cluster certificate (DNS-01 TXT hides the zone wildcard) | 11382 (dup 11383) | open, infra fix proposed |
| BA | wait covers several DSO-delegate confirmed-action executions, which run one at a time behind one lock | 11392 | open, test fix proposed |
| BB | test JVM stalls in the RemoteClock fetch after a sim-time advance; execution context threads all in the blocking clock wait | 11405 | open, cause of the exhaustion unknown, no fix proposed |
| BC | default 20 s wait starts while the SV closes the issuing round the resumed wallet trigger uses; two contention retries at 8-10 s per round trip | 11532 | open, test fix on a local branch; slow window cause open |
| BD | sponsor SV app: sequencer onboarding state export queued behind an ACS export that waits on the frozen synchronizer (one export slot since #7795) | 11539 | open, fix on a local branch (one queue per kind of export) |
| BE | freshly added cluster node cannot pull the istio sidecar image: registry.istio.io answers 403 to the anonymous token; pods on that node stay in ImagePullBackOff | 11541 (same run: 11542, 11544, 11545, 11546; ciperiodic: 11547, 11551; cimain deploys: 11548, 11552, 11555) | open, infra, cause of the 403 unknown |
| BF | Sv1BootstrapProtocolVersion36IntegrationTest: admin connections built before the standalone Canton spawn, GetId polls refused after the participant admin API is up, 60 s budget | 11228 (dup 11553) | open, test fix proposed, no PR |
| BH | main test sources do not compile after a merge race (two PRs green alone, broken together); every job of the post-merge run fails at compile | 11566 (siblings 11567-11636 except 11597, 11637) | fixed, splice#8569 |
| BJ | release deploy after a main deploy on a fixed-token cluster: main writes the auth0 fixed-token cache as v1 under the old secret name, the release loader returns an undefined token, ledger-api-auth secrets lack `token` | 11640 (dup 11656) | open, splice IaC owner decision |
| BN | frontend test reads a polled list with scalatest-selenium `findAll` outside any retry; a row is re-keyed by the 1 s poll between FindElements and the per-row reads (`StaleElementReferenceException`) | 11664 | open, test fix on a local branch (`pperek/fix-11664-licenses-rows-stale-element`, 6451f8f582), no PR |
| BO | default 20 s wait covers two sequential sv1 round trips (confirmation, then ExecuteConfirmedAction); one of them lands on a BFT epoch boundary with a view change (35 s) | 11665 | open, test fix on a local branch (`pperek/fix-11665-wallet-subscription-created-wait`, 155cd7a595), no PR; slow ordering window cause open (see 11532, 11375) |
| BP | main deploy through the operator on a cluster whose infra stack output predates #8216: stacks read `audiences.participantAdminApi` as undefined before the infra stack adds it (token request `RequiredError`, participants with admin audience `undefined`), sv-runbook hits the operator retry limit of 4 failed updates | 11670 | open, splice cluster/pulumi owner decision, no fix written |
| BQ | joining SV: DomainTimeIngestionTrigger retries `Not connected to any synchronizer` from synchronizer registration until the sponsor has bootstrapped the DSO; 35 retries (110 s) run out on a slow runner (checkErrors only) | 11673 | open, owner decision (app fix or #8467 backport) |

## A. ACS_COMMITMENT_MISMATCH sv1Participant vs aliceValidator after a multi-host step
- Signature (canton log WARN): `ReceivedAcsCommitmentMatcher:participant=sv1Participant ... ACS_COMMITMENT_MISMATCH ... sender = aliceValidator`.
- Confirm: `zcat canton_network_test.clog.gz | grep -a -E "Starting test suite|Multi-host alice"`; the mismatched
  period's `fromExclusive` is 1-16 s after a `Multi-host alice on sv1Participant` clue (ExpiryWithMinimalVettedPackages
  base suites: AmuletExpiryV1Fallback, ExpiryWithIgnoredAmuletVersion, ExpiryWithNoVettedAmuletVersion; and
  AutoIgnoreUnresponsiveParties*). The WARN lands 0-27 min later in an unrelated suite (random send delay).
- Mechanism: the tests add sv1Participant as a host of alice's wallet party after she owns contracts, with no
  ACS import; the two hosts genuinely disagree. Test issue, not product. Detected since 2026-09-10 only; the old
  processor also resolved hosting at tick time and the 3.6 pipeline ran unnoticed from 08-21, so the detection change is
  a Canton-side unknown (10146 packet, correction 2026-09-21). Fixed by PR 7435 (merged 2026-09-21).
- Parent 10111 (run 34523566111); dups 10129, 10146, 10155, 10158, 10162, 10164, 10167, 10178 (PG14 nightly), 10182.
- Fix: `ray/fix-multihost-acs-mismatch` (allocate alice's party hosted on both participants before onboarding).
  Do not widen `canton_log.ignore.txt:145`.

## B. BFT 1 -> N sequencer onboarding step with unauthenticated newcomers (quorum loss, blacklisting)
- Signatures: (1) `acknowledge-signed ... DEADLINE_EXCEEDED after 119.99s` + `Failed to acknowledge clean timestamp`
  (participant or mediator client, WARN); (2) SV app WARN `POST /api/sv/v0/onboard/sv/sequencer ... timeout after 38 seconds`.
- Confirm on globalSequencerSv1: `New epoch N has started with leaders = [sv1, sv2, ...]` with `size` stepping 1 -> 3/4,
  preceded within a second by `Authenticated P2P nodes count ... 1 is currently below weak quorum size 2`, then
  `blacklisted nodes = List(SEQ::sv1...)` (or the newcomer) for ~3 epochs; the ack `received a message` on the
  sequencer, `MempoolModule: P2P connectivity is not ready`, `cancelled` then `sending response` ~120 s later.
- Mechanism: Canton activates the new ordering topology at the epoch boundary regardless of P2P authentication.
  Not fixed in digital-asset/canton main as of the 2026-09-15.22 mirror. Splice mitigations: serialise SV
  sequencer onboardings, or gate on P2P authentication; for (2) a non-blocking onboard handler or a
  `custom-timeouts` entry for `onboardSvSequencer`, or extend the `onboard/validator` timeout ignore.
- Umbrella 10165 (10094 and 10153 closed as dups; 10161 same). Occurs during any initDso with 3-4 SVs
  (ValidatorIntegrationTest, SvOnboardingIntegrationTest, SvDsoPartyManagementIntegrationTest).
- Third signature (10611, 2026-10-26, CopyVotesIntegrationTest): a participant send rejected with
  `SEQUENCER_UNAVAILABLE: P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)` is not
  retried (`Retry has not been configured for GrpcServiceUnavailable, giving up`), the command stays in flight until
  max sequencing time (`UnSequencedCommandRejected` ~60 s later), the trigger's resubmits get
  `SUBMISSION_ALREADY_IN_FLIGHT`, and after 20 the `SpliceCircuitBreaker` WARNs `tripped after 20 failures`.
- Same symptom without the rejection (11240, 2026-11-06, main 97df2c4399, canton 3.7.2, CopyVotesIntegrationTest
  setup, splitwellValidator): epoch 32 (2 -> 4) below strong quorum until 12:22:23.842, newcomers set up the epoch at
  12:22:27/12:22:31, first output 12:22:34.500; the top-up submission is ordered late, the test-only 5 s
  `buyExtraTraffic.grpcDeadline` (`ConfigTransforms.setDefaultGrpcDeadlineForBuyExtraTraffic`) cancels the wait,
  19 resubmits get `SUBMISSION_ALREADY_IN_FLIGHT`, breaker trips 14 ms after the submission completes. Test-only
  mitigation: that deadline 5 s -> 30 s (as #7909 for the treasury).
- 11255 lost-verdict shape under simulated time (parent 11255, open, rautenrieth-da): 11306 and 11308 (2026-11-09,
  simtime (3), NonZeroRoundBootstrapBftTimeBasedIntegrationTest setup, closed as dups). Ordering topology 1 -> 2 (sv4
  alone), then 2 -> 4 (sv2 + sv3); sv4 blacklisted for 3 epochs although it was not a newcomer of the multi-node step.
  Setup requests get 1 of 2 mediator results (sv4 refuses while blacklisted, the other mediator delays 3000 ms on a sim
  clock that never advances), participants stop publishing, init times out after 5 min.
- 11255 rejected-send shape under simulated time (parent 11255): 11288 (2026-11-09, simtime (4), setup of
  TrafficBasedRewardsSvAppTimeBasedIntegrationTest) and 11289 (2026-11-09, simtime (3), main 38a37d9680,
  SvTimeBasedRoundMgmtIntegrationTest setup), both closed as dups. Ordering topology 1 -> 4 in one step; the
  MediatorSynchronizerState proposal adding MED::sv4 is rejected for the SV participants whose broadcast lands after
  the switch (`P2P connectivity is not ready (authenticated = 1 < dissemination quorum = 2)`; in 11289 sv1 broadcast
  0.2 s earlier and was sequenced, so the proposal stayed at one signature). The rejected sends count as possibly
  sequenced until max sequencing time 00:00:20 on a sim clock at about 00:00:02, so the outbox flush cycles never
  close and re-proposals log `Another flush cycle is currently ongoing`; the sv4 SV app waits forever on
  `Mediator has been granted unlimited traffic`, never creates sv4_validator_user, and sv4Validator exits the JVM
  after 120 `Get user` retries (no test report, family H). Blacklisting comes later and plays no part. Confirming
  grep: `Rejecting submission request by` with `P2P connectivity is not ready` within 0.3 s after `New epoch N ...
  size = 4`, plus zero `Failed to send result` and live indexers (rules out the lost-verdict shape).
- 11255 on Canton 3.7.3-snapshot.20261109 (#8381), 2026-11-10, all closed as dups; 5 of 12 completed post-merge
  main runs since the bump plus the scheduled mainnet-Daml run.
  - Lost verdict: 11324 11332 (TrafficBasedRewardsSvAppTimeBased setup), 11325 (SvTimeBasedRoundMgmt, in the test),
    11337 (TimeBasedTestNetPreview setup: sv4Validator createValidatorRight loses its verdict, 19
    `SUBMISSION_ALREADY_IN_FLIGHT` retries trip the validator low-priority breaker, JVM exit), 11341 (main
    5a5335450d, SvExpiredRewardsCollectionTimeBased setup 1 -> 2 -> 4, request 06:27:01.296Z gets sv1 only, sv3
    delays, sv4 refused; sv3 init waits on DsoRules_SetSynchronizerNodeConfig resubmits; the reported failure is the
    next suite, DisabledWalletTimeBased, `sv1Scan ... no app state` in its destroy).
  - Rejected send: 11323 (mainnet-Daml run, MED::sv2 proposal lost, sv2_validator_user never created). Batch never
    ordered: 11326 (not an SV step: splitwellValidator DAR vetting on global accepted 0.3 s before the 1 -> 4 switch,
    batch discarded with `received message after closing`, splitwell_provider never created). Exit stacks show
    `PERMISSION_DENIED: An error occurred...`; the participant AuthInterceptor WARN for that tid says
    `UserNotFound(<user>)`. Confirm the batch variant: `LocalBatchCreated` for the stuck submission within 0.5 s of
    the size-4 switch, then no `validated to: Deliver` for it.
  - Breaker shape, checkErrors only: 11334 (sv1 blacklisted epochs 16-18 after the 1 -> 4 step, its mempool
    rejects 20 retried bobValidator onboard submissions as `currently blacklisted, rejecting`, surfaced as
    SEQUENCER_BACKPRESSURE; `Circuit breaker low-priority-connection tripped after 20 failures`). Confirm: the
    `MempoolModule` rejection count on the blacklisted sequencer equals the breaker's failures.
  - Setup-failure blast radius: a hit in the `Running setup` step (environment setup) skips the plugins'
    beforeEnvironmentDestroyed (`EnvironmentSetup.manualCreateEnvironment` only closes the fixture), so
    ResetDecentralizedNamespace never runs and every later suite fails sv1 init with `Not fully authorized` (11332: 7
    suites, 11324: 5; 11341: 1, `TOPOLOGY_UNAUTHORIZED_TRANSACTION`). Confirm: zero `has been reset` lines in
    canton_network_test.clog.gz. A hit inside a test body recovers (11325).
  - splice#8356 (blacklisting off in tests, open) covers lost verdict and breaker, not rejected send.
  - Rejected send of transactions (not topology), 11398 (2026-11-12, simtime (1), main c9c875cfc5, Canton 3.7.3,
    TrafficBasedRewardsSvAppTimeBased setup): 1 -> 4 at 18:13:25.223, sv1 rejects splitwellValidator
    installWalletForUser 1.3 s later and createSplitwellRules at 3.8 s (`P2P connectivity is not ready`), both with max
    sequencing time 00:01:00 against a sim clock at 00:00:02; 69 `SUBMISSION_ALREADY_IN_FLIGHT` until the 5 min init
    timeout, then the setup-failure blast radius (4 suites, sbt killed at 40 min). Rejections can come later than the
    0.3 s window of the confirming grep: check the `authenticated = 1 < dissemination quorum = 2` text instead.
- Signature (2) on Canton 3.7.3-snapshot.20261109 (11350, 2026-11-11, main 9dd892fe2e, wall-clock-time (7),
  ValidatorSequencerConnectionIntegrationTest setup): sv3, sv4, sv2 onboard their sequencers through sv1 within 21 s;
  sv2's add is submitted into the 2 -> 3 step (sv4 newcomer, below strong quorum 03:08:23.5-03:08:46.9) and sv1's
  handler times out at 38 s waiting for it in SequencerSynchronizerState; the retry succeeds. 10165 merged into 11255
  (2026-11-08), so close new hits of this WARN as duplicates of 11255.
- 11255 after splice#8356 (blacklisting off in simtime), 2026-11-11, rejected-send / batch-never-ordered shape only:
  11358 11362 11368 11374 (simtime (3)/(2)/(5)/(0), main 2d54198a47, 2fc780a883, 1ee8c88066, 915616a77d; Canton
  20261109 and 20261111 snapshots), 4 of 16 post-merge runs since #8356, all four closed as dups. A 4-SV setup grows
  the ordering topology 1 -> 4; the SV participants' signatures on the MediatorSynchronizerState add of the joining
  SV's mediator are rejected (`P2P connectivity is not ready`) or batched and never ordered within 1.2 s of the
  switch; the joining SV waits on `Mediator has been granted unlimited traffic` until the 5 min init wait. All four
  are setup-step hits, so the blast radius applies (no reset, sv1 DSO PartyToParticipant
  TOPOLOGY_UNAUTHORIZED_TRANSACTION in every later suite). Confirm: `blacklisted nodes = List()` on the size-4 epoch,
  zero `currently blacklisted` and `Failed to send result`, `Rejecting submission request by Some(PAR::sv` within
  1.2 s after it, and the stuck SV's last line `Waiting until Mediator has been granted unlimited traffic`.
  Not AM: no DAR uploads in the wait.
- 11255 on Canton 3.7.3 final (same contents as 3.7.3-snapshot.20261111, #8453), 2026-11-12: 11386 11391
  (release-line-0.10.2 4db185089a, e214318836; first hits on the 0.10.2 release line) and 11388 11389 (main
  4db185089a), plus 11381 (main 8ecac4f1f0) and 11380 (scheduled mainnet-Daml, splitwell confirmation response)
  closed by the parent's assignee. Same rejected-send shape as the 2026-11-11 hits: serial-4 MediatorSynchronizerState
  signatures rejected, never answered, or stuck behind an open outbox flush at the 1 -> 4 step. 5 of 9 completed
  post-merge runs since 2026-11-11 18:00 had a failed simtime shard, all this shape. Same confirming grep.
- 11255 lost-verdict shape in a wall-clock suite, triggered by a host stall (11395, 2026-11-12, wall-clock-time (4),
  main f7db86672f, SvDsoPartyManagementIntegrationTest): both JVMs silent 15:07:30-15:07:40 (FutureSupervisor
  `Supervising futures itself takes longer than the check frequency: 10368 ms`), no ordered block 15:07:25.4-15:07:40.1,
  view change, `New epoch 155 ... blacklisted nodes = List(SEQ::sv4...)`, mediator sv4 `Failed to send result ...
  currently blacklisted, rejecting`, participant `Response message for request ... timed out`. Ordering topology
  unchanged. splice#8356 does not apply: `disableBftBlacklisting` is only in `EnvironmentDefinition.withSimTime`.
  Confirm: a `Supervising futures itself takes longer` line in both JVMs shortly before the first blacklisted epoch.
  Second wall-clock hit without that line: 11564 (2026-11-18, frontend-wall-clock-time (2), main ec059037fa, Canton
  3.7.4-snapshot.20261112, TokenStandardWalletTransfersFrontendIntegrationTest): canton.clog rate falls from about
  7000 to under 1000 lines/s at 12:49:39 (test JVM unaffected, zero `Supervising futures` lines, no DAR uploads),
  segment timeouts 12:49:47.96, sv2 blacklisted in epoch 202, sv1 in epochs 203-207 (12:50:20.400-12:51:04.908).
  All participants submit through sv1: bobValidator's PartyToParticipant batch (created by sv1 in epoch 202) is
  ordered in epoch 208 after its max sequencing time (`validated to: Discard`), aliceValidator's push is rejected by
  the mempool; both parties exist on splitwell only and `onboardWalletUser` fails with HTTP 400
  `INVALID_PRESCRIBED_SYNCHRONIZER_ID` (do not confuse with L or AH). Confirm without the stall line: `New epoch`
  with a non-empty blacklist while `size = 4` and activationTime are unchanged, `currently blacklisted, rejecting`
  from `MempoolModule` on the sequencer the failing node submits to, and the failing request's message id either
  rejected there or ordered after its `max sequencing time`.
- 11255 on Canton 3.7.3, evening of 2026-11-12, all MediatorSynchronizerState pushes rejected at the 1 -> 4 step:
  11399 (release-line-0.10.2 48f45bf330, TimeBasedTestNetPreview setup, MED::sv2, setup blast radius over 3 suites),
  11400 (main 1fb5a7382f, SvTimeBasedRoundMgmt in the test body, sv4Validator JVM exit), 11402 (main f1e385ffac,
  SvTimeBasedAmuletPrice setup, MED::sv2 pushes of all four SVs rejected within 0.23 s, sv2Validator JVM exit).
- 11255 wall-clock onboarding window, LSU suite (11401, 2026-11-12, logical-sync-upgrade (0), main 1fb5a7382f,
  LsuIntegrationTest test 1 initDso): sv2, sv4, sv3 join through sv1; the SEQ::sv3 add is built by all SVs 0.5 s
  after the 2 -> 3 step, the first 3-node epoch stalls 29 s until the newcomer sv2 enters it, and the batch with
  sv2's and sv4's signatures is proposed 8 s after their max sequencing time (outbox `Failed`, sv1
  `onboard/sv/sequencer` 503 at 38 s, four times). sv3 initializes 5 s after the 5 min wait. Same window as 11350.
  Confirm: `LocalBatchCreated` with the stuck message id within 1 s of the size-3 epoch, `CreateProposal` for that
  batch after the request's `max sequencing time`.
- 11255 on Canton 3.7.3, 2026-11-13, simtime (0), TrafficBasedRewardsSvAppTimeBased setup, splitwellValidator hit at
  the 1 -> 4 step in two consecutive main runs: 11406 (main ee0c92e0fd; 1 -> 4 at 10:47:36.589, the participant's
  confirmation response for its own createSplitwellRules request, sequenced 0.04 s before the switch, is rejected
  16 ms after it with `P2P connectivity is not ready`; no verdict, createValidatorRight does not complete either,
  63 `SUBMISSION_ALREADY_IN_FLIGHT`, low-priority breaker, JVM exit 10:50:35) and 11408 (main 20054fa63d; 1 -> 4 at
  10:57:13.587, splitwellValidator's VettedPackages broadcast rejected at 10:57:14.810, outbox flush never closes,
  validator stuck in `Vet packages`, splitwell_provider never created, providerSplitwellBackend JVM exit 11:01:02;
  same end state as 11326). Confirmation-response variant: look for `Rejecting submission request` of a message
  with `1 envelopes to MGR=1` and `Phase 4: Sending for request=` just before it.
- 11255 on Canton 3.7.3, 2026-11-13, consecutive post-merge runs on main (ee0c92e0fd, 20054fa63d), both simtime (5),
  TimeBasedTestNetPreview setup, MED::sv2 add at the 1 -> 4 step: 11409 (sv3's signature sequenced, sv1's and sv2's
  sends in one batch created 86 ms before the switch and never ordered, sv4's rejected; setup timeout before the JVM
  exit, so a report exists and the blast radius takes 3 more suites, the first one in `Creating test console` because
  sv1's unvetting proposal sits behind the open outbox flush) and 11410 (sv2, sv1, sv3 rejected 0.1 to 3.0 s after the
  switch, sv4's outbox stuck on its serial-3 send batched 82 ms before the switch; sv2Validator JVM exit). A rejection
  can come 3 s after the switch, and a mixed hit can show a single `Rejecting submission request` line: also check for
  a `LocalBatchCreated` within 0.1 s before the size-4 epoch with no `Deliver` for its message ids.
- 11255 on Canton 3.7.3, 2026-11-13, rejected send with a new exit path: 11407 (main ee0c92e0fd, simtime (4),
  SvTimeBasedRoundMgmt in the test body). MED::sv3 MediatorSynchronizerState pushes of sv3, sv1 and sv4 rejected
  0.11-0.18 s after the 1 -> 4 step; the 5 min sv3 init wait ends before sv3Validator runs out of `Get user`
  retries, so the teardown runs, ResetDecentralizedNamespace is not effective within 1 minute (outboxes still log
  `Another flush cycle is currently ongoing`) and ResetTopologyStatePlugin calls sys.exit(1). No console stack:
  the only trace is the ERROR `Failed to reset decentralized namespace` as the last line of
  canton_network_test.clog.gz.
- 2026-11-13 rate: 5 failed simtime shards (11406 11407 11409, 11408 11410) on two consecutive post-merge main runs
  (ee0c92e0fd, 20054fa63d), all at the 1 -> 4 step.
- 11255 on Canton 3.7.3, 2026-11-13 afternoon, the next two post-merge main runs: 11411 (main ec67414f5d, simtime (1),
  NonZeroRoundBootstrapBftTimeBased setup, MED::sv4 add: sv3, sv2, sv1 pushes rejected 0.13-1.3 s after the 1 -> 4
  switch, sv4's outbox stuck on its SequencerSynchronizerState push 0.59 s before the switch, never answered; sv4Validator
  JVM exit, same shape as 11410) and 11412 (main 87d584aa16, simtime (0), TrafficBasedRewardsSvAppTimeBased setup,
  splitwellValidator createSplitwellRules and installWalletForUser rejected 0.88 and 1.13 s after the switch, same as
  11398; setup blast radius over 6 suites). Since 09:30 every post-merge main run failed (5 of 5: 11405 family BB, then
  11406-11412 this family). Confirm a MED push as rejected: `Attempting to push ... <tx hash>` in the participant's
  QueueBasedSynchronizerOutbox 7-15 ms before the `Rejecting submission request by Some(PAR::svN`; a transaction send:
  the `SEQUENCER_UNAVAILABLE(1,<tid>)` trace id matches the app's `SubmitAndWait` tid.
- 11255, 2026-11-13 evening, the next three post-merge main runs, all closed as dups:
  - 11413 (main 09c8608e46, Canton 3.7.3, simtime (0), TrafficBasedRewardsSvAppTimeBased setup): 1 -> 4 at
    16:05:43.732, splitwellValidator VettedPackages broadcast rejected 3.08 s later (sv1 also rejected five
    SetTrafficPurchased top-ups in between, retried fine); same end state as 11408 (providerSplitwellBackend JVM exit,
    `UserNotFound(splitwell_provider)`).
  - 11414 (same run, wall-clock-time (1), SvOnboardingIntegrationTest setup, checkErrors only): signature (2) at the
    1 -> 2 step. sv4's onboard request reaches sv1 18 ms before epoch 156 (sv3 newcomer, 1 < strong quorum 2); sv1
    orders no block for 22.2 s until sv3 enters the epoch; the sv4 add (held, no rejection) is effective 34 s after
    the request, 4 s too late for the 38 s timeout; retry OK in 2.5 s. Confirm: a gap of about 20 s between
    `Delivering block` lines on globalSequencerSv1 starting at the size-2 epoch.
  - Canton 3.7.4-snapshot.20261112.20915.0.v9e248620 (#8479), shape unchanged: 11415 (main 2c5cd9a752, the bump
    commit itself, simtime (5), SvExpiredRewardsCollectionTimeBased setup, MED::sv3 pushes of all four SVs rejected
    62-550 ms after the switch; blast radius makes ExternallySignedTxsTimeBased, whose body never ran, the reported
    failure) and 11416 (main 09f5cc4b78, simtime (3), same suite, MED::sv2 add at DSO threshold 3: sv4 and sv3 signed
    before the switch, sv2 and sv1 rejected 1.48 and 1.52 s after it; blast radius into TokenStandardMetadataTimeBased),
    and 11421 (main 6a996a302c, simtime (4), SvTimeBasedRoundMgmt round management, MED::sv3 add: sv1 send batched 2 ms
    before the switch and never ordered, sv4 and sv2 rejected 79 and 270 ms after it; teardown namespace reset not
    effective in 1 min, ResetTopologyStatePlugin sys.exit, no report, as 11407).
  - Rate: from ee0c92e0fd (10:30) to 09f5cc4b78 (16:45) every post-merge main run had a failed shard of this family
    (7 of 7; 635294d6e6 before them is 11405, family BB).
- 11255 on Canton 3.7.4-snapshot.20261112.20915.0.v9e248620, 2026-11-17, batch never ordered (as 11326): 11534 (main
  54f83a5ddd, simtime (1), TrafficBasedRewardsSvAppTimeBased setup). splitwellValidator VettedPackages broadcast on
  global accepted by sv1 at 08:19:14.035, `LocalBatchCreated` 08:19:14.066, 1 -> 4 at 08:19:14.068 (2 ms later), batch
  disseminated to the three newcomers and never seen again; zero `P2P connectivity is not ready`, zero `Deliver` for
  the message id; outbox flush never closes, splitwell_provider never created, providerSplitwellBackend JVM exit
  08:23:55 (`UserNotFound(splitwell_provider)`). splice#8363 still open.
  Same run, simtime (3), 11535: SvExpiredRewardsCollectionTimeBased setup, 1 -> 4 at 08:36:02.390, MED::sv3 add: sv3,
  sv1 and sv4 pushes rejected 41-197 ms after the switch (`P2P connectivity is not ready`), sv2's outbox stuck on its
  previous push batched 175 ms before the switch and never ordered (mixed hit as 11409); sv3 init timeout 08:39:44,
  blast radius into SvTimeBasedBootstrappingRound and WalletAmuletPriceTimeBased (3 suites aborted, 9 tests passed).
  11540 (main 95f6581dc9, simtime (0), TrafficBasedRewardsSvAppTimeBased setup): 1 -> 4 at 15:36:52.612, MED::sv4 add:
  sv2 and sv1 pushes sequenced, sv4's batched 30 ms before the switch and never ordered, sv3's rejected 225 ms after
  it (`P2P connectivity is not ready`); sv4 waits on mediator traffic, sv4Validator JVM exit 15:41:32
  (`UserNotFound(sv4_validator_user)`), no report. Third failed simtime shard of this family on 2026-11-17.
  11543 (main 408f7ed9e7, simtime (3), TimeBasedTestNetPreview setup): 1 -> 4 at 16:26:24.011, MED::sv2 add: sv4's
  and sv2's pushes 69 and 32 ms before the switch never answered, sv3's rejected 64 ms after it (`P2P connectivity is
  not ready`), sv1's stuck behind its open flush (mixed hit as 11409); sv2 init timeout 16:31:45, blast radius into
  SvTimeBasedOnboarding (sv1Scan init timeout, then the 11347 teardown throw of family H) and, through the leaked
  environment, SvTimeBasedAmuletPrice and SvTimeBasedRewardCoupon (`Address already in use`). Fourth of the day.
  11554 (main facc9443d3, simtime (0), TrafficBasedRewardsSvAppTimeBased setup): 1 -> 4 at 20:15:00.758, rejected
  send of a participant topology broadcast: splitwellParticipant's PartyToParticipant for the splitwell provider party
  rejected by sv1 181 ms after the switch (`P2P connectivity is not ready`), `Retry has not been configured for
  GrpcServiceUnavailable, giving up`, hash and message id never seen again; the party is hosted on the splitwell
  synchronizers only, 62 `installWalletForUser` retries fail with `INVALID_PRESCRIBED_SYNCHRONIZER_ID`,
  splitwellValidator stays in `Set up app instance splitwell` until the init timeout 20:20:30 (zero
  `SUBMISSION_ALREADY_IN_FLIGHT`); blast radius over the 4 remaining suites (0 succeeded). Fifth of the day.
  11559 (2026-11-18, main e74e9bf844, simtime (4), SvTimeBasedRoundMgmt 'round management' setup, first suite): 1 -> 4
  at 09:19:12.310, MED::sv4 add: sv2's push sequenced, sv3's, sv1's and sv4's rejected 103, 126 and 509 ms after the
  switch (`P2P connectivity is not ready`); sv4 waits on mediator traffic, sv4Validator JVM exit 09:24:15
  (`UserNotFound(sv4_validator_user)`), no report; same shape as 11400. splice#8363 still open.
  11560 (same run, simtime (0), TrafficBasedRewardsSvAppTimeBased setup, first suite): 1 -> 4 at 09:20:22.362,
  splitwellParticipant's VettedPackages broadcast on global rejected by sv1 256 ms after the switch (`P2P connectivity
  is not ready`), not retried, outbox flush never closes; splitwell_provider never created, providerSplitwellBackend
  JVM exit 09:24:26 (`UserNotFound(splitwell_provider)`), no report; same shape as 11408 and 11413.
  11565 (2026-11-18, main 465b29cc47, simtime (0), TrafficBasedRewardsSvAppTimeBased setup, first suite): 1 -> 4 at
  14:33:11.774, splitwellParticipant's VettedPackages broadcast on global rejected by sv1 288 ms after the switch
  (`P2P connectivity is not ready`), not retried; splitwellValidator stuck in `Vet packages`, providerSplitwellBackend
  JVM exit 14:37:08 (`UserNotFound(splitwell_provider)`), no report; same shape as 11560.
  11643 (2026-11-19, main 1eafba3113, simtime (4), SvTimeBasedRoundMgmt round management, first suite): 1 -> 4 at
  02:46:54.333, MED::sv3 add: sv3's push 285 ms before the switch, sv4's, sv1's and sv2's rejected 24, 154 and 926 ms
  after it (`P2P connectivity is not ready`), not retried; sv3 waits on mediator traffic, sv3 init timeout
  02:49:48.476, ResetDecentralizedNamespace fails, ResetTopologyStatePlugin sys.exit 02:51:01, no report; same shape
  as 11407 and 11421. splice#8363 still open.
  11668 (2026-11-19, release-line-0.10.2 b12a1318f6, Canton 3.7.3, simtime (2), TimeBasedTestNetPreview setup, second
  suite): 1 -> 4 at 18:39:15.657, sv1 rejects PAR::sv3 and PAR::sv2 156 and 418 ms after the switch (`P2P connectivity
  is not ready`); two joiners stuck (sv2 and sv3 both wait on mediator traffic), sv3 init timeout 18:42:40.417, report
  kept (5 failed: blast radius plus teardown leak cascade). Third hit on the 0.10.2 line. splice#8363 still open.
  11684 (2026-11-20, main ae237a8ac9, Canton 3.7.4 final, simtime (2), SvTimeBasedAmuletPrice amulet price votes and
  metrics, second suite): 1 -> 4 at 12:06:47.939, MED::sv4 add: sv3's push 219 ms before the switch, sv4's, sv2's and
  sv1's rejected 24-26 ms after it (`P2P connectivity is not ready`), not retried; sv4 waits on mediator traffic, sv4
  init timeout 12:12:32.549, ResetDecentralizedNamespace fails, ResetTopologyStatePlugin sys.exit 12:13:45, no report;
  same shape as 11407, 11421 and 11643. First hit recorded on Canton 3.7.4 final. splice#8363 still open.
  11685 (same run, simtime (1), SvExpiredRewardsCollectionTimeBased setup, third suite): 1 -> 4 at 12:07:58.519
  (epoch 112), MED::sv4 add: the pushes of sv3, sv1, sv2 and sv4 all rejected 26-691 ms after the switch (`P2P
  connectivity is not ready`), not retried; sv4 waits on mediator traffic, sv4 init timeout 12:13:24.656, report kept
  (6 tests passed, 4 suites aborted: this one plus blast radius into TokenStandardCliTestDataTimeBased,
  WalletTxLogWithRewardsCollectionTimeBased and WalletAmuletPriceTimeBased, each sv1 init timeout). Same shape as
  11415 and 11535. Second failed simtime shard of this family in one post-merge run. splice#8363 still open.
  11686 (2026-11-20, main d3f9006367, Canton 3.7.4, simtime (0), TrafficBasedRewardsSvAppTimeBased setup, first suite):
  1 -> 4 at 15:34:10.993, splitwellParticipant's VettedPackages broadcast on global rejected by sv1 3.25 s after the
  switch (`P2P connectivity is not ready`, still 1 authenticated node at 15:34:16.563), not retried, outbox flush never
  closes; splitwellValidator stuck in `Set up app instance splitwell` (its `Vet packages` step had finished 1.5 s
  before the switch), splitwell_provider never created, providerSplitwellBackend JVM exit 15:38:13.899
  (`UserNotFound(splitwell_provider)`), no report; same shape as 11413, 11560 and 11565. splice#8363 still open.
  11687 (2026-11-20, main a0e7406501, Canton 3.7.4, simtime (0), TrafficBasedRewardsSvAppTimeBased setup, first suite):
  1 -> 4 at 20:59:44.736, splitwellParticipant's VettedPackages broadcast on global rejected by sv1 4.78 s after the
  switch (`P2P connectivity is not ready`, still 1 authenticated node at 20:59:50.899), not retried, outbox flush never
  closes; splitwell_provider never created, providerSplitwellBackend JVM exit 21:03:37.087
  (`UserNotFound(splitwell_provider)`), no report; same shape as 11686. splice#8363 still open.
  11688 (same run, simtime (3), TimeBasedTestNetPreview setup, second suite): batch-never-ordered variant (11326,
  11358). 1 -> 4 at 21:07:03.081; the serial-3 MediatorSynchronizerState adds of MED::sv3 and MED::sv4 were pushed
  21:07:02.376-21:07:03.005, all accepted by the sv1 mempool; the four accepted 370 to 55 ms before the switch were
  batched with reference epoch 72 and never ordered (zero `Rejecting submission request by`; the trace ends on
  `Dropping network message to unknown SEQ::sv<N> (possibly unauthenticated as of yet)`). sv3 and sv4 wait on mediator
  unlimited traffic until the sv3 init timeout 21:10:37.810; next suite fails sv1 init, its teardown leaks the
  environment (H, 11347 shape). Confirm this variant per push: trace id of `Attempting to push`, then
  `LocalBatchCreated ... reference epoch <old>` without `Block N being output` or `validated to` for its message id.
  11690 (2026-11-22, scheduled mainnet-Daml run, main a0e7406501, Canton 3.7.4, simtime (2), TimeBasedTestNetPreview
  setup, second suite): 1 -> 4 at 05:30:14.440, MED::sv4 add (serial 4): sv4's, sv1's and sv3's pushes 559 to 196 ms
  before the switch, sv2's rejected 8 ms after it (`P2P connectivity is not ready`), not retried; sv4 waits on
  mediator traffic, sv4Validator JVM exit 05:34:52.952 (`UserNotFound(sv4_validator_user)`), no report; same shape as
  11400, 11410, 11540 and 11559. Third hit on a0e7406501 (11687, 11688). splice#8363 still open.

## C. False off-boarding conclusion during onboarding state transfer (10048 family) - FIXED
- Signatures: `Received topology for epoch N, but this node isn't part of it (i.e., it has been off-boarded)` on a
  newly onboarded sequencer; downstream: initDso timeouts (10137), `Failed to fetch P2P server authentication token
  ... Member SEQ::svN access is disabled` (10010: the frozen node judges peers against its stale topology).
- Fixed by DACH-NY/canton#35600: present in 3.5.17+, and 3.6 snapshots from 20260910 on (markers
  `Might already have all necessary blocks for new epoch`, `Detected need for catch-up state transfer (to `).
  Absent in 3.5.16 and 20260909.20244. No supported line is exposed (0.8.0 unsupported). Do not add ignores.
- Beware: `isn't part of it` also appears legitimately when a test really off-boards a sequencer
  (canton-standalone-sv123-non-sv1-svs has its own ignore file).

## D. Simulated time jump with a command in flight (round-opening waits)
- Signature: `Check waiting for open round automation (should create OpenMiningRound N) ... (a, b, c) was not equal to (a+1, b+1, c+1)`
  (TimeTestUtil.scala) in simtime shards; also `Domain time delay is currently 10m ... waiting until delay is below 2 minutes` (sv1).
- Confirm: `Advancing sim clock to <T>` followed within 20 ms by `MAX_SEQUENCING_TIME_EXCEEDED` on the sequencer,
  `Task scheduler waits for tick of sc=...` on sv1Participant, and a `Received TimeProof(<pre-jump time>)`; then no
  `Validating event` on sv1Participant until ~30 s later.
- Mechanism: the sequencer drops the in-flight message, the participant blocks until its 30 s wall-clock timeout,
  domain time stays behind, SV automation pauses. #5779 fixed `advanceTimeAndWaitForRoundAutomation` (90 s);
  `advanceTimeAndWaitForRoundOpening` was left at 20 s.
- Parent 9740 (2026-08-19), dup 10170. Fix: `ray/fix-round-opening-wait-budget`.
- Sibling: `advanceTimeAndWaitForRoundAutomation` failing with rounds one too far ((7, 7, 8, 9) vs (6, 6, 7, 8)) right
  after a multi-hour `advanceTime`: the round automation is still catching up a backlog when the next helper snapshots.
  UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest (37 h coupon TTL jump) = splice #7206 / 10173; fix
  `ray/fix-10173-unhide-expire-coupon-ttl` (short TTL + advanceRoundsUntil). Grep: `advancing time by PT[0-9]+H` followed
  by `successfully advanced the rounds` lines during the next `(act) advancing time` clue.
- Sibling: `advanceTime(PT25H)` then `LOCAL_VERDICT_INACTIVE_CONTRACTS` on transfer-preapproval send in
  WalletMintingDelegationTimeBasedIntegrationTest = 10060 / splice #7223, fixed on main by #7261; release lines
  need the backport (10154, 10166, 10171; `ray/backport-7261-release-line-0.8.3`).

## E. Missing backports to release lines (check first for any release-line-* failure)
- #7261 (minting delegation time jump), #7305 (per-port Vite deps cache, 10156 / 9704), #7304 (wallet allocation
  UI test, 10157 / 10120), #7299 (package downgrade in UnsupportedPackageVettingIntegrationTest, 10169 / 9965).
- #8425 (f1e385ffac, ignore the BFT orderer wording of `Sequencer is unhealthy, so disconnecting all members`, parent
  11211): not on release-line-0.10.2 or 0.10.1 as of 2026-11-19. Hit: 11662 (release-line-0.10.2, splitwellSequencer,
  during Canton startup). Confirm: `DB_CONNECTION_LOST(13,...)` from the DB health check on the same sequencer seconds
  before the WARN, `'sequencer' is now in state SequencerHealthStatus(active = true)` seconds after, and
  `git show <sha>:project/ignore-patterns/canton_log.ignore.txt | grep -n 'unhealthy, so disconnecting'` showing only
  the `Can't connect to database` wording.
- Check: `git merge-base --is-ancestor <sha> origin/<line>`; `git log origin/<line>..origin/main -- <test file>`.

## F. Frontend test infrastructure
- Vite dev servers for alice/bob/charlie splitwell run `vite --force` from one directory; without #7305 they race
  on `node_modules/.vite/deps` (`ENOENT ... rename ... deps_temp` in `npm-splitwell-*.out`, Firefox
  `disallowed MIME type ("")` for every module, empty `<div id="root">`). 9704 / 10156.
- testing-library 1 s `findBy` budgets versus the 15 s vitest budget: `navigateToLegacyGovernancePage` (10145),
  un-awaited `waitFor` in set-amulet-rules-form.test.tsx (10141). Fix: `ray/fix-sv-ui-test-timeouts`.
- Describe-level `}, 7500)` in wallet.test.tsx makes near-limit tests fail on slow runners (10157 B).
- Auth0 login stall on the second login in WalletAuth0FrontendIntegrationTest (10143): only the first login had the
  retry wrapper. Fix: `ray/fix-auth0-relogin-retry`.
- ANS search button remounted between check and click: `StaleElementReferenceException` on `search-entry-button` in
  AnsFrontendIntegrationTest (already-taken clue, AnsFrontendIntegrationTest.scala:51 via eventuallyClickOn
  FrontendIntegrationTest.scala:866). The typed name's 500 ms debounce fires the lookup during the click, and
  DisableConditionally renders a different tree while loading. Confirm: job log `-> id: search-entry-button]` after
  `subscription payments and then list it *** FAILED ***`. 11187 (parent, fixed by splice#8418, 2026-11-12), 11300,
  11361; 3 of 98 frontend-wall-clock-time (0) main jobs 2026-11-04 to 11-11.

## G. "All tests pass, one log line fails checkErrors" items still without a fix
- 10438 / 10302 `Locked connection was lost, trying to rebuild` (DbLockedConnection, any node): the read-only
  probe and `DbLockPostgres: checking lock` race for the single KeepAliveConnection; three `NoConnectionAvailable`
  probes in a row, each 1-3 ms after a lock check on the same node, then rebuilt in < 100 ms. Canton race (older:
  #1283, canton-network-node#15508). No ignore pattern; confirm by pairing each probe failure with a lock check.
- 10084 IndexerState reconnect-drain WARN (Canton `retryLogLevel`; ignore rejected).
- 10140 `SERVER_OVERLOADED` on DownloadTopologyStateForInit: test-only limit 3 in `sequencers.conf`; fix
  `ray/fix-topology-init-limit` (3 -> 7).
- 10144 GetPreferredPackages INTERNAL while a DAR upload merges into the package metadata view (Canton race,
  cn-test-failures 9136).
- 10147 `[UNEXPECTED] State transition ... Connecting (unchanged)` WARN at standalone shutdown (Canton log level).
  Same family, other line (10612): `Token refresh failed` with `RejectedExecutionException: event executor
  terminated` in a standalone `_after_shutdown` log, preceded by `At least 1 active readers prevent closing ...
  p2p-create-peer-sender` and 0.5 s token retries against the closed peer.
- 10606 (new): scan app serves an HTTP request after its DB storage closed (test teardown stops scans before SVs);
  `DB_STORAGE_DEGRADATION ... RejectedExecutionException ... [Terminated ...]` retried as `transient error (request
  infinite retries)` until environment close. Confirm: `Stopping <scan> node` tens of ms before the first
  DB_STORAGE_DEGRADATION, and an incoming `HTTP GET` on that scan in between (peer trigger trace).
  Release lines only: main closes the HTTP admin server before the node since #7711; fix is backporting #7711.
- 10676 (new, regression from #7922): validator PackageVettingTrigger unvets the packages of a config vote right after it
  executes: vote requests come uncached from Scan, AmuletRules from the BftScanConnection cache, so the pending config
  leaves the unvetting input before the new AmuletRules arrives. Confirm: `Running package unvetting` on the validator
  with `Vetting for schedule` still showing only the old entry, then re-vetting `valid from` the vote execution time.
  Second main hit: 10686 (run 40520298067, resource-intensive (1), BootstrapPackageConfigIntegrationTest.scala:494).
- 10495: offboarded mediator's in-flight aggregated send rejected `SEQUENCER_SUBMISSION_REQUEST_MALFORMED ... is not part
  of the mediator group` after the group change, in the standalone after-shutdown log. Reonboarding ignore files have the
  pattern; the mediator-offboarding file lacks it.
- 10121 / 10142 SummarizingMiningRoundTrigger ERROR on a retryable "totals not yet computed"
  (`ray/fix-summarizing-round-log-noise`).
- 10084 re-triaged 2026-09-22: `IndexerState.ensureNoProcessingForSynchronizer` (reconnect path) retries at 200 ms
  forever with no `retryLogLevel`; `RetryWithDelay` promotes Forever retries to WARN after `complainAfterRetries = 10`
  (private constant, identical in 3.6 snapshots 20260910 and 20260916). Confirming grep: `Ensure no processing for
  synchronizer` INFO lines for 2 s then WARN pairs, then `Computing starting points` on the same participant. No config
  key reaches it; fix is a Canton one-liner (`retryLogLevel = Some(Level.INFO)` on that Pause). 1 hit in 59 failed main
  runs 09-10..09-21.
- 2026-11-03: 10438 / 10302 third hit 11155 (main 8fd9921238, wall-clock-time (3), Canton 3.7.1): splitwellMediator
  pool-2 at Canton startup 09:26:50.058, probes at 38.641, 44.040, 50.047 each 1-9 ms after a lock check, rebuilt
  in 99 ms. Packet `11155-splitwell-mediator-locked-connection-lost.md`.
- 9445 GetConnectedSynchronizers INTERNAL while a participant disconnects a synchronizer (Canton race between
  `removeAndClose` and re-adding the topology client; open). Participant line
  `failed with INTERNAL/Failed retrieving SynchronizerTopologyClient` ignored since splice#7334
  (canton_log.ignore.txt:219). Client echo NOT ignored: app-log ApiClientRequestLogger ERROR
  `GetConnectedSynchronizers to <port>: failed with INTERNAL/An error occurred. Please contact the operator` plus
  DomainIngestionService WARN `processTaskWithRetry failed with a non-retryable error` and ERROR `Skipping processing of
  PeriodicTask` (6016 aliceValidator 2025-10; 11364 sv2Scan 2026-11-11, SvOnboardingAddlIntegrationTest, reconnect by
  ReconcileSequencerConnectionsTrigger after sv4 sequencer appears). Confirm: the participant line with the same trace
  id 1 ms earlier, and `DisconnectSynchronizer ... received` on that participant just before it. Close as dup of 9445.
  Third client-side hit 11645 (2026-11-19, main 1eafba3113, mainnet-Daml nightly wall-clock-time (8), Canton
  3.7.4-snapshot.20261112): sv2Validator in SequencerPruningIntegrationTest, reconnect after the sv2 sequencer appears,
  participant line 05:32:56.318, client lines .323-.325.


## H. Evidence loss
- `ResetTopologyStatePlugin` `sys.exit(1)` in teardown (10137, 10139): no report, checkErrors skipped, later
  suites lost. Fix: `ray/fix-reset-topology-plugin-no-exit`.
  Third hit (run 35611022158 wall-clock-time (2), ref 10183): the fourth owner's reset proposal arrived after
  three signatures had already authorized owners = {sv1}; `TOPOLOGY_NO_APPROPRIATE_SIGNING_KEY_IN_STORE`, then 15
  zero-delay restarts inside the 250 ms effective delay all hit `TOPOLOGY_MAPPING_ALREADY_EXISTS`. Confirming grep:
  `zcat canton_network_test.clog.gz | grep -a -c 'Restarting decentralized namespace reset'` = 16 within 100 ms.
  Fix: `ray/fix-10183-reset-namespace-late-proposer` (tolerate the late proposer; wait loop decides).
- `NodeBase` `sys.exit(1)` on init failure in the shared sbt JVM: silent 60-minute hang recorded as cancelled
  (10088-A, #7289; branch `ray/fix-fail-fast-init`). Second hit 10180 (LSU shard, bobValidatorLocal init). Signature:
  GH conclusion cancelled, `Received SIGINT` at start+60 min, no ScalaTest summary, test log ends on
  `app initialization: Initialization failed`, canton logs continue. Confirming grep: `zcat canton_network_test.clog.gz | tail -1`.
- 10139 cause: reference block sequencer `insert block` SQLSTATE 40001 retry storm with four sequencers on one
  Postgres until the 8-connection pools are exhausted (Canton / topology size).
- Teardown leak cascade (10176; July run 28921009132): an exception from any plugin's `beforeEnvironmentDestroyed`
  skips `environment.close()` (vendored `EnvironmentSetup.manualDestroyEnvironment` runs the hook outside its `try`;
  canton main unchanged 2026-09-15.22). Signature: one teardown failure, then every later test fails at `Creating
  fixture` with `Could not create Prometheus HTTP server` / `Address already in use` (:25000) and shared-environment
  suites abort with zero tests. Confirming grep: the old environment's `config=<id>` keeps logging after the failure.
- 11301 (2026-11-09, app-upgrade (0), main 7d3d5f17da): `ResetTopologyStatePlugin` sys.exit after
  `Failed to reset decentralized namespace`; the test had timed out during bundled-node init (family AL) and stopped sv3
  right after its sequencer joined the ordering topology (epoch 41 at 15:18:00.355, size 3, strong quorum 3, sv3 first
  leader, no later epoch). Confirming grep: `zcat canton_network_test.clog.gz | tail -1` is the reset ERROR.
- 11347 (2026-11-11, teardown leak cascade): UpdateHistorySanityCheckPlugin filters scans by `is_initialized`
  (node running) and then calls `scan.automation`, which needs the app state; a scan still initializing (sv2Scan in
  `Get primary party`, waiting for sv1) throws `Node doesn't have any app state` and the cascade follows. Fix
  candidate: filter on app health active, or run beforeEnvironmentDestroyed inside the `try` (canton change).

## H2. LSU: validator init against a non-active psid
- 10088-B (5-min hang, infinite retry) -> fixed by #7311 (WARN + skip). 10174: the WARN fails checkErrors because #7311's
  ignore regex `... active (status: .*), ...` has unescaped parentheses (ripgrep group), fix `ray/fix-10174-lsu-source-ignore-regex`.
  Mechanism: a validator restarted after the LSU uses the participant's registered (stale) psid; Canton rejects the
  modify; on retry the participant reports LSU_SOURCE. Rare on main (1 of 40 runs). Design note: prefer the active psid.
- 10180 (variant C): same restart, the OTK rotation check `listAllTransactions(Synchronizer(logical id))` in
  `NodeInitializer.rotateOwnerToKeyMappingNotSignedByKeys` is not wrapped in `retryProvider.retry`; between the LSU
  target registration (36-0 deactivated) and 36-2 reaching Ok (~470 ms) the participant answers
  `TOPOLOGY_STORE_NOT_FOUND: No active synchronizer found` and the validator exits (family H). App fix: retry it like
  `findOwnerToKeyMappingThatUsesNamespaceSigningKey` does, NOT_FOUND is already in `retryableStatusCodes`.
- 11404 (variant C, second hit, 2026-11-13, main 9f97b5cf4e, Canton 3.7.3, scheduled mainnet-Daml run): test 2 step
  "sv4 upgrades" restarts the sv4 apps; the sv4 SV app's PerformManualLsu marks 37-2 Active 50 ms before the
  participant creates its topology store, and sv4Validator's "Vet packages" (`vetDars` -> `lookupVettingState`,
  unretried) reads in that gap: `TOPOLOGY_STORE_NOT_FOUND: Topology store '...::37-2' is not known`, sys.exit, job
  cancelled at 60 min. Confirm: the ListVettedPackages failure between `Setting status of ... 37-2) to Active` and
  `LSU: About to copy topology` on the same participant. Fix: retry the read (and the 10180 one, still unwrapped).
- 11682 (variant C, third hit, 2026-11-20, main 7b71d35779, Canton 3.7.4): test 2 step "Restart sv2" runs at the
  upgrade time by construction (the test sleeps until it, then stops and starts sv2). sv2Participant's automatic
  LSU sets 37-0 to LSU source at 11:10:36.776 and 37-2 to Active at .789; the sv2 SV app's
  `DsoPartyHosting.isDsoPartyAuthorizedOn` in `JoiningNodeInitializer.joinDsoAndOnboardNodes` (unretried, the
  tolerance only applies with manualConnect=true) sends `ListPartyToParticipant` on the logical store at .788:
  `TOPOLOGY_STORE_NOT_FOUND: No active synchronizer found`, sys.exit, job cancelled at 60 min. Confirm: the
  `ListPartyToParticipant` failure from `SV=sv<n>` between `Setting status of ... to LSU source` and `Automatic upgrade
  was successful` on the same participant. Not a duplicate of 11404 (other app, other read, its fix does not cover
  it). Variant C call sites so far: `NodeInitializer.rotateOwnerToKeyMappingNotSignedByKeys` (10180),
  `ParticipantAdminDarsConnection.vetDars` (11404), `JoiningNodeInitializer` (11682).
  Fix branch `pperek/fix-11682-sv-init-dso-party-read-retry` (6189f34745): retry around the read, not pushed.
- Checking an ignore pattern: `LINE=$(zcat ... | grep -a -m1 '<text>'); echo "$LINE" | rg -c -e '<pattern>'`.

## H3. Trigger pause timeout: `Waited 5 seconds. (TriggerTestUtil.scala:93)`
- `setTriggersWithin` pauses with `pause().futureValue` (5 s). `PollingTrigger.pause()` waits for the running task, so a
  task that is retrying a "retryable" ledger error (deadline-exceeded, CONTRACT_NOT_FOUND) blocks the pause. Confirming
  grep: the trigger's `failed with a retryable error` / `Retrying after a number of N failures` lines in the 5 s window.
- 10175 (7864/#5176 lineage): UnclaimedActivityRecordIntegrationTest resumed alice's merge trigger for 1 ms between two
  blocks after the record's 10 s expiry had passed. Fix: pause across both blocks (outer setTriggersWithin), never a
  bigger expiry margin. Also check `actAndCheck`'s 5 s max poll interval when a block "takes too long".
- 10176: same timeout from `UpdateHistorySanityCheckPlugin.beforeEnvironmentDestroyed` pausing a scan's AcsSnapshotTrigger
  whose `UpdateIncrementalSnapshotTask` transaction took 10.67 s to complete after 1 ms of SQL (Postgres commit
  latency outlier on the runner; p99 350 ms in that shard vs 10-30 ms elsewhere). No retry lines: the confirming
  grep is the gap between the store's `Updated incremental snapshot` and the trigger's `Completed processing` for
  the scan named first in `Checking update histories for List(...)`. Fix `ray/fix-10176-sanity-check-pause-timeout`
  (1 min pause budget in the plugin); the cascade itself is family H.

## I. Test races (venue and splitwell)
- 8784: settlement venue submits `OTCTrade_Settle` before its participant ingested the AmuletAllocation contracts
  (`CONTRACT_NOT_FOUND`). PR #6013's wait compared two unrelated codegen ContractId classes and could never
  match. Fix: `ray/fix-venue-allocation-wait` (compare `.contractId` strings).
- 10149: BulkStorageCommitFromStagingTest re-stubs Mockito mocks while the flow calls them
  (`ClassCastException` Promise -> Uri, then `expectNext(20 s)` timeout). Fix: `ray/fix-bulk-storage-test-stubbing-race`.
- 10179: MemberTrafficIntegrationTest compares the participant admin API's traffic view (lags until the receipt of
  the last submission is processed, ~80 ms) with Scan's sequencer admin view taken 27 ms later; alice's validator
  automation submits 2-3 times a second during the suite. Confirming grep: `TrafficConsumedManager ... Consumed N for
  PAR::aliceValidator` with a sequencing timestamp between the two `TrafficControlState` responses. Fix
  `ray/fix-10179-member-traffic-status-consistent-read` (compare inside `eventually()`).
- 10690: TokenStandardCliIntegrationTest reads an external party's balance before the validator's
  ExternalPartyWalletManager created its wallet (404 `No wallet for external party`). The client
  `GetExternalPartyBalance.handleOk` has no NotFound case, so it is a `MatchError` / CONSOLE_COMMAND_INTERNAL_ERROR that
  `eventually()` does not retry. Confirm: `Creating wallet service and retry provider for external party <p>` logged
  after `external-party/balance ... 404 Not Found`. Fix: NotFound case in the client (production) and wait for the
  wallet in `ExternallySignedPartyTestUtil.checkExternalPartyExists`.
- 10691: SvOnboardingAddlIntegrationTest waits 1 minute for sv4's own sequencer, which is published with a 60 s
  `availableAfter`; synchronizer-time lag on an idle synchronizer pushes the reconcile past the deadline. Confirm:
  sv4Validator's `For synchronizer 'global' at time` lines only list :5408 after the test failed. Fix: 2 minutes.
- 11238: SvInitializationIntegrationTest "connect to all domains during initialization" reads sv4's
  `list_connected()` once after `sv4Backend.startSync()`, while sv4Validator's ReconcileSequencerConnectionsTrigger
  bounces the connection (disconnect + reconnect, ~60 ms) to add a sequencer that just passed its 60 s `availableAfter`.
  Signature: `inside ... was: List() (SvInitializationIntegrationTest.scala:82)`. Confirm: sv4Validator
  `reconnect to the synchronizer Synchronizer 'global' for new sequencer configuration to take effect` and the
  canton `Disconnected from Synchronizer 'global'` of sv4Participant within 100 ms before the test's
  `ListConnectedSynchronizers()`. Earlier hit 5051 (2025-07, unanalysed). Fix: `eventually()` around the read.
- 11394 (2026-11-12, wall-clock-time (5), main 0911e29902): sv4Participant disconnected 14:43:55.610 by the sv4Validator
  reconnect, test list_connected 14:43:55.800, reconnect answered 14:43:55.865. Second hit; fixed, #8416 (2026-11-12).
- 11528 (2026-11-16, wall-clock-time (4), main 213565d133, Canton 3.7.4-snapshot.20261112): same bounce, on the submit
  path. FeaturedAppActivityMarkerIntegrationTest submits `FeaturedAppRight_CreateActivityMarker` through the console
  on bobParticipant at 22:16:53.832 while bobValidator's ReconcileSequencerConnectionsTrigger adds sv4's sequencer
  (disconnected 22:16:53.845-22:16:53.956). Signature: `CommandFailure` with
  `NOT_FOUND/SUBMISSION_SYNCHRONIZER_NOT_READY ... Trying to submit to a disconnected or not configured synchronizer`.
  Confirm: test log `Got canton error ... SUBMISSION_SYNCHRONIZER_NOT_READY ... Retrying: false`, and on the
  participant `Disconnecting from Synchronizer 'global'` within a few ms of `Routing the transaction to synchronizer`
  for the same trace id. The console retry policy (`EnvironmentSetup.scala`, splice#2816) covers
  NOT_CONNECTED_TO_SYNCHRONIZER and NOT_CONNECTED_TO_ANY_SYNCHRONIZER only. Fix: add
  `TransactionRoutingError.ConfigurationErrors.SubmissionSynchronizerNotReady` to `retryableErrorCodes`, branch
  `pperek/fix-11528-retry-submission-synchronizer-not-ready`. A hit on a sha with that fix is a different cause.
- 10737 (dup 11531): BulkStorageCommitFromStagingTest "wait until all objects are known to the peers, and report
  disagreement on consensus correctly" fails at line 341-342 with `forAtLeast(1) failed` and a WARN `The following
  Scan URLs disagreed with consensus` that names only one of scan_5, scan_6 (`did not include substring "scan_5"` or
  `"scan_6"`). In the last step all 7 mocked scans have data and the BFT call uses the first 2f + 1 = 5 whose
  `hasData` callback lands (`BftCallExecutor` phase 1), so one disagreeing scan can be left out. Confirm in
  `canton_network_test.clog.gz`: the `Found enough scans with available data: List(...)` line right before the last
  WARN of the test lacks scan_5 or scan_6. Hits: 10737 (2026-10-28, PR run 40619618938), 11531 (2026-11-17, main
  dbf2ab6c8f). Fix: splice PR #8083 (scans 0 and 1 report no data in the last step), open and approved since
  2026-10-28, not merged as of 2026-11-17; 10737 was closed as completed without it. A hit on a sha with #8083 is a
  different cause.


## K. Sim time: mediator pruning scheduler backoff after a jump of at least the retention period
- Signature (canton-simtime log WARN): `MediatorPruningScheduler ... Backing off 1s or until next window after error: Requested
  pruning timestamp [T] is later than the earliest available pruning timestamp [T - 30 s]`, all tests pass.
- Mechanism (10184 (simtime (2))): the SV app installs a 10-minute cron with 30 d retention on its mediator; the
  scheduler prunes up to `clock.now - retention`; a sim-time jump >= 30 d puts that at the pre-jump clean timestamp, and
  `Mediator.prune` refuses anything above `clean - confirmationResponseTimeout` (30 s) until the mediator sees a
  post-jump event. Confirming grep: `advancing time by PT720H` (or longer) in the test log within a second before the WARN,
  and the other mediators logging `Pruned up to <same T>` a few seconds later.
- Fix: sim-time-only ignore in `canton_log_simtime_extra.ignore.txt` (`ray/fix-10184-mediator-pruning-backoff-ignore`);
  do not put it in `canton_log.ignore.txt`, in production the line means the mediator lags the clock by > retention.

## L. Reference sequencer `insert block` SQLSTATE 40001 retry storm (slow ordering, not only teardown exits)
- 2026-11-05: the reference driver is being removed from Canton (DACH-NY/canton#29603); splice#8234 (rautenrieth-da,
  open) moves every test to DA BFT. After it merges, L cannot recur; close new hits on main as dups only if their run
  predates the merge. DA BFT makes simtime tests about 33% slower (cause unknown), so watch the 40 min sbt budget
  (10358) and BFT-specific failures (BFT orderer DB health, 11211; ordering delay while a BFT peer joins, 11165).
- 2026-11-05 (11189, 11191, resource-intensive (0), main 11-04): BootstrapPackageConfigIntegrationTest.scala:291
  `observe AmuletRules with upgraded config` (45 s after the votes; 19 s vote expiry leaves about 20 s): the
  DsoRules_CloseVoteRequest waited 20-26 s in ordering (Sv1 insert block chain 21.7 s from 18:15:48.9 in 11191, 18.0 s
  from 15:26:11.1 in 11189) and completed after the check. 2 of 62 resource-intensive (0) main runs since 10-31.
- 2026-11-09 (11313, resource-intensive (0), main 91715b25c0, after #8234): same check at :291, not L (DA BFT): a
  stall of the Canton JVM (log rate under 3 % for 7 s) made all four segments of epoch 51 time out, sv4 then sv3 were
  blacklisted and epoch 52 needed view changes on every segment; the close vote committed 26 s after the check.
  Confirm: `New epoch` lines with a non-empty blacklist while `size = 4` is unchanged, and `moving from view number 0
  to 1 after reaching local timeout` on every segment of one epoch. Related: 11305 (slow BFT ordering, no topology
  change). Test budget fix: 90 s at line 274, PR splice#8412.
- 2026-11-10 (11329, resource-intensive (0), main 6c0fee0505, Canton 3.7.3-snapshot.20261109): dup of 11313, close
  committed 27.6 s after the 45 s check, topology unchanged. Likely stall cause in both runs: once the vote request
  with the latest package config exists, the SV and validator vetting triggers upload every intermediate DAR (35 per
  SV, 29 per validator, 192 total) into the shared Canton JVM; canton.clog lines/s collapse within a second of the
  first upload (11329: 10033, 4693, 182 at 09:51:03-05; 11313: 10025, 4131, 53 at 17:13:09-11). Confirm:
  `PackageService/UploadDar` `sending request` per node within 1 s of `Finished clue: (act) sv1-3 accept vote request`,
  plus the lines/s drop in the same second. Unverified: no CPU/GC data, no passing run checked. 2 of 32
  resource-intensive (0) main runs since 11-06.
- 2026-11-11 (11360, resource-intensive (0), main 2fc780a883, Canton 3.7.3-snapshot.20261109): dup of 11313, third
  main hit. Same onset (192 DAR uploads from 14:01:21, canton.clog 10300 to 134 lines/s at 14:01:23), topology
  unchanged. Variant: the blacklisted node was sv1, whose sequencer carries every SV participant submission, so the
  close confirmations sat 38 s in its mempool and the mediators rejected the first close with
  MEDIATOR_SAYS_TX_TIMED_OUT; retries got SEQUENCER_BACKPRESSURE (SEQUENCER_OVERLOADED, node blacklisted) until sv1
  returned. Close committed 74.6 s after the votes (11329: about 73 s), inside the 90 s of splice#8412 (merged
  2026-11-12) but with little margin. Confirm: `MEDIATOR_SAYS_TX_TIMED_OUT` or late `succeeded(OK)` on
  DsoRules_CloseVoteRequest after `Failed clue: (check) observe AmuletRules with upgraded config`, plus the 11329
  confirm. 3 of 55 resource-intensive (0) main runs since 11-06, all this check.
- Signature: a ledger command or confirmation takes 10-25 s; participants log `timeout-result has not completed after
  N milliseconds` / `succeed successfully but slow`; on the sequencer, `enqueued reference sequencer store request` is
  followed by `Created batch reference-driver-requests-batch` many seconds later, and `DbStorageSingle` logs
  `The operation 'insert block' has failed ... Retrying after` with growing backoff and `SQL state: 40001`.
- Confirm: pair first `enqueued ... tag send` with the next `Created batch` on globalSequencerSv1 and list waits over
  5 s; grep the `insert block` retry chain in that window. Block production (`Processing block`) keeps running.
- Cause: the 4 global reference sequencers share one driver database (`sequencer_driver[_simtime]`, per-synchronizer;
  splitwell has its own) and its `blocks` table is the ordering ledger. `DbReferenceBlockOrderingStore.insertRequest`
  (3.7.1 jar) inserts each batch as `insert into blocks (id, request, uuid) values ((select coalesce(max(id) + 1, 0)
  from blocks), ...) on conflict (uuid) do nothing` under SERIALIZABLE (`insert block`), so any two concurrent
  inserts conflict and Postgres aborts one with 40001; retried with exponential backoff. The test configs run the
  reference driver in both wall-clock and sim-time shards (`sequencers.conf:33`).
- 2026-11-03 (11160): not a stall of all writers. globalSequencerSv1 stored nothing from 12:29:13.462 to 27.541 (11
  retries, backoff to 6.373 s) while Sv2-4 stored 31-32 batches each (heights 6741-6839, blocks without events, no
  client requests enqueued by them); the only client request in the window, the one sent through Sv1, waited 13 s.
  Why Sv2-4 store request-free batches (likely driver time-keeping) and why Sv1 lost every retry: not verified.
  Whole shard (12:07-12:33): 9508 stored batches, 2936 insert failures, 2170 retry chains; failures before success
  1: 1484, 2: 616, 3: 64, 4: 5, 8: 1 (Sv1, 11160). Failure ratio per stored batch rises through the shard
  independent of load (0 before 12:20, 0.12 at 12:21 with 846 enqueued/min, 0.48 at 12:29 with 311, 0.69 at 12:31
  with 604). So 40001 is routine and harmless; failures need an outlier chain plus a short waiter budget.
  Driver details (3.7.1 jar): `ReferenceSequencerDriverFactory.create` builds `ProcessingTimeout()` with defaults, so
  the driver storage ignores `timeouts.processing.storage-max-retry-interval = 1s` from canton-basic.conf
  (canton-network/splice#5173) and backs off up to `maxRetryInterval` 10 s; `emptyBlockIntervalMillis` default
  `Some(500)` ticks an empty-block insert per driver every 500 ms regardless of load; `writeParallelism` 1.
  Postgres log (GCP, 11160): all aborts `Canceled on identification as a pivot, during write`; 2-3 of 4 writers
  aborted together in 26 of 58 collisions.
  Fix options: Canton passes node timeouts to the driver storage (cap applies) or drops `max(id) + 1` under
  SERIALIZABLE; test config raises `sequencer.config.empty-block-interval-millis` (side effects unchecked).
  Other runs (`.claude/skills/ci-triage/scripts/ref_seq_contention.py`, 2026-11-04): 11144 simtime (3): 13775 stored, 4641 failed, chains up
  to 5 failures / 2.3 s, no delay over 1 s; 11141 frontend-wall-clock-time (2): 9885 stored, 2966 failed, chains up to
  6 failures / 2.5 s, one delay 1.495 s (uncapped). Rate per minute 0 in the first minutes of a shard, then
  0.15-0.6 per stored batch, not monotonic. Only shards started with the reference driver are exposed: simtime
  (`-s`), frontend wall-clock, resource-intensive, key-download, lsu, signatures, app-upgrade, compose (`-w`);
  wall-clock-time (`-we`, SPLICE_USE_CANTON_BFT_SEQUENCER=1) runs Canton BFT and has no `insert block`.
  Exposed classes on main 7307892450 (2026-11-04): 73 (simtime 33, dyn-sync-params 1, frontend-simtime 5,
  docker-canton-simtime 2, frontend-wall-clock 14, resource-intensive 3, key-download 8, lsu 1, roll-forward-lsu 2,
  signatures 1, app-upgrade 1, docker-compose 2); wall-clock-time (91 classes) runs BFT. StandaloneCanton follows the
  same switch (`IsTheCantonSequencerBFTEnabled` = SPLICE_USE_CANTON_BFT_SEQUENCER).
- Occurrences: 10139 (wall-clock nightly, 27 s gap, then ResetTopologyStatePlugin exit), 10197 (simtime, 19.6 s wait,
  20 s test budget). 3.5.17/3.5.18 sim-time runs show the same storm with backoff under 0.4 s; 10197 reached 8.6 s.
- Fixes: per-test budgets where a check depends on one ordering round trip (`ray/fix-10197-bft-read-confirmation-wait`);
  the contention itself is Canton / test infra (fewer writers per DB, non-serializable insert, capped backoff).
- 2026-11-03: 11160 (release-line-0.10.0 simtime, Canton 3.7.1): globalSequencerSv1 store stall 18.2 s
  (12:29:09.478 to 27.680, backoff up to 6.373 s) makes `onboardWalletUser` fail with HTTP 400
  INVALID_PRESCRIBED_SYNCHRONIZER_ID: the new party is effective on splitwell 1.5 s after allocation but on global
  only after 13.2 s, and `installWalletForUser` gives up after 12 ClientCalls retries. Onboarding waits only on
  the Authorized store (`createPartyAndUser` -> `ensurePartyAllocated(TopologyStoreId.Authorized)`). Packet
  `11160-onboarding-retry-budget-reference-sequencer-40001-stall.md`.

## M. Slow confirmation round trips on the BFT synchronizer vs 10 s client deadlines
- Signature: `REQUEST_TIME_OUT(3,...)` / `CallOptions deadline exceeded after 9.99s` on a wallet/treasury call; the
  command still reaches Phase 7 shortly after the deadline. Confirm with the trace-id Phase 1-7 timeline (each hop
  1-4 s, none stuck). 10281: treasury deadline, FIXED by #7909 (10 s -> 30 s, 2026-10-23).

## N. SV1 bootstrap below the participant default protocol version (mainnet-Daml nightly, PV 36)
- Signature: every shard exits with `sv1 app initialization: ... INITIAL_ONBOARDING_ERROR(9,...) ... Unable to register
  onboarding topology transactions`; sequencer logs `TOPOLOGY_SERIAL_MISMATCH ... expected serial Some(2)` and
  `Processing 3/3 non-duplicate transactions` for sv1Participant NamespaceDelegation/OwnerToKeyMapping serial 1.
- Cause: SV1Initializer takes the participant identity raw from the Authorized store (getIdentityTransactions) while
  mediator/sequencer use generateOnboardingTopologyTransactions(initialProtocolVersion); the outbox later sends the
  PV 36 form with another hash. Since 2026-10-24 (#7944 / #7910). Issue 10520 (per-job dups closed); SV app fix open.
- Every nightly run files ~21 per-job issues (10-28: 10702-10720, 10722, 10723); cancelled shards (key-download) carry
  the same error. Check each job with one grep for `SV1Initializer bootstrapping Dso failed`, then close as dups.
- Cluster instance (likely, 10699/10701, unconfirmed): ciperiodic pins the SV synchronizer to PV 36
  (`cluster/deployment/ciperiodic/sv.yaml`, cni TODO #7542) and the ciperiodic workflows that bootstrap on main
  (sv_reonboard, lsu_operator, non_devnet) have been red since 10-23 17:05; Pawel closes the per-job issues as dups.
  deploy_upgrade is NOT this family (its base is the 0.9.x release, see J). Confirm with
  sv-1 `INITIAL_ONBOARDING_ERROR` in the CircleCI step output or GCP logs (da-cn-ci, cn-ciperiodicnet, sv-app).
- Fix: PR #8065 (participant part from generateOnboardingTopologyTransactions too), reproduced locally at PV 36.

## O. Participant crashes itself on disconnect: AcsCommitmentSender close hangs on a randomized send delay (Canton 3.7)
- Signature: `Task closing pipeline completion future did not complete within 60 seconds` and `Closing
  'AsyncCloseable(name=pipeline completion future)' failed!` on `AcsCommitmentSenderImpl:participant=X`, then `Fatal
  error occurred, crashing the node to recover from invalid state: Failed to disconnect from Synchronizer 'global'`.
  Downstream in splice: every Ledger API call to X is `UNAVAILABLE/io exception`, `ActAsKnownPartyAuthExtractor`
  answers 403 `Authorization Failed ... Reason: UNAVAILABLE: io exception`, console CommandFailure.
- Confirm: `Delaying sending commitment by N seconds` on X logged right after `killSwitch shutdown` closed. The delay
  (up to 0.9 x the 30 min interval) is not interrupted by the kill switch, so `onClosed` times out after
  shutdownProcessing (60 s).
- Trigger: any participant disconnect, in splice mostly the SV onboarding DSO party migration
  (`Starting onboarding with DSO party migration`). 10729 (LsuIntegrationTest, sv2Participant). Canton fix needed.
- Dups of 10729 (closed 2026-11-09): 10353 (2026-10-15) and 11256 (2026-11-06), both the restored bobValidatorLocal
  participant in LsuIntegrationTest. 3 hits in about 4 weeks; whether the Canton 3.7.3 snapshot (splice#8381) fixes it
  is not checked.

## P. Test race with the validator's own traffic top-up
- 10731 (TokenStandardV2TransferIntegrationTest "buy traffic through a transfer to the traffic purchase receiver",
  #8012): asserts the member's total purchased traffic grows by exactly its own purchase; `TopupMemberTrafficTrigger`
  (`topupAmount = 6000000`) bought in the same second -> `8000000 was not equal to 2000000`. Fix: pause the trigger
  for the test or assert on its own MemberTraffic contract.

## Q. Release-line javac inference failure on generated Daml codegen (cold compile)
- Signature: `[error] .../target/daml-codegen-java/... .java:NN:33: incompatible types: cannot infer type-variable(s)
  T,T,T,T,T,T ... inference variable T has incompatible equality constraints X,java.util.Optional<T>` on a
  `JsonLfDecoders.record(...)` decoder, `javac returned non-zero exit code`; 1 job of a run, other shards compile fine,
  tests may all pass. 10728 (0.9.x, Allocation_Settle.java), 10735 (0.9.3, OpenMiningRoundSummary.java), both
  2026-10-28, both cold compiles. Cause open (concurrent codegen into shared API packages? runner/JDK change?).

## J. Infra, no code change
- 10133 ghcr.io pull i/o timeout.
- 11680 (cimain docker preflight, 2026-11-20 11:10 UTC): `docker compose up` of the compose validator
  (`build-tools/splice-compose.sh start`, DockerComposeValidatorPreflightIntegrationTest.scala:26) got one 502 from
  the token endpoint of the image registry while downloading layers of `canton-participant`; the start is not
  retried, test fails after 22 s, no container started. Confirm: private CI job output; the registry error comes
  directly before `Failed to start docker-compose validator`. Rerun; hardening (pull retry in
  `cluster/compose/validator/start.sh` or in the test) proposed, not written.
- 11671 (image scan job, 2026-11-20 03:00 UTC): one of 50 `docker pull`s in `build-tools/docker-scan` was answered
  as unavailable by the registry, neighbours fine, no GitHub incident; the pull had no retry (the Black Duck call
  has 10). Confirm: private CI job output; the registry error comes directly before the make error of that image.
  Fix branch `pperek/fix-11671-docker-scan-pull-retry` (pull retried 5 times).
- Multi-arch image check (deployment_test, `scripts/check-multiarch-images.py`): one skopeo answer decides. 10150 = Docker Hub
  502 (`unable to inspect ... 502` on stderr); 10172 = no stderr line, response without a `manifests` list for a digest
  that is an index. Confirm with the registry probe in the 10172 packet. Fix: `ray/fix-multiarch-check-retry`.
- 10185-10193, 10195 (run 35613990408, release-line-0.8.x, 2026-09-21 14:52-14:57 UTC): eleven shards die in nix flake
  setup, `unable to download https://github.com/nix-systems/default/archive/<rev>.tar.gz: HTTP error 504` after
  cache misses in the runner binary cache and cache.nixos.org. Signature: no `Tests:` line, failure within 2-6 min of
  job start, every shard of the run at once. Fix: rerun failed jobs; ask runner owners to cache the flake inputs.
- 10496 / 10489 docs job: root-workspace `npm ci` (`Npm install called for .../apps/common/frontend/../... Id: 2` with no
  `completed`) hangs until the 40 min sbt timeout. build-tools/npm-install.sh retries failures but has no per-attempt timeout.
- 10358 family: `Killed SBT after timeout 40m` with no `Tests:` line after a precompiled-classes cache miss
  (`Cache not found for input keys: classes-<v> branch:main dependencies:<hash>`), then a from-scratch compile of
  ~13 min instead of ~6. Trigger: the first main run after a merge that changes a file in the cache key (openapi yaml,
  npm/Maven dependencies, daml/dars.lock): 10681/10682 (#7989 scan.yaml), 10684/10685 (#7995 npm), 10697 (#8003
  dars.lock), #7969 wall-clock (0), 10727 (#8007 Maven). Fix in CI:
  a fallback restore key without the dependencies hash, or Test/compile outside the timed step.
  Release-line runs never hit the classes cache (`Cache not found ... classes-10 branch:release-line-...` also on
  passing runs), so every release-line shard compiles (8-13 min) inside the 40 min budget: 11208, 11212 (2026-11-05,
  five runs at once, compile 12.5 min). 11365 (2026-11-11, release-line-0.9.4 62f3512, wall-clock-time (5), compile
  15 min, killed 15:48:20): third 0.9.4 hit after 11212 and 11231; passing 0.9.4 wall-clock-time (5) runs of 11-05
  used 35 and 39 min of the 40.
  11660 (2026-11-19, release-line-0.10.1 c12f83f, wall-clock-time (0), Canton 3.7.2, compile 13 min, killed 13:27:18):
  first hit recorded on 0.10.1, which also lacks #8319.
  11661 (same run, wall-clock-time (4), compile 13 min, killed 13:28:56, 0 tests failed): second shard of that run.
  11674 (2026-11-20, release-line-0.10.1 5ddb73d, resource-intensive (0), build 31 min of the 40 on a slow runner,
  killed 05:12:20 with 2 tests succeeded, 0 failed): third 0.10.1 hit; passing wall-clock shards of that run used 40-45 min
  of the step.
  11675 (same run, wall-clock-time (8), build 32 min, killed 05:12:31 seven minutes into the first of nine suites,
  0 failed): fourth 0.10.1 hit.
  11676 (2026-11-20, release-line-0.10.0 500817c, wall-clock-time (0), Canton 3.7.1, build 11 min, killed 05:12:50
  with six of eight suites finished, 37 tests succeeded, 0 failed): first hit recorded on 0.10.0, same backport
  (#8521) and same hour as 11674/11675.
  Fix: splice #8319 (ray-roestenburg-da, merged 2026-11-05 21:06 UTC) compiles the tests in their own step before the
  timed step, in `.github/actions/tests/scala_test/action.yml` only. Covers GHA scala_test shards on main; release
  lines only once backported. Does not cover the CircleCI preflight jobs (11221, 10677, 10736:
  `preflight_check_final_upgrade_docker`, 20 min `timeout` around sbt). A cold-compile timeout in a GHA shard after
  that merge (on main) is a regression of #8319, not a plain duplicate.
  Checked 2026-11-06: no release line has the step (`name: Compile tests` absent on release-line-0.10.0, 0.9.4, 0.9.x).
  cni#7978 (merged 2026-11-05) adds Test/compile to CircleCI preflight_check_docker; whether it covers
  preflight_check_final_upgrade_docker is unconfirmed. Job-level `timeout-minutes` still covers compile + tests
  (build.scala_test.yml 60, _for_compose 45, _with_cometbft 35): a cold compile can now end as a *cancelled* job
  instead of `Killed SBT`; treat that as this family. Cache misses themselves: 11220 (open; splice #8312 closed unmerged).
- Helm values schema with `additionalProperties: false` (#7178) rejecting a value a concurrent chart change added
  (`httpHealthProbes`, #7976): deployment_test `- at '/httpHealthProbes': false schema`, and CircleCI deploys of that
  splice commit. 10683, 10688, likely 10689. Fixed by the #8015 revert.
- 10643 backup jobs cancelled by the 90 min `Backup node` step timeout (Krzysztof's final diagnosis 10-28, not the
  runner OOM first suspected): backup_status_cluster `Stale backups found: ...` on Mainnet and Devnet (10692 10694
  10698 10700 10724 10725). Fixed by cni #7727 (1451bfce38, 10-28 09:47: timeout removed, one backup run per net).
  Runs before that are dups; status stays red until stale nodes have a fresh backup. Reopen 10643 if it persists.
  After #7727, a Devnet status failure at 18:xx is the 18:00 check racing the 16:00 backup (backups 0,4,16,20; status
  0,3,6,18,21; 8 h threshold; 12 h gap 04->16): 10915, open with Krzysztof. Earlier 18:xx hits 10514 10638 10692.
- ciperiodic workflows share `cluster/deployment/ciperiodic/config.yaml`: a config change for one (cni #7746, PV 36
  founding for lsu_operator) hits sv_reonboard and non_devnet too. 10741 10892 10914 = 10844, fixed by splice#8125.
  After #8125, deploy_sv_reonboard still fails intermittently (11106 10-30 16:00, 11137 11-02 08:00; 5 runs between
  passed): not 10844, check the deployed splice commit with `ghx .../commits/<parent of the build-branch commit>`
  ("Set Splice commit to: ...") and `git merge-base --is-ancestor b4fc190853`. Cause needs the CircleCI step output.
  2026-11-04: cause found (user, CircleCI output): `allowedOrigins must be set when disableAuth is true`, the Helm
  check added by splice #8137 (86eb7829db, merged 2026-10-30 09:28 UTC) in splice-validator/templates/validator.yaml;
  Pulumi passes disableAuth for validator1 but never allowedOrigins, and no cluster/expected fixture has
  disableAuth: true. Parent 11106 (dups 11137 11142 11151 11171 11183), assigned OriolMunoz-da. Open: the 10-31 and
  11-01 runs reportedly passed with #8137 on main.
- Devnet LSU (cni #7836/#7837, 10-30 20:28: synchronizerMigration.active.id 6 -> 7): both backup workflows read
  `.synchronizerMigration.active.id`, so the next run backs up and checks the new serial. First night after it:
  06:00 status stale (11117) and 04:00 backup failed ~07:10 (11118, dup), recovered by 11-01. Not the 18:00 gap of
  10915. Cause (slow first backup vs failing serial-7 component) needs the GHA logs; expect the same at each net's LSU.
  Map cni GHA run ids to times via splice run ids (`gh api repos/canton-network/splice/actions/runs/N`, ~5.2 M ids/h).
- Cloud SQL backup slower than `wait_for_cloudsql_backup` (parent 11233): after splice#8310 the
  backup awaits the slow operation (`Backup is taking longer than expected, awaiting operation completion...`), then
  still gives up (`Timed out waiting for backup of <db> db`, 300 polls of 5 s); the next attempt finds the backup
  `ready` within minutes. Devnet: 11117 (10-31), 11233 11230 (11-05), 11344 and its status consequence 11342 (11-10).
  Loop unchanged on splice main as of 11-10 (#8392 #8394 only drop CometBFT). Confirm: the `ready!` line for the same db on the next attempt.
  Devnet 18:xx status hits since: 11370 (11-11), 11397 (11-12), 11417 (11-13), same instance sv-da-1-participant-1.
  11417: the sv-da-1 job of the 16:01 Devnet Backup timed out on cantonBft (17:02) and cn-apps (18:28), each ready on
  the retry within minutes; participant is backed up last, started 18:53, after the 18:08 status check. Loop
  unchanged on main 6a996a302c.
  11426 (11-15), 11522 (11-16), 11549 (11-17): three 18:00 checks in a row. 11549: the backup run timed out on one
  database after another, each attempt restarting at the first, and the participant backup started hours late. Loop
  unchanged on main facc9443d3.
  11667 (11-19): two stale instances, an sv-da-1 participant and the sequencer of the SV runbook (namespace `sv`),
  on the first run after the runbook node was added to the Devnet backup and to the status check. Expect runbook
  instances in the stale list from now on. Loop unchanged on main 1e80ae1dea.
  11677 (11-20): first Mainnet hit, an sv-da-1 participant stale at the 09:05 check (4 h threshold). Mainnet runs
  node-backup.sh from splice release-line-0.10.0, which has neither #8310 nor #8520 (c20fdd262b, 11-17: await the same
  operation, accept any successful backup of the description). The backup create ran over the client wait three
  times, each retry made a second backup with the same RUN_ID description, and the status poll then saw two entries
  that never compare equal to `SUCCESSFUL`. Needs the backport.
  11683 (11-20): second Mainnet hit, 12:05 check, the other node: an sv-1 participant stale, same mechanism; the
  backup had queued behind the earlier run. Release line unchanged at 500817c15d.
- Cluster reset race (parent 11170): during `cncluster ci_reset_unlock` the `sv` stack deletes
  namespace `sv` while `sv-runbook` still uninstalls its Helm releases there; `uninstall: Release not loaded:
  cluster-ingress-sv: release: not found`, abort, other stacks `context canceled`. scratche 11170 (11-03), 11196
  (11-05), 11343 (11-10); scratchnetc 11345 (11-10, first other cluster); scratchnetd 11556 (11-17, third cluster,
  namespace delete 21:12:47, release delete 21:12:53, error 21:13:01). Confirm: `[id=sv]` Namespace delete by `sv.sv.<cluster>` before the sv-runbook
  `[id=sv/cluster-ingress-sv]` Release delete.
- GitHub incidents: CircleCI `Checkout submodule` or git clone failing with `Permission denied (publickey)` or
  `Repository not found` for canton-foundation/configs(-private) or splice. Parent 10213 (2026-09-23, Github flake);
  earlier 8599 (configs.git), 6800 (bump_splice configs-private), and clone failures 9748 9756 8611 8936 10224.
  Search closed issues for `configs-private` and check githubstatus.com/history. 2026-11-04 10:06: 11184 11185
  (bump_splice, deploy_ciperiodic_upgrade_workflow, cni cbc7cca0f8) during the GitHub incident `Incident with Pull
  Requests, Git Operations and Issues`, 09:49-11:50 UTC.
- ciperiodic deploy_upgrade (10677, 10736; 10:00 runs since 10-27): base = latest release (0.9.x submodule), then
  upgrade to main. Not family N. First runs after cni #7703 (synchronizerMigration.active added on the branch). Needs
  the CircleCI step output.
- deploy_cimain issues come from the wrapper's notify-fail jobs (wait_for_previous_pipeline, dependencies_scan); a
  failing cimain deploy files a deploy_basic issue for build/cimain-deploy-basic instead. 10305, 10493, 10733.
- Auth0 User Cleanup on GHA (10480, 10721, weekly Wed 06:00): suspected `timeout 15m sbt tools/run` without the
  setup_sbt cache step (tools depends on apps-app compile->test). Close as dup of 10480.
- 10679: one runner several times slower in every app init phase (Wait for user 17-35 s, Initialize app ~4 min),
  `Condition never became true within 5 minutes` in waitForInitialization; cache hit, no 40001 storm. One-off.
- Cluster test trigger (splice workflow `cluster_tests.yml`, step "Trigger cluster test", used by Deploy CIMain and
  pr_cluster_test): `curl: (22) The requested URL returned error: 500` four times, body "500 Internal Server Error:
  The server encountered an internal error" (Werkzeug default, so the invoker app raised). 11152 11153 11154 = run
  41610142697 attempts 1-3, 2026-11-03 09:20-09:40 UTC, attempt 4 at 09:44 passed. curl already retries 500 for 60 s;
  cause (invoker log, recipe 13): Cloud Function invoke-circleci-workflow (da-cn-ci) got HTTP 403
  `Permission 'secretmanager.versions.access' denied` reading secret github_token; fixed by 09:44. One-off; each manual rerun
  files a new issue, so expect one issue per failed attempt.
- Run-wide slowdown of the test host (11375, dup 11376; run 43073677994, main ee8772c, 2026-11-11): wall-clock-time
  (5) and (9), separate runner pods with their own Canton, slow down in lockstep 18:45:22-18:49:18 UTC: BFT epochs on
  globalSequencerSv1 15-36 s instead of 3.5-6 s, sv1 `PrePrepare received` -> `DB stored pre-prepare` 100-370 ms
  instead of about 5 ms; every ledger round trip needs 3 hops of 3-6 s, so 38 s HTTP calls (onboardUser) and 20 s
  `eventually()` around one transaction fail. Confirm: the epoch-duration awk in the 11375 packet on two shards of
  the same run shows the slow epochs starting and ending within seconds of each other. Not the Canton version (next
  runs on it green). Cause of the host slowdown not visible in artifacts. Not family M: M is steady per-hop latency
  against a too-short deadline; here every budget fails only inside the window.
- Runner network blip 2026-11-11 16:05-16:24 UTC (11366, 11367): four jobs on four self-hosted runners of two main
  runs: k8s container hook `##[error]Error: socket hang up` (docs Post-SBT cache save, and Initialize containers /
  checkout in two jobs without issues), and apt DNS failures (`Temporary failure resolving archive.ubuntu.com` etc.)
  in docker-no-canton Build Docker images, all 6 make retries. No GitHub incident. The hook retry for 8613
  (runner-container-hooks 8472c0f) matches only ETIMEDOUT and HTTP 429/5xx. Confirm: same error in other jobs of the
  window. One-off.


## R. Forced ACS snapshot returns 500 when the snapshot index trigger holds its advisory lock
- Signature: `Request to .../api/scan/v0/state/acs/force resulted in an unexpected exception: Failed to acquire
  session-scoped advisory lock 1936747523` (= AdvisoryLockIds.acsSnapshotIndex, 0x73706c00 + 3), then the test's
  `forceAcsSnapshotNow` fails with `HTTP 500`.
- Mechanism: per-table ACS snapshots (`NextV2`) index with `pg_try_advisory_lock`; the triggers skip on
  `FailedToAcquireLockException`, `HttpScanHandler.forceAcsSnapshotNow` does not. 10740 (2026-10-28, TokenStandardV2Transfer).
- Owner: OriolMunoz-da (per-table snapshots #6515, #7788).

## S. Environment close times out on an in-flight ledger submission (teardown only)
- Signature: test passes, suite aborted in `afterAll`: `ShutdownFailedException: Unable to close SpliceEnvironment`,
  `Task closing node-<validator> did not complete within 10 seconds`, `Timeout 10 seconds expired, but readers are still active`.
- Cause: a trigger's SubmitAndWait in flight when the env closes; RetryProvider waits 10 s. 10742 (RollForwardLsuDR).

## Note 2026-10-29: merge race on main
- Whole runs red (every job incl. docs/static_tests) with one compile error = main broken by two individually green
  PRs (10-28: #8097 removed `truncateInstant`, #8056 used it; fixed by #8109). Close all per-job issues as fixed by the
  unbreak PR; check whether nightlies ran before the fix.

## T. CI test credentials invalid (2026-10-29)
- GCS: `StorageException: Error getting access token for service account: 400 ... invalid_grant ... Invalid JWT
  Signature ... iss: splice-data-export-tests@da-cn-splice` in GcpBucketPeriodicBackupIntegrationTest /
  PeriodicTopologySnapshotIntegrationTest / key-download; whole CI on all branches; fix = new SA key in GHA secret.
- Auth0: compose validator "with auth": `Failed to start secret machine login ... oauth2: "access_denied"` from
  build-tools/splice-compose.sh auth0 login (AUTH0_TESTS_MANAGEMENT_API_CLIENT_*). Umbrellas 10896 / 10901.
- Cause: planned CI secret rotation on 10-29 (GCP keys ~09:57 UTC, AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET ~10:15 UTC).
  More signatures of the same window: frontend `allow login via auth0` -> `com.auth0.exception.APIException: Request
  failed with status code 401: Unauthorized` (Auth0Util.requestManagementAPIToken); ParticipantKmsIdentitiesIntegrationTest
  hangs until the 60 min job timeout (`Received SIGINT`, job cancelled) with GcpKms `UNAUTHENTICATED` /
  REGISTER_KMS_KEY_INTERNAL_ERROR in canton-standalone-kms-identities-validator.clog. Close as dups after a green run.

## U. Joining SV's ACS import refused: last event is a topology offset (Canton 3.7)
- Signature: `sv4 app initialization: JoiningNodeInitializer joining Dso with key failed` caused by
  `INVALID_ARGUMENT: IMPORT_ACS_ERROR ... Cannot apply a repair command as the last event is a topology offset. Please
  reconnect to the synchronizer to move the ledger end` -> SV app exits the JVM (evidence loss for the shard).
- Check introduced with the Canton 3.7 fork (canton/.../participant/admin/repair/RepairServiceHelpers.scala:223,
  #7944, 10-23). First hit 10907 (SvTimeBasedOnboardingIntegrationTest, simtime, main 3568cba969, 10-29).
- Third hit 11163 (2026-11-03, main 5c53b74294, simtime (1), run 41637827972): sv2 in
  SvTimeBasedRewardCouponIntegrationTest env start, 13:11:32.295; offset 856 (TopologyTransactionEffective) was the
  last indexed event before the disconnect; the same sv2 join passed at 13:06:41 in TimeBasedTestNetPreview (race).
  Also 11107 (dup). Fix direction: in JoiningNodeDsoPartyHosting retry the import after reconnect and ledger end moving
  past the topology offset (ImportPartyAcsRequest has no force_repair_when_topology_transaction_at_ledger_end).
  Packet `11163-sv2-join-import-acs-topology-offset.md`.

## V. PeriodicTopologySnapshot: missing 'scopes' since pekko-connectors-google 1.3.0 (REAL, 2026-10-30)
- Signature: PeriodicTopologySnapshotIntegrationTest `did not include substring "Took a new topology snapshot on <date>"`;
  canton_network_test.clog WARN `Stream onboarding state to GCP bucket failed ... ConfigException$Missing: hardcoded
  value: No configuration setting found for key 'scopes'` at SequencerAdminConnection.streamOnboardingState.
- Cause: #8017 (backport #8073 on 0.9.x) bumped pekko-connectors-google-cloud-storage 1.0.2 -> 1.3.0; 1.3.0
  `Credentials.parseServiceAccount` reads `scopes` at the top level, we pass it inside `service-account`. Fails only
  on the first runs of a day (later runs find today's snapshot and skip streaming). Parent 10921, dup 10923.
  Not to be confused with family T (invalid_grant, 10-29).
- Fixed on main by #8148 (2d373163c3, 2026-10-30); 10921 closed. Branches that have #8017 but not #8148 still hit it.
- 2026-11-02: 10891 (PR 8083 branch 9ff2637f41, run 40713988598 wall-clock-time (9), 10-29 01:30 UTC) is V: has #8017,
  lacks #8148; 9x `'scopes'`, 0x invalid_grant. Dup of 10921.

## W. ScanEventHistoryIntegrationTest pre-tap cursor not quiet (test race)
- Signature: `should provide new events with verdicts *** FAILED ***` at ScanEventHistoryIntegrationTest.scala:65,
  a Vector of EventHistoryItem with licenseCid / couponCid / openRoundCid (background reward/licence automation).
- Parent 10912. Test-side fix: drop the emptiness check or pause that automation.

## Note 2026-10-30
- 10919 ExpiryWithIgnoredAmuletVersion: startup-of-all-nodes 2 min timeout, splitwellValidator `DbLockNotAcquired`
  ~944x right after AutoIgnoreUnresponsivePartiesWithPersistenceIntegrationTest (leftover nodes); lock holder open.
- Compose / LocalNet wallet frontends: tap row missing (10918 parent, 10920) and portfolio
  `Failed to list contracts of interface ...TransferInstruction` ERROR (10917); intermittent, later runs green.
- TokenStandard* `/state/acs/force` 500 on the advisory lock: parent 10740, dup 10916.

## X. Canton 3.7 nodes produce PV 37 topology where a PV 36 synchronizer expects PV 36 (REAL, 2026-10-30)
- Seen in the mainnet-Daml nightly (PV 36) from 10-30, once SV1 bootstrap works (#8065/#8125). Post-merge (PV 37)
  is unaffected. Three variants:
  - X1 external party onboarding: `POST .../admin/external-party/topology/submit` HTTP 400
    `PROTO_DESERIALIZATION_FAILURE`; canton.clog `expected representative protocol version 36 but found 37`.
    ValidatorUtil.createTopologyMappings -> generateTransactions(Authorized, no PV). Parent 10934.
  - X2 joining SV mediators / LSU successor sequencers: tests pass, log check fails on `synchronizer outbox flusher
    failed ... OwnerToKeyMapping(member = MED::svN ...) serial = 1`. Same mechanism as N. Parent 10927.
  - X3 offline root key validator: `TOPOLOGY_CONVERSION_ERROR ... Signing key ... for topology transaction
    NamespaceDelegation` on ReconnectSynchronizer, validator exits, shard hangs to SIGINT. Parent 10937.
- Each nightly files ~12 per-job issues; one grep per job for `found 37`, `outbox flusher failed` or
  `TOPOLOGY_CONVERSION_ERROR`, close as dups of the parent.
- 2026-11-02: X1 fixed by #8149, X3 by #8154 (Martin Florian); both confirmed at PV 36 in the 10-31..11-02 nightlies.
  X2 persists with Canton 3.7.1 (#8165): 16 dups in 3 nights (11108-11135, see `evidence/2026-11-02-nightly-a.md`); the
  roll-forward-lsu shard alternates between OTK on `::36-0` (3x) and the 10925 variant on `::37-2` (8x).

## Y. Test needs newer Daml packages than its scalatest tag (mainnet-Daml nightly)
- The nightly excludes tags for package versions not in MainNet's release line (scripts/initial-package-config.py,
  `Using scalatest tags:` in the job log); a test tagged too low runs against the old packages and fails on
  behaviour it needs. 10930 UnhideAndExpireRewardCouponV2 (tag 0_1_19, needs 0.1.25 / #7627); 10933 traffic purchase via
  token standard (`UNKNOWN_INFORMEES splice_traffic-manager`, #8012, untagged). Fix: tag the test.
- 2026-11-02: both fail every mainnet-Daml nightly (3 nights, 9 dups). Traffic test fails in TokenStandardTransfer and
  TokenStandardV2Transfer shards; confirm with the transfer POST to `splice_traffic-manager` 30-40 ms before
  `UNKNOWN_INFORMEES`. Coupon: count `ProcessRewardsTrigger batchType=`; only BatchOfMintingAllowances = 10930.
- 2026-11-04 correction (11178, 11180, 11181; analysis on main b5e6a1ba55):
  - Traffic (10933): genuinely needs splice-amulet 0.1.25. #8012 added `trafficPurchaseReceiver`
    (`splice_traffic-manager::1220...abcd`, not hosted anywhere) to ExternalPartyAmuletRules: a V1 or V2 transfer to it
    burns the amount and creates MemberTraffic. With 0.1.24 it is an ordinary transfer to an unknown party, hence
    UNKNOWN_INFORMEES 40 ms after the POST. Fix: tag the one test case in TokenStandardTransferIntegrationTest and
    TokenStandardV2TransferIntegrationTest with SpliceAmulet_0_1_25.
  - Coupon (10930): does NOT need 0.1.25; #7627 only adapted a config constructor in the test. The batch-type count is
    no evidence (BatchOfMintingAllowances and BatchOfBatches are the only types). ProcessRewardsTrigger hides a
    provider's RewardCouponV2 (providerIsObserver = false) only if the provider has not vetted the ACTIVE amulet
    version; the test makes Alice unvetted by unvetting `DarResources.amulet.latest`. In the nightly the active
    version is MainNet's (0.1.24, still vetted by Alice), so her coupon is visible and line 206
    `aliceCoupons.filterNot(_.payload.providerIsObserver) should not be empty` fails. Confirm: `Providers with wrong
    vetting state for batch` lines: 0 in the nightly (11178), 3 in a passing main run (11144). The test assumes
    active == latest, so a version tag only hides it until MainNet reaches 0.1.25; right tag: NoDamlCompatibilityCheck
    (or unvet the active version in the test).
- 2026-11-11 (11353): SvFrontendIntegrationTest "Grant, Update, Amend and Revoke Featured App Right", untagged,
  since #8062 fills the splice-amulet 0.1.25 fields appKind/opsParties (from #7627) in the Grant form. Confirm:
  `voterequest/create resulted in a gRPC StatusRuntimeException: INVALID_ARGUMENT: COMMAND_PREPROCESSING_FAILED`
  ... `An optional contract field (\"appKind\") with a value of Some may not be dropped during downgrading` in
  canton_network_test.clog. Its Amend step (SRARC_CreateFeaturedAppConfigUpdateInstruction, #8061) needs
  splice-dso-governance 0.1.31 too. Fix PR splice#8427 tags it SpliceAmulet_0_1_25. Generic signature of this family
  on any field: `may not be dropped during downgrading` (precedents 4734, 5292, 7546).

## Z. ScanPartyMonitorScriptIntegrationTest: private verdict held back by the /v0/events cap (test flake, #8107)
- Signature: `The script reports alice confirming the private transaction ... List() did not contain element
  "confirming" (ScanPartyMonitorScriptIntegrationTest.scala:273)`.
- Confirm: script stderr (`exited with 0:` debug line) stays at one `At record time` for 20 s; sv1Scan logs the
  private verdict (`Last ingested verdict record_time is now`) but only `History N ... ingesting a => b @ None` after.
- Mechanism: ScanEventStore.getEvents caps at min(last verdict, last update) record time; a verdict the DSO party
  does not see is served only after the next DSO-visible update. Test fix: a tap after createTransferOffer.
- Parent 11128 (dup 11136). Owner stephencompall-DA. Fix PR 8211 (2026-11-03, auto-merge).
- 2026-11-02: dups 11138 (41428422164 wall-clock-time (0)) and 11139 (41438225454 wall-clock-time (8)); 4 failures that day
  (05:00, 07:43, 10:23, 11:36). Variant: the hidden verdict is 1 microsecond after the last DSO-visible update (same act),
  not ~250 ms. Packet `11138-11139-scan-party-monitor-events-cap-dup-11128.md`.
- 2026-11-03: dup 11158 (41620786495 wall-clock-time (3), main 6949aa0f3b); hidden verdict 330 ms after the last
  DSO-visible update. Fifth hit; no fix on main (5c53b74294). Packet `11158-scan-party-monitor-events-cap-dup-11128.md`.

## AA. LsuIntegrationTest: sv4 faucet trigger ERROR during the late sv4 successor sequencer's catch-up
- Signature (log check only): ERROR `ReceiveFaucetCouponTrigger ... validator=sv4Validator` `Skipping processing of
  Task(license = ...) due to unexpected failure` with `LOCAL_VERDICT_INACTIVE_CONTRACTS`.
- Confirm: `Total maximum number of retries 35 exceeded`, the preceding sv4Validator SubmitAndWait failures are all
  `SEQUENCER_BACKPRESSURE`; on the successor sequencers `New epoch ... blacklisted nodes = List(sv4)` for ~2 min.
- Fix in the test: pause that trigger on sv4Validator until "sv4 upgrades", like the sv4 circuit breakers (#5587).
  Parent 11104. Owner suggestion Martin Florian.

## AB. BftScanConnectionIntegrationTest: periodic scan refresh WARN about a deliberately stopped scan after the suppression ended
- Signature (log check only): WARN `Failed to connect to scan of Digital-Asset-Eng-2 (http://localhost:5112).` from
  `BftScanConnection$AllDsoScansBft:BftScanConnectionIntegrationTest/.../validator=aliceValidator`, about 1 s before
  `Test succeeded: 'BftScanConnectionIntegrationTest/init fast enough even if there are unavailable scans'`.
- Confirm: `grep -a 'validator=aliceValidator' | grep -a -E 'Failed to connect to scan of|Started refreshing scan list'`
  shows two `Suppressed WARN` refreshes, then an unsuppressed one 60 s after the first; `Stopping node aliceValidator`
  follows within ms, after the TokenStandardCliSanityCheckPlugin teardown.
- Mechanism: the test stops sv2Scan and suppresses the WARN only around `startSync` + `onboardUser`; aliceValidator's
  `scansRefreshInterval = 60s` refresh retries the stopped scan until the environment is torn down. Fails when startup +
  onboarding + listAnsEntries + teardown plugins exceed 60 s.
- Parent 11140 (2026-11-02, main 1938e4170b). Fix in the test: keep the suppression until aliceValidator stops, or
  restart sv2Scan inside it. No suite-wide ignore.

## AC. WalletMetricsTest: trigger.completed lookup matches 2 series (success + noop)
- Signature (canton_network_test.clog, ERROR): `Found 2 matching metrics` followed by `Failed clue: DomainIngestionService
  should report task completions correctly`, for 20 s; job log shows only `CommandFailure: Command execution failed.`
  in "User wallet automation metrics should are labeled with the party ID of the wallet user".
- Confirm: the tc1 DomainIngestionService `Completed processing` line and the first `Running clue:
  DomainIngestionService should report task completions` are more than 24 s apart.
- Mechanism: `trigger.completed` is labeled with `outcome` (TaskbasedTrigger.scala:188-241); the quiet per-party
  DomainIngestionService marks `success` on the first poll and a silent `noop` 24-36 s later; `metrics.get` needs one
  series. Test bug.
- Parent 10732 (run 40672823965); dup 11143 (run 41481391794).
- Fix (test): `"outcome" -> "success"` in the label map at WalletMetricsTest.scala:133-136, branch
  `rautenrieth-da/fix-10732-wallet-metrics-outcome-label` (2026-11-03).

## AD. TrafficBasedRewards test reads round totals before the parallel backlog computation committed them
- Signature (test failure): `RewardAccountingActivityTotalsUndetermined(...) Round <n> should have totals
  (TrafficBasedRewardsTimeBasedIntegrationTest.scala:467)`, n below 10.
- Confirm: `zcat canton_network_test.clog.gz | grep -a 'scan=sv1Scan' | grep -a -E 'RewardComputationTrigger|Computed and stored rewards for round'`
  shows rounds 3..10 started in the same millisecond and round n's `Computed and stored rewards` after round 10's;
  the test's GET `/rounds/<n>/activity-totals` lies in between.
- Mechanism: the test pauses sv1Scan verdict ingestion for rounds 3..10, so on resume RewardComputationTrigger
  (PollingParallelTaskExecutionTrigger) runs the whole backlog in parallel and finishes out of order; the test's
  `eventually` waits for round 10 only, then reads 0..10 once. Undetermined is scan's documented "retry" answer.
- Parent 11144 (run 41492215719, DryRun variant; applies to both variants via the shared base).
- Fix: test-side, `assertRewardCalcs` waits for all rounds 0..10 (totals and root hash) inside the `eventually`.

## AE. Frontend login helper misses the already-logged-in state on a slow page load
- Signature: `Could not find IdQuery(user-id-field)` from `FrontendLoginUtil.loginOnceConfirmedToBeAtUrl`, on a page
  where the user was already logged in (failure screenshot shows `Logout`), no `disallowed MIME type` in the browser log.
- Confirm: in the browser log, the `#logout-button` FindElement of each attempt returns `no such element` before or
  right after `browsingContext.domContentLoaded` of the target page (11161: 12:26:43.883 and 12:26:47.030, DCL
  12:26:47.022); in canton_network_test.clog two `Failed clue: Waiting for login page to be ready` within 5 s.
- Mechanism: the helper checks for the logout button once per attempt, then waits 3 s for the login form; the 5 s
  budget allows two attempts, so a page that renders more than about 2 s after the helper starts is never logged out.
  Seen on wallet `/confirm-payment/<cid>` after the splitwell redirect (Vite dev server, 3.8 s load). Precedent for the
  same helper: #7810 (SvFrontendIntegrationTest auto-login race). Different from F (blocked vite modules).
- Parent 11161 (SplitwellFrontendIntegrationTest settle debts with a single party, main be675a11dd).
- Fix: wait for either `#logout-button` or `#user-id-field` before the retry loop (packet 11161). Not written.

## AF. Sim-clock jump timeouts trip the SV medium-priority circuit breaker
- Signature (log check only, canton_network_test.clog): WARN `Circuit breaker medium-priority-connection tripped after
  20 failures. Attaching last failure` from `SpliceCircuitBreaker:<simtime suite>/.../SV=<sv>`, stack
  `ABORTED: LOCAL_VERDICT_TIMEOUT(2,0)`.
- Confirm: attribute sv2 `failed with` lines by span-name; the medium triggers (SummarizingMiningRoundTrigger,
  CalculateRewardsTrigger) show 20 consecutive LOCAL_VERDICT_TIMEOUT over several consecutive `advancing time by PT1H`
  steps, with no medium success (`Completed processing with outcome`) in between, then the WARN. The unnamed
  `Resetting circuit breaker` lines in between belong to other sv2 breakers.
- Mechanism: each sim-clock step times out the in-flight SV commands (accepted by design at a jump; same jumps as
  10475). The medium triggers wait for domain time to catch up and resubmit before the next step, less than 1 s of
  wall time after the failure, so SpliceCircuitBreaker's 15 min (sim) `resetFailuresAfter` check never fires, and the
  count builds across steps to the test max-failures 20 (`include/svs/_sv.conf`). LOCAL_VERDICT_TIMEOUT (category 2)
  is not ignored.
- Hits: parent 11147 (2026-11-02, main 136e2e3160, 3 steps), 11291 (2026-11-09, main ab36803c8a, 4 steps, dup),
  11348 (2026-11-11, main 2ef0423, 3 steps, dup, sv1), 11357 (2026-11-11, main 2d54198, 5 steps, dup, sv2). All in
  docker-canton-simtime (0),
  ScanNewNetworkBackfillingTimeBasedIntegrationTest; sv2 in the first two, sv1 in 11348 (any SV app can trip).
  2 of 97 post-merge main runs in the week to 11-09; 2 of 82 runs 2026-11-04..11. Fix PR splice#8420 (open) covers
  all SV apps. Fix in the test: `circuitBreakers = CircuitBreakersConfig.never` for all SV apps
  (ConfigTransforms.updateAllSvAppConfigs_), as LsuIntegrationTest does for sv4. Not on main as of 885cd84668.
  No ignore pattern. (The 11-05 count of 47 failures in one jump was wrong: it counted all sv2 priorities.)

## AG. First Postgres connection slower than 5 s at shard start
- Signature (log check only): WARN `Operation com.digitalasset.canton.resource.DbStorage.createDatabase failed, retrying
  in 300ms: DatabaseCreationFailed(Failed to connect to database: ... PoolInitializationException: Failed to initialize
  pool: Connection attempt timed out.` from DbStorage of an app (canton_network_test.clog) or a Canton node
  (canton log), at the first environment / Canton startup of the shard.
- Confirm: `HikariDataSource ... - Starting...` at most about 6.3 s before the WARN, `Added connection` for the same pool
  within 300 ms after it; nothing else fails.
- Mechanism: pools start with connectionTimeout 5000 and initializationFailTimeout 1; Postgres is slow for all its
  clients for a few seconds (in 11141 also the Canton lock checks, 9 LockCheckRejected within 3 ms). The cause of the
  Postgres stall is not in the artifacts (no Postgres log, no host metrics).
- Hits: 10739 (2026-10-28, simtime (1), Canton globalSequencerSv2; closed not planned), 11141 (2026-11-02,
  frontend-wall-clock-time (2), 7 app pools). Fix in test config: `connectionTimeout = 15000` in
  `apps/app/src/test/resources/include/storage-postgres.conf` (and the Canton test storage config). No ignore pattern.
- 11533 (2026-11-17, wall-clock-time (3), mainnet-Daml nightly): not at shard start but at the 8th environment of the
  shard (ScanIntegrationTest, one environment per test), 7 minutes in. One pool (splitwellValidator) timed out after
  6.0 s, three others of the same environment connected after 1.2 to 2.5 s, all other 292 app pools of the shard
  within 0.5 s. So the signature holds for any environment start, and the confirming grep is the same. 11523
  (2026-11-16, wall-clock-time (9)) was closed as a duplicate without a packet here.
- 11536 (2026-11-17, frontend-simtime (0), main): the 11141 shape again, first environment of the shard, all 7 app
  pools time out after 6.288 s; the retry connects 662 ms after the WARN (359 ms after the retry started), so read
  "within 300 ms" above as "on the first retry".
- 11641 (2026-11-18, wall-clock-time (1), main): the 11533 shape, environment start of the 8th and last suite of the
  shard (MultiHostValidatorOperatorIntegrationTest), 22 minutes in. 5 of 7 app pools time out after 6.009 s, the other
  two connect after 2.7 s; the retries connect within 8 ms.
- Variant, NOT confirmed by the grep above (11653, 2026-11-19, wall-clock-time (8), main): environment start of the 7th
  suite (WalletSweepToValidatorOperatorIntegrationTest). The pools connect within 0.45 s, then Postgres stops
  answering: the apps' `connId=db-migration` lock connections (Canton `DbMigrations.withDb`, one lock id for all apps
  on `splice_apps`) get no answer for 15 s and five hit the 15 s network timeout of `DbLockedConnection`
  (`SocketTimeoutException: Read timed out` in the DEBUG line `Failed to get lock owners`). Flagged: `Lock <id> was
  lost` plus `Locked connection was lost, trying to rebuild` per app, WARN. Canton nodes quiet for the last 7 s with
  `Task connection check read-only still not completed after 7500 milliseconds` (INFO). Confirm: `Read timed out` at
  the first flagged timestamp, `Creating new DB lock` 15 s earlier, no `NoConnectionAvailable` (that is 10438, family
  G). No splice fix (timeout and lock config are Canton), no ignore pattern. Packet
  `11653-postgres-stall-migration-lock-read-timeout.md`.

## Note 2026-11-02
- U: second hit 11107 (simtime, main 3525c637db), dup of 10907.
- 11105 Canton 3.7.0: sv1Participant WARN `Internal stream [Internal ACS Updates Stream] completed with a failure`
  (AbruptTerminationException, fanoutPublisherSink) 250 ms after the commitment matcher turns Ok on first connect;
  matcher stays Failed. First hit, no ignore.

## AH. SequencerPruningIntegrationTest disables the shard's shared participants
- Signature: after SequencerPruningIntegrationTest in a shard, every later environment start times out
  (`Timeout while waiting for initialization of <validator>`); canton.clog: globalSequencerSv1 `Disabling member at
  the sequencer: PAR::aliceValidator...` (and bob, splitwell) during the pruning test, then
  `SEQUENCER_SUBMISSION_REQUEST_REFUSED ... is not active at the sequencer` until the end of the shard; validator side
  `INVALID_PRESCRIBED_SYNCHRONIZER_ID` for new parties (do not confuse with family L).
- Confirm: `zcat canton.clog.gz | grep -a 'Disabling member at the sequencer'` lists members other than the test's
  stopped external participant.
- Mechanism: the test resumes SequencerPruningTrigger (retention 120 s) on the shared Canton; members whose recorded
  acknowledgement is older than the pruning point are force-disabled with the stopped one; disabling is permanent.
  Healthy participants acknowledge every 60 s, but the sequencer drops an acknowledgement within the conflation window
  (SequencerNodeConfig.acknowledgementsConflateWindow, default 45 s in 3.7.1, started when the previous acknowledgement
  completes in GrpcSequencerService), so one ordering delay (11165: 18 s while sv2's BFT sequencer joined) makes the
  recorded ack > 2 min old. Test passes, later suites fail.
- 11165 (2026-11-03, main 6a32b00e92, wall-clock-time (6), run 41637223316): disabled 12:58:29.802, last acks
  12:55:30, pruning point 12:57:34; 3 UnclaimedActivityRecord cases and the next suite failed at setup.
- Fix: retention above the worst healthy ack age (about 145 s with one dropped ack; 180 s) in the test, or own Canton instance (owner: suggested martinflorian-da,
  splice#3062).

## AI. SUBMISSION_ALREADY_IN_FLIGHT counted as failures by the circuit breaker (checkErrors)
- Signature (log check only, canton_network_test.clog): WARN `Circuit breaker high-priority-connection tripped after 20
  failures` with stack `ABORTED: SUBMISSION_ALREADY_IN_FLIGHT`, usually on a validator's TopupMemberTrafficTrigger.
- Confirm: the 20 failures before the trip are `SUBMISSION_ALREADY_IN_FLIGHT` for the same trigger's resubmissions
  while its first submission is in flight (count by logger and span-name).
- Parent 9752 (2026-08-24, closed after 2 weeks without recurrence; fix referenced as cni#2785, not readable here);
  10300 (2026-10-07, dup); 11173 (2026-11-03, DistributedDomainIntegrationTest, aliceValidator, 19 rejections
  17:01:20.567-17:01:38.536, wall-clock-time (3) run 41674340437). Different from 10611 (P2P connectivity).
- U: 11222 (release-line-0.10.0 8458061baa, simtime (1), 2026-11-05) dup of 10907; 5 hits in 8 days (10907, 11107,
  11163, 11177, 11222). User 2026-11-05: fixed in upstream Canton, splice does nothing; close further hits as dups
  until the Canton bump lands.

## AJ. ValidatorSequencerConnectionIntegrationTest: duplicate-sequencer scan WARN after the suppression ended
- Signature (log check only, canton_network_test.clog): WARN `Not serving sequencer SEQ::sv2::... for synchronizer
  global-domain::... and serial 0 as it is claimed by multiple SVs: Digital-Asset-Eng-2, Digital-Asset-Eng-4` from
  `HttpScanHandler:ValidatorSequencerConnectionIntegrationTest/.../scan=sv2Scan|sv3Scan|sv4Scan`, a few seconds after
  the last `Suppressed WARN` of the same message on sv1Scan.
- Confirm: the flagged request's trace id belongs to bobValidator's `ReconcileSequencerConnectionsTrigger` polling loop
  (`grep -a <trace-id> | grep -a -m1 'Starting trigger polling loop'`), and the flagged timestamp is after sv1Scan's
  last suppressed occurrence.
- Mechanism: the test case added by #8365 ends its `assertLogsSeq` block once sv1ScanBackend serves all four sequencers
  again; the validator polls all four scans every 1 s and the other scans drop the duplicate a few seconds later.
- Parent 11304 (2026-11-09, main 7d3d5f17da), dups 11307, 11309, 11316 (4 of the first 5 post-merge main runs with
  #8365) and 11327 (2026-11-10, before the fix). Fixed by splice#8389 (a9038ea432: the restore clue waits for all four
  scans). A hit on a sha containing a9038ea432 is a regression.

## AK. RollForwardLsuDRIntegrationTest: pre-DR SV onboarding does not finish before the 300 s sequencing cutoff
- Signature: `roll-forward-lsu` shard, clue "Start nodes before DR" fails with `Timeout while waiting for
  initialization of sv2` (SvAppReference.waitForInitialization, 5 min).
- Confirm: the pre-DR Canton log has `SEQUENCING_TIME_NOT_ADMISSIBLE ... is at or after the upper bound (<suite start +
  300 s>)` refusals, and the joining SV's last init submission (MediatorSynchronizerState for `MED::sv2`, or the
  `OnboardedAfterDelay` `DsoRules_SetSynchronizerNodeConfig`) is proposed or sent after that bound.
- Mechanism: `val maxSequencingTime = CantonTimestamp.now().plusSeconds(300)` (test line 89) sets
  `lsu-repair.global-max-sequencing-time-exclusive` of the pre-DR sequencers. Since #8234 (2026-11-06) they are DA BFT,
  so the joiners' ordering topology steps (1 to 2 to 4) and their 11255 delays (lost broadcasts, epoch stalls, view
  changes) run inside the budget; nothing can be sequenced after it, so the run never recovers.
- Parent 11303 (2026-11-09, main 92705421fd, lost `MED::sv2` broadcasts at the 1 to 4 step), dup 11312 (2026-11-09,
  main 91715b25c0, startup about 70 s slower, 4-sequencer topology 19 s before the cutoff, 18 s epoch stall). 2 of 18
  post-merge main runs with #8234. Fix in the test: more headroom than 300 s (costs the same test time, the test
  sleeps until maxSequencingTime + 5 s), or start the joiners one at a time before DR. Not a dup of 11255: those
  delays only consume the margin.
- Dups 11336 (11303 shape, MED::sv3) and 11328 (11312 shape, sv4 in a separate 3 to 4 step), 2026-11-10 on Canton
  3.7.3-snapshot.20261109. 4 of 31 post-merge main runs since #8234 (2 of 13 since the Canton bump #8381).
- 11396 (2026-11-12, main 3322d770c1, Canton 3.7.3 unchanged by the fork bump #8354): 11303 shape, MED::sv4 add
  re-proposed 17:02:32, sv4 OnboardedAfterDelay SetSynchronizerNodeConfig sent 0.33 s after the bound. 3 of 23 completed
  post-merge main runs since 2026-11-11 (11371, 11372, 11396).
- 11419 (2026-11-13, main 1ffd6415e3, first AK hit on Canton 3.7.4-snapshot.20261112): 11303 shape, MED::sv2 add
  refused for all four SVs at the 1 to 4 step 18:17:07.785, sv1+sv2 resend sequenced with 2 of 3 signatures, sv3
  re-proposal 18:19:05.954; sv2 SetSynchronizerNodeConfig sent 0.95 s after the bound. 1 of 20 post-merge main runs since 11396.

## AL. AppUpgradeIntegrationTest: base-version SV nodes not initialized within the 5 min initial wait
- Signature: `app-upgrade` shard, `false was not true sv<N> wallet initialized` (or `SV app initialized`) from the first
  `eventually(5.minute)` at AppUpgradeIntegrationTest.scala:157; or no report at all when the teardown then hits H.
- Confirm: `splice-node-forUpgrade-sv*-node.clog.gz` shows the `app initialization: ... finished after` phases still
  advancing up to the failure (no hang), and every sequencer-connection swap followed by a completed reconnect (a
  missing reconnect would be 9440).
- Mechanism: the base-version bundled nodes start with DA BFT since #8234 (2026-11-06); passing runs take 8 to 10 min
  end to end, and the init phases run 1.3x to 1.9x slower on slow runners than on 2026-11-04.
- Parent 11301 (2026-11-09, report lost via H), dup 11302 (next main run, missed by about 5 s). 2 of 35 main runs since
  2026-11-04. Fix in the test: `eventually(10.minutes)` (the wait dates from the 2024 import).

## AM. Joining SV init outlasts a fixed wait in the test; repeated DAR uploads in every init
- Signature: SvTimeBasedOnboardingIntegrationTest `List() had length 0 instead of expected length 1
  (SvTimeBasedOnboardingIntegrationTest.scala:75)` in the clue `An SvOnboardingRequest contract is created`.
- Confirm: `Requesting to be onboarded via the sponsor SV` for SV=sv4 later than the `Failed clue` line, with the
  sv4 `PackageService/UploadDar` requests filling the gap.
- Mechanism: the clue waits 60 s while the sv4 SvApp init uploads 87 DARs (5 in parallel, 3.5 s mean on a loaded
  shard). 22 of the 87 are repeats on every participant: `PackageResource.all = latest +: others`, `others` contains
  the latest version under its versioned path, `DarResource` equality includes the path, so `.distinct` in
  `DarResourcesUtil.getRequiredPackageVersions` keeps both and `uploadDarsLocally` only filters against the DARs listed
  before the batch. Grep for repeats: `Processing package upload of` per participant and DAR name in the Canton log.
- 11299 (2026-11-09, main dbcb0c14ca, simtime (5)), first hit. Fixes in the order the user chose: dedup the upload
  list by package id, then raise the timeout at line 72.
- 11347 (2026-11-11, key-download (0), main 2ef0423979): sv1 (founding SV) init in SvReonboardingIntegrationTest
  exceeds startAllSync's 5 min waitForInitialization; 87 DAR uploads (22 repeats) took 190 s at 7.5 s mean. Confirm:
  `Timeout while waiting for initialization of sv1` while the sv1 log shows `Sending upload dar for` or the DsoRules
  bootstrap still running.
- 11373 (2026-11-11, frontend-wall-clock-time (2), main 08774bc437): SvFrontendIntegrationTest setup; the sequential
  `waitForNodeInitialization` (5 min per node) checked sv3 first, sv3 still onboarding its sequencer/mediator at the
  timeout. All init phases 1.3x-1.8x slower than the passing setup of 2026-11-10 (CPU-only package signature loading
  57 s vs 30 s), same 87 DARs/22 repeats, no Daml/DAR/Canton change: slow runner. Later 5 suites: B setup-failure
  blast radius. Confirm: `Timeout while waiting for initialization of sv<N>` in `Running setup` with the node's
  `app initialization` phases still advancing; compare `Finished loading package signatures` minus `Loading` against
  about 30 s.
- 11550 (2026-11-17, frontend-wall-clock-time (2), main c20fdd262b, Canton 3.7.4-snapshot.20261112): dup of 11373,
  same wait, sv3 first, `Onboarding mediator` 55 ms before the timeout. Not a slow runner (signature loading 30.9 s,
  sv2 4m13s) and the DAR dedup is in (65 per SV, 0 repeats): sv3, the last joiner, spent 57.6 s in Canton's
  `InitializeSequencerFromOnboardingStateV2` (sv2 3.6 s, sv4 2.6 s a minute earlier; topology import 25.5 s, 29
  Flyway migrations over 23 s) while database writes were slow for every node from 19:01:03 (single-node splitwell
  epochs 3 s to 5-10 s; cause not in the artifacts). Compare per joining SV: `InitializeSequencerFromOnboardingStateV2`
  `received a message` to `sending response` on its sequencer. Remaining fixes: one overall init deadline, reset on
  setup failure.
- 11558 (2026-11-18, frontend-wall-clock-time (2), main 49d03025a2, scheduled mainnet-Daml nightly, Canton
  3.7.4-snapshot.20261112): dup of 11373, slow-runner shape again: signature loading 61.1 s, sv1 2m38s, joiner vetting
  100-113 s, sv3 `Onboarding sequencer` 1 s before the timeout (sequencer init itself 7.3 s, so not the 11550
  variant). 6 suites aborted. Not Daml-version specific (same nightly shard green 11-14, 11-16, 11-17). Third hit in
  8 days, two within 11 hours.
- 11654 (2026-11-19, frontend-wall-clock-time (2), main 77c89208ee, attempt 1, Canton 3.7.4-snapshot.20261112): dup of
  11373, 11550 side of the comparison: signature loading 31.3 s (runner CPU at baseline), but sv1 2m01s, joiner vetting
  101-114 s, sv3 `onboard/sv/start` 37.6 s and domain time up to 21 s behind wall clock from 10:29 to 10:31 (cause not in
  the artifacts). No joiner reached `Onboarding sequencer`; sv3 was retrying `add sv to Dso` 7 s before the timeout.
  6 later environments aborted (one as a failed test, AnsAuth0FrontendIntegrationTest). Fourth hit in 9 days.

## AN. cimain SV backup snapshots a CometBFT volume on a CantonBFT cluster (node-backup.sh `enableBftSequencer // false`)
- Signature: cni `Deploy cimain` (earlier CircleCI deploy_basic) `Backup cluster (sv-1|sv-da-1)`: `Backing up
  cometbft-0`, `Waiting for cometBFT PVC backup to complete...`, 300 x `still waiting...`, then `Error from server
  (NotFound): volumesnapshots.snapshot.storage.k8s.io "cometbft-migration-0-hd-pvc" not found`; 4 attempts, about 2 h.
- Confirm: no `Backing up cantonBft` and no `BFT sequencer is enabled for migration 0, skipping CometBFT backup` in the
  job; `enableBftSequencer` absent from the cluster's `config.resolved.yaml`.
- Mechanism: the schema defaults `enableBftSequencer` to true since splice#7824, the backup script reads the raw
  resolved YAML with `// false` (node-backup.sh:288, utils.source:119). The NotFound comes from the timeout branch
  patching `$pvc_name` instead of `$backupName` (node-backup.sh:59).
- Parent 10332 (2026-10-09), dups 11297 11298 11314 11315 (2026-11-09). Fix in splice: default true in both places.

## AP. ManualSignatureIntegrationTest: validator OTK check gives up before or during the validator's key rotation
- Signature: signatures (0) shard, `rotate validators' OTK mapping keys that are not signed. *** FAILED ***`,
  `List(<2 keys>) was not empty keys without signatures (ManualSignatureIntegrationTest.scala:47)` from line 100.
- Confirm: in canton_network_test.clog.gz, aliceValidator `Vet packages finished after PT3x S` and `need to be rotated
  because of missing signatures` within about 2 s of (or after) `Test failed`, and the rotated list equals the reported keys.
- Mechanism: the test starts aliceValidator with start() and polls for 40 s; the validator rotates only after Vet
  packages (33-37 s; a quarter of its 79 DAR uploads are repeats, see AM). The rotation is two OTKs (serial 2 adds the
  new keys, serial 3 drops the old ones); serial 3 reaches the synchronizer store about 1.2 s after the validator logs
  Success.
- 11232 (2026-11-05, main 64cbca6d3e, Canton 3.7.2), parent; 11318 (2026-11-09, first run after splice#8381, Canton
  3.7.3-snapshot), dup, not caused by the Canton bump. 2 of 30 main runs.
- Fixed by splice#8391 (087f79bf73, 2026-11-10): aliceValidatorBackend.startSync() before the check.

## AQ. cilr weekly SV catch-up test: BFT orderer restored a day behind, first monitor claims the catch-up
- Signature: CircleCI `monitor_sv_catchup_test` (cilr, Wednesday 19:00 UTC run): `INFO: Initial BFT ordering block
  delay: 999s`, then about 31 h of delay falling steadily, `ERROR: BFT ordering did not catch up within 240m`.
- Confirm: `gcloud sql backups list` for sv-9: the same-day `sv-9-sequencer-bft-19` backup starts after the
  `sv-9-sequencer-19` one, so `find-recent-backup.sh` (cantonBft must end no later than the sequencer backup) restores
  the previous day's cantonBft backup.
- Mechanism: weekly restore Wednesday 18:00 (`sv-catchup-min-backup-age-hours: 3`); hourly monitors Wednesday 19:00 to
  Thursday 10:00 hand the catch-up to the first run seeing a delay in [60 s, 4 h); `monitor-sv-catchup.sh` falls back
  to 999 s for an empty Prometheus result, so the 19:00 run claims a 31 h catch-up and fails after 4 h.
- 10843 (2026-10-28, closed not planned), 11194 (2026-11-04). Fixes: splice Cloud SQL backup window (startTime) or
  the selection in find-recent-backup.sh (rule from splice#6447); cni monitor fallback.

## AR. Fixed wait for deadline-driven automation shorter than deadline + automation delay + a slow round trip
- Signature: a default `eventually()` (20 s) waits for an SV trigger to archive a contract whose expiry was set to
  now + N s; the trigger acts only after expiry + `clockSkewAutomationDelay` (5 s).
- Confirm: trigger `Processing ReadyTask( readyAt = ...` close to expiresAt + 5 s, and SubmitAndWait completing after
  the `Failed clue`; per-minute BFT block-store latency on the sequencer (bftdb.py in the 11333 packet) shows the slow
  window.
- 11333 (2026-11-10, main e59889cad0, wall-clock-time (6), UnclaimedActivityRecordIntegrationTest "An
  UnclaimedActivityRecord gets expired"): 18 s archive round trip during a 10:00:05-10:02:40 BFT orderer DB slowdown
  (block store 3-5 ms to 100-280 ms per block, all leaders, view 0); cause of the DB slowdown unknown. Earlier same
  symptom without root cause: 9729 (release-line-0.7.0, closed). Fix: explicit `eventually(60.seconds)` for both
  archive checks (lines 116 and 191).

## AS. SvFrontendIntegrationTest: expiring proposal closed by sv2-sv4 before the in-flight check
- Signature: `did not contain element "This proposal expires before anybody votes on it" (SvFrontendIntegrationTest.scala:846)`
  in "Set Dso Rules Configuration proposals can be rejected by other SVs and can expire" (the tracker search on the
  test name misses 11186; search the expected element text).
- Confirm: `CloseVoteRequestTrigger` `Completing dso delegate task` on SV=sv2|sv3|sv4 for the expiring request before
  `Running clue: sv1 sees both proposals in flight`.
- Mechanism: the test (#7452) pauses CloseVoteRequestTrigger only on sv1, but every SV runs it (delegateless); the
  10 s proposal lives only until about voteBefore + 5 s plus the close round trip, and the UI step creating the second
  proposal (two ledger creates) can take longer.
- 11186 (2026-11-04, root-caused by rautenrieth-da 11-05), 11340 (2026-11-10, main 1844b95301, sv2 closed it,
  dup). Fix: pause (:805-810) and resume (:856) on sv1Backend to sv4Backend, as
  SvStateManagementIntegrationTest does. Not on main 2026-11-10.

## AT. Frontend test thread stalls for minutes inside the only `eventually` attempt
- Signature: a frontend check right after an action (login, tap) fails on an empty or stale read (`List() had
  length 0`, `forAtLeast(1) ... in List()`), the clue lasts far longer than its `eventually` budget, and the page
  dump at failure shows the expected row.
- Confirm: in the geckodriver log the check has exactly one round of WebDriver commands, all answered in ms, then a
  gap of minutes with no command until the failure dump (gap awk in the 10918 packet, section 3); the test thread
  logs nothing in the gap while other JVM threads and the `BiDi Connection` relay log normally.
- Mechanism: `BaseTest.eventually` (vendored canton test code) checks the deadline only after an attempt fails, so
  an attempt that outlasts the budget is final and its stale observation is reported. Where the test thread blocks
  is unknown: not a Selenium read timeout (180 s default, 10920 stalled 208 s), no DNS errors, stdout flowing.
- 10918 (docker-compose (1), DockerComposeValidatorFrontendIntegrationTest, 127.9 s) and 10920 (docker-no-canton
  (0), LocalNetFrontendIntegrationTest, 208.4 s), both run 40969391292 (main 6d404e421b, 2026-10-29) on different
  docker-large runners; the stalls ended 2.6 s apart (23:26:16.5, 23:26:19.1). No recurrence by 2026-11-10.
  Hardening if it recurs: one more attempt in `FrontendTestCommon.eventually` when the deadline passed during an
  attempt, plus a test-thread stack dump when an attempt exceeds the budget.
- 11561 (2026-11-18, cimain docker preflight, splice e74e9bf844,
  DockerComposeValidatorPreflightIntegrationTest tapAmulets, 115.7 s): dup, same point as 10920 (after the
  `.tx-amount-amulet` text of the validator's own top-up tap, before `GetSpliceInstanceNames`). Lead: that call is
  the first console HTTP command of the JVM, which builds `SpliceConsoleEnvironment.httpCommandRunner` and so loads
  the package signatures of all DAR resources (lazy, unlogged, 30-61 s in the apps on CI shards); suites without
  local apps (preflight, compose, LocalNet) pay it inside the `tapAmulets` attempt. Extra confirm for this variant:
  `grep -a -c 'Running on '` before the stall is 0. Does not explain 10918 (no console command). Candidate fix:
  resolve `amuletNameAcronym` before the `eventually`.

## AU. Retry budget includes external process startup (withCanton does not wait)
- Signature: `Failed clue: We can tap and list after Canton restart and domain reconnection` with `Authorization
  Failed for alice_wallet_user-... Reason: UNAVAILABLE: io exception` through most of the budget.
- Confirm: in canton-standalone-wallet-survive-canton-restarts-2.clog.gz, `Listening on 127.0.0.1:27501` more than
  about 100 s after `Running clue: We can tap and list after Canton restart` in canton_network_test.clog.
- Mechanism: withCanton spawns the process and runs the body at once; eventuallySucceeds(2.minutes) covers JVM start,
  participant startup (package metadata view rebuild of about 100 packages on restart) and synchronizer reconnect.
  Slow runner, budget exhausted.
- 11346 (2026-11-11, wall-clock-time (5), main 5ccef7f), parent. Fix: splice#8444 raises the budget to 5 min.

## AV. Validators outlive a scan restart in LsuIntegrationTest
- Signature: checkErrors only, `"message":"Consensus not reached."` WARN from
  `BftScanConnection:LsuIntegrationTest/.../validator=(aliceValidator|splitwellValidator)`, span
  `ValidatorPackageVettingTrigger-work` or `RollForwardLsuTrigger-work`, a few seconds after
  `Starting 'LsuIntegrationTest/upgrade synchronizer to new physical synchronizer without downtime'`.
- Confirm: `zcat canton_network_test.clog.gz | grep -a -E 'Stopping node sv1Scan"|Total maximum number of retries 12 exceeded|"Consensus not reached."'`
  shows the stop, then give-ups within about 8 s.
- Mechanism: test 2 does `allNodes.par.foreach(_.stop())` (scans, SVs, SV validators) and `initDso`; aliceValidator
  and splitwellValidator from test 1 are not in `allNodes`. Scans refuse connections while stopped and answer 404
  while initializing; the BFT client-call retry budget (12 retries, about 7 s) runs out if the second scan is not
  healthy within that time (11354: sv1Scan HTTP unbind 5.03 s). Race since #6503 (2026-07-23).
- 11354 (2026-11-11, logical-sync-upgrade (0), main b350273), parent. Fix: stop the two validators before the
  restart (test). Fixed by splice#8469 (09f5cc4b78, 2026-11-13).

## AX. New sequencer catch-up breaker plus SV-app breaker (RollForwardLsuIntegrationTest vote)
- Signature (canton_network_test.clog): `Failed clue: sv3 accepts vote` (SvTestUtil.scala:271 / :277) after
  `castVote` retries with SEQUENCER_BACKPRESSURE `Sequencer can't take requests because it is behind on processing
  events`, then WARN `Circuit breaker low-priority-connection tripped after 20 failures` (SV=<voter>), then `Command
  submission aborted by circuit breaker`.
- Confirm (canton.clog): on that SV's sequencer, `Starting Onboarding state transfer` shortly before, and
  `Sequencer not accepting requests momentarily ... behind more than 90 seconds` / `Sequencer now accepting requests
  again` bracketing the castVote refusals; `LocalSequencerConnectionsTrigger` `local sequencer only` inside that window.
- Mechanism: the sequencer replays blocks from a topology > 90 s old, the Canton breaker refuses for 30 s; the SV app
  has just switched its participant to that sequencer only; the test's own castVote retries (20) trip the app
  low-priority breaker (test max-failures 20, 30 s reset), which outlasts the clue's wait.
- Fix (test): CircuitBreakersConfig.never for the voting SV apps, as LsuIntegrationTest does for sv4 (#5587), or wait
  until each voter can submit before scheduling. Distinct from AK (RollForwardLsuDR, shard 0) and the 11255 shapes.
- 11384 (2026-11-12, roll-forward-lsu (1), main 0a0229c744, Canton 3.7.3-snapshot.20261111), parent.

## AW. Re-onboarded SV sequencer on the same P2P endpoint: peers keep the old sequencer id (Canton BFT)
- Signature: SvReonboardingIntegrationTest `Timeout while waiting for initialization of sv4Reonboard` (second
  startAllSync, test line 446); sv4Reonboard log ends in `Waiting until Mediator has been granted unlimited traffic`
  with `No traffic state found for member MED::sv4ReonboardSvReonboarding`.
- Confirm on canton-standalone-sv123-reonboarding.clog.gz: sv1-3 sequencers log `Associating P2P endpoint
  P2PUrl("http://localhost:27410") ... was previously associated with SEQ::sv4ReonboardSvReonboarding..., changing to
  SEQ::sv4SvReonboarding...` repeatedly and `Dropping network message to unknown SEQ::sv4ReonboardSvReonboarding`; on
  canton-standalone-sv4-reonboarding-new.clog.gz `Retrying block transfer request for epoch N` without `Completed
  state transfer`.
- Mechanism: after the offboard vote the SVs remove the old sv4 P2P endpoint while the old sv4 sequencer still runs;
  it reconnects inbound and is authenticated again under its old id, leaving a connection actor for
  NodeId(old id, localhost:27410). The new sv4 Canton reuses port 27410; each authentication of the new id is undone
  by the old actor's next send about 100 ms later. Same symptom as 10011 (ciperiodic sv re-onboard, closed as fixed
  in Canton 3.6 by DACH-NY/canton#35711, splice#7264). That fix (main forward port #35710) IS in 3.7.3-snapshot.20261109;
  this is a gap in it: at the old sequencer's stop, `handleSendFailure` removes the sender but keeps association and
  ref, then `clearActiveConnectionState` (keyed by sender) finds nothing, so the incoming ref leaks; `consolidateNetworkRefs`
  hands it to the new id (packet section 9).
- 11363 (2026-11-11, key-download (0), main 644b3ba, Canton 3.7.3-snapshot.20261109): first hit, 868 flips on sv1.
  Fix in Canton (open); test mitigation merged: splice#8465 (635294d6e6, 2026-11-13) gives the re-onboarded sequencer
  its own P2P port.

## AY. SV joining after an LSU waits on the uninitialized legacy sequencer (SV app init fails)
- Signature (GCP, namespace of the joining SV, sv-app): `Onboarding sequencer`, then `setting domain config for <sv>`
  retries with `UNAVAILABLE: Sequencer does not have a synchronizer ID in its status response`, `Total maximum number
  of retries 120 exceeded`, `JoiningNodeInitializer joining Dso with key failed`; CI: `kubectl wait ... validator-app`
  never matches.
- Confirm: the sv-app config dump (`SpliceEnvironment with config`) has a `legacy {` block under
  `local-synchronizer-nodes`, and the status requests in the wait go to `global-domain-<old>-sequencer`.
- Mechanism: `SynchronizerNodeReconciler.buildPhysicalSynchronizers` (the `preInit` of `onboardLocalSequencerIfRequired`)
  calls `getPhysicalSynchronizerId` on every legacy sequencer; one that the joining SV never initialized has no id.
  Successor nodes are skipped when uninitialized, legacy nodes are not.
- 11379 (2026-11-12, ciperiodic-lsu deploy_lsu_operator, splice 0.10.2-snapshot.20261111): parent. The pod without the
  legacy block (01:39:05) proceeded to `Onboarding sequencer ... through sponsoring SV`. Not AW (no P2P flips).
- 11557 (2026-11-18, same workflow, job and step, splice 0.10.3-snapshot.20261117 facc9443): duplicate, confirmed.
  The legacy block is in the SV app config on all three starts of the pod; the init failed twice after the retries
  against the legacy sequencer ran out. The cluster config declares the pre-LSU migration as legacy from the retire
  step until the archive step, and the SV redeploy and its preflight run in between. Also on the first run with
  splice#7795 (snapshot download ok).
- 11642 (2026-11-19, same workflow, job and step, splice 0.10.3-snapshot.20261118 e5938144): duplicate, confirmed.
  Here the archive step overlapped the wait: the pod rolled out after it, without the legacy block, got through
  the sequencer onboarding 4 minutes after the wait had ended. Second night in a row for a nightly workflow: hard
  broken.
- 11689 (2026-11-21, same workflow, job and step, splice 0.10.4-snapshot.20261120 a0e74065 = main tip, Canton
  3.7.4): duplicate, confirmed. Same shape as 11642; the replacement pod got through 3 minutes after the wait had
  ended. Third failure of this job in four nights (11-20 failed earlier, 11670).

## AZ. Cluster hostnames NXDOMAIN during the cluster certificate renewal (DNS-01 challenge hides the wildcard)
- Signature (preflight canton_network_test.clog): `HTTP request failed` with `java.net.UnknownHostException:
  <app>.<ingress>.<cluster>.network.canton.global` for one or two ingresses, for one to a few minutes, while other
  ingresses resolve.
- Confirm: cert-manager logs (namespace cert-manager) `"Certificate must be re-issued" ... reason="Renewing"` before
  and `"certificate issued"` after the failures; `kubectl get certificaterequest -A` age matches. Decisive (not yet
  seen): `_acme-challenge.<ingress>.<cluster>` TXT changes in the Cloud DNS log of project da-gcp-canton-domain.
- Mechanism: the zone has only `<cluster>` and `*.<cluster>` A records (cluster/pulumi/infra/src/network.ts
  clusterDnsEntries); the certificate covers `*.<ingress>.<cluster>` per ingress and uses ACME DNS-01. A challenge
  TXT at `_acme-challenge.<ingress>.<cluster>` makes `<ingress>.<cluster>` an empty non-terminal, so the wildcard
  does not apply below it (RFC 4592) until the challenge is removed.
- 11382 (parent), 11383 (dup): ciperiodic deploy_upgrade preflights 2026-11-12 10:58-11:00, renewal 10:54:41-11:05:26.
  Possibly 7558 (cimain 2026-03-17, unchecked). Fix: explicit `*.<ingress>.<cluster>` A records in clusterDnsEntries.

## BA. Test wait covers serialized confirmed-action executions (ExecuteConfirmedActionTrigger single lock)
- Signature: a clue waiting for the result of a confirmed DsoRules action (e.g. `ProcessRewardsV2 are created and
  RewardMetricsTrigger reports active counts`) fails with the last of several actions still unexecuted.
- Confirm: delegate SV `ExecuteConfirmedActionTrigger` `Acquired lock after PT<seconds>` of several seconds, and
  `executed action ...` for the awaited action just after the `Failed clue` timestamp.
- Mechanism: one write lock per SV app for all executions (ExecuteConfirmedActionTrigger.scala:67-69, TODO tech-debt),
  task parallelism 4 held by tasks of earlier actions; one slow SubmitAndWait delays every later action.
- 11392 (2026-11-12, simtime (0), main 0911e29902): R8 minting executed 1.15 s after the 20 s wait. Fix: longer
  eventually in the clue (test); optional one lock per action (app).

## BB. Test JVM stalls in the RemoteClock fetch after a sim-time advance (11405)
- Signature (canton_network_test.clog): `Task fetching remote time did not complete within 2 minutes` (WARN,
  logger `c.d.c.t.RemoteClock`, every 2 min, from all apps), reported failure
  `LedgerApiStoreIntegrityChecker ... Future timed out after [60 seconds]` or any 1 min console timeout; then the
  next suite stays `Queued` in the ConcurrentEnvironmentLimiter and sbt is killed at 40 min.
- Confirm: `Request fetch remote time has succeeded` stops while `Sending request fetch remote time` continues;
  in the Canton log every `CurrentTime` request is answered, zero `P2P connectivity is not ready`, zero
  `currently blacklisted`, epochs keep coming, no `sends request with id` on the global sequencers after the stall.
- Mechanism: 11405 (2026-11-13, simtime (0), main 635294d6e6, Canton 3.7.3): 12 s after `advancing time by PT10M10S`
  all 17 remote clocks of the test JVM wait (scheduler threads plus 66 env-ec threads); responses answered by the
  participants are not processed. Likely the callbacks need the exhausted execution context; not shown (the dump has
  no env-ec threads). Trigger of the exhaustion unknown.
- Parent 11405, first hit; 5549 (2025, closed without analysis) has the same warning in a setup.
- Fix: none proposed; test infrastructure or Canton RemoteClock.

## BC. Wait for a resumed wallet trigger starts while the SV closes the issuing round it uses (11532)
- Signature: `WalletRewardsTimeBasedIntegrationTest ... 0E-10 was not greater than or equal to 1006.00 delta=0E-10
  should be at least V2 coupons (1006.00) (WalletRewardsTimeBasedIntegrationTest.scala:299)` in a simtime shard.
- Confirm (canton_network_test.clog, bobValidator end-user party of the validator, loggers TreasuryService and
  CollectRewardsAndMergeAmuletsTrigger): after `Resuming trigger.`, `Batch failed with ABORTED:
  LOCAL_VERDICT_LOCKED_CONTRACTS` then `Batch failed with NOT_FOUND: LOCAL_VERDICT_INACTIVE_CONTRACTS` on the same
  contract id, then `Completing operation ... COO_MergeTransferInputs` after the `Failed clue: Bob's balance reflects
  his own V2 coupon` timestamp; the contract id is the argument of sv1's `DsoRules_MiningRound_Close`
  (ExpireIssuingMiningRoundTrigger) submitted within a second of the resume.
- Mechanism: `advanceTimeForRewardAutomationToRunForCurrentRound` advances to 5 s past the issuing round's closing
  time; the wait for bob's minting starts while sv1 closes that round, so bob's merge is rejected twice (locked,
  then inactive from a stale round) before it succeeds without the round. Three round trips: 3-4 s normally, 27.7 s
  when each takes 8-10 s.
- 11532 (2026-11-17, simtime (1), main dbf2ab6c8f, mainnet-Daml nightly, Canton 3.7.4-snapshot.20261112): round trips
  8-10 s from 05:50:52 to 05:57:04 (BFT epochs 17-33 s instead of 7-8 s on the 4-node ordering topology left by the
  previous suite; PBFT round per block 0.3 s instead of 0.015 s). Cause of the slow window open; the 11375 check
  (second shard of the run) could not be run, artifact download refused (egress limit).
- Fix: `eventually(60.seconds)` in the clue, branch `pperek/fix-11532-wallet-rewards-v2-mint-wait` (edcac5eb9a, local,
  not run). Does not address the slow window.

## BD. Sponsor SV app deadlock: sequencer onboarding state queued behind a blocked ACS export (11539)
- Signature (cluster deploy): the SV app Helm release of every joining SV times out, founding SV fine. In tests
  expect a joining SV init timeout with the joiner logging `download onboarding snapshot <id> from sponsor ...
  UNAVAILABLE: Onboarding snapshot <id> is queued` until the end.
- Confirm on the sponsor SV app: `SvOnboardingSnapshotService` logs `Starting onboarding snapshot export <a>` for an
  `Acs(...)` key with no `Finished` for it, then `Queued onboarding snapshot export <b> for
  SequencerOnboardingState(...)` with no `Starting ... <b>`; on the sponsor sequencer the last `New epoch` has
  `size = 2` (or the size that first includes the joiner) right after `below strong quorum size`, followed only by
  `Dropping network message to unknown SEQ::<joiner>`. Zero `P2P connectivity is not ready`, zero blacklisting:
  not family B.
- Mechanism: since splice#7795 (69b922dca8, 2026-11-17) the sequencer onboarding state goes through the same export
  queue as the DSO party ACS (#7794), default `parallelism = 1`. An ACS export for joiner X started just before
  joiner Y's sequencer enters the ordering topology waits on the synchronizer (participant answers
  `EFFECTIVE_PARTY_TO_PARTICIPANT_MAPPING_NOT_FOUND` every 120 s, retried inside the job); the synchronizer waits on
  Y's sequencer, which waits on its queued onboarding state. Race: needs two joiners at one sponsor.
- 11539 (2026-11-17, cimain cluster deploy, splice 08a89e8dcc, Canton 3.7.4-snapshot.20261112): sv-1 sponsor, ACS
  export for sv-3 started 12:30:39.950, 1 -> 2 (sv-da-1) at 12:30:40.574, sv-da-1 state queued 12:30:41.144, frozen
  until the Helm timeout 12:34:52. Evidence: private CI job output and cluster logs; not reproduced on this branch. Fix on local branch
  `pperek/fix-11539-sequencer-snapshot-export-lane` (86631f9ed5).

## BE. New cluster node cannot pull the istio sidecar image, registry.istio.io 403 (11541)
- Signature (cluster deploy and its preflights): the preflight wait for the validator-app pod finds no pod, or
  times out on it, until it gives up; the deploy job has several Helm releases of one namespace timing out at
  once. No container log from the affected pods at all.
- Confirm: private CI job output and cluster logs; not reproduced on this branch. The pulls of the istio proxy image fail with 403 Forbidden from registry.istio.io on the anonymous
  token request, all from one node that registered minutes before; other nodes already have the image.
- Not BD: zero `onboarding snapshot` lines on the sponsor SV app, and the joining SV app never starts.
- Mechanism: the runbook stacks need more CPU than the cluster has, the autoscaler adds a node, the node has no
  cached sidecar image and the registry refuses the pull; every pod with a sidecar on that node stays in
  ImagePullBackOff, the Helm releases time out, later stacks (the validator of the SV runbook) are never created.
- 11541 (2026-11-17, cimain, splice 772d394449, istio 1.30.5 since #7454): parent. One new node, first 403
  16:01:58, still 403 at 18:35; last successful pull on any node 15:53:33. Open: registry-side change or rate limit
  versus node-specific egress. Fix is infra (serve the istio images from a registry the cluster controls); no test
  change.
- Same cimain run: 11542 (SV preflight), 11544 (validator preflight; the awaited pod is itself on that node), 11545
  (validator runbook deploy), 11546 (SV runbook deploy). The pull failures end a few minutes after the node joins
  (the kubelet then reports only the back-off), so look from the start of the runbook deploy, not only over the
  job's own window.
- ciperiodic, full cluster deploys after a reset: 11547 (SV re-onboarding test; all three app nodes are new, so
  every node is affected) and 11551 (upgrade test on release-line-0.10.2, 90 minutes later on the same three
  nodes). A node need not be new: it only needs to lack the image, so every deploy on these nodes fails until the
  registry answers again.
- Later cimain deploys the same day: 11548, 11552, 11555. The node survives the cluster reset and gets pods of each
  new deploy. Most pod-initializing lines of a deploy log are transient: only pods on the bad node carry the 403.

## BF. Sv1BootstrapProtocolVersion36IntegrationTest GetId polls refused after the admin API is up (11228)
- Signature: `sv1 bootstraps a fresh synchronizer with protocol version 36 and sv2 joins *** FAILED ***` with
  `UNAVAILABLE: io exception. (Sv1BootstrapProtocolVersion36IntegrationTest.scala:98)`, `Failed clue: Canton nodes are
  running but have no identity`.
- Confirm: in canton-standalone-sv1-bootstrap-pv36.clog.gz,
  `grep -a -E 'Starting admin-api services on AdminServerConfig\(127.0.0.1,Some\(27102\)'` is 40-60 s after
  `Running clue: Canton nodes are running but have no identity` in canton_network_test.clog, yet the
  `GetId to 127.0.0.1:27102: failed with UNAVAILABLE/io exception` polls after it fail in the millisecond they are
  sent (`Connection refused`), and the standalone log has no `GetId` line.
- Mechanism: the test builds its admin connections before withCantonSvNodes spawns Canton and polls them for 60 s per
  node. Canton starts sequencers, mediators, participants in sequence with Flyway migrations each (45-55 s to the
  participant admin API on a busy runner); the long-failing channel does not reconnect in the rest of the budget
  (gRPC reconnect backoff, inferred, not verified). Test issue. Related pattern: AU (withCanton does not wait).
- 11228 (2026-11-05, wall-clock-time (2), main fc8bacfc18, Canton 3.7.2), parent: admin API up 45 s in, 3 polls
  refused.
- 11553 (2026-11-17, wall-clock-time (7), main d52fe77b48, Canton 3.7.4-snapshot.20261112): admin API up 54.7 s in
  (19:44:40.885), polls 19:44:44.458 and 19:44:46.199 refused.
- Fix (proposed on 11228, not written): build the connections after Canton is up or use a fresh one per poll, and
  raise the budget above the startup time.

## BG. Compose override passes a HOCON substitution with a single `$`, docker compose blanks it (11562)
- Signature: `Failed to start docker-compose validator (with auth) (DockerComposeValidatorFrontendIntegrationTest.scala:76)`
  (or any `splice-compose.sh start -a`), `container splice-validator-validator-1 is unhealthy`,
  `ERROR: Validator failed to become ready` seconds after the start.
- Confirm: the job log has the docker compose warning
  `grep -a '_participant_client_admin_api_auth_config.. variable is not set'`, and compose-validator.clog.gz has
  `grep -a 'Failed to read config at startup'` with `/app/additional-config.conf: 16: Cannot concatenate object or
  list with a non-object-or-list, Unquoted(\"canton.validator-apps.validator_backend.storage\")`.
- Mechanism: `cluster/compose/validator/compose-enable-participant-admin-api-auth.yaml` (splice#8216, loaded by
  `start.sh -a --enable-participant-admin-api-auth`) writes `${_admin_api_auth_service}` and
  `${_participant_client_admin_api_auth_config}` (HOCON objects defined in the images) without the `$$` escape. The
  validator gets `auth-config = ` with no value and cannot parse its config; the participant gets `auth-services=[]`
  and starts with admin API auth off. Deterministic, not a flake: every run from main 8399d63908 on until fixed.
- 11562 (2026-11-18, docker-compose (1), main 8399d63908, Canton 3.7.4-snapshot.20261112), parent: first run of the
  path, the docker-compose job was skipped on every run of the PR.
- 11563 (2026-11-18, docker-compose (1), main ec059037fa, the commit after #8216, Canton 3.7.4-snapshot.20261112):
  same warning 12:48:30, `Failed to read config at startup` 12:48:42.066.
- 11597 (2026-11-18, docker-compose (1), main 465b29cc47 (#8551), run 44322981802, Canton 3.7.4-snapshot.20261112):
  same warning 14:59:37, `Failed to read config at startup` 14:59:49.995; override unchanged on origin/main 53e7efb8e2.
- 11639 (2026-11-18, docker-compose (1), main 53e7efb8e2 (#8569), run 44364590139, Canton 3.7.4-snapshot.20261112):
  same warning 18:40:57, `Failed to read config at startup` 18:41:06.565; no `cluster/compose` change since 465b29cc47.
- Fix (2 lines, `$${...}`, uncommitted in worktree fix-11562, not verified): open point is whether the Auth0 test
  tenant has the `https://participant_admin_api.example.com` API and a grant for the Docker-Compose Validator App.

## BH. Main test sources do not compile after a merge race (11566)
- Signature: every failed job of a post-merge main run (docs, static_tests and all scala_test shards) ends in
  `[error] (<project> / Test / compileIncremental) Compilation failed`, no test runs.
- Confirm, per job id:
  `gh api --allow-escape-sequences repos/canton-network/splice/actions/jobs/<JOB>/logs | grep -a -c 'SvOnboardingSnapshotServiceTest.scala:361:42: wrong number of arguments for pattern'`
  (1 = this occurrence). For a new occurrence, grep `\[error\] .*\.scala:[0-9]+:[0-9]+:` and find the two PRs.
- Mechanism: splice#7796 (merged 2026-11-18 12:09Z) changed `SvOnboardingSnapshotService.SnapshotState.Ready` to
  `Ready(file, sha256Base64)`; splice#8558, branched before it and not rebuilt, added `case Some(SnapshotState.Ready(_))`
  to SvOnboardingSnapshotServiceTest and merged at 14:44Z. Hard broken, not a flake.
- 11566 (2026-11-18, docs, main a4ca636ec0, run 44327510557), parent. Same line in all 35 failed jobs of
  44327510557 and all 35 of 44328806873 (main 10de6749e0); issues 11566-11636 except 11597 (other run, BG).
- 11637 (2026-11-18, cimain deploy, build tests job, splice 10de6749e0): same compile error. A later cimain run on
  53e7efb8e2 built the tests green.
- Fix: splice#8569 (53e7efb8e2, merged 17:58:38Z, `Ready(_, _)`); run 44364590139 has docs and static_tests green.

## BI. validator1 with disableAuth: validator-app needs a participant admin API secret nobody installs (11638)
- Signature (ciperiodic SV re-onboarding test, cluster deploy): the deploy step fails while every SV, splitwell and
  infra namespace is healthy; only namespace validator1 keeps warning to the end of the job.
- Confirm: private CI job output and cluster logs; not reproduced on this branch. The validator-app pod of validator1 waits on a participant admin API auth secret that does not
  exist. Not BE: no sidecar image pull failures.
- Mechanism: only this workflow deploys validator1 with `disableAuth=true`.
  `installValidatorApp` then installs no validator secrets, but splice#8216 (8399d63908, merged 2026-11-18 10:49
  UTC) renders `participantClient.adminApi.disableAuth` (and the participant's `adminApi.disableAuth`) from
  `disableParticipantAdminApiAuth` only, so the chart references the missing secret. The mock config has the same
  combination, so `cluster/expected/validator1` on main already shows `disableAuth: false` without the secret.
  Hard broken from the first sv_reonboard run on a splice commit with #8216.
- 11638 (2026-11-18, splice 10de6749e0): parent. Fix branch pperek/fix-11638-validator1-disable-auth-admin-api
  (9eb5f3b841, not pushed): `disableAuth ||` the separate flag in common-validator validator.ts and participant.ts.
- 11638 was closed 2026-11-19 09:16 UTC on the revert splice#8571 (e59381448e); the revert was reverted by
  splice#8577 (5eff61ca40, merged 12:27 UTC) with the validator1 code unchanged, so BI is back on main.
- 11666 (2026-11-19, splice c34a2a5b91, contains 5eff61ca40): expected BI by the code and the deployed
  configuration, NOT confirmed from the cluster.
- 11678 (2026-11-20, splice 6c71d08233, contains 5eff61ca40): confirmed. Fixed on main by splice#8617
  (ae237a8ac9, merged 2026-11-20 11:43 UTC): `config.disableAuth ||` the separate flag in validator.ts and
  participant.ts; the unpushed 11638 fix branch is superseded.

## BJ. Release deploy reads main's v1 fixed-token cache, ledger API auth secrets without token (11640)
- Signature (ciperiodic upgrade test, base release deploy): SV, scan, splitwell and validator app pods cannot start
  because their ledger API auth secret has no `token` key.
- Confirm: private CI job output and cluster logs; not reproduced on this branch. The deployed base must lack splice#8494 (ba914d47e5), and an earlier main deploy must have
  rewritten the fixed-token cache secrets of the affected namespaces.
- Mechanism: splice#8494 stores `{version: 1, <clientId>: {ledgerApi, participantAdminApi}}` under the same secret
  name as before; release-line-0.10.2 `auth0.ts` parses each entry as `{accessToken, expiry}`, `new Date(undefined)`
  is Invalid Date, the expiry check is false, the undefined `accessToken` is returned, and `ledgerApiSecretContent`
  creates the Secret without `token`. #8216 forces the rewrite (new participant admin tokens). The caches live in
  namespace default and survive the cluster reset.
- 11640 (2026-11-18, base splice 48f45bf33): parent. The caches of four SV stacks and splitwell had been rewritten
  by the 11638 run (splice 10de6749e0); the errors appear in exactly those five namespaces, validator1 (cache not
  rewritten) has none.
- 11656 (2026-11-19, same base splice 48f45bf33): dup, the next daily run. No cache write in between, same five
  namespaces. #8216 was reverted and re-landed on main in between; irrelevant, the caches stay.
- 11669 (2026-11-19, base splice b12a1318f, release-line-0.10.2 three backports later, still without #8494): dup, a
  second run the same day, same five namespaces.

## BK. Cluster reset: refresh of a Cloud SQL user fails 404 after its instance is gone (11644)

The scheduled cluster reset refreshes then destroys every stack (`downStack` in splice
`cluster/pulumi/pulumiOperations.ts`). When a Cloud SQL instance of the cluster's canton-network stack no longer
exists in the cloud project, the refresh drops the instance from the state, but the read of a SQL user resource on
it fails with a 404 for the missing instance; the refresh exits 1 and the abort cancels the other stacks. Sticky:
the user stays in the state, so every hourly run fails until it is removed by hand (`pulumi state delete`). Not the
Cluster reset race (11170): no missing Helm release error, and the first failed operation is the destroy of the
canton-network stack.

- Parent 11644 (scratchnetc, 2026-11-19 05:11; the deployment of the lock holder had been interrupted, 3 stacks
  with pending create operations).
- Duplicates, each confirmed from its own job log, all 2026-11-19 on scratchnetc: 11646 (06:19), 11647 (07:12),
  11648 (08:15), 11652 (10:11), 11655 (11:10), 11657 (12:16). The runs at 09:12 and 13:11 failed too (conclusions
  only).
- Confirm: private CI job output; not reproduced on this branch.
- Open: what deleted the instance outside the stack state; fix in the reset (tolerate the failed refresh or drop
  children of a missing instance) is an owner decision.

## BL. daml_test: damlc cannot create the script context, gRPC Deadline Exceeded (11651)

`sbt damlTest` runs up to four `dpm test` processes at once (`Tags.limit(damlTestTag, 4)` in
`project/BuildCommon.scala`, tuned for the large executor), each with its own script service JVM, on
`self-hosted-k8s-small`. When the start of one process overlaps several heavy ones, the creation of the script
context does not finish within the gRPC deadline and the whole module fails with a compiler diagnostic at `1:1-2:1`
instead of test results; sbt reports `damlc test failed <package dir>`. No Daml code involved. Not the runner network
failures of the same job (9172, 7205, 7172: github.com unreachable, ETIMEDOUT).

- Signature: `ScriptBackendException {scriptNote = "Failed to create script context", scriptBackendError =
  BErrorClient (ClientIOError (GRPCIOBadStatusCode StatusDeadlineExceeded` followed by `Finishing shakeRun (took
  1m..s, completed)` and `[error] damlc test failed`.
- 11651 (2026-11-19, main 7666260d8d, splitwell-test, TestSplitwell.daml: 75 s from process start to the
  diagnostic, 16-18 s to `test: ok` in passing runs; three overlapping runs of over a minute, sbt 591 s against
  437-460 s).
- Earlier, before the catalogue: 6724 (2026-01-07, same module, same message), closed as duplicate of the catch-all
  6681 (slow CI jobs, pod CPU maxed out at the time of the error), closed completed 2026-02-11.
- Confirm: `grep -a -c 'Failed to create script context' job.clean.log` > 0 with `StatusDeadlineExceeded` on the
  same line, and no `<module>.daml:<test>: ok` line for the module named in the `File:` line above it.
- Open: runner CPU at the time (Grafana); owner decision between lower damlTest concurrency, a larger runner, a
  longer script service timeout, or leaving it at this frequency.

## BM. Frontend test navigates away while the register call of the onboarding button is pending (11658)

`FrontendLoginUtil.withAuth0LoginCheck(onboardThroughWalletUI = true)` clicks "Onboard yourself" and returns when
`#logged-in-user` is shown. The wallet UI shows it as soon as the 1 s `user-status` poll sees the WalletAppInstall,
while `POST /v0/register` only returns after the ValidatorRight has been created too (one more transaction). A test
that navigates right away (WalletAuth0FrontendIntegrationTest "persist user name on refresh" reloads,
AnsAuth0FrontendIntegrationTest goes to the ANS UI) aborts the pending request; the onboarding mutation has
`retry: 3`, and the old document sends the register again 1 s later, as long as the new page has not committed. The
second register overlaps the first: createValidatorRight gets `SUBMISSION_ALREADY_IN_FLIGHT`, then
`DUPLICATE_COMMAND`, logged at WARN by HttpValidatorHandler, HTTP 409. All tests pass, checkErrors fails.

- Signature: `The operation 'createValidatorRight' failed with a non-retryable error, not retrying:` ...
  `DUPLICATE_COMMAND` from `HttpValidatorHandler` of a validator in an Auth0 frontend suite.
- 11658 (2026-11-19, main 5eff61ca40, frontend-wall-clock-time (1): click 12:43:30.347, navigate 12:43:34.645,
  second register 12:43:35.649, first register 200 at 12:43:35.985, WARN 12:43:36.199).
- Confirm: two `POST /api/validator/v0/register ... received request` lines of the same validator within the suite,
  the second before the first `Responding with status code: 200`; in the browser log a `WebDriver:Navigate` between
  the two `validator calling POST .../v0/register` lines, both from the same realm, and no `validator got response`.
- Not the tap family (3536, 9207): there one HTTP request is retried by the wallet backend after a 10 s deadline.
- Fix proposed, not committed or verified (packet 11658): wait for the URL `/transactions` in the helper. Product
  side open: register is not idempotent under overlap (`SpliceUtil.createValidatorRight`), UI `retry: 3`.

## BN. Un-retried `findAll` on a list the UI re-renders on its poll (11664)

scalatest-selenium `findAll(...).toList` is one `FindElements` call plus two to three WebDriver round trips per
element (tag name and `type` attribute, to build the typed element), about 50-100 ms per element on CI. The splice
UIs poll every query each second. A list whose rows are keyed by contract id drops a row when the contract is
recreated, and a reference held by the test goes stale. Inside `eventually` or the check of `actAndCheck` this is
retried (StaleElementReferenceException is retryable there); a bare read between two blocks fails the test.

- Signature: `org.openqa.selenium.StaleElementReferenceException`, `Command: [..., getElementAttribute {name=type,
  ...}]`, stack `WebBrowser$Query.$anonfun$findAllElements$1` under a test line that is not inside `eventually`.
- 11664 (2026-11-19, main c34a2a5b91, frontend-wall-clock-time (2)): SvFrontendIntegrationTest "can prepare an
  onboarding secret for new validator", `val licenseRows = getLicensesTableRows` (SvFrontendIntegrationTest.scala:155).
  sv2Validator's ReceiveFaucetCouponTrigger recorded liveness activity 16:00:39.035, which recreates its
  ValidatorLicense (sv1 ingest 16:00:40.205); FindElements 16:00:40.554 (7 rows), UI poll 16:00:40.749-.813, fifth
  row stale 16:00:40.896.
- Confirm: job log `-> class name: validator-licenses-table-row]` with `getLicensesTableRows` and
  `SvFrontendIntegrationTest.scala:155` in the stack; in the sv1 browser log a `sv calling GET
  .../v0/admin/validator/licenses` between the `WebDriver:FindElements` of `.validator\\-licenses\\-table\\-row` and
  the `stale element reference` answer.
- Not the ANS search button entry of section F (11187): that one is a click on a button remounted by a debounced
  lookup, already inside `eventuallyClickOn`.
- Fix on a local branch, no PR: `val licenseRows = eventually()(getLicensesTableRows)`; the suite passes locally
  with it (13 of 13), the race itself was not reproduced.

## BO. Wait for a confirmed DSO action covers two round trips, one stuck behind a BFT epoch boundary and view change (11665)

- Signature: `WalletTimeBasedIntegrationTest ... allow a user to list multiple subscriptions in different states`,
  `had length 1 instead of expected length 2 (WalletTimeBasedIntegrationTest.scala:105)`, clue `(check) subscription
  is created` in a simtime shard, in a loop iteration after the 10 d advance.
- Confirm (canton_network_test.clog, `SV=sv1`): AnsSubscriptionInitialPaymentTrigger `Completed processing with
  outcome: confirmed to create ans entry` within the wait, ExecuteConfirmedActionTrigger `Acquired lock after
  PT0.0...S` (lock free, so not BA), and its `SubmitAndWaitForTransaction ... succeeded(OK)` after the `Failed clue`
  timestamp. In canton-simtime.clog, by the trace id of that submission: `Phase 4: Sending` to `Phase 5: Received`
  tens of seconds apart, the response batch `Ignoring proposal because we cannot assign more slots` at the end of an
  epoch, and `moving from view number 0 to 1 after reaching local timeout` in the next epoch.
- Mechanism: the Subscription is created by sv1 in two sequential ledger round trips after alice accepts the request.
  On the 4-node ordering topology the previous suite leaves behind (80 blocks per epoch, 7-8 s per epoch when
  healthy), a message batched after its sequencer used the last slot of its segment waits for the next epoch. In
  11665 that epoch started with pre-prepare writes of 1.4-3.3 s on all four sequencers, the 5 s PBFT timer fired, all
  four segments changed view, the proposed block was dropped and the batch was ordered an epoch later: 31 s for the
  two confirmation responses, 35.5 s for the round trip, 44 s from the start of the 20 s wait.
- 11665 (2026-11-19, simtime (5), main dacc71079a, Canton 3.7.4): epochs 79 and 80, 23.7 s and 25.5 s (17:24:12 to
  17:25:01), right after `advancing time by PT240H` and `PT10M`; sv1Scan's Postgres writes stay at 1-3 ms in the same
  seconds, no JVM pause. Why the sequencers' writes stall is not visible in the artifacts.
- Related slow ordering windows, cause open in each: 11532 (BC, simtime, also the suite after
  TimeBasedTestNetPreviewIntegrationTest, epochs 17-33 s for 6 min), 11375 (wall-clock, two shards at once, infra).
- Fix: `actAndCheck(timeUntilSuccess = 60.seconds)` on the `Accept subscription request` check, on the local branch
  `pperek/fix-11665-wallet-subscription-created-wait` (155cd7a595), no PR; Test/compile and the suite pass locally
  (3 of 3), the slow window was not reproduced. Does not address the slow window.

## BP. Stacks read the infra auth0 output before the infra stack adds the participant admin audience (11670)
- Signature (ciperiodic deploy through the Pulumi operator, main with #8216/#8577, after a release-line deploy
  without it on the same cluster): token requests of the stacks fail on a missing audience parameter (auth0.ts,
  `clientCredentialsGrant`), participants reject their own apps on an audience mismatch for about 12 minutes, the
  operator stops retrying sv-runbook at its retry limit, and namespace sv ends with a scan app and no SV app.
- Confirm: private CI job output and cluster logs; not reproduced on this branch. Not BJ: no missing-token secret error after the deploy starts.
- Mechanism: the operator runs all stacks at once. `getParticipantAdminApiAudience` and
  `participantAdminApiSecretContent` read `audiences.participantAdminApi` from the infra stack output without a
  check. Before the infra update finishes, token requests throw (fast failed updates) and participant charts are
  installed with the literal `undefined` as admin API target audience, so SV and splitwell apps get UNAUTHENTICATED
  from their own participant until the stack's first update times out (13 min) and the retry redeploys. validator1
  crash-loops on the scan connection meanwhile. sv-runbook needs validator1 within 175 s per update
  (`getValidator1PartyId`, 20 retries) and normally spends one failed update on that; operator 2.10.0 stops after
  the fourth failure (`maxUpdateFailures = 3`, counter from 0). A stack whose first update succeeded (sv-canton.sv)
  keeps the `undefined` participant.
- 11670 (2026-11-20, ciperiodic LSU test, base deploy, splice dbb2a2c9d, after the release deploy of 11669): parent.
  Four failed sv-runbook updates (two on the audience, two on the validator1 wait) reach the retry limit; the
  audience mismatch lasts about 12 minutes in the four SV namespaces and splitwell.
- Fix (not written, owner decision): throw on a missing audience instead of rendering `undefined`; order or gate the
  dependent stacks on the infra stack; a longer validator1 wait in sv-runbook; re-request reconciliation of a stack
  at the retry limit. Ends by itself once the release line on ciperiodic carries the audience.

## BQ. Joining SV: domain time trigger gives up before the sponsor SV is ready (11673)
- Signature (log check only, tests pass): ERROR `Skipping processing of PeriodicTask(...) due to unexpected failure`
  from `DomainTimeIngestionTrigger:<suite>/config=.../SV=sv<2-4>` with `INVALID_ARGUMENT: Not connected to any
  synchronizer with id global-domain::...`, during the environment setup of a suite that starts all SVs at once.
- Confirm (`canton_network_test.clog`, lines of that SV): `RegisterSynchronizer ... succeeded` about 110 s before the
  ERROR, `Total maximum number of retries 35 exceeded` 2 ms before it, `Got 'DSO party ID from sponsoring SV'` and the
  first `ReconnectSynchronizers` only after it; on sv1 `SV1Initializer bootstrapping Dso finished after` above about
  1m45s. Not 8190 (same line, but a connected validator whose reconnect after a sequencer config change never
  completed) and not 9445 (`GetConnectedSynchronizers ... INTERNAL`).
- Mechanism: `JoiningNodeInitializer` registers the synchronizer (handshake only) and then waits for the sponsor's
  `/v0/dso`. The trigger skips NOT_FOUND from `getSynchronizerId` (not registered yet) but retries the "not connected"
  answer of `FetchTime` with the default budget. In tests the sponsor sv1 bootstraps at the same time; its DSO
  bootstrap is dominated by the DAR uploads on a cold Canton (87 with 22 repeats before #8467, 65 after).
- 11673 (2026-11-20, release-line-0.10.1 5ddb73d3d9, roll-forward-lsu (1), Canton 3.7.2): sv1 bootstrap 108.8 s (87
  uploads in 91 s, mean 3.04 s; 32 s on 2026-11-12 in the 11384 run), sv4 gives up 04:50:10.450, sponsor answers
  04:50:11.607; sv2 and sv3 at 31 and 33 failures. Signature loading 49.9 s (1.35x to 1.65x slow runner). Parent.
- Fix (not written, owner decision): return `TaskNoop` for the "not connected" answer in
  `DomainTimeIngestionTrigger.completeTask` (production log semantics change), or backport #8467 (87d584aa16) to the
  release lines below 0.10.3 (shortens the window only).
