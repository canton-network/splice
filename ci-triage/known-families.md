# Known flake families. Check these before any deep analysis.

Started on 2026-09-08 on the previous triage branch `ray/ci-triage-2026-09-15`, taken over unchanged on 2026-11-02.
Packet paths cited below and in issue comments resolve on this branch.

Format: signature to grep | confirming check | mechanism | parent ref and duplicates | fix state.

## Index

State as recorded in the family section, not rechecked against the tracker on 2026-11-02.

| Family | Subject | Parent / main refs | State |
|---|---|---|---|
| A | ACS_COMMITMENT_MISMATCH after a multi-host step | 10111 | fixed, PR 7435 (2026-09-21) |
| B | BFT 1 -> N sequencer onboarding, quorum loss, blacklisting | 10165 (umbrella), 10611 | open, Canton |
| C | false off-boarding during onboarding state transfer | 10048, 10137, 10010 | fixed, canton#35600 |
| D | sim-time jump with a command in flight | 9740, 10170, 10173, 10154 | fixed on main, backports per E |
| E | missing backports to release lines | per backport | check first on release-line runs |
| F | frontend test infrastructure | 10156, 10141, 10145, 10157, 10143 | fixed on main |
| G | tests pass, one log line fails checkErrors | 10438, 10084, 10140, 10144, 10147, 10612, 10606, 10676, 10495, 10142, 11155 | mixed, see section |
| H | evidence loss (sys.exit, teardown cascade) | 10137, 10139, 10183, 10088, 10180, 10176, 11347 | fix branches open |
| H2 | LSU: validator init against a non-active psid | 10088, 10174, 10180 | 10174 fixed, 10180 closed (no repro) |
| H3 | trigger pause timeout (TriggerTestUtil.scala:93) | 10175, 10176 | 10175 fixed |
| I | test races (venue, bulk storage, traffic, external party, sv4 sequencer, sequencer reconnect) | 8784, 10149, 10179, 10690, 10691, 11238 | mixed |
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
| AF | sim-clock jump timeouts trip the SV medium-priority circuit breaker | 11147 (dup 11291; related 10475) | open, fix PR #8420 |
| AG | first Postgres connection > 5 s at shard start, createDatabase retry WARN | 10739, 11141 | open, test config fix proposed |
| AH | SequencerPruningIntegrationTest disables the shared participants at the global sequencer | 11165 | open, owner suggested martinflorian-da |
| AI | SUBMISSION_ALREADY_IN_FLIGHT trips the validator circuit breaker | 9752 (dups 10300, 11173; 11240 via B) | closed 9752, fix cni#2785 status unknown |
| AJ | scan `claimed by multiple SVs` WARN from sv2-4 scans after the suppression ended on sv1Scan | 11304 (dups 11307, 11309, 11316, 11327) | fixed, #8389 |
| AK | RollForwardLsuDR pre-DR SV onboarding past the 300 s sequencing cutoff (BFT since #8234) | 11303 (dups 11312, 11328, 11336) | open, test budget |
| AL | AppUpgradeIntegrationTest base-version nodes not initialized within the 5 min initial wait | 11301 (dup 11302) | open, fix PR #8414 |
| AM | SV init slower than a fixed wait; a quarter of the DAR uploads are repeats | 11299, 11347 | open, splice dedup + test timeout |
| AN | cimain SV backup snapshots a CometBFT volume on CantonBFT (backup script defaults enableBftSequencer to false) | 10332 (dups 11297 11298 11314 11315) | open, splice script fix |
| AP | ManualSignatureIntegrationTest validator OTK check gives up before or during alice's key rotation | 11232 (dup 11318) | fixed, #8391 |
| AQ | cilr weekly SV catch-up: orderer restored a day behind (cantonBft backup rule), monitor 999 s fallback claims it | 11194 (also 10843) | open, splice backup selection + cni monitor |
| AR | fixed wait for deadline-driven automation shorter than deadline + automation delay + a slow round trip | 11333 | open, fix PR #8411 |
| AS | SvFrontendIntegrationTest expiring proposal closed by another SV (CloseVoteRequestTrigger paused on sv1 only) | 11186 (dup 11340) | open, fix PR #8417 |
| AT | frontend test thread stalls minutes inside the only `eventually` attempt; stale first read rethrown after the deadline | 10918 (dup 10920) | open, stall origin unknown, no fix proposed |

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
  Other runs (`scripts/ref_seq_contention.py`, 2026-11-04): 11144 simtime (3): 13775 stored, 4641 failed, chains up
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
  five runs at once, compile 12.5 min).
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
- Cluster reset race (parent 11170): during `cncluster ci_reset_unlock` the `sv` stack deletes
  namespace `sv` while `sv-runbook` still uninstalls its Helm releases there; `uninstall: Release not loaded:
  cluster-ingress-sv: release: not found`, abort, other stacks `context canceled`. scratche 11170 (11-03), 11196
  (11-05), 11343 (11-10); scratchnetc 11345 (11-10, first other cluster). Confirm: `[id=sv]` Namespace delete by `sv.sv.<cluster>` before the sv-runbook
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
- Hits: parent 11147 (2026-11-02, main 136e2e3160, 3 steps), 11291 (2026-11-09, main ab36803c8a, 4 steps, dup). Both
  in docker-canton-simtime (0), ScanNewNetworkBackfillingTimeBasedIntegrationTest, sv2. 2 of 97 post-merge main runs
  in a week. Fix in the test: `circuitBreakers = CircuitBreakersConfig.never` for all SV apps
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
