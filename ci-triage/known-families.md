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
| H | evidence loss (sys.exit, teardown cascade) | 10137, 10139, 10183, 10088, 10180, 10176 | fix branches open |
| H2 | LSU: validator init against a non-active psid | 10088, 10174, 10180 | 10174 fixed, 10180 open |
| H3 | trigger pause timeout (TriggerTestUtil.scala:93) | 10175, 10176 | 10175 fixed |
| I | test races (venue, bulk storage, traffic, external party, sv4 sequencer) | 8784, 10149, 10179, 10690, 10691 | mixed |
| J | infra, no code change | many | see section |
| K | sim time: mediator pruning backoff after a 30 d jump | 10184 | PR 7440 open (2026-09-22) |
| L | reference sequencer `insert block` 40001 retry storm | 10139, 10197, 11160 | open, Canton / test infra |
| M | slow BFT confirmation vs 10 s client deadline | 10281 | fixed, #7909 |
| N | SV1 bootstrap below participant default PV (PV 36 nightly) | 10520 | fixed, #8065 / #8125 |
| O | AcsCommitmentSender close hang crashes participant (Canton 3.7) | 10729 | open, Canton |
| P | test race with the validator's own traffic top-up | 10731 | open, test |
| Q | release-line javac inference failure on codegen | 10728, 10735 | cause open |
| R | forced ACS snapshot 500 on advisory lock | 10740 (dup 10916) | open, OriolMunoz-da |
| S | environment close timeout on in-flight submission | 10742 | open |
| T | CI test credentials rotated 2026-10-29 | 10896, 10901 | resolved by the rotation |
| U | joining SV ACS import refused, topology offset (Canton 3.7) | 10907 (dups 11107, 11163) | open, 3 hits, owner needed |
| V | PeriodicTopologySnapshot missing 'scopes' (pekko-connectors 1.3.0) | 10921 (dups 10923, 10891) | fixed on main, #8148 |
| W | ScanEventHistoryIntegrationTest pre-tap cursor race | 10912 | open, rautenrieth-da |
| X | PV 37 topology on a PV 36 synchronizer | 10934 (X1), 10927 (X2), 10937 (X3) | X1, X3 fixed; X2 open |
| Y | test tagged below the Daml packages it needs | 10930, 10933 | open, test tags |
| Z | ScanPartyMonitorScript verdict held back by /v0/events cap | 11128 (dups 11136, 11138, 11139, 11158) | open, stephencompall-DA; 5 hits 2026-11-02..03 |
| AA | LsuIntegrationTest sv4 faucet trigger ERROR during catch-up | 11104 | open, test |
| AB | BftScanConnection refresh WARN about stopped sv2Scan after suppression ended | 11140 | open, test fix proposed |
| AC | WalletMetricsTest trigger.completed lookup matches success + noop series | 10732 (dup 11143) | open, test fix proposed |
| AD | TBAR test reads per-round totals while a parallel backlog computation is still committing | 11144 | open, test fix proposed |
| AE | frontend login helper misses already-logged-in state on a slow page load | 11161 | open, test fix proposed |
| AF | sim-clock jump timeouts trip the SV medium-priority circuit breaker | 11147 (related 10475) | open, test fix proposed |
| AG | first Postgres connection > 5 s at shard start, createDatabase retry WARN | 10739, 11141 | open, test config fix proposed |

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
- Devnet LSU (cni #7836/#7837, 10-30 20:28: synchronizerMigration.active.id 6 -> 7): both backup workflows read
  `.synchronizerMigration.active.id`, so the next run backs up and checks the new serial. First night after it:
  06:00 status stale (11117) and 04:00 backup failed ~07:10 (11118, dup), recovered by 11-01. Not the 18:00 gap of
  10915. Cause (slow first backup vs failing serial-7 component) needs the GHA logs; expect the same at each net's LSU.
  Map cni GHA run ids to times via splice run ids (`gh api repos/canton-network/splice/actions/runs/N`, ~5.2 M ids/h).
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
- Confirm: `grep -a -E 'advancing time by|(SubmitAndWait.*failed with|Resetting circuit|tripped).*SV=<sv>'`: a burst of
  LOCAL_VERDICT_TIMEOUT failures within 3 s after one `advancing time by PT1H`, with no `Resetting circuit breaker`
  in between, then the WARN; `moving to closed state` about 30 s later.
- Mechanism: each sim-clock step drops the in-flight SV automation commands (accepted by design at a jump; same
  jumps as 10475). LOCAL_VERDICT_TIMEOUT (category 2) is not ignored by SpliceCircuitBreaker, and the failure count
  is only reset on a call made 15 min (sim time) after the last failure, i.e. after the next jump; test config
  max-failures is 20 (`include/svs/_sv.conf`).
- Parent 11147 (2026-11-02, main 136e2e3160, ScanNewNetworkBackfillingTimeBasedIntegrationTest, 47 sv2 failures in one
  jump). Fix in the test: `circuitBreakers = CircuitBreakersConfig.never` for the SV apps, as LsuIntegrationTest does
  for sv4. No ignore pattern.

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
